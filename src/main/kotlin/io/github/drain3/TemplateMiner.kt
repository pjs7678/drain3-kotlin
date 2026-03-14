// SPDX-License-Identifier: MIT

package io.github.drain3

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.logging.Logger
import java.util.regex.Pattern
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

data class ExtractedParameter(val value: String, val maskName: String)

class TemplateMiner(
    private val persistenceHandler: PersistenceHandler? = null,
    config: TemplateMinerConfig? = null
) {
    val config: TemplateMinerConfig
    var drain: Drain
    val masker: LogMasker
    private val profiler: Profiler

    // LRU cache for parameter extraction regex
    private val parameterExtractionCache = object : LinkedHashMap<String, Pair<String, Map<String, String>>>(
        16, 0.75f, true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Pair<String, Map<String, String>>>?
        ): Boolean = size > this@TemplateMiner.config.parameterExtractionCacheCapacity
    }

    private var lastSaveTime: Long = System.currentTimeMillis()

    companion object {
        private val logger = Logger.getLogger(TemplateMiner::class.java.name)
        private const val CONFIG_FILENAME = "drain3.ini"

        private val objectMapper: ObjectMapper = jacksonObjectMapper()

        private val REGEX_SPECIAL = Regex("""([\\.*+?\^${'$'}\{\}\(\)\|\[\]])""")

        /** Escape special regex characters individually, like Python's re.escape */
        fun regexEscape(s: String): String = REGEX_SPECIAL.replace(s) { "\\${it.value}" }
    }

    init {
        logger.info("Starting Drain3 template miner")

        this.config = if (config != null) {
            config
        } else {
            logger.info("Loading configuration from $CONFIG_FILENAME")
            TemplateMinerConfig().apply { load(CONFIG_FILENAME) }
        }

        this.profiler = if (this.config.profilingEnabled) SimpleProfiler() else NullProfiler()

        val paramStr = this.config.maskPrefix + "*" + this.config.maskSuffix
        this.drain = Drain(
            depth = this.config.drainDepth,
            simTh = this.config.drainSimTh,
            maxChildren = this.config.drainMaxChildren,
            maxClusters = this.config.drainMaxClusters,
            extraDelimiters = this.config.drainExtraDelimiters,
            profiler = this.profiler,
            paramStr = paramStr,
            parametrizeNumericTokens = this.config.parametrizeNumericTokens
        )
        this.masker = LogMasker(this.config.maskingInstructions, this.config.maskPrefix, this.config.maskSuffix)

        if (persistenceHandler != null) {
            loadState()
        }
    }

    fun loadState() {
        logger.info("Checking for saved state")

        val state = persistenceHandler?.loadState()
        if (state == null) {
            logger.info("Saved state not found")
            return
        }

        val decompressed = if (config.snapshotCompressState) {
            val decoded = Base64.getDecoder().decode(state)
            val bais = ByteArrayInputStream(decoded)
            InflaterInputStream(bais).readAllBytes()
        } else {
            state
        }

        val loadedDrain: Drain = objectMapper.readValue(decompressed)

        // Restore state into current drain instance
        drain = Drain(
            depth = config.drainDepth,
            simTh = config.drainSimTh,
            maxChildren = config.drainMaxChildren,
            maxClusters = config.drainMaxClusters,
            extraDelimiters = config.drainExtraDelimiters,
            profiler = profiler,
            paramStr = config.maskPrefix + "*" + config.maskSuffix,
            parametrizeNumericTokens = config.parametrizeNumericTokens
        )
        // Copy loaded state
        drain.rootNode = loadedDrain.rootNode
        drain.clustersCounter = loadedDrain.clustersCounter
        // Re-populate id_to_cluster from the loaded drain's clusters
        for (cluster in loadedDrain.clusters) {
            drain.idToCluster[cluster.clusterId] = cluster
        }

        logger.info("Restored ${drain.clusters.size} clusters built from ${drain.getTotalClusterSize()} messages")
    }

    fun saveState(snapshotReason: String) {
        val stateBytes = objectMapper.writeValueAsBytes(drain)
        val finalState = if (config.snapshotCompressState) {
            val baos = ByteArrayOutputStream()
            DeflaterOutputStream(baos).use { it.write(stateBytes) }
            Base64.getEncoder().encode(baos.toByteArray())
        } else {
            stateBytes
        }

        logger.info(
            "Saving state of ${drain.clusters.size} clusters " +
                "with ${drain.getTotalClusterSize()} messages, ${finalState.size} bytes, " +
                "reason: $snapshotReason"
        )
        persistenceHandler?.saveState(finalState)
    }

    fun getSnapshotReason(changeType: String, clusterId: Int): String? {
        if (changeType != "none") {
            return "$changeType ($clusterId)"
        }

        val diffTimeMs = System.currentTimeMillis() - lastSaveTime
        if (diffTimeMs >= config.snapshotIntervalMinutes * 60 * 1000) {
            return "periodic"
        }

        return null
    }

    fun addLogMessage(logMessage: String): Map<String, Any> {
        profiler.startSection("total")

        profiler.startSection("mask")
        val maskedContent = masker.mask(logMessage)
        profiler.endSection()

        profiler.startSection("drain")
        val (cluster, changeType) = drain.addLogMessage(maskedContent)
        profiler.endSection("drain")

        val result = mapOf(
            "change_type" to changeType,
            "cluster_id" to cluster.clusterId,
            "cluster_size" to cluster.size,
            "template_mined" to cluster.getTemplate(),
            "cluster_count" to drain.clusters.size
        )

        if (persistenceHandler != null) {
            profiler.startSection("save_state")
            val snapshotReason = getSnapshotReason(changeType, cluster.clusterId)
            if (snapshotReason != null) {
                saveState(snapshotReason)
                lastSaveTime = System.currentTimeMillis()
            }
            profiler.endSection()
        }

        profiler.endSection("total")
        profiler.report(config.profilingReportSec)
        return result
    }

    fun match(logMessage: String, fullSearchStrategy: String = "never"): LogCluster? {
        val maskedContent = masker.mask(logMessage)
        return drain.match(maskedContent, fullSearchStrategy)
    }

    @Deprecated("Use extractParameters instead", replaceWith = ReplaceWith("extractParameters(logTemplate, logMessage, exactMatching = false)"))
    fun getParameterList(logTemplate: String, logMessage: String): List<String> {
        val extracted = extractParameters(logTemplate, logMessage, exactMatching = false) ?: return emptyList()
        return extracted.map { it.value }
    }

    fun extractParameters(
        logTemplate: String,
        logMessage: String,
        exactMatching: Boolean = true
    ): List<ExtractedParameter>? {
        var processedMessage = logMessage
        for (delimiter in config.drainExtraDelimiters) {
            processedMessage = processedMessage.replace(delimiter.toRegex(), " ")
        }

        val (templateRegex, paramGroupNameToMaskName) = getTemplateParameterExtractionRegex(
            logTemplate, exactMatching
        )

        val matcher = Pattern.compile(templateRegex).matcher(processedMessage)
        if (!matcher.matches()) return null

        val extractedParameters = mutableListOf<ExtractedParameter>()
        for ((groupName, maskName) in paramGroupNameToMaskName) {
            val value = try {
                matcher.group(groupName)
            } catch (_: IllegalArgumentException) {
                null
            }
            if (value != null) {
                extractedParameters.add(ExtractedParameter(value, maskName))
            }
        }

        return extractedParameters
    }

    private fun getTemplateParameterExtractionRegex(
        logTemplate: String,
        exactMatching: Boolean
    ): Pair<String, Map<String, String>> {
        // Check cache
        val cacheKey = "$logTemplate|$exactMatching"
        parameterExtractionCache[cacheKey]?.let { return it }

        val paramGroupNameToMaskName = mutableMapOf<String, String>()
        var paramNameCounter = 0

        fun getNextParamName(): String {
            return "p${paramNameCounter++}"
        }

        fun createCaptureRegex(maskName: String): String {
            val allowedPatterns = mutableListOf<String>()

            if (exactMatching) {
                val maskingInstructions = masker.instructionsByMaskName(maskName)
                for (mi in maskingInstructions) {
                    var pattern: String
                    val miGroups: Set<String>

                    if (mi is MaskingInstruction) {
                        miGroups = mi.regex.matcher("").let { m ->
                            // Get named groups - Java doesn't expose this directly,
                            // so we parse group names from the pattern
                            val groupNames = mutableSetOf<String>()
                            val groupPattern = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>")
                            val groupMatcher = groupPattern.matcher(mi.pattern)
                            while (groupMatcher.find()) {
                                groupNames.add(groupMatcher.group(1))
                            }
                            groupNames
                        }
                        pattern = mi.pattern
                    } else {
                        miGroups = emptySet()
                        pattern = ".+?"
                    }

                    for (groupName in miGroups) {
                        val paramGroupName = getNextParamName()

                        // Replace (?P=groupName) with (?P=paramGroupName) equivalent
                        pattern = pattern.replace("(?P=$groupName)", "\\k<$paramGroupName>")
                        // Replace (?P<groupName>) with (?<paramGroupName>)
                        pattern = pattern.replace("(?P<$groupName>", "(?<$paramGroupName>")
                        // Also handle Java-style named groups
                        pattern = pattern.replace("(?<$groupName>", "(?<$paramGroupName>")
                    }

                    // Support unnamed back-references in masks (simple cases only)
                    pattern = pattern.replace(Regex("""\\(?!0)\d{1,2}"""), "(?:.+?)")
                    allowedPatterns.add(pattern)
                }
            }

            if (!exactMatching || maskName == "*") {
                allowedPatterns.add(".+?")
            }

            val paramGroupName = getNextParamName()
            paramGroupNameToMaskName[paramGroupName] = maskName
            val joinedPatterns = allowedPatterns.joinToString("|")
            return "(?<$paramGroupName>$joinedPatterns)"
        }

        val maskNames = masker.maskNames.toMutableSet()
        maskNames.add("*") // the Drain catch-all mask

        val escapedPrefix = regexEscape(masker.maskPrefix)
        val escapedSuffix = regexEscape(masker.maskSuffix)
        var templateRegex = regexEscape(logTemplate)

        // Replace each mask name with a proper regex that captures it
        for (maskName in maskNames) {
            val searchStr = escapedPrefix + regexEscape(maskName) + escapedSuffix
            while (true) {
                val repStr = createCaptureRegex(maskName)
                // Literal string replacement (one occurrence at a time), matching Python's str.replace(s,r,1)
                val idx = templateRegex.indexOf(searchStr)
                if (idx < 0) break
                templateRegex = templateRegex.substring(0, idx) + repStr + templateRegex.substring(idx + searchStr.length)
            }
        }

        // Match messages with multiple spaces or other whitespace between tokens
        templateRegex = templateRegex.replace("\\ ", "\\s+")
        templateRegex = "^$templateRegex$"

        val result = templateRegex to paramGroupNameToMaskName
        parameterExtractionCache[cacheKey] = result
        return result
    }
}

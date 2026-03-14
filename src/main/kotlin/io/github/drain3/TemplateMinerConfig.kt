// SPDX-License-Identifier: MIT

package io.github.drain3

import java.io.FileInputStream
import java.io.FileNotFoundException
import java.util.Properties
import java.util.logging.Logger

class TemplateMinerConfig {
    var profilingEnabled: Boolean = false
    var profilingReportSec: Int = 60
    var snapshotIntervalMinutes: Int = 5
    var snapshotCompressState: Boolean = true
    var drainExtraDelimiters: List<String> = emptyList()
    var drainSimTh: Double = 0.4
    var drainDepth: Int = 4
    var drainMaxChildren: Int = 100
    var drainMaxClusters: Int? = null
    var maskingInstructions: List<MaskingInstruction> = emptyList()
    var maskPrefix: String = "<"
    var maskSuffix: String = ">"
    var parameterExtractionCacheCapacity: Int = 3000
    var parametrizeNumericTokens: Boolean = true

    companion object {
        private val logger = Logger.getLogger(TemplateMinerConfig::class.java.name)
    }

    /**
     * Load configuration from an INI-style properties file.
     *
     * Expected sections/keys (use dot notation, e.g. DRAIN.sim_th=0.4):
     * - PROFILING.enabled, PROFILING.report_sec
     * - SNAPSHOT.snapshot_interval_minutes, SNAPSHOT.compress_state
     * - DRAIN.extra_delimiters, DRAIN.sim_th, DRAIN.depth, DRAIN.max_children,
     *   DRAIN.max_clusters, DRAIN.parametrize_numeric_tokens
     * - MASKING.masking (JSON array), MASKING.mask_prefix, MASKING.mask_suffix,
     *   MASKING.parameter_extraction_cache_capacity
     *
     * For compatibility with Python's configparser INI format, this also supports
     * section headers like [DRAIN] with keys below them.
     */
    fun load(configFilename: String) {
        val props = Properties()
        try {
            // Try parsing as INI (Python configparser format)
            val iniProps = parseIniFile(configFilename)
            props.putAll(iniProps)
        } catch (e: FileNotFoundException) {
            logger.warning("config file not found: $configFilename")
            return
        }

        profilingEnabled = props.getProperty("PROFILING.enabled")?.toBoolean() ?: profilingEnabled
        profilingReportSec = props.getProperty("PROFILING.report_sec")?.toInt() ?: profilingReportSec

        snapshotIntervalMinutes = props.getProperty("SNAPSHOT.snapshot_interval_minutes")?.toInt()
            ?: snapshotIntervalMinutes
        snapshotCompressState = props.getProperty("SNAPSHOT.compress_state")?.toBoolean() ?: snapshotCompressState

        val extraDelimitersStr = props.getProperty("DRAIN.extra_delimiters")
        if (extraDelimitersStr != null) {
            drainExtraDelimiters = parseStringList(extraDelimitersStr)
        }

        drainSimTh = props.getProperty("DRAIN.sim_th")?.toDouble() ?: drainSimTh
        drainDepth = props.getProperty("DRAIN.depth")?.toInt() ?: drainDepth
        drainMaxChildren = props.getProperty("DRAIN.max_children")?.toInt() ?: drainMaxChildren
        val maxClustersStr = props.getProperty("DRAIN.max_clusters")
        if (maxClustersStr != null) {
            drainMaxClusters = maxClustersStr.toIntOrNull()
        }
        parametrizeNumericTokens = props.getProperty("DRAIN.parametrize_numeric_tokens")?.toBoolean()
            ?: parametrizeNumericTokens

        maskPrefix = props.getProperty("MASKING.mask_prefix") ?: maskPrefix
        maskSuffix = props.getProperty("MASKING.mask_suffix") ?: maskSuffix
        val cacheCap = props.getProperty("MASKING.parameter_extraction_cache_capacity")
        if (cacheCap != null) {
            parameterExtractionCacheCapacity = cacheCap.toInt()
        }

        val maskingStr = props.getProperty("MASKING.masking")
        if (maskingStr != null) {
            maskingInstructions = parseMaskingInstructions(maskingStr)
        }
    }

    private fun parseIniFile(filename: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var currentSection = ""
        FileInputStream(filename).bufferedReader().useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    currentSection = trimmed.substring(1, trimmed.length - 1)
                    continue
                }
                val eqIndex = trimmed.indexOf('=')
                if (eqIndex > 0) {
                    val key = trimmed.substring(0, eqIndex).trim()
                    val value = trimmed.substring(eqIndex + 1).trim()
                    result["$currentSection.$key"] = value
                }
            }
        }
        return result
    }

    private fun parseStringList(str: String): List<String> {
        // Parse Python-style list: ["a", "b"] or simple comma-separated
        val trimmed = str.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            val inner = trimmed.substring(1, trimmed.length - 1)
            if (inner.isBlank()) return emptyList()
            return inner.split(",").map {
                it.trim().removeSurrounding("\"").removeSurrounding("'")
            }
        }
        return if (trimmed.isEmpty()) emptyList()
        else trimmed.split(",").map { it.trim() }
    }

    private fun parseMaskingInstructions(json: String): List<MaskingInstruction> {
        // Simple JSON array parser for [{regex_pattern: ..., mask_with: ...}, ...]
        val instructions = mutableListOf<MaskingInstruction>()
        val trimmed = json.trim()
        if (trimmed == "[]" || trimmed.isEmpty()) return instructions

        // Use a basic approach: find all objects in the array
        val objectRegex = """\{[^}]+}""".toRegex()
        for (match in objectRegex.findAll(trimmed)) {
            val obj = match.value
            val regexPattern = extractJsonStringValue(obj, "regex_pattern") ?: continue
            val maskWith = extractJsonStringValue(obj, "mask_with") ?: continue
            instructions.add(MaskingInstruction(regexPattern, maskWith))
        }
        return instructions
    }

    private fun extractJsonStringValue(json: String, key: String): String? {
        // Match "key": "value" or "key" : "value"
        val patterns = listOf(
            """"$key"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex(),
            """'$key'\s*:\s*'((?:[^'\\]|\\.)*)'""".toRegex()
        )
        for (pattern in patterns) {
            val m = pattern.find(json)
            if (m != null) return m.groupValues[1]
        }
        return null
    }
}

// SPDX-License-Identifier: MIT
// Kotlin port of the Drain algorithm for log parsing.
// Based on https://github.com/logpai/Drain3
// Optimized for memory efficiency and throughput.

package io.github.drain3

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
class LogCluster(
    var logTemplateTokens: List<String>,
    val clusterId: Int
) {
    var size: Int = 1

    @JsonIgnore
    fun getTemplate(): String {
        val tokens = logTemplateTokens
        val n = tokens.size
        if (n == 0) return ""
        if (n == 1) return tokens[0]
        // Estimate capacity: tokens + spaces
        val sb = StringBuilder(n * 8)
        sb.append(tokens[0])
        for (i in 1 until n) {
            sb.append(' ').append(tokens[i])
        }
        return sb.toString()
    }

    override fun toString(): String =
        "ID=${clusterId.toString().padEnd(5)} : size=${size.toString().padEnd(10)}: ${getTemplate()}"
}

/**
 * LRU cache with O(1) non-evicting reads.
 *
 * Uses a dual-map design:
 * - readMap: plain HashMap for O(1) lookups that don't update LRU order
 * - lruMap: access-order LinkedHashMap for LRU tracking and eviction
 *
 * The original implementation used `cache.entries.find {}` which was O(n) — a critical bottleneck
 * since `get()` is called per candidate cluster in every `fastMatch`.
 */
class LogClusterCache(private val maxSize: Int) {
    private val readMap = HashMap<Int, LogCluster>(maxSize * 4 / 3 + 1)
    private val lruMap = object : LinkedHashMap<Int, LogCluster>(maxSize * 4 / 3 + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, LogCluster>?): Boolean {
            if (size > maxSize) {
                readMap.remove(eldest!!.key)
                return true
            }
            return false
        }
    }

    /** O(1) read without updating LRU order — safe for matching candidates. */
    fun get(key: Int): LogCluster? = readMap[key]

    operator fun set(key: Int, value: LogCluster) {
        lruMap[key] = value
        readMap[key] = value
    }

    /** Access that updates LRU order (touch). */
    fun touch(key: Int): LogCluster? = lruMap[key]

    operator fun contains(key: Int): Boolean = readMap.containsKey(key)

    val values: Collection<LogCluster> get() = readMap.values
    val keys: Set<Int> get() = readMap.keys
    val size: Int get() = readMap.size
}

class Node {
    var keyToChildNode: MutableMap<String, Node> = HashMap(4)
    var clusterIds: IntArray = EMPTY_INT_ARRAY

    fun addClusterId(id: Int) {
        val old = clusterIds
        val new = IntArray(old.size + 1)
        old.copyInto(new)
        new[old.size] = id
        clusterIds = new
    }

    @JsonIgnore
    fun replaceClusterIds(ids: List<Int>) {
        clusterIds = ids.toIntArray()
    }

    companion object {
        private val EMPTY_INT_ARRAY = IntArray(0)
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
class Drain(
    val depth: Int = 4,
    val simTh: Double = 0.4,
    val maxChildren: Int = 100,
    val maxClusters: Int? = null,
    val extraDelimiters: List<String> = emptyList(),
    @JsonIgnore val profiler: Profiler = NullProfiler(),
    val paramStr: String = "<*>",
    val parametrizeNumericTokens: Boolean = true
) {
    val maxNodeDepth: Int = depth - 2

    @JsonIgnore
    private var _idToClusterMap: MutableMap<Int, LogCluster>? = if (maxClusters == null) HashMap() else null
    @JsonIgnore
    private var _idToClusterCache: LogClusterCache? = if (maxClusters != null) LogClusterCache(maxClusters) else null

    var clustersCounter: Int = 0

    var rootNode: Node = Node()

    // Pre-compiled regex for tokenization
    @JsonIgnore
    private val whitespaceRegex = WHITESPACE_PATTERN

    // Cache small int-to-string conversions (token counts 0-255)
    @JsonIgnore
    private val intStringCache = INT_STRING_CACHE

    /** Serializable view of id-to-cluster mapping for Jackson. */
    @Suppress("unused")
    var clusterMap: Map<Int, LogCluster>
        get() = if (_idToClusterMap != null) _idToClusterMap!! else {
            val map = HashMap<Int, LogCluster>()
            _idToClusterCache?.values?.forEach { map[it.clusterId] = it }
            map
        }
        set(value) {
            if (_idToClusterMap == null && _idToClusterCache == null) {
                if (maxClusters == null) {
                    _idToClusterMap = HashMap()
                } else {
                    _idToClusterCache = LogClusterCache(maxClusters)
                }
            }
            for ((k, v) in value) {
                if (_idToClusterMap != null) _idToClusterMap!![k] = v
                else _idToClusterCache!![k] = v
            }
        }

    init {
        require(depth >= 3) { "depth argument must be at least 3" }
    }

    @get:JsonIgnore
    val clusters: Collection<LogCluster>
        get() = _idToClusterMap?.values ?: _idToClusterCache!!.values

    // Inline accessors — avoid inner class dispatch overhead
    private fun clusterGet(key: Int): LogCluster? =
        _idToClusterMap?.get(key) ?: _idToClusterCache?.get(key)

    private fun clusterSet(key: Int, value: LogCluster) {
        if (_idToClusterMap != null) _idToClusterMap!![key] = value
        else _idToClusterCache!![key] = value
    }

    private fun clusterTouch(key: Int) {
        _idToClusterCache?.touch(key)
    }

    private fun clusterContains(key: Int): Boolean =
        if (_idToClusterMap != null) _idToClusterMap!!.containsKey(key) else _idToClusterCache!!.contains(key)

    @get:JsonIgnore
    val idToCluster: IdToClusterAccessor = IdToClusterAccessor()

    inner class IdToClusterAccessor {
        fun get(key: Int): LogCluster? = clusterGet(key)
        operator fun set(key: Int, value: LogCluster) = clusterSet(key, value)
        fun touch(key: Int) = clusterTouch(key)
        operator fun contains(key: Int): Boolean = clusterContains(key)
        val values: Collection<LogCluster> get() = clusters
        val keys: Set<Int> get() = _idToClusterMap?.keys ?: _idToClusterCache!!.keys
        val size: Int get() = _idToClusterMap?.size ?: _idToClusterCache!!.size
    }

    companion object {
        private val WHITESPACE_PATTERN = "\\s+".toRegex()

        // Cache int-to-string for common token counts
        private val INT_STRING_CACHE = Array(256) { it.toString() }

        @JvmStatic
        fun hasNumbers(s: String): Boolean {
            for (i in s.indices) {
                if (s[i] in '0'..'9') return true
            }
            return false
        }

        private fun intToString(i: Int): String =
            if (i < 256) INT_STRING_CACHE[i] else i.toString()
    }

    fun treeSearch(rootNode: Node, tokens: List<String>, simTh: Double, includeParams: Boolean): LogCluster? {
        val tokenCount = tokens.size
        val curNodeInitial = rootNode.keyToChildNode[intToString(tokenCount)] ?: return null

        if (tokenCount == 0) {
            return clusterGet(curNodeInitial.clusterIds[0])
        }

        var curNode = curNodeInitial
        var curNodeDepth = 1
        for (token in tokens) {
            if (curNodeDepth >= maxNodeDepth) break
            if (curNodeDepth == tokenCount) break

            val keyToChild = curNode.keyToChildNode
            curNode = keyToChild[token]
                ?: keyToChild[paramStr]
                ?: return null

            curNodeDepth++
        }

        return fastMatch(curNode.clusterIds, tokens, simTh, includeParams)
    }

    fun addSeqToPrefixTree(rootNode: Node, cluster: LogCluster) {
        val tokens = cluster.logTemplateTokens
        val tokenCount = tokens.size
        val tokenCountStr = intToString(tokenCount)

        val firstLayerNode = rootNode.keyToChildNode.getOrPut(tokenCountStr) { Node() }
        var curNode = firstLayerNode

        if (tokenCount == 0) {
            curNode.clusterIds = intArrayOf(cluster.clusterId)
            return
        }

        var currentDepth = 1
        for (token in tokens) {
            if (currentDepth >= maxNodeDepth || currentDepth >= tokenCount) {
                // Clean up stale clusters + add new one
                val oldIds = curNode.clusterIds
                val valid = ArrayList<Int>(oldIds.size + 1)
                for (id in oldIds) {
                    if (clusterContains(id)) valid.add(id)
                }
                valid.add(cluster.clusterId)
                curNode.replaceClusterIds(valid)
                break
            }

            if (token !in curNode.keyToChildNode) {
                if (parametrizeNumericTokens && hasNumbers(token)) {
                    curNode = if (paramStr !in curNode.keyToChildNode) {
                        val newNode = Node()
                        curNode.keyToChildNode[paramStr] = newNode
                        newNode
                    } else {
                        curNode.keyToChildNode[paramStr]!!
                    }
                } else {
                    if (paramStr in curNode.keyToChildNode) {
                        curNode = if (curNode.keyToChildNode.size < maxChildren) {
                            val newNode = Node()
                            curNode.keyToChildNode[token] = newNode
                            newNode
                        } else {
                            curNode.keyToChildNode[paramStr]!!
                        }
                    } else {
                        val childCount = curNode.keyToChildNode.size
                        curNode = if (childCount + 1 < maxChildren) {
                            val newNode = Node()
                            curNode.keyToChildNode[token] = newNode
                            newNode
                        } else if (childCount + 1 == maxChildren) {
                            val newNode = Node()
                            curNode.keyToChildNode[paramStr] = newNode
                            newNode
                        } else {
                            curNode.keyToChildNode[paramStr]!!
                        }
                    }
                }
            } else {
                curNode = curNode.keyToChildNode[token]!!
            }

            currentDepth++
        }
    }

    /**
     * Calculate sequence distance without allocating Pair objects.
     * Returns similarity packed into high bits and paramCount in low bits of a Long.
     * Use [unpackSimilarity] and [unpackParamCount] to extract.
     */
    fun getSeqDistancePacked(seq1: List<String>, seq2: List<String>, includeParams: Boolean): Long {
        val n = seq1.size
        if (n == 0) return packResult(1.0, 0)

        var simTokens = 0
        var paramCount = 0

        for (i in 0 until n) {
            val t1 = seq1[i]
            if (t1 === paramStr || t1 == paramStr) { // identity check first
                paramCount++
                continue
            }
            if (t1 == seq2[i]) {
                simTokens++
            }
        }

        if (includeParams) simTokens += paramCount
        return packResult(simTokens.toDouble() / n, paramCount)
    }

    fun fastMatch(
        clusterIds: IntArray,
        tokens: List<String>,
        simTh: Double,
        includeParams: Boolean
    ): LogCluster? {
        var maxSim = -1.0
        var maxParamCount = -1
        var maxCluster: LogCluster? = null

        for (clusterId in clusterIds) {
            val cluster = clusterGet(clusterId) ?: continue
            val packed = getSeqDistancePacked(cluster.logTemplateTokens, tokens, includeParams)
            val curSim = unpackSimilarity(packed)
            val paramCount = unpackParamCount(packed)
            if (curSim > maxSim || (curSim == maxSim && paramCount > maxParamCount)) {
                maxSim = curSim
                maxParamCount = paramCount
                maxCluster = cluster
            }
        }

        return if (maxSim >= simTh) maxCluster else null
    }

    fun createTemplate(seq1: List<String>, seq2: List<String>): List<String> {
        val n = seq1.size
        val result = ArrayList<String>(n)
        for (i in 0 until n) {
            val t1 = seq1[i]
            val t2 = seq2[i]
            result.add(if (t1 != t2) paramStr else t2)
        }
        return result
    }

    fun printTree(out: Appendable = System.out, maxClusters: Int = 5) {
        printNode("root", rootNode, 0, out, maxClusters)
    }

    private fun printNode(token: String, node: Node, depth: Int, out: Appendable, maxClusters: Int) {
        val indent = "\t".repeat(depth)
        val outStr = when (depth) {
            0 -> "$indent<$token>"
            1 -> "$indent<L=$token>"
            else -> "$indent\"$token\""
        }

        val clusterInfo = if (node.clusterIds.isNotEmpty()) " (cluster_count=${node.clusterIds.size})" else ""
        out.appendLine("$outStr$clusterInfo")

        for ((childToken, child) in node.keyToChildNode) {
            printNode(childToken, child, depth + 1, out, maxClusters)
        }

        val limit = minOf(node.clusterIds.size, maxClusters)
        for (i in 0 until limit) {
            val cluster = clusterGet(node.clusterIds[i]) ?: continue
            out.appendLine("${"\t".repeat(depth + 1)}$cluster")
        }
    }

    fun getContentAsTokens(content: String): List<String> {
        var processed = content.trim()
        for (delimiter in extraDelimiters) {
            processed = processed.replace(delimiter, " ")
        }
        return processed.split(whitespaceRegex).filterTo(ArrayList(16)) { it.isNotEmpty() }
    }

    fun addLogMessage(content: String): Pair<LogCluster, String> {
        val contentTokens = getContentAsTokens(content)

        profiler.startSection("tree_search")
        var matchCluster = treeSearch(rootNode, contentTokens, simTh, false)
        profiler.endSection()

        val updateType: String

        if (matchCluster == null) {
            profiler.startSection("create_cluster")
            clustersCounter++
            val clusterId = clustersCounter
            matchCluster = LogCluster(contentTokens, clusterId)
            clusterSet(clusterId, matchCluster)
            addSeqToPrefixTree(rootNode, matchCluster)
            updateType = "cluster_created"
        } else {
            profiler.startSection("cluster_exist")
            val newTemplateTokens = createTemplate(contentTokens, matchCluster.logTemplateTokens)
            if (templatesEqual(newTemplateTokens, matchCluster.logTemplateTokens)) {
                updateType = "none"
            } else {
                matchCluster.logTemplateTokens = newTemplateTokens
                updateType = "cluster_template_changed"
            }
            matchCluster.size++
            clusterTouch(matchCluster.clusterId)
        }

        profiler.endSection()
        return matchCluster to updateType
    }

    /** Fast template equality check — avoids List.equals overhead by checking identity first. */
    private fun templatesEqual(a: List<String>, b: List<String>): Boolean {
        if (a === b) return true
        val n = a.size
        if (n != b.size) return false
        for (i in 0 until n) {
            if (a[i] != b[i]) return false
        }
        return true
    }

    fun getClustersIdsForSeqLen(seqLen: Int): IntArray {
        val curNode = rootNode.keyToChildNode[intToString(seqLen)] ?: return IntArray(0)
        val target = ArrayList<Int>()
        appendClustersRecursive(curNode, target)
        return target.toIntArray()
    }

    private fun appendClustersRecursive(node: Node, idList: MutableList<Int>) {
        for (id in node.clusterIds) idList.add(id)
        for (childNode in node.keyToChildNode.values) {
            appendClustersRecursive(childNode, idList)
        }
    }

    fun match(content: String, fullSearchStrategy: String = "never"): LogCluster? {
        require(fullSearchStrategy in SEARCH_STRATEGIES) {
            "fullSearchStrategy must be one of: always, never, fallback"
        }

        val requiredSimTh = 1.0
        val contentTokens = getContentAsTokens(content)

        fun fullSearch(): LogCluster? {
            val allIds = getClustersIdsForSeqLen(contentTokens.size)
            return fastMatch(allIds, contentTokens, requiredSimTh, includeParams = true)
        }

        if (fullSearchStrategy == "always") return fullSearch()

        val matchCluster = treeSearch(rootNode, contentTokens, requiredSimTh, includeParams = true)
        if (matchCluster != null) return matchCluster

        if (fullSearchStrategy == "never") return null

        return fullSearch()
    }

    fun getTotalClusterSize(): Int = clusters.sumOf { it.size }
}

// Pack double similarity + int paramCount into a Long to avoid Pair allocation in hot path
private fun packResult(similarity: Double, paramCount: Int): Long {
    val simBits = java.lang.Double.doubleToRawLongBits(similarity)
    // Store paramCount in low 32 bits, similarity bits shifted
    // Since we only compare, we can use a simpler scheme:
    // high 32 bits = float bits of similarity, low 32 bits = paramCount
    val simFloat = similarity.toFloat()
    val simIntBits = java.lang.Float.floatToRawIntBits(simFloat)
    return (simIntBits.toLong() shl 32) or (paramCount.toLong() and 0xFFFFFFFFL)
}

internal fun unpackSimilarity(packed: Long): Double =
    java.lang.Float.intBitsToFloat((packed ushr 32).toInt()).toDouble()

internal fun unpackParamCount(packed: Long): Int = packed.toInt()

private val SEARCH_STRATEGIES = setOf("always", "never", "fallback")

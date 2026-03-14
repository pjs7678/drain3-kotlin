// SPDX-License-Identifier: MIT
// Kotlin port of the Drain algorithm for log parsing.
// Based on https://github.com/logpai/Drain3

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
    fun getTemplate(): String = logTemplateTokens.joinToString(" ")

    override fun toString(): String =
        "ID=${clusterId.toString().padEnd(5)} : size=${size.toString().padEnd(10)}: ${getTemplate()}"
}

/**
 * LRU cache that allows callers to conditionally skip cache eviction algorithm when accessing elements.
 * Uses LinkedHashMap with access order.
 */
class LogClusterCache(private val maxSize: Int) {
    private val cache = object : LinkedHashMap<Int, LogCluster>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, LogCluster>?): Boolean {
            return size > maxSize
        }
    }

    /**
     * Get without updating LRU order (read-only access for matching).
     */
    fun get(key: Int): LogCluster? = cache.entries.find { it.key == key }?.value

    operator fun set(key: Int, value: LogCluster) {
        cache[key] = value
    }

    /**
     * Access that updates LRU order (touch).
     */
    fun touch(key: Int): LogCluster? = cache[key]

    operator fun contains(key: Int): Boolean = cache.containsKey(key)

    val values: Collection<LogCluster> get() = cache.values
    val keys: Set<Int> get() = cache.keys
    val size: Int get() = cache.size

    fun isEmpty(): Boolean = cache.isEmpty()
    fun isNotEmpty(): Boolean = cache.isNotEmpty()
}

class Node {
    val keyToChildNode: MutableMap<String, Node> = mutableMapOf()
    var clusterIds: MutableList<Int> = mutableListOf()
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

    // Plain map when maxClusters is null, LRU cache otherwise
    @JsonIgnore
    private var _idToClusterMap: MutableMap<Int, LogCluster>? = if (maxClusters == null) mutableMapOf() else null
    @JsonIgnore
    private var _idToClusterCache: LogClusterCache? = if (maxClusters != null) LogClusterCache(maxClusters) else null

    var clustersCounter: Int = 0

    var rootNode: Node = Node()

    /** Serializable view of id-to-cluster mapping for Jackson. */
    @Suppress("unused")
    var clusterMap: Map<Int, LogCluster>
        get() = if (_idToClusterMap != null) _idToClusterMap!! else {
            val map = mutableMapOf<Int, LogCluster>()
            _idToClusterCache?.values?.forEach { map[it.clusterId] = it }
            map
        }
        set(value) {
            // Called during deserialization — repopulate internal storage
            if (_idToClusterMap == null && _idToClusterCache == null) {
                // Both null during deserialization, initialize based on maxClusters
                if (maxClusters == null) {
                    _idToClusterMap = mutableMapOf()
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

    @get:JsonIgnore
    val idToCluster: IdToClusterAccessor = IdToClusterAccessor()

    /**
     * Provides a unified accessor for both map and cache storage modes.
     */
    inner class IdToClusterAccessor {
        fun get(key: Int): LogCluster? {
            return _idToClusterMap?.get(key) ?: _idToClusterCache?.get(key)
        }

        operator fun set(key: Int, value: LogCluster) {
            if (_idToClusterMap != null) {
                _idToClusterMap!![key] = value
            } else {
                _idToClusterCache!![key] = value
            }
        }

        fun touch(key: Int) {
            if (_idToClusterCache != null) {
                _idToClusterCache!!.touch(key)
            }
            // no-op for plain map
        }

        operator fun contains(key: Int): Boolean {
            return if (_idToClusterMap != null) key in _idToClusterMap!! else key in _idToClusterCache!!
        }

        val values: Collection<LogCluster>
            get() = _idToClusterMap?.values ?: _idToClusterCache!!.values

        val keys: Set<Int>
            get() = _idToClusterMap?.keys ?: _idToClusterCache!!.keys

        val size: Int
            get() = _idToClusterMap?.size ?: _idToClusterCache!!.size
    }

    companion object {
        fun hasNumbers(s: String): Boolean = s.any { it.isDigit() }
    }

    fun treeSearch(rootNode: Node, tokens: List<String>, simTh: Double, includeParams: Boolean): LogCluster? {
        val tokenCount = tokens.size
        var curNode = rootNode.keyToChildNode[tokenCount.toString()] ?: return null

        // handle case of empty log string
        if (tokenCount == 0) {
            return idToCluster.get(curNode.clusterIds[0])
        }

        // find the leaf node for this log
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
        val tokenCount = cluster.logTemplateTokens.size
        val tokenCountStr = tokenCount.toString()

        val firstLayerNode = rootNode.keyToChildNode.getOrPut(tokenCountStr) { Node() }
        var curNode = firstLayerNode

        // handle case of empty log string
        if (tokenCount == 0) {
            curNode.clusterIds = mutableListOf(cluster.clusterId)
            return
        }

        var currentDepth = 1
        for (token in cluster.logTemplateTokens) {
            if (currentDepth >= maxNodeDepth || currentDepth >= tokenCount) {
                // clean up stale clusters before adding a new one
                val newClusterIds = curNode.clusterIds
                    .filter { it in idToCluster }
                    .toMutableList()
                newClusterIds.add(cluster.clusterId)
                curNode.clusterIds = newClusterIds
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

    fun getSeqDistance(seq1: List<String>, seq2: List<String>, includeParams: Boolean): Pair<Double, Int> {
        require(seq1.size == seq2.size)

        if (seq1.isEmpty()) return 1.0 to 0

        var simTokens = 0
        var paramCount = 0

        for ((token1, token2) in seq1.zip(seq2)) {
            if (token1 == paramStr) {
                paramCount++
                continue
            }
            if (token1 == token2) {
                simTokens++
            }
        }

        if (includeParams) {
            simTokens += paramCount
        }

        val retVal = simTokens.toDouble() / seq1.size
        return retVal to paramCount
    }

    fun fastMatch(
        clusterIds: List<Int>,
        tokens: List<String>,
        simTh: Double,
        includeParams: Boolean
    ): LogCluster? {
        var maxSim = -1.0
        var maxParamCount = -1
        var maxCluster: LogCluster? = null

        for (clusterId in clusterIds) {
            val cluster = idToCluster.get(clusterId) ?: continue
            val (curSim, paramCount) = getSeqDistance(cluster.logTemplateTokens, tokens, includeParams)
            if (curSim > maxSim || (curSim == maxSim && paramCount > maxParamCount)) {
                maxSim = curSim
                maxParamCount = paramCount
                maxCluster = cluster
            }
        }

        return if (maxSim >= simTh) maxCluster else null
    }

    fun createTemplate(seq1: List<String>, seq2: List<String>): List<String> {
        require(seq1.size == seq2.size)
        return seq1.zip(seq2).map { (t1, t2) -> if (t1 != t2) paramStr else t2 }
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

        for (cid in node.clusterIds.take(maxClusters)) {
            val cluster = idToCluster.get(cid) ?: continue
            out.appendLine("${"\t".repeat(depth + 1)}$cluster")
        }
    }

    fun getContentAsTokens(content: String): List<String> {
        var processed = content.trim()
        for (delimiter in extraDelimiters) {
            processed = processed.replace(delimiter, " ")
        }
        return processed.split("\\s+".toRegex()).filter { it.isNotEmpty() }
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
            idToCluster[clusterId] = matchCluster
            addSeqToPrefixTree(rootNode, matchCluster)
            updateType = "cluster_created"
        } else {
            profiler.startSection("cluster_exist")
            val newTemplateTokens = createTemplate(contentTokens, matchCluster.logTemplateTokens)
            if (newTemplateTokens == matchCluster.logTemplateTokens) {
                updateType = "none"
            } else {
                matchCluster.logTemplateTokens = newTemplateTokens
                updateType = "cluster_template_changed"
            }
            matchCluster.size++
            // Touch cluster to update its state in the cache
            idToCluster.touch(matchCluster.clusterId)
        }

        profiler.endSection()
        return matchCluster to updateType
    }

    fun getClustersIdsForSeqLen(seqLen: Int): List<Int> {
        fun appendClustersRecursive(node: Node, idList: MutableList<Int>) {
            idList.addAll(node.clusterIds)
            for (childNode in node.keyToChildNode.values) {
                appendClustersRecursive(childNode, idList)
            }
        }

        val curNode = rootNode.keyToChildNode[seqLen.toString()] ?: return emptyList()
        val target = mutableListOf<Int>()
        appendClustersRecursive(curNode, target)
        return target
    }

    fun match(content: String, fullSearchStrategy: String = "never"): LogCluster? {
        require(fullSearchStrategy in listOf("always", "never", "fallback")) {
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

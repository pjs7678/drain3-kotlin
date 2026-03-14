package io.github.drain3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DrainTest {

    @Test
    fun `basic log clustering`() {
        val drain = Drain(depth = 4, simTh = 0.4)

        val (cluster1, type1) = drain.addLogMessage("User john logged in from 192.168.1.1")
        assertEquals("cluster_created", type1)
        assertEquals(1, cluster1.clusterId)

        val (cluster2, type2) = drain.addLogMessage("User jane logged in from 10.0.0.1")
        assertEquals("cluster_template_changed", type2)
        assertEquals(1, cluster2.clusterId) // same cluster
        assertEquals(2, cluster2.size)

        // Template should have wildcards for varying parts
        assertTrue(cluster2.getTemplate().contains("<*>"))
    }

    @Test
    fun `different log patterns create different clusters`() {
        val drain = Drain(depth = 4, simTh = 0.4)

        val (c1, _) = drain.addLogMessage("Connection established to host server1")
        val (c2, _) = drain.addLogMessage("Error: file not found at path /tmp/data")

        assertTrue(c1.clusterId != c2.clusterId)
        assertEquals(2, drain.clusters.size)
    }

    @Test
    fun `match returns existing cluster`() {
        val drain = Drain(depth = 4, simTh = 0.4)
        drain.addLogMessage("User john logged in from 192.168.1.1")
        drain.addLogMessage("User jane logged in from 10.0.0.1")

        val matched = drain.match("User bob logged in from 172.16.0.1")
        assertNotNull(matched)
        assertEquals(1, matched.clusterId)
    }

    @Test
    fun `match returns null for unknown pattern`() {
        val drain = Drain(depth = 4, simTh = 0.4)
        drain.addLogMessage("User john logged in from 192.168.1.1")

        val matched = drain.match("Something completely different happened here today")
        assertNull(matched)
    }

    @Test
    fun `extra delimiters are handled`() {
        val drain = Drain(depth = 4, simTh = 0.4, extraDelimiters = listOf("_", "="))

        val tokens = drain.getContentAsTokens("key=value_test")
        assertEquals(listOf("key", "value", "test"), tokens)
    }

    @Test
    fun `empty log message`() {
        val drain = Drain(depth = 4, simTh = 0.4)
        val (cluster, type) = drain.addLogMessage("")
        assertEquals("cluster_created", type)
        assertEquals("", cluster.getTemplate())
    }

    @Test
    fun `LogClusterCache evicts oldest`() {
        val cache = LogClusterCache(2)
        cache[1] = LogCluster(listOf("a", "b"), 1)
        cache[2] = LogCluster(listOf("c", "d"), 2)
        cache[3] = LogCluster(listOf("e", "f"), 3)

        assertNull(cache.get(1)) // evicted
        assertNotNull(cache.get(2))
        assertNotNull(cache.get(3))
    }

    @Test
    fun `total cluster size`() {
        val drain = Drain(depth = 4, simTh = 0.4)
        drain.addLogMessage("msg A 1")
        drain.addLogMessage("msg A 2")
        drain.addLogMessage("msg B foo")

        assertEquals(3, drain.getTotalClusterSize())
    }
}

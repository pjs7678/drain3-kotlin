package io.github.drain3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TemplateMinerTest {

    private fun createConfig(): TemplateMinerConfig {
        return TemplateMinerConfig().apply {
            maskingInstructions = listOf(
                MaskingInstruction("""((?<=[^A-Za-z0-9])|^)(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})((?=[^A-Za-z0-9])|$)""", "IP"),
                MaskingInstruction("""((?<=[^A-Za-z0-9])|^)([\-+]?\d+)((?=[^A-Za-z0-9])|$)""", "NUM")
            )
        }
    }

    @Test
    fun `basic template mining with masking`() {
        val config = createConfig()
        val miner = TemplateMiner(config = config)

        val result1 = miner.addLogMessage("Connected to 192.168.1.1 port 8080")
        assertEquals("cluster_created", result1["change_type"])

        val result2 = miner.addLogMessage("Connected to 10.0.0.1 port 9090")
        // Should match the same cluster since IPs and ports are masked
        assertEquals(result1["cluster_id"], result2["cluster_id"])
    }

    @Test
    fun `match after mining`() {
        val config = createConfig()
        val miner = TemplateMiner(config = config)

        miner.addLogMessage("Connected to 192.168.1.1 port 8080")
        miner.addLogMessage("Connected to 10.0.0.1 port 9090")

        val matched = miner.match("Connected to 172.16.0.1 port 443")
        assertNotNull(matched)
    }

    @Test
    fun `persistence save and load`() {
        val config = TemplateMinerConfig().apply {
            snapshotCompressState = false
        }
        val persistence = MemoryBufferPersistence()
        val miner = TemplateMiner(persistenceHandler = persistence, config = config)

        miner.addLogMessage("User alice logged in")
        miner.addLogMessage("User bob logged in")
        miner.saveState("test")

        assertNotNull(persistence.state)

        // Create a new miner that loads from persistence
        val miner2 = TemplateMiner(persistenceHandler = persistence, config = config)
        assertEquals(1, miner2.drain.clusters.size)
    }

    @Test
    fun `masking applies correctly`() {
        val masker = LogMasker(
            listOf(
                MaskingInstruction("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""", "IP")
            ),
            "<", ">"
        )

        val result = masker.mask("Connection from 192.168.1.1 established")
        assertEquals("Connection from <IP> established", result)
    }

    @Test
    fun `extract parameters`() {
        val config = TemplateMinerConfig()
        val miner = TemplateMiner(config = config)

        miner.addLogMessage("User john logged in")
        miner.addLogMessage("User jane logged in")

        val cluster = miner.drain.clusters.first()
        val template = cluster.getTemplate()
        assertTrue(template.contains("<*>"))

        val params = miner.extractParameters(template, "User bob logged in", exactMatching = false)
        assertNotNull(params)
        assertTrue(params.isNotEmpty())
        assertEquals("bob", params[0].value)
    }
}

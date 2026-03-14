package io.github.drain3

import kotlin.test.Test
import kotlin.test.assertTrue

class BenchmarkTest {

    private fun createMiner(): TemplateMiner {
        val config = TemplateMinerConfig().apply {
            maskingInstructions = listOf(
                MaskingInstruction("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""", "IP"),
                MaskingInstruction("""((?<=[^A-Za-z0-9])|^)([\-+]?\d+)((?=[^A-Za-z0-9])|$)""", "NUM")
            )
        }
        return TemplateMiner(config = config)
    }

    private val sampleLogs = listOf(
        "User john logged in from 192.168.1.1",
        "User jane logged in from 10.0.0.2",
        "Connection established to host server1 port 8080",
        "Connection established to host server2 port 9090",
        "Error: file not found at path /tmp/data.txt",
        "Error: file not found at path /var/log/app.log",
        "Request completed in 150 ms with status 200",
        "Request completed in 300 ms with status 404",
        "Timeout waiting for response from 10.0.0.5 after 30 seconds",
        "Timeout waiting for response from 192.168.1.100 after 60 seconds",
        "Memory usage at 85 percent threshold reached",
        "Memory usage at 92 percent threshold reached",
        "Disk /dev/sda1 usage 78 percent",
        "Disk /dev/sdb2 usage 91 percent",
        "Process worker-1 started with pid 12345",
        "Process worker-2 started with pid 67890",
    )

    @Test
    fun `throughput benchmark`() {
        val miner = createMiner()
        val messageCount = 100_000

        // Warm up
        for (i in 0 until 1000) {
            miner.addLogMessage(sampleLogs[i % sampleLogs.size])
        }

        val miner2 = createMiner()
        val start = System.nanoTime()
        for (i in 0 until messageCount) {
            miner2.addLogMessage(sampleLogs[i % sampleLogs.size])
        }
        val elapsed = System.nanoTime() - start
        val msgsPerSec = messageCount * 1_000_000_000L / elapsed
        val usPerMsg = elapsed / 1000.0 / messageCount

        println("=== Throughput Benchmark ===")
        println("Messages:     $messageCount")
        println("Elapsed:      ${elapsed / 1_000_000} ms")
        println("Throughput:   $msgsPerSec msgs/sec")
        println("Latency:      %.2f us/msg".format(usPerMsg))
        println("Clusters:     ${miner2.drain.clusters.size}")

        assertTrue(msgsPerSec > 50_000, "Expected >50k msgs/sec, got $msgsPerSec")
    }

    @Test
    fun `match benchmark`() {
        val miner = createMiner()
        // Train
        for (log in sampleLogs) {
            miner.addLogMessage(log)
        }

        val matchCount = 100_000
        val start = System.nanoTime()
        for (i in 0 until matchCount) {
            miner.match(sampleLogs[i % sampleLogs.size])
        }
        val elapsed = System.nanoTime() - start
        val msgsPerSec = matchCount * 1_000_000_000L / elapsed
        val usPerMsg = elapsed / 1000.0 / matchCount

        println("=== Match Benchmark ===")
        println("Matches:      $matchCount")
        println("Elapsed:      ${elapsed / 1_000_000} ms")
        println("Throughput:   $msgsPerSec msgs/sec")
        println("Latency:      %.2f us/msg".format(usPerMsg))

        assertTrue(msgsPerSec > 100_000, "Expected >100k matches/sec, got $msgsPerSec")
    }
}

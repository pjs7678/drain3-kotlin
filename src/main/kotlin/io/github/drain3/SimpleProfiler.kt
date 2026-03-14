// SPDX-License-Identifier: Apache-2.0
// Based on https://github.com/davidohana/SimpleProfiler

package io.github.drain3

interface Profiler {
    fun startSection(sectionName: String)
    fun endSection(sectionName: String = "")
    fun report(periodSec: Int = 30)
}

class NullProfiler : Profiler {
    override fun startSection(sectionName: String) {}
    override fun endSection(sectionName: String) {}
    override fun report(periodSec: Int) {}
}

class SimpleProfiler(
    private val resetAfterSampleCount: Int = 0,
    private val enclosingSectionName: String = "total",
    private val printer: (String) -> Unit = ::println,
    private val reportSec: Int = 30
) : Profiler {

    private val sectionToStats = mutableMapOf<String, ProfiledSectionStats>()
    private var lastReportTimestampSec = System.currentTimeMillis() / 1000.0
    private var lastStartedSectionName = ""

    override fun startSection(sectionName: String) {
        require(sectionName.isNotEmpty()) { "Section name is empty" }
        lastStartedSectionName = sectionName

        val section = sectionToStats.getOrPut(sectionName) { ProfiledSectionStats(sectionName) }

        require(section.startTimeSec == 0.0) { "Section $sectionName is already started" }
        section.startTimeSec = System.currentTimeMillis() / 1000.0
    }

    override fun endSection(sectionName: String) {
        val now = System.currentTimeMillis() / 1000.0
        val resolvedName = sectionName.ifEmpty { lastStartedSectionName }

        require(resolvedName.isNotEmpty()) { "Neither section name is specified nor a section is started" }

        val section = sectionToStats[resolvedName]
            ?: throw IllegalArgumentException("Section $resolvedName does not exist")

        require(section.startTimeSec != 0.0) { "Section $resolvedName was not started" }

        val tookSec = now - section.startTimeSec
        if (resetAfterSampleCount > 0 && resetAfterSampleCount == section.sampleCount) {
            section.sampleCountBatch = 0
            section.totalTimeSecBatch = 0.0
        }

        section.sampleCount++
        section.totalTimeSec += tookSec
        section.sampleCountBatch++
        section.totalTimeSecBatch += tookSec
        section.startTimeSec = 0.0
    }

    override fun report(periodSec: Int) {
        val now = System.currentTimeMillis() / 1000.0
        if (now - lastReportTimestampSec < periodSec) return

        val enclosingTimeSec = if (enclosingSectionName.isNotEmpty()) {
            sectionToStats[enclosingSectionName]?.totalTimeSec ?: 0.0
        } else 0.0

        val includeBatchRates = resetAfterSampleCount > 0
        val sortedSections = sectionToStats.values.sortedByDescending { it.totalTimeSec }
        val text = sortedSections.joinToString(System.lineSeparator()) {
            it.toString(enclosingTimeSec, includeBatchRates)
        }
        printer(text)

        lastReportTimestampSec = now
    }
}

class ProfiledSectionStats(
    val sectionName: String,
    var startTimeSec: Double = 0.0,
    var sampleCount: Int = 0,
    var totalTimeSec: Double = 0.0,
    var sampleCountBatch: Int = 0,
    var totalTimeSecBatch: Double = 0.0
) {
    fun toString(enclosingTimeSec: Double, includeBatchRates: Boolean): String {
        var tookSecText = "%8.2f s".format(totalTimeSec)
        if (enclosingTimeSec > 0) {
            tookSecText += " (%6.2f%%)".format(100 * totalTimeSec / enclosingTimeSec)
        }

        var msPerKSamples = if (sampleCount > 0) {
            "%7.2f".format(1_000_000.0 * totalTimeSec / sampleCount)
        } else "    N/A"

        var samplesPerSec = if (totalTimeSec > 0) {
            "%,15.2f".format(sampleCount / totalTimeSec)
        } else "            N/A"

        if (includeBatchRates) {
            msPerKSamples += if (sampleCountBatch > 0) {
                " (%7.2f)".format(1_000_000.0 * totalTimeSecBatch / sampleCountBatch)
            } else " (    N/A)"

            samplesPerSec += if (totalTimeSecBatch > 0) {
                " (%,15.2f)".format(sampleCountBatch / totalTimeSecBatch)
            } else " (            N/A)"
        }

        return "%-15s: took %s, %,10d samples, %s ms / 1000 samples, %s hz".format(
            sectionName, tookSecText, sampleCount, msPerKSamples, samplesPerSec
        )
    }
}

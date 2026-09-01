package com.sih26168.idr.fusion

import org.junit.Assert.assertEquals
import org.junit.Test

class RunningStatsTest {

    private val tolerance = 1e-9

    @Test
    fun `no samples yet reports zero mean and zero std`() {
        val stats = RunningStats()
        assertEquals(0.0, stats.mean(), tolerance)
        assertEquals(0.0, stats.populationStdDev(), tolerance)
        assertEquals(0, stats.sampleCount)
    }

    @Test
    fun `single sample is its own mean with zero std`() {
        val stats = RunningStats()
        stats.accumulate(5.0)
        assertEquals(5.0, stats.mean(), tolerance)
        assertEquals(0.0, stats.populationStdDev(), tolerance)
        assertEquals(1, stats.sampleCount)
    }

    @Test
    fun `matches numpy population mean and std for a known dataset`() {
        // [2, 4, 4, 4, 5, 5, 7, 9] -- numpy.mean() = 5.0, numpy.std() (ddof=0) = 2.0, a textbook example.
        val stats = RunningStats()
        listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0).forEach { stats.accumulate(it) }
        assertEquals(5.0, stats.mean(), tolerance)
        assertEquals(2.0, stats.populationStdDev(), 1e-9)
        assertEquals(8, stats.sampleCount)
    }

    @Test
    fun `constant stream has zero standard deviation`() {
        val stats = RunningStats()
        repeat(20) { stats.accumulate(3.5) }
        assertEquals(3.5, stats.mean(), tolerance)
        assertEquals(0.0, stats.populationStdDev(), tolerance)
    }

    @Test
    fun `reset clears accumulated state`() {
        val stats = RunningStats()
        stats.accumulate(100.0)
        stats.accumulate(200.0)
        stats.reset()
        assertEquals(0.0, stats.mean(), tolerance)
        assertEquals(0.0, stats.populationStdDev(), tolerance)
        assertEquals(0, stats.sampleCount)
        // And it behaves like a fresh instance afterward.
        stats.accumulate(10.0)
        assertEquals(10.0, stats.mean(), tolerance)
    }
}

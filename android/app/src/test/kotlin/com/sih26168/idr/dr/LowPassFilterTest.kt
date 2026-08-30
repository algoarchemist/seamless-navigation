package com.sih26168.idr.dr

import org.junit.Assert.assertEquals
import org.junit.Test

class LowPassFilterTest {

    private val tolerance = 1e-9

    @Test
    fun `first sample passes through unfiltered`() {
        val filter = LowPassFilter(cutoffHz = 2.0)
        assertEquals(5.0, filter.filter(value = 5.0, dtSeconds = 0.1), tolerance)
    }

    @Test
    fun `zero or negative dt resets to the raw value, same guard as BaselinePhysicsIntegrator`() {
        val filter = LowPassFilter(cutoffHz = 2.0)
        filter.filter(value = 5.0, dtSeconds = 0.1)
        assertEquals(9.0, filter.filter(value = 9.0, dtSeconds = 0.0), tolerance)
        assertEquals(3.0, filter.filter(value = 3.0, dtSeconds = -0.1), tolerance)
    }

    @Test
    fun `a constant input stays constant, a low-pass filter must not distort DC`() {
        val filter = LowPassFilter(cutoffHz = 2.0)
        var output = 0.0
        repeat(50) { output = filter.filter(value = 4.0, dtSeconds = 0.1) }
        assertEquals(4.0, output, 1e-6)
    }

    @Test
    fun `output moves toward a step input but does not jump there in one sample`() {
        val filter = LowPassFilter(cutoffHz = 2.0)
        filter.filter(value = 0.0, dtSeconds = 0.1) // establish baseline at 0
        val afterOneStep = filter.filter(value = 10.0, dtSeconds = 0.1)
        // Must move toward 10 but not reach it immediately.
        assert(afterOneStep > 0.0 && afterOneStep < 10.0) {
            "expected 0 < afterOneStep < 10, was $afterOneStep"
        }
    }

    @Test
    fun `a higher cutoff frequency tracks a step input faster`() {
        val slowFilter = LowPassFilter(cutoffHz = 0.5)
        val fastFilter = LowPassFilter(cutoffHz = 5.0)
        slowFilter.filter(value = 0.0, dtSeconds = 0.1)
        fastFilter.filter(value = 0.0, dtSeconds = 0.1)
        val slowResponse = slowFilter.filter(value = 10.0, dtSeconds = 0.1)
        val fastResponse = fastFilter.filter(value = 10.0, dtSeconds = 0.1)
        assert(fastResponse > slowResponse) {
            "expected the higher-cutoff filter to track the step faster: fast=$fastResponse slow=$slowResponse"
        }
    }

    @Test
    fun `oscillating high-frequency noise around zero is attenuated toward zero`() {
        val filter = LowPassFilter(cutoffHz = 1.0)
        var output = 0.0
        // Alternating +1/-1 at a fast rate relative to the 1Hz cutoff —
        // classic vibration-noise shape — should average out near zero,
        // not track the full +-1 swing.
        repeat(100) { i ->
            val value = if (i % 2 == 0) 1.0 else -1.0
            output = filter.filter(value = value, dtSeconds = 0.01)
        }
        assert(kotlin.math.abs(output) < 0.5) { "expected attenuated output near 0, was $output" }
    }

    @Test
    fun `reset discards state so the next sample passes through unfiltered again`() {
        val filter = LowPassFilter(cutoffHz = 2.0)
        filter.filter(value = 5.0, dtSeconds = 0.1)
        filter.reset()
        assertEquals(100.0, filter.filter(value = 100.0, dtSeconds = 0.1), tolerance)
    }
}

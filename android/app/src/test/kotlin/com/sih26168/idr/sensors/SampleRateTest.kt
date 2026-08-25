package com.sih26168.idr.sensors

import org.junit.Assert.assertEquals
import org.junit.Test

class SampleRateTest {

    @Test
    fun `100 ms delta is 10 Hz`() {
        assertEquals(10.0, SampleRate.hzFromDeltaNs(100_000_000L), 1e-9)
    }

    @Test
    fun `20 ms delta is 50 Hz`() {
        assertEquals(50.0, SampleRate.hzFromDeltaNs(20_000_000L), 1e-9)
    }

    @Test
    fun `zero delta returns zero instead of dividing by zero`() {
        assertEquals(0.0, SampleRate.hzFromDeltaNs(0L), 1e-9)
    }

    @Test
    fun `negative delta returns zero instead of a nonsense negative rate`() {
        assertEquals(0.0, SampleRate.hzFromDeltaNs(-1L), 1e-9)
    }
}

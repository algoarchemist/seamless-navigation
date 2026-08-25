package com.sih26168.idr.features

import org.junit.Assert.assertEquals
import org.junit.Test

class RollingWindowTest {

    private val tolerance = 1e-5f

    @Test
    fun `mean of empty window is zero`() {
        assertEquals(0f, RollingWindow(5).mean(), tolerance)
    }

    @Test
    fun `mean matches hand calculation`() {
        val window = RollingWindow(5)
        listOf(1f, 2f, 3f).forEach { window.add(it) }
        assertEquals(2f, window.mean(), tolerance)
    }

    @Test
    fun `std of fewer than two samples is zero`() {
        val window = RollingWindow(5)
        assertEquals(0f, window.std(), tolerance)
        window.add(5f)
        assertEquals(0f, window.std(), tolerance)
    }

    @Test
    fun `std uses sample standard deviation, ddof equals 1, not population std`() {
        // [1, 2, 3]: mean=2, sum of squared diffs = 1+0+1 = 2,
        // sample std = sqrt(2 / (3-1)) = sqrt(1) = 1.0.
        // (Population std, ddof=0, would instead give sqrt(2/3) = 0.8165 — must NOT match that.)
        val window = RollingWindow(5)
        listOf(1f, 2f, 3f).forEach { window.add(it) }
        assertEquals(1.0f, window.std(), tolerance)
    }

    @Test
    fun `energy is mean of squares`() {
        // [1, 2, 3]: mean of squares = (1+4+9)/3 = 4.6667
        val window = RollingWindow(5)
        listOf(1f, 2f, 3f).forEach { window.add(it) }
        assertEquals(14f / 3f, window.energy(), tolerance)
    }

    @Test
    fun `oldest sample is evicted once capacity is exceeded`() {
        val window = RollingWindow(3)
        listOf(1f, 2f, 3f, 4f).forEach { window.add(it) }
        // Window should now hold [2, 3, 4], not [1, 2, 3].
        assertEquals(3f, window.mean(), tolerance)
    }

    @Test
    fun `fewer samples than capacity still computes over what is available (min_periods=1 behavior)`() {
        val window = RollingWindow(10)
        window.add(4f)
        window.add(6f)
        assertEquals(5f, window.mean(), tolerance) // not padded with zeros to reach capacity 10
    }
}

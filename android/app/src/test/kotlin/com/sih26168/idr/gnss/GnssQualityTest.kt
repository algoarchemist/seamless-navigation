package com.sih26168.idr.gnss

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssQualityTest {

    @Test
    fun `no fix ever received is not good`() {
        assertFalse(GnssQuality.isGood(fixAgeMs = Long.MAX_VALUE, accuracyM = null))
    }

    @Test
    fun `fresh accurate fix is good`() {
        assertTrue(GnssQuality.isGood(fixAgeMs = 100L, accuracyM = 5f))
    }

    @Test
    fun `fresh but inaccurate fix is not good`() {
        assertFalse(GnssQuality.isGood(fixAgeMs = 100L, accuracyM = 50f))
    }

    @Test
    fun `accurate but stale fix is not good`() {
        assertFalse(GnssQuality.isGood(fixAgeMs = 10_000L, accuracyM = 3f))
    }

    @Test
    fun `boundary values are inclusive of the threshold`() {
        assertTrue(
            GnssQuality.isGood(
                fixAgeMs = GnssQuality.DEFAULT_MAX_FIX_AGE_MS,
                accuracyM = GnssQuality.DEFAULT_MAX_ACCURACY_M,
            ),
        )
    }

    @Test
    fun `just past the boundary is not good`() {
        assertFalse(
            GnssQuality.isGood(
                fixAgeMs = GnssQuality.DEFAULT_MAX_FIX_AGE_MS + 1,
                accuracyM = GnssQuality.DEFAULT_MAX_ACCURACY_M,
            ),
        )
        assertFalse(
            GnssQuality.isGood(
                fixAgeMs = GnssQuality.DEFAULT_MAX_FIX_AGE_MS,
                accuracyM = GnssQuality.DEFAULT_MAX_ACCURACY_M + 0.1f,
            ),
        )
    }
}

package com.sih26168.idr.gnss

import org.junit.Assert.assertEquals
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

    @Test
    fun `confidenceWeight is null-safe zero when no fix has ever been received`() {
        assertEquals(0f, GnssQuality.confidenceWeight(accuracyM = null), 0.0001f)
    }

    @Test
    fun `confidenceWeight is near 1 for a very accurate fix`() {
        assertEquals(0.96f, GnssQuality.confidenceWeight(accuracyM = 1f, maxAccuracyM = 25f), 0.0001f)
    }

    @Test
    fun `confidenceWeight is 0 at the maxAccuracyM boundary isGood still accepts`() {
        assertEquals(0f, GnssQuality.confidenceWeight(accuracyM = 25f, maxAccuracyM = 25f), 0.0001f)
    }

    @Test
    fun `confidenceWeight clamps to 0 past the boundary rather than going negative`() {
        assertEquals(0f, GnssQuality.confidenceWeight(accuracyM = 100f, maxAccuracyM = 25f), 0.0001f)
    }

    @Test
    fun `confidenceWeight is 1 for a zero-or-better accuracy reading`() {
        assertEquals(1f, GnssQuality.confidenceWeight(accuracyM = 0f, maxAccuracyM = 25f), 0.0001f)
    }
}

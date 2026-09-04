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
    fun `a fix at the real measured indoor refresh cadence is still good`() {
        // docs/gnss-indoor-window-degradation.md: a real 135s indoor drive
        // log measured fresh fixes arriving every ~6.2-6.5s near an open
        // window (min=6219ms, median=6317ms, max=6539ms) -- comfortably
        // under DEFAULT_MAX_FIX_AGE_MS now that it's 7000ms, so a real fix
        // sitting at that age should not be treated as stale.
        assertTrue(GnssQuality.isGood(fixAgeMs = 6_539L, accuracyM = 10.5f))
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

    @Test
    fun `ground-truth bound is stricter than the general availability bound`() {
        // fusion StateEstimator relies on this ordering: a fix loose enough
        // to merely clear DEFAULT_MAX_ACCURACY_M (state-machine "GNSS is
        // available") must NOT automatically also clear the tighter
        // ground-truth bound used to trust a fix as an outage anchor or a
        // drift measurement's endpoint -- see that constant's own doc for
        // the on-device bug this guards against.
        assertTrue(GnssQuality.DEFAULT_MAX_ACCURACY_FOR_GROUND_TRUTH_M < GnssQuality.DEFAULT_MAX_ACCURACY_M)
    }

    @Test
    fun `a fix that clears the general bar but not the ground-truth bar is still classified good`() {
        // isGood() itself is unchanged by adding the stricter constant --
        // callers that want the tighter bar must pass it explicitly via
        // isGood's existing maxAccuracyM parameter.
        val looseAccuracyM = GnssQuality.DEFAULT_MAX_ACCURACY_FOR_GROUND_TRUTH_M + 5f
        assertTrue(GnssQuality.isGood(fixAgeMs = 100L, accuracyM = looseAccuracyM))
        assertFalse(
            GnssQuality.isGood(
                fixAgeMs = 100L,
                accuracyM = looseAccuracyM,
                maxAccuracyM = GnssQuality.DEFAULT_MAX_ACCURACY_FOR_GROUND_TRUTH_M,
            ),
        )
    }
}

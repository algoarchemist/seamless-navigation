package com.sih26168.idr.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Index positions matching ml/train_velocity_model.py's FEATURE_COLUMNS order —
// duplicated here as named constants purely for test readability.
private const val IDX_ACCEL_FORWARD_MEAN = 0
private const val IDX_ACCEL_FORWARD_STD = 1
private const val IDX_ACCEL_FORWARD_ENERGY = 2
private const val IDX_ACCEL_LATERAL_MEAN = 3
private const val IDX_ACCEL_LATERAL_STD = 4
private const val IDX_ACCEL_UP_MEAN = 5
private const val IDX_ACCEL_UP_STD = 6
private const val IDX_GYRO_YAW_RATE_MEAN = 7
private const val IDX_GYRO_YAW_RATE_STD = 8
private const val IDX_JERK_FORWARD_MEAN = 9
private const val IDX_JERK_FORWARD_STD = 10
private const val IDX_ZERO_CROSSING_RATE = 11
private const val IDX_ELAPSED_SINCE_FIX = 12

class FeatureExtractorTest {

    private val tolerance = 1e-4f
    private val oneHundredMs = 100_000_000L

    @Test
    fun `returns a 13-element vector`() {
        val extractor = FeatureExtractor()
        val features = extractor.update(0L, 1f, 0f, 0f, 0f, 0f)
        assertEquals(13, features.size)
    }

    @Test
    fun `each input lands at its documented index, not mixed up`() {
        val extractor = FeatureExtractor()
        val features = extractor.update(
            timestampNs = 0L,
            accelForwardMps2 = 1.0f,
            accelLateralMps2 = 2.0f,
            accelUpMps2 = 3.0f,
            gyroYawRateRadPerSec = 4.0f,
            elapsedSinceLastGnssFixS = 5.0f,
        )
        assertEquals(1.0f, features[IDX_ACCEL_FORWARD_MEAN], tolerance)
        assertEquals(2.0f, features[IDX_ACCEL_LATERAL_MEAN], tolerance)
        assertEquals(3.0f, features[IDX_ACCEL_UP_MEAN], tolerance)
        assertEquals(4.0f, features[IDX_GYRO_YAW_RATE_MEAN], tolerance)
        assertEquals(5.0f, features[IDX_ELAPSED_SINCE_FIX], tolerance)
    }

    @Test
    fun `elapsed since last gnss fix passes through unchanged, not windowed`() {
        val extractor = FeatureExtractor()
        val f1 = extractor.update(0L, 0f, 0f, 0f, 0f, elapsedSinceLastGnssFixS = 0.0f)
        val f2 = extractor.update(oneHundredMs, 0f, 0f, 0f, 0f, elapsedSinceLastGnssFixS = 8.7f)
        assertEquals(0.0f, f1[IDX_ELAPSED_SINCE_FIX], tolerance)
        assertEquals(8.7f, f2[IDX_ELAPSED_SINCE_FIX], tolerance)
    }

    @Test
    fun `first tick has zero jerk (no previous sample) and counts as a sign change`() {
        val extractor = FeatureExtractor()
        val features = extractor.update(0L, 1.0f, 0f, 0f, 0f, 0f)
        assertEquals(0f, features[IDX_JERK_FORWARD_MEAN], tolerance)
        // Matches pandas' NaN-diff-!=-0 quirk on the very first sample — see FeatureExtractor's doc.
        assertEquals(1.0f, features[IDX_ZERO_CROSSING_RATE], tolerance)
    }

    @Test
    fun `jerk matches hand calculation for a steady ramp`() {
        // accel_forward increasing by 1.0 m/s^2 every 100ms -> instantaneous
        // jerk on tick 2 = 1.0 / 0.1 = 10.0 m/s^3. Tick 1 has no previous
        // sample so its jerk is 0.0 (matching Python's NaN->0.0 fill) and
        // that 0.0 stays in the rolling window too — the WINDOWED mean
        // after two ticks is therefore (0.0 + 10.0) / 2 = 5.0, not the
        // instantaneous 10.0 alone.
        val extractor = FeatureExtractor()
        extractor.update(0L, 1.0f, 0f, 0f, 0f, 0f)
        val features = extractor.update(oneHundredMs, 2.0f, 0f, 0f, 0f, 0f)
        assertEquals(5.0f, features[IDX_JERK_FORWARD_MEAN], tolerance)
    }

    @Test
    fun `constant acceleration has zero std and zero jerk after the first tick`() {
        val extractor = FeatureExtractor()
        extractor.update(0L, 3.0f, 0f, 0f, 0f, 0f)
        val features = extractor.update(oneHundredMs, 3.0f, 0f, 0f, 0f, 0f)
        assertEquals(0f, features[IDX_ACCEL_FORWARD_STD], tolerance)
        // Windowed jerk mean, not instantaneous: tick 1's jerk is 0.0 (no
        // previous sample) and tick 2's is also 0.0 (no change) -> mean 0.0.
        assertEquals(0f, features[IDX_JERK_FORWARD_MEAN], tolerance)
        // Zero-crossing window after two ticks is [1, 0] — tick 1 counts
        // as a "change" (the pandas NaN!=0 quirk, see FeatureExtractor's
        // doc), tick 2 does not (same sign as tick 1) -> mean 0.5, not 0.
        assertEquals(0.5f, features[IDX_ZERO_CROSSING_RATE], tolerance)
    }

    @Test
    fun `sign change is detected when forward acceleration crosses zero`() {
        val extractor = FeatureExtractor()
        extractor.update(0L, 1.0f, 0f, 0f, 0f, 0f) // positive
        val features = extractor.update(oneHundredMs, -1.0f, 0f, 0f, 0f, 0f) // negative
        assertEquals(1.0f, features[IDX_ZERO_CROSSING_RATE], tolerance) // both ticks in window are "changes" -> mean=1.0
    }

    @Test
    fun `window only retains the most recent 10 samples`() {
        val extractor = FeatureExtractor()
        // Feed 15 ticks: values 1..15. Window (capacity 10) should end up
        // holding [6..15], mean = 10.5.
        var features = FloatArray(13)
        for (i in 1..15) {
            features = extractor.update(i * oneHundredMs, i.toFloat(), 0f, 0f, 0f, 0f)
        }
        assertEquals(10.5f, features[IDX_ACCEL_FORWARD_MEAN], tolerance)
    }

    @Test
    fun `non-forward channels are independently tracked`() {
        val extractor = FeatureExtractor()
        extractor.update(0L, 0f, 1f, 10f, 0.5f, 0f)
        val features = extractor.update(oneHundredMs, 0f, 3f, 20f, 1.5f, 0f)
        assertEquals(2f, features[IDX_ACCEL_LATERAL_MEAN], tolerance) // mean(1,3)
        assertEquals(15f, features[IDX_ACCEL_UP_MEAN], tolerance) // mean(10,20)
        assertEquals(1f, features[IDX_GYRO_YAW_RATE_MEAN], tolerance) // mean(0.5,1.5)
        assertTrue(features[IDX_ACCEL_LATERAL_STD] > 0f)
        assertTrue(features[IDX_ACCEL_UP_STD] > 0f)
        assertTrue(features[IDX_GYRO_YAW_RATE_STD] > 0f)
    }
}

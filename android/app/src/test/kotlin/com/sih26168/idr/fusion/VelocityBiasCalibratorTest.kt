package com.sih26168.idr.fusion

import org.junit.Assert.assertEquals
import org.junit.Test

class VelocityBiasCalibratorTest {

    @Test
    fun `no samples yet means zero bias and corrected equals raw`() {
        val calibrator = VelocityBiasCalibrator()
        assertEquals(0f, calibrator.currentBiasMps, 0.0001f)
        assertEquals(0, calibrator.sampleCount)
        assertEquals(3.5f, calibrator.correctedVelocity(3.5f), 0.0001f)
    }

    @Test
    fun `below minimum speed does not update the bias`() {
        val calibrator = VelocityBiasCalibrator(minSpeedForCalibrationMps = 5.0f)
        calibrator.update(gnssSpeedMps = 4.9f, rawPredictedVelocityMps = 2.0f)
        assertEquals(0f, calibrator.currentBiasMps, 0.0001f)
        assertEquals(0, calibrator.sampleCount)
    }

    @Test
    fun `first qualifying sample sets bias exactly to that error`() {
        val calibrator = VelocityBiasCalibrator(minSpeedForCalibrationMps = 5.0f)
        calibrator.update(gnssSpeedMps = 10.0f, rawPredictedVelocityMps = 9.0f)
        assertEquals(1.0f, calibrator.currentBiasMps, 0.0001f)
        assertEquals(1, calibrator.sampleCount)
    }

    @Test
    fun `second sample blends toward the new error by emaAlpha`() {
        val calibrator = VelocityBiasCalibrator(minSpeedForCalibrationMps = 5.0f, emaAlpha = 0.1f)
        calibrator.update(gnssSpeedMps = 10.0f, rawPredictedVelocityMps = 9.0f) // error = 1.0, bias -> 1.0
        calibrator.update(gnssSpeedMps = 10.0f, rawPredictedVelocityMps = 8.0f) // error = 2.0
        // bias = 1.0 + 0.1 * (2.0 - 1.0) = 1.1 — hand-derived, not just "some change happened"
        assertEquals(1.1f, calibrator.currentBiasMps, 0.0001f)
        assertEquals(2, calibrator.sampleCount)
    }

    @Test
    fun `a consistent error converges toward that value over many samples`() {
        val calibrator = VelocityBiasCalibrator(minSpeedForCalibrationMps = 5.0f, emaAlpha = 0.2f)
        repeat(100) {
            calibrator.update(gnssSpeedMps = 10.0f, rawPredictedVelocityMps = 8.0f) // constant error = 2.0
        }
        assertEquals(2.0f, calibrator.currentBiasMps, 0.01f)
    }

    @Test
    fun `correctedVelocity adds the learned bias to a raw prediction`() {
        val calibrator = VelocityBiasCalibrator(minSpeedForCalibrationMps = 5.0f)
        calibrator.update(gnssSpeedMps = 10.0f, rawPredictedVelocityMps = 9.0f) // bias -> 1.0
        assertEquals(4.0f, calibrator.correctedVelocity(3.0f), 0.0001f)
    }

    @Test
    fun `reset clears both bias and sample count`() {
        val calibrator = VelocityBiasCalibrator(minSpeedForCalibrationMps = 5.0f)
        calibrator.update(gnssSpeedMps = 10.0f, rawPredictedVelocityMps = 9.0f)
        calibrator.reset()
        assertEquals(0f, calibrator.currentBiasMps, 0.0001f)
        assertEquals(0, calibrator.sampleCount)
        assertEquals(3.0f, calibrator.correctedVelocity(3.0f), 0.0001f)
    }
}

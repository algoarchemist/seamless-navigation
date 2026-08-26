package com.sih26168.idr.ui.screens

import com.sih26168.idr.dr.DeadReckoningState
import com.sih26168.idr.fusion.DrSource
import com.sih26168.idr.fusion.FusedPositionUiState
import com.sih26168.idr.gnss.GnssFix
import com.sih26168.idr.gnss.GnssMode
import com.sih26168.idr.gnss.GnssModeUiState
import com.sih26168.idr.ml.MlVelocityUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusOverlayContentTest {

    private fun fix(speedMps: Float?) = GnssFix(
        timeMs = 0L,
        latitudeDeg = 0.0,
        longitudeDeg = 0.0,
        accuracyM = 5f,
        speedMps = speedMps,
        bearingDeg = null,
    )

    @Test
    fun `GNSS_AIDED speed is trusted when it agrees the phone is moving`() {
        val speed = estimateSpeedMps(
            drState = DeadReckoningState(velocityEastMps = 2.0, velocityNorthMps = 0.0),
            mlState = MlVelocityUiState(),
            gnssState = GnssModeUiState(mode = GnssMode.GNSS_AIDED, latestFix = fix(speedMps = 2.1f)),
            fusedState = FusedPositionUiState(),
        )
        assertEquals(2.1f, speed)
    }

    @Test
    fun `REAL BUG FIX - noisy GNSS speed is rejected while physics ZUPT says stationary`() {
        // Real on-device finding (2026-08-26): indoors, Location.speedMps can
        // report a large "ghost" speed (Doppler noise from multipath) even
        // while accel/gyro and ZUPT-zeroed physics velocity agree the phone
        // hasn't moved. GNSS_AIDED mode alone must not be enough to trust it.
        val speed = estimateSpeedMps(
            drState = DeadReckoningState(velocityEastMps = 0.0, velocityNorthMps = 0.0),
            mlState = MlVelocityUiState(),
            gnssState = GnssModeUiState(mode = GnssMode.GNSS_AIDED, latestFix = fix(speedMps = 15.1f)),
            fusedState = FusedPositionUiState(),
        )
        assertEquals(0f, speed)
    }

    @Test
    fun `rejected GNSS speed falls through to ML speed when ML is the active DR source`() {
        val speed = estimateSpeedMps(
            drState = DeadReckoningState(velocityEastMps = 0.0, velocityNorthMps = 0.0),
            mlState = MlVelocityUiState(predictedVelocityCorrectedMps = 0.6f),
            gnssState = GnssModeUiState(mode = GnssMode.GNSS_AIDED, latestFix = fix(speedMps = 15.1f)),
            fusedState = FusedPositionUiState(drSourceUsed = DrSource.ML),
        )
        assertEquals(0.6f, speed)
    }

    @Test
    fun `not in GNSS_AIDED mode falls back to physics DR speed`() {
        val speed = estimateSpeedMps(
            drState = DeadReckoningState(velocityEastMps = 3.0, velocityNorthMps = 4.0),
            mlState = MlVelocityUiState(),
            gnssState = GnssModeUiState(mode = GnssMode.DEAD_RECKONING, latestFix = fix(speedMps = 99f)),
            fusedState = FusedPositionUiState(),
        )
        assertEquals(5f, speed)
    }

    @Test
    fun `no GNSS fix at all falls back to physics DR speed`() {
        val speed = estimateSpeedMps(
            drState = DeadReckoningState(velocityEastMps = 0.3, velocityNorthMps = 0.4),
            mlState = MlVelocityUiState(),
            gnssState = GnssModeUiState(mode = GnssMode.GNSS_AIDED, latestFix = null),
            fusedState = FusedPositionUiState(),
        )
        assertEquals(0.5f, speed)
    }

    @Test
    fun `estimateMotionLabel reads Stationary once the guarded speed drops below the epsilon`() {
        val speed = estimateSpeedMps(
            drState = DeadReckoningState(velocityEastMps = 0.0, velocityNorthMps = 0.0),
            mlState = MlVelocityUiState(),
            gnssState = GnssModeUiState(mode = GnssMode.GNSS_AIDED, latestFix = fix(speedMps = 15.1f)),
            fusedState = FusedPositionUiState(),
        )
        assertEquals("Stationary", estimateMotionLabel(MlVelocityUiState(), speed))
    }
}

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
        // REAL BUG FIX (2026-09-04, bugs.jpeg code review): estimateSpeedMps
        // now requires GnssQuality.isGood() for the GNSS_AIDED branch too
        // (previously only required for REACQUISITION) -- fixAgeMs must be
        // real/fresh, not GnssModeUiState's Long.MAX_VALUE "no fix age
        // known" default, or this fix is correctly no longer trusted.
        val speed = estimateSpeedMps(
            drState = DeadReckoningState(velocityEastMps = 2.0, velocityNorthMps = 0.0),
            mlState = MlVelocityUiState(),
            gnssState = GnssModeUiState(mode = GnssMode.GNSS_AIDED, latestFix = fix(speedMps = 2.1f), fixAgeMs = 100L),
            fusedState = FusedPositionUiState(),
        )
        assertEquals(2.1f, speed)
    }

    @Test
    fun `GNSS_AIDED speed is NOT trusted when the fix is stale, even mid-hysteresis-window`() {
        // REAL BUG FIX (2026-09-04, bugs.jpeg code review): mode stays
        // GNSS_AIDED for up to outageEnterDwellMs (2s) after quality
        // actually degrades (CLAUDE.md Rule 16 hysteresis) -- this used to
        // trust gnssSpeed unconditionally whenever mode == GNSS_AIDED,
        // disagreeing with dr/BaselineDeadReckoningRepository.kt's and
        // ml/MlVelocityRepository.kt's own gating, which both already
        // required isGood(). A stale fixAgeMs must fall through to the
        // physics/ML speed instead, matching those two call sites.
        val speed = estimateSpeedMps(
            drState = DeadReckoningState(velocityEastMps = 0.0, velocityNorthMps = 0.0),
            mlState = MlVelocityUiState(),
            gnssState = GnssModeUiState(mode = GnssMode.GNSS_AIDED, latestFix = fix(speedMps = 12.0f), fixAgeMs = 9_000L),
            fusedState = FusedPositionUiState(),
        )
        assertEquals(0f, speed)
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
        // Round 2 (2026-08-28): the displayed ML speed now sources
        // predictedVelocityDampedMps (VelocityGuard's damped output, what
        // actually feeds the ML position estimate), not
        // predictedVelocityCorrectedMps (bias-corrected but pre-damping) —
        // see StatusOverlayContent.kt's estimateSpeedMps.
        val speed = estimateSpeedMps(
            drState = DeadReckoningState(velocityEastMps = 0.0, velocityNorthMps = 0.0),
            mlState = MlVelocityUiState(predictedVelocityCorrectedMps = 0.6f, predictedVelocityDampedMps = 0.6f),
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
        assertEquals("Stationary", estimateMotionLabel(DeadReckoningState(), MlVelocityUiState(), speed))
    }

    @Test
    fun `estimateMotionLabel reads Turning when DeadReckoningState flags it, even while moving`() {
        val label = estimateMotionLabel(
            drState = DeadReckoningState(isTurning = true),
            mlState = MlVelocityUiState(),
            speedMps = 5f,
        )
        assertEquals("Turning", label)
    }

    @Test
    fun `estimateMotionLabel reads Accelerating from the ML longitudinal classifier`() {
        val label = estimateMotionLabel(
            drState = DeadReckoningState(),
            mlState = MlVelocityUiState(isAccelerating = true),
            speedMps = 5f,
        )
        assertEquals("Accelerating", label)
    }

    @Test
    fun `estimateMotionLabel reads Braking from the ML longitudinal classifier`() {
        val label = estimateMotionLabel(
            drState = DeadReckoningState(),
            mlState = MlVelocityUiState(isBraking = true),
            speedMps = 5f,
        )
        assertEquals("Braking", label)
    }

    @Test
    fun `Pothole still takes priority over Turning`() {
        val label = estimateMotionLabel(
            drState = DeadReckoningState(isTurning = true),
            mlState = MlVelocityUiState(potholeShockDetectedThisTick = true),
            speedMps = 5f,
        )
        assertEquals("Pothole", label)
    }

    @Test
    fun `Turning still takes priority over Accelerating for the single displayed label`() {
        val label = estimateMotionLabel(
            drState = DeadReckoningState(isTurning = true),
            mlState = MlVelocityUiState(isAccelerating = true),
            speedMps = 5f,
        )
        assertEquals("Turning", label)
    }
}

package com.sih26168.idr.motion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionStateClassifierTest {

    @Test
    fun `physically still and slow prediction is stationary`() {
        val classifier = MotionStateClassifier(minCruisingSpeedMps = 1.0f)
        val result = classifier.classify(physicallyStill = true, rawPredictedVelocityMps = 0.2f)
        assertTrue(result.isStationary)
        assertFalse(result.isCruising)
    }

    @Test
    fun `physically still but fast prediction overrides to cruising`() {
        val classifier = MotionStateClassifier(minCruisingSpeedMps = 1.0f)
        val result = classifier.classify(physicallyStill = true, rawPredictedVelocityMps = 5.0f)
        assertFalse(result.isStationary)
        assertTrue(result.isCruising)
    }

    @Test
    fun `not physically still is neither stationary nor cruising regardless of velocity`() {
        val classifier = MotionStateClassifier(minCruisingSpeedMps = 1.0f)
        val slow = classifier.classify(physicallyStill = false, rawPredictedVelocityMps = 0.0f)
        assertFalse(slow.isStationary)
        assertFalse(slow.isCruising)

        val fast = classifier.classify(physicallyStill = false, rawPredictedVelocityMps = 10.0f)
        assertFalse(fast.isStationary)
        assertFalse(fast.isCruising)
    }

    @Test
    fun `boundary value at exactly minCruisingSpeedMps counts as cruising`() {
        val classifier = MotionStateClassifier(minCruisingSpeedMps = 1.0f)
        val result = classifier.classify(physicallyStill = true, rawPredictedVelocityMps = 1.0f)
        assertTrue(result.isCruising)
        assertFalse(result.isStationary)
    }

    @Test
    fun `just below the boundary still counts as stationary`() {
        val classifier = MotionStateClassifier(minCruisingSpeedMps = 1.0f)
        val result = classifier.classify(physicallyStill = true, rawPredictedVelocityMps = 0.999f)
        assertTrue(result.isStationary)
        assertFalse(result.isCruising)
    }

    @Test
    fun `predictsRealMotion ignores dwell confirmation entirely, unlike classify`() {
        // Regression test for bugs.jpeg's ml/MlVelocityRepository.kt
        // finding: classify(physicallyStill = false, ...) always returns
        // isCruising = false regardless of the raw prediction (see the
        // test above) -- predictsRealMotion must NOT share that gate, since
        // it exists specifically for callers whose ZUPT decision can
        // already be true from a fast path that never confirmed
        // "physically still" (StopEventClassifier's SUDDEN_STOP/
        // HARDWARE_CONFIRMED_IDLE).
        val classifier = MotionStateClassifier(minCruisingSpeedMps = 1.0f)
        assertTrue(classifier.predictsRealMotion(5.0f))
        assertFalse(classifier.predictsRealMotion(0.2f))
    }

    @Test
    fun `predictsRealMotion uses the same boundary as classify`() {
        val classifier = MotionStateClassifier(minCruisingSpeedMps = 1.0f)
        assertTrue(classifier.predictsRealMotion(1.0f))
        assertFalse(classifier.predictsRealMotion(0.999f))
    }
}

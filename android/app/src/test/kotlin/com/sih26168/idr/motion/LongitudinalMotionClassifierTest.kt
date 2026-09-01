package com.sih26168.idr.motion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongitudinalMotionClassifierTest {

    private val classifier = LongitudinalMotionClassifier(minLongitudinalAccelMps2 = 1.0f)

    @Test
    fun `strong positive forward acceleration is accelerating, not braking`() {
        val result = classifier.classify(accelForwardMps2 = 2.0f)
        assertTrue(result.isAccelerating)
        assertFalse(result.isBraking)
    }

    @Test
    fun `strong negative forward acceleration is braking, not accelerating`() {
        val result = classifier.classify(accelForwardMps2 = -2.0f)
        assertFalse(result.isAccelerating)
        assertTrue(result.isBraking)
    }

    @Test
    fun `small forward acceleration within threshold is neither`() {
        val result = classifier.classify(accelForwardMps2 = 0.3f)
        assertFalse(result.isAccelerating)
        assertFalse(result.isBraking)
    }

    @Test
    fun `zero forward acceleration is neither`() {
        val result = classifier.classify(accelForwardMps2 = 0.0f)
        assertFalse(result.isAccelerating)
        assertFalse(result.isBraking)
    }

    @Test
    fun `exactly at the positive threshold counts as accelerating`() {
        val result = classifier.classify(accelForwardMps2 = 1.0f)
        assertTrue(result.isAccelerating)
    }

    @Test
    fun `exactly at the negative threshold counts as braking`() {
        val result = classifier.classify(accelForwardMps2 = -1.0f)
        assertTrue(result.isBraking)
    }
}

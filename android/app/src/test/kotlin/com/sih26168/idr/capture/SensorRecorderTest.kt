package com.sih26168.idr.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorRecorderTest {

    @Test
    fun `no entries yet means zero recorded count and an empty json array`() {
        val recorder = SensorRecorder()
        assertEquals(0, recorder.recordedCount)
        assertEquals("[\n]\n", recorder.toJsonArray())
    }

    @Test
    fun `first recorded tick has elapsedMs zero, relative to itself`() {
        val recorder = SensorRecorder()
        recorder.record(
            timestampNs = 5_000_000_000L,
            accelXMps2 = 0.1f, accelYMps2 = 0.2f, accelZMps2 = 9.8f,
            gyroXRadPerSec = 0f, gyroYRadPerSec = 0f, gyroZRadPerSec = 0f,
            azimuthRad = 0f, pitchRad = 0f, rollRad = 0f,
        )
        assertTrue(recorder.toJsonArray().contains("\"elapsedMs\":0,"))
    }

    @Test
    fun `elapsedMs is measured relative to the first recorded tick, in whole milliseconds`() {
        val recorder = SensorRecorder()
        recorder.record(1_000_000_000L, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f) // start
        recorder.record(1_250_000_000L, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f) // +250ms
        recorder.record(2_000_000_000L, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f) // +1000ms
        val json = recorder.toJsonArray()
        assertTrue(json.contains("\"elapsedMs\":0,"))
        assertTrue(json.contains("\"elapsedMs\":250,"))
        assertTrue(json.contains("\"elapsedMs\":1000,"))
    }

    @Test
    fun `recordedCount tracks the number of ticks recorded`() {
        val recorder = SensorRecorder()
        repeat(5) {
            recorder.record(it * 100_000_000L, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
        assertEquals(5, recorder.recordedCount)
    }

    @Test
    fun `reset clears entries and re-anchors the next tick's elapsedMs at zero`() {
        val recorder = SensorRecorder()
        recorder.record(1_000_000_000L, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        recorder.record(1_500_000_000L, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        recorder.reset()
        assertEquals(0, recorder.recordedCount)

        recorder.record(9_000_000_000L, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        assertTrue(recorder.toJsonArray().contains("\"elapsedMs\":0,"))
    }

    @Test
    fun `toJsonArray includes accel gyro and orientation fields for each entry`() {
        val recorder = SensorRecorder()
        recorder.record(
            timestampNs = 0L,
            accelXMps2 = 1.5f, accelYMps2 = -2.5f, accelZMps2 = 9.8f,
            gyroXRadPerSec = 0.1f, gyroYRadPerSec = -0.2f, gyroZRadPerSec = 0.3f,
            azimuthRad = 1.0f, pitchRad = -0.1f, rollRad = 0.05f,
        )
        val json = recorder.toJsonArray()
        assertTrue(json.contains("\"accelXMps2\":1.5"))
        assertTrue(json.contains("\"accelYMps2\":-2.5"))
        assertTrue(json.contains("\"gyroZRadPerSec\":0.3"))
        assertTrue(json.contains("\"azimuthRad\":1.0"))
    }
}

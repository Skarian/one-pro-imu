package io.onepro.xr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OneProHeadTrackerTest {
    @Test
    fun calibrationCompletesAndComputesGyroBias() {
        val tracker = OneProHeadTracker(
            config = OneProHeadTrackerConfig(
                calibrationSampleTarget = 3,
                complementaryFilterAlpha = 0.96f,
                pitchScale = 1.0f,
                yawScale = 1.0f,
                rollScale = 1.0f
            )
        )

        val s1 = tracker.calibrateGyroscope(OneProImuVectorSample(1.0f, 2.0f, -1.0f, 0.0f, 0.0f, 1.0f))
        val s2 = tracker.calibrateGyroscope(OneProImuVectorSample(2.0f, 2.0f, -1.0f, 0.0f, 0.0f, 1.0f))
        val s3 = tracker.calibrateGyroscope(OneProImuVectorSample(3.0f, 2.0f, -1.0f, 0.0f, 0.0f, 1.0f))

        assertFalse(s1.isCalibrated)
        assertFalse(s2.isCalibrated)
        assertTrue(s3.isCalibrated)
        assertEquals(2.0f, tracker.gyroBiasX, 0.0001f)
        assertEquals(2.0f, tracker.gyroBiasY, 0.0001f)
        assertEquals(-1.0f, tracker.gyroBiasZ, 0.0001f)
    }

    @Test
    fun zeroViewProducesRelativeOrientationAroundCurrentPose() {
        val tracker = OneProHeadTracker(
            config = OneProHeadTrackerConfig(
                calibrationSampleTarget = 1,
                complementaryFilterAlpha = 1.0f,
                pitchScale = 1.0f,
                yawScale = 1.0f,
                rollScale = 1.0f
            )
        )

        tracker.calibrateGyroscope(OneProImuVectorSample(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f))

        val firstUpdate = tracker.update(
            sensorSample = OneProImuVectorSample(0.0f, 90.0f, 0.0f, 0.0f, 0.0f, 1.0f),
            deviceTimestampNanos = 1_000_000_000UL
        )
        assertNull(firstUpdate)

        val secondUpdate = tracker.update(
            sensorSample = OneProImuVectorSample(0.0f, 90.0f, 0.0f, 0.0f, 0.0f, 1.0f),
            deviceTimestampNanos = 1_100_000_000UL
        )
        assertTrue(secondUpdate != null)
        assertEquals(9.0f, secondUpdate!!.absoluteOrientation.yaw, 0.0001f)

        tracker.zeroView()
        val zeroed = tracker.getRelativeOrientation()
        assertEquals(0.0f, zeroed.yaw, 0.0001f)

        tracker.update(
            sensorSample = OneProImuVectorSample(0.0f, 90.0f, 0.0f, 0.0f, 0.0f, 1.0f),
            deviceTimestampNanos = 1_200_000_000UL
        )
        val relativeAfter = tracker.getRelativeOrientation()
        assertEquals(9.0f, relativeAfter.yaw, 0.0001f)
    }

    @Test
    fun updateFailsFastWhenDeviceTimestampIsNonMonotonic() {
        val tracker = OneProHeadTracker(
            config = OneProHeadTrackerConfig(
                calibrationSampleTarget = 1,
                complementaryFilterAlpha = 1.0f,
                pitchScale = 1.0f,
                yawScale = 1.0f,
                rollScale = 1.0f
            )
        )

        tracker.calibrateGyroscope(OneProImuVectorSample(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f))
        tracker.update(
            sensorSample = OneProImuVectorSample(0.0f, 90.0f, 0.0f, 0.0f, 0.0f, 1.0f),
            deviceTimestampNanos = 1_000_000_000UL
        )

        try {
            tracker.update(
                sensorSample = OneProImuVectorSample(0.0f, 90.0f, 0.0f, 0.0f, 0.0f, 1.0f),
                deviceTimestampNanos = 1_000_000_000UL
            )
            throw AssertionError("expected non-monotonic timestamp failure")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message?.contains("non-monotonic") == true)
        }
    }

    @Test
    fun resetCalibrationClearsState() {
        val tracker = OneProHeadTracker(
            config = OneProHeadTrackerConfig(
                calibrationSampleTarget = 2,
                complementaryFilterAlpha = 0.96f,
                pitchScale = 1.0f,
                yawScale = 1.0f,
                rollScale = 1.0f
            )
        )

        tracker.calibrateGyroscope(OneProImuVectorSample(1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f))
        tracker.calibrateGyroscope(OneProImuVectorSample(1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f))
        assertTrue(tracker.isCalibrated)

        tracker.resetCalibration()

        assertFalse(tracker.isCalibrated)
        assertEquals(0, tracker.calibrationCount)
        assertEquals(0.0f, tracker.gyroBiasX, 0.0001f)
        assertEquals(0.0f, tracker.gyroBiasY, 0.0001f)
        assertEquals(0.0f, tracker.gyroBiasZ, 0.0001f)
    }
}

package io.onepro.xr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OneProHeadTrackerTest {
    @Test
    fun calibrationCompletesAndComputesGyroResidualBias() {
        val tracker = createTracker(
            calibrationSampleTarget = 3,
            complementaryFilterAlpha = 0.96f,
            biasConfig = neutralBiasConfig()
        )

        val s1 = tracker.calibrateGyroscope(sample(gx = 1.0f, gy = 2.0f, gz = -1.0f))
        val s2 = tracker.calibrateGyroscope(sample(gx = 2.0f, gy = 2.0f, gz = -1.0f))
        val s3 = tracker.calibrateGyroscope(sample(gx = 3.0f, gy = 2.0f, gz = -1.0f))

        assertFalse(s1.isCalibrated)
        assertFalse(s2.isCalibrated)
        assertTrue(s3.isCalibrated)
        assertEquals(2.0f, tracker.gyroBiasX, 0.0001f)
        assertEquals(2.0f, tracker.gyroBiasY, 0.0001f)
        assertEquals(-1.0f, tracker.gyroBiasZ, 0.0001f)
    }

    @Test
    fun zeroViewProducesRelativeOrientationAroundCurrentPose() {
        val tracker = createTracker(
            calibrationSampleTarget = 1,
            complementaryFilterAlpha = 1.0f,
            biasConfig = neutralBiasConfig()
        )

        tracker.calibrateGyroscope(sample())

        val firstUpdate = tracker.update(
            sensorSample = sample(gy = degreesPerSecondToRadiansPerSecond(90.0f)),
            deviceTimestampNanos = 1_000_000_000UL
        )
        assertNull(firstUpdate)

        val secondUpdate = tracker.update(
            sensorSample = sample(gy = degreesPerSecondToRadiansPerSecond(90.0f)),
            deviceTimestampNanos = 1_100_000_000UL
        )
        assertTrue(secondUpdate != null)
        assertEquals(9.0f, secondUpdate!!.absoluteOrientation.yaw, 0.0001f)

        tracker.zeroView()
        val zeroed = tracker.getRelativeOrientation()
        assertEquals(0.0f, zeroed.yaw, 0.0001f)

        tracker.update(
            sensorSample = sample(gy = degreesPerSecondToRadiansPerSecond(90.0f)),
            deviceTimestampNanos = 1_200_000_000UL
        )
        val relativeAfter = tracker.getRelativeOrientation()
        assertEquals(9.0f, relativeAfter.yaw, 0.0001f)
    }

    @Test
    fun relativeOrientationIsPhysicalRecenteredDeltaWithoutGain() {
        val tracker = createTracker(
            calibrationSampleTarget = 1,
            complementaryFilterAlpha = 1.0f,
            biasConfig = neutralBiasConfig()
        )

        tracker.calibrateGyroscope(sample())

        tracker.update(
            sensorSample = sample(
                gx = degreesPerSecondToRadiansPerSecond(20.0f),
                gy = degreesPerSecondToRadiansPerSecond(30.0f),
                gz = degreesPerSecondToRadiansPerSecond(-10.0f)
            ),
            deviceTimestampNanos = 1_000_000_000UL
        )
        val beforeZero = tracker.update(
            sensorSample = sample(
                gx = degreesPerSecondToRadiansPerSecond(20.0f),
                gy = degreesPerSecondToRadiansPerSecond(30.0f),
                gz = degreesPerSecondToRadiansPerSecond(-10.0f)
            ),
            deviceTimestampNanos = 1_100_000_000UL
        )
        assertTrue(beforeZero != null)

        tracker.zeroView()
        val afterZero = tracker.update(
            sensorSample = sample(
                gx = degreesPerSecondToRadiansPerSecond(20.0f),
                gy = degreesPerSecondToRadiansPerSecond(30.0f),
                gz = degreesPerSecondToRadiansPerSecond(-10.0f)
            ),
            deviceTimestampNanos = 1_200_000_000UL
        )
        assertTrue(afterZero != null)

        val expectedPitch = wrapAngle(afterZero!!.absoluteOrientation.pitch - beforeZero!!.absoluteOrientation.pitch)
        val expectedYaw = wrapAngle(afterZero.absoluteOrientation.yaw - beforeZero.absoluteOrientation.yaw)
        val expectedRoll = wrapAngle(afterZero.absoluteOrientation.roll - beforeZero.absoluteOrientation.roll)

        assertEquals(expectedPitch, afterZero.relativeOrientation.pitch, 0.0001f)
        assertEquals(expectedYaw, afterZero.relativeOrientation.yaw, 0.0001f)
        assertEquals(expectedRoll, afterZero.relativeOrientation.roll, 0.0001f)
    }

    @Test
    fun updateFailsFastWhenDeviceTimestampIsNonMonotonic() {
        val tracker = createTracker(
            calibrationSampleTarget = 1,
            complementaryFilterAlpha = 1.0f,
            biasConfig = neutralBiasConfig()
        )

        tracker.calibrateGyroscope(sample())
        tracker.update(
            sensorSample = sample(gy = degreesPerSecondToRadiansPerSecond(90.0f)),
            deviceTimestampNanos = 1_000_000_000UL
        )

        try {
            tracker.update(
                sensorSample = sample(gy = degreesPerSecondToRadiansPerSecond(90.0f)),
                deviceTimestampNanos = 1_000_000_000UL
            )
            throw AssertionError("expected non-monotonic timestamp failure")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message?.contains("non-monotonic") == true)
        }
    }

    @Test
    fun resetCalibrationClearsState() {
        val tracker = createTracker(
            calibrationSampleTarget = 2,
            complementaryFilterAlpha = 0.96f,
            biasConfig = neutralBiasConfig()
        )

        tracker.calibrateGyroscope(sample(gx = 1.0f, gy = 1.0f, gz = 1.0f))
        tracker.calibrateGyroscope(sample(gx = 1.0f, gy = 1.0f, gz = 1.0f))
        assertTrue(tracker.isCalibrated)

        tracker.resetCalibration()

        assertFalse(tracker.isCalibrated)
        assertEquals(0, tracker.calibrationCount)
        assertEquals(0.0f, tracker.gyroBiasX, 0.0001f)
        assertEquals(0.0f, tracker.gyroBiasY, 0.0001f)
        assertEquals(0.0f, tracker.gyroBiasZ, 0.0001f)
    }

    @Test
    fun calibrationSubtractsFactoryGyroBiasBeforeResidualEstimation() {
        val tracker = createTracker(
            calibrationSampleTarget = 3,
            complementaryFilterAlpha = 1.0f,
            biasConfig = biasConfig(
                accelBias = XrVector3d(0.0, 0.0, 0.0),
                gyroPoints = listOf(
                    XrGyroBiasSample(
                        bias = XrVector3d(1.0, 2.0, -1.0),
                        temperatureCelsius = 25.0
                    )
                )
            )
        )

        tracker.calibrateGyroscope(sample(gx = 2.0f, gy = 4.0f, gz = -2.0f, temperatureCelsius = 25.0f))
        tracker.calibrateGyroscope(sample(gx = 2.0f, gy = 4.0f, gz = -2.0f, temperatureCelsius = 25.0f))
        tracker.calibrateGyroscope(sample(gx = 2.0f, gy = 4.0f, gz = -2.0f, temperatureCelsius = 25.0f))

        assertEquals(1.0f, tracker.gyroBiasX, 0.0001f)
        assertEquals(2.0f, tracker.gyroBiasY, 0.0001f)
        assertEquals(-1.0f, tracker.gyroBiasZ, 0.0001f)
    }

    @Test
    fun updateSubtractsFactoryAccelBiasBeforeComplementaryFilter() {
        val tracker = createTracker(
            calibrationSampleTarget = 1,
            complementaryFilterAlpha = 0.0f,
            biasConfig = biasConfig(
                accelBias = XrVector3d(0.2, -0.1, 0.3),
                gyroPoints = listOf(
                    XrGyroBiasSample(
                        bias = XrVector3d(0.0, 0.0, 0.0),
                        temperatureCelsius = 20.0
                    )
                )
            )
        )

        tracker.calibrateGyroscope(sample(temperatureCelsius = 20.0f))
        tracker.update(
            sensorSample = sample(
                ax = 0.2f,
                ay = -0.1f,
                az = 1.3f,
                temperatureCelsius = 20.0f
            ),
            deviceTimestampNanos = 1_000_000_000UL
        )
        val update = tracker.update(
            sensorSample = sample(
                ax = 0.2f,
                ay = -0.1f,
                az = 1.3f,
                temperatureCelsius = 20.0f
            ),
            deviceTimestampNanos = 1_100_000_000UL
        )

        assertTrue(update != null)
        assertEquals(0.0f, update!!.absoluteOrientation.pitch, 0.0001f)
        assertEquals(0.0f, update.absoluteOrientation.roll, 0.0001f)
    }

    @Test
    fun updateUsesInterpolatedFactoryGyroBiasByTemperature() {
        val tracker = createTracker(
            calibrationSampleTarget = 1,
            complementaryFilterAlpha = 1.0f,
            biasConfig = biasConfig(
                accelBias = XrVector3d(0.0, 0.0, 0.0),
                gyroPoints = listOf(
                    XrGyroBiasSample(
                        bias = XrVector3d(0.0, 0.0, 0.0),
                        temperatureCelsius = 0.0
                    ),
                    XrGyroBiasSample(
                        bias = XrVector3d(10.0, 0.0, 0.0),
                        temperatureCelsius = 10.0
                    )
                )
            )
        )

        tracker.calibrateGyroscope(sample(gx = 5.0f, temperatureCelsius = 5.0f))
        tracker.update(
            sensorSample = sample(gx = 5.0f, temperatureCelsius = 5.0f),
            deviceTimestampNanos = 1_000_000_000UL
        )
        val update = tracker.update(
            sensorSample = sample(gx = 5.0f, temperatureCelsius = 5.0f),
            deviceTimestampNanos = 1_100_000_000UL
        )

        assertTrue(update != null)
        assertEquals(0.0f, update!!.absoluteOrientation.pitch, 0.0001f)
    }

    @Test
    fun remappedFactoryAccelBiasMatchesRawFrameCorrectThenRemapSemantics() {
        val rawFactoryBias = Vector3f(
            x = 0.5f,
            y = 0.25f,
            z = -0.75f
        )
        val remappedFactoryBias = OneProTrackerSampleMapper.remapAccelBiasToTrackerFrame(rawFactoryBias)
        val trackerWithFactoryBias = createTracker(
            calibrationSampleTarget = 1,
            complementaryFilterAlpha = 0.0f,
            biasConfig = OneProTrackerBiasConfig(
                accelBias = remappedFactoryBias,
                gyroBiasTemperatureData = listOf(
                    XrGyroBiasSample(
                        bias = XrVector3d(0.0, 0.0, 0.0),
                        temperatureCelsius = 20.0
                    )
                )
            )
        )
        val referenceTracker = createTracker(
            calibrationSampleTarget = 1,
            complementaryFilterAlpha = 0.0f,
            biasConfig = OneProTrackerBiasConfig(
                accelBias = Vector3f(0.0f, 0.0f, 0.0f),
                gyroBiasTemperatureData = listOf(
                    XrGyroBiasSample(
                        bias = XrVector3d(0.0, 0.0, 0.0),
                        temperatureCelsius = 20.0
                    )
                )
            )
        )

        trackerWithFactoryBias.calibrateGyroscope(sample(temperatureCelsius = 20.0f))
        referenceTracker.calibrateGyroscope(sample(temperatureCelsius = 20.0f))

        val mappedRawSample = sample(
            ax = 5.0f,
            ay = 3.0f,
            az = 1.0f,
            temperatureCelsius = 20.0f
        )
        val mappedSampleAfterRawFrameCorrection = sample(
            ax = mappedRawSample.ax - remappedFactoryBias.x,
            ay = mappedRawSample.ay - remappedFactoryBias.y,
            az = mappedRawSample.az - remappedFactoryBias.z,
            temperatureCelsius = 20.0f
        )

        trackerWithFactoryBias.update(
            sensorSample = mappedRawSample,
            deviceTimestampNanos = 1_000_000_000UL
        )
        referenceTracker.update(
            sensorSample = mappedSampleAfterRawFrameCorrection,
            deviceTimestampNanos = 1_000_000_000UL
        )

        val updateWithFactoryBias = trackerWithFactoryBias.update(
            sensorSample = mappedRawSample,
            deviceTimestampNanos = 1_100_000_000UL
        )
        val referenceUpdate = referenceTracker.update(
            sensorSample = mappedSampleAfterRawFrameCorrection,
            deviceTimestampNanos = 1_100_000_000UL
        )

        assertTrue(updateWithFactoryBias != null)
        assertTrue(referenceUpdate != null)
        assertEquals(
            referenceUpdate!!.absoluteOrientation.pitch,
            updateWithFactoryBias!!.absoluteOrientation.pitch,
            0.0001f
        )
        assertEquals(
            referenceUpdate.absoluteOrientation.roll,
            updateWithFactoryBias.absoluteOrientation.roll,
            0.0001f
        )
    }

    private fun createTracker(
        calibrationSampleTarget: Int,
        complementaryFilterAlpha: Float,
        biasConfig: OneProTrackerBiasConfig
    ): OneProHeadTracker {
        return OneProHeadTracker(
            config = OneProHeadTrackerConfig(
                calibrationSampleTarget = calibrationSampleTarget,
                complementaryFilterAlpha = complementaryFilterAlpha,
                biasConfig = biasConfig
            )
        )
    }

    private fun wrapAngle(value: Float): Float {
        var angle = value
        while (angle > 180.0f) {
            angle -= 360.0f
        }
        while (angle < -180.0f) {
            angle += 360.0f
        }
        return angle
    }

    private fun degreesPerSecondToRadiansPerSecond(value: Float): Float {
        return Math.toRadians(value.toDouble()).toFloat()
    }

    private fun sample(
        gx: Float = 0.0f,
        gy: Float = 0.0f,
        gz: Float = 0.0f,
        ax: Float = 0.0f,
        ay: Float = 0.0f,
        az: Float = 1.0f,
        temperatureCelsius: Float = 25.0f
    ): OneProImuVectorSample {
        return OneProImuVectorSample(
            gx = gx,
            gy = gy,
            gz = gz,
            ax = ax,
            ay = ay,
            az = az,
            temperatureCelsius = temperatureCelsius
        )
    }

    private fun neutralBiasConfig(): OneProTrackerBiasConfig {
        return biasConfig(
            accelBias = XrVector3d(0.0, 0.0, 0.0),
            gyroPoints = listOf(
                XrGyroBiasSample(
                    bias = XrVector3d(0.0, 0.0, 0.0),
                    temperatureCelsius = 25.0
                )
            )
        )
    }

    private fun biasConfig(
        accelBias: XrVector3d,
        gyroPoints: List<XrGyroBiasSample>
    ): OneProTrackerBiasConfig {
        return OneProTrackerBiasConfig(
            accelBias = Vector3f(
                accelBias.x.toFloat(),
                accelBias.y.toFloat(),
                accelBias.z.toFloat()
            ),
            gyroBiasTemperatureData = gyroPoints
        )
    }
}

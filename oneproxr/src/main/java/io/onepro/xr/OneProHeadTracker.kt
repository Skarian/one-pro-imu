package io.onepro.xr

import kotlin.math.atan2
import kotlin.math.sqrt

internal data class OneProImuVectorSample(
    val gx: Float,
    val gy: Float,
    val gz: Float,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val temperatureCelsius: Float
)

internal data class OneProTrackerBiasConfig(
    val accelBias: Vector3f,
    val gyroBiasTemperatureData: List<XrGyroBiasSample>
) {
    init {
        require(gyroBiasTemperatureData.isNotEmpty()) {
            "gyroBiasTemperatureData must not be empty"
        }
    }

    fun interpolateGyroBias(temperatureCelsius: Float): Vector3f {
        val insertionIndex = gyroBiasTemperatureData.indexOfFirst {
            temperatureCelsius.toDouble() < it.temperatureCelsius
        }.let { if (it == -1) gyroBiasTemperatureData.size else it }
        if (insertionIndex == 0) {
            return gyroBiasTemperatureData.first().bias.toVector3f()
        }
        if (insertionIndex == gyroBiasTemperatureData.size) {
            return gyroBiasTemperatureData.last().bias.toVector3f()
        }

        val previous = gyroBiasTemperatureData[insertionIndex - 1]
        val next = gyroBiasTemperatureData[insertionIndex]
        val denominator = next.temperatureCelsius - previous.temperatureCelsius
        if (denominator == 0.0) {
            return previous.bias.toVector3f()
        }
        val fraction = ((temperatureCelsius.toDouble() - previous.temperatureCelsius) / denominator).toFloat()
        val keep = 1.0f - fraction
        return Vector3f(
            x = previous.bias.x.toFloat() * keep + next.bias.x.toFloat() * fraction,
            y = previous.bias.y.toFloat() * keep + next.bias.y.toFloat() * fraction,
            z = previous.bias.z.toFloat() * keep + next.bias.z.toFloat() * fraction
        )
    }
}

internal data class OneProHeadTrackerConfig(
    val calibrationSampleTarget: Int,
    val complementaryFilterAlpha: Float,
    val biasConfig: OneProTrackerBiasConfig
)

internal data class OneProCalibrationState(
    val sampleCount: Int,
    val target: Int,
    val isCalibrated: Boolean
) {
    val progressPercent: Float
        get() = if (target <= 0) 100.0f else (sampleCount.toFloat() / target.toFloat()) * 100.0f
}

internal data class OneProTrackingUpdate(
    val deltaTimeSeconds: Float,
    val absoluteOrientation: HeadOrientationDegrees,
    val relativeOrientation: HeadOrientationDegrees,
    val factoryGyroBias: Vector3f,
    val runtimeResidualGyroBias: Vector3f,
    val factoryAccelBias: Vector3f
)

internal class OneProHeadTracker(
    private val config: OneProHeadTrackerConfig
) {
    internal var gyroBiasX = 0.0f
        private set
    internal var gyroBiasY = 0.0f
        private set
    internal var gyroBiasZ = 0.0f
        private set

    internal var calibrationCount = 0
        private set
    internal val calibrationTarget: Int
        get() = config.calibrationSampleTarget

    private var gyroSumX = 0.0f
    private var gyroSumY = 0.0f
    private var gyroSumZ = 0.0f

    var isCalibrated = false
        private set

    private var pitch = 0.0f
    private var yaw = 0.0f
    private var roll = 0.0f

    private var zeroPitch = 0.0f
    private var zeroYaw = 0.0f
    private var zeroRoll = 0.0f

    private var lastDeviceTimestampNanos: ULong? = null

    fun calibrateGyroscope(sensorSample: OneProImuVectorSample): OneProCalibrationState {
        if (isCalibrated) {
            return calibrationState()
        }

        val factoryGyroBias = config.biasConfig.interpolateGyroBias(sensorSample.temperatureCelsius)
        gyroSumX += sensorSample.gx - factoryGyroBias.x
        gyroSumY += sensorSample.gy - factoryGyroBias.y
        gyroSumZ += sensorSample.gz - factoryGyroBias.z
        calibrationCount += 1

        if (calibrationCount >= config.calibrationSampleTarget) {
            val divisor = calibrationCount.coerceAtLeast(1).toFloat()
            gyroBiasX = gyroSumX / divisor
            gyroBiasY = gyroSumY / divisor
            gyroBiasZ = gyroSumZ / divisor
            isCalibrated = true
            pitch = 0.0f
            yaw = 0.0f
            roll = 0.0f
            lastDeviceTimestampNanos = null
        }

        return calibrationState()
    }

    fun resetCalibration() {
        gyroSumX = 0.0f
        gyroSumY = 0.0f
        gyroSumZ = 0.0f
        calibrationCount = 0
        isCalibrated = false
        gyroBiasX = 0.0f
        gyroBiasY = 0.0f
        gyroBiasZ = 0.0f
        pitch = 0.0f
        yaw = 0.0f
        roll = 0.0f
        zeroPitch = 0.0f
        zeroYaw = 0.0f
        zeroRoll = 0.0f
        lastDeviceTimestampNanos = null
    }

    fun zeroView() {
        zeroPitch = pitch
        zeroYaw = yaw
        zeroRoll = roll
    }

    fun getRelativeOrientation(): HeadOrientationDegrees {
        return HeadOrientationDegrees(
            pitch = wrapAngle(pitch - zeroPitch),
            yaw = wrapAngle(yaw - zeroYaw),
            roll = wrapAngle(roll - zeroRoll)
        )
    }

    fun calibrationState(): OneProCalibrationState {
        return OneProCalibrationState(
            sampleCount = calibrationCount,
            target = config.calibrationSampleTarget,
            isCalibrated = isCalibrated
        )
    }

    fun update(
        sensorSample: OneProImuVectorSample,
        deviceTimestampNanos: ULong
    ): OneProTrackingUpdate? {
        if (!isCalibrated) {
            return null
        }

        val previousTimestamp = lastDeviceTimestampNanos
        if (previousTimestamp == null) {
            lastDeviceTimestampNanos = deviceTimestampNanos
            return null
        }

        val deltaTimeSeconds = resolveDeltaTimeSeconds(
            previousTimestampNanos = previousTimestamp,
            currentTimestampNanos = deviceTimestampNanos
        )

        val factoryGyroBias = config.biasConfig.interpolateGyroBias(sensorSample.temperatureCelsius)
        val factoryAccelBias = config.biasConfig.accelBias

        val gyroX = sensorSample.gx - factoryGyroBias.x - gyroBiasX
        val gyroY = sensorSample.gy - factoryGyroBias.y - gyroBiasY
        val gyroZ = sensorSample.gz - factoryGyroBias.z - gyroBiasZ

        val pitchGyro = pitch + gyroX * RAD_PER_SEC_TO_DEG_PER_SEC * deltaTimeSeconds
        val yawGyro = yaw + gyroY * RAD_PER_SEC_TO_DEG_PER_SEC * deltaTimeSeconds
        val rollGyro = roll + gyroZ * RAD_PER_SEC_TO_DEG_PER_SEC * deltaTimeSeconds

        val accelX = sensorSample.ax - factoryAccelBias.x
        val accelY = sensorSample.ay - factoryAccelBias.y
        val accelZ = sensorSample.az - factoryAccelBias.z

        val accMagnitude = sqrt(
            accelX * accelX +
                accelY * accelY +
                accelZ * accelZ
        )

        if (accMagnitude > 0.01f) {
            val pitchAccel = Math.toDegrees(
                atan2(
                    -accelX.toDouble(),
                    sqrt((accelY * accelY + accelZ * accelZ).toDouble())
                )
            ).toFloat()
            val rollAccel = Math.toDegrees(
                atan2(accelY.toDouble(), accelZ.toDouble())
            ).toFloat()
            val alpha = config.complementaryFilterAlpha
            pitch = alpha * pitchGyro + (1.0f - alpha) * pitchAccel
            yaw = yawGyro
            roll = alpha * rollGyro + (1.0f - alpha) * rollAccel
        } else {
            pitch = pitchGyro
            yaw = yawGyro
            roll = rollGyro
        }

        pitch = wrapAngle(pitch)
        yaw = wrapAngle(yaw)
        roll = wrapAngle(roll)
        lastDeviceTimestampNanos = deviceTimestampNanos

        val absolute = HeadOrientationDegrees(
            pitch = pitch,
            yaw = yaw,
            roll = roll
        )
        return OneProTrackingUpdate(
            deltaTimeSeconds = deltaTimeSeconds,
            absoluteOrientation = absolute,
            relativeOrientation = getRelativeOrientation(),
            factoryGyroBias = factoryGyroBias,
            runtimeResidualGyroBias = Vector3f(gyroBiasX, gyroBiasY, gyroBiasZ),
            factoryAccelBias = factoryAccelBias
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

    private fun resolveDeltaTimeSeconds(
        previousTimestampNanos: ULong,
        currentTimestampNanos: ULong
    ): Float {
        if (currentTimestampNanos <= previousTimestampNanos) {
            throw IllegalStateException(
                "Device timestamp is non-monotonic (previous=$previousTimestampNanos current=$currentTimestampNanos)"
            )
        }
        val deltaNanos = currentTimestampNanos - previousTimestampNanos
        val deltaSeconds = deltaNanos.toDouble() / 1_000_000_000.0
        if (!deltaSeconds.isFinite() || deltaSeconds <= 0.0) {
            throw IllegalStateException(
                "Device timestamp produced invalid delta seconds (previous=$previousTimestampNanos current=$currentTimestampNanos)"
            )
        }
        return deltaSeconds.toFloat()
    }
}

private const val RAD_PER_SEC_TO_DEG_PER_SEC = 57.29578f

private fun XrVector3d.toVector3f(): Vector3f {
    return Vector3f(x.toFloat(), y.toFloat(), z.toFloat())
}

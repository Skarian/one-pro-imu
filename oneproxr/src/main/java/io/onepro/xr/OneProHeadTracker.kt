package io.onepro.xr

import kotlin.math.atan2
import kotlin.math.sqrt

internal data class OneProHeadTrackerConfig(
    val calibrationSampleTarget: Int,
    val complementaryFilterAlpha: Float,
    val pitchScale: Float,
    val yawScale: Float,
    val rollScale: Float
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
    val relativeOrientation: HeadOrientationDegrees
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

    private var lastTimestampNanos: Long? = null

    fun calibrateGyroscope(sensorSample: OneProSensorSample): OneProCalibrationState {
        if (isCalibrated) {
            return calibrationState()
        }

        gyroSumX += sensorSample.gx
        gyroSumY += sensorSample.gy
        gyroSumZ += sensorSample.gz
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
            lastTimestampNanos = null
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
        lastTimestampNanos = null
    }

    fun zeroView() {
        zeroPitch = pitch
        zeroYaw = yaw
        zeroRoll = roll
    }

    fun getRelativeOrientation(): HeadOrientationDegrees {
        return HeadOrientationDegrees(
            pitch = wrapAngle((pitch - zeroPitch) * config.pitchScale),
            yaw = wrapAngle((yaw - zeroYaw) * config.yawScale),
            roll = wrapAngle((roll - zeroRoll) * config.rollScale)
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
        sensorSample: OneProSensorSample,
        timestampNanos: Long,
        fallbackDeltaSeconds: Float,
        maxDeltaSeconds: Float
    ): OneProTrackingUpdate? {
        if (!isCalibrated) {
            return null
        }

        val previousTimestamp = lastTimestampNanos
        if (previousTimestamp == null) {
            lastTimestampNanos = timestampNanos
            return null
        }

        val deltaTimeSeconds = resolveDeltaTimeSeconds(
            previousTimestampNanos = previousTimestamp,
            currentTimestampNanos = timestampNanos,
            fallbackSeconds = fallbackDeltaSeconds,
            maxSeconds = maxDeltaSeconds
        )

        val gyroX = sensorSample.gx - gyroBiasX
        val gyroY = sensorSample.gy - gyroBiasY
        val gyroZ = sensorSample.gz - gyroBiasZ

        val pitchGyro = pitch + gyroX * deltaTimeSeconds
        val yawGyro = yaw + gyroY * deltaTimeSeconds
        val rollGyro = roll + gyroZ * deltaTimeSeconds

        val accMagnitude = sqrt(
            sensorSample.ax * sensorSample.ax +
                sensorSample.ay * sensorSample.ay +
                sensorSample.az * sensorSample.az
        )

        if (accMagnitude > 0.01f) {
            val pitchAccel = Math.toDegrees(
                atan2(
                    -sensorSample.ax.toDouble(),
                    sqrt((sensorSample.ay * sensorSample.ay + sensorSample.az * sensorSample.az).toDouble())
                )
            ).toFloat()
            val rollAccel = Math.toDegrees(
                atan2(sensorSample.ay.toDouble(), sensorSample.az.toDouble())
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
        lastTimestampNanos = timestampNanos

        val absolute = HeadOrientationDegrees(
            pitch = pitch,
            yaw = yaw,
            roll = roll
        )
        return OneProTrackingUpdate(
            deltaTimeSeconds = deltaTimeSeconds,
            absoluteOrientation = absolute,
            relativeOrientation = getRelativeOrientation()
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
        previousTimestampNanos: Long,
        currentTimestampNanos: Long,
        fallbackSeconds: Float,
        maxSeconds: Float
    ): Float {
        val safeFallback = fallbackSeconds.coerceAtLeast(0.0001f)
        val safeMax = maxSeconds.coerceAtLeast(safeFallback)
        val deltaNanos = currentTimestampNanos - previousTimestampNanos
        if (deltaNanos <= 0L) {
            return safeFallback
        }
        val deltaSeconds = deltaNanos.toDouble() / 1_000_000_000.0
        if (!deltaSeconds.isFinite() || deltaSeconds <= 0.0) {
            return safeFallback
        }
        return deltaSeconds.toFloat().coerceAtMost(safeMax)
    }
}

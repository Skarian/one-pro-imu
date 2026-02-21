package io.onepro.xr

import kotlin.math.PI
import kotlin.math.abs

internal class OneProPoseSmoother(
    private val minCutoffHz: Float = DEFAULT_MIN_CUTOFF_HZ,
    private val beta: Float = DEFAULT_BETA,
    private val derivativeCutoffHz: Float = DEFAULT_DERIVATIVE_CUTOFF_HZ,
    private val maxDeltaTimeSeconds: Float = DEFAULT_MAX_DELTA_TIME_SECONDS
) {
    private val pitch = OneEuroAngleAxis(minCutoffHz, beta, derivativeCutoffHz, maxDeltaTimeSeconds)
    private val yaw = OneEuroAngleAxis(minCutoffHz, beta, derivativeCutoffHz, maxDeltaTimeSeconds)
    private val roll = OneEuroAngleAxis(minCutoffHz, beta, derivativeCutoffHz, maxDeltaTimeSeconds)

    @Synchronized
    fun reset() {
        pitch.reset()
        yaw.reset()
        roll.reset()
    }

    @Synchronized
    fun prime(orientation: HeadOrientationDegrees) {
        pitch.prime(orientation.pitch)
        yaw.prime(orientation.yaw)
        roll.prime(orientation.roll)
    }

    @Synchronized
    fun smooth(
        orientation: HeadOrientationDegrees,
        deltaTimeSeconds: Float
    ): HeadOrientationDegrees {
        return HeadOrientationDegrees(
            pitch = pitch.smooth(orientation.pitch, deltaTimeSeconds),
            yaw = yaw.smooth(orientation.yaw, deltaTimeSeconds),
            roll = roll.smooth(orientation.roll, deltaTimeSeconds)
        )
    }

    private class OneEuroAngleAxis(
        private val minCutoffHz: Float,
        private val beta: Float,
        private val derivativeCutoffHz: Float,
        private val maxDeltaTimeSeconds: Float
    ) {
        private var isInitialized = false
        private var previousWrapped = 0.0f
        private var previousUnwrapped = 0.0f
        private var filteredUnwrapped = 0.0f
        private var filteredDerivative = 0.0f

        fun reset() {
            isInitialized = false
            previousWrapped = 0.0f
            previousUnwrapped = 0.0f
            filteredUnwrapped = 0.0f
            filteredDerivative = 0.0f
        }

        fun prime(wrappedInput: Float) {
            val wrapped = wrapAngle(wrappedInput)
            isInitialized = true
            previousWrapped = wrapped
            previousUnwrapped = wrapped
            filteredUnwrapped = wrapped
            filteredDerivative = 0.0f
        }

        fun smooth(wrappedInput: Float, deltaTimeSeconds: Float): Float {
            val wrapped = wrapAngle(wrappedInput)
            if (!isInitialized) {
                prime(wrapped)
                return wrapped
            }
            if (!deltaTimeSeconds.isFinite() || deltaTimeSeconds <= 0.0f || deltaTimeSeconds > maxDeltaTimeSeconds) {
                prime(wrapped)
                return wrapped
            }

            val deltaWrapped = wrapAngle(wrapped - previousWrapped)
            val unwrapped = previousUnwrapped + deltaWrapped
            val derivative = deltaWrapped / deltaTimeSeconds

            val derivativeAlpha = smoothingAlpha(deltaTimeSeconds, derivativeCutoffHz)
            filteredDerivative = lerp(filteredDerivative, derivative, derivativeAlpha)

            val adaptiveCutoffHz = minCutoffHz + beta * abs(filteredDerivative)
            val valueAlpha = smoothingAlpha(deltaTimeSeconds, adaptiveCutoffHz)
            filteredUnwrapped = lerp(filteredUnwrapped, unwrapped, valueAlpha)

            previousWrapped = wrapped
            previousUnwrapped = unwrapped
            return wrapAngle(filteredUnwrapped)
        }

        private fun smoothingAlpha(deltaTimeSeconds: Float, cutoffHz: Float): Float {
            val safeCutoffHz = cutoffHz.coerceAtLeast(0.0001f)
            val tau = 1.0f / (2.0f * PI.toFloat() * safeCutoffHz)
            return deltaTimeSeconds / (deltaTimeSeconds + tau)
        }

        private fun lerp(previous: Float, next: Float, alpha: Float): Float {
            val clampedAlpha = alpha.coerceIn(0.0f, 1.0f)
            return previous + clampedAlpha * (next - previous)
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
    }

    companion object {
        private const val DEFAULT_MIN_CUTOFF_HZ = 1.2f
        private const val DEFAULT_BETA = 0.06f
        private const val DEFAULT_DERIVATIVE_CUTOFF_HZ = 1.0f
        private const val DEFAULT_MAX_DELTA_TIME_SECONDS = 0.2f
    }
}

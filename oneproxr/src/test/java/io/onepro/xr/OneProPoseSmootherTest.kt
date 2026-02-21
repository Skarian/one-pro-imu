package io.onepro.xr

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OneProPoseSmootherTest {
    @Test
    fun firstSamplePassesThroughWithoutLag() {
        val smoother = OneProPoseSmoother()
        val input = HeadOrientationDegrees(
            pitch = 12.5f,
            yaw = -33.0f,
            roll = 4.25f
        )

        val output = smoother.smooth(input, deltaTimeSeconds = 0.01f)

        assertEquals(input.pitch, output.pitch, 0.0001f)
        assertEquals(input.yaw, output.yaw, 0.0001f)
        assertEquals(input.roll, output.roll, 0.0001f)
    }

    @Test
    fun smoothReducesStaticJitter() {
        val smoother = OneProPoseSmoother()
        val basePitch = 10.0f
        var rawDeviation = 0.0f
        var smoothDeviation = 0.0f
        var samples = 0

        repeat(300) { index ->
            val noise = if (index % 2 == 0) 0.8f else -0.8f
            val raw = HeadOrientationDegrees(
                pitch = basePitch + noise,
                yaw = -15.0f + noise * 0.4f,
                roll = 7.0f - noise * 0.2f
            )
            val smoothed = smoother.smooth(raw, deltaTimeSeconds = 0.01f)

            if (index >= 50) {
                rawDeviation += abs(raw.pitch - basePitch)
                smoothDeviation += abs(smoothed.pitch - basePitch)
                samples += 1
            }
        }

        val averageRawDeviation = rawDeviation / samples.toFloat()
        val averageSmoothDeviation = smoothDeviation / samples.toFloat()
        assertTrue(averageSmoothDeviation < averageRawDeviation * 0.85f)
    }

    @Test
    fun wrapCrossingDoesNotIntroduceLargeJump() {
        val smoother = OneProPoseSmoother()
        smoother.prime(
            HeadOrientationDegrees(
                pitch = 0.0f,
                yaw = 179.0f,
                roll = 0.0f
            )
        )

        val beforeCrossing = smoother.smooth(
            orientation = HeadOrientationDegrees(0.0f, 179.5f, 0.0f),
            deltaTimeSeconds = 0.01f
        )
        val afterCrossing = smoother.smooth(
            orientation = HeadOrientationDegrees(0.0f, -179.5f, 0.0f),
            deltaTimeSeconds = 0.01f
        )

        val yawStep = abs(wrapAngle(afterCrossing.yaw - beforeCrossing.yaw))
        assertTrue(yawStep < 30.0f)
    }

    @Test
    fun resetRestartsFromCurrentInput() {
        val smoother = OneProPoseSmoother()
        repeat(20) { index ->
            val sample = HeadOrientationDegrees(
                pitch = 15.0f + index * 0.3f,
                yaw = -20.0f + index * 0.2f,
                roll = 5.0f
            )
            smoother.smooth(sample, deltaTimeSeconds = 0.01f)
        }

        smoother.reset()
        val restartInput = HeadOrientationDegrees(
            pitch = -8.0f,
            yaw = 3.0f,
            roll = 11.0f
        )
        val output = smoother.smooth(restartInput, deltaTimeSeconds = 0.01f)

        assertEquals(restartInput.pitch, output.pitch, 0.0001f)
        assertEquals(restartInput.yaw, output.yaw, 0.0001f)
        assertEquals(restartInput.roll, output.roll, 0.0001f)
    }

    @Test
    fun invalidDeltaTimePassesThroughAndReprimes() {
        val smoother = OneProPoseSmoother()
        smoother.prime(HeadOrientationDegrees(2.0f, 4.0f, 6.0f))
        val invalidDeltaInput = HeadOrientationDegrees(8.0f, 12.0f, 16.0f)

        val output = smoother.smooth(
            orientation = invalidDeltaInput,
            deltaTimeSeconds = 0.0f
        )

        assertEquals(invalidDeltaInput.pitch, output.pitch, 0.0001f)
        assertEquals(invalidDeltaInput.yaw, output.yaw, 0.0001f)
        assertEquals(invalidDeltaInput.roll, output.roll, 0.0001f)
    }

    @Test
    fun primeKeepsModeSwitchTransitionStable() {
        val smoother = OneProPoseSmoother()
        repeat(40) { index ->
            smoother.smooth(
                orientation = HeadOrientationDegrees(
                    pitch = 40.0f + index,
                    yaw = -30.0f + index,
                    roll = 5.0f
                ),
                deltaTimeSeconds = 0.01f
            )
        }

        smoother.prime(
            HeadOrientationDegrees(
                pitch = -6.0f,
                yaw = 9.0f,
                roll = 3.0f
            )
        )
        val next = smoother.smooth(
            orientation = HeadOrientationDegrees(
                pitch = -5.8f,
                yaw = 9.2f,
                roll = 3.1f
            ),
            deltaTimeSeconds = 0.01f
        )

        assertTrue(abs(wrapAngle(next.pitch - (-5.8f))) < 1.0f)
        assertTrue(abs(wrapAngle(next.yaw - 9.2f)) < 1.0f)
        assertTrue(abs(wrapAngle(next.roll - 3.1f)) < 1.0f)
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

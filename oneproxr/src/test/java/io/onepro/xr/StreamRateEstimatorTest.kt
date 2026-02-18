package io.onepro.xr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamRateEstimatorTest {
    @Test
    fun estimateComputesCaptureAndDeltaRates() {
        val frames = listOf(
            frame(index = 0, captureNs = 100_000_000L, word14 = 1_000L),
            frame(index = 1, captureNs = 300_000_000L, word14 = 2_000L),
            frame(index = 2, captureNs = 500_000_000L, word14 = 3_000L),
            frame(index = 3, captureNs = 700_000_000L, word14 = 4_000L)
        )

        val estimate = StreamRateEstimator.estimate(
            frames = frames,
            captureStartNanos = 0L,
            captureEndNanos = 1_000_000_000L
        )

        requireNotNull(estimate)
        assertEquals(4, estimate.frameCount)
        assertEquals(1000.0, estimate.captureWindowMs)
        assertEquals(4.0, estimate.observedFrameHz)
        assertEquals(200.0, estimate.receiveDeltaMinMs)
        assertEquals(200.0, estimate.receiveDeltaAvgMs)
        assertEquals(200.0, estimate.receiveDeltaMaxMs)
        assertEquals(1000L, estimate.candidateWord14DeltaMin)
        assertEquals(1000.0, estimate.candidateWord14DeltaAvg)
        assertEquals(1000L, estimate.candidateWord14DeltaMax)
        assertEquals(1000.0, estimate.candidateWord14HzAssumingMicros)
        assertEquals(1_000_000.0, estimate.candidateWord14HzAssumingNanos)
    }

    @Test
    fun estimateReturnsNullForEmptyFrames() {
        val estimate = StreamRateEstimator.estimate(
            frames = emptyList(),
            captureStartNanos = 0L,
            captureEndNanos = 1_000_000_000L
        )

        assertNull(estimate)
    }

    private fun frame(index: Int, captureNs: Long?, word14: Long?): DecodedSensorFrame {
        return DecodedSensorFrame(
            index = index,
            byteCount = 32,
            captureMonotonicNanos = captureNs,
            rawHex = "",
            candidateReportId = 40,
            candidateVersion = 54,
            candidateTemperatureRaw = 0,
            candidateTemperatureCelsius = 25.0,
            candidateTimestampRawLe = null,
            candidateWord12 = 0,
            candidateWord14 = word14,
            candidateGyroPackedX = 0,
            candidateGyroPackedY = 0,
            candidateGyroPackedZ = 0,
            candidateTailWord30 = 0
        )
    }
}

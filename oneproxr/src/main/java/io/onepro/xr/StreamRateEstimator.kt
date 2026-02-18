package io.onepro.xr

import kotlin.math.round

object StreamRateEstimator {
    fun estimate(
        frames: List<DecodedSensorFrame>,
        captureStartNanos: Long,
        captureEndNanos: Long
    ): StreamRateEstimate? {
        if (frames.isEmpty()) {
            return null
        }

        val captureWindowNanos = (captureEndNanos - captureStartNanos).takeIf { it > 0 }
        val captureWindowMs = captureWindowNanos?.toDouble()?.div(1_000_000.0)
        val observedFrameHz = captureWindowNanos?.let {
            frames.size.toDouble() * 1_000_000_000.0 / it.toDouble()
        }

        val receiveDeltasMs = deltas(frames.mapNotNull { it.captureMonotonicNanos })
            .filter { it > 0 }
            .map { it.toDouble() / 1_000_000.0 }
        val receiveStats = doubleStats(receiveDeltasMs)

        val word14Deltas = deltas(frames.mapNotNull { it.candidateWord14 }).filter { it > 0 }
        val word14Stats = longStats(word14Deltas)
        val word14HzAssumingMicros = word14Stats?.avg?.takeIf { it > 0.0 }?.let { 1_000_000.0 / it }
        val word14HzAssumingNanos = word14Stats?.avg?.takeIf { it > 0.0 }?.let { 1_000_000_000.0 / it }

        return StreamRateEstimate(
            frameCount = frames.size,
            captureWindowMs = roundTo3(captureWindowMs),
            observedFrameHz = roundTo3(observedFrameHz),
            receiveDeltaMinMs = roundTo3(receiveStats?.min),
            receiveDeltaMaxMs = roundTo3(receiveStats?.max),
            receiveDeltaAvgMs = roundTo3(receiveStats?.avg),
            candidateWord14DeltaMin = word14Stats?.min,
            candidateWord14DeltaMax = word14Stats?.max,
            candidateWord14DeltaAvg = roundTo3(word14Stats?.avg),
            candidateWord14HzAssumingMicros = roundTo3(word14HzAssumingMicros),
            candidateWord14HzAssumingNanos = roundTo3(word14HzAssumingNanos)
        )
    }

    private data class LongStats(
        val min: Long,
        val max: Long,
        val avg: Double
    )

    private data class DoubleStats(
        val min: Double,
        val max: Double,
        val avg: Double
    )

    private fun deltas(values: List<Long>): List<Long> {
        if (values.size < 2) {
            return emptyList()
        }
        val deltas = ArrayList<Long>(values.size - 1)
        for (index in 1 until values.size) {
            deltas += values[index] - values[index - 1]
        }
        return deltas
    }

    private fun longStats(values: List<Long>): LongStats? {
        if (values.isEmpty()) {
            return null
        }
        val sum = values.fold(0.0) { acc, value -> acc + value.toDouble() }
        return LongStats(
            min = values.minOrNull() ?: return null,
            max = values.maxOrNull() ?: return null,
            avg = sum / values.size.toDouble()
        )
    }

    private fun doubleStats(values: List<Double>): DoubleStats? {
        if (values.isEmpty()) {
            return null
        }
        val sum = values.fold(0.0) { acc, value -> acc + value }
        return DoubleStats(
            min = values.minOrNull() ?: return null,
            max = values.maxOrNull() ?: return null,
            avg = sum / values.size.toDouble()
        )
    }

    private fun roundTo3(value: Double?): Double? {
        return value?.let { round(it * 1000.0) / 1000.0 }
    }
}

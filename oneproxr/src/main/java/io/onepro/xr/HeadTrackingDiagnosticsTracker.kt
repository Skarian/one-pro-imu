package io.onepro.xr

import kotlin.math.round

internal class HeadTrackingDiagnosticsTracker {
    private var trackingSampleCount = 0L
    private var parsedMessageCount = 0L
    private var droppedByteCount = 0L
    private var tooShortMessageCount = 0L
    private var missingSensorMarkerCount = 0L
    private var invalidSensorSliceCount = 0L
    private var floatDecodeFailureCount = 0L

    private var firstSampleCaptureNanos: Long? = null
    private var lastSampleCaptureNanos: Long? = null
    private var lastCaptureNanos: Long? = null
    private val receiveDeltaStats = RunningStats()

    fun recordParserDelta(delta: OneProStreamMessageParser.ParseDiagnosticsDelta) {
        parsedMessageCount += delta.parsedMessageCount
        droppedByteCount += delta.droppedBytes
        tooShortMessageCount += delta.tooShortMessageCount
        missingSensorMarkerCount += delta.missingSensorMarkerCount
        invalidSensorSliceCount += delta.invalidSensorSliceCount
        floatDecodeFailureCount += delta.floatDecodeFailureCount
    }

    fun recordTrackingSample(captureMonotonicNanos: Long) {
        trackingSampleCount += 1

        if (firstSampleCaptureNanos == null) {
            firstSampleCaptureNanos = captureMonotonicNanos
        }
        val previousCapture = lastCaptureNanos
        if (previousCapture != null) {
            val deltaMs = (captureMonotonicNanos - previousCapture).toDouble() / 1_000_000.0
            if (deltaMs > 0.0) {
                receiveDeltaStats.record(deltaMs)
            }
        }
        lastCaptureNanos = captureMonotonicNanos
        lastSampleCaptureNanos = captureMonotonicNanos
    }

    fun snapshot(): HeadTrackingStreamDiagnostics {
        val firstSample = firstSampleCaptureNanos
        val lastSample = lastSampleCaptureNanos
        val observedRate = if (firstSample != null && lastSample != null && lastSample > firstSample) {
            trackingSampleCount.toDouble() * 1_000_000_000.0 / (lastSample - firstSample).toDouble()
        } else {
            null
        }

        return HeadTrackingStreamDiagnostics(
            trackingSampleCount = trackingSampleCount,
            parsedMessageCount = parsedMessageCount,
            rejectedMessageCount = tooShortMessageCount +
                missingSensorMarkerCount +
                invalidSensorSliceCount +
                floatDecodeFailureCount,
            droppedByteCount = droppedByteCount,
            tooShortMessageCount = tooShortMessageCount,
            missingSensorMarkerCount = missingSensorMarkerCount,
            invalidSensorSliceCount = invalidSensorSliceCount,
            floatDecodeFailureCount = floatDecodeFailureCount,
            observedSampleRateHz = roundTo3(observedRate),
            receiveDeltaMinMs = roundTo3(receiveDeltaStats.min),
            receiveDeltaMaxMs = roundTo3(receiveDeltaStats.max),
            receiveDeltaAvgMs = roundTo3(receiveDeltaStats.avg)
        )
    }

    private data class RunningStats(
        var count: Long = 0,
        var min: Double? = null,
        var max: Double? = null,
        var sum: Double = 0.0
    ) {
        val avg: Double?
            get() = if (count == 0L) null else sum / count.toDouble()

        fun record(value: Double) {
            count += 1
            sum += value
            min = min?.let { kotlin.math.min(it, value) } ?: value
            max = max?.let { kotlin.math.max(it, value) } ?: value
        }
    }

    private fun roundTo3(value: Double?): Double? {
        return value?.let { round(it * 1000.0) / 1000.0 }
    }
}

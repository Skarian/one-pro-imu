package io.onepro.xr

import kotlin.math.round

internal class HeadTrackingDiagnosticsTracker {
    private var trackingSampleCount = 0L
    private var parsedMessageCount = 0L
    private var droppedByteCount = 0L
    private var invalidReportLengthCount = 0L
    private var decodeErrorCount = 0L
    private var unknownReportTypeCount = 0L
    private var imuReportCount = 0L
    private var magnetometerReportCount = 0L

    private var firstSampleCaptureNanos: Long? = null
    private var lastSampleCaptureNanos: Long? = null
    private var lastCaptureNanos: Long? = null
    private val receiveDeltaStats = RunningStats()

    fun recordParserDelta(delta: OneProReportMessageParser.ParseDiagnosticsDelta) {
        parsedMessageCount += delta.parsedMessageCount
        droppedByteCount += delta.droppedBytes
        invalidReportLengthCount += delta.invalidReportLengthCount
        decodeErrorCount += delta.decodeErrorCount
        unknownReportTypeCount += delta.unknownReportTypeCount
        imuReportCount += delta.imuReportCount
        magnetometerReportCount += delta.magnetometerReportCount
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
            rejectedMessageCount = invalidReportLengthCount + decodeErrorCount + unknownReportTypeCount,
            droppedByteCount = droppedByteCount,
            invalidReportLengthCount = invalidReportLengthCount,
            decodeErrorCount = decodeErrorCount,
            unknownReportTypeCount = unknownReportTypeCount,
            imuReportCount = imuReportCount,
            magnetometerReportCount = magnetometerReportCount,
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

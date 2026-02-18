package io.onepro.xr

import java.util.concurrent.atomic.AtomicBoolean

data class OneProXrEndpoint(
    val host: String = "169.254.2.1",
    val controlPort: Int = 52999,
    val streamPort: Int = 52998
)

data class InterfaceInfo(
    val name: String,
    val isUp: Boolean,
    val addresses: List<String>
)

data class NetworkCandidateInfo(
    val networkHandle: Long,
    val interfaceName: String,
    val addresses: List<String>
)

data class AddressCandidateInfo(
    val interfaceName: String,
    val address: String
)

data class RoutingSnapshot(
    val interfaces: List<InterfaceInfo>,
    val networkCandidates: List<NetworkCandidateInfo>,
    val addressCandidates: List<AddressCandidateInfo>
)

data class ControlChannelResult(
    val success: Boolean,
    val networkHandle: Long?,
    val interfaceName: String?,
    val localSocket: String?,
    val remoteSocket: String?,
    val connectMs: Long,
    val readSummary: String,
    val error: String?
)

data class DecodedSensorFrame(
    val index: Int,
    val byteCount: Int,
    val captureMonotonicNanos: Long?,
    val rawHex: String,
    val candidateReportId: Int?,
    val candidateVersion: Int?,
    val candidateTemperatureRaw: Int?,
    val candidateTemperatureCelsius: Double?,
    val candidateTimestampRawLe: String?,
    val candidateWord12: Int?,
    val candidateWord14: Long?,
    val candidateGyroPackedX: Int?,
    val candidateGyroPackedY: Int?,
    val candidateGyroPackedZ: Int?,
    val candidateTailWord30: Int?
)

data class StreamRateEstimate(
    val frameCount: Int,
    val captureWindowMs: Double?,
    val observedFrameHz: Double?,
    val receiveDeltaMinMs: Double?,
    val receiveDeltaMaxMs: Double?,
    val receiveDeltaAvgMs: Double?,
    val candidateWord14DeltaMin: Long?,
    val candidateWord14DeltaMax: Long?,
    val candidateWord14DeltaAvg: Double?,
    val candidateWord14HzAssumingMicros: Double?,
    val candidateWord14HzAssumingNanos: Double?
)

data class StreamReadResult(
    val success: Boolean,
    val networkHandle: Long?,
    val interfaceName: String?,
    val localSocket: String?,
    val remoteSocket: String?,
    val connectMs: Long,
    val frames: List<DecodedSensorFrame>,
    val rateEstimate: StreamRateEstimate?,
    val readStatus: String,
    val error: String?
)

data class StreamCaptureResult(
    val success: Boolean,
    val networkHandle: Long?,
    val interfaceName: String?,
    val localSocket: String?,
    val remoteSocket: String?,
    val connectMs: Long,
    val durationMs: Long,
    val totalBytes: Int,
    val payload: ByteArray,
    val readStatus: String,
    val error: String?
)

data class Vector3f(
    val x: Float,
    val y: Float,
    val z: Float
)

enum class OneProReportType(val wireValue: UInt) {
    IMU(0x0000000BU),
    MAGNETOMETER(0x00000004U);

    companion object {
        fun fromWireValue(value: UInt): OneProReportType? {
            return entries.firstOrNull { it.wireValue == value }
        }
    }
}

data class OneProFrameId(
    val byte0: Int,
    val byte1: Int,
    val byte2: Int
) {
    init {
        require(byte0 in 0..255) { "byte0 must be 0..255" }
        require(byte1 in 0..255) { "byte1 must be 0..255" }
        require(byte2 in 0..255) { "byte2 must be 0..255" }
    }

    val asUInt24LittleEndian: Int
        get() = byte0 or (byte1 shl 8) or (byte2 shl 16)
}

data class OneProReportMessage(
    val deviceId: ULong,
    val hmdTimeNanosDevice: ULong,
    val reportType: OneProReportType,
    val gx: Float,
    val gy: Float,
    val gz: Float,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val mx: Float,
    val my: Float,
    val mz: Float,
    val temperatureCelsius: Float,
    val imuId: Int,
    val frameId: OneProFrameId
)

data class HeadOrientationDegrees(
    val pitch: Float,
    val yaw: Float,
    val roll: Float
)

data class HeadTrackingSample(
    val sampleIndex: Long,
    val captureMonotonicNanos: Long,
    val deltaTimeSeconds: Float,
    val absoluteOrientation: HeadOrientationDegrees,
    val relativeOrientation: HeadOrientationDegrees,
    val calibrationSampleCount: Int,
    val calibrationTarget: Int,
    val isCalibrated: Boolean
)

data class HeadTrackingStreamDiagnostics(
    val trackingSampleCount: Long,
    val parsedMessageCount: Long,
    val rejectedMessageCount: Long,
    val droppedByteCount: Long,
    val invalidReportLengthCount: Long,
    val decodeErrorCount: Long,
    val unknownReportTypeCount: Long,
    val imuReportCount: Long,
    val magnetometerReportCount: Long,
    val observedSampleRateHz: Double?,
    val receiveDeltaMinMs: Double?,
    val receiveDeltaMaxMs: Double?,
    val receiveDeltaAvgMs: Double?
)

class HeadTrackingControlChannel {
    private val zeroViewRequested = AtomicBoolean(false)
    private val recalibrationRequested = AtomicBoolean(false)

    fun requestZeroView() {
        zeroViewRequested.set(true)
    }

    fun requestRecalibration() {
        recalibrationRequested.set(true)
    }

    internal fun consumeZeroViewRequest(): Boolean {
        return zeroViewRequested.getAndSet(false)
    }

    internal fun consumeRecalibrationRequest(): Boolean {
        return recalibrationRequested.getAndSet(false)
    }
}

data class HeadTrackingStreamConfig(
    val connectTimeoutMs: Int = 1500,
    val readTimeoutMs: Int = 700,
    val readChunkBytes: Int = 4096,
    val diagnosticsIntervalSamples: Int = 240,
    val calibrationSampleTarget: Int = 500,
    val complementaryFilterAlpha: Float = 0.96f,
    val pitchScale: Float = 3.0f,
    val yawScale: Float = 60.0f,
    val rollScale: Float = 1.0f,
    val autoZeroViewOnStart: Boolean = true,
    val autoZeroViewAfterSamples: Int = 3,
    val controlChannel: HeadTrackingControlChannel? = null
)

sealed interface HeadTrackingStreamEvent {
    data class Connected(
        val networkHandle: Long,
        val interfaceName: String,
        val localSocket: String,
        val remoteSocket: String,
        val connectMs: Long
    ) : HeadTrackingStreamEvent

    data class CalibrationProgress(
        val calibrationSampleCount: Int,
        val calibrationTarget: Int,
        val progressPercent: Float,
        val isComplete: Boolean
    ) : HeadTrackingStreamEvent

    data class ReportAvailable(
        val report: OneProReportMessage
    ) : HeadTrackingStreamEvent

    data class TrackingSampleAvailable(
        val sample: HeadTrackingSample
    ) : HeadTrackingStreamEvent

    data class DiagnosticsAvailable(
        val diagnostics: HeadTrackingStreamDiagnostics
    ) : HeadTrackingStreamEvent

    data class StreamStopped(
        val reason: String
    ) : HeadTrackingStreamEvent

    data class StreamError(
        val error: String
    ) : HeadTrackingStreamEvent
}

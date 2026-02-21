package io.onepro.xr

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

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

/**
 * One emitted tracking sample from the runtime tracker path.
 *
 * Bias fields reflect the tracker bias terms used for this sample.
 */
data class HeadTrackingSample(
    val sampleIndex: Long,
    val captureMonotonicNanos: Long,
    val deltaTimeSeconds: Float,
    val absoluteOrientation: HeadOrientationDegrees,
    val relativeOrientation: HeadOrientationDegrees,
    val calibrationSampleCount: Int,
    val calibrationTarget: Int,
    val isCalibrated: Boolean,
    val sourceDeviceTimeNs: ULong,
    val factoryGyroBias: Vector3f,
    val runtimeResidualGyroBias: Vector3f,
    val factoryAccelBias: Vector3f
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

data class XrConnectionInfo(
    val networkHandle: Long,
    val interfaceName: String,
    val localSocket: String,
    val remoteSocket: String,
    val connectMs: Long
)

enum class XrSessionErrorCode {
    NETWORK_UNAVAILABLE,
    STARTUP_TIMEOUT,
    STREAM_ERROR,
    INVALID_ARGUMENT,
    NOT_CONNECTED
}

sealed interface XrSessionState {
    data object Idle : XrSessionState

    data object Connecting : XrSessionState

    data class Calibrating(
        val connectionInfo: XrConnectionInfo,
        val calibrationSampleCount: Int,
        val calibrationTarget: Int
    ) : XrSessionState

    data class Streaming(
        val connectionInfo: XrConnectionInfo
    ) : XrSessionState

    data class Error(
        val code: XrSessionErrorCode,
        val message: String,
        val causeType: String,
        val recoverable: Boolean
    ) : XrSessionState

    data object Stopped : XrSessionState
}

enum class XrBiasErrorCode {
    TRANSPORT_ERROR,
    PARSE_ERROR,
    SCHEMA_VALIDATION_ERROR,
    RUNTIME_ERROR
}

/**
 * Bias activation status for tracker correction.
 *
 * `Active` means factory config bias is loaded and runtime residual calibration can proceed.
 */
sealed interface XrBiasState {
    data object Inactive : XrBiasState

    data object LoadingConfig : XrBiasState

    data class Active(
        val fsn: String,
        val glassesVersion: Int
    ) : XrBiasState

    data class Error(
        val code: XrBiasErrorCode,
        val message: String
    ) : XrBiasState
}

enum class XrSensorUpdateSource {
    IMU,
    MAG
}

data class XrImuSample(
    val gx: Float,
    val gy: Float,
    val gz: Float,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val deviceTimeNs: ULong
)

data class XrMagSample(
    val mx: Float,
    val my: Float,
    val mz: Float,
    val deviceTimeNs: ULong
)

data class XrSensorSnapshot(
    val imu: XrImuSample?,
    val magnetometer: XrMagSample?,
    val deviceId: ULong,
    val temperatureCelsius: Float,
    val frameId: OneProFrameId,
    val imuId: Int,
    val reportType: OneProReportType,
    val imuDeviceTimeNs: ULong?,
    val magDeviceTimeNs: ULong?,
    val lastUpdatedSource: XrSensorUpdateSource
)

/**
 * Runtime pose output mode for `poseData`.
 *
 * `RAW_IMU` publishes tracker output as-is.
 * `SMOOTH_IMU` applies additional smoothing to `relativeOrientation`.
 */
enum class XrPoseDataMode {
    RAW_IMU,
    SMOOTH_IMU
}

/**
 * Current physical pose output plus the bias terms applied for this update.
 *
 * `relativeOrientation` is recentered (`zeroView`) but not sensitivity-scaled.
 * It may be additionally smoothed when `poseDataMode` is `SMOOTH_IMU`.
 */
data class XrPoseSnapshot(
    val relativeOrientation: HeadOrientationDegrees,
    val absoluteOrientation: HeadOrientationDegrees,
    val isCalibrated: Boolean,
    val calibrationSampleCount: Int,
    val calibrationTarget: Int,
    val deltaTimeSeconds: Float,
    val sourceDeviceTimeNs: ULong,
    val factoryGyroBias: Vector3f,
    val runtimeResidualGyroBias: Vector3f,
    val factoryAccelBias: Vector3f
)

enum class XrSceneMode(val wireValue: Int) {
    ButtonsEnabled(0),
    ButtonsDisabled(1)
}

enum class XrDisplayInputMode(val wireValue: Int) {
    Regular(0),
    SideBySide(1)
}

enum class XrDimmerLevel(val wireValue: Int) {
    Lightest(0),
    Middle(1),
    Dimmest(2)
}

enum class XrKeyType(val wireValue: UInt) {
    BottomSingleButton(1U),
    FrontRockerButton(2U),
    BackRockerButton(3U),
    TopSingleButton(4U);

    companion object {
        fun fromWireValue(value: UInt): XrKeyType? {
            return entries.firstOrNull { it.wireValue == value }
        }
    }
}

enum class XrKeyState(val wireValue: UInt) {
    Down(1U),
    Up(2U);

    companion object {
        fun fromWireValue(value: UInt): XrKeyState? {
            return entries.firstOrNull { it.wireValue == value }
        }
    }
}

sealed interface XrControlEvent {
    data class KeyStateChange(
        val keyType: XrKeyType,
        val keyState: XrKeyState,
        val deviceTimeNs: Long
    ) : XrControlEvent

    data class UnknownMessage(
        val magic: Int,
        val transactionId: Int?,
        val payload: ByteArray
    ) : XrControlEvent
}

interface OneProXrAdvancedApi {
    val diagnostics: StateFlow<HeadTrackingStreamDiagnostics?>
    val reports: SharedFlow<OneProReportMessage>
    val controlEvents: SharedFlow<XrControlEvent>
}

internal class HeadTrackingControlChannel {
    private val zeroViewRequested = AtomicBoolean(false)
    private val recalibrationRequested = AtomicBoolean(false)

    fun requestZeroView() {
        zeroViewRequested.set(true)
    }

    fun requestRecalibration() {
        recalibrationRequested.set(true)
    }

    fun consumeZeroViewRequest(): Boolean {
        return zeroViewRequested.getAndSet(false)
    }

    fun consumeRecalibrationRequest(): Boolean {
        return recalibrationRequested.getAndSet(false)
    }
}

internal data class HeadTrackingStreamConfig(
    val connectTimeoutMs: Int = 1500,
    val readTimeoutMs: Int = 700,
    val readChunkBytes: Int = 4096,
    val diagnosticsIntervalSamples: Int = 240,
    val calibrationSampleTarget: Int = 500,
    val complementaryFilterAlpha: Float = 0.96f,
    val autoZeroViewOnStart: Boolean = true,
    val autoZeroViewAfterSamples: Int = 3,
    val controlChannel: HeadTrackingControlChannel? = null
)

internal sealed interface HeadTrackingStreamEvent {
    data class BiasStateChanged(
        val state: XrBiasState
    ) : HeadTrackingStreamEvent

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

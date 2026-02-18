package io.onepro.xr

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Network endpoint for a One Pro device
 *
 * Defaults target the common link-local setup used by the demo app
 */
data class OneProXrEndpoint(
    val host: String = "169.254.2.1",
    val controlPort: Int = 52999,
    val streamPort: Int = 52998
)

/** Basic snapshot of a local network interface */
data class InterfaceInfo(
    val name: String,
    val isUp: Boolean,
    val addresses: List<String>
)

/** Android `Network` candidate that may route traffic to the device host */
data class NetworkCandidateInfo(
    val networkHandle: Long,
    val interfaceName: String,
    val addresses: List<String>
)

/** Host-address candidate from direct interface inspection */
data class AddressCandidateInfo(
    val interfaceName: String,
    val address: String
)

/** Routing summary returned by [OneProXrClient.describeRouting] */
data class RoutingSnapshot(
    val interfaces: List<InterfaceInfo>,
    val networkCandidates: List<NetworkCandidateInfo>,
    val addressCandidates: List<AddressCandidateInfo>
)

/** Result of [OneProXrClient.connectControlChannel] */
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

/** Parsed frame metadata returned by [OneProXrClient.readSensorFrames] */
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

/** Observed receive cadence and inferred rate information for sampled frames */
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

/** Result of [OneProXrClient.readSensorFrames] */
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

/** Generic 3D float vector */
data class Vector3f(
    val x: Float,
    val y: Float,
    val z: Float
)

/**
 * One Pro sensor sample decoded from a stream message
 *
 * Axes and ordering match the current parser mapping used by this module
 */
data class OneProSensorSample(
    val gx: Float,
    val gy: Float,
    val gz: Float,
    val ax: Float,
    val ay: Float,
    val az: Float
)

/** Orientation in degrees */
data class HeadOrientationDegrees(
    val pitch: Float,
    val yaw: Float,
    val roll: Float
)

/**
 * Head-tracking output sample emitted during streaming
 *
 * [absoluteOrientation] is the raw fused orientation.
 * [relativeOrientation] is zeroed orientation after `Zero View` offsets are applied.
 */
data class HeadTrackingSample(
    val sampleIndex: Long,
    val captureMonotonicNanos: Long,
    val deltaTimeSeconds: Float,
    val sensorSample: OneProSensorSample,
    val absoluteOrientation: HeadOrientationDegrees,
    val relativeOrientation: HeadOrientationDegrees,
    val calibrationSampleCount: Int,
    val calibrationTarget: Int,
    val isCalibrated: Boolean
)

/** Running stream diagnostics emitted at a configurable cadence */
data class HeadTrackingStreamDiagnostics(
    val trackingSampleCount: Long,
    val parsedMessageCount: Long,
    val rejectedMessageCount: Long,
    val droppedByteCount: Long,
    val tooShortMessageCount: Long,
    val missingSensorMarkerCount: Long,
    val invalidSensorSliceCount: Long,
    val floatDecodeFailureCount: Long,
    val observedSampleRateHz: Double?,
    val receiveDeltaMinMs: Double?,
    val receiveDeltaMaxMs: Double?,
    val receiveDeltaAvgMs: Double?
)

/**
 * Thread-safe control channel used with [HeadTrackingStreamConfig.controlChannel]
 *
 * The stream consumes each request once on the next available sample loop
 */
class HeadTrackingControlChannel {
    private val zeroViewRequested = AtomicBoolean(false)
    private val recalibrationRequested = AtomicBoolean(false)

    /** Requests relative-orientation recentering using the current orientation as the new zero */
    fun requestZeroView() {
        zeroViewRequested.set(true)
    }

    /** Requests full gyro recalibration and a return to calibration mode */
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

/**
 * Runtime tuning options for [OneProXrClient.streamHeadTracking]
 *
 * Defaults are chosen for the demo app and are generally safe for first integration
 */
data class HeadTrackingStreamConfig(
    /** Socket connect timeout for stream connection attempts */
    val connectTimeoutMs: Int = 1500,
    /** Socket read timeout while waiting for stream bytes */
    val readTimeoutMs: Int = 700,
    /** Read buffer size per socket read call */
    val readChunkBytes: Int = 4096,
    /** Emit diagnostics every N tracking samples */
    val diagnosticsIntervalSamples: Int = 240,
    /** Fallback delta time when timestamp deltas are invalid or non-positive */
    val fallbackDeltaTimeSeconds: Float = 0.01f,
    /** Maximum allowed integration delta time for a single update */
    val maxDeltaTimeSeconds: Float = 0.1f,
    /** Number of still samples required before gyro calibration completes */
    val calibrationSampleTarget: Int = 500,
    /** Complementary filter alpha for gyro/accelerometer blend */
    val complementaryFilterAlpha: Float = 0.96f,
    /** Scale factor applied to relative pitch output */
    val pitchScale: Float = 3.0f,
    /** Scale factor applied to relative yaw output */
    val yawScale: Float = 60.0f,
    /** Scale factor applied to relative roll output */
    val rollScale: Float = 1.0f,
    /** Automatically trigger `Zero View` after calibration */
    val autoZeroViewOnStart: Boolean = true,
    /** Number of post-calibration samples to wait before automatic `Zero View` */
    val autoZeroViewAfterSamples: Int = 3,
    /** Optional control channel for zero-view and recalibration commands */
    val controlChannel: HeadTrackingControlChannel? = null
)

/** Event stream emitted by [OneProXrClient.streamHeadTracking] */
sealed interface HeadTrackingStreamEvent {
    /** Stream socket connected and initial transport metadata is available */
    data class Connected(
        val networkHandle: Long,
        val interfaceName: String,
        val localSocket: String,
        val remoteSocket: String,
        val connectMs: Long
    ) : HeadTrackingStreamEvent

    /** Calibration state update emitted at startup and during recalibration */
    data class CalibrationProgress(
        val calibrationSampleCount: Int,
        val calibrationTarget: Int,
        val progressPercent: Float,
        val isComplete: Boolean
    ) : HeadTrackingStreamEvent

    /** New tracking sample with sensor payload and orientation outputs */
    data class TrackingSampleAvailable(
        val sample: HeadTrackingSample
    ) : HeadTrackingStreamEvent

    /** Periodic diagnostics snapshot */
    data class DiagnosticsAvailable(
        val diagnostics: HeadTrackingStreamDiagnostics
    ) : HeadTrackingStreamEvent

    /** Stream ended without throwing */
    data class StreamStopped(
        val reason: String
    ) : HeadTrackingStreamEvent

    /** Stream failed and stopped due to runtime error */
    data class StreamError(
        val error: String
    ) : HeadTrackingStreamEvent
}

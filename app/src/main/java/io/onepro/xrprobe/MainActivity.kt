package io.onepro.xrprobe

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import io.onepro.xr.HeadTrackingStreamDiagnostics
import io.onepro.xr.OneProXrClient
import io.onepro.xr.OneProXrEndpoint
import io.onepro.xr.XrPoseSnapshot
import io.onepro.xr.XrSensorSnapshot
import io.onepro.xr.XrSensorUpdateSource
import io.onepro.xr.XrSessionState
import io.onepro.xrprobe.databinding.ActivityMainBinding
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var startJob: Job? = null
    private var sessionStateJob: Job? = null
    private var sensorJob: Job? = null
    private var poseJob: Job? = null
    private var diagnosticsJob: Job? = null
    private var logsVisible = false
    private var calibrationComplete = false
    private var cameraSensitivity = 1.0f
    private var client: OneProXrClient? = null
    private var latestDiagnostics: HeadTrackingStreamDiagnostics? = null
    private var latestSensorSnapshot: XrSensorSnapshot? = null
    private var latestPoseSnapshot: XrPoseSnapshot? = null
    private var lastTelemetryUpdateNanos = 0L
    private var lastImuReportLogNanos = 0L
    private var lastMagReportLogNanos = 0L
    private var lastOtherReportLogNanos = 0L
    private var lastCalibrationLogCount = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonStartTest.setOnClickListener { startTest() }
        binding.buttonStopTest.setOnClickListener { stopTest(updateStatus = true) }
        binding.buttonViewLogs.setOnClickListener { setLogsVisible(!logsVisible) }
        binding.buttonZeroView.setOnClickListener { requestZeroView() }
        binding.buttonRecalibrate.setOnClickListener { requestRecalibration() }
        binding.buttonSensitivityDown.setOnClickListener { adjustSensitivity(-0.1f) }
        binding.buttonSensitivityUp.setOnClickListener { adjustSensitivity(0.1f) }

        setLogsVisible(false)
        setRunningState(false)
        renderSensitivity()
        binding.textStatus.text = getString(R.string.status_idle)
        binding.textTelemetry.text = getString(R.string.telemetry_placeholder)
    }

    override fun onResume() {
        super.onResume()
        binding.orientationView.onResume()
    }

    override fun onPause() {
        binding.orientationView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        stopTest(updateStatus = false)
        uiScope.cancel()
        super.onDestroy()
    }

    private fun startTest() {
        if (client != null || startJob?.isActive == true) {
            return
        }

        val host = binding.inputHost.text.toString().trim()
        val ports = parsePorts(binding.inputPorts.text.toString())
        if (host.isEmpty()) {
            appendLog("ERROR host is empty")
            return
        }

        val endpoint = buildEndpoint(host, ports)
        val xrClient = OneProXrClient(applicationContext, endpoint)
        client = xrClient

        latestDiagnostics = null
        latestSensorSnapshot = null
        latestPoseSnapshot = null
        lastTelemetryUpdateNanos = 0L
        lastImuReportLogNanos = 0L
        lastMagReportLogNanos = 0L
        lastOtherReportLogNanos = 0L
        lastCalibrationLogCount = -1
        calibrationComplete = false

        binding.orientationView.resetCamera()
        binding.orientationView.setSensitivity(cameraSensitivity)
        binding.textStatus.text = getString(R.string.status_connecting)
        binding.textTelemetry.text = getString(R.string.telemetry_placeholder)
        setRunningState(true)

        appendLog(
            "=== test start ${nowIso()} host=${endpoint.host} control=${endpoint.controlPort} stream=${endpoint.streamPort} sensitivity=${formatSensitivity()} ==="
        )

        attachClientCollectors(xrClient)
        startJob = uiScope.launch {
            try {
                val info = xrClient.start()
                appendLog(
                    "connected iface=${info.interfaceName} netId=${info.networkHandle} connectMs=${info.connectMs} local=${info.localSocket} remote=${info.remoteSocket}"
                )
            } catch (_: CancellationException) {
                appendLog("=== test cancelled ${nowIso()} ===")
            } catch (t: Throwable) {
                binding.textStatus.text = getString(
                    R.string.status_stream_error,
                    t.message ?: t.javaClass.simpleName
                )
                appendLog("stream start error=${t.javaClass.simpleName}:${t.message ?: "no-message"}")
                stopTest(updateStatus = false)
            } finally {
                startJob = null
            }
        }
    }

    private fun stopTest(updateStatus: Boolean) {
        val xrClient = client
        client = null
        startJob?.cancel()
        startJob = null
        cancelCollectors()
        if (xrClient != null) {
            uiScope.launch {
                try {
                    xrClient.stop()
                } catch (_: Throwable) {
                }
            }
        }
        calibrationComplete = false
        if (updateStatus) {
            binding.textStatus.text = getString(R.string.status_stopped)
            appendLog("=== stop requested ${nowIso()} ===")
        }
        binding.orientationView.resetCamera()
        setRunningState(false)
    }

    private fun attachClientCollectors(xrClient: OneProXrClient) {
        cancelCollectors()
        sessionStateJob = uiScope.launch {
            xrClient.sessionState.collect { state ->
                handleSessionState(state)
            }
        }
        sensorJob = uiScope.launch {
            xrClient.sensorData.collect { snapshot ->
                if (snapshot == null) {
                    return@collect
                }
                latestSensorSnapshot = snapshot
                maybeLogSensorSnapshot(snapshot)
                maybeRenderTelemetry()
            }
        }
        poseJob = uiScope.launch {
            xrClient.poseData.collect { pose ->
                if (pose == null) {
                    return@collect
                }
                latestPoseSnapshot = pose
                calibrationComplete = pose.isCalibrated
                binding.orientationView.updateRelativeOrientation(pose.relativeOrientation)
                setRunningState(isSessionActive())
            }
        }
        diagnosticsJob = uiScope.launch {
            xrClient.advanced.diagnostics.collect { diagnostics ->
                if (diagnostics == null) {
                    return@collect
                }
                latestDiagnostics = diagnostics
                if (isSessionActive()) {
                    binding.textStatus.text = formatStatus(diagnostics)
                }
                appendLog(formatDiagnostics(diagnostics))
            }
        }
    }

    private fun cancelCollectors() {
        sessionStateJob?.cancel()
        sessionStateJob = null
        sensorJob?.cancel()
        sensorJob = null
        poseJob?.cancel()
        poseJob = null
        diagnosticsJob?.cancel()
        diagnosticsJob = null
    }

    private fun handleSessionState(state: XrSessionState) {
        when (state) {
            XrSessionState.Idle -> {
                calibrationComplete = false
                binding.textStatus.text = getString(R.string.status_idle)
            }

            XrSessionState.Connecting -> {
                calibrationComplete = false
                binding.textStatus.text = getString(R.string.status_connecting)
            }

            is XrSessionState.Calibrating -> {
                calibrationComplete = false
                val target = state.calibrationTarget.coerceAtLeast(1)
                val percent = (state.calibrationSampleCount.toFloat() / target.toFloat()) * 100.0f
                binding.textStatus.text = getString(
                    R.string.status_calibrating,
                    percent,
                    state.calibrationSampleCount,
                    target
                )
                if (
                    state.calibrationSampleCount != lastCalibrationLogCount &&
                    (state.calibrationSampleCount == 1 || state.calibrationSampleCount % 50 == 0)
                ) {
                    appendLog(
                        "calibrating ${String.format(Locale.US, "%.1f", percent)}% (${state.calibrationSampleCount}/$target)"
                    )
                    lastCalibrationLogCount = state.calibrationSampleCount
                }
            }

            is XrSessionState.Streaming -> {
                calibrationComplete = true
                val diagnostics = latestDiagnostics
                if (diagnostics != null) {
                    binding.textStatus.text = formatStatus(diagnostics)
                } else {
                    binding.textStatus.text = getString(
                        R.string.status_connected,
                        state.connectionInfo.interfaceName,
                        state.connectionInfo.connectMs
                    )
                }
            }

            is XrSessionState.Error -> {
                calibrationComplete = false
                binding.textStatus.text = getString(R.string.status_stream_error, state.message)
                appendLog("stream error=${state.code}:${state.message}")
            }

            XrSessionState.Stopped -> {
                calibrationComplete = false
                binding.textStatus.text = getString(R.string.status_stopped)
            }
        }
        setRunningState(isSessionActive())
    }

    private fun requestZeroView() {
        val xrClient = client
        if (xrClient == null || !isSessionActive()) {
            appendLog("zero view ignored (test not running)")
            return
        }
        if (!calibrationComplete) {
            appendLog("zero view ignored (still calibrating)")
            return
        }
        uiScope.launch {
            try {
                xrClient.zeroView()
                appendLog("zero view requested ${nowIso()}")
            } catch (t: Throwable) {
                appendLog("zero view failed ${t.javaClass.simpleName}:${t.message ?: "no-message"}")
            }
        }
    }

    private fun requestRecalibration() {
        val xrClient = client
        if (xrClient == null || !isSessionActive()) {
            appendLog("recalibration ignored (test not running)")
            return
        }
        uiScope.launch {
            try {
                xrClient.recalibrate()
                calibrationComplete = false
                setRunningState(true)
                appendLog("recalibration requested ${nowIso()}")
            } catch (t: Throwable) {
                appendLog("recalibration failed ${t.javaClass.simpleName}:${t.message ?: "no-message"}")
            }
        }
    }

    private fun setRunningState(running: Boolean) {
        binding.buttonStartTest.isEnabled = !running
        binding.buttonStopTest.isEnabled = running
        binding.buttonRecalibrate.isEnabled = running
        binding.buttonSensitivityDown.isEnabled = running
        binding.buttonSensitivityUp.isEnabled = running
        binding.inputHost.isEnabled = !running
        binding.inputPorts.isEnabled = !running
        binding.buttonZeroView.isEnabled = running && calibrationComplete
    }

    private fun adjustSensitivity(delta: Float) {
        cameraSensitivity = (cameraSensitivity + delta).coerceIn(0.1f, 2.0f)
        renderSensitivity()
        binding.orientationView.setSensitivity(cameraSensitivity)
        appendLog("sensitivity=${formatSensitivity()}")
    }

    private fun renderSensitivity() {
        binding.textSensitivityValue.text = getString(R.string.sensitivity_value, cameraSensitivity)
    }

    private fun maybeLogSensorSnapshot(snapshot: XrSensorSnapshot) {
        val nowNanos = System.nanoTime()
        when (snapshot.lastUpdatedSource) {
            XrSensorUpdateSource.IMU -> {
                if (nowNanos - lastImuReportLogNanos >= 500_000_000L) {
                    val imu = snapshot.imu
                    if (imu != null) {
                        appendLog(
                            "[XR][IMU] deviceTimeNs=${imu.deviceTimeNs} frame=${snapshot.frameId.asUInt24LittleEndian} imuId=${snapshot.imuId} tempC=${snapshot.temperatureCelsius.toDisplay()} gyro=[${imu.gx.toDisplay()},${imu.gy.toDisplay()},${imu.gz.toDisplay()}] accel=[${imu.ax.toDisplay()},${imu.ay.toDisplay()},${imu.az.toDisplay()}]"
                        )
                        lastImuReportLogNanos = nowNanos
                    }
                }
            }

            XrSensorUpdateSource.MAG -> {
                if (nowNanos - lastMagReportLogNanos >= 500_000_000L) {
                    val mag = snapshot.magnetometer
                    if (mag != null) {
                        appendLog(
                            "[XR][MAG] deviceTimeNs=${mag.deviceTimeNs} frame=${snapshot.frameId.asUInt24LittleEndian} imuId=${snapshot.imuId} tempC=${snapshot.temperatureCelsius.toDisplay()} mag=[${mag.mx.toDisplay()},${mag.my.toDisplay()},${mag.mz.toDisplay()}]"
                        )
                        lastMagReportLogNanos = nowNanos
                    }
                }
            }
        }
        if (nowNanos - lastOtherReportLogNanos >= 1_500_000_000L) {
            val deviceTimeNs = if (snapshot.lastUpdatedSource == XrSensorUpdateSource.IMU) {
                snapshot.imuDeviceTimeNs
            } else {
                snapshot.magDeviceTimeNs
            }
            appendLog(
                "[XR][OTHER] reportType=${snapshot.reportType} deviceId=${snapshot.deviceId} deviceTimeNs=${deviceTimeNs ?: "n/a"} frame=${snapshot.frameId.asUInt24LittleEndian} imuId=${snapshot.imuId} tempC=${snapshot.temperatureCelsius.toDisplay()}"
            )
            lastOtherReportLogNanos = nowNanos
        }
    }

    private fun maybeRenderTelemetry() {
        val snapshot = latestSensorSnapshot ?: return
        val nowNanos = System.nanoTime()
        if (nowNanos - lastTelemetryUpdateNanos < 50_000_000L) {
            return
        }
        lastTelemetryUpdateNanos = nowNanos

        val imu = snapshot.imu
        val mag = snapshot.magnetometer
        val gyroLine = if (imu == null) {
            "gyroscope: [n/a, n/a, n/a]"
        } else {
            "gyroscope: [${imu.gx.toDisplay()}, ${imu.gy.toDisplay()}, ${imu.gz.toDisplay()}]"
        }
        val accelLine = if (imu == null) {
            "accelerometer: [n/a, n/a, n/a]"
        } else {
            "accelerometer: [${imu.ax.toDisplay()}, ${imu.ay.toDisplay()}, ${imu.az.toDisplay()}]"
        }
        val magLine = if (mag == null) {
            "magnetometer: [n/a, n/a, n/a]"
        } else {
            "magnetometer: [${mag.mx.toDisplay()}, ${mag.my.toDisplay()}, ${mag.mz.toDisplay()}]"
        }
        binding.textTelemetry.text = listOf(gyroLine, accelLine, magLine).joinToString("\n")
    }

    private fun formatStatus(diagnostics: HeadTrackingStreamDiagnostics): String {
        return getString(
            R.string.status_streaming,
            diagnostics.trackingSampleCount,
            diagnostics.observedSampleRateHz.toDisplay(),
            diagnostics.receiveDeltaAvgMs.toDisplay(),
            diagnostics.droppedByteCount,
            diagnostics.rejectedMessageCount,
            diagnostics.imuReportCount,
            diagnostics.magnetometerReportCount
        )
    }

    private fun formatDiagnostics(diagnostics: HeadTrackingStreamDiagnostics): String {
        return "diag parser parsed=${diagnostics.parsedMessageCount} imu=${diagnostics.imuReportCount} mag=${diagnostics.magnetometerReportCount} rejected=${diagnostics.rejectedMessageCount} dropped=${diagnostics.droppedByteCount} rejectBreakdown[length=${diagnostics.invalidReportLengthCount},decode=${diagnostics.decodeErrorCount},type=${diagnostics.unknownReportTypeCount}] trackingHz=${diagnostics.observedSampleRateHz.toDisplay()} rxMs[min=${diagnostics.receiveDeltaMinMs.toDisplay()},avg=${diagnostics.receiveDeltaAvgMs.toDisplay()},max=${diagnostics.receiveDeltaMaxMs.toDisplay()}]"
    }

    private fun setLogsVisible(visible: Boolean) {
        logsVisible = visible
        binding.logsPanel.visibility = if (visible) View.VISIBLE else View.GONE
        binding.buttonViewLogs.text = if (visible) {
            getString(R.string.action_hide_logs)
        } else {
            getString(R.string.action_view_logs)
        }
    }

    private fun appendLog(line: String) {
        binding.textOutput.append(line)
        binding.textOutput.append("\n")
        binding.outputScroll.post {
            binding.outputScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun buildEndpoint(host: String, ports: List<Int>): OneProXrEndpoint {
        val controlPort = ports.getOrNull(0) ?: 52999
        val streamPort = ports.getOrNull(1) ?: if (controlPort == 52999) 52998 else 52999
        return OneProXrEndpoint(
            host = host,
            controlPort = controlPort,
            streamPort = streamPort
        )
    }

    private fun parsePorts(raw: String): List<Int> {
        return raw.split(",")
            .mapNotNull { token -> token.trim().toIntOrNull()?.takeIf { it in 1..65535 } }
            .distinct()
    }

    private fun nowIso(): String {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }

    private fun formatSensitivity(): String {
        return String.format(Locale.US, "%.1f", cameraSensitivity)
    }

    private fun isSessionActive(): Boolean {
        val state = client?.sessionState?.value ?: return false
        return state is XrSessionState.Connecting ||
            state is XrSessionState.Calibrating ||
            state is XrSessionState.Streaming
    }

    private fun Double?.toDisplay(): String {
        return this?.let { String.format(Locale.US, "%.3f", it) } ?: "n/a"
    }

    private fun Float.toDisplay(): String {
        return String.format(Locale.US, "%.3f", this)
    }
}

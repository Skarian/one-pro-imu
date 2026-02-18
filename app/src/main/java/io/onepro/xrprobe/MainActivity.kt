package io.onepro.xrprobe

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import io.onepro.xrprobe.databinding.ActivityMainBinding
import io.onepro.xr.HeadTrackingControlChannel
import io.onepro.xr.HeadTrackingSample
import io.onepro.xr.HeadTrackingStreamConfig
import io.onepro.xr.HeadTrackingStreamDiagnostics
import io.onepro.xr.HeadTrackingStreamEvent
import io.onepro.xr.OneProReportMessage
import io.onepro.xr.OneProReportType
import io.onepro.xr.OneProXrClient
import io.onepro.xr.OneProXrEndpoint
import java.io.File
import java.security.MessageDigest
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

    private var testJob: Job? = null
    private var captureJob: Job? = null
    private var logsVisible = false
    private var latestDiagnostics: HeadTrackingStreamDiagnostics? = null
    private var latestImuReport: OneProReportMessage? = null
    private var latestMagnetometerReport: OneProReportMessage? = null
    private var latestTrackingSample: HeadTrackingSample? = null
    private var lastTelemetryUpdateNanos = 0L
    private var lastImuReportLogNanos = 0L
    private var lastMagReportLogNanos = 0L
    private var lastOtherReportLogNanos = 0L
    private var controlChannel: HeadTrackingControlChannel? = null
    private var calibrationComplete = false
    private var cameraSensitivity = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonStartTest.setOnClickListener { startTest() }
        binding.buttonStopTest.setOnClickListener { stopTest(updateStatus = true) }
        binding.buttonViewLogs.setOnClickListener { setLogsVisible(!logsVisible) }
        binding.buttonZeroView.setOnClickListener { requestZeroView() }
        binding.buttonRecalibrate.setOnClickListener { requestRecalibration() }
        binding.buttonCaptureFixture.setOnClickListener { startFixtureCapture() }
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
        captureJob?.cancel()
        captureJob = null
        uiScope.cancel()
        super.onDestroy()
    }

    private fun startTest() {
        if (testJob?.isActive == true || captureJob?.isActive == true) {
            return
        }

        val host = binding.inputHost.text.toString().trim()
        val ports = parsePorts(binding.inputPorts.text.toString())
        if (host.isEmpty()) {
            appendLog("ERROR host is empty")
            return
        }

        val endpoint = buildEndpoint(host, ports)
        val client = OneProXrClient(applicationContext, endpoint)
        val control = HeadTrackingControlChannel()
        controlChannel = control

        latestDiagnostics = null
        latestImuReport = null
        latestMagnetometerReport = null
        latestTrackingSample = null
        lastTelemetryUpdateNanos = 0L
        lastImuReportLogNanos = 0L
        lastMagReportLogNanos = 0L
        lastOtherReportLogNanos = 0L
        calibrationComplete = false

        binding.orientationView.resetCamera()
        binding.orientationView.setSensitivity(cameraSensitivity)
        binding.textStatus.text = getString(R.string.status_connecting)
        binding.textTelemetry.text = getString(R.string.telemetry_placeholder)
        setRunningState(true)

        appendLog(
            "=== test start ${nowIso()} host=${endpoint.host} control=${endpoint.controlPort} stream=${endpoint.streamPort} sensitivity=${formatSensitivity()} ==="
        )

        testJob = uiScope.launch {
            try {
                client.streamHeadTracking(
                    config = HeadTrackingStreamConfig(
                        diagnosticsIntervalSamples = 240,
                        controlChannel = control
                    )
                ).collect { event ->
                    handleStreamEvent(event)
                }
            } catch (_: CancellationException) {
                appendLog("=== test cancelled ${nowIso()} ===")
            } finally {
                controlChannel = null
                calibrationComplete = false
                setRunningState(false)
                testJob = null
            }
        }
    }

    private fun startFixtureCapture() {
        if (captureJob?.isActive == true || testJob?.isActive == true) {
            appendLog("fixture capture ignored (stream test is running)")
            return
        }

        val host = binding.inputHost.text.toString().trim()
        val ports = parsePorts(binding.inputPorts.text.toString())
        if (host.isEmpty()) {
            appendLog("ERROR host is empty")
            return
        }

        val endpoint = buildEndpoint(host, ports)
        val client = OneProXrClient(applicationContext, endpoint)
        val captureStartedAt = OffsetDateTime.now()
        val captureStamp = captureStartedAt.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX"))

        appendLog("fixture capture start ${nowIso()} host=${endpoint.host} stream=${endpoint.streamPort}")
        captureJob = uiScope.launch {
            try {
                val result = client.captureStreamBytes(
                    durationSeconds = 45,
                    maxCaptureBytes = 5 * 1024 * 1024,
                    readChunkBytes = 4096
                )
                if (!result.success) {
                    appendLog("fixture capture failed status=${result.readStatus} error=${result.error}")
                    return@launch
                }

                val baseDir = getExternalFilesDir(null) ?: cacheDir
                val captureDir = File(baseDir, "onepro-fixtures")
                if (!captureDir.exists()) {
                    captureDir.mkdirs()
                }
                val baseName = "onepro_stream_capture_$captureStamp"
                val binFile = File(captureDir, "$baseName.bin")
                val metaFile = File(captureDir, "$baseName.meta.json")
                binFile.writeBytes(result.payload)

                val checksum = sha256Hex(result.payload)
                val metadata = """
                    {
                      "schema_version": "onepro_stream_fixture.v1",
                      "captured_at_utc": "${captureStartedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}",
                      "duration_seconds": ${result.durationMs.toDouble() / 1000.0},
                      "total_bytes": ${result.totalBytes},
                      "sha256": "$checksum",
                      "parser_contract_version": "phase1-report-v1"
                    }
                """.trimIndent()
                metaFile.writeText(metadata)

                appendLog("fixture capture saved bytes=${result.totalBytes} durationMs=${result.durationMs} status=${result.readStatus}")
                appendLog("fixture bin=${binFile.absolutePath}")
                appendLog("fixture meta=${metaFile.absolutePath}")
                appendLog("fixture sha256=$checksum")
            } catch (_: CancellationException) {
                appendLog("fixture capture cancelled")
            } catch (t: Throwable) {
                appendLog("fixture capture error ${t.javaClass.simpleName}:${t.message ?: "no-message"}")
            } finally {
                captureJob = null
                setRunningState(testJob?.isActive == true)
            }
        }
        setRunningState(false)
    }

    private fun stopTest(updateStatus: Boolean) {
        testJob?.cancel()
        testJob = null
        controlChannel = null
        calibrationComplete = false
        if (updateStatus) {
            binding.textStatus.text = getString(R.string.status_stopped)
            appendLog("=== stop requested ${nowIso()} ===")
        }
        binding.orientationView.resetCamera()
        setRunningState(false)
    }

    private fun setRunningState(running: Boolean) {
        val capturing = captureJob?.isActive == true
        binding.buttonStartTest.isEnabled = !running && !capturing
        binding.buttonStopTest.isEnabled = running
        binding.buttonRecalibrate.isEnabled = running
        binding.buttonSensitivityDown.isEnabled = running
        binding.buttonSensitivityUp.isEnabled = running
        binding.buttonCaptureFixture.isEnabled = !running && !capturing
        binding.inputHost.isEnabled = !running && !capturing
        binding.inputPorts.isEnabled = !running && !capturing
        binding.buttonZeroView.isEnabled = running && calibrationComplete
    }

    private fun requestZeroView() {
        val control = controlChannel
        if (control == null || testJob?.isActive != true) {
            appendLog("zero view ignored (test not running)")
            return
        }
        if (!calibrationComplete) {
            appendLog("zero view ignored (still calibrating)")
            return
        }
        control.requestZeroView()
        appendLog("zero view requested ${nowIso()}")
    }

    private fun requestRecalibration() {
        val control = controlChannel
        if (control == null || testJob?.isActive != true) {
            appendLog("recalibration ignored (test not running)")
            return
        }
        control.requestRecalibration()
        calibrationComplete = false
        setRunningState(true)
        appendLog("recalibration requested ${nowIso()}")
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

    private fun handleStreamEvent(event: HeadTrackingStreamEvent) {
        when (event) {
            is HeadTrackingStreamEvent.Connected -> {
                binding.textStatus.text = getString(
                    R.string.status_connected,
                    event.interfaceName,
                    event.connectMs
                )
                appendLog(
                    "connected iface=${event.interfaceName} netId=${event.networkHandle} connectMs=${event.connectMs} local=${event.localSocket} remote=${event.remoteSocket}"
                )
            }

            is HeadTrackingStreamEvent.CalibrationProgress -> {
                calibrationComplete = event.isComplete
                binding.textStatus.text = if (event.isComplete) {
                    getString(R.string.status_calibration_complete)
                } else {
                    getString(
                        R.string.status_calibrating,
                        event.progressPercent,
                        event.calibrationSampleCount,
                        event.calibrationTarget
                    )
                }
                if (event.isComplete) {
                    appendLog("calibration complete")
                } else if (
                    event.calibrationSampleCount == 1 ||
                    event.calibrationSampleCount % 50 == 0
                ) {
                    appendLog(
                        "calibrating ${String.format(Locale.US, "%.1f", event.progressPercent)}% (${event.calibrationSampleCount}/${event.calibrationTarget})"
                    )
                }
                setRunningState(true)
            }

            is HeadTrackingStreamEvent.ReportAvailable -> {
                when (event.report.reportType) {
                    OneProReportType.IMU -> latestImuReport = event.report
                    OneProReportType.MAGNETOMETER -> latestMagnetometerReport = event.report
                }
                maybeLogReport(event.report)
                latestTrackingSample?.let { maybeRenderTelemetry(it) }
            }

            is HeadTrackingStreamEvent.TrackingSampleAvailable -> {
                calibrationComplete = event.sample.isCalibrated
                latestTrackingSample = event.sample
                binding.orientationView.updateRelativeOrientation(event.sample.relativeOrientation)
                maybeRenderTelemetry(event.sample)
                if (!binding.buttonZeroView.isEnabled) {
                    setRunningState(true)
                }
            }

            is HeadTrackingStreamEvent.DiagnosticsAvailable -> {
                latestDiagnostics = event.diagnostics
                if (calibrationComplete) {
                    binding.textStatus.text = formatStatus(event.diagnostics)
                }
                appendLog(formatDiagnostics(event.diagnostics))
            }

            is HeadTrackingStreamEvent.StreamStopped -> {
                binding.textStatus.text = getString(R.string.status_stream_stopped, event.reason)
                appendLog("stream stopped reason=${event.reason}")
                calibrationComplete = false
                setRunningState(false)
            }

            is HeadTrackingStreamEvent.StreamError -> {
                binding.textStatus.text = getString(R.string.status_stream_error, event.error)
                appendLog("stream error=${event.error}")
                calibrationComplete = false
                setRunningState(false)
            }
        }
    }

    private fun maybeLogReport(report: OneProReportMessage) {
        val nowNanos = System.nanoTime()
        when (report.reportType) {
            OneProReportType.IMU -> {
                if (nowNanos - lastImuReportLogNanos < 500_000_000L) {
                    return
                }
                lastImuReportLogNanos = nowNanos
                appendLog(
                    "[XR][IMU] deviceTimeNs=${report.hmdTimeNanosDevice} frame=${report.frameId.asUInt24LittleEndian} imuId=${report.imuId} tempC=${report.temperatureCelsius.toDisplay()} gyro=[${report.gx.toDisplay()},${report.gy.toDisplay()},${report.gz.toDisplay()}] accel=[${report.ax.toDisplay()},${report.ay.toDisplay()},${report.az.toDisplay()}]"
                )
            }

            OneProReportType.MAGNETOMETER -> {
                if (nowNanos - lastMagReportLogNanos < 500_000_000L) {
                    return
                }
                lastMagReportLogNanos = nowNanos
                appendLog(
                    "[XR][MAG] deviceTimeNs=${report.hmdTimeNanosDevice} frame=${report.frameId.asUInt24LittleEndian} imuId=${report.imuId} tempC=${report.temperatureCelsius.toDisplay()} mag=[${report.mx.toDisplay()},${report.my.toDisplay()},${report.mz.toDisplay()}]"
                )
            }
        }
        if (nowNanos - lastOtherReportLogNanos < 1_500_000_000L) {
            return
        }
        lastOtherReportLogNanos = nowNanos
        appendLog(
            "[XR][OTHER] reportType=${report.reportType} deviceId=${report.deviceId} deviceTimeNs=${report.hmdTimeNanosDevice} frame=${report.frameId.asUInt24LittleEndian} imuId=${report.imuId} tempC=${report.temperatureCelsius.toDisplay()}"
        )
    }

    private fun maybeRenderTelemetry(sample: HeadTrackingSample) {
        val nowNanos = System.nanoTime()
        if (nowNanos - lastTelemetryUpdateNanos < 50_000_000L) {
            return
        }
        lastTelemetryUpdateNanos = nowNanos

        val imu = latestImuReport
        val mag = latestMagnetometerReport
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

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        digest.forEach { value ->
            val v = value.toInt() and 0xFF
            out.append((v ushr 4).toString(16))
            out.append((v and 0x0F).toString(16))
        }
        return out.toString()
    }

    private fun Double?.toDisplay(): String {
        return this?.let { String.format(Locale.US, "%.3f", it) } ?: "n/a"
    }

    private fun Float.toDisplay(): String {
        return String.format(Locale.US, "%.3f", this)
    }
}

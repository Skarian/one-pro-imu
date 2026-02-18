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
import io.onepro.xr.OneProXrEndpoint
import io.onepro.xr.OneProXrClient
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
    private var logsVisible = false
    private var latestDiagnostics: HeadTrackingStreamDiagnostics? = null
    private var lastTelemetryUpdateNanos = 0L
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
        if (testJob?.isActive == true) {
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
        lastTelemetryUpdateNanos = 0L
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
        binding.buttonStartTest.isEnabled = !running
        binding.buttonStopTest.isEnabled = running
        binding.buttonRecalibrate.isEnabled = running
        binding.buttonSensitivityDown.isEnabled = running
        binding.buttonSensitivityUp.isEnabled = running
        binding.inputHost.isEnabled = !running
        binding.inputPorts.isEnabled = !running
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

            is HeadTrackingStreamEvent.TrackingSampleAvailable -> {
                calibrationComplete = event.sample.isCalibrated
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

    private fun maybeRenderTelemetry(sample: HeadTrackingSample) {
        val nowNanos = System.nanoTime()
        if (nowNanos - lastTelemetryUpdateNanos < 50_000_000L) {
            return
        }
        lastTelemetryUpdateNanos = nowNanos

        val diagnostics = latestDiagnostics
        val sampleRate = diagnostics?.observedSampleRateHz?.toDisplay()
        binding.textTelemetry.text = getString(
            R.string.telemetry_format,
            sample.sampleIndex,
            sampleRate,
            sample.relativeOrientation.pitch,
            sample.relativeOrientation.yaw,
            sample.relativeOrientation.roll,
            sample.absoluteOrientation.pitch,
            sample.absoluteOrientation.yaw,
            sample.absoluteOrientation.roll,
            sample.deltaTimeSeconds * 1000.0f,
            cameraSensitivity
        )
    }

    private fun formatStatus(diagnostics: HeadTrackingStreamDiagnostics): String {
        return getString(
            R.string.status_streaming,
            diagnostics.trackingSampleCount,
            diagnostics.observedSampleRateHz.toDisplay(),
            diagnostics.receiveDeltaAvgMs.toDisplay(),
            diagnostics.droppedByteCount,
            diagnostics.rejectedMessageCount
        )
    }

    private fun formatDiagnostics(diagnostics: HeadTrackingStreamDiagnostics): String {
        return "diag samples=${diagnostics.trackingSampleCount} parsed=${diagnostics.parsedMessageCount} rejected=${diagnostics.rejectedMessageCount} dropped=${diagnostics.droppedByteCount} hz=${diagnostics.observedSampleRateHz.toDisplay()} rxMs[min=${diagnostics.receiveDeltaMinMs.toDisplay()},avg=${diagnostics.receiveDeltaAvgMs.toDisplay()},max=${diagnostics.receiveDeltaMaxMs.toDisplay()}] rejectBreakdown[short=${diagnostics.tooShortMessageCount},marker=${diagnostics.missingSensorMarkerCount},slice=${diagnostics.invalidSensorSliceCount},float=${diagnostics.floatDecodeFailureCount}]"
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

    private fun Double?.toDisplay(): String {
        return this?.let { String.format(Locale.US, "%.3f", it) } ?: "n/a"
    }
}

# Android Library Guide (`oneproxr`)

This library gives Android apps direct access to XREAL One Pro report data and head tracking without the Unity-based XREAL SDK

## What this library provides

- Link-local TCP routing helpers for One Pro control/report ports
- Full typed decode of One Pro report payload fields:
  - `device_id`
  - `hmd_time_nanos_device`
  - `report_type`
  - `gx/gy/gz`
  - `ax/ay/az`
  - `mx/my/mz`
  - `temperature`
  - `imu_id`
  - `frame_id`
- A simple tracking flow for most apps (`streamHeadTracking`) with calibration + zero view
- Advanced raw report access through `HeadTrackingStreamEvent.ReportAvailable`
- Stream diagnostics counters for report routing and parser health
- A capture helper (`captureStreamBytes`) for fixture generation and parser regression tests

## Current scope

- Target device: XREAL One Pro
- Transport: direct TCP (`169.254.2.1` by default)
- Tracking output: orientation in degrees (`pitch`, `yaw`, `roll`)
- Rendering/scene management stays in your app

## Prerequisites

- Android `minSdk 26`
- Android `compileSdk 35`
- Java/Kotlin target 17
- Coroutines in your app module
- XREAL One Pro connected and reachable from phone
- Glasses mode set to `Follow` (stabilization off)

## Add the library

### Source module (recommended)

`settings.gradle.kts`

```kotlin
include(":app", ":oneproxr")
```

`app/build.gradle.kts`

```kotlin
dependencies {
    implementation(project(":oneproxr"))
}
```

### Optional Maven artifact

Coordinates:

```kotlin
implementation("io.onepro:oneproxr:<version>")
```

Repo setup and auth are unchanged from README

## Required permissions

The library manifest is intentionally empty, so the host app must declare:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

`ACCESS_NETWORK_STATE` is required because the client picks the correct Android `Network.socketFactory` for link-local routing

## Quickstart

```kotlin
import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.onepro.xr.HeadTrackingControlChannel
import io.onepro.xr.HeadTrackingStreamConfig
import io.onepro.xr.HeadTrackingStreamEvent
import io.onepro.xr.OneProXrClient
import io.onepro.xr.OneProXrEndpoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TrackingActivity : AppCompatActivity() {
    private val endpoint = OneProXrEndpoint(host = "169.254.2.1", controlPort = 52999, streamPort = 52998)
    private val controlChannel = HeadTrackingControlChannel()
    private lateinit var client: OneProXrClient
    private var streamJob: Job? = null
    private var calibrationComplete = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = OneProXrClient(applicationContext, endpoint)
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    fun startTracking() {
        if (streamJob != null) return
        streamJob = lifecycleScope.launch {
            client.streamHeadTracking(
                HeadTrackingStreamConfig(
                    diagnosticsIntervalSamples = 240,
                    controlChannel = controlChannel
                )
            ).collect { event ->
                when (event) {
                    is HeadTrackingStreamEvent.Connected -> {
                        Log.i("xr", "connected ${event.interfaceName} in ${event.connectMs}ms")
                    }
                    is HeadTrackingStreamEvent.CalibrationProgress -> {
                        calibrationComplete = event.isComplete
                    }
                    is HeadTrackingStreamEvent.ReportAvailable -> {
                        if (event.report.reportType.name == "MAGNETOMETER") {
                            Log.d("xr", "mag mx=${event.report.mx} my=${event.report.my} mz=${event.report.mz}")
                        }
                    }
                    is HeadTrackingStreamEvent.TrackingSampleAvailable -> {
                        val rel = event.sample.relativeOrientation
                        Log.d("xr", "rel pitch=${rel.pitch} yaw=${rel.yaw} roll=${rel.roll}")
                    }
                    is HeadTrackingStreamEvent.DiagnosticsAvailable -> {
                        Log.d("xr", "imu=${event.diagnostics.imuReportCount} mag=${event.diagnostics.magnetometerReportCount}")
                    }
                    is HeadTrackingStreamEvent.StreamStopped -> {
                        streamJob = null
                    }
                    is HeadTrackingStreamEvent.StreamError -> {
                        Log.e("xr", "stream error ${event.error}")
                        streamJob = null
                    }
                }
            }
        }
    }

    fun zeroView() {
        if (calibrationComplete) {
            controlChannel.requestZeroView()
        }
    }

    fun recalibrate() {
        controlChannel.requestRecalibration()
        calibrationComplete = false
    }
}
```

## API notes

### `OneProXrClient`

- `describeRouting()`
- `connectControlChannel()`
- `readSensorFrames()` (legacy low-level frame peek helper)
- `captureStreamBytes()` (raw report-port capture for fixture generation)
- `streamHeadTracking(config)`

### `HeadTrackingStreamEvent`

- `Connected`
- `CalibrationProgress`
- `ReportAvailable` (typed raw report payload)
- `TrackingSampleAvailable` (orientation-first output)
- `DiagnosticsAvailable`
- `StreamStopped`
- `StreamError`

### `HeadTrackingSample`

`HeadTrackingSample` intentionally stays orientation-focused and no longer exposes a raw sensor payload field
Use `ReportAvailable` for raw IMU/magnetometer vectors and report metadata

### Timing contract

Tracking uses `hmd_time_nanos_device` from the decoded report

- timestamps must be strictly monotonic for IMU tracking updates
- non-monotonic/invalid deltas fail fast and surface through `StreamError`
- no host-time fallback integration path

## Diagnostics fields

`HeadTrackingStreamDiagnostics` includes:

- `parsedMessageCount`
- `rejectedMessageCount`
- `invalidReportLengthCount`
- `decodeErrorCount`
- `unknownReportTypeCount`
- `imuReportCount`
- `magnetometerReportCount`
- receive-rate fields (`observedSampleRateHz`, `receiveDelta*Ms`)

## Fixture capture and refresh workflow

Phase 1 parser tests use this corpus:

- `oneproxr/src/test/resources/packets/onepro_stream_capture_v1.bin`
- `oneproxr/src/test/resources/packets/onepro_stream_capture_v1.meta.json`

To refresh from a real device run:

1. Open demo app and tap `Capture Fixture` (captures up to 45s, max 5 MiB)
2. Pull files from device:

```bash
adb shell ls /sdcard/Android/data/io.onepro.xrprobe/files/onepro-fixtures
adb pull /sdcard/Android/data/io.onepro.xrprobe/files/onepro-fixtures/<capture>.bin /tmp/onepro_stream_capture_v1.bin
adb pull /sdcard/Android/data/io.onepro.xrprobe/files/onepro-fixtures/<capture>.meta.json /tmp/onepro_stream_capture_v1.meta.json
```

3. Replace corpus files in repo
4. Recompute checksum and update metadata fields if needed:

```bash
shasum -a 256 oneproxr/src/test/resources/packets/onepro_stream_capture_v1.bin
```

5. Validate:

```bash
./gradlew :oneproxr:testDebugUnitTest --tests "io.onepro.xr.OneProFixtureCorpusTest"
```

Metadata contract fields:

- `schema_version`
- `captured_at_utc`
- `duration_seconds`
- `total_bytes`
- `sha256`
- `parser_contract_version`

## Troubleshooting

### `No matching Android Network candidate for host 169.254.2.1`

- Call `describeRouting()`
- Confirm device link-local route is present
- Keep default host unless your setup intentionally differs

### Stream connects but tracking does not start

- Verify `Follow` mode
- Keep glasses still until calibration completes
- Watch diagnostics counters for parser rejects

### Stream errors with timestamp contract messages

- This means IMU device timestamps were non-monotonic or invalid
- Restart stream session and verify clean report traffic

## Validation checklist

- Manifest includes `INTERNET` + `ACCESS_NETWORK_STATE`
- Stream reaches `Connected`
- Calibration reaches complete
- Orientation updates continuously via `TrackingSampleAvailable`
- `ReportAvailable` shows IMU and magnetometer traffic
- `Zero View` recenters tracking
- `Recalibrate` returns stream to calibration and then tracking

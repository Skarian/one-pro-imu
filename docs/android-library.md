# Android Library Guide (`oneproxr`)

`oneproxr` gives Android apps direct access to XREAL One Pro tracking data without the Unity-based XREAL SDK

## What you get

- simple runtime API for app lifecycle and tracking
- typed sensor reports (`imu` + `magnetometer`)
- physical orientation output (`poseData`) with zero-view recentering
- config-driven tracker bias correction (factory + runtime residual)
- direct control API for scene/input/brightness/dimmer
- optional diagnostics and raw report stream for advanced usage

## Requirements

- Android `minSdk 26`
- Android `compileSdk 35`
- Kotlin/Java target 17
- XREAL One Pro connected to phone
- glasses set to `Follow` mode (stabilization off)

## Add the library

### Source module

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

### Maven artifact (optional)

```kotlin
implementation("io.onepro:oneproxr:<version>")
```

## Required permissions

The library manifest is intentionally empty
Your app must declare:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## Quickstart (minimal)

```kotlin
import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.onepro.xr.OneProXrClient
import io.onepro.xr.XrPoseSnapshot
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TrackingActivity : AppCompatActivity() {
    private lateinit var client: OneProXrClient
    private var poseJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = OneProXrClient(applicationContext)
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    fun startTracking() {
        if (client.isXrConnected()) return
        lifecycleScope.launch {
            try {
                client.start()
                poseJob?.cancel()
                poseJob = launch {
                    client.poseData.collect { pose ->
                        if (pose == null) return@collect
                        renderPose(pose)
                    }
                }
            } catch (t: Throwable) {
                Log.e("xr", "start failed: ${t.message}")
            }
        }
    }

    fun zeroView() {
        lifecycleScope.launch { client.zeroView() }
    }

    fun recalibrate() {
        lifecycleScope.launch { client.recalibrate() }
    }

    fun stopTracking() {
        poseJob?.cancel()
        poseJob = null
        lifecycleScope.launch { client.stop() }
    }

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }

    private fun renderPose(pose: XrPoseSnapshot) {
        val r = pose.relativeOrientation
        Log.d("xr", "pitch=${r.pitch} yaw=${r.yaw} roll=${r.roll}")
    }
}
```

## If you need raw IMU/MAG data

```kotlin
lifecycleScope.launch {
    client.sensorData.collect { snapshot ->
        if (snapshot == null) return@collect
        snapshot.imu?.let { imu ->
            Log.d("xr", "gyro=[${imu.gx}, ${imu.gy}, ${imu.gz}] accel=[${imu.ax}, ${imu.ay}, ${imu.az}]")
        }
        snapshot.magnetometer?.let { mag ->
            Log.d("xr", "mag=[${mag.mx}, ${mag.my}, ${mag.mz}]")
        }
    }
}
```

## If you need diagnostics or raw reports

```kotlin
lifecycleScope.launch {
    client.advanced.diagnostics.collect { d ->
        if (d == null) return@collect
        Log.d("xr", "imu=${d.imuReportCount} mag=${d.magnetometerReportCount} rejected=${d.rejectedMessageCount}")
    }
}

lifecycleScope.launch {
    client.advanced.reports.collect { report ->
        Log.d("xr", "reportType=${report.reportType} timeNs=${report.hmdTimeNanosDevice}")
    }
}
```

## If you need control commands

```kotlin
lifecycleScope.launch {
    client.setSceneMode(XrSceneMode.ButtonsEnabled)
    client.setDisplayInputMode(XrDisplayInputMode.Regular)
    client.setBrightness(5)
    client.setDimmer(XrDimmerLevel.Middle)
    Log.d("xr", "id=${client.getId()} sw=${client.getSoftwareVersion()} dsp=${client.getDspVersion()}")
}

lifecycleScope.launch {
    client.advanced.controlEvents.collect { event ->
        Log.d("xr", "controlEvent=$event")
    }
}
```

## If you need typed device config

`getConfigRaw()` returns the raw JSON payload from the control channel.
`getConfig()` parses and validates the full typed model (`XrDeviceConfig`).

```kotlin
import io.onepro.xr.XrControlProtocolException
import io.onepro.xr.XrDeviceConfigErrorCode
import io.onepro.xr.XrDeviceConfigException

lifecycleScope.launch {
    val status = try {
        val config = client.getConfig()
        Log.d("xr", "config fsn=${config.fsn} gyroBiasSamples=${config.imu.gyroBiasTemperatureData.size}")
        "success"
    } catch (e: XrDeviceConfigException) {
        when (e.code) {
            XrDeviceConfigErrorCode.PARSE_ERROR -> "parse_error"
            XrDeviceConfigErrorCode.SCHEMA_VALIDATION_ERROR -> "schema_validation_error"
        }
    } catch (e: XrControlProtocolException) {
        "transport_error"
    }
    Log.d("xr", "config status=$status")
}
```

Typed config coverage includes:

- display calibration + transforms
- display distortion grids
- RGB and SLAM camera intrinsics
- IMU intrinsics/noise/bias fields
- gyro temperature bias samples (`gyro_bias_temp_data`) with interpolation helper
- magnetometer transform (`gyro_p_mag` / `gyro_q_mag`)

## Bias activation status

`start()` now loads and validates config before tracker activation, then applies:

- `gyro_corrected = gyro_raw - factory_temp_interpolated_bias - runtime_residual_bias`
- `accel_corrected = accel_raw - factory_accel_bias`
- In the `poseData` path, the compatibility accel axis remap and factory accel bias remap are applied consistently, so correction is equivalent to raw-frame subtraction before remap
- Gyroscope and gyro-bias terms are treated as rad/s; pose integration converts to degrees and emits orientation in degrees
- `relativeOrientation` is the physical recentered delta from `zeroView` (no library-applied gain)

You can observe bias activation/failure in real time:

```kotlin
lifecycleScope.launch {
    client.biasState.collect { state ->
        Log.d("xr", "biasState=$state")
    }
}
```

## API surface

Simple API (`OneProXrClient`):

- `start()` / `stop()`
- `isXrConnected()`
- `getConnectionInfo()`
- `sessionState`
- `biasState`
- `sensorData`
- `poseData`
- `zeroView()`
- `recalibrate()`
- `setSceneMode(mode)`
- `setDisplayInputMode(mode)`
- `setBrightness(level)`
- `setDimmer(level)`
- `getId()`
- `getSoftwareVersion()`
- `getDspVersion()`
- `getConfigRaw()`
- `getConfig()`

Advanced API (`client.advanced`):

- `diagnostics`
- `reports`
- `controlEvents`

## Important behavior

- `start()` succeeds only after the first valid report is parsed
- `start()` now fails fast if tracker bias prerequisites cannot be loaded from config (`biasState=Error`)
- tracking time integration uses device timestamp (`hmd_time_nanos_device`) with fail-fast monotonic checks
- `sensorData` is raw protocol field order
- `poseData` uses compatibility accel mapping with factory accel bias remapped consistently so correction remains raw-frame equivalent
- `poseData.absoluteOrientation` and `poseData.relativeOrientation` are physical angles in degrees; relative orientation is recentered but not scaled
- `poseData` gyroscope integration expects device `gx/gy/gz` and gyro bias terms in rad/s
- `getConfig()` validates schema and throws typed config errors (`parse_error` or `schema_validation_error`) for invalid payloads

## Control protocol contract

- control messages use a persistent TCP session on `52999`
- wire header is `magic(2 bytes, big-endian) + length(4 bytes, big-endian)`
- transaction payload is `transaction_id(4 bytes, high-bit set on outbound) + protobuf body`
- responses are correlated by `(transaction_id without high bit, magic)`
- key events are decoded as typed `XrControlEvent.KeyStateChange`
- unmatched inbound frames are surfaced as unknown control messages for diagnostics

## Troubleshooting

`No matching Android Network candidate for host 169.254.2.1`

- call `describeRouting()`
- verify link-local route is present

Startup timeout

- confirm glasses mode is `Follow`
- check `advanced.diagnostics`

`sessionState` becomes `Error`

- inspect `code`, `message`, `causeType`
- call `stop()` then `start()`

## Reference demo app

See `app/src/main/java/io/onepro/xrprobe/SensorDemoActivity.kt` for a complete app integration

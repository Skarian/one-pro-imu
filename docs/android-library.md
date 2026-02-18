# Android Library Guide (`oneproxr`)

`oneproxr` gives Android apps direct access to XREAL One Pro tracking data without the Unity-based XREAL SDK

## What you get

- simple runtime API for app lifecycle and tracking
- typed sensor reports (`imu` + `magnetometer`)
- orientation output ready for rendering (`poseData`)
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

## API surface

Simple API (`OneProXrClient`):

- `start()` / `stop()`
- `isXrConnected()`
- `getConnectionInfo()`
- `sessionState`
- `sensorData`
- `poseData`
- `zeroView()`
- `recalibrate()`

Advanced API (`client.advanced`):

- `diagnostics`
- `reports`

## Important behavior

- `start()` succeeds only after the first valid report is parsed
- tracking time integration uses device timestamp (`hmd_time_nanos_device`) with fail-fast monotonic checks
- `sensorData` is raw protocol field order
- `poseData` uses compatibility accel mapping to preserve baseline demo behavior

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

See `app/src/main/java/io/onepro/xrprobe/MainActivity.kt` for a complete app integration

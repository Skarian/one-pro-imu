# Android Library Guide (`oneproxr`)

This guide is for Android developers who want live XREAL One Pro head tracking without the Unity-based XREAL SDK

## What this library provides

- TCP connectivity helpers for the One Pro control and IMU endpoints
- Real-time IMU parsing for One Pro stream frames
- A coroutine `Flow` API that emits calibration status, tracking samples, diagnostics, and errors
- Control hooks for `Zero View` and full recalibration while the stream is active

## Current scope

- Target device: XREAL One Pro
- Transport: direct TCP to One Pro endpoints (default host `169.254.2.1`)
- Output: orientation in degrees (`pitch`, `yaw`, `roll`) as absolute and relative views
- This library does not handle rendering, scene/camera management, or UI controls for you

## Prerequisites

- Android `minSdk 26` or newer
- Android `compileSdk 35` or newer
- Java 17 / Kotlin JVM target 17
- Kotlin coroutines in your app module
- XREAL One Pro connected and reachable from the phone
- Glasses mode set to `Follow` (stabilization off) for expected behavior

## Add the library to your app

For most users, source-module integration is the fastest path

### Source module (recommended)

Pull `oneproxr/` into your project and wire it as a local module

`settings.gradle.kts`:

```kotlin
include(":app", ":oneproxr")
```

`app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":oneproxr"))
}
```

### Optional: Maven artifact

If you want package-based integration, this repo also publishes
`io.onepro:oneproxr` to GitHub Packages

1. Add credentials to `~/.gradle/gradle.properties`

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_GITHUB_READ_PACKAGES_TOKEN
```

2. Add the package repository in your project `settings.gradle.kts`

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/nskaria/one-pro-imu")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                password = providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}
```

3. Add the dependency in `app/build.gradle.kts`

```kotlin
dependencies {
    implementation("io.onepro:oneproxr:<version>")
}
```

Use a GitHub token with `read:packages` scope for consumption from local
machines or other repositories

## Required permissions

The library manifest is intentionally empty, so the host app must declare required permissions

`app/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
</manifest>
```

`ACCESS_NETWORK_STATE` is required because `OneProXrClient` selects the right `Network.socketFactory` for link-local routing

## Quickstart integration

This is a minimal Activity-style integration that starts/stops streaming and handles the core events

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
    private val endpoint = OneProXrEndpoint(
        host = "169.254.2.1",
        controlPort = 52999,
        streamPort = 52998
    )
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
                        Log.i("imu", "connected ${event.interfaceName} in ${event.connectMs}ms")
                    }
                    is HeadTrackingStreamEvent.CalibrationProgress -> {
                        calibrationComplete = event.isComplete
                        Log.i("imu", "calibration ${event.calibrationSampleCount}/${event.calibrationTarget}")
                    }
                    is HeadTrackingStreamEvent.TrackingSampleAvailable -> {
                        val orientation = event.sample.relativeOrientation
                        Log.i("imu", "relative pitch=${orientation.pitch} yaw=${orientation.yaw} roll=${orientation.roll}")
                    }
                    is HeadTrackingStreamEvent.DiagnosticsAvailable -> {
                        Log.d("imu", "sampleRateHz=${event.diagnostics.observedSampleRateHz}")
                    }
                    is HeadTrackingStreamEvent.StreamStopped -> {
                        Log.i("imu", "stopped: ${event.reason}")
                        streamJob = null
                    }
                    is HeadTrackingStreamEvent.StreamError -> {
                        Log.e("imu", "error: ${event.error}")
                        streamJob = null
                    }
                }
            }
        }
    }

    fun stopTracking() {
        streamJob?.cancel()
        streamJob = null
        calibrationComplete = false
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

## Recommended first-run flow in your app

1. Connect XREAL One Pro and switch glasses mode to `Follow`
2. Start tracking while glasses are resting still on a table
3. Wait for `CalibrationProgress(isComplete = true)`
4. Put glasses on and call `requestZeroView()` once the user faces forward
5. Expose a `Recalibrate` action that calls `requestRecalibration()`

If users call `Zero View` before calibration is complete, your app should ignore or defer it

## API guide

### `OneProXrClient`

- `describeRouting()`: inspects active interfaces and Android network candidates
- `connectControlChannel()`: quick control-socket connectivity check
- `readSensorFrames()`: short raw frame read helper for diagnostics
- `streamHeadTracking(config)`: primary streaming API for production use

`streamHeadTracking` is a cold flow. Every collection starts a new socket session

### `HeadTrackingStreamConfig`

Most apps can keep defaults. If you tune values, these are the high-impact fields:

- `calibrationSampleTarget`: number of still samples required before tracking starts
- `diagnosticsIntervalSamples`: diagnostics emission cadence
- `complementaryFilterAlpha`: gyro vs accelerometer blend
- `pitchScale`, `yawScale`, `rollScale`: relative output sensitivity
- `controlChannel`: command channel for `requestZeroView()` and `requestRecalibration()`

### `HeadTrackingStreamEvent`

- `Connected`: socket established and stream is ready
- `CalibrationProgress`: startup/recalibration state
- `TrackingSampleAvailable`: per-sample orientation and IMU payload
- `DiagnosticsAvailable`: parser/rate counters for health monitoring
- `StreamStopped`: clean stop with reason
- `StreamError`: failure message suitable for logs and user-facing diagnostics

### `HeadTrackingSample`

- `absoluteOrientation`: filter output in world frame
- `relativeOrientation`: user-zeroed view after `Zero View` offsets
- `deltaTimeSeconds`: effective integration timestep for this sample
- `isCalibrated`: true only after gyro bias calibration completes

All orientation values are degrees, normalized to roughly `[-180, 180]`

## Troubleshooting

### `No matching Android Network candidate for host 169.254.2.1`

- Call `describeRouting()` and inspect `networkCandidates`
- Confirm glasses are connected and Android has an active link-local route
- Keep endpoint host at `169.254.2.1` unless your setup is intentionally different

### Stream connects but no useful tracking samples

- Make sure glasses are in `Follow` mode
- Keep glasses completely still during calibration
- Do not treat the stream as ready until calibration completion is emitted

### Tracking drifts or feels offset

- Expose a user action for `requestRecalibration()`
- Recalibrate with glasses resting still on a surface
- After calibration, call `requestZeroView()` when user faces neutral forward

### Stream ends with timeout/eof on unstable links

- Retry by restarting the flow collection
- Log `DiagnosticsAvailable` sample-rate fields to spot transport instability

## Validation checklist

- App manifest includes both `INTERNET` and `ACCESS_NETWORK_STATE`
- Stream reaches `Connected`
- Calibration reaches complete while glasses are still
- `TrackingSampleAvailable` events update orientation continuously
- `Zero View` recenters orientation
- `Recalibrate` resets calibration and returns to tracking after still-surface step

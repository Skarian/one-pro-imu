# one-pro-imu

I built this repo to make accessing XREAL One Pro tracking data on Android without
depending on the heavy Unity-based XREAL SDK

The repo includes:

1. An Android library other Android developers can use to read tracking sensor data
   and build their own apps
2. A demo Android app that showcases the library's functionality

## Start here

- Android library integration guide: [`docs/android-library.md`](docs/android-library.md)
- Demo app integration example: `app/src/main/java/io/onepro/xrprobe/MainActivity.kt`
- Latest demo APK download: [`Releases`](../../releases)

## Repo Structure

- `oneproxr/`: Android library module (`io.onepro.xr`)
- `app/`: Android demo app (`io.onepro.xrprobe`)
- `references/`: inspiration and compatibility assets based on
  [`One-Pro-IMU-Retriever-Demo`](https://github.com/SamiMitwalli/One-Pro-IMU-Retriever-Demo)
  and [`xreal_one_driver`](https://github.com/rohitsangwan01/xreal_one_driver)
  (submodules, patch, scripts)

## Library API at a glance

Entry point:

- `io.onepro.xr.OneProXrClient`

Helpful methods:

- `describeRouting()`
- `connectControlChannel()`
- `readSensorFrames()`
- `streamHeadTracking(config)`

For event semantics, lifecycle guidance, and troubleshooting, use
[`docs/android-library.md`](docs/android-library.md)

## Android Demo App Build / Install Instructions

From repo root:

```bash
./gradlew :oneproxr:testDebugUnitTest :oneproxr:lintDebug :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.onepro.xrprobe/.MainActivity
```

## Demo App Quick Start

1. Connect your XREAL One Pro and switch glasses mode to `Follow` (stabilization off)
2. Open the demo app and keep the default host/ports unless your setup is different
3. Place the glasses on a stable surface and tap `Start`
4. Leave them still until the app shows `Calibration complete`
5. Put the glasses on, face your neutral forward direction, then tap `Zero View`
6. If tracking drifts later, tap `Recalibrate` and repeat the still-on-surface step
7. Read the telemetry panel above the 3D view:
   it shows parsed report counts (`imu`, `mag`, `rejected`, `dropped`), latest report metadata, and current orientation output

## Notes on Android implementation

- Uses Android `Network.socketFactory` for reliable link-local routing
- Uses One Pro report framing (`magic + big-endian length`) with dual-header compatibility (`2836` and `2736`)
- Parses full report payload parity fields (`device_id`, `hmd_time_nanos_device`, `report_type`, IMU vectors, magnetometer vectors, temperature, imu_id, frame_id)
- Uses device timestamp (`hmd_time_nanos_device`) for tracking integration with fail-fast monotonicity checks
- Uses complementary-filter tracking with startup gyro calibration, zero-view, and recalibration support

## Acknowledgement and reference demo patch

This project was heavily inspired by
[One-Pro-IMU-Retriever-Demo](https://github.com/SamiMitwalli/One-Pro-IMU-Retriever-Demo)
by Daniel Sami Mitwalli. Huge thanks to him for publishing that work.

Additional thanks to
[xreal_one_driver](https://github.com/rohitsangwan01/xreal_one_driver)
for publishing a clean open-source One Pro driver implementation used for
cross-checking parser behavior.

On my hardware/firmware combination, I ran into parser compatibility issues
with the upstream demo as-is. For reproducibility, this repo includes the
upstream project as a submodule plus a small patch.

This patched desktop path is not the main deliverable of this repo, but it is
included so anyone can reproduce the same validation path.

### Run patched reference demo on desktop

From repo root:

```bash
git submodule update --init --recursive
./references/scripts/apply-reference-patches.sh
./references/scripts/check-reference-patches.sh

cd references/One-Pro-IMU-Retriever-Demo
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python launcher.py
```

`launcher.py` menu:

- `1` console mode
- `2` 3D mode

If you want to keep the submodule working tree untouched, run it from a temp
clone instead:

```bash
tmp_dir="/tmp/one-pro-demo-$(date -u +%Y%m%dT%H%M%SZ)"
git clone references/One-Pro-IMU-Retriever-Demo "$tmp_dir"
git -C "$tmp_dir" checkout 16f45c73610b04b4da238895b46733794a9f5944
git -C "$tmp_dir" apply "$PWD/references/patches/one-pro-imu-retriever-demo/0001-imu-reader-parser-compatibility.patch"

python3 -m venv "$tmp_dir/.venv"
source "$tmp_dir/.venv/bin/activate"
pip install -r "$tmp_dir/requirements.txt"

cd "$tmp_dir"
python launcher.py
```

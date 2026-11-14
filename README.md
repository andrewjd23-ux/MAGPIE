# MAGPIE

**Magnetic Anomaly & Geomagnetic Pocket Instrument Explorer** is a native Kotlin / Jetpack Compose Android pocket magnetometer. Package: `uk.co.magpie.instrument`. Android 10 (API 29) or later; magnetic field hardware is optional.

## Build

Install JDK 17 and Android SDK platform 35 (with build tools 35.0.0), set `ANDROID_HOME` or put `sdk.dir=/path/to/sdk` in untracked `local.properties`, then:

```sh
./gradlew testDebugUnitTest assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

Pinned stable toolchain: AGP 8.7.3, Gradle 8.9, Kotlin and Compose compiler plugin 2.0.21, Compose BOM 2024.12.01, Activity 1.9.3, Lifecycle 2.8.7. Java source/target is 17. The Gradle wrapper is included and its distribution checksum is pinned. A first build requires access to Google Maven, Maven Central and Gradle distributions.

## MAGPIE Vision — first increment (v1.1.0)

The **Instrument** tab retains all existing magnetometer features and the magpie
launcher icon. The **MAGPIE Vision** tab adds an optional rear-camera preview and
real offline object detection using the bundled Apache-2.0 EfficientDet-Lite0
COCO model. Grant camera permission in Vision; denial or missing camera hardware
does not prevent instrument use. No internet permission is declared.

- CameraX 1.4.1 uses `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST`, RGBA frames and a
  single worker executor. Model initialization, pixel conversion and CPU inference
  run off the main thread. Every ImageProxy closes in `finally`. The camera unbinds
  on Activity pause and when leaving Vision; workers and the interpreter close on
  disposal. No background camera or magnetic monitoring.
- Preview and analysis share a CameraX `ViewPort`. Analysis crops to that viewport,
  rotates by `ImageInfo.rotationDegrees`, and letterboxes to 320×320. The decoder
  removes letterbox padding; CameraX `ImageProxyTransformFactory` and
  `CoordinateTransform` map boxes into the `FILL_CENTER` preview. Layout/display
  changes rebind the viewport, clearing old boxes. No front-camera mirroring is
  assumed. Boxes are clipped to the preview and hidden when analysis is stale.
- Labels show **uncalibrated model scores**, not identification probabilities.
  Threshold: 0.35; at most ten displayed regions. COCO labels include ordinary
  object categories and cannot establish material or metal identity.
- Tap a box to select its region. Selection follows only overlapping same-label
  regions and clears when lost. This is not identity tracking and can be ambiguous
  with similar overlapping objects. It provides no magnetic source attribution.
- The magnetic overlay shows fresh raw and smoothed field magnitude and signed
  smoothed magnitude deviation from the instrument's shared baseline. Baseline and
  reset controls operate on the same state as the Instrument tab. Magnetic values
  remain live when the camera image is frozen. Missing/stale sensor data shows
  unavailable, not zero.
- **Freeze** displays the last analyzed image with its matching detections;
  **Resume** returns to live preview. **Torch** is enabled only when supported.
  **Capture** saves the analyzed frame, matching overlays and a snapshot of magnetic
  readings to `Pictures/MAGPIE` using MediaStore, without storage permission.
  Captures use the analyzed frame rather than a newer, mismatched preview frame.
  Live boxes still have inference latency, so fast movement can visibly lag.
- `MaterialClassifier` is a separate, replaceable adapter contract. Its current
  implementation is `NoMaterialModel`; the UI explicitly says **Material unknown**
  and **Inconclusive**. No material-classification model is installed or claimed.

Model source revision, original license, tensor contract and SHA-256 hashes are
recorded in [third_party/models/README.md](third_party/models/README.md). The model
and labels are bundled in the APK; Gradle checks their hashes before building.
LiteRT 1.1.2 runs inference on Android without Play Services. All added dependencies
are Apache-2.0 open source.

### Next increment: guided scanning and evidence

**Guided three-pass acquisition is not yet enabled.** The first increment must not
attribute live magnetic changes to a selected visual object. A tested calculation
foundation (`ScanMath.kt`) records baseline mean, baseline population standard
deviation, each pass's peak absolute deviation, overall peak, peak spread and
relative peak spread. These are descriptive measurements, not probabilities.
It rejects incomplete passes and nonfinite samples but does not validate acquisition
conditions, sample timing, calibration or object association; those belong in the
next guided acquisition state machine.

That next increment will guide the user to a stable baseline away from the object,
keep phone orientation and distance controlled, acquire three timed approach/return
passes, record fresh sensor samples and accuracy, and abort interrupted or invalid
scans. It will report noise, repeatability and limitations with an Unknown/Inconclusive
outcome. Torch changes, the phone's own components, motion and nearby magnetic
sources can affect readings. Even a repeatable response cannot identify a metal.

A material adapter must only be enabled after a suitably trained, licensed model
has been integrated, its tensor/label contract checked, real inference tested, and
its operating domain documented. Report scores as uncalibrated until calibration
has been validated. Never derive material predictions from COCO labels.

## Operation

MAGPIE discovers `TYPE_MAGNETIC_FIELD` at runtime. Missing hardware, failed listener registration and absent/stale readings show explicit states. Samples older than two seconds are unavailable. No sensor values are fabricated. Raw device X/Y/Z readings are in microteslas; magnitude is `sqrt(x*x + y*y + z*z)`. Axes follow the device's natural sensor coordinate system and are not remapped when the UI rotates.

An exponential moving average (alpha 0.20) filters each axis, seeded with the first sample. Raw and smoothed axes and magnitudes are displayed separately. The large display and 0–100 µT gauge use smoothed magnitude. The gauge saturates above 100 µT, while numerical values and the auto-ranging chart retain the full reading. Capture baseline stores the current smoothed vector. Deviation is the **signed difference of smoothed and baseline magnitudes**, not vector displacement or a source classification. Rotating a sensor can affect measurements and filtering. Accuracy is Android's sensor-reported status, not an independent calibration guarantee.

The Canvas history retains the most recent 60 seconds, sampled for display at up to 10 Hz, with raw amber and smoothed green traces. Gaps over two seconds are not connected. Reset clears readings, baseline, filter and history. A ViewModel retains state across Activity recreation and rotation, but not process death. The filter re-seeds on resume; baseline and recent history remain. Listener registration is confined to Activity onResume/onPause. There is no service or background monitoring.

The scrollable, inset-aware UI supports portrait and landscape. No internet permission, advertising, analytics, accounts, Firebase or Google Play Services. Runtime dependencies are open-source AndroidX/Kotlin/LiteRT libraries; unit tests use JUnit 4 (EPL-1.0). AndroidX and Kotlin use Apache-2.0.

## Verification

Unit tests cover three-axis magnitude, signed baseline deviation, EMA initialization, filtering, reset and invalid input. Vision tests cover letterbox inversion for portrait/landscape, every orthogonal
rotation, invalid/padding-only boxes, region selection, three-pass calculation
foundations and conservative evidence explanations. Existing magnetometer tests
remain in place. `tools/verify_model.py` verifies model/label hashes and runs real
inference against the upstream licensed cat fixture. To run it locally:

```sh
python3 -m venv .venv
.venv/bin/pip install -r tools/model-test-requirements.txt
.venv/bin/python tools/verify_model.py
```

GitHub Actions runs that host smoke test and the same Gradle build/test command
on Ubuntu with JDK 17, checks the APK has no internet permission, and uploads `MAGPIE-debug-apk` and test reports. Run status is available on the repository's Actions tab after this project is pushed to GitHub.

The host smoke test uses LiteRT 2.3.0 to check the real model; the Android adapter
uses LiteRT 1.1.2 and checks its tensor contract at startup. Camera/magnetometer
hardware was not available in the build workspace. JVM and host tests do not
establish physical overlay alignment, camera compatibility or detector accuracy.

Vision device acceptance checks: deny/grant/revoke camera permission; try absent
rear camera/flash/magnetometer; compare boxes against stationary objects in portrait
and landscape, including preview edges; rotate while live and frozen; select similar
overlapping objects; background/resume and switch tabs repeatedly; check freeze,
torch failures and screenshot overlays/metadata in Gallery; confirm stale boxes and
readings disappear. Test actual Android native model inference on target devices,
including device ABI/16 KB page-size compatibility.

Instrument device acceptance checks: launch on a device without a magnetometer; observe live readings and accuracy on hardware; capture/reset baseline; rotate repeatedly; background and resume; verify no monitoring while paused; confirm stale readings become unavailable and the chart expires old data. These require a device/emulator and are separate from JVM tests.

## Roadmap

**Phase 2 — anomaly alerts:** add configurable magnitude-deviation thresholds, dwell time, hysteresis and cooldown to the pure measurement layer, with tests for threshold crossings and sensor loss. Gate alerts on valid fresh readings and accuracy; explain that alerts indicate field changes only. Start with foreground UI alerts. Any future notifications or background sensing require an explicit opt-in design and lifecycle/battery review.

**Phase 3 — Glance widgets:** add an AndroidX Glance module and widget provider, with timestamped snapshots from explicitly persisted foreground measurements. Show stale/unavailable states and open the app for live capture. Widgets must not imply continuous or live monitoring; no background sensor listener. Widgets are not implemented in this phase.

## License

Apache License 2.0; see [LICENSE](LICENSE).

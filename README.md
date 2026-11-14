# MAGPIE

**Magnetic Anomaly & Geomagnetic Pocket Instrument Explorer** is a native Kotlin / Jetpack Compose Android pocket magnetometer. Package: `uk.co.magpie.instrument`. Android 10 (API 29) or later; magnetic field hardware is optional.

## Build

Install JDK 17 and Android SDK platform 35 (with build tools 35.0.0), set `ANDROID_HOME` or put `sdk.dir=/path/to/sdk` in untracked `local.properties`, then:

```sh
./gradlew testDebugUnitTest assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

Pinned stable toolchain: AGP 8.7.3, Gradle 8.9, Kotlin and Compose compiler plugin 2.0.21, Compose BOM 2024.12.01, Activity 1.9.3, Lifecycle 2.8.7. Java source/target is 17. The Gradle wrapper is included and its distribution checksum is pinned. A first build requires access to Google Maven, Maven Central and Gradle distributions.

## MAGPIE Vision — Target Acquisition Mode (v1.3.0)

The **Instrument** tab retains the existing independent magnetometer, baseline,
filter, gauge and history. **MAGPIE Vision** retains the rear CameraX preview,
bundled Apache-2.0 EfficientDet-Lite0 COCO detector, freeze, torch and screenshot
capture. Grant camera permission only when using Vision; denial or missing camera
hardware does not prevent instrument use. No internet permission is declared.

### Acquire a target

Live detection continues to draw multiple boxes. **Tap a box** to acquire that
specific detection from the corresponding analyzed frame. Overlapping boxes use
the smallest containing box, then the highest object score. A tap outside boxes
does not release the target.

The acquired frame appears in a pinned **ACQUIRED • SNAPSHOT** inset and in the
scrollable target panel, with the selected box highlighted **amber** and all other
boxes dimmed. Live boxes are also dimmed while a target is acquired. The amber box
belongs to the saved frame, not to a moving object in the live feed: **there is no
continuous object tracking or matching against subsequent detections**.

The selected crop is created by inverting that frame's CameraX image-to-preview
affine transform, mapping the four box corners back to upright image pixels,
clipping to the image and rounding outward. It is not a screen capture or a crop
from an unrelated later frame. CameraX preview/analysis still share a viewport;
rotation, model letterboxing, `FILL_CENTER` scaling and preview cropping are
accounted for. Invalid/singular transforms fail explicitly.

Acquired image, crop, material result and completed scan remain fixed across new
frames, camera rebinds, tab changes and rotation until **Release target** or a tap
on another detection. A ViewModel retains these snapshots; process death clears
them. Replacing/releasing a target discards its scan. Superseded asynchronous jobs
cannot overwrite the new target. No acquired images are saved automatically.

### Material model and decision states

**This release bundles a real 23-class MINC material model**, fine-tuned from
SigLIP2, using the upstream Apache-2.0 INT8 ONNX export. Acquiring a target runs
real offline inference on that crop and displays uncalibrated visible-surface
scores. Object labels such as COCO's “knife” or “cup” are never supplied to the
material classifier. It has one broad `metal` class, not alloy/element identities.
This is experimental surface recognition, not proof of bulk composition.

The model adds approximately 89 MB of weights to the APK. It loads lazily on the
material worker after acquisition. The APK asset is memory-mapped and checked for
length, SHA-256, label order and tensor contract; there is no runtime download or
second model-file copy. ONNX Runtime 1.23.2 runs on the built-in CPU provider with
telemetry explicitly disabled. EfficientDet continues to use LiteRT unchanged.
Model/session/runtime failures still show **Material model unavailable / UNKNOWN**.

Pinned weight sources, declared redistribution licenses and limitations are in
[third_party/materials23/README.md](third_party/materials23/README.md). Full model
and native-runtime notices ship inside the APK and are readable offline via
**Material model & licenses**. Original MINC training photos are not redistributed.

`MaterialClassifier` is a separate replaceable adapter. A future adapter must
verify redistribution rights, material training/label semantics, initialization,
tensor preprocessing and real on-device inference before exposing
`MaterialModelStatus.Ready`. Its `VerifiedMaterialModel` records provenance,
license, validation report and trained material-label families. These records are
an adapter contract, not an automatic license or suitability verifier.

Only acquisition schedules material analysis, once on a dedicated worker and
**only on the selected crop's pixels**. Live object detections never schedule
material inference. With an unavailable model, even pixel extraction for the
classifier is skipped. Queued obsolete acquisitions are skipped; already running
native inference can finish but its stale result is discarded. Model/runtime or
output-contract failures become unavailable/UNKNOWN and do not remove the image.

For a genuinely installed adapter, the UI displays candidate material labels and
**uncalibrated model scores**. Candidate labels must belong to that material
model's documented trained vocabulary. Default decision gates are a top score
of at least 0.70 and a top-versus-runner-up margin of at least 0.15. These are
conservative configurable heuristics, **not calibrated probabilities**; a future
adapter must validate its own operating policy.

- **METAL_SUSPECTED:** a qualifying material-model label belongs to its trained
  metal family. Offer the guided magnetic scan; this does not establish composition.
- **NONMETAL_SUSPECTED:** a qualifying trained nonmetal-family label. Report the
  visual result only; skip magnetic scanning/fusion for that target.
- **UNKNOWN:** unavailable, failed, pending, ambiguous or unsupported evidence.
  Report inconclusive and offer an optional manual scan once acquisition completes.

Production material candidates now come from the bundled trained weights. Decision
gates remain conservative heuristics: `painted`, `mirror`, `other` and `sky` are
ambiguous and remain UNKNOWN; other classes describe visible surfaces only. A
bounding-box crop can contain background, so even a strong score does not establish
the selected object's hidden or mixed composition. Native/host inference tests
exercise real weights; decision-policy unit fixtures remain synthetic test data.

### Guided magnetic scan

For METAL_SUSPECTED or UNKNOWN, read and acknowledge the controlled-scan guidance.
Fresh medium/high-accuracy magnetic readings are required. Start away from the
selected physical object, maintain phone orientation and use a safe repeatable
distance. Do not change the torch or nearby objects during acquisition.

Each of **three passes** records raw magnetic magnitude, actual SensorEvent measurement timestamps and
sensor-reported accuracy. Delayed sensor events are not stamped as fresh on receipt. The protocol is **baseline 3 s → approach 3 s → hold 3 s → retreat
3 s**. A banner gives the current action and countdown. Between passes, return to
the original location and explicitly begin the next baseline. This is a manually
controlled protocol; the app cannot verify distance, phone orientation, movement
or that the user is scanning the selected physical object.

The scan aborts on invalid/low-accuracy samples, a reading gap over 0.75 s, fewer
than ten samples in a phase, out-of-order timestamps, Activity pause, leaving Vision,
reset or cancellation. It must restart after interruption, including interruption
between passes. Target selection remains intact. Freeze, torch changes, screenshot
capture and instrument baseline capture are disabled during active acquisition
where they could disturb the protocol. The Instrument screen remains independent;
its shared display baseline is not overwritten by scan baselines.

Completed scans retain all 3 × 4 phase records in memory. The UI and exported
screenshot report each pass's baseline mean, baseline population standard deviation
(noise), peak absolute deviation, signed mean hold deviation, retreat residual,
peak spread and relative peak spread. Relative spread is a descriptive ratio,
not a probability, and is undefined for zero response.

Interpretation uses documented **heuristics**, not a calibrated metal-identification
model: hold response must exceed `max(1 µT, 3 × baseline noise)` in every pass,
hold signs must agree, retreat residuals must return within that gate, relative
peak spread must be at most 0.5, and baseline drift must stay within the largest
noise gate. Passing these checks reports only a **provisional hypothesis of
association**. Weak, noisy, inconsistent or drifting responses remain inconclusive.
**A weak response is never proof of a nonmetallic object.** Nonferromagnetic metals
can have weak responses. Surrounding materials, phone components, motion and other
interference can explain field changes. No scan establishes metal identity.

### Camera, capture and offline behavior

CameraX 1.4.1 uses `STRATEGY_KEEP_ONLY_LATEST`, RGBA frames and one analysis executor.
Model initialization, conversion and CPU inference run off the main thread; every
ImageProxy closes in `finally`. Camera resources unbind on pause and leaving Vision.
Old viewport frames are cleared on rebind; the independent acquired target remains.
The camera and sensor do not monitor in the background.

EfficientDet scores are uncalibrated; detection threshold is 0.35 with at most ten
regions. Live boxes have inference latency and stale boxes are hidden. **Freeze**
pauses live analysis and displays a matching analyzed image, while magnetic values
remain live. It is separate from acquiring a target, which leaves detection running.
**Capture** exports the acquired frame and amber/dim overlays when a target exists,
otherwise the current analyzed frame, with acquisition/material/scan explanations
and a snapshot of live magnetic readings. Files are PNGs in `Pictures/MAGPIE` via
MediaStore without storage permission. Footer text wraps to keep caveats visible.

Model source revision, original redistribution license, tensor contract and
SHA-256 hashes are in [third_party/models/README.md](third_party/models/README.md).
The detector and labels are bundled and hash-checked by Gradle. LiteRT 1.1.2 runs
on Android without Play Services. Existing open-source dependencies and the
Android-only Ubuntu GitHub Actions debug-APK workflow are preserved.

## Operation

MAGPIE discovers `TYPE_MAGNETIC_FIELD` at runtime. Missing hardware, failed listener registration and absent/stale readings show explicit states. Samples older than two seconds are unavailable. No sensor values are fabricated. Raw device X/Y/Z readings are in microteslas; magnitude is `sqrt(x*x + y*y + z*z)`. Axes follow the device's natural sensor coordinate system and are not remapped when the UI rotates.

An exponential moving average (alpha 0.20) filters each axis, seeded with the first sample. Raw and smoothed axes and magnitudes are displayed separately. The large display and 0–100 µT gauge use smoothed magnitude. The gauge saturates above 100 µT, while numerical values and the auto-ranging chart retain the full reading. Capture baseline stores the current smoothed vector. Deviation is the **signed difference of smoothed and baseline magnitudes**, not vector displacement or a source classification. Rotating a sensor can affect measurements and filtering. Accuracy is Android's sensor-reported status, not an independent calibration guarantee.

The Canvas history retains the most recent 60 seconds, sampled for display at up to 10 Hz, with raw amber and smoothed green traces. Gaps over two seconds are not connected. Reset clears readings, baseline, filter and history. A ViewModel retains state across Activity recreation and rotation, but not process death. The filter re-seeds on resume; baseline and recent history remain. Listener registration is confined to Activity onResume/onPause. There is no service or background monitoring.

The scrollable, inset-aware UI supports portrait and landscape. No internet permission, advertising, analytics, accounts, Firebase or Google Play Services. Runtime dependencies are open-source AndroidX/Kotlin/LiteRT/ONNX Runtime libraries; unit tests use JUnit 4 (EPL-1.0). AndroidX and Kotlin use Apache-2.0.

## Verification

Unit tests cover three-axis magnitude, signed baseline deviation, EMA initialization, filtering, reset and invalid input. Vision tests cover letterbox inversion, affine crop inversion across scale,
translation, every orthogonal rotation and mirroring, invalid boxes/transforms,
specific selection and stale-job rejection, material decision gates and unavailable
models, crop-only inference, scan phase recording/interruption/quality, weak-response
interpretation and nonmetal fusion exclusion. Independent instrument sample observers
are also tested. Existing magnetometer tests
remain in place. `tools/verify_model.py` verifies model/label hashes and runs real
inference against the upstream licensed cat fixture. To run it locally:

```sh
python3 -m venv .venv
.venv/bin/pip install -r tools/model-test-requirements.txt
.venv/bin/python tools/verify_model.py
.venv/bin/python tools/verify_material_model.py
```

GitHub Actions runs that host smoke test and the same Gradle build/test command
on Ubuntu with JDK 17. It also runs the material host reference test, then real
Android-native material/acquisition tests on an API 29 x86_64 emulator, checks the
APK has no internet permission, and uploads `MAGPIE-debug-apk` and test reports. Run status is available on the repository's Actions tab after this project is pushed to GitHub.

The host smoke test uses LiteRT 2.3.0 to check the real model; the Android adapter
uses LiteRT 1.1.2 and checks its tensor contract at startup. The material adapter
uses ONNX Runtime 1.23.2 on both host and Android; its Kotlin preprocessing is
compared against Pillow references, and native output is compared against the
committed real-model score vector. See
[material validation scope](third_party/materials23/VALIDATION.md). Camera/magnetometer
hardware was not available in the build workspace. JVM, host and emulator tests do
not establish physical overlay alignment, field material accuracy, calibration,
camera compatibility on every phone, or detector accuracy.

Vision device acceptance checks: deny/grant/revoke camera permission; try absent
rear camera/flash/magnetometer; compare boxes against stationary objects in portrait
and landscape, including preview edges; rotate while live/frozen/acquired; acquire
similar overlapping objects and confirm the snapshot never follows later boxes;
release/replace targets rapidly; background/resume and switch tabs; check freeze,
torch and screenshot overlays/metadata. Test three complete physical scan passes,
accuracy loss, backgrounding and between-pass interruption. Confirm acquired crops
remain correct at preview edges and rotated/cropped viewports. Verify snapshots
remain stable while stale live boxes/readings disappear. Test actual Android native model inference on target devices,
including device ABI/16 KB page-size compatibility.

Instrument device acceptance checks: launch on a device without a magnetometer; observe live readings and accuracy on hardware; capture/reset baseline; rotate repeatedly; background and resume; verify no monitoring while paused; confirm stale readings become unavailable and the chart expires old data. These require a device/emulator and are separate from JVM tests.

## Roadmap

**Future — anomaly alerts:** add configurable magnitude-deviation thresholds, dwell time, hysteresis and cooldown to the pure measurement layer, with tests for threshold crossings and sensor loss. Gate alerts on valid fresh readings and accuracy; explain that alerts indicate field changes only. Start with foreground UI alerts. Any future notifications or background sensing require an explicit opt-in design and lifecycle/battery review.

**Future — Glance widgets:** add an AndroidX Glance module and widget provider, with timestamped snapshots from explicitly persisted foreground measurements. Show stale/unavailable states and open the app for live capture. Widgets must not imply continuous or live monitoring; no background sensor listener. Widgets are not implemented in this phase.

## License

Apache License 2.0; see [LICENSE](LICENSE).

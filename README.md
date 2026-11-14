# MAGPIE

**Magnetic Anomaly & Geomagnetic Pocket Instrument Explorer** is a native Kotlin / Jetpack Compose Android pocket magnetometer. Package: `uk.co.magpie.instrument`. Android 10 (API 29) or later; magnetic field hardware is optional.

## Build

Install JDK 17 and Android SDK platform 35 (with build tools 35.0.0), set `ANDROID_HOME` or put `sdk.dir=/path/to/sdk` in untracked `local.properties`, then:

```sh
./gradlew testDebugUnitTest assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

Pinned stable toolchain: AGP 8.7.3, Gradle 8.9, Kotlin and Compose compiler plugin 2.0.21, Compose BOM 2024.12.01, Activity 1.9.3, Lifecycle 2.8.7. Java source/target is 17. The Gradle wrapper is included and its distribution checksum is pinned. A first build requires access to Google Maven, Maven Central and Gradle distributions.

## Operation

MAGPIE discovers `TYPE_MAGNETIC_FIELD` at runtime. Missing hardware, failed listener registration and absent/stale readings show explicit states. Samples older than two seconds are unavailable. No sensor values are fabricated. Raw device X/Y/Z readings are in microteslas; magnitude is `sqrt(x*x + y*y + z*z)`. Axes follow the device's natural sensor coordinate system and are not remapped when the UI rotates.

An exponential moving average (alpha 0.20) filters each axis, seeded with the first sample. Raw and smoothed axes and magnitudes are displayed separately. The large display and 0–100 µT gauge use smoothed magnitude. The gauge saturates above 100 µT, while numerical values and the auto-ranging chart retain the full reading. Capture baseline stores the current smoothed vector. Deviation is the **signed difference of smoothed and baseline magnitudes**, not vector displacement or a source classification. Rotating a sensor can affect measurements and filtering. Accuracy is Android's sensor-reported status, not an independent calibration guarantee.

The Canvas history retains the most recent 60 seconds, sampled for display at up to 10 Hz, with raw amber and smoothed green traces. Gaps over two seconds are not connected. Reset clears readings, baseline, filter and history. A ViewModel retains state across Activity recreation and rotation, but not process death. The filter re-seeds on resume; baseline and recent history remain. Listener registration is confined to Activity onResume/onPause. There is no service or background monitoring.

The scrollable, inset-aware UI supports portrait and landscape. No internet permission, advertising, analytics, accounts, Firebase or Google Play Services. Runtime dependencies are open-source AndroidX/Kotlin libraries; unit tests use JUnit 4 (EPL-1.0). AndroidX and Kotlin use Apache-2.0.

## Verification

Unit tests cover three-axis magnitude, signed baseline deviation, EMA initialization, filtering, reset and invalid input. GitHub Actions runs the same build/test command on Ubuntu with JDK 17 and uploads `MAGPIE-debug-apk` and test reports. Run status is available on the repository's Actions tab after this project is pushed to GitHub.

Device acceptance checks: launch on a device without a magnetometer; observe live readings and accuracy on hardware; capture/reset baseline; rotate repeatedly; background and resume; verify no monitoring while paused; confirm stale readings become unavailable and the chart expires old data. These require a device/emulator and are separate from JVM tests.

## Roadmap

**Phase 2 — anomaly alerts:** add configurable magnitude-deviation thresholds, dwell time, hysteresis and cooldown to the pure measurement layer, with tests for threshold crossings and sensor loss. Gate alerts on valid fresh readings and accuracy; explain that alerts indicate field changes only. Start with foreground UI alerts. Any future notifications or background sensing require an explicit opt-in design and lifecycle/battery review.

**Phase 3 — Glance widgets:** add an AndroidX Glance module and widget provider, with timestamped snapshots from explicitly persisted foreground measurements. Show stale/unavailable states and open the app for live capture. Widgets must not imply continuous or live monitoring; no background sensor listener. Widgets are not implemented in this phase.

## License

Apache License 2.0; see [LICENSE](LICENSE).

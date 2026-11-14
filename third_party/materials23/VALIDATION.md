# Material adapter validation scope

The following are regression and functionality checks, not an independent material
recognition accuracy study. No confidence calibration or alloy capability is claimed.

- Upstream model bytes, length and SHA-256; ordered labels from the original config.
- Android/host ONNX Runtime **1.23.2**, CPU-only, 2 intra-op / 1 inter-op thread,
  memory-pattern and CPU-arena caching disabled; runtime telemetry disabled.
- Pure Kotlin preprocessing RGB hashes compared with Pillow 13 bilinear references
  for 37×19, 389×271, 224×224 and 1×1 patterns, covering up/downsampling and edges.
- NCHW layout, RGB normalization, stable softmax, finite logits and ambiguous label
  family checks in JVM tests, alongside existing acquisition/scan/instrument tests.
- Actual host inference using the shipped weights on the upstream Apache-2.0
  Google Coral `cat.bmp` fixture already in this repository. The cat image is
  **not material ground truth**; this validates a real working graph/tensor pipeline.
- The existing LiteRT EfficientDet detector is also invoked before and after material
  inference while both native adapters are alive, checking runtime coexistence.
- The same real fixture and expected score vector are tested through the actual
  **Android native adapter** on the GitHub Actions API 29 x86_64 emulator. Score
  decoded RGB, resized RGB and normalized FLOAT32 NCHW inputs must match their
  host SHA-256 references exactly. Native score comparison tolerance is 0.02,
  with the same top three fixture labels and normalized finite scores required.
  This is not a confidence/accuracy threshold. The initial strict 0.005 comparison
  found a maximum difference of 0.008371 between the host and Android quantized
  runtimes despite identical weights and bit-identical input tensors. This measured
  native numerical variation is documented rather than changing the app's scores.
- Android Activity acquisition runs through the real AndroidViewModel factory,
  actual crop/pixels and native classifier. The test deliberately uses object label
  `knife` with cat pixels to ensure material output follows pixels, not COCO labels.
  The acquired image/result are checked for stability across Activity recreation.
- The native test asserts absence of INTERNET permission. CI also checks the built
  APK's permissions before uploading it, and uploads Android native test reports.
- Android instrumentation assets live only in the test APK, not the user APK.

The Android workflow must pass these checks before publishing its debug APK.
`tools/verify_material_model.py` checks committed host regression references;
`--write-reference` is a development command for deliberate, reviewed regeneration.

The new ARM64 ONNX Runtime 1.23.2 library has 16 KB-aligned load segments. That does
not replace physical-device validation of the complete APK or all other libraries.
API 29 x86_64 emulator success does not establish ARM phone latency, peak memory,
field accuracy, camera crop quality on every device, or accuracy on thin/reflective/
painted objects. Test representative labelled target crops and a range of actual
phones before making any accuracy or identification claims.

# Bundled detector provenance and redistribution

The following files come from **Google Coral / test_data**, revision
`104342d2d3480b3e66203073dac24f4e2dbb4c41`:

- `efficientdet_lite0_320_ptq.tflite` → `app/src/main/assets/models/efficientdet_lite0.tflite`
- `coco_labels.txt` → `app/src/main/assets/models/coco_labels.txt`
- `cat.bmp` → `tools/testdata/cat.bmp` (test fixture only, excluded from APK)

Source repository: https://github.com/google-coral/test_data/tree/104342d2d3480b3e66203073dac24f4e2dbb4c41

The repository explicitly distributes pre-trained/pre-compiled TFLite models and test
images under its Apache License 2.0. Its license at the pinned revision is:
https://github.com/google-coral/test_data/blob/104342d2d3480b3e66203073dac24f4e2dbb4c41/LICENSE

A copy is retained in [LICENSE](LICENSE). Apache-2.0 permits redistribution of these
files; the original binary model, labels and test image are unmodified. No external
model fetch or separately accepted runtime terms are required by this app.

EfficientDet-Lite0 is an object detector trained on COCO categories, not a material
classifier. It accepts RGB UINT8 `[1,320,320,3]`, quantization scale 0.0078125 and
zero point 127, and emits four FLOAT32 tensors: boxes `[1,25,4]` in normalized
`[top,left,bottom,right]`, zero-based class indices `[1,25]`, scores `[1,25]` and
count `[1]`. Labels retain COCO's `n/a` slots; these must not be removed or shifted.
Raw input byte values are passed directly; quantization is part of the model.
The display threshold is 0.35. Scores are uncalibrated and are not probabilities
of correct identification. The model has no validated metal/material capability.

SHA-256:

```
33a3b622c7cac0762f96089353cd61495f3e993968d133af7871bfc2d5396704  efficientdet_lite0.tflite
dc183f003fc753c4c43fae6fdf7f387559449573f13fa32e517fb7453fd380f1  coco_labels.txt
```

`tools/verify_model.py` checks the shipped bytes and invokes the actual model on
the licensed cat fixture. The host smoke test uses LiteRT 2.3.0; Android uses
LiteRT 1.1.2, with the input/output contract checked on initialization. The host
smoke test does not replace Android device testing.

Runtime: `com.google.ai.edge.litert:litert:1.1.2`, Apache-2.0, verified via its
published Maven POM. CameraX 1.4.1 is AndroidX / Apache-2.0. Neither requires
Google Play Services, accounts, telemetry, cloud inference or internet permission.

# Bundled MINC material classifier

MAGPIE v1.3.0 bundles real material-classification weights. It does not derive
material labels from EfficientDet/COCO object names.

## Source, integrity and redistribution

Original: [prithivMLmods/Minc-Materials-23](https://huggingface.co/prithivMLmods/Minc-Materials-23),
revision `b21f5bc35e96bfd60ef218088ae6ecba56ffe858`, trained from
`google/siglip2-base-patch16-224` on MINC material classes.

Export: [manhhuynhsd/Minc-Materials-23-ONNX](https://huggingface.co/manhhuynhsd/Minc-Materials-23-ONNX),
revision `273554c59c161c2b787c56136e21bfd64d56ff13`.

Original `model_quantized.onnx` is bundled **unmodified** as
`app/src/main/assets/models/minc_materials23_int8.onnx` (88,864,018 bytes).

SHA-256:

```
0222f661abc762bc0ef69a7eded939a921ab7e0f2174c37788891162f1d0f2c8  minc_materials23_int8.onnx
ef8d90b59508e8b5c79f07ef4b6811017a228d8960bbbaeee02a203bb796be90  minc_materials23_labels.txt
```

Both the original model publisher and export publisher declare **Apache-2.0**;
the base model is also published as Apache-2.0. The pinned original/export model
cards, configuration and preprocessing contract are preserved in `provenance/`.
These are publisher license declarations, not a separate audit of every original
training image's rights. MINC annotations and photos have their own terms; MAGPIE
redistributes the licensed pretrained weights, not MINC training photos.

The APK and source include attribution, Apache-2.0 model license, ONNX Runtime MIT
license and its full upstream third-party notices under
`app/src/main/assets/licenses/`. The user can read them offline via **Material
model & licenses**. Runtime: `com.microsoft.onnxruntime:onnxruntime-android:1.23.2`,
verified stable Maven artifact and MIT license. Its CPU provider is used, telemetry
is explicitly disabled, and no internet permission is present.

## Input/output and adapter

Model input: `pixel_values`, FLOAT32 `[1,3,224,224]` RGB / NCHW. Resize the complete
selected crop to 224×224 with antialiased bilinear resampling, rescale RGB by 1/255,
then normalize each channel with mean 0.5 and standard deviation 0.5. Published
`preprocessor_config.json` uses PIL resample value 2 (bilinear).

Output: `logits`, FLOAT32 `[1,23]`. Ordered labels are copied from the pinned original
`config.json`; index 9 is `metal`. A numerically stable softmax produces **uncalibrated
ranking scores**, not validated identification probabilities. The model has one
broad metal class; no steel/aluminium/copper/alloy identities.

`OnnxMaterialClassifier` initializes lazily on the material executor, memory-maps
the uncompressed APK asset, verifies SHA-256 and label order, checks native tensor
names/types/shapes, then runs actual CPU inference. It does not extract/download a
second model file or allocate an 89 MB Java byte array. Native tensors/results/options
close with their owning scope, and the session closes with its ViewModel. A failure
returns Material model unavailable / UNKNOWN. No object label is passed to the
adapter. Acquired target generation guards discard obsolete results; live detections
never initiate material inference.

`metal` maps to METAL_SUSPECTED only if score/margin gates pass. `mirror`, `other`,
`painted` and `sky` stay ambiguous/UNKNOWN. Remaining trained classes represent
nonmetal **visible surfaces**, not proof about hidden or mixed materials. The
existing 0.70 score / 0.15 margin gates are heuristics, not calibrated probabilities.

## Limitations

Visible appearance cannot prove bulk composition, hidden metal, magnetic behavior
or alloy identity. A thin diagonal object can occupy a small part of its rectangular
crop and the model may classify background. Coatings and mixed materials can also
mislead it. Field accuracy and confidence calibration have not been established.
Unknown rejection, manual scanning and provisional/interference language remain.
See [VALIDATION.md](VALIDATION.md) for the precise test scope.

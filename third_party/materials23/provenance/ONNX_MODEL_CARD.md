---
language: en
license: apache-2.0
tags:
- vision
- image-classification
- onnx
- siglip2
- minc
- material-recognition
- pbr
- blender
pipeline_tag: image-classification
---

# MINC-Materials-23 ONNX

ONNX export and INT8 dynamic quantized version of **`prithivMLmods/Minc-Materials-23`** (fine-tuned SigLIP 2 `google/siglip2-base-patch16-224` on the MINC - Materials in Context dataset).

Designed for fast, lightweight PBR material classification and surface detection in 3D/VFX applications (Blender Addons, Unity, Unreal Engine, WebGL).

---

## 📦 Model Files

| File | Precision | Size | Description |
|---|---|---|---|
| `model_quantized.onnx` | **INT8 Dynamic** | **~84.7 MB** | **Recommended.** Ultra-fast MatMul/Gemm INT8 quantization for lightweight CPU/GPU deployment. |
| `model.onnx` | **FP32** | **~354 MB** | Full-precision standard ONNX model. |

---

## 🏷️ 23 Material Classes

The model predicts probabilities across 23 visual surface material categories:

`brick`, `carpet`, `ceramic`, `fabric`, `foliage`, `food`, `glass`, `hair`, `leather`, `metal`, `mirror`, `other`, `painted`, `paper`, `plastic`, `polishedstone`, `skin`, `sky`, `stone`, `tile`, `wallpaper`, `water`, `wood`

---

## 💻 Python ONNX Runtime Usage

```python
import onnxruntime as ort
import numpy as np
from PIL import Image

# 1. Load quantized ONNX model session
session = ort.InferenceSession("model_quantized.onnx", providers=['CPUExecutionProvider'])

# 2. Preprocess input RGB image (224x224)
img = Image.open("sample.jpg").convert("RGB").resize((224, 224), Image.Resampling.BILINEAR)
img_np = np.array(img, dtype=np.float32) / 255.0

# 3. SigLIP Normalization: (x - 0.5) / 0.5
norm_img = (img_np - 0.5) / 0.5
tensor_in = np.transpose(norm_img, (2, 0, 1))[np.newaxis, ...].astype(np.float32)

# 4. Run ONNX Inference
logits = session.run(None, {"pixel_values": tensor_in})[0][0]

# 5. Softmax over logits
exp_logits = np.exp(logits - np.max(logits))
probs = exp_logits / np.sum(exp_logits)

classes = [
    'brick', 'carpet', 'ceramic', 'fabric', 'foliage', 'food', 'glass', 'hair',
    'leather', 'metal', 'mirror', 'other', 'painted', 'paper', 'plastic',
    'polishedstone', 'skin', 'sky', 'stone', 'tile', 'wallpaper', 'water', 'wood'
]

top_idx = np.argmax(probs)
print(f"Top Material Class: {classes[top_idx]} ({probs[top_idx]*100:.2f}%)")
```

---

## 📜 License & Attribution

- **License**: Apache License 2.0
- **Original Model**: [`prithivMLmods/Minc-Materials-23`](https://huggingface.co/prithivMLmods/Minc-Materials-23)
- **Base Architecture**: [`google/siglip2-base-patch16-224`](https://huggingface.co/google/siglip2-base-patch16-224) (Google Research)
- **Dataset**: MINC (Materials in Context) Database (Bell et al., Cornell University)
- **ONNX Conversion & Quantization**: Manh Huynh ([`manhhuynhsd`](https://huggingface.co/manhhuynhsd))

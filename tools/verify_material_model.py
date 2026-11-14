#!/usr/bin/env python3
"""Pinned material model integrity, native CPU inference, and reference generation.

References are technical regression fixtures, not material ground truth or accuracy metrics.
"""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "app/src/main/assets/models/minc_materials23_int8.onnx"
SHA = "0222f661abc762bc0ef69a7eded939a921ab7e0f2174c37788891162f1d0f2c8"
REFERENCE = ROOT / "tools/testdata/material_reference.json"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--write-reference", action="store_true")
    args = parser.parse_args()
    with MODEL.open("rb") as f:
        assert hashlib.file_digest(f, "sha256").hexdigest() == SHA
    import numpy as np
    import onnxruntime as ort
    from PIL import Image

    ort.disable_telemetry_events()
    labels = (ROOT / "app/src/main/assets/models/minc_materials23_labels.txt").read_text().splitlines()
    original = json.loads((ROOT / "third_party/materials23/provenance/config.json").read_text())
    assert labels == [original["id2label"][str(i)] for i in range(23)]
    assert labels[9] == "metal"
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    options.enable_cpu_mem_arena = False
    options.enable_mem_pattern = False
    session = ort.InferenceSession(str(MODEL), sess_options=options, providers=["CPUExecutionProvider"])
    assert [(v.name, v.shape[1:], v.type) for v in session.get_inputs()] == [("pixel_values", [3, 224, 224], "tensor(float)")]
    assert [(v.name, v.shape[1:], v.type) for v in session.get_outputs()] == [("logits", [23], "tensor(float)")]
    original_image = Image.open(ROOT / "tools/testdata/cat.bmp").convert("RGB")
    image = original_image.resize((224, 224), Image.Resampling.BILINEAR)
    pixels = np.asarray(image, dtype=np.float32)
    tensor = np.ascontiguousarray(((pixels / np.float32(255) - np.float32(.5)) / np.float32(.5)).transpose(2, 0, 1)[None, ...])
    logits = session.run(["logits"], {"pixel_values": tensor})[0]
    assert logits.shape == (1, 23) and np.isfinite(logits).all()
    scores = np.exp(logits[0].astype(np.float64) - float(logits.max()))
    scores /= scores.sum()
    resize_fixtures = {}
    for width, height in [(37, 19), (389, 271), (224, 224), (1, 1)]:
        y, x = np.indices((height, width))
        pixels = np.stack(((x * 13 + y * 7) % 256, (x * 3 + y * 17) % 256, (x * 19 + y * 5) % 256), axis=2).astype(np.uint8)
        resized = Image.fromarray(pixels).resize((224, 224), Image.Resampling.BILINEAR)
        resize_fixtures[f"{width}x{height}"] = hashlib.sha256(resized.tobytes()).hexdigest()
    actual = {"sha256": SHA, "runtime": ort.__version__, "labels": labels,
              "cat_logits": logits[0].tolist(), "cat_scores": scores.tolist(),
              "cat_original_rgb_sha256": hashlib.sha256(original_image.tobytes()).hexdigest(),
              "cat_resized_rgb_sha256": hashlib.sha256(image.tobytes()).hexdigest(),
              "cat_tensor_sha256": hashlib.sha256(tensor.astype("<f4").tobytes()).hexdigest(),
              "resize_sha256": resize_fixtures,
              "scope": "Real graph/tensor/preprocessing regression only. Cat fixture is not material ground truth; no field accuracy or calibration claim."}
    if args.write_reference:
        REFERENCE.write_text(json.dumps(actual, indent=2) + "\n")
        (ROOT / "app/src/androidTest/assets/material_reference.json").write_text(REFERENCE.read_text())
        properties = "\n".join(f"{key}={value}" for key, value in resize_fixtures.items()) + "\n"
        (ROOT / "app/src/test/resources/material/resize.properties").write_text(properties)
    else:
        expected = json.loads(REFERENCE.read_text())
        assert expected["sha256"] == SHA and expected["labels"] == labels
        assert expected["resize_sha256"] == resize_fixtures
        for key in ("cat_original_rgb_sha256", "cat_resized_rgb_sha256", "cat_tensor_sha256"):
            assert expected[key] == actual[key]
        np.testing.assert_allclose(logits[0], expected["cat_logits"], rtol=0, atol=.02)
        np.testing.assert_allclose(scores, expected["cat_scores"], rtol=0, atol=.005)
    print(f"Material model SHA-256, label order, real inference and preprocessing reference passed (ORT {ort.__version__})")
    print("This is not a material-accuracy or phone-performance benchmark.")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Verify shipped bytes and real CPU inference. No model/label downloads at runtime."""
import argparse
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/models"
EXPECTED = {
    "efficientdet_lite0.tflite": "33a3b622c7cac0762f96089353cd61495f3e993968d133af7871bfc2d5396704",
    "coco_labels.txt": "dc183f003fc753c4c43fae6fdf7f387559449573f13fa32e517fb7453fd380f1",
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--integrity-only", action="store_true")
    args = parser.parse_args()
    for filename, expected in EXPECTED.items():
        assert hashlib.sha256((ASSETS / filename).read_bytes()).hexdigest() == expected, filename
    print("Bundled model and labels: SHA-256 verified")
    if args.integrity_only:
        return
    import numpy as np
    from PIL import Image
    from ai_edge_litert.interpreter import Interpreter

    interpreter = Interpreter(model_path=str(ASSETS / "efficientdet_lite0.tflite"), num_threads=2)
    interpreter.allocate_tensors()
    inputs = interpreter.get_input_details()
    outputs = interpreter.get_output_details()
    assert len(inputs) == 1 and inputs[0]["shape"].tolist() == [1, 320, 320, 3]
    assert inputs[0]["dtype"] == np.uint8
    assert [out["shape"].tolist() for out in outputs] == [[1, 25, 4], [1, 25], [1, 25], [1]]
    assert all(out["dtype"] == np.float32 for out in outputs)
    image = Image.open(ROOT / "tools/testdata/cat.bmp").convert("RGB")
    scale = min(320 / image.width, 320 / image.height)
    image = image.resize((round(image.width * scale), round(image.height * scale)), Image.Resampling.BILINEAR)
    letterbox = Image.new("RGB", (320, 320), (127, 127, 127))
    letterbox.paste(image, ((320 - image.width) // 2, (320 - image.height) // 2))
    interpreter.set_tensor(inputs[0]["index"], np.asarray(letterbox, dtype=np.uint8)[None, ...])
    interpreter.invoke()
    boxes, classes, scores, count = [interpreter.get_tensor(out["index"]) for out in outputs]
    labels = (ASSETS / "coco_labels.txt").read_text().splitlines()
    found = [(labels[int(classes[0, i])], float(scores[0, i])) for i in range(int(count[0]))]
    assert any(label == "cat" and score >= 0.35 for label, score in found), found
    assert np.isfinite(boxes).all() and np.isfinite(scores).all()
    print("Real detector smoke test passed:", found[:3], "(uncalibrated scores)")


if __name__ == "__main__":
    main()

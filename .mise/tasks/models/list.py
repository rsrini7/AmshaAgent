#!/usr/bin/env python3
#MISE description="List installed local ONNX model bundles and check manifest status"
"""List installed ONNX model bundles under data/models/."""

from __future__ import annotations

import json
import os
from pathlib import Path


def main() -> None:
    model_root = Path(os.environ.get("AMSHA_ONNX_MODEL_ROOT", "data/models"))
    if not model_root.is_dir():
        print(f"Directory {model_root} does not exist.")
        return

    print(f"📂 Scanning ONNX models in {model_root.resolve()}:\n")
    found = 0
    for bundle_dir in sorted(model_root.iterdir()):
        if not bundle_dir.is_dir():
            continue
        manifest_file = bundle_dir / "manifest.json"
        model_file = bundle_dir / "model.onnx"
        tokenizer_file = bundle_dir / "tokenizer.json"

        if manifest_file.is_file():
            try:
                with manifest_file.open("r", encoding="utf-8") as f:
                    manifest = json.load(f)
                bundle_id = manifest.get("bundleId", bundle_dir.name)
                is_whisper = (
                    "audioTranscription" in manifest
                    or "whisper" in bundle_id.lower()
                    or (bundle_dir / "encoder_model.onnx").is_file()
                )

                if is_whisper:
                    kind = "speech-to-text (whisper)"
                    enc_file = bundle_dir / "encoder_model.onnx"
                    dec_file = bundle_dir / "decoder_model.onnx"
                    valid = enc_file.is_file() and dec_file.is_file() and tokenizer_file.is_file()
                    status = "✅ Valid" if valid else "⚠️ Incomplete"
                    enc_sz = f"{enc_file.stat().st_size / (1024*1024):.1f} MB" if enc_file.is_file() else "missing"
                    dec_sz = f"{dec_file.stat().st_size / (1024*1024):.1f} MB" if dec_file.is_file() else "missing"
                    tok_sz = f"{tokenizer_file.stat().st_size / 1024:.1f} KB" if tokenizer_file.is_file() else "missing"
                    print(f"  • {bundle_id} [{kind}]")
                    print(f"      Status: {status} (encoder: {enc_sz}, decoder: {dec_sz}, tokenizer: {tok_sz})")
                    print(f"      Path:   {bundle_dir}")
                    found += 1
                    continue

                kind = "embedding" if "embedding" in manifest else ("scoring/reranker" if "scoring" in manifest else "classifier/other")
                model_name = manifest.get("modelFile", "model.onnx")
                model_file = bundle_dir / model_name
                model_sz = f"{model_file.stat().st_size / (1024*1024):.1f} MB" if model_file.is_file() else "missing"
                tok_sz = f"{tokenizer_file.stat().st_size / 1024:.1f} KB" if tokenizer_file.is_file() else "missing"
                status = "✅ Valid" if model_file.is_file() and tokenizer_file.is_file() else "⚠️ Incomplete"

                print(f"  • {bundle_id} [{kind}]")
                print(f"      Status: {status} (model: {model_sz}, tokenizer: {tok_sz})")
                print(f"      Path:   {bundle_dir}")
                found += 1
            except Exception as e:
                print(f"  • {bundle_dir.name}: Corrupt manifest ({e})")
        else:
            print(f"  • {bundle_dir.name}: No manifest.json")

    if found == 0:
        print("  No installed ONNX model bundles found.")
        print("  Run `mise run models:install-all` to download development bundles.")


if __name__ == "__main__":
    main()

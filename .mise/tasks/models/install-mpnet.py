#!/usr/bin/env python3
#MISE description="Install pinned verified all-mpnet-base-v2 ONNX development bundle"
"""Install the pinned MPNet development bundle on every supported host."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import urllib.request


BUNDLE_ID = "all-mpnet-base-v2-e8c3b32"
REVISION = "e8c3b32edf5434bc2275fc9bab85f82640a19130"
MODEL_BYTES = 435826548
MODEL_SHA256 = "74187b16d9c946fea252e120cfd7a12c5779d8b8b86838a2e4c56573c47941bd"
TOKENIZER_BYTES = 466021
TOKENIZER_SHA256 = "b8be2c30ba5dd723a6d5ee26d013da103d5408d92ddcb23747622f9e48f1d842"
SOURCE_BASE = (
    "https://huggingface.co/sentence-transformers/all-mpnet-base-v2/resolve/" + REVISION
)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verified_artifact(path: Path, expected_bytes: int, expected_sha256: str) -> bool:
    return (
        path.is_file()
        and path.stat().st_size == expected_bytes
        and sha256_file(path) == expected_sha256
    )


def install_artifact(bundle_directory: Path, name: str, expected_bytes: int, expected_sha256: str) -> None:
    target = bundle_directory / name
    if verified_artifact(target, expected_bytes, expected_sha256):
        return

    partial = target.with_name(target.name + ".partial")
    partial.unlink(missing_ok=True)
    url = f"{SOURCE_BASE}/{('onnx/' if name == 'model.onnx' else '')}{name}"
    try:
        with urllib.request.urlopen(url, timeout=60) as response, partial.open("wb") as output:
            shutil.copyfileobj(response, output, length=1024 * 1024)
        if not verified_artifact(partial, expected_bytes, expected_sha256):
            raise RuntimeError(f"Downloaded {name} failed byte-count or SHA-256 verification")
        os.replace(partial, target)
    except Exception:
        partial.unlink(missing_ok=True)
        raise


def write_manifest(bundle_directory: Path) -> None:
    manifest = {
        "bundleId": BUNDLE_ID,
        "revision": REVISION,
        "modelFile": "model.onnx",
        "modelSha256": MODEL_SHA256,
        "modelBytes": MODEL_BYTES,
        "tokenizerFile": "tokenizer.json",
        "tokenizerSha256": TOKENIZER_SHA256,
        "tokenizerBytes": TOKENIZER_BYTES,
        "embedding": {
            "tokenizerRevision": REVISION,
            "inputIdsName": "input_ids",
            "attentionMaskName": "attention_mask",
            "tokenTypeIdsName": None,
            "outputName": "last_hidden_state",
            "maxSequenceLength": 384,
            "vectorDimension": 768,
            "paddingTokenId": 1,
            "poolingStrategy": "MASKED_MEAN",
            "l2Normalize": True,
            "spaceId": "sentence-transformers/all-mpnet-base-v2",
            "spaceRevision": REVISION,
            "warmupProbes": ["Amsha local ONNX warm-up.", "Vector embedding readiness probe."],
        },
        "source": {
            "repository": "https://huggingface.co/sentence-transformers/all-mpnet-base-v2",
            "revision": REVISION,
            "modelPath": "onnx/model.onnx",
            "tokenizerPath": "tokenizer.json",
            "license": "Apache-2.0",
        },
    }
    with (bundle_directory / "manifest.json").open("w", encoding="utf-8", newline="\n") as output:
        json.dump(manifest, output, indent=2)
        output.write("\n")


def main() -> None:
    model_root = Path(os.environ.get("AMSHA_ONNX_MODEL_ROOT", "data/models"))
    bundle_directory = model_root / BUNDLE_ID
    bundle_directory.mkdir(parents=True, exist_ok=True)
    install_artifact(bundle_directory, "model.onnx", MODEL_BYTES, MODEL_SHA256)
    install_artifact(bundle_directory, "tokenizer.json", TOKENIZER_BYTES, TOKENIZER_SHA256)
    write_manifest(bundle_directory)
    print(f"Wrote verified bundle manifest: {bundle_directory / 'manifest.json'}")
    print(
        "Enable AMSHA_ONNX_ENABLED=true, "
        f"AMSHA_EMBEDDING_ONNX_MODEL={BUNDLE_ID}, and include onnx in "
        "AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS."
    )


if __name__ == "__main__":
    main()

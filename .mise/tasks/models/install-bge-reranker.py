#!/usr/bin/env python3
#MISE description="Install pinned verified bge-reranker-v1 ONNX cross-encoder bundle"
"""Install the pinned BGE Reranker development bundle on supported hosts."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import urllib.request


BUNDLE_ID = "bge-reranker-v1"
REVISION = "a76e9c60e58c8e1a1005a76e9389270e5b9fbb40"
MODEL_BYTES = 1112459588
MODEL_SHA256 = "15b9a8c3da82eddf263df571281166e00e9308fe19d077084b642ebfcaf06d2b"
TOKENIZER_BYTES = 17098079
TOKENIZER_SHA256 = "48564c5c7d3fa64d85d95e65414a542385f88b0f128fd8d4163fd7a57f2be05c"
SOURCE_BASE = "https://huggingface.co/Xenova/bge-reranker-base/resolve/main"


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


def install_artifact(bundle_directory: Path, name: str, subpath: str, expected_bytes: int, expected_sha256: str) -> None:
    target = bundle_directory / name
    if verified_artifact(target, expected_bytes, expected_sha256):
        print(f"Artifact {name} already installed and verified.")
        return

    partial = target.with_name(target.name + ".partial")
    partial.unlink(missing_ok=True)
    url = f"{SOURCE_BASE}/{subpath}"
    print(f"Downloading {name} from {url}...")
    headers = {"User-Agent": "AmshaRerankerInstaller/1.0"}
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=180) as response, partial.open("wb") as output:
            shutil.copyfileobj(response, output, length=1024 * 1024)
        if not verified_artifact(partial, expected_bytes, expected_sha256):
            raise RuntimeError(f"Downloaded {name} failed byte-count ({expected_bytes}) or SHA-256 verification")
        os.replace(partial, target)
        print(f"Verified and installed {name}: {expected_bytes} bytes")
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
        "scoring": {
            "inputType": "text-pair",
            "tokenizerRevision": REVISION,
            "inputIdsName": "input_ids",
            "attentionMaskName": "attention_mask",
            "tokenTypeIdsName": None,
            "outputName": "logits",
            "maxSequenceLength": 384,
            "paddingTokenId": 1,
            "outputLabel": "relevance",
            "scoreDirection": "HIGHER_IS_BETTER",
            "expectedOutputShape": "BATCH_BY_ONE",
            "warmupProbes": [
                "Amsha local ONNX reranker warm-up probe query.",
                "Candidate document text for cross-encoder readiness validation."
            ]
        },
        "source": {
            "repository": "https://huggingface.co/Xenova/bge-reranker-base",
            "revision": REVISION,
            "modelPath": "onnx/model.onnx",
            "tokenizerPath": "tokenizer.json",
            "license": "Apache-2.0"
        }
    }
    with (bundle_directory / "manifest.json").open("w", encoding="utf-8", newline="\n") as output:
        json.dump(manifest, output, indent=2)
        output.write("\n")


def main() -> None:
    model_root = Path(os.environ.get("AMSHA_ONNX_MODEL_ROOT", "data/models"))
    bundle_directory = model_root / BUNDLE_ID
    bundle_directory.mkdir(parents=True, exist_ok=True)
    install_artifact(bundle_directory, "model.onnx", "onnx/model.onnx", MODEL_BYTES, MODEL_SHA256)
    install_artifact(bundle_directory, "tokenizer.json", "tokenizer.json", TOKENIZER_BYTES, TOKENIZER_SHA256)
    write_manifest(bundle_directory)
    print(f"Wrote verified reranker bundle manifest: {bundle_directory / 'manifest.json'}")
    print(
        "Enable with AMSHA_ONNX_ENABLED=true and AMSHA_RAG_RERANKER_ENABLED=true."
    )


if __name__ == "__main__":
    main()

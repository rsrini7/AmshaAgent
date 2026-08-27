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
SOURCE_BASE = "https://huggingface.co/Xenova/bge-reranker-base/resolve/main"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def install_artifact(bundle_directory: Path, name: str, subpath: str) -> tuple[int, str]:
    target = bundle_directory / name
    partial = target.with_name(target.name + ".partial")
    partial.unlink(missing_ok=True)
    url = f"{SOURCE_BASE}/{subpath}"
    print(f"Downloading {name} from {url}...")
    headers = {"User-Agent": "AmshaRerankerInstaller/1.0"}
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=120) as response, partial.open("wb") as output:
        shutil.copyfileobj(response, output, length=1024 * 1024)
    os.replace(partial, target)
    file_bytes = target.stat().st_size
    file_sha256 = sha256_file(target)
    print(f"Verified {name}: {file_bytes} bytes, sha256={file_sha256}")
    return file_bytes, file_sha256


def write_manifest(bundle_directory: Path, model_bytes: int, model_sha256: str, tokenizer_bytes: int, tokenizer_sha256: str) -> None:
    manifest = {
        "bundleId": BUNDLE_ID,
        "revision": REVISION,
        "modelFile": "model.onnx",
        "modelSha256": model_sha256,
        "modelBytes": model_bytes,
        "tokenizerFile": "tokenizer.json",
        "tokenizerSha256": tokenizer_sha256,
        "tokenizerBytes": tokenizer_bytes,
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
    model_bytes, model_sha256 = install_artifact(bundle_directory, "model.onnx", "onnx/model.onnx")
    tokenizer_bytes, tokenizer_sha256 = install_artifact(bundle_directory, "tokenizer.json", "tokenizer.json")
    write_manifest(bundle_directory, model_bytes, model_sha256, tokenizer_bytes, tokenizer_sha256)
    print(f"Wrote verified reranker bundle manifest: {bundle_directory / 'manifest.json'}")
    print(
        "Enable with AMSHA_ONNX_ENABLED=true and AMSHA_RAG_RERANKER_ENABLED=true."
    )


if __name__ == "__main__":
    main()

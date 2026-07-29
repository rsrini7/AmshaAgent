#!/usr/bin/env python3
"""Offline smoke test for the pinned MiniLM, PyTorch, and ONNX export stack."""

from __future__ import annotations

import json
import tempfile
from pathlib import Path

import torch
from transformers import AutoModelForSequenceClassification, AutoTokenizer

from package_classifier_bundle import export_onnx, sha256
from train_classifier import DEFAULT_BASE_MODEL, package_versions


def main() -> int:
    receipt = DEFAULT_BASE_MODEL / "source-lock.verified.json"
    if not receipt.is_file():
        raise RuntimeError("run mise run classifier:install-base first")

    torch.manual_seed(2112)
    tokenizer = AutoTokenizer.from_pretrained(DEFAULT_BASE_MODEL, local_files_only=True, use_fast=True)
    model = AutoModelForSequenceClassification.from_pretrained(
        DEFAULT_BASE_MODEL,
        local_files_only=True,
        num_labels=2,
        id2label={0: "ACCEPTABLE", 1: "RETRY_RECOMMENDED"},
        label2id={"ACCEPTABLE": 0, "RETRY_RECOMMENDED": 1},
        ignore_mismatched_sizes=True,
    )

    with tempfile.TemporaryDirectory(prefix="amsha-classifier-toolchain-") as directory:
        model_directory = Path(directory) / "model"
        model.save_pretrained(model_directory, safe_serialization=True)
        tokenizer.save_pretrained(model_directory)
        onnx_path = Path(directory) / "model.onnx"
        input_names, output_name = export_onnx(model_directory, onnx_path, 32)
        summary = {
            "base_revision": json.loads(receipt.read_text(encoding="utf-8"))["revision"],
            "input_names": input_names,
            "output_name": output_name,
            "onnx_bytes": onnx_path.stat().st_size,
            "onnx_sha256": sha256(onnx_path),
            "packages": package_versions(),
        }
        print(json.dumps(summary, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

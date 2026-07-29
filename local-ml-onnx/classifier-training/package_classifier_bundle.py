#!/usr/bin/env python3
"""Export a passing classifier run to a verified Amsha ONNX bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path
from typing import Any

from classifier_data import TASK_LABELS, DatasetValidationError, load_jsonl, validate_records

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parents[1]
DEFAULT_MODEL_ROOT = PROJECT_ROOT / "data" / "models"
WARMUP_PROBES = {
    "intent": ["Research the current API documentation.", "Fix the parser regression."],
    "tool_risk": [
        '{"classifier":"tool_risk","agent_role":"coordinator","autonomy":"SUPERVISED","tool_name":"core_memory_read","goal":"read profile","parameters":{"block_key":"user_profile"}}',
        '{"classifier":"tool_risk","agent_role":"coordinator","autonomy":"SUPERVISED","tool_name":"sandbox_exec","goal":"run command","parameters":{"code":"date"}}',
    ],
    "answer_quality": [
        '{"classifier":"answer_quality","agent_role":"coordinator","goal":"answer clearly","answer":"The requested result is ready.","tool_invoked_in_turn":false}',
        '{"classifier":"answer_quality","agent_role":"coordinator","goal":"answer clearly","answer":"Done.","tool_invoked_in_turn":false}',
    ],
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def export_onnx(best_directory: Path, output: Path, maximum_length: int) -> tuple[list[str], str]:
    import numpy as np
    import onnx
    import onnxruntime
    import torch
    from transformers import AutoModelForSequenceClassification, AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(best_directory, local_files_only=True, use_fast=True)
    model = AutoModelForSequenceClassification.from_pretrained(best_directory, local_files_only=True)
    model.eval()
    probe = tokenizer(
        "Amsha classifier export probe.",
        max_length=maximum_length,
        padding="max_length",
        truncation=True,
        return_tensors="pt",
    )
    input_names = [name for name in ("input_ids", "attention_mask", "token_type_ids") if name in probe]

    class LogitsOnly(torch.nn.Module):
        def __init__(self, classifier: Any, names: list[str]):
            super().__init__()
            self.classifier = classifier
            self.names = names

        def forward(self, *values: Any) -> Any:
            return self.classifier(**dict(zip(self.names, values, strict=True))).logits

    wrapper = LogitsOnly(model, input_names)
    wrapper.eval()
    arguments = tuple(probe[name] for name in input_names)
    dynamic_axes = {name: {0: "batch", 1: "sequence"} for name in input_names}
    dynamic_axes["logits"] = {0: "batch"}
    torch.onnx.export(
        wrapper,
        arguments,
        output,
        input_names=input_names,
        output_names=["logits"],
        dynamic_axes=dynamic_axes,
        opset_version=17,
        do_constant_folding=True,
        dynamo=False,
    )
    onnx.checker.check_model(onnx.load(output))

    session = onnxruntime.InferenceSession(str(output), providers=["CPUExecutionProvider"])
    ort_inputs = {name: probe[name].numpy() for name in input_names}
    ort_logits = session.run(["logits"], ort_inputs)[0]
    wrapper.eval()
    with torch.no_grad():
        torch_logits = wrapper(*arguments).numpy()
    if not np.allclose(torch_logits, ort_logits, rtol=1e-4, atol=1e-4):
        maximum_difference = float(np.max(np.abs(torch_logits - ort_logits)))
        raise RuntimeError(
            f"ONNX logits do not match the trained PyTorch classifier; max_abs_diff={maximum_difference:.8f}"
        )
    return input_names, "logits"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("task", choices=sorted(TASK_LABELS))
    parser.add_argument("dataset", type=Path)
    parser.add_argument("model_dir", type=Path)
    parser.add_argument("--model-root", type=Path, default=DEFAULT_MODEL_ROOT)
    args = parser.parse_args()

    try:
        records = load_jsonl(args.dataset)
        summary = validate_records(
            records, expected_task=args.task, require_approved=True, enforce_release_size=True
        )
        metadata = json.loads((args.model_dir / "training-metadata.json").read_text(encoding="utf-8"))
        evaluation = json.loads((args.model_dir / "evaluation.json").read_text(encoding="utf-8"))
        if metadata["dataset_sha256"] != summary["sha256"] or evaluation["dataset_sha256"] != summary["sha256"]:
            raise RuntimeError("training or evaluation does not match the approved dataset")
        if not evaluation.get("release_gate_passed"):
            raise RuntimeError("evaluation or error-budget approval gate has not passed")

        bundle_id = f"{args.task.replace('_', '-')}-minilm-l6-{summary['sha256'][:8]}"
        destination = (args.model_root / bundle_id).resolve()
        if destination.exists():
            raise RuntimeError(f"refusing to overwrite existing bundle: {destination}")
        destination.mkdir(parents=True)

        model_path = destination / "model.onnx"
        input_names, output_name = export_onnx(
            args.model_dir / "best-model", model_path, metadata["max_sequence_length"]
        )
        tokenizer_source = args.model_dir / "best-model" / "tokenizer.json"
        tokenizer_path = destination / "tokenizer.json"
        shutil.copyfile(tokenizer_source, tokenizer_path)

        source_lock = json.loads((SCRIPT_DIR / "base-model-lock.json").read_text(encoding="utf-8"))
        manifest = {
            "bundleId": bundle_id,
            "revision": f"{source_lock['revision']}-{summary['sha256'][:12]}",
            "modelFile": model_path.name,
            "modelSha256": sha256(model_path),
            "modelBytes": model_path.stat().st_size,
            "tokenizerFile": tokenizer_path.name,
            "tokenizerSha256": sha256(tokenizer_path),
            "tokenizerBytes": tokenizer_path.stat().st_size,
            "classifier": {
                "tokenizerRevision": source_lock["revision"],
                "inputIdsName": "input_ids",
                "attentionMaskName": "attention_mask",
                "tokenTypeIdsName": "token_type_ids" if "token_type_ids" in input_names else None,
                "outputName": output_name,
                "maxSequenceLength": metadata["max_sequence_length"],
                "paddingTokenId": 0,
                "labels": metadata["labels"],
                "scoreTransform": "SOFTMAX",
                "minimumConfidence": evaluation["minimum_confidence"],
                "ruleId": f"{args.task}-shadow-v1",
                "warmupProbes": WARMUP_PROBES[args.task],
            },
            "source": {
                "repository": source_lock["repository"],
                "revision": source_lock["revision"],
                "modelPath": "model.safetensors",
                "tokenizerPath": "tokenizer.json",
                "license": source_lock["license"],
            },
        }
        (destination / "manifest.json").write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        shutil.copyfile(args.model_dir / "evaluation.json", destination / "evaluation.json")
        shutil.copyfile(args.model_dir / "MODEL_CARD.draft.md", destination / "MODEL_CARD.md")
        print(f"verified development classifier bundle: {destination}")
        return 0
    except (DatasetValidationError, KeyError, OSError, RuntimeError, ValueError) as error:
        print(f"classifier packaging failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

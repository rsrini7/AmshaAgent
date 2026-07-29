#!/usr/bin/env python3
"""Train, calibrate, and evaluate one approved MiniLM shadow classifier."""

from __future__ import annotations

import argparse
import importlib.metadata
import json
import math
import os
import platform
import random
import sys
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

from classifier_data import TASK_LABELS, DatasetValidationError, dataset_digest, load_jsonl, render_input, validate_records

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parents[1]
DEFAULT_BASE_MODEL = PROJECT_ROOT / "data" / "classifier-training" / "base" / "all-MiniLM-L6-v2-1110a243"
DEFAULT_RUN_ROOT = PROJECT_ROOT / "data" / "classifier-training" / "runs"
MAX_SEQUENCE_LENGTH = 256


def included_labels(task: str, records: Sequence[Mapping[str, Any]]) -> list[str]:
    present = {str(record["label"]) for record in records}
    return [label for label in TASK_LABELS[task] if label in present]


def split_records(records: Sequence[Mapping[str, Any]], split: str) -> list[Mapping[str, Any]]:
    selected = [record for record in records if record["split"] == split]
    if not selected:
        raise DatasetValidationError(f"dataset has no {split} records")
    return selected


def classification_metrics(
    actual: Sequence[str], predicted: Sequence[str | None], labels: Sequence[str], task: str
) -> dict[str, Any]:
    if len(actual) != len(predicted) or not actual:
        raise ValueError("actual and predicted must be non-empty and equally sized")

    matrix = {label: {candidate: 0 for candidate in (*labels, "NO_OPINION")} for label in labels}
    per_label: dict[str, dict[str, float | int]] = {}
    for expected, candidate in zip(actual, predicted, strict=True):
        matrix[expected][candidate or "NO_OPINION"] += 1

    f1_values: list[float] = []
    for label in labels:
        true_positive = sum(1 for expected, candidate in zip(actual, predicted, strict=True) if expected == label and candidate == label)
        false_positive = sum(1 for expected, candidate in zip(actual, predicted, strict=True) if expected != label and candidate == label)
        false_negative = sum(1 for expected, candidate in zip(actual, predicted, strict=True) if expected == label and candidate != label)
        precision = true_positive / (true_positive + false_positive) if true_positive + false_positive else 0.0
        recall = true_positive / (true_positive + false_negative) if true_positive + false_negative else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        f1_values.append(f1)
        per_label[label] = {
            "support": sum(1 for value in actual if value == label),
            "precision": precision,
            "recall": recall,
            "f1": f1,
        }

    opinions = sum(candidate is not None for candidate in predicted)
    result: dict[str, Any] = {
        "count": len(actual),
        "coverage": opinions / len(actual),
        "accuracy": sum(expected == candidate for expected, candidate in zip(actual, predicted, strict=True)) / len(actual),
        "macro_f1": sum(f1_values) / len(f1_values),
        "per_label": per_label,
        "confusion_matrix": matrix,
    }
    if task == "tool_risk":
        unsafe_total = sum(value in ("REQUIRE_APPROVAL", "BLOCK") for value in actual)
        block_total = sum(value == "BLOCK" for value in actual)
        result["unsafe_as_low_risk_rate"] = (
            sum(
                expected in ("REQUIRE_APPROVAL", "BLOCK") and candidate == "LOW_RISK"
                for expected, candidate in zip(actual, predicted, strict=True)
            )
            / unsafe_total
            if unsafe_total
            else 0.0
        )
        result["block_recall"] = (
            sum(expected == "BLOCK" and candidate == "BLOCK" for expected, candidate in zip(actual, predicted, strict=True))
            / block_total
            if block_total
            else 0.0
        )
    elif task == "answer_quality":
        retry_total = sum(value == "RETRY_RECOMMENDED" for value in actual)
        acceptable_total = sum(value == "ACCEPTABLE" for value in actual)
        result["retry_as_acceptable_rate"] = (
            sum(
                expected == "RETRY_RECOMMENDED" and candidate == "ACCEPTABLE"
                for expected, candidate in zip(actual, predicted, strict=True)
            )
            / retry_total
            if retry_total
            else 0.0
        )
        result["acceptable_as_retry_rate"] = (
            sum(
                expected == "ACCEPTABLE" and candidate == "RETRY_RECOMMENDED"
                for expected, candidate in zip(actual, predicted, strict=True)
            )
            / acceptable_total
            if acceptable_total
            else 0.0
        )
    return result


def meets_budget(metrics: Mapping[str, Any], task: str, budget: Mapping[str, Any]) -> bool:
    if metrics["coverage"] < budget["minimum_coverage"] or metrics["macro_f1"] < budget["minimum_macro_f1"]:
        return False
    if task == "intent":
        return all(
            values["recall"] >= budget["minimum_per_label_recall"]
            for values in metrics["per_label"].values()
        )
    if task == "tool_risk":
        return (
            metrics["unsafe_as_low_risk_rate"] <= budget["maximum_unsafe_as_low_risk_rate"]
            and metrics["block_recall"] >= budget["minimum_block_recall"]
        )
    return (
        metrics["retry_as_acceptable_rate"] <= budget["maximum_retry_as_acceptable_rate"]
        and metrics["acceptable_as_retry_rate"] <= budget["maximum_acceptable_as_retry_rate"]
    )


def calibrate_threshold(
    actual: Sequence[str], raw_predictions: Sequence[str], confidences: Sequence[float], labels: Sequence[str], task: str, budget: Mapping[str, Any]
) -> tuple[float, dict[str, Any], bool]:
    candidates: list[tuple[float, dict[str, Any], bool]] = []
    for step in range(100):
        threshold = step / 100
        thresholded = [prediction if confidence >= threshold else None for prediction, confidence in zip(raw_predictions, confidences, strict=True)]
        metrics = classification_metrics(actual, thresholded, labels, task)
        candidates.append((threshold, metrics, meets_budget(metrics, task, budget)))

    passing = [candidate for candidate in candidates if candidate[2]]
    if passing:
        return max(passing, key=lambda item: (item[1]["coverage"], item[1]["macro_f1"], item[0]))
    return max(candidates, key=lambda item: (item[1]["macro_f1"], item[1]["coverage"], item[0]))


def encoded_dataset(tokenizer: Any, records: Sequence[Mapping[str, Any]], label_to_id: Mapping[str, int]) -> Any:
    import torch

    rendered = [render_input(record) for record in records]
    encoded = tokenizer(
        rendered,
        max_length=MAX_SEQUENCE_LENGTH,
        padding="max_length",
        truncation=True,
        return_tensors="pt",
    )
    encoded["labels"] = torch.tensor([label_to_id[str(record["label"])] for record in records], dtype=torch.long)

    class EncodedDataset(torch.utils.data.Dataset):
        def __len__(self) -> int:
            return len(records)

        def __getitem__(self, index: int) -> dict[str, Any]:
            return {key: value[index] for key, value in encoded.items()}

    return EncodedDataset()


def select_device(torch: Any) -> Any:
    if torch.backends.mps.is_available():
        return torch.device("mps")
    if torch.cuda.is_available():
        return torch.device("cuda")
    return torch.device("cpu")


def predict(model: Any, tokenizer: Any, records: Sequence[Mapping[str, Any]], labels: Sequence[str], device: Any, batch_size: int) -> tuple[list[str], list[float]]:
    import torch

    dataset = encoded_dataset(tokenizer, records, {label: index for index, label in enumerate(labels)})
    loader = torch.utils.data.DataLoader(dataset, batch_size=batch_size, shuffle=False)
    predictions: list[str] = []
    confidences: list[float] = []
    model.eval()
    with torch.no_grad():
        for batch in loader:
            inputs = {key: value.to(device) for key, value in batch.items() if key != "labels"}
            scores = torch.softmax(model(**inputs).logits, dim=-1).cpu()
            selected = scores.argmax(dim=-1)
            predictions.extend(labels[index] for index in selected.tolist())
            confidences.extend(scores.max(dim=-1).values.tolist())
    return predictions, confidences


def package_versions() -> dict[str, str]:
    names = ("accelerate", "onnx", "onnxruntime", "scikit-learn", "torch", "transformers")
    return {name: importlib.metadata.version(name) for name in names}


def load_approved_dataset(path: Path, task: str) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    records = load_jsonl(path)
    summary = validate_records(records, expected_task=task, require_approved=True, enforce_release_size=True)
    return records, summary


def train(args: argparse.Namespace) -> int:
    import torch
    from transformers import AutoModelForSequenceClassification, AutoTokenizer

    records, summary = load_approved_dataset(args.dataset, args.task)
    labels = included_labels(args.task, records)
    base_model = args.base_model.resolve()
    if not (base_model / "source-lock.verified.json").is_file():
        raise RuntimeError("pinned base model is not installed and verified")

    run_directory = (args.output_root / f"{args.task}-{summary['sha256'][:12]}").resolve()
    if run_directory.exists():
        raise RuntimeError(f"refusing to overwrite existing training run: {run_directory}")
    run_directory.mkdir(parents=True)

    random.seed(args.seed)
    torch.manual_seed(args.seed)
    label_to_id = {label: index for index, label in enumerate(labels)}
    id_to_label = {index: label for label, index in label_to_id.items()}
    tokenizer = AutoTokenizer.from_pretrained(base_model, local_files_only=True, use_fast=True)
    model = AutoModelForSequenceClassification.from_pretrained(
        base_model,
        local_files_only=True,
        num_labels=len(labels),
        label2id=label_to_id,
        id2label=id_to_label,
        ignore_mismatched_sizes=True,
    )
    device = select_device(torch)
    model.to(device)

    train_records = split_records(records, "train")
    validation_records = split_records(records, "validation")
    train_dataset = encoded_dataset(tokenizer, train_records, label_to_id)
    generator = torch.Generator().manual_seed(args.seed)
    loader = torch.utils.data.DataLoader(
        train_dataset, batch_size=args.batch_size, shuffle=True, generator=generator
    )
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.learning_rate)
    best_macro_f1 = -math.inf
    best_directory = run_directory / "best-model"

    for epoch in range(args.epochs):
        model.train()
        total_loss = 0.0
        for batch in loader:
            optimizer.zero_grad(set_to_none=True)
            batch = {key: value.to(device) for key, value in batch.items()}
            output = model(**batch)
            output.loss.backward()
            optimizer.step()
            total_loss += float(output.loss.detach().cpu())

        raw_predictions, confidences = predict(
            model, tokenizer, validation_records, labels, device, args.batch_size
        )
        validation_metrics = classification_metrics(
            [str(record["label"]) for record in validation_records], raw_predictions, labels, args.task
        )
        print(
            f"epoch={epoch + 1} loss={total_loss / max(1, len(loader)):.6f} "
            f"validation_macro_f1={validation_metrics['macro_f1']:.6f} "
            f"mean_confidence={sum(confidences) / len(confidences):.6f}"
        )
        if validation_metrics["macro_f1"] > best_macro_f1:
            best_macro_f1 = validation_metrics["macro_f1"]
            model.save_pretrained(best_directory, safe_serialization=True)
            tokenizer.save_pretrained(best_directory)

    metadata = {
        "task": args.task,
        "dataset_sha256": summary["sha256"],
        "dataset_counts": summary["counts"],
        "labels": labels,
        "base_model": str(base_model),
        "base_revision": json.loads((SCRIPT_DIR / "base-model-lock.json").read_text(encoding="utf-8"))["revision"],
        "seed": args.seed,
        "epochs": args.epochs,
        "batch_size": args.batch_size,
        "learning_rate": args.learning_rate,
        "max_sequence_length": MAX_SEQUENCE_LENGTH,
        "platform": platform.platform(),
        "packages": package_versions(),
    }
    (run_directory / "training-metadata.json").write_text(
        json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    write_draft_model_card(run_directory, metadata)
    print(f"training run ready for independent evaluation: {run_directory}")
    return 0


def evaluate(args: argparse.Namespace) -> int:
    import torch
    from transformers import AutoModelForSequenceClassification, AutoTokenizer

    records, summary = load_approved_dataset(args.dataset, args.task)
    metadata = json.loads((args.model_dir / "training-metadata.json").read_text(encoding="utf-8"))
    if metadata["task"] != args.task or metadata["dataset_sha256"] != summary["sha256"]:
        raise RuntimeError("model metadata does not match the approved dataset")

    budget_document = json.loads(args.budgets.read_text(encoding="utf-8"))
    budget = budget_document[args.task]
    labels = metadata["labels"]
    best_directory = args.model_dir / "best-model"
    tokenizer = AutoTokenizer.from_pretrained(best_directory, local_files_only=True, use_fast=True)
    model = AutoModelForSequenceClassification.from_pretrained(best_directory, local_files_only=True)
    device = select_device(torch)
    model.to(device)

    validation_records = split_records(records, "validation")
    validation_predictions, validation_confidences = predict(
        model, tokenizer, validation_records, labels, device, args.batch_size
    )
    threshold, validation_metrics, validation_passed = calibrate_threshold(
        [str(record["label"]) for record in validation_records],
        validation_predictions,
        validation_confidences,
        labels,
        args.task,
        budget,
    )

    test_records = split_records(records, "test")
    test_predictions, test_confidences = predict(model, tokenizer, test_records, labels, device, args.batch_size)
    thresholded_test = [
        prediction if confidence >= threshold else None
        for prediction, confidence in zip(test_predictions, test_confidences, strict=True)
    ]
    test_metrics = classification_metrics(
        [str(record["label"]) for record in test_records], thresholded_test, labels, args.task
    )
    test_passed = meets_budget(test_metrics, args.task, budget)
    approved_budget = budget_document.get("status") == "APPROVED"
    report = {
        "task": args.task,
        "dataset_sha256": summary["sha256"],
        "dataset_counts": summary["counts"],
        "labels": labels,
        "minimum_confidence": threshold,
        "budget_document": str(args.budgets),
        "budget_status": budget_document.get("status", "UNKNOWN"),
        "validation_passed": validation_passed,
        "test_passed": test_passed,
        "release_gate_passed": approved_budget and validation_passed and test_passed,
        "validation": validation_metrics,
        "test": test_metrics,
    }
    (args.model_dir / "evaluation.json").write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    print(
        f"evaluation task={args.task} threshold={threshold:.2f} "
        f"validation_passed={validation_passed} test_passed={test_passed} "
        f"budget_status={report['budget_status']}"
    )
    return 0 if report["release_gate_passed"] else 1


def write_draft_model_card(run_directory: Path, metadata: Mapping[str, Any]) -> None:
    text = f"""# {metadata['task']} MiniLM shadow classifier — draft model card

Status: NOT APPROVED. This draft is not release evidence and must not enable behavioral use.

- Base: sentence-transformers/all-MiniLM-L6-v2 at `{metadata['base_revision']}`
- Dataset SHA-256: `{metadata['dataset_sha256']}`
- Labels: {', '.join(metadata['labels'])}
- Maximum sequence length: {metadata['max_sequence_length']}
- Training seed: {metadata['seed']}

The model is intended only for disabled-by-default shadow observation. It cannot replace deterministic
authorization, HITL, tool execution policy, answer guards, retries, routing, or FINISH handling.

Required before approval: privacy review, dataset review record, calibration report, per-label error
analysis, adversarial prompt-injection evaluation for tool risk, supported-platform resource evidence,
rollback criteria, and retained shadow evidence against the approved budgets.
"""
    (run_directory / "MODEL_CARD.draft.md").write_text(text, encoding="utf-8")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    commands = root.add_subparsers(dest="command", required=True)

    train_command = commands.add_parser("train")
    train_command.add_argument("task", choices=sorted(TASK_LABELS))
    train_command.add_argument("dataset", type=Path)
    train_command.add_argument("--base-model", type=Path, default=DEFAULT_BASE_MODEL)
    train_command.add_argument("--output-root", type=Path, default=DEFAULT_RUN_ROOT)
    train_command.add_argument("--epochs", type=int, default=5)
    train_command.add_argument("--batch-size", type=int, default=16)
    train_command.add_argument("--learning-rate", type=float, default=2e-5)
    train_command.add_argument("--seed", type=int, default=2112)

    evaluate_command = commands.add_parser("evaluate")
    evaluate_command.add_argument("task", choices=sorted(TASK_LABELS))
    evaluate_command.add_argument("dataset", type=Path)
    evaluate_command.add_argument("model_dir", type=Path)
    evaluate_command.add_argument("--budgets", type=Path, default=SCRIPT_DIR / "error-budgets.proposed.json")
    evaluate_command.add_argument("--batch-size", type=int, default=32)
    return root


def main() -> int:
    args = parser().parse_args()
    try:
        if args.command == "train":
            return train(args)
        return evaluate(args)
    except (DatasetValidationError, OSError, RuntimeError, ValueError) as error:
        print(f"classifier {args.command} failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
    os.environ.setdefault("HF_HUB_DISABLE_TELEMETRY", "1")
    raise SystemExit(main())

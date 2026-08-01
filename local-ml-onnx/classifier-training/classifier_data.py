#!/usr/bin/env python3
"""Dataset rendering, de-identification, and release-gate validation."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import sys
from collections import Counter
from collections.abc import Iterable, Mapping
from pathlib import Path
from typing import Any

TASK_LABELS: dict[str, tuple[str, ...]] = {
    "intent": (
        "architect",
        "coordinator",
        "researcher",
        "coder",
        "debugger",
        "synthesizer",
        "optimizer",
        "sentinel",
        "healer",
        "protector",
        "purifier",
        "nurturer",
    ),
    "tool_risk": ("LOW_RISK", "REQUIRE_APPROVAL", "BLOCK"),
    "answer_quality": ("ACCEPTABLE", "RETRY_RECOMMENDED"),
}
SPLITS = frozenset(("train", "validation", "test"))
REVIEW_STATUSES = frozenset(("pending", "approved", "rejected"))
RELEASE_MINIMUM_PER_LABEL = {"train": 20, "validation": 10, "test": 20}

EMAIL = re.compile(r"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b")
PHONE = re.compile(r"(?<![\w.])(?:\+?\d[\d ()-]{7,}\d)(?![\w.])")
UUID = re.compile(r"(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b")
HOME_PATH = re.compile(r"/(?:Users|home)/[^/\s]+")
TELEGRAM_TOKEN = re.compile(r"(?<![A-Za-z0-9_-])\d{6,}:[A-Za-z0-9_-]{20,}(?![A-Za-z0-9_-])")
BEARER_TOKEN = re.compile(r"(?i)(\bbearer\s+)[A-Za-z0-9._~+/=-]{8,}")
SECRET_ASSIGNMENT = re.compile(
    r"(?i)(\b(?:authorization|api[-_]?key|access[-_]?token|auth[-_]?token|bot[-_]?token|client[-_]?secret|password)\b\s*[=:]\s*[\"']?)[^\s,\"';&]+"
)
IDENTIFIER = re.compile(r"[a-z0-9][a-z0-9._-]{2,127}")


class DatasetValidationError(ValueError):
    """Raised when a classifier dataset cannot pass its declared gate."""


def deidentify_text(value: str) -> str:
    value = TELEGRAM_TOKEN.sub("[TELEGRAM_TOKEN]", value)
    value = BEARER_TOKEN.sub(r"\1[SECRET]", value)
    value = SECRET_ASSIGNMENT.sub(r"\1[SECRET]", value)
    value = EMAIL.sub("[EMAIL]", value)
    value = PHONE.sub("[PHONE]", value)
    value = UUID.sub("[UUID]", value)
    return HOME_PATH.sub("/[HOME]/[USER]", value)


def deidentify(value: Any) -> Any:
    if isinstance(value, str):
        return deidentify_text(value)
    if isinstance(value, list):
        return [deidentify(item) for item in value]
    if isinstance(value, Mapping):
        return {str(key): deidentify(item) for key, item in value.items()}
    return value


def render_input(record: Mapping[str, Any]) -> str:
    task = require_string(record, "task")
    payload = record.get("input")
    if not isinstance(payload, Mapping):
        raise DatasetValidationError("input must be an object")

    if task == "intent":
        return require_string(payload, "message")
    if task == "tool_risk":
        rendered = {
            "classifier": "tool_risk",
            "agent_role": require_string(payload, "agent_role"),
            "autonomy": require_string(payload, "autonomy"),
            "tool_name": require_string(payload, "tool_name"),
            "goal": require_string(payload, "goal", allow_blank=True),
            "parameters": require_mapping(payload, "parameters"),
        }
        return json.dumps(rendered, separators=(",", ":"), ensure_ascii=False, sort_keys=False)
    if task == "answer_quality":
        tool_invoked = payload.get("tool_invoked_in_turn")
        if not isinstance(tool_invoked, bool):
            raise DatasetValidationError("input.tool_invoked_in_turn must be boolean")
        rendered = {
            "classifier": "answer_quality",
            "agent_role": require_string(payload, "agent_role"),
            "goal": require_string(payload, "goal", allow_blank=True),
            "answer": require_string(payload, "answer", allow_blank=True),
            "tool_invoked_in_turn": tool_invoked,
        }
        return json.dumps(rendered, separators=(",", ":"), ensure_ascii=False, sort_keys=False)
    raise DatasetValidationError(f"unsupported task: {task}")


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as source:
        for line_number, line in enumerate(source, 1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as error:
                raise DatasetValidationError(f"line {line_number}: invalid JSON: {error.msg}") from error
            if not isinstance(record, dict):
                raise DatasetValidationError(f"line {line_number}: record must be an object")
            record["_line"] = line_number
            records.append(record)
    if not records:
        raise DatasetValidationError("dataset is empty")
    return records


def write_jsonl(path: Path, records: Iterable[Mapping[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as target:
        for record in records:
            clean = {key: value for key, value in record.items() if key != "_line"}
            target.write(json.dumps(clean, ensure_ascii=False, sort_keys=True) + "\n")


def validate_records(
    records: list[dict[str, Any]],
    *,
    expected_task: str | None = None,
    require_approved: bool = False,
    enforce_release_size: bool = False,
) -> dict[str, Any]:
    errors: list[str] = []
    identifiers: set[str] = set()
    rendered_hashes: dict[str, str] = {}
    counts: Counter[tuple[str, str]] = Counter()
    task_seen: str | None = None

    for record in records:
        line = record.get("_line", "?")
        prefix = f"line {line}"
        try:
            identifier = require_string(record, "id")
            if not IDENTIFIER.fullmatch(identifier):
                raise DatasetValidationError("id has unsupported characters")
            if identifier in identifiers:
                raise DatasetValidationError(f"duplicate id: {identifier}")
            identifiers.add(identifier)

            task = require_string(record, "task")
            if task not in TASK_LABELS:
                raise DatasetValidationError(f"unsupported task: {task}")
            if expected_task is not None and task != expected_task:
                raise DatasetValidationError(f"expected task {expected_task}, found {task}")
            if task_seen is not None and task != task_seen:
                raise DatasetValidationError("one dataset file must contain exactly one task")
            task_seen = task

            label = require_string(record, "label")
            if label not in TASK_LABELS[task]:
                raise DatasetValidationError(f"unsupported {task} label: {label}")
            split = require_string(record, "split")
            if split not in SPLITS:
                raise DatasetValidationError(f"unsupported split: {split}")
            counts[(split, label)] += 1

            source = record.get("source")
            if not isinstance(source, Mapping):
                raise DatasetValidationError("source must be an object")
            require_string(source, "kind")
            require_string(source, "ref")
            require_string(source, "license")

            review = record.get("review")
            if not isinstance(review, Mapping):
                raise DatasetValidationError("review must be an object")
            status = require_string(review, "status")
            if status not in REVIEW_STATUSES:
                raise DatasetValidationError(f"unsupported review status: {status}")
            if require_approved:
                if status != "approved":
                    raise DatasetValidationError("release dataset contains a non-approved record")
                reviewer = require_string(review, "reviewer")
                if reviewer.casefold().startswith(("ai", "auto", "model")):
                    raise DatasetValidationError("reviewer must identify a human reviewer")
                require_string(review, "reviewed_at")

            rendered = render_input(record)
            if deidentify_text(rendered) != rendered:
                raise DatasetValidationError("input still contains credential or direct-identifier shaped text")
            digest = hashlib.sha256(rendered.encode("utf-8")).hexdigest()
            duplicate = rendered_hashes.get(digest)
            if duplicate is not None:
                raise DatasetValidationError(f"duplicate rendered input also used by {duplicate}")
            rendered_hashes[digest] = identifier
        except DatasetValidationError as error:
            errors.append(f"{prefix}: {error}")

    if enforce_release_size and task_seen is not None:
        labels_present = {label for _, label in counts}
        for label in sorted(labels_present):
            for split, minimum in RELEASE_MINIMUM_PER_LABEL.items():
                actual = counts[(split, label)]
                if actual < minimum:
                    errors.append(f"release minimum not met for {label}/{split}: {actual} < {minimum}")

    if errors:
        raise DatasetValidationError("\n".join(errors))

    return {
        "task": task_seen,
        "records": len(records),
        "counts": {f"{split}/{label}": count for (split, label), count in sorted(counts.items())},
        "sha256": dataset_digest(records),
        "approved": all(record.get("review", {}).get("status") == "approved" for record in records),
    }


def dataset_digest(records: Iterable[Mapping[str, Any]]) -> str:
    digest = hashlib.sha256()
    for record in records:
        clean = {key: value for key, value in record.items() if key != "_line"}
        digest.update(json.dumps(clean, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8"))
        digest.update(b"\n")
    return digest.hexdigest()


def require_string(mapping: Mapping[str, Any], key: str, *, allow_blank: bool = False) -> str:
    value = mapping.get(key)
    if not isinstance(value, str) or (not allow_blank and not value.strip()):
        raise DatasetValidationError(f"{key} must be a {'string' if allow_blank else 'non-blank string'}")
    return value


def require_mapping(mapping: Mapping[str, Any], key: str) -> Mapping[str, Any]:
    value = mapping.get(key)
    if not isinstance(value, Mapping):
        raise DatasetValidationError(f"{key} must be an object")
    return dict(sorted(value.items()))


def prepare_review(source: Path, target: Path) -> dict[str, Any]:
    records = load_jsonl(source)
    prepared: list[dict[str, Any]] = []
    for record in records:
        clean = {key: value for key, value in record.items() if key != "_line"}
        clean["input"] = deidentify(clean.get("input"))
        clean["review"] = {"status": "pending", "reviewer": "", "reviewed_at": "", "notes": ""}
        prepared.append(clean)
    write_jsonl(target, prepared)
    return validate_records(load_jsonl(target))


CSV_FIELDS = (
    "id",
    "task",
    "split",
    "label",
    "input_json",
    "source_kind",
    "source_ref",
    "source_license",
    "review_status",
    "reviewer",
    "reviewed_at",
    "notes",
)


def export_review_csv(source: Path, target: Path) -> dict[str, Any]:
    records = load_jsonl(source)
    summary = validate_records(records)
    with target.open("x", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=CSV_FIELDS, lineterminator="\n")
        writer.writeheader()
        for record in records:
            review = record["review"]
            source_spec = record["source"]
            writer.writerow(
                {
                    "id": record["id"],
                    "task": record["task"],
                    "split": record["split"],
                    "label": record["label"],
                    "input_json": json.dumps(record["input"], ensure_ascii=False, sort_keys=True),
                    "source_kind": source_spec["kind"],
                    "source_ref": source_spec["ref"],
                    "source_license": source_spec["license"],
                    "review_status": review["status"],
                    "reviewer": review.get("reviewer", ""),
                    "reviewed_at": review.get("reviewed_at", ""),
                    "notes": review.get("notes", ""),
                }
            )
    return summary


def import_review_csv(source: Path, target: Path) -> dict[str, Any]:
    records: list[dict[str, Any]] = []
    with source.open(encoding="utf-8", newline="") as input_file:
        reader = csv.DictReader(input_file)
        if tuple(reader.fieldnames or ()) != CSV_FIELDS:
            raise DatasetValidationError("review CSV header does not match the versioned schema")
        for line_number, row in enumerate(reader, 2):
            try:
                input_value = json.loads(row["input_json"])
            except json.JSONDecodeError as error:
                raise DatasetValidationError(
                    f"CSV line {line_number}: input_json is invalid: {error.msg}"
                ) from error
            records.append(
                {
                    "id": row["id"],
                    "task": row["task"],
                    "split": row["split"],
                    "label": row["label"],
                    "input": input_value,
                    "source": {
                        "kind": row["source_kind"],
                        "ref": row["source_ref"],
                        "license": row["source_license"],
                    },
                    "review": {
                        "status": row["review_status"],
                        "reviewer": row["reviewer"],
                        "reviewed_at": row["reviewed_at"],
                        "notes": row["notes"],
                    },
                }
            )
    summary = validate_records(records)
    write_jsonl(target, records)
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subcommands = parser.add_subparsers(dest="command", required=True)

    prepare = subcommands.add_parser("prepare-review", help="de-identify raw JSONL into a pending review packet")
    prepare.add_argument("source", type=Path)
    prepare.add_argument("target", type=Path)

    export_csv = subcommands.add_parser("export-review-csv", help="export a JSONL review packet to CSV")
    export_csv.add_argument("source", type=Path)
    export_csv.add_argument("target", type=Path)

    import_csv = subcommands.add_parser("import-review-csv", help="import a reviewed CSV packet to JSONL")
    import_csv.add_argument("source", type=Path)
    import_csv.add_argument("target", type=Path)

    validate = subcommands.add_parser("validate", help="validate a review or release dataset")
    validate.add_argument("dataset", type=Path)
    validate.add_argument("--task", choices=sorted(TASK_LABELS))
    validate.add_argument("--require-approved", action="store_true")
    validate.add_argument("--release-size", action="store_true")
    digest_command = subcommands.add_parser("digest", help="print the canonical dataset SHA-256")
    digest_command.add_argument("dataset", type=Path)

    args = parser.parse_args()
    try:
        if args.command == "prepare-review":
            summary = prepare_review(args.source, args.target)
        elif args.command == "export-review-csv":
            summary = export_review_csv(args.source, args.target)
        elif args.command == "import-review-csv":
            summary = import_review_csv(args.source, args.target)
        elif args.command == "digest":
            records = load_jsonl(args.dataset)
            summary = {"sha256": dataset_digest(records), "records": len(records)}
        else:
            summary = validate_records(
                load_jsonl(args.dataset),
                expected_task=args.task,
                require_approved=args.require_approved,
                enforce_release_size=args.release_size,
            )
        print(json.dumps(summary, indent=2, sort_keys=True))
        return 0
    except (DatasetValidationError, OSError) as error:
        print(f"classifier dataset gate failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Merge sanitized classifier candidates into a deterministic review dataset."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections.abc import Iterable, Mapping
from pathlib import Path
from typing import Any

from classifier_data import (
    TASK_LABELS,
    DatasetValidationError,
    deidentify,
    load_jsonl,
    render_input,
    validate_records,
    write_jsonl,
)


def _split_for(record: Mapping[str, Any]) -> str:
    """Assign a stable split from the rendered input, avoiding random leakage."""

    digest = hashlib.sha256(render_input(record).encode("utf-8")).hexdigest()
    bucket = int(digest[:8], 16) % 100
    if bucket < 60:
        return "train"
    if bucket < 80:
        return "validation"
    return "test"


def _canonical_record(record: Mapping[str, Any], *, assign_splits: bool, preserve_review: bool) -> dict[str, Any]:
    clean = {key: value for key, value in record.items() if key != "_line"}
    clean["input"] = deidentify(clean.get("input"))
    if assign_splits:
        clean["split"] = _split_for(clean)
    if not preserve_review:
        clean["review"] = {
            "status": "pending",
            "reviewer": "",
            "reviewed_at": "",
            "notes": "",
        }
    return clean


def build_dataset(
    task: str,
    sources: Iterable[Path],
    target: Path,
    *,
    assign_splits: bool,
    preserve_review: bool,
) -> dict[str, Any]:
    if task not in TASK_LABELS:
        raise DatasetValidationError(f"unsupported task: {task}")

    by_id: dict[str, dict[str, Any]] = {}
    by_input: dict[str, tuple[str, str]] = {}
    for source in sources:
        records = load_jsonl(source)
        for record in records:
            # Validate each candidate before merging so repeated append-only
            # collection records can be safely deduplicated here.
            validate_records([record], expected_task=task)
            clean = _canonical_record(record, assign_splits=assign_splits, preserve_review=preserve_review)
            identifier = str(clean["id"])
            rendered_digest = hashlib.sha256(render_input(clean).encode("utf-8")).hexdigest()
            existing = by_id.get(identifier)
            if existing is not None:
                if json.dumps(existing, sort_keys=True, ensure_ascii=False) != json.dumps(
                    clean, sort_keys=True, ensure_ascii=False
                ):
                    raise DatasetValidationError(f"conflicting duplicate id: {identifier}")
                continue
            previous = by_input.get(rendered_digest)
            if previous is not None:
                previous_id, previous_label = previous
                if previous_label != str(clean["label"]):
                    raise DatasetValidationError(
                        f"same rendered input has conflicting labels: {previous_id} and {identifier}"
                    )
                continue
            by_id[identifier] = clean
            by_input[rendered_digest] = (identifier, str(clean["label"]))

    merged = [by_id[key] for key in sorted(by_id)]
    summary = validate_records(merged, expected_task=task)
    write_jsonl(target, merged)
    return {**summary, "source_files": [str(path) for path in sources], "target": str(target)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("task", choices=sorted(TASK_LABELS))
    parser.add_argument("target", type=Path)
    parser.add_argument("sources", type=Path, nargs="+")
    parser.add_argument("--assign-splits", action="store_true")
    parser.add_argument("--preserve-review", action="store_true")
    args = parser.parse_args()
    try:
        summary = build_dataset(
            args.task,
            args.sources,
            args.target,
            assign_splits=args.assign_splits,
            preserve_review=args.preserve_review,
        )
        print(json.dumps(summary, indent=2, sort_keys=True))
        return 0
    except (DatasetValidationError, OSError, ValueError) as error:
        print(f"dataset build failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

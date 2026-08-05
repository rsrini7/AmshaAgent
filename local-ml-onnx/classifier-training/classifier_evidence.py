#!/usr/bin/env python3
"""Create retained classifier evidence and enforce approval-gated promotion."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from classifier_data import TASK_LABELS, DatasetValidationError, load_jsonl, validate_records


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _require_file(path: Path) -> Path:
    if not path.is_file():
        raise DatasetValidationError(f"required file not found: {path}")
    return path


def _read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(_require_file(path).read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise DatasetValidationError(f"invalid JSON in {path}: {error.msg}") from error
    if not isinstance(value, dict):
        raise DatasetValidationError(f"expected JSON object: {path}")
    return value


def _copy_artifact(source: Path, destination: Path, evidence_dir: Path) -> dict[str, Any]:
    _require_file(source)
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
    return {
        "path": str(destination.relative_to(evidence_dir)),
        "sha256": sha256(destination),
        "bytes": destination.stat().st_size,
    }


def _git_revision() -> str:
    try:
        import subprocess

        result = subprocess.run(
            ["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=True
        )
        return result.stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "UNKNOWN"


def create_evidence(args: argparse.Namespace) -> int:
    records = load_jsonl(args.dataset)
    summary = validate_records(records, expected_task=args.task, require_approved=True, enforce_release_size=True)
    evaluation = _read_json(args.run_dir / "evaluation.json")
    if evaluation.get("dataset_sha256") != summary["sha256"]:
        raise DatasetValidationError("evaluation dataset digest does not match the release dataset")
    if evaluation.get("budget_status") != "APPROVED":
        raise DatasetValidationError("classifier budget document is not APPROVED")
    if evaluation.get("release_gate_passed") is not True:
        raise DatasetValidationError("held-out evaluation did not pass the release gate")

    manifest = _read_json(args.bundle_dir / "manifest.json")
    if manifest.get("classifier", {}).get("task") not in (None, args.task):
        raise DatasetValidationError("bundle classifier task does not match requested task")

    if args.evidence_dir.exists():
        raise DatasetValidationError(f"refusing to overwrite evidence directory: {args.evidence_dir}")
    args.evidence_dir.mkdir(parents=True)
    artifacts = args.evidence_dir / "artifacts"
    copied: list[dict[str, Any]] = []
    copied.append(_copy_artifact(args.dataset, artifacts / "dataset.jsonl", args.evidence_dir))
    for name in ("training-metadata.json", "evaluation.json", "MODEL_CARD.draft.md"):
        copied.append(_copy_artifact(args.run_dir / name, artifacts / name, args.evidence_dir))
    bundle_artifacts = artifacts / "bundle"
    for source in sorted(args.bundle_dir.iterdir()):
        if source.is_file():
            copied.append(_copy_artifact(source, bundle_artifacts / source.name, args.evidence_dir))

    evidence = {
        "schemaVersion": 1,
        "status": "READY_FOR_HUMAN_REVIEW",
        "task": args.task,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "sourceRevision": _git_revision(),
        "dataset": {"sha256": summary["sha256"], "records": summary["records"], "counts": summary["counts"]},
        "evaluation": {
            "budgetStatus": evaluation.get("budget_status"),
            "releaseGatePassed": evaluation.get("release_gate_passed"),
            "validation": evaluation.get("validation"),
            "test": evaluation.get("test"),
        },
        "bundle": {"name": args.bundle_dir.name, "manifestSha256": sha256(args.bundle_dir / "manifest.json")},
        "artifacts": copied,
        "approval": {
            "status": "PENDING_HUMAN_APPROVAL",
            "requiredFields": [
                "reviewer",
                "approvedAt",
                "datasetApproved",
                "modelCardApproved",
                "rollbackCriteriaApproved",
                "shadowEvidenceApproved",
            ],
        },
    }
    evidence_text = json.dumps(evidence, indent=2, sort_keys=True) + "\n"
    (args.evidence_dir / "evidence.json").write_text(evidence_text, encoding="utf-8")
    evidence_digest = hashlib.sha256(evidence_text.encode("utf-8")).hexdigest()
    (args.evidence_dir / "evidence.sha256").write_text(
        f"{evidence_digest}  evidence.json\n", encoding="utf-8"
    )
    (args.evidence_dir / "approval-template.json").write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "status": "PENDING_HUMAN_APPROVAL",
                "evidenceSha256": evidence_digest,
                "task": args.task,
                "reviewer": "",
                "approvedAt": "",
                "datasetApproved": False,
                "modelCardApproved": False,
                "rollbackCriteriaApproved": False,
                "shadowEvidenceApproved": False,
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    print(json.dumps({"evidenceDir": str(args.evidence_dir), "evidenceSha256": evidence_digest}, indent=2))
    return 0


def promote(args: argparse.Namespace) -> int:
    evidence = _read_json(args.evidence_dir / "evidence.json")
    approval = _read_json(args.approval)
    evidence_text = (args.evidence_dir / "evidence.json").read_text(encoding="utf-8")
    evidence_digest = hashlib.sha256(evidence_text.encode("utf-8")).hexdigest()
    if evidence.get("status") != "READY_FOR_HUMAN_REVIEW":
        raise DatasetValidationError("evidence is not a reviewable candidate")
    if approval.get("status") != "APPROVED":
        raise DatasetValidationError("approval status is not APPROVED")
    if approval.get("evidenceSha256") != evidence_digest:
        raise DatasetValidationError("approval does not match this evidence bundle")
    reviewer = str(approval.get("reviewer", "")).strip()
    if not reviewer or reviewer.casefold().startswith(("ai", "auto", "model")):
        raise DatasetValidationError("promotion requires a named human reviewer")
    for field in ("datasetApproved", "modelCardApproved", "rollbackCriteriaApproved", "shadowEvidenceApproved"):
        if approval.get(field) is not True:
            raise DatasetValidationError(f"approval field is not true: {field}")

    source_bundle = args.evidence_dir / "artifacts" / "bundle"
    _require_file(source_bundle / "manifest.json")
    target = args.model_root / str(evidence["bundle"]["name"])
    if target.exists():
        raise DatasetValidationError(f"refusing to overwrite promoted bundle: {target}")
    shutil.copytree(source_bundle, target)
    promotion = {
        "schemaVersion": 1,
        "status": "PROMOTED",
        "task": evidence["task"],
        "bundle": target.name,
        "evidenceSha256": evidence_digest,
        "reviewer": reviewer,
        "approvedAt": approval.get("approvedAt"),
        "promotedAt": datetime.now(timezone.utc).isoformat(),
    }
    (target / "promotion.json").write_text(json.dumps(promotion, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    evidence["status"] = "PROMOTED"
    evidence["promotion"] = promotion
    updated_evidence = json.dumps(evidence, indent=2, sort_keys=True) + "\n"
    (args.evidence_dir / "evidence.json").write_text(updated_evidence, encoding="utf-8")
    (args.evidence_dir / "evidence.sha256").write_text(
        f"{hashlib.sha256(updated_evidence.encode('utf-8')).hexdigest()}  evidence.json\n",
        encoding="utf-8",
    )
    print(json.dumps(promotion, indent=2, sort_keys=True))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("create")
    create.add_argument("task", choices=sorted(TASK_LABELS))
    create.add_argument("dataset", type=Path)
    create.add_argument("run_dir", type=Path)
    create.add_argument("bundle_dir", type=Path)
    create.add_argument("evidence_dir", type=Path)
    promote_command = commands.add_parser("promote")
    promote_command.add_argument("evidence_dir", type=Path)
    promote_command.add_argument("approval", type=Path)
    promote_command.add_argument("model_root", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "create":
            return create_evidence(args)
        return promote(args)
    except (DatasetValidationError, OSError, KeyError, ValueError) as error:
        print(f"classifier evidence failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

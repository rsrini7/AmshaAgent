import json
import hashlib
import tempfile
import unittest
from pathlib import Path

from build_classifier_dataset import build_dataset
from classifier_data import load_jsonl
from classifier_evidence import promote


class ClassifierPipelineTest(unittest.TestCase):
    @staticmethod
    def record(identifier: str, message: str, label: str = "coder") -> dict:
        return {
            "id": identifier,
            "task": "intent",
            "label": label,
            "split": "train",
            "input": {"message": message},
            "source": {"kind": "test", "ref": identifier, "license": "Apache-2.0"},
            "review": {"status": "pending", "reviewer": "", "reviewed_at": "", "notes": ""},
        }

    def test_build_dataset_deduplicates_and_assigns_stable_splits(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "candidates.jsonl"
            target = root / "dataset.jsonl"
            records = [self.record("one", "Write a parser"), self.record("two", "Write a parser")]
            source.write_text("\n".join(json.dumps(record) for record in records) + "\n", encoding="utf-8")

            summary = build_dataset("intent", [source], target, assign_splits=True, preserve_review=False)

            self.assertEqual(1, summary["records"])
            built = load_jsonl(target)
            self.assertEqual("pending", built[0]["review"]["status"])
            self.assertIn(built[0]["split"], {"train", "validation", "test"})

    def test_promote_rejects_pending_human_approval(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence_dir = root / "evidence"
            bundle = evidence_dir / "artifacts" / "bundle"
            bundle.mkdir(parents=True)
            (bundle / "manifest.json").write_text("{}\n", encoding="utf-8")
            (evidence_dir / "evidence.json").write_text(
                json.dumps({"status": "READY_FOR_HUMAN_REVIEW", "task": "intent", "bundle": {"name": "x"}}),
                encoding="utf-8",
            )
            approval = root / "approval.json"
            approval.write_text(json.dumps({"status": "PENDING_HUMAN_APPROVAL"}), encoding="utf-8")

            with self.assertRaises(ValueError):
                promote(type("Args", (), {"evidence_dir": evidence_dir, "approval": approval, "model_root": root / "models"})())

    def test_promote_refreshes_evidence_sidecar_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence_dir = root / "evidence"
            bundle = evidence_dir / "artifacts" / "bundle"
            bundle.mkdir(parents=True)
            (bundle / "manifest.json").write_text("{}\n", encoding="utf-8")
            evidence_text = json.dumps(
                {"status": "READY_FOR_HUMAN_REVIEW", "task": "intent", "bundle": {"name": "x"}},
                indent=2,
                sort_keys=True,
            ) + "\n"
            (evidence_dir / "evidence.json").write_text(evidence_text, encoding="utf-8")
            evidence_digest = hashlib.sha256(evidence_text.encode("utf-8")).hexdigest()
            approval = root / "approval.json"
            approval.write_text(
                json.dumps(
                    {
                        "status": "APPROVED",
                        "evidenceSha256": evidence_digest,
                        "reviewer": "maintainer",
                        "approvedAt": "2026-08-01T00:00:00Z",
                        "datasetApproved": True,
                        "modelCardApproved": True,
                        "rollbackCriteriaApproved": True,
                        "shadowEvidenceApproved": True,
                    }
                ),
                encoding="utf-8",
            )

            promote(type("Args", (), {"evidence_dir": evidence_dir, "approval": approval, "model_root": root / "models"})())

            updated = (evidence_dir / "evidence.json").read_bytes()
            sidecar = (evidence_dir / "evidence.sha256").read_text(encoding="utf-8")
            self.assertTrue(sidecar.startswith(hashlib.sha256(updated).hexdigest()))

    def test_release_candidate_uses_training_run_prefix(self):
        project_root = Path(__file__).parents[2]
        training_source = (Path(__file__).parent / "train_classifier.py").read_text(encoding="utf-8")
        pipeline_source = (project_root / "scripts" / "classifier" / "classifier_training.sh").read_text(encoding="utf-8")

        self.assertIn("summary['sha256'][:12]", training_source)
        self.assertIn('run_dir="$RUN_ROOT/$task-${digest:0:12}"', pipeline_source)


if __name__ == "__main__":
    unittest.main()

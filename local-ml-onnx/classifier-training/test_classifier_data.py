import json
import tempfile
import unittest
from pathlib import Path

from classifier_data import (
    DatasetValidationError,
    deidentify_text,
    export_review_csv,
    import_review_csv,
    load_jsonl,
    prepare_review,
    render_input,
    validate_records,
)
from train_classifier import classification_metrics, meets_budget


class ClassifierDataTest(unittest.TestCase):
    def test_deidentify_removes_direct_identifiers_and_credentials(self):
        token = "123456789:AAExampleTelegramCredential_123456789"
        value = deidentify_text(f"mail me@example.com from /Users/alice token={token}")
        self.assertNotIn("me@example.com", value)
        self.assertNotIn("/Users/alice", value)
        self.assertNotIn(token, value)

    def test_render_tool_risk_matches_runtime_field_order(self):
        record = {
            "task": "tool_risk",
            "input": {
                "agent_role": "coordinator",
                "autonomy": "SUPERVISED",
                "tool_name": "github",
                "goal": "inspect repository",
                "parameters": {"z": 2, "a": 1},
            },
        }
        self.assertEqual(
            '{"classifier":"tool_risk","agent_role":"coordinator","autonomy":"SUPERVISED",'
            '"tool_name":"github","goal":"inspect repository","parameters":{"a":1,"z":2}}',
            render_input(record),
        )

    def test_release_gate_rejects_pending_review(self):
        record = self._intent_record("one", "train")
        with self.assertRaises(DatasetValidationError):
            validate_records([record], require_approved=True)

    def test_prepare_review_deidentifies_and_forces_pending(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "raw.jsonl"
            target = Path(directory) / "review.jsonl"
            record = self._intent_record("one", "train")
            record["input"]["message"] = "email me@example.com"
            source.write_text(json.dumps(record) + "\n", encoding="utf-8")

            summary = prepare_review(source, target)
            prepared = load_jsonl(target)[0]

            self.assertEqual("pending", prepared["review"]["status"])
            self.assertEqual("email [EMAIL]", prepared["input"]["message"])
            self.assertEqual(1, summary["records"])

    def test_no_opinion_reduces_coverage_and_recall(self):
        metrics = classification_metrics(
            ["ACCEPTABLE", "RETRY_RECOMMENDED"],
            ["ACCEPTABLE", None],
            ["ACCEPTABLE", "RETRY_RECOMMENDED"],
            "answer_quality",
        )
        self.assertEqual(0.5, metrics["coverage"])
        self.assertEqual(0.0, metrics["per_label"]["RETRY_RECOMMENDED"]["recall"])

    def test_tool_risk_budget_rejects_unsafe_low_risk_prediction(self):
        metrics = classification_metrics(
            ["LOW_RISK", "REQUIRE_APPROVAL", "BLOCK"],
            ["LOW_RISK", "LOW_RISK", "BLOCK"],
            ["LOW_RISK", "REQUIRE_APPROVAL", "BLOCK"],
            "tool_risk",
        )
        budget = {
            "minimum_coverage": 0.8,
            "minimum_macro_f1": 0.5,
            "maximum_unsafe_as_low_risk_rate": 0.01,
            "minimum_block_recall": 0.98,
        }
        self.assertFalse(meets_budget(metrics, "tool_risk", budget))

    def test_base_model_lock_contains_only_complete_hashes(self):
        lock_path = Path(__file__).with_name("base-model-lock.json")
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
        self.assertEqual(40, len(lock["revision"]))
        for specification in lock["files"].values():
            self.assertGreater(specification["bytes"], 0)
            self.assertRegex(specification["sha256"], r"^[0-9a-f]{64}$")

    def test_review_csv_round_trip_preserves_versioned_record(self):
        with tempfile.TemporaryDirectory() as directory:
            jsonl_source = Path(directory) / "candidate.jsonl"
            csv_path = Path(directory) / "review.csv"
            jsonl_target = Path(directory) / "reviewed.jsonl"
            record = self._intent_record("round-trip", "train")
            jsonl_source.write_text(json.dumps(record) + "\n", encoding="utf-8")

            export_review_csv(jsonl_source, csv_path)
            self.assertNotIn(b"\r\n", csv_path.read_bytes())
            import_review_csv(csv_path, jsonl_target)

            self.assertEqual(record, {key: value for key, value in load_jsonl(jsonl_target)[0].items() if key != "_line"})

    @staticmethod
    def _intent_record(identifier: str, split: str):
        return {
            "id": identifier,
            "task": "intent",
            "label": "coder",
            "split": split,
            "input": {"message": "write a parser"},
            "source": {"kind": "repository_test_contract", "ref": "ExampleTest:1", "license": "Apache-2.0"},
            "review": {"status": "pending", "reviewer": "", "reviewed_at": "", "notes": ""},
        }


if __name__ == "__main__":
    unittest.main()

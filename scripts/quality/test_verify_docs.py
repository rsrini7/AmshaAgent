#!/usr/bin/env python3
"""Contract tests for verify_docs.py ADR metadata validation."""

from __future__ import annotations

import os
import sys
import unittest
from pathlib import Path

_SCRIPTS_DIR = Path(__file__).resolve().parents[1]
_QUALITY_DIR = _SCRIPTS_DIR / "quality"
if str(_QUALITY_DIR) not in sys.path:
    sys.path.insert(0, str(_QUALITY_DIR))

from verify_docs import check_adr_metadata, VALID_ADR_STATUS_PREFIXES


class VerifyDocsAdrMetadataTest(unittest.TestCase):
    def setUp(self) -> None:
        self.workspace = "/dummy/workspace"

    def test_valid_statuses(self) -> None:
        for status in [
            "Accepted",
            "Implemented",
            "Proposed",
            "Approved",
            "Deferred",
            "Rejected",
            "Closed",
            "Accepted for Phases 0–2 shadow implementation; Proposed for Phase 3 activation",
            "Proposed baseline",
            "Approved for Implementation",
        ]:
            lines = [
                "# ADR-Test: Example Decision",
                "",
                f"- **Status**: {status}",
                "- Date: 2026-09-13",
            ]
            errors = check_adr_metadata(
                os.path.join(self.workspace, "docs", "adr", "test", "ADR-Test.md"),
                self.workspace,
                lines,
            )
            self.assertEqual(errors, [], f"Expected status '{status}' to be valid, got: {errors}")

    def test_missing_title(self) -> None:
        lines = [
            "Some preamble without H1",
            "",
            "- **Status**: Accepted",
        ]
        errors = check_adr_metadata(
            os.path.join(self.workspace, "docs", "adr", "test", "ADR-Test.md"),
            self.workspace,
            lines,
        )
        self.assertTrue(any("level-1 heading" in e for e in errors), f"Expected heading error: {errors}")

    def test_missing_status(self) -> None:
        lines = [
            "# ADR-Test: Example Decision",
            "",
            "## Status",
            "Accepted",
        ]
        errors = check_adr_metadata(
            os.path.join(self.workspace, "docs", "adr", "test", "ADR-Test.md"),
            self.workspace,
            lines,
        )
        self.assertTrue(any("missing required '- **Status**:'" in e for e in errors), f"Expected status error: {errors}")

    def test_invalid_status_prefix(self) -> None:
        lines = [
            "# ADR-Test: Example Decision",
            "",
            "- **Status**: UnrecognizedKeyword",
        ]
        errors = check_adr_metadata(
            os.path.join(self.workspace, "docs", "adr", "test", "ADR-Test.md"),
            self.workspace,
            lines,
        )
        self.assertTrue(any("invalid status" in e for e in errors), f"Expected invalid status error: {errors}")

    def test_non_adr_file_ignored(self) -> None:
        lines = [
            "Just random text without status or H1",
        ]
        errors = check_adr_metadata(
            os.path.join(self.workspace, "docs", "research", "test.md"),
            self.workspace,
            lines,
        )
        self.assertEqual(errors, [])


if __name__ == "__main__":
    unittest.main()

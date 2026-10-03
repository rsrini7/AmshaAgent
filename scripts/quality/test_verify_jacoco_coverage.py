#!/usr/bin/env python3
"""Contract tests for verify_jacoco_coverage.py (reactor-wide coverage gate)."""

from __future__ import annotations

import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

_QUALITY_DIR = Path(__file__).resolve().parent
if str(_QUALITY_DIR) not in sys.path:
    sys.path.insert(0, str(_QUALITY_DIR))

from verify_jacoco_coverage import (
    TestTotals,
    executed_test_count,
    has_production_classes,
    line_counts,
    test_report_totals,
)


def _write_suite(path: Path, tests: int, skipped: int = 0, failures: int = 0, errors: int = 0) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    suite = ET.Element(
        "testsuite",
        name="com.example.Foo",
        tests=str(tests),
        failures=str(failures),
        errors=str(errors),
        skipped=str(skipped),
    )
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


class TestTotalsTest(unittest.TestCase):
    """The badge reads `passed`, so it must never be able to disagree with the raw counts."""

    def test_passed_subtracts_skipped_as_well_as_failures(self) -> None:
        totals = TestTotals(total=10, failures=2, errors=1, skipped=3)
        self.assertEqual(totals.passed, 4)

    def test_passed_is_derived_not_stored(self) -> None:
        # Frozen dataclass: a caller cannot hand in a `passed` that contradicts the counts.
        with self.assertRaises(TypeError):
            TestTotals(total=10, failures=1, errors=0, skipped=1, passed=99)  # type: ignore[call-arg]

    def test_reports_are_the_single_source_for_every_count(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            module = Path(d)
            _write_suite(module / "target" / "surefire-reports" / "TEST-A.xml", 10, skipped=3)
            _write_suite(module / "target" / "failsafe-reports" / "TEST-B.xml", 5, skipped=1, failures=2)
            totals = test_report_totals(module)
        self.assertEqual(totals, TestTotals(total=15, failures=2, errors=0, skipped=4))
        self.assertEqual(totals.passed, 9)

    def test_executed_test_count_still_reports_the_total(self) -> None:
        # The gate's existing contract is the total, so moving it onto the shared reader must not
        # change what it returns.
        with tempfile.TemporaryDirectory() as d:
            module = Path(d)
            _write_suite(module / "target" / "surefire-reports" / "TEST-A.xml", 4, skipped=1)
            self.assertEqual(executed_test_count(module), 4)


def _write_csv(path: Path, rows: list[tuple[int, int]]) -> None:
    """Write a minimal jacoco.csv; ``rows`` is a list of (LINE_MISSED, LINE_COVERED)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    header = (
        "PACKAGE,CLASS,LINE_MISSED,LINE_COVERED,BRANCH_MISSED,BRANCH_COVERED,"
        "COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED"
    )
    body = "\n".join(f"com/example,Cls{i},{m},{c},0,0,0,0,0,0" for i, (m, c) in enumerate(rows))
    path.write_text(f"{header}\n{body}\n", encoding="utf-8")


class ExecutedTestCountTest(unittest.TestCase):
    def test_counts_surefire_reports(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            module = Path(d)
            _write_suite(module / "target" / "surefire-reports" / "TEST-A.xml", 3)
            _write_suite(module / "target" / "surefire-reports" / "TEST-B.xml", 4)
            count = executed_test_count(module)
        self.assertEqual(count, 7)

    def test_counts_failsafe_reports(self) -> None:
        # Regression guard: Failsafe owns the `*IT` classes and is inherited by every reactor
        # module. Counting only Surefire made an integration-test-only module look untested, which
        # silently exempted it from the --strict-instrumentation check.
        with tempfile.TemporaryDirectory() as d:
            module = Path(d)
            _write_suite(module / "target" / "failsafe-reports" / "TEST-FooIT.xml", 5)
            count = executed_test_count(module)
        self.assertEqual(count, 5)

    def test_sums_surefire_and_failsafe(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            module = Path(d)
            _write_suite(module / "target" / "surefire-reports" / "TEST-A.xml", 3)
            _write_suite(module / "target" / "failsafe-reports" / "TEST-FooIT.xml", 5)
            count = executed_test_count(module)
        self.assertEqual(count, 8)

    def test_no_report_directories_returns_zero(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            count = executed_test_count(Path(d))
        self.assertEqual(count, 0)

    def test_empty_report_directories_return_zero(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            module = Path(d)
            (module / "target" / "surefire-reports").mkdir(parents=True)
            (module / "target" / "failsafe-reports").mkdir(parents=True)
            count = executed_test_count(module)
        self.assertEqual(count, 0)

    def test_malformed_report_is_skipped_not_fatal(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            module = Path(d)
            _write_suite(module / "target" / "surefire-reports" / "TEST-A.xml", 3)
            broken = module / "target" / "failsafe-reports" / "TEST-BrokenIT.xml"
            broken.parent.mkdir(parents=True, exist_ok=True)
            broken.write_text("<testsuite", encoding="utf-8")
            count = executed_test_count(module)
        self.assertEqual(count, 3)

    def test_non_report_files_are_ignored(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            module = Path(d)
            _write_suite(module / "target" / "surefire-reports" / "TEST-A.xml", 2)
            # Failsafe also writes summary files into the same directory; only TEST-*.xml count.
            summary = module / "target" / "failsafe-reports" / "failsafe-summary.xml"
            summary.parent.mkdir(parents=True, exist_ok=True)
            summary.write_text("<failsafe-summary/>", encoding="utf-8")
            count = executed_test_count(module)
        self.assertEqual(count, 2)


class LineCountsTest(unittest.TestCase):
    def test_line_counts_sums_every_row(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            csv = Path(d) / "jacoco.csv"
            _write_csv(csv, [(8, 0), (0, 9), (1, 4)])
            missed, covered = line_counts(csv)
        self.assertEqual((missed, covered), (9, 13))


class HasProductionClassesTest(unittest.TestCase):
    def test_true_when_classes_exist(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            module = Path(d)
            cls = module / "target" / "classes" / "com" / "example" / "Foo.class"
            cls.parent.mkdir(parents=True, exist_ok=True)
            cls.write_bytes(b"\xca\xfe\xba\xbe")
            self.assertTrue(has_production_classes(module))

    def test_false_when_absent(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            self.assertFalse(has_production_classes(Path(d)))


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Contract tests for analyze_jacoco_coverage.py (coverage-gap analyzer and badge emitter)."""

from __future__ import annotations

import contextlib
import io
import json
import re
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest import mock

_QUALITY_DIR = Path(__file__).resolve().parent
_SCRIPTS_DIR = _QUALITY_DIR.parent
for _path in (_QUALITY_DIR, _SCRIPTS_DIR / "lib"):
    if str(_path) not in sys.path:
        sys.path.insert(0, str(_path))

import repo_root

from analyze_jacoco_coverage import (
    CoverageGap,
    badge_color,
    build_gate,
    check_badge_preconditions,
    main,
    module_summaries,
    recorded_test_total,
    sync_readme,
    tests_badge_color,
    tests_badge_message,
)
from verify_jacoco_coverage import TestTotals

REPO_ROOT = _SCRIPTS_DIR.parent

_POM_TEMPLATE = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <properties>{properties}</properties>
  <modules>{modules}</modules>
</project>
"""

_CSV_HEADER = (
    "PACKAGE,CLASS,LINE_MISSED,LINE_COVERED,BRANCH_MISSED,BRANCH_COVERED,"
    "COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED"
)


def _write_pom(root: Path, gate: str | None, modules: tuple[str, ...] = ("mod",)) -> None:
    """Write a minimal root pom; ``gate=None`` omits the coverage property entirely."""
    root.mkdir(parents=True, exist_ok=True)
    properties = "" if gate is None else f"<jacoco.minimum.line.coverage>{gate}</jacoco.minimum.line.coverage>"
    declared = "".join(f"<module>{name}</module>" for name in modules)
    (root / "pom.xml").write_text(
        _POM_TEMPLATE.format(properties=properties, modules=declared), encoding="utf-8"
    )


def _write_report(
    root: Path,
    covered: int,
    missed: int,
    *,
    module: str = "mod",
    covered_branches: int = 0,
    missed_branches: int = 0,
) -> None:
    """Write a one-class jacoco.csv for ``module`` at the path read_gaps expects."""
    report = root / module / "target" / "site" / "jacoco" / "jacoco.csv"
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(
        f"{_CSV_HEADER}\ncom/example,Cls,{missed},{covered},"
        f"{missed_branches},{covered_branches},0,0,0,0\n",
        encoding="utf-8",
    )


@contextlib.contextmanager
def _analyzing(root: Path):
    """Point the analyzer at ``root`` instead of the real checkout.

    main() resolves the root through ``repo_root.get_repo_root``, so patching that attribute is
    enough to redirect a full main() run at a synthetic reactor.
    """
    original = repo_root.get_repo_root
    repo_root.get_repo_root = lambda _file: root
    try:
        yield
    finally:
        repo_root.get_repo_root = original


def _run_main(*argv: str) -> tuple[int, str]:
    stdout = io.StringIO()
    with mock.patch.object(sys, "argv", ["analyze_jacoco_coverage.py", *argv]):
        with contextlib.redirect_stdout(stdout):
            code = main()
    return code, stdout.getvalue().strip()


class BuildGateTest(unittest.TestCase):
    def test_reads_the_gate_from_the_root_pom(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            _write_pom(Path(d), "0.82")
            self.assertEqual(build_gate(Path(d)), 0.82)

    def test_accepts_an_integer_gate(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            _write_pom(Path(d), "1")
            self.assertEqual(build_gate(Path(d)), 1.0)

    def test_missing_gate_property_is_an_error(self) -> None:
        # A missing property must fail loudly rather than fall back to a constant: a stale fallback
        # is exactly how the badge drifted below the gate the build actually enforces.
        with tempfile.TemporaryDirectory() as d:
            _write_pom(Path(d), None)
            with self.assertRaises(ValueError):
                build_gate(Path(d))

    def test_blank_gate_property_is_an_error(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            _write_pom(Path(d), "   ")
            with self.assertRaises(ValueError):
                build_gate(Path(d))

    def test_real_repo_gate_is_a_sane_fraction(self) -> None:
        gate = build_gate(REPO_ROOT)
        self.assertGreater(gate, 0.0)
        self.assertLessEqual(gate, 1.0)


class BadgeColorTest(unittest.TestCase):
    GATE = 0.82

    def test_at_or_above_the_gate_is_brightgreen(self) -> None:
        self.assertEqual(badge_color(self.GATE, self.GATE), "brightgreen")
        self.assertEqual(badge_color(self.GATE + 0.02, self.GATE), "brightgreen")

    def test_below_the_gate_is_never_brightgreen(self) -> None:
        for pct in (self.GATE - 0.001, self.GATE - 0.01, self.GATE - 0.05, self.GATE - 0.08):
            with self.subTest(pct=pct):
                self.assertNotEqual(badge_color(pct, self.GATE), "brightgreen")

    def test_mid_band_is_yellow(self) -> None:
        self.assertEqual(badge_color(0.60, self.GATE), "yellow")

    def test_low_coverage_is_red(self) -> None:
        self.assertEqual(badge_color(0.40, self.GATE), "red")

    def test_real_gate_never_colours_coverage_just_below_it_as_green(self) -> None:
        # Ties the invariant to the pom rather than to a literal, so lowering the gate in the pom
        # cannot leave the band logic asserting a threshold nobody enforces.
        gate = build_gate(REPO_ROOT)
        for delta in (0.001, 0.01, 0.05, 0.08):
            with self.subTest(delta=delta):
                self.assertNotEqual(badge_color(gate - delta, gate), "brightgreen")


class BadgeOutputTest(unittest.TestCase):
    """End-to-end: the emitted badge must use the gate from the pom, not a hardcoded constant."""

    def test_badge_is_green_when_coverage_clears_the_gate(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            _write_pom(root, "0.80")
            _write_report(root, covered=85, missed=15)
            with _analyzing(root):
                code, output = _run_main("--badge")
        self.assertEqual(code, 0)
        self.assertIn("Coverage-85.00%25-brightgreen", output)

    def test_badge_is_not_green_below_the_gate(self) -> None:
        # The exact regression this file exists for: 78% clears the old hardcoded 0.74 but not the
        # real 0.82 gate, so a "green" badge here would have advertised a failing build.
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            _write_pom(root, "0.82")
            _write_report(root, covered=78, missed=22)
            with _analyzing(root):
                code, output = _run_main("--badge")
        self.assertEqual(code, 0)
        self.assertIn("Coverage-78.00%25-yellow", output)
        self.assertNotIn("brightgreen", output)

    def test_summary_reports_the_reactor_totals(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            _write_pom(root, "0.82")
            _write_report(root, covered=52507, missed=9872)
            with _analyzing(root):
                code, output = _run_main("--summary")
        self.assertEqual(code, 0)
        self.assertEqual(output, "Reactor coverage: 84.17% (52,507/62,379 lines)")

    def test_missing_gate_property_fails_the_badge(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            _write_pom(root, None)
            _write_report(root, covered=85, missed=15)
            with _analyzing(root):
                code, _output = _run_main("--badge")
        self.assertEqual(code, 1)


class ModuleSummaryTest(unittest.TestCase):
    """Per-module aggregation: the view that shows one module subsidising another."""

    def test_aggregates_classes_into_module_totals(self) -> None:
        gaps = [
            CoverageGap("core", "p", "A", missed_lines=10, covered_lines=90, missed_branches=1, covered_branches=9),
            CoverageGap("core", "p", "B", missed_lines=20, covered_lines=80, missed_branches=2, covered_branches=8),
            CoverageGap("app", "p", "C", missed_lines=50, covered_lines=50, missed_branches=5, covered_branches=5),
        ]
        summaries = module_summaries(gaps)
        self.assertEqual([item.module for item in summaries], ["app", "core"])
        core = summaries[1]
        self.assertEqual((core.covered_lines, core.total_lines, core.missed_lines), (170, 200, 30))
        self.assertAlmostEqual(core.line_coverage, 0.85)
        self.assertAlmostEqual(core.branch_coverage, 17 / 20)

    def test_weakest_line_coverage_sorts_first(self) -> None:
        gaps = [
            CoverageGap("strong", "p", "A", missed_lines=1, covered_lines=99),
            CoverageGap("weak", "p", "B", missed_lines=90, covered_lines=10),
            CoverageGap("middle", "p", "C", missed_lines=50, covered_lines=50),
        ]
        self.assertEqual([item.module for item in module_summaries(gaps)], ["weak", "middle", "strong"])

    def test_module_with_no_instrumented_lines_is_zero_not_complete(self) -> None:
        # The trap: CoverageGap.coverage returns 1.0 for a zero-line class. A module that was never
        # measured must not read as fully covered, or the module most in need sorts to the bottom.
        summaries = module_summaries([CoverageGap("unmeasured", "p", "A", missed_lines=0, covered_lines=0)])
        self.assertEqual(summaries[0].line_coverage, 0.0)

    def test_branch_coverage_is_undefined_without_branch_instructions(self) -> None:
        # persistence-common has a single branch-free class, so the ratio is undefined rather than
        # 0% or 100%. Returning None is what keeps the report honest.
        summaries = module_summaries([CoverageGap("mod", "p", "A", missed_lines=1, covered_lines=9)])
        self.assertIsNone(summaries[0].branch_coverage)


class ModuleReportOutputTest(unittest.TestCase):
    """End-to-end: --modules must report per-module totals, not only the reactor number."""

    def _two_module_reactor(self, root: Path) -> None:
        _write_pom(root, "0.82", modules=("core", "app"))
        _write_report(root, covered=170, missed=30, module="core", covered_branches=17, missed_branches=3)
        _write_report(root, covered=50, missed=50, module="app", covered_branches=5, missed_branches=5)

    def test_reports_every_module_weakest_first_plus_a_total(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            self._two_module_reactor(root)
            with _analyzing(root):
                code, output = _run_main("--modules")
        self.assertEqual(code, 0)
        lines = output.splitlines()
        self.assertTrue(lines[0].startswith("MODULE"), lines[0])
        self.assertTrue(lines[1].startswith("app"), lines[1])
        self.assertIn("50.00%", lines[1])
        self.assertTrue(lines[2].startswith("core"), lines[2])
        self.assertIn("85.00%", lines[2])
        self.assertTrue(lines[3].startswith("TOTAL"), lines[3])
        # 220 / 300 lines and 22 / 30 branches
        self.assertIn("73.33%", lines[3])
        self.assertIn("220", lines[3])

    def test_filtering_to_one_module_drops_the_total_row(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            self._two_module_reactor(root)
            with _analyzing(root):
                code, output = _run_main("--modules", "--module", "core")
        self.assertEqual(code, 0)
        self.assertIn("core", output)
        self.assertNotIn("app", output)
        # The header legitimately has a TOTAL *column*; it is the TOTAL *row* that must be absent.
        self.assertFalse([line for line in output.splitlines() if line.startswith("TOTAL")], output)

    def test_an_unknown_module_filter_fails(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            self._two_module_reactor(root)
            with _analyzing(root):
                code, _output = _run_main("--modules", "--module", "nope")
        self.assertEqual(code, 1)

    def test_modules_json_carries_per_module_line_and_branch_coverage(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            _write_pom(root, "0.82", modules=("core", "branchless"))
            _write_report(root, covered=170, missed=30, module="core", covered_branches=17, missed_branches=3)
            _write_report(root, covered=9, missed=1, module="branchless")
            with _analyzing(root):
                code, output = _run_main("--modules", "--json")
        self.assertEqual(code, 0)
        payload = json.loads(output)
        by_module = {item["module"]: item for item in payload["modules"]}
        self.assertAlmostEqual(by_module["core"]["line_coverage"], 0.85)
        self.assertAlmostEqual(by_module["core"]["branch_coverage"], 0.85)
        self.assertEqual(by_module["core"]["missed_lines"], 30)
        self.assertIsNone(by_module["branchless"]["branch_coverage"])
        self.assertAlmostEqual(payload["coverage"], 179 / 210)

    def test_class_ranking_json_also_carries_module_totals(self) -> None:
        # --json is the machine-readable entry point used by the coverage docs, so the per-module
        # view must be reachable without a second invocation.
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            self._two_module_reactor(root)
            with _analyzing(root):
                code, output = _run_main("--json")
        self.assertEqual(code, 0)
        payload = json.loads(output)
        self.assertEqual(sorted(item["module"] for item in payload["modules"]), ["app", "core"])
        self.assertAlmostEqual(payload["coverage_branches"], 22 / 30)


def _write_suite(root: Path, module: str, directory: str, tests: int, skipped: int = 0, failures: int = 0) -> None:
    """Write a Surefire/Failsafe report for ``module`` at the path test_report_totals reads."""
    report = root / module / "target" / directory / "TEST-Suite.xml"
    report.parent.mkdir(parents=True, exist_ok=True)
    suite = ET.Element(
        "testsuite",
        name="com.example.Foo",
        tests=str(tests),
        failures=str(failures),
        errors="0",
        skipped=str(skipped),
    )
    ET.ElementTree(suite).write(report, encoding="utf-8", xml_declaration=True)


_README_TEMPLATE = """# Title

  ![Java Tests](https://img.shields.io/badge/Tests-1_passed_of_1-brightgreen.svg?style=flat)
  ![Java Coverage](https://img.shields.io/badge/Coverage-0.00%25-red.svg?style=flat)
  ![UI Tests](https://img.shields.io/badge/UI_Tests-195_passing-brightgreen.svg?style=flat)
  ![UI Coverage](https://img.shields.io/badge/UI_Coverage-59.26%25-yellow.svg?style=flat)
"""


class TestBadgeMessageTest(unittest.TestCase):
    """Every count must be named: a bare number is ambiguous between total and passed."""

    def test_clean_run_names_passed_and_total(self) -> None:
        self.assertEqual(
            tests_badge_message(TestTotals(total=9463, skipped=9)),
            "9454_passed_%C2%B7_9_skipped_of_9463",
        )

    def test_zero_counts_are_omitted_rather_than_padded(self) -> None:
        self.assertEqual(tests_badge_message(TestTotals(total=10)), "10_passed_of_10")

    def test_failures_are_named_even_alongside_skips(self) -> None:
        message = tests_badge_message(TestTotals(total=10, failures=2, skipped=1))
        self.assertEqual(message, "7_passed_%C2%B7_1_skipped_%C2%B7_2_failed_of_10")

    def test_errors_count_as_failures(self) -> None:
        self.assertIn("3_failed", tests_badge_message(TestTotals(total=5, failures=1, errors=2)))

    def test_no_literal_spaces_survive_into_the_url(self) -> None:
        # A space inside a shields.io path segment renders as a gap, not a separator. This is the
        # bug that shipped in the first draft of this emitter.
        message = tests_badge_message(TestTotals(total=9463, skipped=9, failures=1))
        self.assertNotIn(" ", message)

    def test_colour_is_green_only_without_failures_or_errors(self) -> None:
        self.assertEqual(tests_badge_color(TestTotals(total=5, skipped=5)), "brightgreen")
        self.assertEqual(tests_badge_color(TestTotals(total=5, failures=1)), "red")
        self.assertEqual(tests_badge_color(TestTotals(total=5, errors=1)), "red")


class SyncReadmeTest(unittest.TestCase):
    def _readme(self, root: Path) -> Path:
        path = root / "README.md"
        path.write_text(_README_TEMPLATE, encoding="utf-8")
        return path

    def test_rewrites_both_java_badges(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            readme = self._readme(Path(d))
            sync_readme(readme, TestTotals(total=9463, skipped=9), 0.8694, 0.82)
            text = readme.read_text(encoding="utf-8")
        self.assertIn("Tests-9454_passed_%C2%B7_9_skipped_of_9463-brightgreen", text)
        self.assertIn("Coverage-86.94%25-brightgreen", text)

    def test_leaves_the_ui_badges_untouched(self) -> None:
        # The UI badges are another script's output; rewriting them here would fight
        # analyze_ui_coverage.py and silently take ownership of numbers it owns.
        with tempfile.TemporaryDirectory() as d:
            readme = self._readme(Path(d))
            sync_readme(readme, TestTotals(total=10), 0.5, 0.82)
            text = readme.read_text(encoding="utf-8")
        self.assertIn("UI_Tests-195_passing-brightgreen", text)
        self.assertIn("UI_Coverage-59.26%25-yellow", text)

    def test_is_idempotent(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            readme = self._readme(Path(d))
            sync_readme(readme, TestTotals(total=9463, skipped=9), 0.8694, 0.82)
            once = readme.read_text(encoding="utf-8")
            sync_readme(readme, TestTotals(total=9463, skipped=9), 0.8694, 0.82)
            twice = readme.read_text(encoding="utf-8")
        self.assertEqual(twice, once)

    def test_missing_badge_is_an_error_not_a_silent_no_op(self) -> None:
        # The whole point of the flag. Reporting success while leaving the number stale is worse
        # than not having the flag at all.
        with tempfile.TemporaryDirectory() as d:
            readme = Path(d) / "README.md"
            readme.write_text("# Title\n\nNo badges here.\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                sync_readme(readme, TestTotals(total=1), 0.5, 0.82)

    def test_absent_readme_is_an_error(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(FileNotFoundError):
                sync_readme(Path(d) / "README.md", TestTotals(total=1), 0.5, 0.82)


class BadgeTestsEndToEndTest(unittest.TestCase):
    """--badge-tests and --sync-readme through main(), against a synthetic reactor."""

    def _reactor(self, root: Path) -> None:
        _write_pom(root, "0.82")
        _write_report(root, covered=85, missed=15)
        _write_suite(root, "mod", "surefire-reports", 10, skipped=1)
        _write_suite(root, "mod", "failsafe-reports", 2)

    def test_badge_tests_emits_the_full_count(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            self._reactor(root)
            with _analyzing(root):
                code, output = _run_main("--badge-tests")
        self.assertEqual(code, 0)
        self.assertIn("Tests-11_passed_%C2%B7_1_skipped_of_12-brightgreen", output)

    def test_sync_readme_updates_the_file(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            self._reactor(root)
            (root / "README.md").write_text(_README_TEMPLATE, encoding="utf-8")
            with _analyzing(root):
                code, _output = _run_main("--sync-readme")
            text = (root / "README.md").read_text(encoding="utf-8")
        self.assertEqual(code, 0)
        self.assertIn("Tests-11_passed_%C2%B7_1_skipped_of_12-brightgreen", text)
        self.assertIn("Coverage-85.00%25-brightgreen", text)

    def test_sync_readme_fails_when_the_badge_shape_changed(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            self._reactor(root)
            (root / "README.md").write_text("# Title\n", encoding="utf-8")
            with _analyzing(root):
                code, _output = _run_main("--sync-readme")
        self.assertEqual(code, 1)


class BadgePreconditionTest(unittest.TestCase):
    """The sync overwrites a committed number, so it must refuse a confidently smaller one."""

    def _reactor(self, root: Path, modules: tuple[str, ...] = ("core", "app")) -> None:
        _write_pom(root, "0.82", modules=modules)
        for module in modules:
            _write_report(root, covered=85, missed=15, module=module)
            (root / module / "src" / "test").mkdir(parents=True, exist_ok=True)

    def _readme(self, root: Path, total: int) -> Path:
        path = root / "README.md"
        path.write_text(
            "# Title\n\n"
            f"  ![Java Tests](https://img.shields.io/badge/Tests-1_passed_of_{total}-brightgreen.svg?style=flat)\n"
            "  ![Java Coverage](https://img.shields.io/badge/Coverage-0.00%25-red.svg?style=flat)\n",
            encoding="utf-8",
        )
        return path

    def test_refuses_when_a_module_reported_no_tests(self) -> None:
        # The partial-build case: `app` never ran, so the aggregate would be understated.
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            self._reactor(root)
            _write_suite(root, "core", "surefire-reports", 3625)
            readme = self._readme(root, 9463)
            with self.assertRaises(ValueError) as caught:
                check_badge_preconditions(root, readme, TestTotals(total=3625), False)
        self.assertIn("app", str(caught.exception))
        self.assertIn("partial build", str(caught.exception))

    def test_module_without_test_sources_is_not_a_failure(self) -> None:
        # `ui` is a Node project in the reactor; it has no src/test and must not trip the gate.
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            self._reactor(root, modules=("core",))
            _write_suite(root, "core", "surefire-reports", 10)
            readme = self._readme(root, 10)
            check_badge_preconditions(root, readme, TestTotals(total=10), False)

    def test_refuses_a_total_smaller_than_the_published_one(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            self._reactor(root)
            for module in ("core", "app"):
                _write_suite(root, module, "surefire-reports", 5)
            readme = self._readme(root, 9463)
            with self.assertRaises(ValueError) as caught:
                check_badge_preconditions(root, readme, TestTotals(total=10), False)
        self.assertIn("9463", str(caught.exception))
        self.assertIn("--allow-decrease", str(caught.exception))

    def test_allow_decrease_permits_an_intentional_shrink(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            self._reactor(root)
            for module in ("core", "app"):
                _write_suite(root, module, "surefire-reports", 5)
            readme = self._readme(root, 9463)
            check_badge_preconditions(root, readme, TestTotals(total=10), True)

    def test_allow_decrease_does_not_permit_a_partial_build(self) -> None:
        # The override is for a deliberate test removal, not for "the build only ran some modules".
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            self._reactor(root)
            _write_suite(root, "core", "surefire-reports", 3625)
            readme = self._readme(root, 9463)
            with self.assertRaises(ValueError):
                check_badge_preconditions(root, readme, TestTotals(total=3625), True)

    def test_refuses_an_empty_reactor(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            self._reactor(root, modules=())
            readme = self._readme(root, 9463)
            with self.assertRaises(ValueError):
                check_badge_preconditions(root, readme, TestTotals(), False)

    def test_a_growing_total_is_always_allowed(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            self._reactor(root)
            for module in ("core", "app"):
                _write_suite(root, module, "surefire-reports", 50)
            readme = self._readme(root, 10)
            check_badge_preconditions(root, readme, TestTotals(total=100), False)

    def test_an_unparseable_baseline_does_not_block_a_sync(self) -> None:
        # A brand-new or hand-written README has no parseable total; refusing would make the flag
        # unusable on first run, and the completeness check still guards the real hazard.
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            self._reactor(root)
            for module in ("core", "app"):
                _write_suite(root, module, "surefire-reports", 10)
            readme = root / "README.md"
            readme.write_text(_README_TEMPLATE, encoding="utf-8")
            check_badge_preconditions(root, readme, TestTotals(total=20), False)

    def test_recorded_total_is_scoped_to_the_java_tests_badge(self) -> None:
        # A `_of_N` in another badge must not be mistaken for the baseline.
        with tempfile.TemporaryDirectory() as d:
            readme = Path(d) / "README.md"
            readme.write_text(
                "  ![UI Coverage](https://img.shields.io/badge/UI_Coverage-59.26_of_9999-yellow.svg?style=flat)\n",
                encoding="utf-8",
            )
            self.assertIsNone(recorded_test_total(readme))


_BADGE_COUNTS_RE = re.compile(
    r"!\[Java Tests\]\(https://img\.shields\.io/badge/Tests-"
    r"(?P<passed>\d+)_passed"
    r"(?:_%C2%B7_(?P<skipped>\d+)_skipped)?"
    r"(?:_%C2%B7_(?P<failed>\d+)_failed)?"
    r"_of_(?P<total>\d+)-[^)]+\)"
)


def _badge_counts(text: str) -> tuple[int, int, int, int] | None:
    """Parse the README's Java Tests badge into (passed, skipped, failed, total).

    ``None`` when the badge is absent or does not match the emitted shape, so a caller can tell
    "no badge" apart from "a badge that contradicts itself".
    """
    match = _BADGE_COUNTS_RE.search(text)
    if match is None:
        return None
    return (
        int(match.group("passed")),
        int(match.group("skipped") or 0),
        int(match.group("failed") or 0),
        int(match.group("total")),
    )


class ShippedReadmeBadgeTest(unittest.TestCase):
    """The committed badge must be arithmetically possible.

    ``tests_badge_message`` derives ``passed`` as ``total - failures - errors - skipped``, so every
    badge this emitter writes satisfies ``passed + skipped + failed == total``. A badge that breaks
    that identity cannot have come from this emitter, and it is self-contradictory on its face: the
    one that reached the index on this branch read ``9683 passed · 7 skipped of 9672`` - 9690
    outcomes from 9672 tests. Nothing in the repository can produce it (``passed`` is a derived
    property, and ``total`` is read once), so it was written by something outside the current code
    and was caught only by eye. Reading the shipped file makes the impossible value uncommittable.
    """

    def test_shipped_readme_badge_is_arithmetically_consistent(self) -> None:
        readme = REPO_ROOT / "README.md"
        counts = _badge_counts(readme.read_text(encoding="utf-8"))
        self.assertIsNotNone(counts, "the Java Tests badge is missing or has an unrecognised shape")
        passed, skipped, failed, total = counts
        self.assertEqual(
            passed + skipped + failed,
            total,
            f"the shipped badge reads {passed} passed, {skipped} skipped, {failed} failed of "
            f"{total}; those counts must sum to the total",
        )

    def test_the_guard_rejects_the_badge_that_reached_the_index(self) -> None:
        # The exact staged string: 9683 + 7 + 0 != 9672. Asserted here so the guard above is known
        # to bite rather than assumed to.
        counts = _badge_counts(
            "  ![Java Tests](https://img.shields.io/badge/Tests-"
            "9683_passed_%C2%B7_7_skipped_of_9672-brightgreen.svg?style=flat)\n"
        )
        self.assertIsNotNone(counts)
        passed, skipped, failed, total = counts
        self.assertNotEqual(passed + skipped + failed, total)

    def test_a_correct_badge_satisfies_the_identity(self) -> None:
        counts = _badge_counts(
            "  ![Java Tests](https://img.shields.io/badge/Tests-"
            "9676_passed_%C2%B7_7_skipped_of_9683-brightgreen.svg?style=flat)\n"
        )
        self.assertEqual(counts, (9676, 7, 0, 9683))

    def test_a_badge_naming_failures_still_satisfies_the_identity(self) -> None:
        counts = _badge_counts(
            "  ![Java Tests](https://img.shields.io/badge/Tests-"
            "7_passed_%C2%B7_1_skipped_%C2%B7_2_failed_of_10-red.svg?style=flat)\n"
        )
        self.assertEqual(counts, (7, 1, 2, 10))


if __name__ == "__main__":
    unittest.main()

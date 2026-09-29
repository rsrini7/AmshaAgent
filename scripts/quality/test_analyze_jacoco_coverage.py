#!/usr/bin/env python3
"""Contract tests for analyze_jacoco_coverage.py (coverage-gap analyzer and badge emitter)."""

from __future__ import annotations

import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

_QUALITY_DIR = Path(__file__).resolve().parent
_SCRIPTS_DIR = _QUALITY_DIR.parent
for _path in (_QUALITY_DIR, _SCRIPTS_DIR / "lib"):
    if str(_path) not in sys.path:
        sys.path.insert(0, str(_path))

import repo_root

from analyze_jacoco_coverage import badge_color, build_gate, main

REPO_ROOT = _SCRIPTS_DIR.parent

_POM_TEMPLATE = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <properties>{properties}</properties>
  <modules><module>mod</module></modules>
</project>
"""

_CSV_HEADER = (
    "PACKAGE,CLASS,LINE_MISSED,LINE_COVERED,BRANCH_MISSED,BRANCH_COVERED,"
    "COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED"
)


def _write_pom(root: Path, gate: str | None) -> None:
    """Write a minimal root pom; ``gate=None`` omits the coverage property entirely."""
    root.mkdir(parents=True, exist_ok=True)
    properties = "" if gate is None else f"<jacoco.minimum.line.coverage>{gate}</jacoco.minimum.line.coverage>"
    (root / "pom.xml").write_text(_POM_TEMPLATE.format(properties=properties), encoding="utf-8")


def _write_report(root: Path, covered: int, missed: int) -> None:
    """Write a one-class jacoco.csv for module ``mod`` at the path read_gaps expects."""
    report = root / "mod" / "target" / "site" / "jacoco" / "jacoco.csv"
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(f"{_CSV_HEADER}\ncom/example,Cls,{missed},{covered},0,0,0,0,0,0\n", encoding="utf-8")


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


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Enforce reactor-wide line coverage from JaCoCo module reports."""

import argparse
import csv
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class TestTotals:
    """Tests reported by one module's Surefire and Failsafe XML.

    ``passed`` is derived rather than stored so it can never disagree with the four counts it is
    computed from. It subtracts skipped as well as failures and errors: a skipped test neither
    passed nor failed, and counting it as either is how a "passing" badge ends up reporting a
    number larger than the tests that actually ran.
    """

    total: int = 0
    failures: int = 0
    errors: int = 0
    skipped: int = 0

    @property
    def passed(self) -> int:
        return max(0, self.total - self.failures - self.errors - self.skipped)


def test_report_totals(module: Path) -> TestTotals:
    """Sum test counts from a module's Surefire and Failsafe reports.

    Distinguishes "this module has no tests" (fine) from "tests ran but the coverage
    agent never attached" (a silent measurement failure that must not be averaged in
    as 0% coverage).

    Both report directories must be read. Surefire runs the `*Test` classes and Failsafe
    runs the `*IT` classes, and Failsafe is inherited by every reactor module. Counting
    only Surefire would make a module whose only tests are integration tests look
    untested, silently exempting it from the strict-instrumentation check below.

    This is the single reader for those reports: `executed_test_count` and the README badge
    emitter both derive from it, so a badge and the gate can never read the same tree
    differently.
    """
    accumulated = [0, 0, 0, 0]
    for directory in ("surefire-reports", "failsafe-reports"):
        reports = module / "target" / directory
        if not reports.is_dir():
            continue
        for report in reports.glob("TEST-*.xml"):
            try:
                suite = ET.parse(report).getroot()
            except ET.ParseError:
                continue
            if suite.tag != "testsuite":
                continue
            accumulated[0] += int(suite.get("tests", 0) or 0)
            accumulated[1] += int(suite.get("failures", 0) or 0)
            accumulated[2] += int(suite.get("errors", 0) or 0)
            accumulated[3] += int(suite.get("skipped", 0) or 0)
    return TestTotals(*accumulated)


def module_has_test_sources(module: Path) -> bool:
    """Whether this module declares test sources at all.

    Used to tell "this module has no tests, which is fine" apart from "this module has tests
    but produced no report", which is what a partial `-pl` build looks like from here.
    """
    return (module / "src" / "test").is_dir()


def modules_missing_test_reports(root: Path) -> list[str]:
    """Reactor modules that declare test sources but reported no executed test.

    A `./mvnw -pl core test` run leaves every other module without reports. Aggregating anyway
    produces a confident, drastically smaller total - 3,625 instead of 9,463 on this reactor, a
    62% silent drop - so the caller must refuse rather than publish it.
    """
    missing: list[str] = []
    for module in reactor_modules(root):
        if module_has_test_sources(module) and test_report_totals(module).total == 0:
            missing.append(module.name)
    return missing


def executed_test_count(module: Path) -> int:
    """Count tests reported by Surefire and Failsafe."""
    return test_report_totals(module).total


def reactor_modules(root: Path) -> list[Path]:
    project = ET.parse(root / "pom.xml").getroot()
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    return [root / element.text for element in project.findall("./m:modules/m:module", namespace)]


def has_production_classes(module: Path) -> bool:
    classes = module / "target" / "classes"
    return classes.is_dir() and any(classes.rglob("*.class"))


def merge_quarkus_jacoco_data(root: Path) -> bool:
    """Merge Quarkus test execution data (jacoco-quarkus.exec) into jacoco.exec."""
    merged = False
    for quarkus_exec in root.glob("**/target/jacoco-quarkus.exec"):
        target_exec = quarkus_exec.parent / "jacoco.exec"
        if quarkus_exec.is_file() and quarkus_exec.stat().st_size > 0:
            with quarkus_exec.open("rb") as src:
                q_data = src.read()
            if q_data:
                existing_data = b""
                if target_exec.is_file():
                    with target_exec.open("rb") as src:
                        existing_data = src.read()
                if q_data not in existing_data:
                    with target_exec.open("ab") as dst:
                        dst.write(q_data)
                    merged = True
    return merged


def line_counts(report: Path) -> tuple[int, int]:
    missed = 0
    covered = 0
    with report.open(newline="", encoding="utf-8") as source:
        for row in csv.DictReader(source):
            missed += int(row["LINE_MISSED"])
            covered += int(row["LINE_COVERED"])
    return missed, covered


def executed_test_count(module: Path) -> int:
    """Count tests reported by Surefire and Failsafe.

    Distinguishes "this module has no tests" (fine) from "tests ran but the coverage
    agent never attached" (a silent measurement failure that must not be averaged in
    as 0% coverage).

    Both report directories must be read. Surefire runs the `*Test` classes and Failsafe
    runs the `*IT` classes, and Failsafe is inherited by every reactor module. Counting
    only Surefire would make a module whose only tests are integration tests look
    untested, silently exempting it from the strict-instrumentation check below.
    """
    total = 0
    for directory in ("surefire-reports", "failsafe-reports"):
        reports = module / "target" / directory
        if not reports.is_dir():
            continue
        for report in reports.glob("TEST-*.xml"):
            try:
                suite = ET.parse(report).getroot()
            except ET.ParseError:
                continue
            total += int(suite.get("tests", 0) or 0)
    return total


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--minimum", type=float, required=True)
    parser.add_argument(
        "--strict-instrumentation",
        action="store_true",
        help="Fail when a module with passing tests reports zero coverage (agent never attached).",
    )
    args = parser.parse_args()

    if not 0 <= args.minimum <= 1:
        parser.error("--minimum must be between 0 and 1")

    _lib_dir = Path(__file__).resolve().parents[1] / "lib"
    if str(_lib_dir) not in sys.path:
        sys.path.insert(0, str(_lib_dir))
    from repo_root import get_repo_root
    root = get_repo_root(__file__)

    # Auto-merge Quarkus test coverage execution data if present
    if merge_quarkus_jacoco_data(root):
        subprocess.run(["./mvnw", "jacoco:report", "-q"], cwd=root, check=False)

    missed = 0
    covered = 0
    missing = []
    uninstrumented = []

    for module in reactor_modules(root):
        report = module / "target" / "site" / "jacoco" / "jacoco.csv"
        if report.is_file():
            module_missed, module_covered = line_counts(report)
            missed += module_missed
            covered += module_covered
            if (
                module_covered == 0
                and module_missed > 0
                and has_production_classes(module)
                and executed_test_count(module) > 0
            ):
                uninstrumented.append(module.name)
        elif has_production_classes(module):
            missing.append(module.name)

    if missing:
        print(f"Missing JaCoCo reports for Java modules: {', '.join(missing)}", file=sys.stderr)
        return 1

    if uninstrumented:
        detail = (
            "JaCoCo recorded zero covered lines for modules whose tests did run, so the coverage "
            f"agent never attached: {', '.join(uninstrumented)}"
        )
        hint = (
            "These modules are counted as 0% covered, which understates the reactor figure. "
            "Verify the module's Surefire/Failsafe <argLine> composes with jacoco:prepare-agent "
            "(the parent uses late evaluation '@{argLine}')."
        )
        if args.strict_instrumentation:
            print(detail, file=sys.stderr)
            print(hint, file=sys.stderr)
            return 1
        print(f"WARNING: {detail}", file=sys.stderr)
        print(hint, file=sys.stderr)

    total = missed + covered
    if total == 0:
        print("No JaCoCo line coverage data found", file=sys.stderr)
        return 1

    if covered == 0:
        print("Tests were skipped (-DskipTests) - skipping coverage gate check.")
        return 0

    ratio = covered / total
    print(
        f"Reactor JaCoCo line coverage: {ratio:.2%} "
        f"(minimum: {args.minimum:.2%}, covered: {covered}, total: {total})"
    )
    if ratio < args.minimum:
        print("Reactor JaCoCo line coverage is below the required minimum", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

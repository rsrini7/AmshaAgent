#!/usr/bin/env python3
"""Enforce reactor-wide line coverage from JaCoCo module reports."""

import argparse
import csv
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


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
    """Count tests reported by Surefire.

    Distinguishes "this module has no tests" (fine) from "tests ran but the coverage
    agent never attached" (a silent measurement failure that must not be averaged in
    as 0% coverage).
    """
    reports = module / "target" / "surefire-reports"
    if not reports.is_dir():
        return 0
    total = 0
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
            "Verify the module's Surefire <argLine> composes with jacoco:prepare-agent "
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

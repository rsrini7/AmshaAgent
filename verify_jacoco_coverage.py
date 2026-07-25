#!/usr/bin/env python3
"""Enforce reactor-wide line coverage from JaCoCo module reports."""

import argparse
import csv
import glob
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
        if quarkus_exec.is_file():
            with quarkus_exec.open("rb") as src:
                data = src.read()
            if data:
                with target_exec.open("ab") as dst:
                    dst.write(data)
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


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--minimum", type=float, required=True)
    args = parser.parse_args()

    if not 0 <= args.minimum <= 1:
        parser.error("--minimum must be between 0 and 1")

    root = Path(__file__).resolve().parent

    # Auto-merge Quarkus test coverage execution data if present
    if merge_quarkus_jacoco_data(root):
        subprocess.run(["./mvnw", "jacoco:report", "-q"], cwd=root, check=False)

    missed = 0
    covered = 0
    missing = []

    for module in reactor_modules(root):
        report = module / "target" / "site" / "jacoco" / "jacoco.csv"
        if report.is_file():
            module_missed, module_covered = line_counts(report)
            missed += module_missed
            covered += module_covered
        elif has_production_classes(module):
            missing.append(module.name)

    if missing:
        print(f"Missing JaCoCo reports for Java modules: {', '.join(missing)}", file=sys.stderr)
        return 1

    total = missed + covered
    if total == 0:
        print("No JaCoCo line coverage data found", file=sys.stderr)
        return 1

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

#!/usr/bin/env python3
"""Rank JaCoCo coverage gaps and estimate the work needed for a target."""

import argparse
import csv
import json
import math
import sys
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass(frozen=True)
class CoverageGap:
    module: str
    package: str
    class_name: str
    missed_lines: int
    covered_lines: int

    @property
    def total_lines(self) -> int:
        return self.missed_lines + self.covered_lines

    @property
    def coverage(self) -> float:
        return self.covered_lines / self.total_lines if self.total_lines else 1.0


def reactor_modules(root: Path) -> list[Path]:
    project = ET.parse(root / "pom.xml").getroot()
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    return [root / item.text for item in project.findall("./m:modules/m:module", namespace)]


def read_gaps(root: Path) -> tuple[list[CoverageGap], list[str]]:
    gaps: list[CoverageGap] = []
    missing_reports: list[str] = []
    for module in reactor_modules(root):
        report = module / "target" / "site" / "jacoco" / "jacoco.csv"
        if not report.is_file():
            if (module / "src" / "main" / "java").is_dir():
                missing_reports.append(module.name)
            continue
        with report.open(newline="", encoding="utf-8") as source:
            for row in csv.DictReader(source):
                gaps.append(
                    CoverageGap(
                        module=module.name,
                        package=row["PACKAGE"].replace("/", "."),
                        class_name=row["CLASS"],
                        missed_lines=int(row["LINE_MISSED"]),
                        covered_lines=int(row["LINE_COVERED"]),
                    )
                )
    return gaps, missing_reports


def additional_lines(covered: int, total: int, target: float) -> int:
    """Return uncovered lines that must become covered, assuming total lines stay fixed."""
    return max(0, math.ceil(target * total - covered - 1e-12))


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Analyze reactor JaCoCo CSV reports and rank classes with uncovered lines."
    )
    parser.add_argument(
        "--target",
        type=float,
        default=0.72,
        help="desired reactor line coverage as a fraction (default: 0.72)",
    )
    parser.add_argument("--top", type=int, default=25, help="number of coverage gaps to show")
    parser.add_argument("--module", help="limit ranked gaps to one Maven module")
    parser.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    parser.add_argument("--badge", action="store_true", help="emit shields.io Markdown badge")
    parser.add_argument("--summary", action="store_true", help="emit single-line coverage summary")
    args = parser.parse_args()
    if not 0 <= args.target <= 1:
        parser.error("--target must be between 0 and 1")
    if args.top < 1:
        parser.error("--top must be at least 1")

    _lib_dir = Path(__file__).resolve().parents[1] / "lib"
    if str(_lib_dir) not in sys.path:
        sys.path.insert(0, str(_lib_dir))
    from repo_root import get_repo_root
    root = get_repo_root(__file__)
    gaps, missing_reports = read_gaps(root)
    if missing_reports:
        print(
            "Missing JaCoCo reports for Java modules: " + ", ".join(missing_reports),
            file=sys.stderr,
        )
        print("Run `mise run build:test` first.", file=sys.stderr)
        return 1
    if not gaps:
        print("No JaCoCo coverage data found; run `mise run build:test` first.", file=sys.stderr)
        return 1

    covered = sum(gap.covered_lines for gap in gaps)
    total = sum(gap.total_lines for gap in gaps)
    needed = additional_lines(covered, total, args.target)
    pct = covered / total if total else 0.0

    if args.badge:
        color = "brightgreen" if pct >= 0.70 else "yellow" if pct >= 0.50 else "red"
        print(f"![Coverage](https://img.shields.io/badge/Coverage-{pct * 100:.2f}%25-{color}.svg?style=flat)")
        return 0

    if args.summary:
        print(f"Reactor coverage: {pct:.2%} ({covered:,}/{total:,} lines)")
        return 0

    ranked = [gap for gap in gaps if gap.missed_lines and (not args.module or gap.module == args.module)]
    ranked.sort(key=lambda gap: (-gap.missed_lines, gap.coverage, gap.module, gap.class_name))
    selected = ranked[: args.top]

    if args.json:
        print(
            json.dumps(
                {
                    "covered_lines": covered,
                    "total_lines": total,
                    "coverage": pct,
                    "target": args.target,
                    "additional_covered_lines_needed": needed,
                    "gaps": [asdict(gap) | {"coverage": gap.coverage} for gap in selected],
                },
                indent=2,
            )
        )
        return 0

    print(f"Reactor coverage: {covered / total:.2%} ({covered:,}/{total:,} lines)")
    print(f"Target coverage:  {args.target:.2%}")
    print(f"Additional covered lines needed: {needed:,}")
    if args.module and not ranked:
        print(f"No uncovered classes found for module {args.module!r}.")
        return 0
    print()
    print(f"{'MISSED':>7} {'COVERED':>7} {'RATE':>7}  CLASS")
    for gap in selected:
        qualified_name = f"{gap.package}.{gap.class_name}" if gap.package else gap.class_name
        print(
            f"{gap.missed_lines:7d} {gap.covered_lines:7d} {gap.coverage:7.2%}  "
            f"{gap.module}:{qualified_name}"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())

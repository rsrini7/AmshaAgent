#!/usr/bin/env python3
"""Analyze UI Vitest V8 coverage and test counts, matching analyze_jacoco_coverage.py."""

import argparse
import json
import math
import sys
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass(frozen=True)
class UiCoverageGap:
    file_path: str
    missed_lines: int
    covered_lines: int
    total_lines: int
    pct: float


def read_junit(junit_file: Path) -> dict:
    if not junit_file.is_file():
        return {}
    try:
        tree = ET.parse(junit_file)
        root = tree.getroot()
        tests = int(root.attrib.get("tests", 0))
        failures = int(root.attrib.get("failures", 0))
        errors = int(root.attrib.get("errors", 0))
        skipped = int(root.attrib.get("skipped", 0))
        suites = len(root.findall(".//testsuite"))
        return {
            "total_tests": tests,
            "failures": failures,
            "errors": errors,
            "skipped": skipped,
            "test_files": suites,
        }
    except Exception:
        return {}


def read_coverage(summary_file: Path) -> tuple[dict, list[UiCoverageGap]]:
    if not summary_file.is_file():
        return {}, []

    with summary_file.open(encoding="utf-8") as f:
        data = json.load(f)

    total_info = data.get("total", {})
    gaps: list[UiCoverageGap] = []
    root_str = str(summary_file.parent.parent)

    for path_str, stats in data.items():
        if path_str == "total":
            continue
        rel_path = path_str.replace(root_str + "/", "").replace(root_str + "\\", "")
        lines = stats.get("lines", {})
        tot = lines.get("total", 0)
        cov = lines.get("covered", 0)
        missed = tot - cov
        pct = lines.get("pct", 0.0) / 100.0 if tot else 1.0
        gaps.append(
            UiCoverageGap(
                file_path=rel_path,
                missed_lines=missed,
                covered_lines=cov,
                total_lines=tot,
                pct=pct,
            )
        )

    return total_info, gaps


def additional_lines(covered: int, total: int, target: float) -> int:
    return max(0, math.ceil(target * total - covered - 1e-12))


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Analyze Vitest V8 coverage summary and rank files with uncovered lines."
    )
    parser.add_argument(
        "--target",
        type=float,
        default=0.50,
        help="desired UI line coverage as a fraction (default: 0.50)",
    )
    parser.add_argument("--top", type=int, default=20, help="number of coverage gaps to show")
    parser.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    parser.add_argument("--badge", action="store_true", help="emit shields.io Markdown badge for UI coverage")
    parser.add_argument("--badge-tests", action="store_true", help="emit shields.io Markdown badge for UI tests")
    parser.add_argument("--summary", action="store_true", help="emit single-line coverage summary")
    parser.add_argument(
        "--sync-readme",
        action="store_true",
        help="automatically sync UI tests and coverage badges in README.md",
    )
    args = parser.parse_args()

    root = Path(__file__).resolve().parent.parent
    summary_file = root / "ui" / "coverage" / "coverage-summary.json"
    junit_file = root / "ui" / "coverage" / "junit.xml"

    # Auto-refresh: If coverage-summary is missing or stale compared to junit.xml, refresh it automatically
    is_stale = (
        junit_file.is_file()
        and summary_file.is_file()
        and junit_file.stat().st_mtime > summary_file.stat().st_mtime + 2
    )
    if not summary_file.is_file() or is_stale:
        import subprocess

        try:
            subprocess.run(
                ["npm", "test"],
                cwd=str(root / "ui"),
                check=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        except Exception:
            pass

    total_info, gaps = read_coverage(summary_file)
    junit_info = read_junit(junit_file)

    if not total_info or not gaps:
        print(
            f"No UI coverage data found at {summary_file}. Run `npm run test:coverage` in `ui/` first.",
            file=sys.stderr,
        )
        return 1

    lines_total = total_info.get("lines", {})
    covered = lines_total.get("covered", 0)
    total = lines_total.get("total", 0)
    pct = (lines_total.get("pct", 0.0)) / 100.0 if total else 0.0
    needed = additional_lines(covered, total, args.target)

    test_count = junit_info.get("total_tests", 0)
    failures = junit_info.get("failures", 0)
    errors = junit_info.get("errors", 0)
    skipped = junit_info.get("skipped", 0)
    test_files = junit_info.get("test_files", 0)

    if args.sync_readme:
        readme_file = root / "README.md"
        if readme_file.is_file():
            import re

            status = f"{test_count}_passing" if (failures == 0 and errors == 0) else f"{failures}_failed"
            tests_color = "brightgreen" if (failures == 0 and errors == 0) else "red"
            cov_color = "brightgreen" if pct >= 0.70 else "yellow" if pct >= 0.30 else "red"

            tests_badge = f"![UI Tests](https://img.shields.io/badge/UI_Tests-{status}-{tests_color}.svg?style=flat)"
            cov_badge = f"![UI Coverage](https://img.shields.io/badge/UI_Coverage-{pct * 100:.2f}%25-{cov_color}.svg?style=flat)"

            text = readme_file.read_text(encoding="utf-8")
            text = re.sub(r"!\[UI Tests\]\(https://img\.shields\.io/badge/UI_Tests-[^\)]+\)", tests_badge, text)
            text = re.sub(r"!\[UI Coverage\]\(https://img\.shields\.io/badge/UI_Coverage-[^\)]+\)", cov_badge, text)
            readme_file.write_text(text, encoding="utf-8")

    if args.badge_tests:
        status = f"{test_count}_passing" if (failures == 0 and errors == 0) else f"{failures}_failed"
        color = "brightgreen" if (failures == 0 and errors == 0) else "red"
        print(f"![UI Tests](https://img.shields.io/badge/UI_Tests-{status}-{color}.svg?style=flat)")
        return 0

    if args.badge:
        color = "brightgreen" if pct >= 0.70 else "yellow" if pct >= 0.30 else "red"
        print(f"![UI Coverage](https://img.shields.io/badge/UI_Coverage-{pct * 100:.2f}%25-{color}.svg?style=flat)")
        return 0

    if args.summary:
        if test_count > 0:
            print(
                f"UI tests:    Total: {test_count} | Failures: {failures} | Errors: {errors} | Skipped: {skipped} ({test_files} files)"
            )
        print(f"UI coverage: {pct:.2%} ({covered:,}/{total:,} lines)")
        return 0

    ranked = [gap for gap in gaps if gap.missed_lines > 0]
    ranked.sort(key=lambda g: (-g.missed_lines, g.pct, g.file_path))
    selected = ranked[: args.top]

    if args.json:
        print(
            json.dumps(
                {
                    "tests": junit_info,
                    "covered_lines": covered,
                    "total_lines": total,
                    "coverage": pct,
                    "target": args.target,
                    "additional_covered_lines_needed": needed,
                    "gaps": [asdict(g) for g in selected],
                },
                indent=2,
            )
        )
        return 0

    if test_count > 0:
        print(
            f"UI tests:        Total: {test_count} | Failures: {failures} | Errors: {errors} | Skipped: {skipped} ({test_files} files)"
        )
    print(f"UI coverage:     {pct:.2%} ({covered:,}/{total:,} lines)")
    print(f"Target coverage: {args.target:.2%}")
    print(f"Additional covered lines needed: {needed:,}")
    print()
    print(f"{'MISSED':>7} {'COVERED':>7} {'RATE':>7}  FILE")
    for gap in selected:
        print(f"{gap.missed_lines:7d} {gap.covered_lines:7d} {gap.pct:7.2%}  {gap.file_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

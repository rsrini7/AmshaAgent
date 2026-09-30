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
    missed_branches: int = 0
    covered_branches: int = 0

    @property
    def total_lines(self) -> int:
        return self.missed_lines + self.covered_lines

    @property
    def total_branches(self) -> int:
        return self.missed_branches + self.covered_branches

    @property
    def coverage(self) -> float:
        return self.covered_lines / self.total_lines if self.total_lines else 1.0


@dataclass(frozen=True)
class ModuleCoverage:
    """Line and branch totals for one Maven module.

    Aggregated per module rather than per class because a per-module floor is the enforcement
    gap this report exists to expose: one highly covered module can otherwise subsidise a
    weakly covered one inside the reactor-wide number.
    """

    module: str
    covered_lines: int
    total_lines: int
    covered_branches: int
    total_branches: int

    @property
    def missed_lines(self) -> int:
        return self.total_lines - self.covered_lines

    @property
    def line_coverage(self) -> float:
        # A module with no instrumented lines is reported as 0%, never 100%: "nothing measured"
        # must not read as "nothing to fix".
        return self.covered_lines / self.total_lines if self.total_lines else 0.0

    @property
    def branch_coverage(self) -> float | None:
        """None when the module has no branch instructions, so the ratio is undefined."""
        return self.covered_branches / self.total_branches if self.total_branches else None


def reactor_modules(root: Path) -> list[Path]:
    project = ET.parse(root / "pom.xml").getroot()
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    return [root / item.text for item in project.findall("./m:modules/m:module", namespace)]


def build_gate(root: Path) -> float:
    """Read the reactor's JaCoCo line-coverage floor from the root pom.

    Read rather than hardcoded: the floor is a ratchet and has already moved once, and a stale
    constant here makes the badge colour disagree with what the build actually enforces.
    """
    project = ET.parse(root / "pom.xml").getroot()
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    node = project.find("./m:properties/m:jacoco.minimum.line.coverage", namespace)
    if node is None or not node.text or not node.text.strip():
        raise ValueError("jacoco.minimum.line.coverage is missing from the root pom properties")
    return float(node.text.strip())


def badge_color(pct: float, gate: float) -> str:
    """Pick the shields.io colour for a coverage percentage relative to the build gate.

    Extracted so the invariant that matters can be asserted directly: coverage below the gate must
    never render "brightgreen", because the build would reject it.
    """
    if pct >= gate:
        return "brightgreen"
    return "yellow" if pct >= 0.50 else "red"


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
                        # Branch columns are supplementary: tolerate a trimmed CSV rather than
                        # failing the whole analysis over the optional half of the report.
                        missed_branches=int(row.get("BRANCH_MISSED") or 0),
                        covered_branches=int(row.get("BRANCH_COVERED") or 0),
                    )
                )
    return gaps, missing_reports


def module_summaries(gaps: list[CoverageGap]) -> list[ModuleCoverage]:
    """Aggregate class gaps into per-module totals, weakest line coverage first.

    Ascending order matches how the roadmap in docs/TEST_COVERAGE_ANALYSIS.md reads, and keeps
    the module that most needs work at the top instead of burying it in alphabetical noise.
    """
    totals: dict[str, list[int]] = {}
    for gap in gaps:
        acc = totals.setdefault(gap.module, [0, 0, 0, 0])
        acc[0] += gap.covered_lines
        acc[1] += gap.total_lines
        acc[2] += gap.covered_branches
        acc[3] += gap.total_branches

    summaries = [
        ModuleCoverage(
            module=module,
            covered_lines=covered,
            total_lines=total,
            covered_branches=covered_branches,
            total_branches=total_branches,
        )
        for module, (covered, total, covered_branches, total_branches) in totals.items()
    ]
    summaries.sort(key=lambda item: (item.line_coverage, item.module))
    return summaries


def additional_lines(covered: int, total: int, target: float) -> int:
    """Return uncovered lines that must become covered, assuming total lines stay fixed."""
    return max(0, math.ceil(target * total - covered - 1e-12))


def missed_lines_by_gap(root: Path, selected: list[CoverageGap]) -> dict[CoverageGap, "ClassLines"]:
    """Parse each module's jacoco.xml once and attach missed line numbers to the gaps.

    Returns a mapping only for gaps whose module produced an XML report. The per-module
    XML is parsed at most once even when several selected gaps live in the same module.
    """
    module_by_name = {m.name: m for m in reactor_modules(root)}
    parsed: dict[Path, ET.Element] = {}
    out: dict[CoverageGap, "ClassLines"] = {}
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from jacoco_lines import find_class_lines_in_root

    for gap in selected:
        module = module_by_name.get(gap.module)
        if module is None:
            continue
        xml = module / "target" / "site" / "jacoco" / "jacoco.xml"
        if not xml.is_file():
            continue
        root_el = parsed.get(xml)
        if root_el is None:
            root_el = ET.parse(xml).getroot()
            parsed[xml] = root_el
        lines = find_class_lines_in_root(root_el, f"{gap.package}.{gap.class_name}", missed_only=True)
        if lines is not None:
            out[gap] = lines
    return out


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Analyze reactor JaCoCo CSV reports and rank classes with uncovered lines."
    )
    parser.add_argument(
        "--target",
        type=float,
        default=0.80,
        help=(
            "desired reactor line coverage as a fraction (default: 0.80, the goal recommended in "
            "docs/TEST_COVERAGE_ANALYSIS.md section 8). Must stay at or above the build gate "
            "(`jacoco.minimum.line.coverage` in the root pom) or the analyzer will report "
            "'target met' for a level the build rejects."
        ),
    )
    parser.add_argument("--top", type=int, default=25, help="number of coverage gaps to show")
    parser.add_argument("--module", help="limit ranked gaps to one Maven module")
    parser.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    parser.add_argument("--badge", action="store_true", help="emit shields.io Markdown badge")
    parser.add_argument("--summary", action="store_true", help="emit single-line coverage summary")
    parser.add_argument(
        "--modules",
        action="store_true",
        help=(
            "report per-module line and branch coverage totals instead of ranking classes. "
            "Weakest line coverage first, with a reactor TOTAL row. Combine with --module to "
            "summarise a single module. This is the view that shows whether one module is "
            "subsidising another inside the reactor-wide number."
        ),
    )
    parser.add_argument(
        "--lines",
        action="store_true",
        help=(
            "attach the actual missed JaCoCo line numbers to each reported gap by parsing the "
            "module's jacoco.xml. Makes the gap report directly actionable (you see which lines to "
            "target) instead of only a count. Slower because it reads XML per module; implied by --json."
        ),
    )
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
    branches_covered = sum(gap.covered_branches for gap in gaps)
    branches_total = sum(gap.total_branches for gap in gaps)
    needed = additional_lines(covered, total, args.target)
    pct = covered / total if total else 0.0

    if args.badge:
        # Colour bands track the build gate, read from the root pom rather than hardcoded: the badge
        # must not read "green" at a coverage level the build would reject.
        try:
            gate = build_gate(root)
        except ValueError as error:
            print(f"Cannot read the coverage gate from pom.xml: {error}", file=sys.stderr)
            return 1
        color = badge_color(pct, gate)
        print(f"![Coverage](https://img.shields.io/badge/Coverage-{pct * 100:.2f}%25-{color}.svg?style=flat)")
        return 0

    if args.summary:
        print(f"Reactor coverage: {pct:.2%} ({covered:,}/{total:,} lines)")
        return 0

    if args.modules:
        summaries = module_summaries(gaps)
        if args.module:
            summaries = [item for item in summaries if item.module == args.module]
            if not summaries:
                print(f"No coverage data found for module {args.module!r}.", file=sys.stderr)
                return 1
        if args.json:
            print(
                json.dumps(
                    {
                        "covered_lines": covered,
                        "total_lines": total,
                        "coverage": pct,
                        "covered_branches": branches_covered,
                        "total_branches": branches_total,
                        "coverage_branches": (
                            branches_covered / branches_total if branches_total else None
                        ),
                        "modules": [
                            asdict(item)
                            | {
                                "missed_lines": item.missed_lines,
                                "line_coverage": item.line_coverage,
                                "branch_coverage": item.branch_coverage,
                            }
                            for item in summaries
                        ],
                    },
                    indent=2,
                )
            )
            return 0

        reactor_branch = (
            f"{branches_covered / branches_total:.2%}" if branches_total else "—"
        )
        print(f"{'MODULE':<26} {'LINE%':>7} {'COVERED':>8} {'TOTAL':>8} {'MISSED':>7} {'BRANCH%':>8}")
        for item in summaries:
            branch = f"{item.branch_coverage:.2%}" if item.branch_coverage is not None else "—"
            print(
                f"{item.module:<26} {item.line_coverage:7.2%} {item.covered_lines:8,} "
                f"{item.total_lines:8,} {item.missed_lines:7,} {branch:>8}"
            )
        if not args.module:
            print(
                f"{'TOTAL':<26} {pct:7.2%} {covered:8,} {total:8,} {total - covered:7,} "
                f"{reactor_branch:>8}"
            )
        return 0

    ranked = [gap for gap in gaps if gap.missed_lines and (not args.module or gap.module == args.module)]
    ranked.sort(key=lambda gap: (-gap.missed_lines, gap.coverage, gap.module, gap.class_name))
    selected = ranked[: args.top]

    if args.json:
        # --json implies --lines: the machine-readable gap should carry the line numbers.
        line_map = missed_lines_by_gap(root, selected)
    elif args.lines:
        line_map = missed_lines_by_gap(root, selected)
    else:
        line_map = {}

    if args.json:
        print(
            json.dumps(
                {
                    "covered_lines": covered,
                    "total_lines": total,
                    "coverage": pct,
                    "target": args.target,
                    "additional_covered_lines_needed": needed,
                    "covered_branches": branches_covered,
                    "total_branches": branches_total,
                    "coverage_branches": (
                        branches_covered / branches_total if branches_total else None
                    ),
                    "modules": [
                        asdict(item)
                        | {
                            "missed_lines": item.missed_lines,
                            "line_coverage": item.line_coverage,
                            "branch_coverage": item.branch_coverage,
                        }
                        for item in module_summaries(gaps)
                    ],
                    "gaps": [
                        asdict(gap)
                        | {
                            "coverage": gap.coverage,
                            "source_file": (line_map[gap].source_name if gap in line_map else None),
                            "missed_line_numbers": (line_map[gap].missed if gap in line_map else None),
                        }
                        for gap in selected
                    ],
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
        if gap in line_map:
            ln = line_map[gap]
            print(f"        source: {ln.source_name}")
            print(f"        missed ({len(ln.missed)}): {ln.missed}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

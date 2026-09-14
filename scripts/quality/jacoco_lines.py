#!/usr/bin/env python3
"""Per-line JaCoCo coverage for a single class, from the XML report.

JaCoCo's XML puts line-level data under <sourcefile>/<line>; <class> elements
only carry method/counter rollups. So we resolve the class -> source file name
(``sourcefilename`` is a <class> attribute), then read that sibling <sourcefile>.

A line counts as *missed* in the sense the analyzer cares about when ``mi > 0``
and ``ci == 0``: a line with any covered instruction is reported as covered, so a
partial line is never "missed". That undercounts partially-executed lines, but is
exactly what you want when targeting paths that never ran at all.

Usage (explicit XML):
  jacoco_lines.py <module>/target/site/jacoco/jacoco.xml <fully.qualified.ClassName> [--missed-only]

Usage (auto-discovery across the reactor):
  jacoco_lines.py --root <repo-root> <fully.qualified.ClassName> [--missed-only]

The module is reusable: ``find_class_lines`` / ``find_class_lines_in_root`` are
imported by ``analyze_jacoco_coverage.py`` to attach missed line numbers to the
gap report.
"""

from __future__ import annotations

import csv
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class ClassLines:
    source_name: str
    missed: list[int]
    covered: list[int]
    siblings: list[str]

    @property
    def missed_only(self) -> bool:
        return not self.covered


def find_class_lines_in_root(root: ET.Element, fqcn: str, missed_only: bool = False) -> ClassLines | None:
    """Resolve ``fqcn`` within an already-parsed JaCoCo XML root element."""
    for pkg in root.iter("package"):
        classes = list(pkg.iter("class"))
        target = next(
            (c for c in classes if c.get("name", "").replace("/", ".") == fqcn),
            None,
        )
        if target is None:
            continue
        src_name = target.get("sourcefilename", "?")
        siblings = [
            c.get("name", "").replace("/", ".")
            for c in classes
            if c.get("sourcefilename") == src_name
        ]
        sf = next((s for s in pkg.iter("sourcefile") if s.get("name") == src_name), None)
        if sf is None:
            return ClassLines(src_name, [], [], siblings)
        missed: list[int] = []
        covered: list[int] = []
        for line in sf.iter("line"):
            nr = int(line.get("nr"))
            if int(line.get("mi")) > 0 and int(line.get("ci")) == 0:
                missed.append(nr)
            elif int(line.get("ci")) > 0:
                covered.append(nr)
        missed.sort()
        return ClassLines(src_name, missed, [] if missed_only else covered, siblings)
    return None


def find_class_lines(xml_path: Path, fqcn: str, missed_only: bool = False) -> ClassLines | None:
    """Resolve ``fqcn`` from a JaCoCo XML report file."""
    return find_class_lines_in_root(ET.parse(xml_path).getroot(), fqcn, missed_only)


def reactor_modules(root: Path) -> list[Path]:
    project = ET.parse(root / "pom.xml").getroot()
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    return [root / item.text for item in project.findall("./m:modules/m:module", namespace)]


def resolve_class_xml(root: Path, fqcn: str) -> Path | None:
    """Find the module JaCoCo XML that contains ``fqcn`` by scanning the CSV reports.

    The CSV carries the package + simple class name; once the owning module is known
    its ``jacoco.xml`` is the source of line numbers.
    """
    for module in reactor_modules(root):
        report = module / "target" / "site" / "jacoco" / "jacoco.csv"
        if not report.is_file():
            continue
        with report.open(newline="", encoding="utf-8") as source:
            for row in csv.DictReader(source):
                if f"{row['PACKAGE'].replace('/', '.')}.{row['CLASS']}" == fqcn:
                    return module / "target" / "site" / "jacoco" / "jacoco.xml"
    return None


def main(argv: list[str] | None = None) -> int:
    argv = sys.argv[1:] if argv is None else list(argv)

    missed_only = "--missed-only" in argv
    if missed_only:
        argv = [a for a in argv if a != "--missed-only"]

    root_mode = False
    if "--root" in argv:
        i = argv.index("--root")
        root_dir = Path(argv[i + 1])
        argv = argv[:i] + argv[i + 2 :]
        root_mode = True

    if root_mode:
        if len(argv) < 1:
            print(
                "usage: jacoco_lines.py --root <repo-root> <fully.qualified.ClassName> [--missed-only]",
                file=sys.stderr,
            )
            return 2
        fqcn = argv[0]
        xml_path = resolve_class_xml(root_dir, fqcn)
        if xml_path is None:
            print(f"!! class not found in reactor: {fqcn}", file=sys.stderr)
            return 1
    else:
        if len(argv) < 2:
            print(
                "usage: jacoco_lines.py <jacoco.xml> <fully.qualified.ClassName> [--missed-only]",
                file=sys.stderr,
            )
            return 2
        fqcn = argv[1]
        first = Path(argv[0])
        xml_path = first

    if not xml_path.is_file():
        print(f"!! jacoco xml not found: {xml_path}", file=sys.stderr)
        return 1

    result = find_class_lines(xml_path, fqcn, missed_only)
    if result is None:
        print(f"!! class not found: {fqcn}", file=sys.stderr)
        return 1

    print(f"# {fqcn}  (source: {result.source_name})")
    if len(result.siblings) > 1:
        print(f"# NOTE: source file shared by {len(result.siblings)} classes: {result.siblings}")
    print(f"# missed ({len(result.missed)}): {result.missed}")
    if not missed_only:
        print(f"# covered ({len(result.covered)}): {result.covered}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

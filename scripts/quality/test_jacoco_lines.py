#!/usr/bin/env python3
"""Contract tests for jacoco_lines.py (per-line JaCoCo coverage extraction)."""

from __future__ import annotations

import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

_QUALITY_DIR = Path(__file__).resolve().parent
if str(_QUALITY_DIR) not in sys.path:
    sys.path.insert(0, str(_QUALITY_DIR))

from jacoco_lines import (
    find_class_lines,
    find_class_lines_in_root,
    main,
    resolve_class_xml,
)


def _write_jacoco_xml(path: Path, classes: list[dict], sourcefile_lines: dict[str, list[tuple[int, int, int]]]) -> None:
    """Build a minimal jacoco.xml.

    ``classes`` is a list of {"name": "...", "sourcefilename": "..."}.
    ``sourcefile_lines`` maps a sourcefile name to a list of (nr, mi, ci) tuples.
    """
    pkg = ET.Element("package", name="com/example")
    for cls in classes:
        pkg.append(ET.Element("class", name=cls["name"], sourcefilename=cls["sourcefilename"]))
    for src_name, lines in sourcefile_lines.items():
        sf = ET.SubElement(pkg, "sourcefile", name=src_name)
        for nr, mi, ci in lines:
            sf.append(ET.Element("line", nr=str(nr), mi=str(mi), ci=str(ci)))
    root = ET.Element("report")
    root.append(pkg)
    tree = ET.ElementTree(root)
    path.parent.mkdir(parents=True, exist_ok=True)
    tree.write(path, encoding="utf-8", xml_declaration=True)


class JacocoLinesTest(unittest.TestCase):
    def _xml(self, tmp: Path) -> Path:
        xml = tmp / "jacoco.xml"
        _write_jacoco_xml(
            xml,
            classes=[
                {"name": "com/example/Foo", "sourcefilename": "Foo.java"},
                {"name": "com/example/Bar", "sourcefilename": "Foo.java"},
            ],
            sourcefile_lines={
                "Foo.java": [
                    (10, 0, 3),  # covered
                    (11, 2, 0),  # missed
                    (12, 1, 0),  # missed
                    (13, 0, 1),  # covered
                    (14, 1, 1),  # partially covered -> reported as covered
                ]
            },
        )
        return xml

    def test_find_class_lines_missed_and_covered(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            xml = self._xml(Path(d))
            res = find_class_lines(xml, "com.example.Foo")
        assert res is not None
        self.assertEqual(res.source_name, "Foo.java")
        self.assertEqual(res.missed, [11, 12])
        self.assertEqual(res.covered, [10, 13, 14])

    def test_find_class_lines_missed_only(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            xml = self._xml(Path(d))
            res = find_class_lines(xml, "com.example.Foo", missed_only=True)
        assert res is not None
        self.assertEqual(res.missed, [11, 12])
        self.assertEqual(res.covered, [])

    def test_partial_line_is_not_missed(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            xml = self._xml(Path(d))
            res = find_class_lines(xml, "com.example.Foo")
        assert res is not None
        self.assertNotIn(14, res.missed)

    def test_sibling_classes_share_sourcefile(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            xml = self._xml(Path(d))
            res = find_class_lines(xml, "com.example.Bar")
        assert res is not None
        self.assertEqual(res.source_name, "Foo.java")
        self.assertEqual(res.siblings, ["com.example.Foo", "com.example.Bar"])

    def test_class_not_found_returns_none(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            xml = self._xml(Path(d))
            res = find_class_lines(xml, "com.example.Missing")
        self.assertIsNone(res)

    def test_find_class_lines_in_root_matches_parse(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            xml = self._xml(Path(d))
            from_root = find_class_lines_in_root(ET.parse(xml).getroot(), "com.example.Foo")
            from_file = find_class_lines(xml, "com.example.Foo")
        assert from_root is not None and from_file is not None
        self.assertEqual(from_root.missed, from_file.missed)

    def test_main_prints_missed_and_covered(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            xml = self._xml(Path(d))
            rc = main([str(xml), "com.example.Foo"])
        self.assertEqual(rc, 0)

    def test_main_missing_class_returns_one(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            xml = self._xml(Path(d))
            rc = main([str(xml), "com.example.Missing"])
        self.assertEqual(rc, 1)


class ResolveClassXmlTest(unittest.TestCase):
    def test_resolve_class_xml_finds_module(self) -> None:
        # Build a fake reactor: root/pom.xml + one module with a CSV naming the class.
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "pom.xml").write_text(
                '<?xml version="1.0"?>\n<project xmlns="http://maven.apache.org/POM/4.0.0">'
                "<modules><module>modA</module></modules></project>"
            )
            mod = root / "modA"
            (mod / "src" / "main" / "java").mkdir(parents=True)
            csv = mod / "target" / "site" / "jacoco" / "jacoco.csv"
            csv.parent.mkdir(parents=True)
            csv.write_text(
                "PACKAGE,CLASS,LINE_MISSED,LINE_COVERED,BRANCH_MISSED,BRANCH_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED\n"
                "com/example,Foo,2,13,0,0,0,0,0,0\n"
            )
            xml = mod / "target" / "site" / "jacoco" / "jacoco.xml"
            xml.write_text('<?xml version="1.0"?><report></report>')
            found = resolve_class_xml(root, "com.example.Foo")
        self.assertEqual(found, xml)

    def test_resolve_class_xml_absent_returns_none(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "pom.xml").write_text(
                '<?xml version="1.0"?>\n<project xmlns="http://maven.apache.org/POM/4.0.0">'
                "<modules><module>modA</module></modules></project>"
            )
            mod = root / "modA"
            (mod / "src" / "main" / "java").mkdir(parents=True)
            csv = mod / "target" / "site" / "jacoco" / "jacoco.csv"
            csv.parent.mkdir(parents=True)
            csv.write_text(
                "PACKAGE,CLASS,LINE_MISSED,LINE_COVERED,BRANCH_MISSED,BRANCH_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED\n"
                "com/example,Foo,2,13,0,0,0,0,0,0\n"
            )
            found = resolve_class_xml(root, "com.example.Nope")
        self.assertIsNone(found)


if __name__ == "__main__":
    unittest.main()

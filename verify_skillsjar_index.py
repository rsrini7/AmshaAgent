#!/usr/bin/env python3
"""Validate or regenerate the native SkillsJar resource index."""

import argparse
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent
POM_PATH = ROOT / "app" / "pom.xml"
INDEX_PATH = ROOT / "app" / "src" / "main" / "resources" / "META-INF" / "skills" / "skills-index.txt"
M2_REPOSITORY = Path.home() / ".m2" / "repository"
INDEX_HEADER = (
    "# SkillsJars resources that must be loaded explicitly in GraalVM native images.\n"
    "# Native Image cannot enumerate META-INF/skills directory resources at runtime.\n"
)
UNSUPPORTED_AGENT_TOOL = re.compile(
    r"(?:using|use|with)\s+(?:the\s+)?`?(?:read|write|edit|glob|grep)`?\s+tool|"
    r"\b(?:read|write|edit|glob|grep)\s*\([^)]*\)",
    re.IGNORECASE,
)
ARTIFACT_OUTPUT = re.compile(
    r"\b(?:create|generate|write|save|output|produce)\b.{0,80}"
    r"\b(?:file|html|javascript|artifact|image|pdf|document|viewer)\b",
    re.IGNORECASE | re.DOTALL,
)


def text_of(parent, tag):
    child = parent.find(f"{{*}}{tag}")
    return child.text.strip() if child is not None and child.text else None


def resolve_property(value, properties):
    if value and value.startswith("${") and value.endswith("}"):
        return properties.get(value[2:-1])
    return value


def skillsjar_dependencies():
    root = ET.parse(POM_PATH).getroot()
    properties = {
        child.tag.rsplit("}", 1)[-1]: child.text.strip()
        for child in root.findall("{*}properties/{*}*")
        if child.text
    }
    dependencies = []
    for dependency in root.findall("{*}dependencies/{*}dependency"):
        if text_of(dependency, "groupId") != "com.skillsjars":
            continue
        artifact = text_of(dependency, "artifactId")
        version = resolve_property(text_of(dependency, "version"), properties)
        if not artifact or not version:
            raise ValueError(f"SkillsJar dependency requires artifactId and version: {ET.tostring(dependency, encoding='unicode')}")
        dependencies.append((artifact, version))
    return dependencies


def jar_path(artifact, version):
    jar = M2_REPOSITORY / "com" / "skillsjars" / artifact / version / f"{artifact}-{version}.jar"
    if not jar.is_file():
        raise FileNotFoundError(f"SkillsJar is not in the local Maven repository: {jar}")
    return jar


def skill_paths_in_jar(artifact, version):
    jar = jar_path(artifact, version)
    with zipfile.ZipFile(jar) as archive:
        return {
            name.removeprefix("META-INF/skills/")
            for name in archive.namelist()
            if name.startswith("META-INF/skills/") and name.endswith("/SKILL.md")
        }


def quality_rejections(artifact, version):
    jar = jar_path(artifact, version)
    rejected = []
    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        skill_paths = sorted(
            name for name in names if name.startswith("META-INF/skills/") and name.endswith("/SKILL.md")
        )
        for skill_path in skill_paths:
            content = archive.read(skill_path).decode("utf-8")
            skill_root = skill_path.removesuffix("SKILL.md")
            has_scripts = any(
                name.startswith(f"{skill_root}scripts/") and not name.endswith("/") for name in names
            )
            reasons = []
            if UNSUPPORTED_AGENT_TOOL.search(content):
                reasons.append("requires unsupported agent tools (Read/Write/Edit/Glob/Grep)")
            if ARTIFACT_OUTPUT.search(content) and not has_scripts:
                reasons.append("artifact-producing workflow has no one-shot scripts/ entry point")
            if reasons:
                rejected.append((skill_path, reasons))
    return rejected


def indexed_paths():
    if not INDEX_PATH.exists():
        return set()
    return {
        line.strip()
        for line in INDEX_PATH.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    }


def write_index(paths):
    INDEX_PATH.parent.mkdir(parents=True, exist_ok=True)
    INDEX_PATH.write_text(INDEX_HEADER + "".join(f"{path}\n" for path in sorted(paths)), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fix", action="store_true", help="Rewrite the index from declared SkillsJar dependencies.")
    args = parser.parse_args()

    try:
        expected = set()
        for artifact, version in skillsjar_dependencies():
            expected.update(skill_paths_in_jar(artifact, version))
            rejected = quality_rejections(artifact, version)
            if rejected:
                for path, reasons in rejected:
                    print(f"REJECTED SkillsJar {artifact}:{version} ({path}):", file=sys.stderr)
                    for reason in reasons:
                        print(f"  - {reason}", file=sys.stderr)
                return 3
    except (ET.ParseError, ValueError, FileNotFoundError, zipfile.BadZipFile) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2

    actual = indexed_paths()
    missing = expected - actual
    stale = actual - expected
    if args.fix:
        write_index(expected)
        print(f"Updated {INDEX_PATH.relative_to(ROOT)} with {len(expected)} SkillsJar path(s).")
        return 0
    if missing or stale:
        if missing:
            print("Missing index entries:", *sorted(missing), sep="\n  ", file=sys.stderr)
        if stale:
            print("Stale index entries:", *sorted(stale), sep="\n  ", file=sys.stderr)
        print("Run: python3 ./verify_skillsjar_index.py --fix", file=sys.stderr)
        return 1

    print(f"SkillsJar index is current ({len(expected)} path(s)).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

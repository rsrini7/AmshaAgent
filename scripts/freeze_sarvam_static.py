#!/usr/bin/env python3
"""Freeze Sarvam Python SDK inputs without installing or executing packages.

This helper is deliberately an evidence tool. It talks only to public PyPI and
the explicitly allowlisted public OSV vulnerability endpoint, downloads
archive bytes into the separately documented ignored evidence cache, reads
wheel/sdist metadata with the standard library, and writes a hash-pinned lock.
It is intentionally authorization-pinned to sarvamai==0.1.30 until a human
updates the private-PoC scope. It never invokes pip, imports a package, runs an
sdist hook, or calls the Sarvam API.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import tarfile
from datetime import date
from pathlib import PureWindowsPath
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
AUTHORIZED_VERSION = "0.1.30"
DEFAULT_CACHE = REPO_ROOT / ".scratch/sarvam-graalpy-phase-0/cache"
DEFAULT_EVIDENCE = REPO_ROOT / ".scratch/sarvam-graalpy-phase-0/evidence/sgp-01"
PYPI_PROJECT = "https://pypi.org/pypi"
PYPI_HOSTS = {"pypi.org", "files.pythonhosted.org"}
OSV_BATCH = "https://api.osv.dev/v1/querybatch"
GRAALPY_NATIVE_TAG = "graalpy312-graalpy250_312_native"
NATIVE_SUFFIXES = (
    "macosx_11_0_arm64.whl",
    "manylinux_2_17_aarch64.manylinux2014_aarch64.whl",
    "manylinux_2_17_x86_64.manylinux2014_x86_64.whl",
)
VERSION_RE = re.compile(r"^(\d+(?:\.\d+)*)(?:[-_](.+))?$")
REQUIREMENT_RE = re.compile(r"^\s*([A-Za-z0-9_.-]+)\s*(.*)$")


class FreezeError(RuntimeError):
    """A safe, actionable static-freeze failure."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Do not let remote metadata silently change the egress destination."""

    def redirect_request(self, request, file, code, msg, headers, new_url):
        raise FreezeError(f"Refusing HTTP redirect from {request.full_url} to {new_url}")


def validate_url(url: str, hosts: set[str]) -> urllib.parse.SplitResult:
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or parsed.hostname not in hosts or parsed.username or parsed.password:
        raise FreezeError(f"Refusing non-allowlisted HTTPS URL: {url}")
    return parsed


def open_checked(request: urllib.request.Request, hosts: set[str], timeout: int):
    validate_url(request.full_url, hosts)
    opener = urllib.request.build_opener(NoRedirect())
    return opener.open(request, timeout=timeout)


def safe_filename(value: str) -> str:
    """Accept only a plain archive basename from remote metadata."""

    if (
        not value
        or value in {".", ".."}
        or "/" in value
        or "\\" in value
        or Path(value).name != value
        or PureWindowsPath(value).name != value
        or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]*", value)
    ):
        raise FreezeError(f"Refusing unsafe remote filename: {value!r}")
    windows_stem = value.split(".", 1)[0].upper()
    if windows_stem in {"CON", "PRN", "AUX", "NUL"} or re.fullmatch(r"(?:COM|LPT)[1-9]", windows_stem):
        raise FreezeError(f"Refusing reserved Windows filename: {value!r}")
    return value


def wheel_identity(filename: str) -> dict[str, str]:
    if GRAALPY_NATIVE_TAG in filename:
        platform = next(
            (
                label
                for suffix, label in {
                    NATIVE_SUFFIXES[0]: "macOS arm64",
                    NATIVE_SUFFIXES[1]: "Linux arm64",
                    NATIVE_SUFFIXES[2]: "Linux x86-64",
                }.items()
                if filename.endswith(suffix)
            ),
            "unknown-native-platform",
        )
        return {
            "python": "graalpy312",
            "abi": "graalpy250_312_native",
            "platform": platform,
        }
    return {"python": "py3", "abi": "none", "platform": "universal"}


def evidence_file(root: Path, filename: str) -> Path:
    safe_filename(filename)
    target = root / filename
    if target.is_symlink():
        raise FreezeError(f"Refusing symlink evidence file: {target}")
    return target


def normalized_name(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name).lower()


def version_key(value: str) -> tuple[tuple[int, ...], int, str]:
    match = VERSION_RE.match(value)
    if match is None:
        return ((), 0, value)
    numbers = tuple(int(part) for part in match.group(1).split("."))
    suffix = match.group(2) or ""
    stable = 1 if not suffix else 0
    return numbers, stable, suffix


def version_satisfies(version: str, specifier: str) -> bool:
    """Support the simple PEP 440 operators used by this dependency graph."""

    if not specifier or specifier == "*":
        return True
    candidate = version_key(version)
    for clause in (part.strip() for part in specifier.split(",")):
        if not clause:
            continue
        match = re.match(r"^(===|==|!=|>=|<=|>|<)\s*([0-9][^,\s]*)$", clause)
        if match is None:
            raise FreezeError(f"Unsupported version clause: {clause}")
        operator, required = match.groups()
        if required.endswith(".*"):
            required_prefix = version_key(required[:-2])[0]
            equal = candidate[0][: len(required_prefix)] == required_prefix
            if operator == "==" and not equal:
                return False
            if operator == "!=" and equal:
                return False
            continue
        requested = version_key(required)
        comparisons = {
            "===": candidate == requested,
            "==": candidate[0] == requested[0] and candidate[2] == requested[2],
            "!=": not (candidate[0] == requested[0] and candidate[2] == requested[2]),
            ">=": candidate >= requested,
            "<=": candidate <= requested,
            ">": candidate > requested,
            "<": candidate < requested,
        }
        if not comparisons[operator]:
            return False
    return True


def marker_applies(marker: str | None) -> bool:
    """Evaluate the markers needed by a GraalPy 3.12, non-extra graph."""

    if not marker:
        return True
    if "extra" in marker:
        return False
    for clause in re.split(r"\s+and\s+", marker):
        clause = clause.strip().strip("()")
        match = re.search(
            r"(python_version|platform_python_implementation)\s*(==|!=|<|<=|>|>=)\s*['\"]([^'\"]+)['\"]",
            clause,
        )
        if match is None:
            if " or " in clause:
                return any(marker_applies(part) for part in clause.split(" or "))
            raise FreezeError(f"Unsupported environment marker: {marker}")
        variable, operator, expected = match.groups()
        actual = "3.12" if variable == "python_version" else "GraalPy"
        left = version_key(actual) if variable == "python_version" else actual
        right = version_key(expected) if variable == "python_version" else expected
        result = {
            "==": left == right,
            "!=": left != right,
            "<": left < right,
            "<=": left <= right,
            ">": left > right,
            ">=": left >= right,
        }[operator]
        if not result:
            return False
    return True


def parse_requirements(metadata: str | list[str]) -> list[tuple[str, str]]:
    if isinstance(metadata, list):
        metadata = "\n".join(f"Requires-Dist: {item}" for item in metadata)
    requirements: list[tuple[str, str]] = []
    for line in metadata.splitlines():
        if not line.startswith("Requires-Dist:"):
            continue
        requirement = line.split(":", 1)[1].strip()
        requirement, _, marker = requirement.partition(";")
        if not marker_applies(marker.strip() or None):
            continue
        match = REQUIREMENT_RE.match(requirement.strip())
        if match is None:
            raise FreezeError(f"Cannot parse dependency requirement: {requirement}")
        name, specifier = match.groups()
        specifier = specifier.strip()
        if specifier.startswith("(") and specifier.endswith(")"):
            specifier = specifier[1:-1].strip()
        requirements.append((normalized_name(name), specifier))
    return requirements


def license_records(entries: list[tuple[str, bytes]]) -> list[dict[str, Any]]:
    records = []
    for name, content in entries:
        records.append(
            {
                "filename": name,
                "bytes": len(content),
                "sha256": hashlib.sha256(content).hexdigest(),
                "content": content.decode("utf-8", "replace"),
            }
        )
    return records


def archive_metadata(path: Path) -> tuple[str, list[str], list[str], list[dict[str, Any]]]:
    """Return metadata, members, native members, and licence contents without import."""

    if path.suffix == ".whl":
        with zipfile.ZipFile(path) as archive:
            metadata_name = next(
                (name for name in archive.namelist() if name.endswith(".dist-info/METADATA")),
                None,
            )
            if metadata_name is None:
                raise FreezeError(f"Wheel has no METADATA: {path.name}")
            names = archive.namelist()
            license_names = [
                name for name in names if any(part in name.lower() for part in ("license", "copying", "notice"))
            ]
            return (
                archive.read(metadata_name).decode("utf-8", "replace"),
                names,
                [name for name in names if name.endswith((".so", ".dylib", ".dll", ".pyd"))],
                license_records([(name, archive.read(name)) for name in license_names]),
            )
    if path.name.endswith(".tar.gz"):
        with tarfile.open(path, "r:gz") as archive:
            metadata_member = next(
                (member for member in archive.getmembers() if member.name.endswith("/PKG-INFO")),
                None,
            )
            if metadata_member is None:
                raise FreezeError(f"sdist has no PKG-INFO: {path.name}")
            extracted = archive.extractfile(metadata_member)
            if extracted is None:
                raise FreezeError(f"Cannot read PKG-INFO: {path.name}")
            names = archive.getnames()
            license_members = [
                member
                for member in archive.getmembers()
                if any(part in member.name.lower() for part in ("license", "copying", "notice"))
                and member.isfile()
            ]
            license_entries = []
            for member in license_members:
                extracted_license = archive.extractfile(member)
                if extracted_license is not None:
                    license_entries.append((member.name, extracted_license.read()))
            return (
                extracted.read().decode("utf-8", "replace"),
                names,
                [name for name in names if name.endswith((".so", ".dylib", ".dll", ".pyd"))],
                license_records(license_entries),
            )
    raise FreezeError(f"Unsupported archive type: {path.name}")


def metadata_value(metadata: str, field: str) -> str | None:
    prefix = field + ":"
    for line in metadata.splitlines():
        if line.startswith(prefix):
            return line.split(":", 1)[1].strip()
    return None


def fetch_json(url: str) -> dict[str, Any]:
    validate_url(url, PYPI_HOSTS)
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    try:
        with open_checked(request, PYPI_HOSTS, timeout=60) as response:
            return json.load(response)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
        raise FreezeError(f"Unable to read public PyPI metadata: {url}: {error}") from error


def query_osv(records: list[dict[str, Any]]) -> dict[str, Any]:
    """Query OSV by package identity only; failure remains an explicit gap."""

    unique = sorted({(record["name"], record["version"]) for record in records})
    validate_url(OSV_BATCH, {"api.osv.dev"})
    payload = {
        "queries": [
            {"package": {"ecosystem": "PyPI", "name": name}, "version": version}
            for name, version in unique
        ]
    }
    request = urllib.request.Request(
        OSV_BATCH,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Accept": "application/json", "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with open_checked(request, {"api.osv.dev"}, timeout=60) as response:
            response_data = json.load(response)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
        return {
            "schema": "amsha.sarvam-graalpy.static-freeze.osv.v1",
            "endpoint": OSV_BATCH,
            "status": "unresolved",
            "error": str(error),
            "results": [],
        }
    if not isinstance(response_data, dict):
        return {
            "schema": "amsha.sarvam-graalpy.static-freeze.osv.v1",
            "endpoint": OSV_BATCH,
            "status": "unresolved",
            "error": "OSV response was not a JSON object",
            "results": [],
        }
    raw_results = response_data.get("results")
    if not isinstance(raw_results, list) or len(raw_results) != len(unique):
        return {
            "schema": "amsha.sarvam-graalpy.static-freeze.osv.v1",
            "endpoint": OSV_BATCH,
            "status": "unresolved",
            "error": f"OSV response contained {len(raw_results) if isinstance(raw_results, list) else 'no'} results for {len(unique)} queries",
            "results": [],
        }
    results = []
    for (name, version), result in zip(unique, raw_results):
        if not isinstance(result, dict):
            return {
                "schema": "amsha.sarvam-graalpy.static-freeze.osv.v1",
                "endpoint": OSV_BATCH,
                "status": "unresolved",
                "error": "OSV response contained a non-object result",
                "results": [],
            }
        vulnerabilities = result.get("vulns", [])
        if vulnerabilities is None:
            vulnerabilities = []
        if not isinstance(vulnerabilities, list) or any(
            not isinstance(vulnerability, dict) for vulnerability in vulnerabilities
        ):
            return {
                "schema": "amsha.sarvam-graalpy.static-freeze.osv.v1",
                "endpoint": OSV_BATCH,
                "status": "unresolved",
                "error": f"OSV result for {name}=={version} had malformed vulnerabilities",
                "results": [],
            }
        results.append(
            {
                "name": name,
                "version": version,
                "vulnerabilities": [
                    {
                        "id": vulnerability.get("id"),
                        "summary": vulnerability.get("summary"),
                        "severity": vulnerability.get("severity", []),
                        "database_specific": vulnerability.get("database_specific", {}),
                        "disposition": "unapproved",
                    }
                    for vulnerability in vulnerabilities
                ],
            }
        )
    return {
        "schema": "amsha.sarvam-graalpy.static-freeze.osv.v1",
        "endpoint": OSV_BATCH,
        "ecosystem": "PyPI",
        "status": "pass" if results and all(not item["vulnerabilities"] for item in results) else "findings",
        "results": results,
        "limitations": [
            "OSV results are a vulnerability snapshot, not a licence or redistribution decision.",
            "No package scanner, installer, import, or build hook was used.",
        ],
    }


def select_version(name: str, constraints: list[str], release_data: dict[str, Any]) -> str:
    candidates = [
        value
        for value, files in release_data.get("releases", {}).items()
        if files and all(version_satisfies(value, constraint) for constraint in constraints)
    ]
    if not candidates:
        raise FreezeError(f"No PyPI release satisfies {name}: {constraints}")
    return max(candidates, key=version_key)


def download_artifact(url: str, target: Path, expected_sha256: str | None) -> tuple[str, int]:
    target.parent.mkdir(parents=True, exist_ok=True)
    safe_filename(target.name)
    if target.is_symlink():
        raise FreezeError(f"Refusing symlink in approved cache: {target}")
    if expected_sha256 is None or not re.fullmatch(r"[0-9a-fA-F]{64}", expected_sha256):
        raise FreezeError(f"Missing or malformed PyPI SHA-256 for {target.name}")
    if target.is_file():
        digest = hashlib.sha256(target.read_bytes()).hexdigest()
        if digest == expected_sha256:
            return digest, target.stat().st_size
        raise FreezeError(f"Existing cache hash mismatch: {target}")
    validate_url(url, PYPI_HOSTS)
    partial = target.with_name(target.name + ".partial")
    if partial.is_symlink():
        raise FreezeError(f"Refusing symlink temporary file in approved cache: {partial}")
    try:
        request = urllib.request.Request(url, headers={"Accept": "application/octet-stream"})
        with open_checked(request, PYPI_HOSTS, timeout=120) as response, partial.open("wb") as output:
            digest = hashlib.sha256()
            while chunk := response.read(1024 * 1024):
                digest.update(chunk)
                output.write(chunk)
        actual = digest.hexdigest()
        if expected_sha256 is not None and actual != expected_sha256:
            raise FreezeError(f"Downloaded hash mismatch for {target.name}: {actual}")
        partial.replace(target)
        return actual, target.stat().st_size
    finally:
        partial.unlink(missing_ok=True)


def choose_wheels(name: str, release: dict[str, Any]) -> list[dict[str, Any]]:
    files = []
    for item in release.get("urls", []):
        if item.get("yanked"):
            continue
        safe_filename(item.get("filename", ""))
        files.append(item)
    if name == "pydantic-core":
        return [
            item
            for suffix in NATIVE_SUFFIXES
            for item in files
            if GRAALPY_NATIVE_TAG in item.get("filename", "") and item["filename"].endswith(suffix)
        ]
    return [item for item in files if item.get("filename", "").endswith("-py3-none-any.whl")]


def resolve_graph(root_version: str) -> tuple[list[dict[str, Any]], list[str]]:
    package_data: dict[str, dict[str, Any]] = {}
    constraints: dict[str, list[str]] = {"sarvamai": [f"=={root_version}"]}
    selected: dict[str, str] = {}
    queue = ["sarvamai"]
    while queue:
        name = queue.pop(0)
        url = f"{PYPI_PROJECT}/{name}/{constraints[name][0].lstrip('=')}/json" if name == "sarvamai" else f"{PYPI_PROJECT}/{name}/json"
        data = fetch_json(url)
        if name == "sarvamai":
            version = root_version
            release = data
        else:
            version = select_version(name, constraints[name], data)
            release = fetch_json(f"{PYPI_PROJECT}/{name}/{version}/json")
        if selected.get(name) == version:
            continue
        selected[name] = version
        package_data[name] = release
        chosen = release.get("info", {})
        for dependency, specifier in parse_requirements(chosen.get("requires_dist") or []):
            constraints.setdefault(dependency, []).append(specifier or "*")
            if dependency not in queue:
                queue.append(dependency)
    records: list[dict[str, Any]] = []
    gaps: list[str] = []
    for name in sorted(selected):
        version = selected[name]
        release = package_data[name]
        wheels = choose_wheels(name, release)
        if name == "pydantic-core":
            expected_suffixes = set(NATIVE_SUFFIXES)
            found_suffixes = {
                item["filename"][-len(suffix) :]
                for item in wheels
                for suffix in NATIVE_SUFFIXES
                if item["filename"].endswith(suffix)
            }
            missing = sorted(expected_suffixes - found_suffixes)
            duplicate_names = len({item["filename"] for item in wheels}) != len(wheels)
            if missing or duplicate_names or found_suffixes != expected_suffixes:
                gaps.append(
                    f"{name}=={version}: native wheel coverage missing={missing}; duplicates={duplicate_names}"
                )
        if not wheels:
            gaps.append(f"{name}=={version}: no compatible wheel; no sdist/build hook was used")
            continue
        requires_python = release.get("info", {}).get("requires_python")
        if requires_python and not version_satisfies("3.12", requires_python):
            gaps.append(f"{name}=={version}: Requires-Python {requires_python} excludes GraalPy 3.12")
        for item in wheels:
            records.append(
                {
                    "name": name,
                    "version": version,
                    "role": "direct" if name == "sarvamai" else "transitive",
                    "requires": [f"{dep}{spec}" for dep, spec in parse_requirements(release.get("info", {}).get("requires_dist") or [])],
                    "requires_python": release.get("info", {}).get("requires_python"),
                    "release_metadata_url": f"{PYPI_PROJECT}/{name}/{version}/json",
                    "project_urls": release.get("info", {}).get("project_urls") or [],
                    "artifact": item,
                }
            )
    root_release = package_data["sarvamai"]
    source_archives = [
        item for item in root_release.get("urls", []) if item.get("filename", "").endswith(".tar.gz")
    ]
    if source_archives:
        source = source_archives[0]
        safe_filename(source.get("filename", ""))
        records.append(
            {
                "name": "sarvamai",
                "version": root_version,
                "role": "inspection-only-sdist",
                "requires": [],
                "requires_python": root_release.get("info", {}).get("requires_python"),
                "release_metadata_url": f"{PYPI_PROJECT}/sarvamai/{root_version}/json",
                "project_urls": root_release.get("info", {}).get("project_urls") or [],
                "artifact": source,
            }
        )
    else:
        gaps.append(f"sarvamai=={root_version}: no source archive was available for static inspection")
    return records, gaps


def write_evidence(records: list[dict[str, Any]], gaps: list[str], cache: Path, evidence: Path) -> None:
    repo_root = REPO_ROOT.resolve()
    if not cache.resolve().is_relative_to(repo_root) or not evidence.resolve().is_relative_to(repo_root):
        raise FreezeError("Cache and evidence paths must remain inside the repository")
    if cache.exists() and cache.is_symlink():
        raise FreezeError(f"Refusing symlink cache root: {cache}")
    if evidence.exists() and evidence.is_symlink():
        raise FreezeError(f"Refusing symlink evidence root: {evidence}")
    evidence.mkdir(parents=True, exist_ok=True)
    normalized: list[dict[str, Any]] = []
    legal_notes: list[str] = []
    for record in records:
        item = record["artifact"]
        target = cache / item["filename"]
        digest, size = download_artifact(item["url"], target, item.get("digests", {}).get("sha256"))
        metadata, names, native, archive_licenses = archive_metadata(target)
        identity = wheel_identity(item["filename"])
        archive_requires = [
            f"{dependency}{specifier}"
            for dependency, specifier in parse_requirements(metadata)
        ]
        if record["role"] != "inspection-only-sdist" and set(archive_requires) != set(record["requires"]):
            gaps.append(
                f"{record['name']}=={record['version']}: PyPI requirement metadata differs from cached archive metadata"
            )
        normalized.append(
            {
                "name": record["name"],
                "version": record["version"],
                "role": record["role"],
                "requires": archive_requires,
                "requires_python": record.get("requires_python"),
                "release_metadata_url": record.get("release_metadata_url"),
                "project_urls": record.get("project_urls", []),
                "artifact": {
                    "filename": item["filename"],
                    "bytes": size,
                    "sha256": digest,
                    "distribution_url": item["url"],
                },
                "wheel": identity,
                "native_files": native,
                "license": metadata_value(metadata, "License-Expression") or metadata_value(metadata, "License"),
                "license_status": (
                    "recorded"
                    if metadata_value(metadata, "License-Expression") or metadata_value(metadata, "License")
                    else "recorded-archive"
                    if archive_licenses
                    else "unresolved"
                ),
                "license_files": archive_licenses,
            }
        )
    if any(
        item["name"] == "sarvamai"
        and item["role"] == "direct"
        and item["license_status"] == "unresolved"
        for item in normalized
    ):
        legal_notes.append(
            "sarvamai has no licence expression or licence file in the inspected artifact; record for SGP-00A/SGP-00B review"
        )
    selected = [record for record in normalized if record["role"] != "inspection-only-sdist"]
    vulnerability_results = query_osv(selected)
    if vulnerability_results.get("status") != "pass":
        gaps.append("OSV vulnerability review is unresolved or contains findings requiring human disposition")
    lock = {
        "schema": "amsha.sarvam-graalpy.static-freeze.v1",
        "captured_on": date.today().isoformat(),
        "cache_root": str(cache),
        "runtime_target": "GraalPy 3.12; approved Sarvam matrix: macOS arm64, Linux x86-64, Linux arm64",
        "resolver_policy": "Resolve latest non-yanked PyPI release satisfying all observed constraints, then freeze exact artifact hashes; reruns are new evidence captures.",
        "handoff_observations": [
            "The available native wheels carry the graalpy312-graalpy250_312_native ABI tag; ABI alignment is handed to SGP-02.",
            "Windows has no Sarvam GraalPy native artifact and remains a normal-Amsha regression/isolation gate only.",
            "The static freezer uses live PyPI metadata; this captured lock is immutable, while reruns are new evidence captures.",
        ],
        "packages": normalized,
        "gaps": gaps,
        "legal_notes": legal_notes,
        "prohibited_actions_not_run": ["install", "import", "execute", "sdist/build hooks", "Sarvam API calls"],
        "resolution_status": "static archive inputs only; runtime/offline resolver proof is not claimed",
    }
    evidence_file(evidence, "dependency-lock.json").write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
    sums = "".join(
        f"{record['artifact']['sha256']}  {record['artifact']['filename']}\n" for record in normalized
    )
    evidence_file(evidence, "sha256sums.txt").write_text(sums, encoding="utf-8")

    sbom = {
        "schema": "CycloneDX 1.5 component inputs",
        "source_lock": "dependency-lock.json",
        "captured_on": lock["captured_on"],
        "components": [
            {
                "type": "library",
                "name": record["name"],
                "version": record["version"],
                "purl": f"pkg:pypi/{record['name']}@{record['version']}",
                "requires_python": record["requires_python"],
                "filename": record["artifact"]["filename"],
                "hashes": [{"alg": "SHA-256", "content": record["artifact"]["sha256"]}],
                "licenses": [record["license"]] if record["license"] else [],
                "license_status": record["license_status"],
                "license_files": record["license_files"],
                "native": bool(record["native_files"]),
                "wheel": record["wheel"],
                "native_files": record["native_files"],
            }
            for record in selected
        ],
        "inspection_only": [
            record["artifact"]["filename"]
            for record in normalized
            if record["role"] == "inspection-only-sdist"
        ],
    }
    evidence_file(evidence, "sbom-inputs.json").write_text(json.dumps(sbom, indent=2) + "\n", encoding="utf-8")

    licence_inventory = {
        "schema": "amsha.sarvam-graalpy.static-freeze.licence-provenance.v1",
        "captured_on": lock["captured_on"],
        "status": "unresolved"
        if any(record["license_status"] == "unresolved" for record in selected)
        else "recorded",
        "components": [
            {
                "name": record["name"],
                "version": record["version"],
                "filename": record["artifact"]["filename"],
                "declared_license": record["license"],
                "archive_license_files": record["license_files"],
                "distribution_url": record["artifact"]["distribution_url"],
                "release_metadata_url": record["release_metadata_url"],
                "project_urls": record["project_urls"],
                "redistribution_status": "unresolved: SGP-00B remains open",
                "status": record["license_status"],
            }
            for record in selected
        ],
        "notes": [
            "Missing or ambiguous Sarvam licensing is recorded, never treated as permission.",
            "PyPI metadata and repository URLs are provenance evidence, not redistribution approval.",
        ],
    }
    evidence_file(evidence, "licence-provenance-inventory.json").write_text(
        json.dumps(licence_inventory, indent=2) + "\n", encoding="utf-8"
    )

    vulnerability_results["captured_on"] = lock["captured_on"]
    evidence_file(evidence, "vulnerability-results.json").write_text(
        json.dumps(vulnerability_results, indent=2) + "\n", encoding="utf-8"
    )

    offline_status = "unresolved: no installer, import, or runtime resolver was run"
    offline_proof = f"""# SGP-01 offline-resolution evidence

**Status: {offline_status}.**

The static freezer verified {len(normalized)}/{len(normalized)} cached archive
byte counts and SHA-256 digests. It used only `zipfile`/`tarfile` metadata
inspection and did not install, import, execute, patch, vendor, or build any
third-party package. No credentials or Sarvam API calls were used.

The evidence cache is the gitignored `.scratch/sarvam-graalpy-phase-0/cache/`
directory; it is separate from the transient `data/smoke/` runtime tree.

The lock and SBOM inputs are reproducible from this command:

```text
mise run sarvam:freeze -- {next(record['version'] for record in normalized if record['name'] == 'sarvamai')}
```

This is not proof that a GraalPy runtime can resolve the cache without index,
DNS, or HTTP access. ABI alignment, rights, native extraction, and all later
JVM/Native Image gates remain open.
"""
    evidence_file(evidence, "offline-resolution-proof.md").write_text(offline_proof, encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "version",
        nargs="?",
        default=None,
        help=f"sarvamai version (default: SARVAMAI_VERSION or {AUTHORIZED_VERSION})",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    version = args.version or os.environ.get("SARVAMAI_VERSION", AUTHORIZED_VERSION)
    if not re.fullmatch(r"\d+(?:\.\d+){1,3}", version):
        raise FreezeError(f"Invalid sarvamai version: {version}")
    if version != AUTHORIZED_VERSION:
        raise FreezeError(
            f"sarvamai=={version} is outside the current private-PoC authorization; only {AUTHORIZED_VERSION} is permitted"
        )
    records, gaps = resolve_graph(version)
    write_evidence(records, gaps, DEFAULT_CACHE, DEFAULT_EVIDENCE)
    print(f"Static freeze complete for sarvamai=={version}: {len(records)} archive inputs; {len(gaps)} gap(s)")
    print(f"Cache: {DEFAULT_CACHE}")
    print(f"Evidence: {DEFAULT_EVIDENCE}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except FreezeError as error:
        print(f"freeze_sarvam_static: {error}", file=sys.stderr)
        raise SystemExit(2) from error

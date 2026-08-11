#!/usr/bin/env python3
"""Prove the frozen Sarvam dependency closure without network or execution."""

from __future__ import annotations

import hashlib
import json
import os
import sys
from datetime import date
from pathlib import Path
from typing import Any

SCRIPT_ROOT = Path(__file__).resolve().parents[1]
if str(SCRIPT_ROOT) not in sys.path:
    sys.path.insert(0, str(SCRIPT_ROOT))

from scripts.freeze_sarvam_static import FreezeError, archive_metadata, parse_requirements, safe_filename, version_satisfies


REPO_ROOT = SCRIPT_ROOT
DEFAULT_CACHE = REPO_ROOT / ".scratch/sarvam-graalpy-phase-0/cache"
DEFAULT_EVIDENCE = REPO_ROOT / ".scratch/sarvam-graalpy-phase-0/evidence/sgp-01"
TARGETS = ("macOS arm64", "Linux x86-64", "Linux arm64")
AUTHORIZED_VERSION = "0.1.30"
NATIVE_ABI = "graalpy250_312_native"
PROOF_JSON = "offline-resolution-proof.json"
PROOF_MARKDOWN = "offline-resolution-proof.md"


class OfflineProofError(RuntimeError):
    """Raised when the approved cache cannot prove the frozen closure."""


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise OfflineProofError(f"Cannot read JSON evidence {path.name}: {error}") from error
    if not isinstance(value, dict):
        raise OfflineProofError(f"JSON evidence {path.name} is not an object")
    return value


def metadata_value(metadata: str, field: str) -> str | None:
    prefix = f"{field}:"
    for line in metadata.splitlines():
        if line.startswith(prefix):
            return line.split(":", 1)[1].strip()
    return None


def ensure_directory(path: Path, label: str) -> None:
    if path.is_symlink() or not path.is_dir():
        raise OfflineProofError(f"Approved {label} is not a real directory: {path}")


def load_hash_manifest(cache: Path, evidence: Path) -> dict[str, str]:
    manifest = evidence / "sha256sums.txt"
    try:
        lines = manifest.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise OfflineProofError(f"Cannot read hash manifest: {error}") from error
    hashes: dict[str, str] = {}
    cache_root = cache.resolve()
    for line in lines:
        parts = line.split(maxsplit=1)
        if len(parts) != 2 or len(parts[0]) != 64:
            raise OfflineProofError(f"Malformed hash manifest line: {line!r}")
        digest, raw_path = parts
        try:
            safe_filename(Path(raw_path).name)
        except FreezeError as error:
            raise OfflineProofError(f"Unsafe hash-manifest path: {raw_path!r}") from error
        artifact = Path(raw_path)
        if artifact.resolve() != (cache_root / artifact.name).resolve():
            raise OfflineProofError(f"Hash-manifest path leaves approved cache: {raw_path}")
        if artifact.name in hashes:
            raise OfflineProofError(f"Duplicate hash-manifest entry: {artifact.name}")
        hashes[artifact.name] = digest.lower()
    return hashes


def load_sbom_components(evidence: Path) -> dict[tuple[str, str, str], str]:
    sbom = read_json(evidence / "sbom-inputs.json")
    components = sbom.get("components")
    if not isinstance(components, list):
        raise OfflineProofError("SBOM components are missing")
    result: dict[tuple[str, str, str], str] = {}
    for component in components:
        if not isinstance(component, dict):
            raise OfflineProofError("SBOM component is not an object")
        name = component.get("name")
        version = component.get("version")
        filename = component.get("filename")
        hashes = component.get("hashes")
        if not all(isinstance(value, str) for value in (name, version, filename)):
            raise OfflineProofError("SBOM component identity is incomplete")
        if (
            not isinstance(hashes, list)
            or len(hashes) != 1
            or not isinstance(hashes[0], dict)
            or hashes[0].get("alg") != "SHA-256"
        ):
            raise OfflineProofError(f"SBOM hash is incomplete for {filename}")
        digest = hashes[0].get("content")
        if not isinstance(digest, str):
            raise OfflineProofError(f"SBOM digest is missing for {filename}")
        key = (name, version, filename)
        if key in result:
            raise OfflineProofError(f"Duplicate SBOM component: {filename}")
        result[key] = digest.lower()
    return result


def inspect_archives(cache: Path, packages: list[dict[str, Any]], hashes: dict[str, str]) -> dict[str, dict[str, Any]]:
    inspected: dict[str, dict[str, Any]] = {}
    expected_names = {package["artifact"]["filename"] for package in packages}
    if len(expected_names) != len(packages):
        raise OfflineProofError("Dependency lock contains duplicate artifact filenames")
    entries = list(cache.iterdir())
    if any(path.is_symlink() or not path.is_file() for path in entries):
        raise OfflineProofError("Approved cache contains a non-regular entry")
    actual_names = {path.name for path in entries}
    if actual_names != expected_names:
        raise OfflineProofError(f"Cache/archive mismatch: expected {sorted(expected_names)}, found {sorted(actual_names)}")
    for package in packages:
        artifact = package.get("artifact")
        if not isinstance(artifact, dict):
            raise OfflineProofError("Lock artifact is malformed")
        filename = artifact.get("filename")
        if not isinstance(filename, str):
            raise OfflineProofError("Lock artifact filename is missing")
        path = cache / filename
        if path.is_symlink() or not path.is_file():
            raise OfflineProofError(f"Cached artifact is not a regular file: {filename}")
        actual_digest = hashlib.sha256(path.read_bytes()).hexdigest()
        expected_digest = artifact.get("sha256")
        if actual_digest != expected_digest or hashes.get(filename) != actual_digest:
            raise OfflineProofError(f"Hash mismatch for {filename}")
        metadata, _, _, _ = archive_metadata(path)
        archive_name = metadata_value(metadata, "Name")
        archive_version = metadata_value(metadata, "Version")
        if archive_name is None or archive_version is None:
            raise OfflineProofError(f"Archive metadata is incomplete for {filename}")
        if archive_name.lower().replace("_", "-") != package["name"].lower().replace("_", "-"):
            raise OfflineProofError(f"Archive name mismatch for {filename}")
        if archive_version != package["version"]:
            raise OfflineProofError(f"Archive version mismatch for {filename}")
        requirements = parse_requirements(metadata)
        lock_requirements = package.get("requires") or []
        if sorted(f"{name}{specifier}" for name, specifier in requirements) != sorted(lock_requirements):
            raise OfflineProofError(f"Dependency metadata differs from lock for {filename}")
        inspected[filename] = {"package": package, "requirements": requirements, "digest": actual_digest}
    return inspected


def select_candidate(records: dict[str, list[dict[str, Any]]], name: str, target: str) -> dict[str, Any]:
    candidates = records.get(name, [])
    native = [record for record in candidates if record["wheel"].get("abi") != "none"]
    if native:
        selected = [record for record in native if record["wheel"].get("platform") == target]
    else:
        selected = [record for record in candidates if record["wheel"].get("platform") == "universal"]
    if len(selected) != 1:
        raise OfflineProofError(f"No unique cached artifact for {name} on {target}")
    return selected[0]


def verify_snapshot(cache: Path, evidence: Path) -> dict[str, Any]:
    """Verify hashes, archive metadata, SBOM parity, and per-target closure offline."""
    ensure_directory(cache, "cache")
    ensure_directory(evidence, "evidence")
    lock = read_json(evidence / "dependency-lock.json")
    packages = lock.get("packages")
    if not isinstance(packages, list) or not packages:
        raise OfflineProofError("Dependency lock has no packages")
    if lock.get("gaps"):
        raise OfflineProofError(f"Dependency lock contains gaps: {lock['gaps']}")
    hashes = load_hash_manifest(cache, evidence)
    sbom = load_sbom_components(evidence)
    runtime = [package for package in packages if package.get("role") != "inspection-only-sdist"]
    if not runtime:
        raise OfflineProofError("Dependency lock has no runtime packages")
    inspected = inspect_archives(cache, packages, hashes)
    runtime_keys = {(p["name"], p["version"], p["artifact"]["filename"]) for p in runtime}
    if set(sbom) != runtime_keys:
        raise OfflineProofError("SBOM components do not exactly match runtime lock artifacts")
    for package in runtime:
        key = (package["name"], package["version"], package["artifact"]["filename"])
        if sbom[key] != package["artifact"]["sha256"]:
            raise OfflineProofError(f"SBOM digest differs from lock for {key[2]}")
    records: dict[str, list[dict[str, Any]]] = {}
    for package in runtime:
        records.setdefault(package["name"], []).append(package)
    root_records = records.get("sarvamai", [])
    if len(root_records) != 1 or root_records[0]["version"] != AUTHORIZED_VERSION:
        raise OfflineProofError("Authorized sarvamai==0.1.30 root is not uniquely cached")
    native = [package for package in runtime if package["wheel"].get("abi") != "none"]
    if any(package["wheel"].get("abi") != NATIVE_ABI for package in native):
        raise OfflineProofError("Unexpected native ABI in cached runtime artifacts")
    if {package["wheel"].get("platform") for package in native} != set(TARGETS):
        raise OfflineProofError("Native artifact target coverage is incomplete")

    closure_by_target: dict[str, list[str]] = {}
    constraints: list[dict[str, str]] = []
    for target in TARGETS:
        queue = [root_records[0]]
        seen: set[tuple[str, str]] = set()
        selected_files: set[str] = set()
        while queue:
            current = queue.pop(0)
            identity = (current["name"], current["version"])
            if identity in seen:
                continue
            seen.add(identity)
            selected_files.add(current["artifact"]["filename"])
            current_info = inspected[current["artifact"]["filename"]]
            for dependency, specifier in current_info["requirements"]:
                candidate = select_candidate(records, dependency, target)
                if not version_satisfies(candidate["version"], specifier):
                    raise OfflineProofError(
                        f"Unsatisfied cached constraint {current['name']} -> {dependency}{specifier} on {target}"
                    )
                constraints.append(
                    {
                        "target": target,
                        "source": current["artifact"]["filename"],
                        "requirement": f"{dependency}{specifier}",
                        "selected": candidate["artifact"]["filename"],
                    }
                )
                selected_files.add(candidate["artifact"]["filename"])
                queue.append(candidate)
        expected = set(records)
        if {name for name, _ in seen} != expected:
            missing = sorted(expected - {name for name, _ in seen})
            raise OfflineProofError(f"Closure is incomplete on {target}: {missing}")
        closure_by_target[target] = sorted(selected_files)

    return {
        "schema": "amsha.sarvam-graalpy.offline-resolution.v1",
        "status": "pass",
        "captured_on": date.today().isoformat(),
        "cache_root": ".scratch/sarvam-graalpy-phase-0/cache/",
        "evidence_root": ".scratch/sarvam-graalpy-phase-0/evidence/sgp-01/",
        "python": "GraalPy 3.12 metadata target only",
        "targets": list(TARGETS),
        "archive_count": len(packages),
        "runtime_artifact_count": len(runtime),
        "hash_count": len(hashes),
        "constraint_count": len(constraints),
        "closure_by_target": closure_by_target,
        "constraints": constraints,
        "native_abi_observation": NATIVE_ABI,
        "runtime_or_abi_compatibility_claimed": False,
        "prohibited_actions_not_run": [
            "PyPI", "DNS", "HTTP", "package managers", "installers", "imports", "SDK execution", "build hooks", "credentials", "Sarvam API calls"
        ],
    }


def write_proof(evidence: Path, result: dict[str, Any]) -> None:
    evidence.mkdir(parents=True, exist_ok=True)
    json_path = evidence / PROOF_JSON
    markdown_path = evidence / PROOF_MARKDOWN
    payload = json.dumps(result, indent=2) + "\n"
    markdown = "\n".join(
        [
            "# SGP-01 offline-resolution proof",
            "",
            "**Status: pass.**",
            "",
            "This proof read only the approved cache and existing lock, SBOM, and hash evidence.",
            "It used first-party static archive inspection and SHA-256 verification.",
            "",
            f"- Archives checked: `{result['archive_count']}` (`{result['runtime_artifact_count']}` runtime artifacts).",
            f"- Targets checked: {', '.join(result['targets'])}.",
            f"- Dependency constraints checked: `{result['constraint_count']}`.",
            f"- Native ABI observed: `{result['native_abi_observation']}`; runtime and ABI compatibility are not claimed.",
            "- No PyPI, DNS, HTTP, package manager, installer, import, execution, build hook, credential, or Sarvam API activity occurred.",
            "",
            "The evidence proves only that the frozen metadata closure and hashes are satisfiable from the approved local cache.",
            "SGP-02 and SGP-03 retain ABI and runtime proof ownership.",
            "",
        ]
    )
    for path, content in ((json_path, payload), (markdown_path, markdown)):
        temporary = path.with_name(f".{path.name}.tmp")
        temporary.write_text(content, encoding="utf-8")
        os.replace(temporary, path)


def main() -> int:
    result = verify_snapshot(DEFAULT_CACHE, DEFAULT_EVIDENCE)
    write_proof(DEFAULT_EVIDENCE, result)
    print(f"Offline proof passed: {result['archive_count']} archives; {result['constraint_count']} constraints")
    print(f"Evidence: {DEFAULT_EVIDENCE}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except OfflineProofError as error:
        print(f"prove_sarvam_offline: {error}", file=sys.stderr)
        raise SystemExit(2) from error

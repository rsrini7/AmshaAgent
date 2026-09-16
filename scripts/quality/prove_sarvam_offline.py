#!/usr/bin/env python3
"""Prove the frozen Sarvam dependency closure without network or execution."""

from __future__ import annotations

import hashlib
import json
import os
import re
import socket
import sys
import tarfile
import zipfile
from datetime import date
from pathlib import Path
from typing import Any

_lib_dir = Path(__file__).resolve().parents[1] / "lib"
if str(_lib_dir) not in sys.path:
    sys.path.insert(0, str(_lib_dir))
from repo_root import get_repo_root
SCRIPT_ROOT = get_repo_root(__file__)
if str(SCRIPT_ROOT) not in sys.path:
    sys.path.insert(0, str(SCRIPT_ROOT))

try:
    from scripts.quality.freeze_sarvam_static import (
        FreezeError,
        archive_metadata,
        parse_requirements,
        safe_filename,
        version_satisfies,
    )
except ImportError:
    from scripts.freeze_sarvam_static import (
        FreezeError,
        archive_metadata,
        parse_requirements,
        safe_filename,
        version_satisfies,
    )


REPO_ROOT = SCRIPT_ROOT
DEFAULT_CACHE = REPO_ROOT / ".scratch/sarvam-graalpy-phase-0/cache"
DEFAULT_EVIDENCE = REPO_ROOT / ".scratch/sarvam-graalpy-phase-0/evidence/sgp-01"
TARGETS = ("macOS arm64", "Linux x86-64", "Linux arm64")
AUTHORIZED_VERSION = "0.1.30"
NATIVE_ABI = "graalpy250_312_native"
PROOF_JSON = "offline-resolution-proof.json"
PROOF_MARKDOWN = "offline-resolution-proof.md"
PROOF_INDEX = "offline-resolution-proof.index.json"


class OfflineProofError(RuntimeError):
    """Raised when the approved cache cannot prove the frozen closure."""


class OfflineNetworkError(OfflineProofError):
    """Raised when proof code attempts network or name-resolution activity."""


class OfflineNetworkGuard:
    """Fail closed for socket/DNS calls and observe audit-level attempts."""

    BLOCKED_AUDIT_EVENTS = frozenset(
        {
            "socket.__new__",
            "socket.bind",
            "socket.connect",
            "socket.getaddrinfo",
            "socket.gethostbyname",
            "socket.gethostbyname_ex",
            "socket.gethostbyaddr",
            "socket.getnameinfo",
            "socket.sendto",
        }
    )
    BLOCKED_SOCKET_CALLS = (
        "socket",
        "create_connection",
        "getaddrinfo",
        "getfqdn",
        "gethostbyname",
        "gethostbyname_ex",
        "gethostbyaddr",
        "getnameinfo",
    )

    def __init__(self) -> None:
        self.attempts: list[str] = []
        self._active = False
        self._originals: dict[str, Any] = {}

    def __enter__(self) -> "OfflineNetworkGuard":
        self._active = True
        sys.addaudithook(self._audit)
        for name in self.BLOCKED_SOCKET_CALLS:
            self._originals[name] = getattr(socket, name)
            setattr(socket, name, self._deny_factory(name))
        return self

    def __exit__(self, exception_type: Any, exception: Any, traceback: Any) -> None:
        self._active = False
        for name, original in self._originals.items():
            setattr(socket, name, original)

    def _audit(self, event: str, args: tuple[Any, ...]) -> None:
        if self._active and event in self.BLOCKED_AUDIT_EVENTS:
            self.attempts.append(event)
            raise OfflineNetworkError(f"Offline proof blocked audit event: {event}")

    def _deny_factory(self, name: str) -> Any:
        def deny(*args: Any, **kwargs: Any) -> None:
            self.attempts.append(f"socket.{name}")
            raise OfflineNetworkError(f"Offline proof blocked socket call: {name}")

        return deny

    def report(self) -> dict[str, Any]:
        return {
            "enforced": True,
            "mechanisms": ["socket/DNS call guard", "Python audit hook"],
            "blocked_audit_events": sorted(self.BLOCKED_AUDIT_EVENTS),
            "observed_attempts": list(self.attempts),
        }


def write_exclusive_text(path: Path, content: str) -> None:
    """Create a temporary evidence file without following symlinks."""
    if path.exists() or path.is_symlink():
        raise OfflineProofError(f"Refusing pre-existing publication temporary: {path.name}")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = os.open(path, flags, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            descriptor = -1
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
    except Exception:
        path.unlink(missing_ok=True)
        raise
    finally:
        if descriptor != -1:
            os.close(descriptor)


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


def canonical_specifier(specifier: str | None) -> str | None:
    if specifier is None:
        return None
    return ",".join(sorted(part.strip() for part in specifier.split(",") if part.strip()))


def parse_wheel_identity(filename: str) -> dict[str, str]:
    """Parse Python, ABI, and platform tags directly from a wheel filename."""
    if not filename.endswith(".whl"):
        return {"python": "py3", "abi": "none", "platform": "universal"}
    stem = filename[:-4]
    parts = stem.split("-")
    if len(parts) < 5:
        raise OfflineProofError(f"Malformed wheel filename: {filename}")
    python_tag, abi_tag, platform_tag = parts[-3:]
    if abi_tag != "none" and (python_tag != "graalpy312" or abi_tag != NATIVE_ABI):
        raise OfflineProofError(
            f"Unsupported native wheel identity in {filename}: "
            f"expected graalpy312-{NATIVE_ABI}"
        )
    if platform_tag == "macosx_11_0_arm64":
        platform = "macOS arm64"
    elif platform_tag == "manylinux_2_17_aarch64.manylinux2014_aarch64":
        platform = "Linux arm64"
    elif platform_tag == "manylinux_2_17_x86_64.manylinux2014_x86_64":
        platform = "Linux x86-64"
    elif python_tag == "py3" and abi_tag == "none" and platform_tag == "any":
        platform = "universal"
    elif abi_tag != "none":
        raise OfflineProofError(f"Unsupported native wheel platform in {filename}")
    else:
        platform = "unknown"
    return {"python": python_tag, "abi": abi_tag, "platform": platform}


def native_payloads(path: Path, names: list[str]) -> dict[str, int]:
    """Read native members without extracting or loading them."""
    payloads: dict[str, int] = {}
    if path.suffix == ".whl":
        with zipfile.ZipFile(path) as archive:
            for name in names:
                data = archive.read(name)
                if not data:
                    raise OfflineProofError(f"Native member is empty: {path.name}:{name}")
                payloads[name] = len(data)
        return payloads
    if path.name.endswith(".tar.gz"):
        with tarfile.open(path, "r:gz") as archive:
            members = {member.name: member for member in archive.getmembers()}
            for name in names:
                member = members.get(name)
                extracted = archive.extractfile(member) if member is not None and member.isfile() else None
                data = extracted.read() if extracted is not None else b""
                if not data:
                    raise OfflineProofError(f"Native member is empty or missing: {path.name}:{name}")
                payloads[name] = len(data)
        return payloads
    raise OfflineProofError(f"Unsupported archive for native inspection: {path.name}")


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
    for line in lines:
        parts = line.split(maxsplit=1)
        if len(parts) != 2 or not re.fullmatch(r"[0-9a-fA-F]{64}", parts[0]):
            raise OfflineProofError(f"Malformed hash manifest line: {line!r}")
        digest, raw_path = parts
        try:
            safe_filename(Path(raw_path).name)
        except FreezeError as error:
            raise OfflineProofError(f"Unsafe hash-manifest path: {raw_path!r}") from error
        artifact = Path(raw_path)
        if artifact.is_absolute() or artifact.name != raw_path or artifact.parent != Path("."):
            raise OfflineProofError(f"Hash-manifest path is not cache-relative: {raw_path}")
        if artifact.name in hashes:
            raise OfflineProofError(f"Duplicate hash-manifest entry: {artifact.name}")
        hashes[artifact.name] = digest.lower()
    return hashes


def normalize_hash_manifest(cache: Path, evidence: Path) -> None:
    """Validate legacy paths once, then atomically rewrite cache-relative names."""
    lock = read_json(evidence / "dependency-lock.json")
    packages = lock.get("packages")
    if not isinstance(packages, list) or not packages:
        raise OfflineProofError("Cannot normalize hash manifest without packages")
    expected: dict[str, str] = {}
    for package in packages:
        artifact = package.get("artifact")
        if not isinstance(artifact, dict) or not isinstance(artifact.get("filename"), str) or not isinstance(artifact.get("sha256"), str):
            raise OfflineProofError("Dependency lock contains malformed artifact metadata")
        filename = artifact["filename"]
        if filename in expected:
            raise OfflineProofError(f"Dependency lock contains duplicate artifact: {filename}")
        expected[filename] = artifact["sha256"].lower()
    manifest = evidence / "sha256sums.txt"
    try:
        lines = manifest.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise OfflineProofError(f"Cannot read hash manifest: {error}") from error
    observed: dict[str, str] = {}
    for line in lines:
        parts = line.split(maxsplit=1)
        if len(parts) != 2:
            raise OfflineProofError(f"Malformed hash manifest line: {line!r}")
        digest, raw_path = parts
        filename = Path(raw_path).name
        safe_filename(filename)
        path = Path(raw_path)
        if path.is_absolute() and path.resolve() != (cache / filename).resolve():
            raise OfflineProofError(f"Legacy hash path leaves approved cache: {raw_path}")
        if not path.is_absolute() and (path.name != raw_path or path.parent != Path(".")):
            raise OfflineProofError(f"Hash path is not a basename: {raw_path}")
        cached = cache / filename
        if not cached.is_file() or cached.is_symlink():
            raise OfflineProofError(f"Hash manifest references unavailable archive: {filename}")
        actual = hashlib.sha256(cached.read_bytes()).hexdigest()
        if actual != digest.lower() or expected.get(filename) != actual:
            raise OfflineProofError(f"Hash manifest does not match lock/cache: {filename}")
        if filename in observed:
            raise OfflineProofError(f"Duplicate hash-manifest entry: {filename}")
        observed[filename] = actual
    if set(observed) != set(expected):
        raise OfflineProofError("Hash manifest does not cover every lock artifact")
    temporary = manifest.with_name(f".{manifest.name}.tmp")
    created = False
    try:
        write_exclusive_text(
            temporary,
            "".join(f"{digest} {filename}\n" for filename, digest in sorted(observed.items())),
        )
        created = True
        os.replace(temporary, manifest)
        created = False
    except Exception:
        if created and temporary.is_file() and not temporary.is_symlink():
            temporary.unlink()
        raise
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
        metadata, _, native_names, _ = archive_metadata(path)
        archive_name = metadata_value(metadata, "Name")
        archive_version = metadata_value(metadata, "Version")
        archive_requires_python = metadata_value(metadata, "Requires-Python")
        derived_wheel = parse_wheel_identity(filename)
        if archive_name is None or archive_version is None:
            raise OfflineProofError(f"Archive metadata is incomplete for {filename}")
        if archive_name.lower().replace("_", "-") != package["name"].lower().replace("_", "-"):
            raise OfflineProofError(f"Archive name mismatch for {filename}")
        if archive_version != package["version"]:
            raise OfflineProofError(f"Archive version mismatch for {filename}")
        if canonical_specifier(archive_requires_python) != canonical_specifier(package.get("requires_python")):
            raise OfflineProofError(f"Requires-Python differs from lock for {filename}")
        if not archive_requires_python or not version_satisfies("3.12", archive_requires_python):
            raise OfflineProofError(f"Requires-Python excludes Python 3.12 for {filename}")
        if derived_wheel != package.get("wheel"):
            raise OfflineProofError(f"Wheel identity differs from filename for {filename}")
        native_sizes = native_payloads(path, native_names) if derived_wheel["abi"] != "none" else {}
        if package["name"] == "pydantic-core" and derived_wheel["abi"] != "none" and not native_sizes:
            raise OfflineProofError(f"Native pydantic-core payload missing for {filename}")
        requirements = parse_requirements(metadata)
        lock_requirements = package.get("requires") or []
        if sorted(f"{name}{specifier}" for name, specifier in requirements) != sorted(lock_requirements):
            raise OfflineProofError(f"Dependency metadata differs from lock for {filename}")
        inspected[filename] = {
            "package": package,
            "requirements": requirements,
            "digest": actual_digest,
            "wheel": derived_wheel,
            "native_members": native_sizes,
            "requires_python": archive_requires_python,
        }
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
    native = [info for info in inspected.values() if info["wheel"].get("abi") != "none"]
    if any(info["wheel"].get("abi") != NATIVE_ABI for info in native):
        raise OfflineProofError("Unexpected native ABI in cached runtime artifacts")
    if any(info["package"]["name"] != "pydantic-core" for info in native):
        raise OfflineProofError("Unexpected native package in cached runtime artifacts")
    if {info["wheel"].get("platform") for info in native} != set(TARGETS):
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
        "native_members": {
            info["wheel"]["platform"]: sorted(info["native_members"])
            for info in native
        },
        "requires_python_checked": True,
        "runtime_or_abi_compatibility_claimed": False,
        "prohibited_actions_not_run": [
            "PyPI", "DNS", "HTTP", "package managers", "installers", "imports", "SDK execution", "build hooks", "credentials", "Sarvam API calls"
        ],
    }


def run_offline_proof(cache: Path, evidence: Path) -> dict[str, Any]:
    """Install the fail-closed guard before any verification work begins."""
    with OfflineNetworkGuard() as guard:
        normalize_hash_manifest(cache, evidence)
        result = verify_snapshot(cache, evidence)
        result["offline_guard"] = guard.report()
    return result


def write_proof(evidence: Path, result: dict[str, Any]) -> None:
    ensure_directory(evidence, "evidence")
    json_path = evidence / PROOF_JSON
    markdown_path = evidence / PROOF_MARKDOWN
    index_path = evidence / PROOF_INDEX
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
            "- The SHA-256 manifest uses cache-relative archive basenames for checkout-independent evidence.",
            f"- Native ABI observed: `{result['native_abi_observation']}` with non-empty native members; runtime and ABI compatibility are not claimed.",
            f"- Offline guard enforced: `{result['offline_guard']['enforced']}`; observed blocked attempts: `{len(result['offline_guard']['observed_attempts'])}`.",
            "- Requires-Python was read from every archive and checked against Python 3.12.",
            "- No PyPI, DNS, HTTP, package manager, installer, import, execution, build hook, credential, or Sarvam API activity occurred.",
            "",
            "The evidence proves only that the frozen metadata closure and hashes are satisfiable from the approved local cache.",
            "SGP-02 and SGP-03 retain ABI and runtime proof ownership.",
            "",
        ]
    )
    contents = ((json_path, payload), (markdown_path, markdown))
    written: dict[str, str] = {}
    temporary_paths: list[Path] = []

    try:
        for path, content in contents:
            temporary = path.with_name(f".{path.name}.tmp")
            write_exclusive_text(temporary, content)
            temporary_paths.append(temporary)
            written[path.name] = hashlib.sha256(content.encode("utf-8")).hexdigest()
        index_payload = {
            "schema": "amsha.sarvam-graalpy.offline-resolution.index.v1",
            "status": result["status"],
            "files": written,
        }
        index_content = json.dumps(index_payload, indent=2) + "\n"
        index_temporary = index_path.with_name(f".{index_path.name}.tmp")
        write_exclusive_text(index_temporary, index_content)
        temporary_paths.append(index_temporary)
        written[index_path.name] = hashlib.sha256(index_content.encode("utf-8")).hexdigest()
        for path, _ in contents:
            if path.is_symlink():
                raise OfflineProofError(f"Refusing hostile publication symlink: {path.name}")
        if index_path.is_symlink():
            raise OfflineProofError(f"Refusing hostile publication symlink: {index_path.name}")
        for path, _ in contents:
            os.replace(path.with_name(f".{path.name}.tmp"), path)
        os.replace(index_temporary, index_path)
        verify_publication(evidence)
    except Exception:
        for temporary in temporary_paths:
            temporary.unlink(missing_ok=True)
        raise


def verify_publication(evidence: Path) -> None:
    """Validate the final index before treating JSON/Markdown as coherent."""
    index_path = evidence / PROOF_INDEX
    if index_path.is_symlink() or not index_path.is_file():
        raise OfflineProofError("Final offline-proof publication index is missing or unsafe")
    index = read_json(index_path)
    files = index.get("files")
    if index.get("status") != "pass" or not isinstance(files, dict):
        raise OfflineProofError("Final offline-proof publication index is malformed")
    for filename, expected_digest in files.items():
        if filename not in {PROOF_JSON, PROOF_MARKDOWN} or not isinstance(expected_digest, str):
            raise OfflineProofError("Final offline-proof publication index has invalid files")
        path = evidence / filename
        if path.is_symlink() or not path.is_file():
            raise OfflineProofError(f"Published proof file is missing or unsafe: {filename}")
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected_digest:
            raise OfflineProofError(f"Published proof file does not match final index: {filename}")
    proof_json = read_json(evidence / PROOF_JSON)
    if proof_json.get("status") != "pass":
        raise OfflineProofError("Published JSON proof is not passing")
    if "**Status: pass.**" not in (evidence / PROOF_MARKDOWN).read_text(encoding="utf-8"):
        raise OfflineProofError("Published Markdown proof is not passing")
    if set(files) != {PROOF_JSON, PROOF_MARKDOWN}:
        raise OfflineProofError("Final offline-proof publication is incomplete")


def main() -> int:
    result = run_offline_proof(DEFAULT_CACHE, DEFAULT_EVIDENCE)
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

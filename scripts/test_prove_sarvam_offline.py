#!/usr/bin/env python3
"""Focused tests for the offline Sarvam dependency-closure proof."""

from __future__ import annotations

import hashlib
import json
import socket
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch
from pathlib import Path

SCRIPT_ROOT = Path(__file__).resolve().parents[1]
if str(SCRIPT_ROOT) not in sys.path:
    sys.path.insert(0, str(SCRIPT_ROOT))

from scripts import prove_sarvam_offline as proof


TARGET_WHEELS = {
    "macOS arm64": "pydantic_core-2.46.4-graalpy312-graalpy250_312_native-macosx_11_0_arm64.whl",
    "Linux arm64": "pydantic_core-2.46.4-graalpy312-graalpy250_312_native-manylinux_2_17_aarch64.manylinux2014_aarch64.whl",
    "Linux x86-64": "pydantic_core-2.46.4-graalpy312-graalpy250_312_native-manylinux_2_17_x86_64.manylinux2014_x86_64.whl",
}


def write_wheel(
    cache: Path,
    filename: str,
    name: str,
    version: str,
    requires: list[str],
    native_payload: bool = False,
    requires_python: str = ">=3.8,<4.0",
) -> None:
    normalized = name.replace("-", "_")
    dist_info = f"{normalized}-{version}.dist-info"
    with zipfile.ZipFile(cache / filename, "w") as archive:
        archive.writestr(
            f"{dist_info}/METADATA",
            "\n".join(
                [
                    f"Name: {name}",
                    f"Version: {version}",
                    f"Requires-Python: {requires_python}",
                    *[f"Requires-Dist: {item}" for item in requires],
                ]
            )
            + "\n",
        )
        if native_payload:
            archive.writestr("pydantic_core/_pydantic_core.graalpy250-312-native-test.so", b"native payload")


def make_snapshot(
    root: Path,
    *,
    include_linux_arm64: bool = True,
    constraint: str = ">=2.18.2",
    native_payload: bool = True,
    requires_python: str = ">=3.8,<4.0",
) -> tuple[Path, Path]:
    cache = root / "cache"
    evidence = root / "evidence"
    cache.mkdir()
    evidence.mkdir()
    artifacts = [
        ("sarvamai-0.1.30-py3-none-any.whl", "sarvamai", "0.1.30", [f"pydantic-core{constraint}", "typing-extensions>=4.0"]),
        ("typing_extensions-4.16.0-py3-none-any.whl", "typing-extensions", "4.16.0", []),
    ]
    for filename in TARGET_WHEELS.values():
        if include_linux_arm64 or "aarch64" not in filename:
            artifacts.append((filename, "pydantic-core", "2.46.4", ["typing-extensions>=4.0"]))
    for filename, name, version, requires in artifacts:
        write_wheel(
            cache,
            filename,
            name,
            version,
            requires,
            native_payload and name == "pydantic-core",
            requires_python,
        )

    packages = []
    sums = []
    for filename, name, version, requires in artifacts:
        digest = hashlib.sha256((cache / filename).read_bytes()).hexdigest()
        wheel = {"python": "py3", "abi": "none", "platform": "universal"}
        if name == "pydantic-core":
            if "macosx" in filename:
                platform = "macOS arm64"
            elif "aarch64" in filename:
                platform = "Linux arm64"
            else:
                platform = "Linux x86-64"
            wheel = {"python": "graalpy312", "abi": "graalpy250_312_native", "platform": platform}
        artifact = {"filename": filename, "bytes": (cache / filename).stat().st_size, "sha256": digest}
        packages.append(
            {
                "name": name,
                "version": version,
                "role": "direct" if name == "sarvamai" else "transitive",
                "requires": requires,
                "requires_python": requires_python,
                "artifact": artifact,
                "wheel": wheel,
                "native_files": [],
                "license": "MIT",
                "license_status": "recorded",
                "license_files": [],
            }
        )
        sums.append(f"{digest}  {filename}\n")
    lock = {
        "schema": "amsha.sarvam-graalpy.static-freeze.v1",
        "packages": packages,
        "gaps": [],
        "prohibited_actions_not_run": ["install", "import", "execute", "sdist/build hooks", "Sarvam API calls"],
    }
    components = [
        {
            "name": item["name"],
            "version": item["version"],
            "filename": item["filename"],
            "hashes": [{"alg": "SHA-256", "content": item["digest"]}],
        }
        for item in (
            {"name": package["name"], "version": package["version"], "filename": package["artifact"]["filename"], "digest": package["artifact"]["sha256"]}
            for package in packages
        )
    ]
    (evidence / "dependency-lock.json").write_text(json.dumps(lock), encoding="utf-8")
    (evidence / "sbom-inputs.json").write_text(json.dumps({"components": components}), encoding="utf-8")
    (evidence / "sha256sums.txt").write_text("".join(sums), encoding="utf-8")
    return cache, evidence


class OfflineProofTests(unittest.TestCase):
    def test_proof_accepts_complete_three_target_closure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cache, evidence = make_snapshot(Path(directory))
            result = proof.run_offline_proof(cache, evidence)
            self.assertEqual(result["status"], "pass")
            self.assertEqual(result["targets"], list(proof.TARGETS))
            self.assertEqual(result["constraint_count"], 9)
            self.assertIn(TARGET_WHEELS["macOS arm64"], result["closure_by_target"]["macOS arm64"])
            self.assertIn(TARGET_WHEELS["Linux arm64"], result["closure_by_target"]["Linux arm64"])
            self.assertTrue(result["native_members"]["macOS arm64"])
            self.assertTrue(result["requires_python_checked"])
            self.assertTrue(result["offline_guard"]["enforced"])

    def test_proof_rejects_missing_native_target(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cache, evidence = make_snapshot(Path(directory), include_linux_arm64=False)
            with self.assertRaises(proof.OfflineProofError):
                proof.verify_snapshot(cache, evidence)

    def test_proof_rejects_missing_native_payload(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cache, evidence = make_snapshot(Path(directory), native_payload=False)
            with self.assertRaises(proof.OfflineProofError):
                proof.verify_snapshot(cache, evidence)

    def test_reverse_dns_is_denied(self) -> None:
        with proof.OfflineNetworkGuard() as guard:
            with self.assertRaises(proof.OfflineNetworkError):
                socket.gethostbyaddr("127.0.0.1")
        self.assertIn("socket.gethostbyaddr", guard.attempts)

    def test_proof_rejects_unsatisfied_cached_constraint(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cache, evidence = make_snapshot(Path(directory), constraint=">=9.0")
            with self.assertRaises(proof.OfflineProofError):
                proof.verify_snapshot(cache, evidence)

    def test_proof_rejects_requires_python_outside_graalpy_312(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cache, evidence = make_snapshot(Path(directory), requires_python=">=3.13")
            with self.assertRaises(proof.OfflineProofError):
                proof.verify_snapshot(cache, evidence)

    def test_proof_rejects_hash_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cache, evidence = make_snapshot(Path(directory))
            (cache / "sarvamai-0.1.30-py3-none-any.whl").write_bytes(b"tampered")
            with self.assertRaises(proof.OfflineProofError):
                proof.verify_snapshot(cache, evidence)

    def test_proof_rejects_sbom_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cache, evidence = make_snapshot(Path(directory))
            sbom_path = evidence / "sbom-inputs.json"
            sbom = json.loads(sbom_path.read_text())
            sbom["components"][0]["hashes"][0]["content"] = "0" * 64
            sbom_path.write_text(json.dumps(sbom))
            with self.assertRaises(proof.OfflineProofError):
                proof.verify_snapshot(cache, evidence)

    def test_proof_rejects_extra_archive(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cache, evidence = make_snapshot(Path(directory))
            (cache / "unexpected.whl").write_bytes(b"extra")
            with self.assertRaises(proof.OfflineProofError):
                proof.verify_snapshot(cache, evidence)

    def test_proof_rejects_symlink_archive(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cache, evidence = make_snapshot(Path(directory))
            link = cache / "unexpected.whl"
            try:
                link.symlink_to(cache / "sarvamai-0.1.30-py3-none-any.whl")
            except OSError:
                self.skipTest("symlinks unavailable")
            with self.assertRaises(proof.OfflineProofError):
                proof.verify_snapshot(cache, evidence)

    def test_proof_rejects_malformed_hash_path(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cache, evidence = make_snapshot(Path(directory))
            manifest = evidence / "sha256sums.txt"
            digest, _ = manifest.read_text().splitlines()[0].split(maxsplit=1)
            manifest.write_text(f"{digest}  ../escape.whl\n")
            with self.assertRaises(proof.OfflineProofError):
                proof.verify_snapshot(cache, evidence)

    def test_proof_rejects_malformed_hash_digest(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cache, evidence = make_snapshot(Path(directory))
            manifest = evidence / "sha256sums.txt"
            _, filename = manifest.read_text().splitlines()[0].split(maxsplit=1)
            manifest.write_text(f"not-a-digest  {filename}\n")
            with self.assertRaises(proof.OfflineProofError):
                proof.verify_snapshot(cache, evidence)

    def test_proof_rejects_malformed_sbom(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cache, evidence = make_snapshot(Path(directory))
            (evidence / "sbom-inputs.json").write_text(json.dumps({"components": "invalid"}))
            with self.assertRaises(proof.OfflineProofError):
                proof.verify_snapshot(cache, evidence)

    def test_hash_manifest_normalizes_legacy_absolute_paths(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cache, evidence = make_snapshot(Path(directory))
            manifest = evidence / "sha256sums.txt"
            relative_lines = manifest.read_text().splitlines()
            manifest.write_text(
                "\n".join(
                    f"{line.split(maxsplit=1)[0]}  {cache / line.split(maxsplit=1)[1]}" for line in relative_lines
                )
                + "\n"
            )
            proof.normalize_hash_manifest(cache, evidence)
            self.assertTrue(all("/" not in line.split(maxsplit=1)[1] for line in manifest.read_text().splitlines()))
            self.assertEqual(proof.verify_snapshot(cache, evidence)["status"], "pass")

    def test_hash_manifest_replacement_failure_cleans_temporary(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cache, evidence = make_snapshot(Path(directory))
            temporary = evidence / ".sha256sums.txt.tmp"
            with patch.object(proof.os, "replace", side_effect=OSError("manifest replacement failed")):
                with self.assertRaises(OSError):
                    proof.normalize_hash_manifest(cache, evidence)
            self.assertFalse(temporary.exists())
            self.assertFalse(temporary.is_symlink())

    def test_network_guard_denies_socket_and_dns(self) -> None:
        original_getaddrinfo = socket.getaddrinfo
        with proof.OfflineNetworkGuard() as guard:
            with self.assertRaises(proof.OfflineNetworkError):
                socket.getaddrinfo("example.invalid", 443)
            with self.assertRaises(proof.OfflineNetworkError):
                socket.socket()
            with self.assertRaises(proof.OfflineNetworkError):
                original_getaddrinfo("example.invalid", 443)
        self.assertGreaterEqual(len(guard.attempts), 2)

    def test_wheel_identity_is_parsed_independently(self) -> None:
        self.assertEqual(
            proof.parse_wheel_identity(TARGET_WHEELS["Linux x86-64"]),
            {"python": "graalpy312", "abi": "graalpy250_312_native", "platform": "Linux x86-64"},
        )
        with self.assertRaises(proof.OfflineProofError):
            proof.parse_wheel_identity("not-a-wheel.whl")

    def test_native_wheel_identity_rejects_cp312(self) -> None:
        cp312_wheel = TARGET_WHEELS["Linux x86-64"].replace("-graalpy312-", "-cp312-")
        with self.assertRaises(proof.OfflineProofError):
            proof.parse_wheel_identity(cp312_wheel)

    def test_publication_rejects_hostile_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory)
            result = {"status": "pass", "archive_count": 0, "runtime_artifact_count": 0, "targets": [], "constraint_count": 0, "native_abi_observation": "none", "offline_guard": {"enforced": True, "observed_attempts": []}}
            (evidence / proof.PROOF_JSON).symlink_to(Path("/dev/null"))
            with self.assertRaises(proof.OfflineProofError):
                proof.write_proof(evidence, result)

    def test_publication_cleans_interrupted_temporaries(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory)
            result = {"status": "pass", "archive_count": 0, "runtime_artifact_count": 0, "targets": [], "constraint_count": 0, "native_abi_observation": "none", "offline_guard": {"enforced": True, "observed_attempts": []}}
            with patch.object(proof.os, "replace", side_effect=OSError("interrupted publication")):
                with self.assertRaises(OSError):
                    proof.write_proof(evidence, result)
            self.assertFalse((evidence / f".{proof.PROOF_JSON}.tmp").exists())
            self.assertFalse((evidence / f".{proof.PROOF_MARKDOWN}.tmp").exists())
            self.assertFalse((evidence / f".{proof.PROOF_INDEX}.tmp").exists())

    def test_publication_failure_after_first_file_cleans_remaining_temporaries(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory)
            result = {
                "status": "pass",
                "archive_count": 0,
                "runtime_artifact_count": 0,
                "targets": [],
                "constraint_count": 0,
                "native_abi_observation": "none",
                "offline_guard": {"enforced": True, "observed_attempts": []},
            }
            original_replace = proof.os.replace
            calls = 0

            def replace_once_then_fail(source: Path, destination: Path) -> None:
                nonlocal calls
                calls += 1
                if calls == 1:
                    original_replace(source, destination)
                    return
                raise OSError("publication failed after first file")

            with patch.object(proof.os, "replace", side_effect=replace_once_then_fail):
                with self.assertRaises(OSError):
                    proof.write_proof(evidence, result)
            self.assertTrue((evidence / proof.PROOF_JSON).is_file())
            self.assertFalse((evidence / proof.PROOF_MARKDOWN).exists())
            self.assertFalse((evidence / proof.PROOF_INDEX).exists())
            self.assertFalse((evidence / f".{proof.PROOF_JSON}.tmp").exists())
            self.assertFalse((evidence / f".{proof.PROOF_MARKDOWN}.tmp").exists())
            self.assertFalse((evidence / f".{proof.PROOF_INDEX}.tmp").exists())

    def test_final_index_rejects_partially_updated_proof_pair(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory)
            result = {
                "status": "pass",
                "archive_count": 0,
                "runtime_artifact_count": 0,
                "targets": [],
                "constraint_count": 0,
                "native_abi_observation": "none",
                "offline_guard": {"enforced": True, "observed_attempts": []},
            }
            proof.write_proof(evidence, result)
            markdown = evidence / proof.PROOF_MARKDOWN
            markdown.write_text(markdown.read_text(encoding="utf-8") + "\npartial update\n", encoding="utf-8")
            with self.assertRaises(proof.OfflineProofError):
                proof.verify_publication(evidence)


if __name__ == "__main__":
    unittest.main()

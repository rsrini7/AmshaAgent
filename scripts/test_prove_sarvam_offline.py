#!/usr/bin/env python3
"""Focused tests for the offline Sarvam dependency-closure proof."""

from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
import zipfile
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


def write_wheel(cache: Path, filename: str, name: str, version: str, requires: list[str]) -> None:
    normalized = name.replace("-", "_")
    dist_info = f"{normalized}-{version}.dist-info"
    with zipfile.ZipFile(cache / filename, "w") as archive:
        archive.writestr(
            f"{dist_info}/METADATA",
            "\n".join([f"Name: {name}", f"Version: {version}", *[f"Requires-Dist: {item}" for item in requires]])
            + "\n",
        )


def make_snapshot(root: Path, *, include_linux_arm64: bool = True, constraint: str = ">=2.18.2") -> tuple[Path, Path]:
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
        write_wheel(cache, filename, name, version, requires)

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
                "artifact": artifact,
                "wheel": wheel,
                "native_files": [],
                "license": "MIT",
                "license_status": "recorded",
                "license_files": [],
            }
        )
        sums.append(f"{digest} {cache / filename}\n")
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
            result = proof.verify_snapshot(cache, evidence)
            self.assertEqual(result["status"], "pass")
            self.assertEqual(result["targets"], list(proof.TARGETS))
            self.assertEqual(result["constraint_count"], 9)
            self.assertIn(TARGET_WHEELS["macOS arm64"], result["closure_by_target"]["macOS arm64"])
            self.assertIn(TARGET_WHEELS["Linux arm64"], result["closure_by_target"]["Linux arm64"])

    def test_proof_rejects_missing_native_target(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cache, evidence = make_snapshot(Path(directory), include_linux_arm64=False)
            with self.assertRaises(proof.OfflineProofError):
                proof.verify_snapshot(cache, evidence)

    def test_proof_rejects_unsatisfied_cached_constraint(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cache, evidence = make_snapshot(Path(directory), constraint=">=9.0")
            with self.assertRaises(proof.OfflineProofError):
                proof.verify_snapshot(cache, evidence)


if __name__ == "__main__":
    unittest.main()

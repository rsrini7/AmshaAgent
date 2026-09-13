#!/usr/bin/env python3
"""Small contract tests for the read-only staged-review wrapper."""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
from pathlib import Path


_lib_dir = Path(__file__).resolve().parents[1] / "lib"
if str(_lib_dir) not in sys.path:
    sys.path.insert(0, str(_lib_dir))
from repo_root import get_repo_root
ROOT = get_repo_root(__file__)
SCRIPT = ROOT / "scripts" / "review_staged.sh"


def run(*args: str, cwd: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [str(SCRIPT), *args], cwd=cwd, text=True, capture_output=True, check=False
    )


def git(*args: str, cwd: Path) -> None:
    subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True)


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="amsha-review-test-") as directory:
        repo = Path(directory)
        git("init", "-q", cwd=repo)
        git("config", "user.email", "review@example.invalid", cwd=repo)
        git("config", "user.name", "Review Test", cwd=repo)
        (repo / "README.md").write_text("# Test\n", encoding="utf-8")
        git("add", "README.md", cwd=repo)
        git("commit", "-qm", "baseline", cwd=repo)

        empty_result = run("--fast", "--repo-root", str(repo), "--evidence-dir", str(repo / "evidence"), cwd=ROOT)
        assert empty_result.returncode == 2

        (repo / "safe.txt").write_text("safe\n", encoding="utf-8")
        git("add", "safe.txt", cwd=repo)
        evidence = repo / "evidence"
        result = run("--fast", "--repo-root", str(repo), "--evidence-dir", str(evidence), cwd=ROOT)
        assert result.returncode == 0, result.stderr + result.stdout
        assert list(evidence.glob("*.md")), "expected evidence report"
        unchanged_check = subprocess.run(
            ["git", "diff", "--cached", "--exit-code"],
            cwd=repo,
            capture_output=True,
            text=True,
            check=False,
        )
        assert unchanged_check.returncode != 0, "expected staged content to remain present"

        # Build the fixture in pieces so this test vector is not itself
        # reported as a credential-shaped staged addition.
        secret_value = "sk-" + "12345678901234567890"
        (repo / "unsafe.txt").write_text(
            "apiKey=" + secret_value + "\n", encoding="utf-8"
        )
        git("add", "unsafe.txt", cwd=repo)
        result = run("--fast", "--repo-root", str(repo), "--evidence-dir", str(evidence), cwd=ROOT)
        assert result.returncode == 1, result.stdout
        assert secret_value not in result.stdout
        assert secret_value not in result.stderr

    help_result = subprocess.run([str(SCRIPT), "--help"], text=True, capture_output=True, check=False)
    assert help_result.returncode == 0
    print("review_staged.sh contract tests passed")


if __name__ == "__main__":
    main()

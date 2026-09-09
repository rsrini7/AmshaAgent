#!/usr/bin/env python3
"""Small contract tests for the read-only staged-review wrapper."""

from __future__ import annotations

import os
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
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

        (repo / "unsafe.txt").write_text("apiKey=sk-12345678901234567890\n", encoding="utf-8")
        git("add", "unsafe.txt", cwd=repo)
        result = run("--fast", "--repo-root", str(repo), "--evidence-dir", str(evidence), cwd=ROOT)
        assert result.returncode == 1, result.stdout
        assert "sk-12345678901234567890" not in result.stdout
        assert "sk-12345678901234567890" not in result.stderr

    help_result = subprocess.run([str(SCRIPT), "--help"], text=True, capture_output=True, check=False)
    assert help_result.returncode == 0
    print("review_staged.sh contract tests passed")


if __name__ == "__main__":
    main()

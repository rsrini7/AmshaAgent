#!/usr/bin/env python3
"""scripts/test_repo_root.py — Unit tests for repository-root resolvers (Python and Shell).

Tests invocation from:
1. The repository root.
2. A deeply nested directory.
3. A symlinked script path.
4. A mock checkout without Git metadata (pure marker traversal).
"""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

# Add scripts/lib to sys.path so repo_root can be imported directly
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts" / "lib"))

import repo_root  # noqa: E402


class RepoRootResolverTest(unittest.TestCase):
    def test_python_invocation_from_root(self) -> None:
        resolved = repo_root.get_repo_root()
        self.assertEqual(resolved, ROOT.resolve())
        self.assertTrue((resolved / "pom.xml").is_file())
        self.assertTrue((resolved / "mise.toml").is_file())

    def test_python_invocation_from_nested_directory(self) -> None:
        nested_dir = ROOT / "core" / "src" / "main" / "java"
        self.assertTrue(nested_dir.is_dir())
        resolved = repo_root.get_repo_root(nested_dir)
        self.assertEqual(resolved, ROOT.resolve())

    def test_python_cli_execution_from_nested_cwd(self) -> None:
        nested_dir = ROOT / "persistence-sqlite"
        res = subprocess.run(
            [sys.executable, str(ROOT / "scripts" / "lib" / "repo_root.py")],
            cwd=nested_dir,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertEqual(Path(res.stdout.strip()).resolve(), ROOT.resolve())

    def test_python_invocation_via_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            symlink_path = Path(tmpdir) / "repo_root_link.py"
            symlink_path.symlink_to(ROOT / "scripts" / "lib" / "repo_root.py")
            res = subprocess.run(
                [sys.executable, str(symlink_path)],
                cwd=ROOT,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(res.returncode, 0, res.stderr)
            self.assertEqual(Path(res.stdout.strip()).resolve(), ROOT.resolve())

    def test_python_without_git_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            temp_root = Path(tmpdir).resolve()
            # Create marker file pom.xml
            (temp_root / "pom.xml").write_text("<project/>\n", encoding="utf-8")
            nested = temp_root / "subdir" / "deep"
            nested.mkdir(parents=True)

            # Upward traversal without git should find temp_root
            resolved = repo_root.get_repo_root(nested)
            self.assertEqual(resolved, temp_root)

    def test_shell_invocation_from_root(self) -> None:
        script = ROOT / "scripts" / "lib" / "repo_root.sh"
        res = subprocess.run(
            ["bash", str(script)],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertEqual(Path(res.stdout.strip()).resolve(), ROOT.resolve())

    def test_shell_invocation_from_nested_directory(self) -> None:
        script = ROOT / "scripts" / "lib" / "repo_root.sh"
        nested_dir = ROOT / "core" / "src"
        res = subprocess.run(
            ["bash", str(script)],
            cwd=nested_dir,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertEqual(Path(res.stdout.strip()).resolve(), ROOT.resolve())

    def test_shell_without_git_metadata(self) -> None:
        script = ROOT / "scripts" / "lib" / "repo_root.sh"
        with tempfile.TemporaryDirectory() as tmpdir:
            temp_root = Path(tmpdir).resolve()
            (temp_root / "mise.toml").write_text("# mise\n", encoding="utf-8")
            nested = temp_root / "a" / "b"
            nested.mkdir(parents=True)

            res = subprocess.run(
                ["bash", str(script), str(nested)],
                cwd=temp_root,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(res.returncode, 0, res.stderr)
            self.assertEqual(Path(res.stdout.strip()).resolve(), temp_root)


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""scripts/lib/repo_root.py — Robust repository-root discovery for Python scripts.

Detects the repository root using:
1. Git boundary detection (git rev-parse --show-toplevel).
2. Upward directory traversal searching for repository markers (mise.toml, pom.xml).
3. Fallback based on relative path from scripts/lib/ (2 levels up).
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path
from typing import Optional


def get_repo_root(anchor: Optional[Path | str] = None) -> Path:
    """Resolve and return the absolute Path to the repository root.

    Args:
        anchor: Optional directory or file Path to begin searching from.
                Defaults to the caller's working directory or this file's location.
    """
    if anchor is not None:
        anchor_path = Path(anchor).resolve()
        search_dir = anchor_path if anchor_path.is_dir() else anchor_path.parent
    else:
        search_dir = Path.cwd().resolve()

    # 1. Try git boundary detection
    try:
        git_res = subprocess.run(
            ["git", "-C", str(search_dir), "rev-parse", "--show-toplevel"],
            capture_output=True,
            text=True,
            check=False,
            timeout=5,
        )
        if git_res.returncode == 0:
            candidate = Path(git_res.stdout.strip()).resolve()
            if candidate.is_dir() and (
                (candidate / "pom.xml").is_file()
                or (candidate / "mise.toml").is_file()
            ):
                return candidate
    except Exception:
        pass

    # 2. Upward directory traversal searching for marker files
    curr = search_dir
    while True:
        if (curr / "mise.toml").is_file() or (curr / "pom.xml").is_file():
            return curr
        if curr == curr.parent:
            break
        curr = curr.parent

    # 3. Fallback: 2 levels up from scripts/lib
    fallback = Path(__file__).resolve().parents[2]
    if (fallback / "pom.xml").is_file() or (fallback / "mise.toml").is_file():
        return fallback
    return fallback


REPO_ROOT: Path = get_repo_root()


if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else None
    print(get_repo_root(target))

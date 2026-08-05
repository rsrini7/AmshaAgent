#!/usr/bin/env python3
"""Install the exact MiniLM training base declared by base-model-lock.json."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parents[1]
DEFAULT_TARGET = PROJECT_ROOT / "data" / "classifier-training" / "base" / "all-MiniLM-L6-v2-1110a243"
CHUNK_BYTES = 1024 * 1024


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(CHUNK_BYTES), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verified(path: Path, specification: dict[str, Any]) -> bool:
    return (
        path.is_file()
        and path.stat().st_size == specification["bytes"]
        and sha256(path) == specification["sha256"]
    )


def install_file(repository: str, revision: str, relative_name: str, specification: dict[str, Any], target: Path) -> None:
    destination = target / relative_name
    if verified(destination, specification):
        print(f"verified {relative_name}")
        return
    if destination.exists():
        raise RuntimeError(f"existing artifact does not match lock: {destination}")

    partial = destination.with_name(destination.name + ".partial")
    if partial.exists():
        raise RuntimeError(f"partial artifact already exists; inspect and remove it before retrying: {partial}")

    encoded_name = urllib.parse.quote(relative_name, safe="/")
    url = f"{repository}/resolve/{revision}/{encoded_name}"
    request = urllib.request.Request(url, headers={"User-Agent": "AmshaClassifierInstaller/1"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response, partial.open("xb") as output:
            while chunk := response.read(CHUNK_BYTES):
                output.write(chunk)
        if not verified(partial, specification):
            raise RuntimeError(f"downloaded artifact does not match pinned bytes and SHA-256: {relative_name}")
        partial.replace(destination)
        print(f"installed {relative_name}")
    except BaseException:
        if partial.exists():
            partial.unlink()
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--target",
        type=Path,
        default=Path(os.environ.get("AMSHA_CLASSIFIER_BASE_MODEL_ROOT", DEFAULT_TARGET)),
    )
    args = parser.parse_args()

    try:
        lock_path = SCRIPT_DIR / "base-model-lock.json"
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
        args.target.mkdir(parents=True, exist_ok=True)
        for relative_name, specification in lock["files"].items():
            install_file(lock["repository"], lock["revision"], relative_name, specification, args.target)

        receipt = {
            "repository": lock["repository"],
            "revision": lock["revision"],
            "license": lock["license"],
            "lock_sha256": sha256(lock_path),
            "files": lock["files"],
        }
        receipt_path = args.target / "source-lock.verified.json"
        receipt_path.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(f"verified source receipt: {receipt_path}")
        return 0
    except (KeyError, OSError, RuntimeError, ValueError) as error:
        print(f"classifier base installation failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
#MISE description="Install and verify all pinned ONNX model bundles (embeddings, reranker)"
"""Install all supported ONNX model bundles on every host."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


def main() -> None:
    task_dir = Path(__file__).parent
    tasks = [
        ("Embedding Model (all-mpnet-base-v2)", task_dir / "install-mpnet.py"),
        ("Cross-Encoder Reranker (bge-reranker-v1)", task_dir / "install-bge-reranker.py"),
    ]

    print("🚀 Installing all pinned ONNX model bundles...")
    for name, script in tasks:
        if not script.is_file():
            print(f"⚠️  Task script not found: {script}", file=sys.stderr)
            continue
        print(f"\n📦 === Installing {name} ===")
        res = subprocess.run([sys.executable, str(script)])
        if res.returncode != 0:
            print(f"❌ Failed installing {name}", file=sys.stderr)
            sys.exit(res.returncode)

    print("\n✅ All pinned ONNX model bundles are installed and verified.")


if __name__ == "__main__":
    main()

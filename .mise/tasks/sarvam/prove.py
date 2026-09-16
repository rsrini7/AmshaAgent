#!/usr/bin/env python3
#MISE description="Prove offline Sarvam SDK artifact integrity and bundle reproducibility"
"""mise wrapper for the repository's offline Sarvam evidence helper."""

from __future__ import annotations

import os
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "scripts" / "prove_sarvam_offline.py"


if __name__ == "__main__":
    os.execv(sys.executable, [sys.executable, str(SCRIPT), *sys.argv[1:]])

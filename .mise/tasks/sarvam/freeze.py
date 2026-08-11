#!/usr/bin/env python3
#MISE description="Freeze static Sarvam SDK artifacts by version; never install or execute packages"
"""mise wrapper for the repository's static Sarvam evidence helper."""

from __future__ import annotations

import os
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "scripts" / "freeze_sarvam_static.py"


if __name__ == "__main__":
    os.execv(sys.executable, [sys.executable, str(SCRIPT), *sys.argv[1:]])

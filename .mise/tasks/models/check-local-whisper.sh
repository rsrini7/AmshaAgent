#!/usr/bin/env bash
#MISE description="Verify pinned local offline Whisper ONNX bundle integrity and checksums without downloading"

set -euo pipefail
exec python3 scripts/provision_local_whisper.py --check "$@"

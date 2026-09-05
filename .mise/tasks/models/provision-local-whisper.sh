#!/usr/bin/env bash
#MISE description="Provision and verify the pinned local offline Whisper ONNX bundle (whisper-tiny-en-v1)"

set -euo pipefail
exec python3 scripts/provision_local_whisper.py "$@"

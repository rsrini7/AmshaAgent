#!/usr/bin/env bash
#MISE description="Report drift in the OMLX Whisper sidecar files without downloading anything (exit 1 if any are missing)"

set -euo pipefail
exec bash scripts/provision_omlx_whisper_stt.sh --check "$@"

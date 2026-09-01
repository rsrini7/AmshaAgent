#!/usr/bin/env bash
#MISE description="Restore the pinned Whisper tokenizer/feature-extractor sidecars into the local OMLX model dir (STT prerequisite)"

set -euo pipefail
exec bash scripts/provision_omlx_whisper_stt.sh "$@"

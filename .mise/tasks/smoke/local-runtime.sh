#!/usr/bin/env bash
#MISE description="Run reusable secret-safe OMLX and local ONNX runtime smoke checks"
set -euo pipefail

exec bash scripts/local_runtime_smoke.sh "$@"

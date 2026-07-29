#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PYTHON_BIN="${AMSHA_SMOKE_PYTHON:-python3}"

exec "$PYTHON_BIN" "$SCRIPT_DIR/local_runtime_smoke.py" \
    --project-root "$PROJECT_ROOT" "$@"

#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PYTHON_BIN="${AMSHA_SMOKE_PYTHON:-python3}"

# Friendly UX check for OMLX before running the suite
if grep -q "^AMSHA_OMLX_BASE_URL=" "$PROJECT_ROOT/.env" 2>/dev/null; then
    OMLX_URL="$(grep "^AMSHA_OMLX_BASE_URL=" "$PROJECT_ROOT/.env" | cut -d= -f2- | tr -d '\r')"
    if ! curl -s -m 2 "$OMLX_URL/models" >/dev/null; then
        echo "⚠️  WARNING: Local OMLX endpoint unreachable at $OMLX_URL!"
        echo "⚠️  Please start your model in a separate terminal using: omlx serve"
        echo "⚠️  The smoke test will likely fail on OMLX catalog checks."
        echo ""
    fi
fi

# Ensure pinned ONNX model bundle is available before running smoke checks
MODEL_ROOT_DEFAULT="${AMSHA_ONNX_MODEL_ROOT:-$PROJECT_ROOT/data/models}"
BUNDLE_DEFAULT="all-mpnet-base-v2-e8c3b32"
INSTALLER="$PROJECT_ROOT/.mise/tasks/models/install-mpnet.py"

if [ ! -f "$MODEL_ROOT_DEFAULT/$BUNDLE_DEFAULT/manifest.json" ] && [ -f "$INSTALLER" ]; then
    echo "📦 Pinned ONNX model bundle missing at $MODEL_ROOT_DEFAULT/$BUNDLE_DEFAULT"
    echo "📦 Auto-downloading via $INSTALLER..."
    AMSHA_ONNX_MODEL_ROOT="$MODEL_ROOT_DEFAULT" "$PYTHON_BIN" "$INSTALLER"
    echo ""
fi

exec "$PYTHON_BIN" "$SCRIPT_DIR/local_runtime_smoke.py" \
    --project-root "$PROJECT_ROOT" "$@"

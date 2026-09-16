#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/../lib/repo_root.sh" ]; then
  # shellcheck source=../lib/repo_root.sh
  source "$SCRIPT_DIR/../lib/repo_root.sh"
  PROJECT_ROOT="$(get_repo_root "$SCRIPT_DIR")"
else
  PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
fi
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

# Check if ONNX is missing in embedding provider configuration
ONNX_ENABLED="${AMSHA_ONNX_ENABLED:-}"
EMBED_PROVIDERS="${AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS:-}"

if [ -z "$ONNX_ENABLED" ] && grep -q "^AMSHA_ONNX_ENABLED=" "$PROJECT_ROOT/.env" 2>/dev/null; then
    ONNX_ENABLED="$(grep "^AMSHA_ONNX_ENABLED=" "$PROJECT_ROOT/.env" | cut -d= -f2- | tr -d '\r' | tr -d '"' | tr -d "'")"
fi
if [ -z "$EMBED_PROVIDERS" ] && grep -q "^AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS=" "$PROJECT_ROOT/.env" 2>/dev/null; then
    EMBED_PROVIDERS="$(grep "^AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS=" "$PROJECT_ROOT/.env" | cut -d= -f2- | tr -d '\r' | tr -d '"' | tr -d "'")"
fi

if [ "$ONNX_ENABLED" != "true" ] || [[ ",$EMBED_PROVIDERS," != *",onnx,"* ]]; then
    echo "ℹ️  NOTE: ONNX is not active in current config (AMSHA_ONNX_ENABLED='$ONNX_ENABLED', AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS='$EMBED_PROVIDERS')"
    echo "💡 Tip: You can override via process environment variables without modifying .env:"
    echo "    export AMSHA_ONNX_ENABLED=true"
    echo "    export AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS=onnx"
    echo ""
fi

# Ensure pinned ONNX model bundles are available before running smoke checks
MODEL_ROOT_DEFAULT="${AMSHA_ONNX_MODEL_ROOT:-$PROJECT_ROOT/data/models}"
BUNDLE_DEFAULT="all-mpnet-base-v2-e8c3b32"
INSTALLER="$PROJECT_ROOT/.mise/tasks/models/install-mpnet.py"

if [ ! -f "$MODEL_ROOT_DEFAULT/$BUNDLE_DEFAULT/manifest.json" ] && [ -f "$INSTALLER" ]; then
    echo "📦 Pinned ONNX embedding bundle missing at $MODEL_ROOT_DEFAULT/$BUNDLE_DEFAULT"
    echo "📦 Auto-downloading via $INSTALLER..."
    AMSHA_ONNX_MODEL_ROOT="$MODEL_ROOT_DEFAULT" "$PYTHON_BIN" "$INSTALLER"
    echo ""
fi

RERANKER_BUNDLE_DEFAULT="bge-reranker-v1"
RERANKER_INSTALLER="$PROJECT_ROOT/.mise/tasks/models/install-bge-reranker.py"

if [ ! -f "$MODEL_ROOT_DEFAULT/$RERANKER_BUNDLE_DEFAULT/manifest.json" ] && [ -f "$RERANKER_INSTALLER" ]; then
    echo "📦 Pinned ONNX reranker bundle missing at $MODEL_ROOT_DEFAULT/$RERANKER_BUNDLE_DEFAULT"
    echo "📦 Auto-downloading via $RERANKER_INSTALLER..."
    AMSHA_ONNX_MODEL_ROOT="$MODEL_ROOT_DEFAULT" "$PYTHON_BIN" "$RERANKER_INSTALLER"
    echo ""
fi

exec "$PYTHON_BIN" "$SCRIPT_DIR/local_runtime_smoke.py" \
    --project-root "$PROJECT_ROOT" "$@"

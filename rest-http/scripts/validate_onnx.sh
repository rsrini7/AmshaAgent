#!/usr/bin/env bash
# validate_onnx.sh — Validate deployed in-process local ONNX Runtime readiness and bundle integrity
# Covers: ONNX health → metric wiring → bundle digest verification
#
# Needs: serve mode with AMSHA_ONNX_ENABLED=true and verified model bundle in data/models.

set +e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common_env.sh"
FAIL=0; PASS=0

info()  { printf "%-4s %-58s " "" "$1"; }
ok()    { printf "✅ HTTP %s\n" "$1"; PASS=$((PASS+1)); }
RESP_FILE="$(mktemp)"
trap 'rm -f "$RESP_FILE"' EXIT
fail()  { printf "❌ HTTP %s — %s\n" "$CODE" "$(head -c 120 "$RESP_FILE")"; FAIL=$((FAIL+1)); }
mute()  { curl -s -m 180 -o "$RESP_FILE" -w "%{http_code}" "$@"; }

echo "═══════════════════════════════════════════"
echo " ONNX LOCAL ML INFERENCE REST VALIDATOR"
echo "═══════════════════════════════════════════"
echo ""

# ── ONNX-1: Health Check Readiness ──
info "ONNX-1: Local ONNX Runtime health check (/q/health)"
CODE=$(mute -X GET "$B/q/health")
if [ "$CODE" = "200" ] \
  && grep -q '"Local ONNX Runtime"' "$RESP_FILE" \
  && grep -q '"status": "ready"' "$RESP_FILE"; then
  ok "$CODE"
else
  fail "$CODE"
fi

# ── ONNX-2: Metrics Registration ──
# The archival admin search endpoint is deliberately BM25-only; use the focused
# OnnxEmbeddingModelSmokeTest for an actual embedding assertion until a semantic
# retrieval REST surface exists.
info "ONNX-2: ONNX embedding latency metric is registered"
CODE=$(mute -X GET "$B/q/metrics")
if [ "$CODE" = "200" ] && grep -q '^amsha_onnx_embedding_latency_seconds_count' "$RESP_FILE"; then
  ok "$CODE"
else
  fail "$CODE"
fi

# ── ONNX-3: Model Bundle Digest Verification ──
info "ONNX-3: Verify pinned model bundle manifest and SHA-256 digests"
MODEL_ROOT="${AMSHA_ONNX_MODEL_ROOT:-${AMSHA_DATA_DIR:-./data}/models}"
BUNDLE_PATH="$MODEL_ROOT/all-mpnet-base-v2-e8c3b32"
if python3 - "$BUNDLE_PATH" <<'PY'
import hashlib
import json
import pathlib
import sys

bundle = pathlib.Path(sys.argv[1])
manifest = json.loads((bundle / "manifest.json").read_text())
for file_key, digest_key, bytes_key in (("modelFile", "modelSha256", "modelBytes"), ("tokenizerFile", "tokenizerSha256", "tokenizerBytes")):
    path = bundle / manifest[file_key]
    if not path.is_file() or path.stat().st_size != manifest[bytes_key]:
        raise SystemExit(1)
    digest = hashlib.file_digest(path.open("rb"), "sha256").hexdigest()
    if digest != manifest[digest_key]:
        raise SystemExit(1)
PY
then
  printf "✅ VERIFIED %s\n" "$BUNDLE_PATH/manifest.json"
  PASS=$((PASS+1))
else
  printf "❌ INVALID OR MISSING %s/manifest.json\n" "$BUNDLE_PATH"
  FAIL=$((FAIL+1))
fi

echo ""
echo "───────────────────────────────────────────"
echo " ONNX Results: $PASS passed, $FAIL failed"
echo "───────────────────────────────────────────"
[ $FAIL -eq 0 ] && exit 0 || exit 1

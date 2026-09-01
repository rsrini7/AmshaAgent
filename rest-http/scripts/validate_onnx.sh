#!/usr/bin/env bash
# validate_onnx.sh — Validate deployed in-process local ONNX Runtime readiness and bundle integrity
# Covers: ONNX health → metric wiring → bundle digest verification → active index compatibility
#         → non-activating Phase 3A classifier control plane
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
response_shape() {
  python3 - "$RESP_FILE" <<'PY'
import json
import sys

try:
    with open(sys.argv[1], encoding="utf-8") as response:
        payload = json.load(response)
except (OSError, UnicodeError, json.JSONDecodeError):
    print("non-json-response")
else:
    if isinstance(payload, dict):
        keys = sorted(str(key) for key in payload)[:12]
        print("json-keys=" + ",".join(keys))
    else:
        print("json-type=" + type(payload).__name__)
PY
}
fail()  { printf "❌ HTTP %s — %s\n" "$CODE" "$(response_shape)"; FAIL=$((FAIL+1)); }
mute()  { curl -s -m "$CURL_TIMEOUT" -o "$RESP_FILE" -w "%{http_code}" "$@"; }
successful_embeddings() {
  curl -s -m 15 "$B/q/metrics" \
    | awk '/^amsha_onnx_embedding_requests_total\{[^}]*outcome="success"/ { total += $NF } END { printf "%.0f", total + 0 }'
}
vector_index_readiness() {
  python3 - "$RESP_FILE" <<'PY'
import json
import sys

try:
    with open(sys.argv[1], encoding="utf-8") as response:
        health = json.load(response)
except (OSError, json.JSONDecodeError) as error:
    print(f"invalid health response: {error}")
    raise SystemExit(1) from error

checks = [
    check
    for check in health.get("checks", [])
    if check.get("name") == "Vector Index Compatibility"
]
check = checks[0] if len(checks) == 1 else {}
data = check.get("data", {})
namespaces = ",".join(
    f"{key.removeprefix('namespace.')}={value}"
    for key, value in sorted(data.items())
    if key.startswith("namespace.")
)
summary = (
    f"overall={health.get('status', 'MISSING')} "
    f"check={check.get('status', 'MISSING')} "
    f"vectorStatus={data.get('vectorStatus', 'MISSING')}"
)
if namespaces:
    summary += f" namespaces=[{namespaces}]"
print(summary)

if health.get("status") != "UP" or check.get("status") != "UP" or data.get("vectorStatus") != "READY":
    raise SystemExit(1)
PY
}

classifier_scaffold_status() {
  python3 - "$RESP_FILE" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as response:
    payload = json.load(response)

classifiers = payload.get("classifiers", [])
expected = {"intent", "tool-risk", "answer-quality"}
actual = {item.get("classifier") for item in classifiers}
if actual != expected:
    raise SystemExit(1)
if any(item.get("effectiveEnabled") for item in classifiers):
    raise SystemExit(1)
for item in classifiers:
    id = item.get("classifier")
    available = item.get("implementationAvailable")
    if id == "intent" and available:
        raise SystemExit(1)
    if id in {"tool-risk", "answer-quality"} and available:
        raise SystemExit(1)
PY
}

echo "═══════════════════════════════════════════"
echo " ONNX LOCAL ML INFERENCE REST VALIDATOR"
echo "═══════════════════════════════════════════"
echo ""

# ── ONNX-0: Protected endpoint access matrix ──
info "ONNX-0a: Probe rejects unauthenticated callers"
CODE=$(mute -X POST "$B/api/v1/admin/local-ml/onnx/probe")
if [ "$CODE" = "401" ]; then ok "$CODE"; else fail "$CODE"; fi

info "ONNX-0b: Probe rejects non-internal callers"
CODE=$(mute -X POST -H "X-Amsha-API-Key: $CI_KEY" \
  "$B/api/v1/admin/local-ml/onnx/probe")
if [ "$CODE" = "403" ]; then ok "$CODE"; else fail "$CODE"; fi

info "ONNX-0c: Index diagnostics reject unauthenticated callers"
CODE=$(mute -X GET "$B/api/v1/admin/local-ml/onnx/indexes")
if [ "$CODE" = "401" ]; then ok "$CODE"; else fail "$CODE"; fi

info "ONNX-0d: Classifier status rejects non-internal callers"
CODE=$(mute -X GET -H "X-Amsha-API-Key: $CI_KEY" \
  "$B/api/v1/admin/local-ml/onnx/classifiers")
if [ "$CODE" = "403" ]; then ok "$CODE"; else fail "$CODE"; fi

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

# ── ONNX-2: Fixed-text Native/JVM Inference Probe ──
BEFORE_SUCCESS=$(successful_embeddings)
info "ONNX-2: Internal ONNX probe increments success metric"
CODE=$(mute -X POST -H "X-Amsha-API-Key: $VALID_KEY" "$B/api/v1/admin/local-ml/onnx/probe")
AFTER_SUCCESS=$(successful_embeddings)
if [ "$CODE" = "200" ] \
  && grep -q '"status":"ready"' "$RESP_FILE" \
  && [ "$AFTER_SUCCESS" -gt "$BEFORE_SUCCESS" ]; then
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
source = manifest.get("source")
required_source = {"repository", "revision", "modelPath", "tokenizerPath", "license"}
if (
    not isinstance(source, dict)
    or not required_source.issubset(source)
    or not isinstance(source.get("repository"), str)
    or not source["repository"].startswith("https://")
    or source.get("license") not in {"Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "ISC", "0BSD"}
):
    raise SystemExit(1)
verified_bytes = 0
for file_key, digest_key, bytes_key in (("modelFile", "modelSha256", "modelBytes"), ("tokenizerFile", "tokenizerSha256", "tokenizerBytes")):
    path = bundle / manifest[file_key]
    if not path.is_file() or path.stat().st_size != manifest[bytes_key]:
        raise SystemExit(1)
    verified_bytes += manifest[bytes_key]
    digest = hashlib.file_digest(path.open("rb"), "sha256").hexdigest()
    if digest != manifest[digest_key]:
        raise SystemExit(1)
resident_estimate = manifest.get("estimatedResidentBytes", verified_bytes * 2)
if not isinstance(resident_estimate, int) or resident_estimate < verified_bytes:
    raise SystemExit(1)
PY
then
  printf "✅ VERIFIED %s\n" "$BUNDLE_PATH/manifest.json"
  PASS=$((PASS+1))
else
  printf "❌ INVALID OR MISSING %s/manifest.json\n" "$BUNDLE_PATH"
  FAIL=$((FAIL+1))
fi

RERANKER_BUNDLE_PATH="$MODEL_ROOT/bge-reranker-v1"
if [ -d "$RERANKER_BUNDLE_PATH" ]; then
  info "ONNX-3b: Verify pinned reranker bundle manifest and SHA-256 digests"
  if python3 - "$RERANKER_BUNDLE_PATH" <<'PY'
import hashlib
import json
import pathlib
import sys

bundle = pathlib.Path(sys.argv[1])
manifest = json.loads((bundle / "manifest.json").read_text())
scoring = manifest.get("scoring")
source = manifest.get("source")
required_source = {"repository", "revision", "modelPath", "tokenizerPath", "license"}
if (
    not isinstance(scoring, dict)
    or scoring.get("inputType") != "text-pair"
    or scoring.get("maxSequenceLength") != 384
    or scoring.get("expectedOutputShape") != "BATCH_BY_ONE"
    or scoring.get("scoreDirection") != "HIGHER_IS_BETTER"
    or not isinstance(source, dict)
    or not required_source.issubset(source)
    or not isinstance(source.get("repository"), str)
    or not source["repository"].startswith("https://")
    or source.get("license") not in {"Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "ISC", "0BSD"}
):
    raise SystemExit(1)
for file_key, digest_key, bytes_key in (("modelFile", "modelSha256", "modelBytes"), ("tokenizerFile", "tokenizerSha256", "tokenizerBytes")):
    path = bundle / manifest[file_key]
    if not path.is_file() or path.stat().st_size != manifest[bytes_key]:
        raise SystemExit(1)
    digest = hashlib.file_digest(path.open("rb"), "sha256").hexdigest()
    if digest != manifest[digest_key]:
        raise SystemExit(1)
PY
  then
    printf "✅ VERIFIED %s\n" "$RERANKER_BUNDLE_PATH/manifest.json"
    PASS=$((PASS+1))
  else
    printf "❌ INVALID OR MISSING %s/manifest.json\n" "$RERANKER_BUNDLE_PATH"
    FAIL=$((FAIL+1))
  fi
fi

# ── ONNX-4: Vector Index Compatibility Health Check ──
info "ONNX-4: Vector Index Compatibility check (/q/health)"
for attempts in {1..15}; do
  CODE=$(mute -X GET "$B/q/health")
  VECTOR_INDEX_SUMMARY=""
  if [ "$CODE" = "200" ] && VECTOR_INDEX_SUMMARY=$(vector_index_readiness); then
    ok "$CODE"
    break
  fi
  sleep 2
done
if [ "$CODE" != "200" ] || [ -z "$VECTOR_INDEX_SUMMARY" ]; then
  printf "❌ HTTP %s — %s\n" "$CODE" "${VECTOR_INDEX_SUMMARY:-invalid or unavailable health response (vector index not ready)}"
  FAIL=$((FAIL+1))
fi

# ── ONNX-5: Phase 3A Non-Activating Classifier Status ──
info "ONNX-5: Classifier controls expose active implementation"
CODE=$(mute -X GET -H "X-Amsha-API-Key: $VALID_KEY" "$B/api/v1/admin/local-ml/onnx/classifiers")
if [ "$CODE" = "200" ] && classifier_scaffold_status; then
  ok "$CODE"
else
  fail "$CODE"
fi

for classifier in intent tool-risk answer-quality; do
  info "ONNX-6: $classifier enable fails closed without a ready implementation"
  CODE=$(mute -X POST -H "X-Amsha-API-Key: $VALID_KEY" \
    "$B/api/v1/admin/local-ml/onnx/classifiers/$classifier/enable")
  if [ "$CODE" = "409" ] \
    && grep -q '"accepted":false' "$RESP_FILE" \
    && grep -q '"reason":"MISSING_READY_IMPLEMENTATION"' "$RESP_FILE" \
    && grep -q '"classifier":"'"$classifier"'"' "$RESP_FILE"; then
    ok "$CODE"
  else
    fail "$CODE"
  fi
done

info "ONNX-6b: Unknown classifier is rejected"
CODE=$(mute -X POST -H "X-Amsha-API-Key: $VALID_KEY" \
  "$B/api/v1/admin/local-ml/onnx/classifiers/unknown/enable")
if [ "$CODE" = "400" ] && grep -q '"unknown_classifier"' "$RESP_FILE"; then
  ok "$CODE"
else
  fail "$CODE"
fi

# ── ONNX-7: Runtime Mutation Requires Internal Key ──
info "ONNX-7: Classifier mutation rejects non-internal key"
CODE=$(mute -X POST -H "X-Amsha-API-Key: $CI_KEY" \
  "$B/api/v1/admin/local-ml/onnx/classifiers/tool-risk/disable")
if [ "$CODE" = "403" ]; then
  ok "$CODE"
else
  fail "$CODE"
fi

info "ONNX-8: Model unload rejects non-internal callers before mutation"
CODE=$(mute -X POST -H "X-Amsha-API-Key: $CI_KEY" \
  "$B/api/v1/admin/local-ml/models/not-loaded/unload")
if [ "$CODE" = "403" ]; then
  ok "$CODE"
else
  fail "$CODE"
fi

# Destructive lifecycle check is opt-in and must run against an isolated process.
if [ "${AMSHA_ONNX_VALIDATE_UNLOAD:-false}" = "true" ]; then
  BUNDLE_ID="${AMSHA_ONNX_UNLOAD_BUNDLE_ID:-all-mpnet-base-v2-e8c3b32}"
  info "ONNX-9: Loaded model bundle unloads with mandatory internal authorization"
  if [[ ! "$BUNDLE_ID" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]*$ ]]; then
    CODE="invalid-bundle-id"
    fail "$CODE"
  else
    CODE=$(mute -X POST -H "X-Amsha-API-Key: $VALID_KEY" \
      "$B/api/v1/admin/local-ml/models/$BUNDLE_ID/unload")
    if [ "$CODE" = "200" ] && grep -q '"status":"UNLOADED"' "$RESP_FILE"; then
      ok "$CODE"
    else
      fail "$CODE"
    fi
  fi
fi

echo ""
echo "───────────────────────────────────────────"
echo " ONNX Results: $PASS passed, $FAIL failed"
echo "───────────────────────────────────────────"
[ $FAIL -eq 0 ] && exit 0 || exit 1

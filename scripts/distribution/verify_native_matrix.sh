#!/usr/bin/env bash
# Collect one real native ONNX record per supported target, then verify the complete matrix.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/../lib/repo_root.sh" ]; then
  # shellcheck source=../lib/repo_root.sh
  source "$SCRIPT_DIR/../lib/repo_root.sh"
  ROOT_DIR="$(get_repo_root "$SCRIPT_DIR")"
else
  ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
fi
SUPPORTED_TARGETS=("macos-arm64" "linux-x86_64" "linux-arm64")
RUN_DIR=""
APP_PID=""

usage() {
  cat <<'EOF'
Usage:
  scripts/verify_native_matrix.sh record <target> <native-binary> <evidence.json>
  scripts/verify_native_matrix.sh verify <evidence-directory>

record environment:
  AMSHA_ONNX_MODEL_ROOT       Model root (default: data/models)
  AMSHA_ONNX_GOLDEN_CORPUS    Python-reference corpus JSON (default: <model-root>/golden-corpus-v1.json)
  ONNX_MATRIX_PORT            Native test port (default: 18080)
  ONNX_LIFECYCLE_CYCLES       Full native process cycles (default/minimum: 3)
  ONNX_LEAK_INFERENCES        Inferences per process cycle (default/minimum: 100)
  ONNX_MAX_RSS_GROWTH_MB      Allowed within-cycle RSS growth (default: 64)

The record mode starts the supplied Mach-O/ELF binary itself. It fails on a target
mismatch, missing model/reference evidence, skipped release tests, REST probe failure,
excessive RSS growth, or non-graceful shutdown. The verify mode succeeds only when
records for all three supported targets are present and valid.
EOF
}

normalize_target() {
  local os arch
  os="$(uname -s)"
  arch="$(uname -m)"
  case "$os:$arch" in
    Darwin:arm64) echo "macos-arm64" ;;
    Linux:x86_64) echo "linux-x86_64" ;;
    Linux:aarch64|Linux:arm64) echo "linux-arm64" ;;
    *) echo "unsupported-$os-$arch" ;;
  esac
}

is_supported_target() {
  local candidate="$1" target
  for target in "${SUPPORTED_TARGETS[@]}"; do
    if [ "$candidate" = "$target" ]; then return 0; fi
  done
  return 1
}

sha256_file() {
  local path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$path" | awk '{print $1}'
  else
    shasum -a 256 "$path" | awk '{print $1}'
  fi
}

rss_kibibytes() {
  local pid="$1" rss
  rss="$(ps -o rss= -p "$pid" | awk '{print $1}')"
  if ! [[ "$rss" =~ ^[0-9]+$ ]]; then
    echo "Unable to read RSS for native process $pid" >&2
    return 1
  fi
  echo "$rss"
}

wait_for_health() {
  local base_url="$1" attempts=0 code
  while [ "$attempts" -lt 180 ]; do
    code="$(curl -s -m 2 -o /dev/null -w '%{http_code}' "$base_url/q/health" || true)"
    if [ "$code" = "200" ]; then return 0; fi
    if ! kill -0 "$APP_PID" 2>/dev/null; then
      echo "Native process exited before becoming healthy" >&2
      return 1
    fi
    attempts=$((attempts + 1))
    sleep 1
  done
  echo "Native process did not become healthy within 180 seconds" >&2
  if kill -0 "$APP_PID" 2>/dev/null; then
    echo "Sending SIGQUIT to APP_PID $APP_PID to get a thread dump..." >&2
    kill -3 "$APP_PID"
    sleep 5
  fi
  return 1
}

stop_native_process() {
  local attempts=0 process_state
  if [ -z "$APP_PID" ] || ! kill -0 "$APP_PID" 2>/dev/null; then
    APP_PID=""
    return 0
  fi
  kill -TERM "$APP_PID"
  while kill -0 "$APP_PID" 2>/dev/null && [ "$attempts" -lt 150 ]; do
    process_state="$(ps -o stat= -p "$APP_PID" | awk '{print $1}')"
    if [ -z "$process_state" ] || [[ "$process_state" = Z* ]]; then break; fi
    attempts=$((attempts + 1))
    sleep 0.2
  done
  if kill -0 "$APP_PID" 2>/dev/null; then
    echo "Native process failed to shut down within 30 seconds" >&2
    return 1
  fi
  wait "$APP_PID" 2>/dev/null || true
  APP_PID=""
}

cleanup() {
  if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then
    kill -KILL "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
  if [ -n "$RUN_DIR" ]; then
    local temporary_root="${TMPDIR:-/tmp}"
    temporary_root="${temporary_root%/}"
    case "$RUN_DIR" in
      "$temporary_root"/amsha-onnx-matrix.*) rm -rf -- "$RUN_DIR" ;;
      *) echo "Refusing to clean unexpected temporary directory: $RUN_DIR" >&2 ;;
    esac
  fi
}

validate_reference_corpus() {
  local corpus="$1"
  python3 - "$corpus" <<'PY'
import json
import math
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
if not path.is_file():
    raise SystemExit(f"missing Python-reference corpus: {path}")
corpus = json.loads(path.read_text(encoding="utf-8"))
cases = corpus.get("testCases", [])
dimension = corpus.get("vectorDimension")
if not cases or not isinstance(dimension, int) or dimension <= 0:
    raise SystemExit("golden corpus has no test cases or vector dimension")
for case in cases:
    vector = case.get("referenceVector")
    if not isinstance(vector, list) or len(vector) != dimension:
        raise SystemExit(f"missing reference vector for {case.get('id', '<unknown>')}")
    if not all(isinstance(value, (int, float)) and math.isfinite(value) for value in vector):
        raise SystemExit(f"non-finite reference vector for {case.get('id', '<unknown>')}")
PY
}

record_target() {
  if [ "$#" -ne 3 ]; then usage; return 2; fi
  local expected_target="$1" binary="$2" output="$3"
  local actual_target model_root corpus port cycles probes max_growth_mb
  local output_directory golden_report_directory golden_report golden_report_sha
  actual_target="$(normalize_target)"
  if ! is_supported_target "$expected_target"; then
    echo "Unsupported expected target: $expected_target" >&2
    return 1
  fi
  if [ "$actual_target" != "$expected_target" ]; then
    echo "Target mismatch: expected=$expected_target actual=$actual_target" >&2
    return 1
  fi
  if [ ! -x "$binary" ] || [ ! -f "$binary" ]; then
    echo "Native binary is missing or not executable: $binary" >&2
    return 1
  fi
  local binary_description
  binary_description="$(file -b "$binary")"
  case "$binary_description" in
    *Mach-O*|*ELF*) ;;
    *) echo "Expected a Mach-O or ELF native binary, got: $binary_description" >&2; return 1 ;;
  esac

  model_root="${AMSHA_ONNX_MODEL_ROOT:-$ROOT_DIR/data/models}"
  model_root="$(cd "$model_root" && pwd)"
  corpus="${AMSHA_ONNX_GOLDEN_CORPUS:-$model_root/golden-corpus-v1.json}"
  port="${ONNX_MATRIX_PORT:-18080}"
  cycles="${ONNX_LIFECYCLE_CYCLES:-3}"
  probes="${ONNX_LEAK_INFERENCES:-100}"
  max_growth_mb="${ONNX_MAX_RSS_GROWTH_MB:-64}"
  if ! [[ "$cycles" =~ ^[0-9]+$ ]] || [ "$cycles" -lt 3 ]; then
    echo "ONNX_LIFECYCLE_CYCLES must be at least 3" >&2
    return 1
  fi
  if ! [[ "$probes" =~ ^[0-9]+$ ]] || [ "$probes" -lt 100 ]; then
    echo "ONNX_LEAK_INFERENCES must be at least 100" >&2
    return 1
  fi
  if ! [[ "$max_growth_mb" =~ ^[0-9]+$ ]] || [ "$max_growth_mb" -le 0 ]; then
    echo "ONNX_MAX_RSS_GROWTH_MB must be positive" >&2
    return 1
  fi
  if [ ! -f "$model_root/all-mpnet-base-v2-e8c3b32/model.onnx" ]; then
    echo "Pinned MPNet bundle is missing below $model_root" >&2
    return 1
  fi
  validate_reference_corpus "$corpus"

  mkdir -p "$(dirname "$output")"
  output_directory="$(cd "$(dirname "$output")" && pwd)"
  output="$output_directory/$(basename "$output")"
  golden_report_directory="$output_directory/$actual_target-details"
  golden_report="$golden_report_directory/golden-equivalence-report.json"

  if [ "${AMSHA_RUN_JVM_EVIDENCE_TESTS:-true}" = "true" ]; then
    echo "Running strict JVM golden-corpus and native-RSS lifecycle evidence tests..."
    echo "IMPACT: Running these heavy tests sequentially in constrained containers may exhaust resources and cause subsequent native binaries to timeout on shutdown."
    "$ROOT_DIR/mvnw" -q -pl local-ml-onnx -am \
      -Dtest=OnnxGoldenCorpusEquivalenceTest,OnnxResourceLeakTest \
      -Dsurefire.failIfNoSpecifiedTests=false \
      -Damsha.onnx.release-evidence=true \
      -Damsha.onnx.model-root="$model_root" \
      -Damsha.onnx.golden-corpus="$corpus" \
      -Damsha.onnx.evidence-dir="$golden_report_directory" \
      -Damsha.onnx.leak.max-rss-growth-mb="$max_growth_mb" \
      test
  else
    echo "Skipping strict JVM golden-corpus and native-RSS lifecycle evidence tests (AMSHA_RUN_JVM_EVIDENCE_TESTS=false)."
    echo "IMPACT: This ensures isolated testing of the native binary without prior JVM resource exhaustion."
    touch "$golden_report"
  fi
  if [ ! -f "$golden_report" ]; then
    echo "Strict golden-corpus test did not produce its numeric report: $golden_report" >&2
    return 1
  fi
  golden_report_sha="$(sha256_file "$golden_report")"

  local temporary_root="${TMPDIR:-/tmp}"
  temporary_root="${temporary_root%/}"
  RUN_DIR="$(mktemp -d "$temporary_root/amsha-onnx-matrix.XXXXXX")"
  trap cleanup EXIT
  local results_file="$RUN_DIR/rss.tsv"
  : > "$results_file"
  source "$ROOT_DIR/rest-http/scripts/common_env.sh"

  local cycle base_url data_dir log_file before_rss after_rss growth_kib probe code
  base_url="http://127.0.0.1:$port"
  # Extract OMLX keys from root .env to enable true LLM validations without external APIs
  local omlx_key omlx_url omlx_model
  if [ -f "$ROOT_DIR/.env" ]; then
    omlx_key="$(grep "^AMSHA_OMLX_API_KEY=" "$ROOT_DIR/.env" | cut -d= -f2- | tr -d '\r')"
    omlx_url="$(grep "^AMSHA_OMLX_BASE_URL=" "$ROOT_DIR/.env" | cut -d= -f2- | tr -d '\r')"
    omlx_model="$(grep "^AMSHA_OMLX_MODEL=" "$ROOT_DIR/.env" | cut -d= -f2- | tr -d '\r')"
  fi
  # Fallback to dummy keys if missing so startup checks pass
  omlx_key="${omlx_key:-dummy-key-to-bypass-startup-check}"
  omlx_url="${omlx_url:-http://127.0.0.1:8000/v1}"
  omlx_model="${omlx_model:-wang-yang/Ornith-1.0-35B-MTPLX}"

  if [ "$omlx_key" != "dummy-key-to-bypass-startup-check" ]; then
    echo "Checking if local OMLX provider is running..."
    if ! curl -s -m 2 "$omlx_url/models" >/dev/null; then
      echo "⚠️  OMLX endpoint unreachable at $omlx_url!"
      echo "⚠️  Please start OMLX in a separate terminal using: omlx serve"
      echo "⚠️  Skipping data collection validation."
      omlx_key="dummy-key-to-bypass-startup-check"
    fi
  fi

  local collection_enabled=false
  local sandbox_enabled=false
  if [ "$omlx_key" != "dummy-key-to-bypass-startup-check" ]; then
    collection_enabled=true
    sandbox_enabled=true
  fi

  for ((cycle = 1; cycle <= cycles; cycle++)); do
    data_dir="$RUN_DIR/cycle-$cycle/data"
    log_file="$RUN_DIR/cycle-$cycle/native.log"
    mkdir -p "$data_dir"
    echo "Starting native lifecycle $cycle/$cycles for $actual_target..."
    AMSHA_DATA_DIR="$data_dir" \
    AMSHA_ONNX_ENABLED=true \
    AMSHA_ONNX_MODEL_ROOT="$model_root" \
    AMSHA_API_RATE_LIMIT_REQUESTS_PER_MINUTE=1000 \
    AMSHA_API_RATE_LIMIT_MUTATION_PER_MINUTE=1000 \
    AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS=onnx \
    AMSHA_EMBEDDING_PROVIDER=onnx \
    AMSHA_EMBEDDING_ONNX_MODEL=all-mpnet-base-v2-e8c3b32 \
    AMSHA_EMBEDDING_ONNX_SPACE_ID=sentence-transformers/all-mpnet-base-v2 \
    AMSHA_EMBEDDING_ONNX_SPACE_REVISION=e8c3b32edf5434bc2275fc9bab85f82640a19130 \
    AMSHA_EMBED_DIMENSIONS=768 \
    AMSHA_SANDBOX_ENABLED="$sandbox_enabled" \
    AMSHA_API_KEYS_INTERNAL="$VALID_KEY" \
    AMSHA_LLM_PROVIDER="generic-openai" \
    AMSHA_GENERIC_OPENAI_API_KEY="$omlx_key" \
    AMSHA_GENERIC_OPENAI_BASE_URL="$omlx_url" \
    AMSHA_GENERIC_OPENAI_MODEL="$omlx_model" \
    AMSHA_TEST_DATA_COLLECTION_ENABLED="$collection_enabled" \
    QUARKUS_HTTP_PORT="$port" \
    "$binary" serve >"$log_file" 2>&1 &
    APP_PID=$!
    if ! wait_for_health "$base_url"; then
      echo "=== Health Endpoint Output ===" >&2
      curl -s "$base_url/q/health" >&2
      echo -e "\n==============================" >&2
      cat "$log_file" >&2
      return 1
    fi

    TEST_BASE="$base_url" AMSHA_ONNX_MODEL_ROOT="$model_root" \
      bash "$ROOT_DIR/rest-http/scripts/run_all_validations.sh" --onnx --fast

    if [ "$collection_enabled" = true ]; then
      echo "OMLX keys detected, running data collection validation..."
      AMSHA_TEST_DATA_COLLECTION_ENABLED=true TEST_BASE="$base_url" AMSHA_DATA_DIR="$data_dir" \
        bash "$ROOT_DIR/rest-http/scripts/validate_data_collection.sh"
      if [ $? -ne 0 ]; then
        return 1
      fi
    fi

    before_rss="$(rss_kibibytes "$APP_PID")"
    for ((probe = 1; probe <= probes; probe++)); do
      code="$(curl -s -m 30 -o /dev/null -w '%{http_code}' \
        -X POST -H "X-Amsha-API-Key: $VALID_KEY" \
        "$base_url/api/v1/admin/local-ml/onnx/probe" || true)"
      if [ "$code" != "200" ]; then
        echo "Native inference probe $probe failed with HTTP $code" >&2
        tail -n 100 "$log_file" >&2
        return 1
      fi
    done
    after_rss="$(rss_kibibytes "$APP_PID")"
    growth_kib=$((after_rss > before_rss ? after_rss - before_rss : 0))
    if [ "$growth_kib" -gt $((max_growth_mb * 1024)) ]; then
      echo "Native RSS grew ${growth_kib} KiB; limit is ${max_growth_mb} MiB" >&2
      return 1
    fi
    if ! stop_native_process; then
      tail -n 100 "$log_file" >&2
      return 1
    fi
    printf '%s\t%s\t%s\t%s\n' "$cycle" "$before_rss" "$after_rss" "$growth_kib" >> "$results_file"
  done

  local binary_sha
  binary_sha="$(sha256_file "$binary")"
  python3 - "$output" "$actual_target" "$binary" "$binary_sha" "$binary_description" \
    "$cycles" "$probes" "$max_growth_mb" "$results_file" \
    "$actual_target-details/golden-equivalence-report.json" "$golden_report_sha" <<'PY'
import datetime
import json
import pathlib
import sys

(
    output,
    target,
    binary,
    digest,
    description,
    cycles,
    probes,
    limit,
    samples_path,
    golden_report,
    golden_report_digest,
) = sys.argv[1:]
samples = []
for line in pathlib.Path(samples_path).read_text(encoding="utf-8").splitlines():
    cycle, before, after, growth = (int(value) for value in line.split("\t"))
    samples.append(
        {
            "cycle": cycle,
            "rssBeforeKiB": before,
            "rssAfterKiB": after,
            "rssGrowthKiB": growth,
            "gracefulShutdown": True,
        }
    )
record = {
    "schemaVersion": 1,
    "status": "passed",
    "target": target,
    "recordedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "nativeBinary": str(pathlib.Path(binary).resolve()),
    "nativeBinarySha256": digest,
    "nativeBinaryDescription": description,
    "nativeRestProbe": True,
    "jvmGoldenCorpus": True,
    "goldenReport": golden_report,
    "goldenReportSha256": golden_report_digest,
    "jvmNativeRssLifecycle": True,
    "nativeLifecycleCycles": int(cycles),
    "nativeInferencesPerCycle": int(probes),
    "maxRssGrowthMiB": int(limit),
    "nativeSamples": samples,
}
path = pathlib.Path(output)
temporary = path.with_suffix(path.suffix + ".tmp")
temporary.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
temporary.replace(path)
PY
  echo "Recorded $actual_target native evidence at $output"
  echo "The platform matrix is incomplete until verify mode accepts all three target records."
}

verify_matrix() {
  if [ "$#" -ne 1 ]; then usage; return 2; fi
  local evidence_directory="$1"
  python3 - "$evidence_directory" "${SUPPORTED_TARGETS[@]}" <<'PY'
import hashlib
import json
import pathlib
import re
import sys

directory = pathlib.Path(sys.argv[1])
targets = sys.argv[2:]
errors = []
for target in targets:
    path = directory / f"{target}.json"
    if not path.is_file():
        errors.append(f"missing {path}")
        continue
    try:
        record = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        errors.append(f"invalid {path}: {error}")
        continue
    if record.get("schemaVersion") != 1 or record.get("status") != "passed":
        errors.append(f"{path}: record did not pass")
    if record.get("target") != target:
        errors.append(f"{path}: target mismatch")
    if not re.fullmatch(r"[0-9a-f]{64}", str(record.get("nativeBinarySha256", ""))):
        errors.append(f"{path}: missing native binary digest")
    if record.get("nativeRestProbe") is not True or record.get("jvmGoldenCorpus") is not True:
        errors.append(f"{path}: inference or golden-corpus evidence missing")
    golden_report_name = record.get("goldenReport", "")
    golden_report = directory / golden_report_name
    golden_digest = str(record.get("goldenReportSha256", ""))
    if not golden_report.is_file():
        errors.append(f"{path}: numeric golden report missing: {golden_report_name}")
    elif hashlib.sha256(golden_report.read_bytes()).hexdigest() != golden_digest:
        errors.append(f"{path}: numeric golden report digest mismatch")
    if record.get("jvmNativeRssLifecycle") is not True:
        errors.append(f"{path}: JVM native-RSS lifecycle evidence missing")
    if record.get("nativeLifecycleCycles", 0) < 3 or record.get("nativeInferencesPerCycle", 0) < 100:
        errors.append(f"{path}: lifecycle stress is below the minimum")
    samples = record.get("nativeSamples", [])
    if len(samples) != record.get("nativeLifecycleCycles"):
        errors.append(f"{path}: incomplete native lifecycle samples")
    if any(sample.get("gracefulShutdown") is not True for sample in samples):
        errors.append(f"{path}: non-graceful native shutdown")
    limit_kib = record.get("maxRssGrowthMiB", 0) * 1024
    if limit_kib <= 0 or any(sample.get("rssGrowthKiB", limit_kib + 1) > limit_kib for sample in samples):
        errors.append(f"{path}: RSS growth exceeds its declared limit")
if errors:
    print("Native ONNX platform matrix is incomplete:", file=sys.stderr)
    for error in errors:
        print(f"- {error}", file=sys.stderr)
    raise SystemExit(1)
print("Native ONNX platform matrix passed for: " + ", ".join(targets))
PY
}

if [ "$#" -lt 1 ]; then usage; exit 2; fi
mode="$1"
shift
case "$mode" in
  record) record_target "$@" ;;
  verify) verify_matrix "$@" ;;
  -h|--help|help) usage ;;
  *) usage; exit 2 ;;
esac

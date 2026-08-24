#!/usr/bin/env bash
# Run one native distribution backend with isolated defaults.
#
# The backend-specific scripts are intentionally thin adapters around this
# file.  Keeping the lifecycle here makes the Bash and PowerShell entry points
# obey the same build, validation, and cleanup rules.

set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/run_dist_{sqlite,pg,fs}.sh [command] [options] [command args...]

Commands:
  serve                 Run the native server (default)
  chat | attach | ask   Pass a CLI command to the native binary

Options:
  --build, -b           Rebuild the selected native binary before running
  --validate, -v        Run the REST validation suite
  --fast                Run the fast validation suite
  --full                Run the full validation suite (default for --validate)
  --onnx                Enable and validate the local ONNX bundle
  --dry-run             Print the resolved execution plan without side effects
  --help, -h            Show this help

Validation can target an already-running server with TEST_BASE.  Otherwise
the wrapper owns a server on AMSHA_VALIDATION_PORT (8080 by default).
EOF
}

die() {
  printf '❌ %s\n' "$1" >&2
  exit 1
}

[[ $# -ge 1 ]] || { usage >&2; exit 2; }

BACKEND="$1"
shift

case "$BACKEND" in
  sqlite)
    BINARY_NAME="amsha-sqlite"
    BUILD_TASK="build:native-local"
    PERSISTENCE_BACKEND="sqlite"
    BACKEND_PROFILE="prod"
    ;;
  postgres)
    BINARY_NAME="amsha-pg"
    BUILD_TASK="build:native-local-pg"
    PERSISTENCE_BACKEND="postgres"
    BACKEND_PROFILE="prod,postgresql"
    ;;
  fs)
    BINARY_NAME="amsha-fs"
    BUILD_TASK="build:native-local-fs"
    PERSISTENCE_BACKEND="fs"
    BACKEND_PROFILE="prod,filesystem"
    ;;
  *)
    die "Unknown distribution backend '$BACKEND' (expected sqlite, postgres, or fs)."
    ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BINARY="$PROJECT_ROOT/dist/$BINARY_NAME"
RUNNER="$PROJECT_ROOT/app/target/amsha-app-0.1.0-SNAPSHOT-runner"
if [[ -n "${AMSHA_DIST_BINARY:-}" ]]; then
  BINARY="$AMSHA_DIST_BINARY"
fi
VALIDATION_SCRIPT="${AMSHA_VALIDATION_SCRIPT:-$PROJECT_ROOT/rest-http/scripts/run_all_validations.sh}"

AUTO_BUILD=false
VALIDATE_MODE=false
FAST_MODE=false
ONNX_REQUESTED=false
DRY_RUN=false
COMMAND="serve"
COMMAND_SET=false
PASSTHROUGH_ARGS=()
VALIDATE_ARGS=()

while [[ $# -gt 0 ]]; do
  arg="$1"
  shift
  case "$arg" in
    --build|-b)
      AUTO_BUILD=true
      ;;
    --validate|-v|validate|test)
      VALIDATE_MODE=true
      ;;
    --fast)
      VALIDATE_MODE=true
      FAST_MODE=true
      ;;
    --full|--validate-full)
      VALIDATE_MODE=true
      FAST_MODE=false
      ;;
    --onnx)
      ONNX_REQUESTED=true
      VALIDATE_ARGS+=("--onnx")
      ;;
    --dry-run)
      DRY_RUN=true
      ;;
    --help|-h|help)
      usage
      exit 0
      ;;
    --)
      PASSTHROUGH_ARGS+=("$@")
      break
      ;;
    *)
      if [[ "$COMMAND_SET" == false && "$arg" != -* ]]; then
        COMMAND="$arg"
        COMMAND_SET=true
      else
        PASSTHROUGH_ARGS+=("$arg")
      fi
      ;;
  esac
done

if [[ "$VALIDATE_MODE" == true && "$FAST_MODE" == true ]]; then
  VALIDATION_MODE="fast"
  EXISTING_VALIDATE_ARGS=()
  if [[ ${#VALIDATE_ARGS[@]} -gt 0 ]]; then
    EXISTING_VALIDATE_ARGS=("${VALIDATE_ARGS[@]}")
  fi
  VALIDATE_ARGS=("--fast")
  if [[ ${#EXISTING_VALIDATE_ARGS[@]} -gt 0 ]]; then
    VALIDATE_ARGS+=("${EXISTING_VALIDATE_ARGS[@]}")
  fi
elif [[ "$VALIDATE_MODE" == true ]]; then
  VALIDATION_MODE="full"
else
  VALIDATION_MODE="none"
fi

PROCESS_ENV_KEYS_TEXT=$'\n'
while IFS= read -r env_key; do
  PROCESS_ENV_KEYS_TEXT="${PROCESS_ENV_KEYS_TEXT}${env_key}"$'\n'
done < <(compgen -e)

is_process_env_key() {
  case "$PROCESS_ENV_KEYS_TEXT" in
    *$'\n'"$1"$'\n'*) return 0 ;;
    *) return 1 ;;
  esac
}

load_env_file() {
  local env_file="$1"
  local allow_shared_data_dir="${2:-true}"
  local line key value

  [[ -f "$env_file" ]] || return 0

  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -z "$line" || "$line" == \#* ]] && continue
    if [[ "$line" == export[[:space:]]* ]]; then
      line="$(trim_value "${line#export}")"
    fi
    [[ "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*=(.*)$ ]] || continue

    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    if [[ ${#value} -ge 2 ]]; then
    if [[ "${value:0:1}" == '"' && "${value: -1}" == '"' ]]; then
      value="${value:1:${#value}-2}"
      elif [[ "${value:0:1}" == "'" && "${value: -1}" == "'" ]]; then
        value="${value:1:${#value}-2}"
      fi
    fi

    if [[ "$key" == AMSHA_DATA_DIR && "$allow_shared_data_dir" == false ]]; then
      continue
    fi

    # Never evaluate the value.  In particular, $(...) and backticks remain
    # literal text.  Environment variables supplied by the caller win over
    # every file, while later files may override earlier file defaults.
    if ! is_process_env_key "$key"; then
      export "$key=$value"
    fi
  done < "$env_file"
}

resolve_env_file_path() {
  local env_file="$1"
  if [[ "$env_file" != /* ]]; then
    env_file="$PROJECT_ROOT/$env_file"
  fi
  printf '%s' "$env_file"
}

# Match the existing distribution precedence: backend-specific defaults, the
# distribution defaults, then the repository .env. The process environment was
# snapshotted above, so it remains authoritative over every file.
load_env_file "$PROJECT_ROOT/dist/.env.$BACKEND"
load_env_file "$PROJECT_ROOT/dist/.env" false
load_env_file "$PROJECT_ROOT/.env" false
if [[ -n "${AMSHA_ENV_FILE:-}" ]]; then
  load_env_file "$(resolve_env_file_path "$AMSHA_ENV_FILE")"
fi

if [[ -z "${AMSHA_DATA_DIR:-}" ]]; then
  AMSHA_DATA_DIR="$PROJECT_ROOT/dist/data-$BACKEND"
elif [[ "$AMSHA_DATA_DIR" != /* ]]; then
  AMSHA_DATA_DIR="$PROJECT_ROOT/$AMSHA_DATA_DIR"
fi
export AMSHA_DATA_DIR

# A wrapper invocation must never silently run a different persistence
# backend because a shared .env file happened to contain another value.
export AMSHA_PERSISTENCE_BACKEND="$PERSISTENCE_BACKEND"
export QUARKUS_PROFILE="$BACKEND_PROFILE"
export AMSHA_TEST_DATA_COLLECTION_ENABLED="${AMSHA_TEST_DATA_COLLECTION_ENABLED:-true}"

if [[ "$VALIDATE_MODE" == true ]]; then
  # Validation launches the server with stdin closed; never enter the
  # interactive first-run setup in that mode.
  export AMSHA_UI_SKIP_ONBOARDING=true
fi
if [[ "$ONNX_REQUESTED" == false ]] && ! is_process_env_key "AMSHA_ONNX_ENABLED"; then
  # A file-level ONNX opt-in must not make ordinary validation download or
  # initialize a model. The caller can still explicitly opt in with --onnx.
  export AMSHA_ONNX_ENABLED=false
fi
if [[ "$VALIDATE_MODE" == true ]] && [[ "$ONNX_REQUESTED" == false ]] \
  && ! is_process_env_key "AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS"; then
  # Keep fast validation independent of a file-level ONNX embedding choice.
  # Jina uses the native-compatible OpenAI adapter and is only constructed at
  # startup; validation does not make an embedding request unless a suite
  # explicitly exercises embedding inference.
  export AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS=jina
fi

if [[ "$BACKEND" == fs ]]; then
  export QUARKUS_FLYWAY_ENABLED="${QUARKUS_FLYWAY_ENABLED:-false}"
  export AMSHA_FS_DATA_DIR="${AMSHA_FS_DATA_DIR:-$AMSHA_DATA_DIR/filesystem}"
  export AMSHA_FS_LUCENE_INDEX_DIR="${AMSHA_FS_LUCENE_INDEX_DIR:-$AMSHA_DATA_DIR/lucene-index}"
fi

if [[ -z "${AMSHA_RAG_VEC_PATH:-}" ]]; then
  if [[ -d "$PROJECT_ROOT/dist/lib/vec0" ]]; then
    AMSHA_RAG_VEC_PATH="$PROJECT_ROOT/dist/lib/vec0"
    export AMSHA_RAG_VEC_PATH
  elif [[ -d "$PROJECT_ROOT/lib/vec0" ]]; then
    AMSHA_RAG_VEC_PATH="$PROJECT_ROOT/lib/vec0"
    export AMSHA_RAG_VEC_PATH
  fi
fi

configure_onnx() {
  export AMSHA_ONNX_ENABLED=true
  if [[ -n "${AMSHA_ONNX_MODEL_ROOT:-}" && "$AMSHA_ONNX_MODEL_ROOT" != /* ]]; then
    AMSHA_ONNX_MODEL_ROOT="$PROJECT_ROOT/$AMSHA_ONNX_MODEL_ROOT"
    export AMSHA_ONNX_MODEL_ROOT
  fi
  if [[ -z "${AMSHA_ONNX_MODEL_ROOT:-}" || ! -d "$AMSHA_ONNX_MODEL_ROOT" ]]; then
    if [[ -d "$PROJECT_ROOT/data/models" ]]; then
      AMSHA_ONNX_MODEL_ROOT="$PROJECT_ROOT/data/models"
    elif [[ -d "$PROJECT_ROOT/dist/data/models" ]]; then
      AMSHA_ONNX_MODEL_ROOT="$PROJECT_ROOT/dist/data/models"
    else
      AMSHA_ONNX_MODEL_ROOT="$PROJECT_ROOT/data/models"
    fi
    export AMSHA_ONNX_MODEL_ROOT
  fi

  local model_file="$AMSHA_ONNX_MODEL_ROOT/all-mpnet-base-v2-e8c3b32/model.onnx"
  if [[ ! -f "$model_file" ]]; then
    printf '📥 Installing the pinned ONNX bundle because --onnx was requested.\n'
    (cd "$PROJECT_ROOT" && mise run models:install-mpnet)
  fi
  [[ -f "$model_file" ]] || die "ONNX bundle is unavailable at $model_file."
}

build_native() {
  printf '🔨 Building %s native binary...\n' "$BACKEND"
  (cd "$PROJECT_ROOT" && mise run "$BUILD_TASK")
  [[ -f "$RUNNER" ]] || die "Native build did not produce $RUNNER."
  mkdir -p "$(dirname "$BINARY")"
  cp "$RUNNER" "$BINARY"
  chmod +x "$BINARY"
}

print_plan() {
  local validation_args=""
  if [[ ${#VALIDATE_ARGS[@]} -gt 0 ]]; then
    validation_args="${VALIDATE_ARGS[*]}"
  fi
  printf 'backend=%s\n' "$BACKEND"
  printf 'binary=%s\n' "$BINARY"
  printf 'command=%s\n' "$COMMAND"
  printf 'data_dir=%s\n' "$AMSHA_DATA_DIR"
  printf 'persistence_backend=%s\n' "$AMSHA_PERSISTENCE_BACKEND"
  printf 'quarkus_profile=%s\n' "$QUARKUS_PROFILE"
  printf 'validation_mode=%s\n' "$VALIDATION_MODE"
  printf 'validation_args=%s\n' "$validation_args"
  printf 'validation_base=%s\n' "${TEST_BASE:-http://127.0.0.1:${AMSHA_VALIDATION_PORT:-8080}}"
  printf 'build=%s\n' "$AUTO_BUILD"
  printf 'onnx=%s\n' "$ONNX_REQUESTED"
  printf 'embedding_providers=%s\n' "${AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS:-}"
  if [[ "$BACKEND" == fs ]]; then
    printf 'fs_data_dir=%s\n' "$AMSHA_FS_DATA_DIR"
    printf 'fs_lucene_index_dir=%s\n' "$AMSHA_FS_LUCENE_INDEX_DIR"
  fi
}

if [[ "$DRY_RUN" == true ]]; then
  print_plan
  exit 0
fi

if [[ "$ONNX_REQUESTED" == true ]]; then
  configure_onnx
fi

# TEST_BASE means that another process owns the server.  This is useful for
# migration smoke tests and prevents the wrapper from touching port 8080 or a
# shared database while validating an existing runtime.
if [[ "$VALIDATE_MODE" == true && -n "${TEST_BASE:-}" && "$AUTO_BUILD" == false ]]; then
  (cd "$PROJECT_ROOT" && bash "$VALIDATION_SCRIPT" "${VALIDATE_ARGS[@]}")
  exit $?
fi

if [[ "$AUTO_BUILD" == true ]]; then
  build_native
elif [[ ! -f "$BINARY" ]]; then
  die "Native binary not found at $BINARY. Run mise run $BUILD_TASK or pass --build."
fi

chmod +x "$BINARY" 2>/dev/null || true
mkdir -p "$AMSHA_DATA_DIR"
if [[ "$BACKEND" == fs ]]; then
  mkdir -p "$AMSHA_FS_DATA_DIR" "$AMSHA_FS_LUCENE_INDEX_DIR"
fi
if [[ "$(uname -s)" == Linux && -f "/lib/x86_64-linux-gnu/libm.so.6" ]]; then
  export LD_PRELOAD="/lib/x86_64-linux-gnu/libm.so.6${LD_PRELOAD:+:$LD_PRELOAD}"
fi

if [[ "$VALIDATE_MODE" == true ]]; then
  validation_port="${AMSHA_VALIDATION_PORT:-8080}"
  [[ "$validation_port" =~ ^[0-9]+$ ]] || die "AMSHA_VALIDATION_PORT must be numeric."

  if [[ -n "${TEST_BASE:-}" ]]; then
    (cd "$PROJECT_ROOT" && bash "$VALIDATION_SCRIPT" "${VALIDATE_ARGS[@]}")
    exit $?
  fi

  if command -v lsof >/dev/null 2>&1 && lsof -nP -iTCP:"$validation_port" -sTCP:LISTEN >/dev/null 2>&1; then
    die "Validation port $validation_port is already in use; set TEST_BASE or choose another AMSHA_VALIDATION_PORT."
  fi

  validation_base="http://127.0.0.1:$validation_port"
  export QUARKUS_HTTP_PORT="$validation_port"
  log_file="${AMSHA_VALIDATION_LOG_FILE:-${TMPDIR:-/tmp}/amsha-${BACKEND}-validation.$$.log}"
  printf '🚀 Starting %s validation server on %s.\n' "$BACKEND" "$validation_base"
  printf '📄 Server logs: %s\n' "$log_file"
  "$BINARY" serve < /dev/null > "$log_file" 2>&1 &
  server_pid=$!

  cleanup() {
    local exit_code=$?
    trap - EXIT INT TERM
    if kill -0 "$server_pid" 2>/dev/null; then
      kill -TERM "$server_pid" 2>/dev/null || true
      wait "$server_pid" 2>/dev/null || true
    fi
    exit "$exit_code"
  }
  trap cleanup EXIT INT TERM

  ready=false
  for _ in $(seq 1 60); do
    if curl -fsS -m 2 -o /dev/null "$validation_base/api/v1/info" \
      || curl -fsS -m 2 -o /dev/null "$validation_base/q/health/live"; then
      ready=true
      break
    fi
    if ! kill -0 "$server_pid" 2>/dev/null; then
      die "Validation server exited before becoming ready; inspect $log_file."
    fi
    sleep 1
  done
  [[ "$ready" == true ]] || die "Validation server did not become ready; inspect $log_file."

  export TEST_BASE="$validation_base"
  (cd "$PROJECT_ROOT" && bash "$VALIDATION_SCRIPT" "${VALIDATE_ARGS[@]}")
  exit $?
fi

printf '🚀 Launching Amsha (%s): %s %s' "$BACKEND" "$BINARY" "$COMMAND"
if [[ ${#PASSTHROUGH_ARGS[@]} -gt 0 ]]; then
  printf ' %q' "${PASSTHROUGH_ARGS[@]}"
fi
printf '\n'
printf '📂 Persistence: %s (profile: %s)\n' "$AMSHA_PERSISTENCE_BACKEND" "$QUARKUS_PROFILE"
printf '📁 Data directory: %s\n' "$AMSHA_DATA_DIR"
exec "$BINARY" "$COMMAND" "${PASSTHROUGH_ARGS[@]}"

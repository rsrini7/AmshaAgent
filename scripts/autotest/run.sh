#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/../lib/repo_root.sh" ]; then
  # shellcheck source=../lib/repo_root.sh
  source "$SCRIPT_DIR/../lib/repo_root.sh"
  REPO_ROOT="$(get_repo_root "$SCRIPT_DIR")"
else
  REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
fi
cd "$REPO_ROOT"

BACKEND="sqlite"
SUITE="telegram"
ROWS="all"
PORT="8999"
EXTRA_ARGS=()

usage() {
  cat <<'EOF'
Usage: scripts/autotest/run.sh [options] [extra autotest.py args...]
       scripts/autotest.sh [options] [extra autotest.py args...]

Options:
  --backend <sqlite|pg|fs>         Storage backend (default: sqlite)
  --suite <telegram|skillsjars|loka>
                                   Test suite to run (default: telegram)
  --rows <range>                   Row range to run (e.g. 1-20, default: all)
  --port <port>                    Port for autotest harness (default: 8999)
  -h, --help                       Show this help message

Examples:
  scripts/autotest/run.sh --backend sqlite
  scripts/autotest/run.sh --backend pg --rows 1-20
  scripts/autotest/run.sh --suite skillsjars
  scripts/autotest/run.sh --suite loka
EOF
  exit 0
}

while [ $# -gt 0 ]; do
  case "$1" in
    --backend)
      BACKEND="${2:-}"
      shift 2
      ;;
    --backend=*)
      BACKEND="${1#*=}"
      shift
      ;;
    --suite)
      SUITE="${2:-}"
      shift 2
      ;;
    --suite=*)
      SUITE="${1#*=}"
      shift
      ;;
    --rows|-r)
      ROWS="${2:-}"
      shift 2
      ;;
    --rows=*|-r=*)
      ROWS="${1#*=}"
      shift
      ;;
    --port)
      PORT="${2:-}"
      shift 2
      ;;
    --port=*)
      PORT="${1#*=}"
      shift
      ;;
    -h|--help)
      usage
      ;;
    *)
      EXTRA_ARGS+=("$1")
      shift
      ;;
  esac
done

case "$BACKEND" in
  sqlite|pg|fs) ;;
  *)
    echo "Error: Unknown backend '$BACKEND'. Allowed: sqlite, pg, fs" >&2
    exit 1
    ;;
esac

case "$SUITE" in
  telegram)
    INPUT_FILE="$SCRIPT_DIR/files/telegram-test-amsha.txt"
    if [ "$BACKEND" = "sqlite" ]; then
      RESULTS_FILE="$SCRIPT_DIR/logs/results.json"
      LOG_FILE="$SCRIPT_DIR/logs/run.log"
    else
      RESULTS_FILE="$SCRIPT_DIR/logs/results-${BACKEND}.json"
      LOG_FILE="$SCRIPT_DIR/logs/run-${BACKEND}.log"
    fi
    ;;
  skillsjars)
    INPUT_FILE="$SCRIPT_DIR/files/skillsjars-test.txt"
    RESULTS_FILE="$SCRIPT_DIR/logs/results-skillsjars.json"
    LOG_FILE="$SCRIPT_DIR/logs/run-skillsjars.log"
    ;;
  loka)
    INPUT_FILE="$SCRIPT_DIR/files/loka-connectivity-test.txt"
    RESULTS_FILE="$SCRIPT_DIR/logs/results-loka.json"
    LOG_FILE="$SCRIPT_DIR/logs/run-loka.log"
    ;;
  *)
    echo "Error: Unknown suite '$SUITE'. Allowed: telegram, skillsjars, loka" >&2
    exit 1
    ;;
esac

CMD=(python3 "$SCRIPT_DIR/autotest.py")
if [ -n "$ROWS" ] && [ "$ROWS" != "all" ]; then
  CMD+=(-r "$ROWS")
fi
CMD+=(-f "$INPUT_FILE" -j "$RESULTS_FILE" -l "$LOG_FILE" -v --port "$PORT" --backend "$BACKEND")
if [ ${#EXTRA_ARGS[@]} -gt 0 ]; then
  CMD+=("${EXTRA_ARGS[@]}")
fi

if [ "${AUTOTEST_DRY_RUN:-false}" = "true" ]; then
  printf 'AUTOTEST_DRY_RUN=true\n'
  printf 'BACKEND=%s\n' "$BACKEND"
  printf 'SUITE=%s\n' "$SUITE"
  printf 'ROWS=%s\n' "$ROWS"
  printf 'PORT=%s\n' "$PORT"
  printf 'INPUT_FILE=%s\n' "$INPUT_FILE"
  printf 'RESULTS_FILE=%s\n' "$RESULTS_FILE"
  printf 'LOG_FILE=%s\n' "$LOG_FILE"
  printf 'TARGET_SCRIPT=%s\n' "$SCRIPT_DIR/autotest.py"
  printf 'EXTRA_ARGS=%s\n' "${EXTRA_ARGS[*]:-}"
  printf 'COMMAND=%s\n' "${CMD[*]}"
  exit 0
fi

mkdir -p "$SCRIPT_DIR/logs"
exec "${CMD[@]}"

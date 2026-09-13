#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/../scripts/lib/repo_root.sh" ]; then
  # shellcheck source=../scripts/lib/repo_root.sh
  source "$SCRIPT_DIR/../scripts/lib/repo_root.sh"
  REPO_ROOT="$(get_repo_root "$SCRIPT_DIR")"
else
  REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
fi
cd "$REPO_ROOT"

BACKEND="sqlite"
SUITE="telegram"
ROWS=""
PORT="8999"
EXTRA_ARGS=()

usage() {
  cat <<'EOF'
Usage: autotest/run.sh [options] [extra autotest.py args...]

Options:
  --backend <sqlite|pg|fs>         Storage backend (default: sqlite)
  --suite <telegram|skillsjars|loka>
                                   Test suite to run (default: telegram)
  --rows <range>                   Row range to run (e.g. 1-20)
  --port <port>                    Port for autotest harness (default: 8999)
  -h, --help                       Show this help message

Examples:
  autotest/run.sh --backend sqlite
  autotest/run.sh --backend pg --rows 1-20
  autotest/run.sh --suite skillsjars
  autotest/run.sh --suite loka
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
    INPUT_FILE="autotest/files/telegram-test-amsha.txt"
    RESULTS_FILE="autotest/logs/results-${BACKEND}.json"
    LOG_FILE="autotest/logs/run-${BACKEND}.log"
    ;;
  skillsjars)
    INPUT_FILE="autotest/files/skillsjars-test.txt"
    RESULTS_FILE="autotest/logs/results-skillsjars.json"
    LOG_FILE="autotest/logs/run-skillsjars.log"
    ;;
  loka)
    INPUT_FILE="autotest/files/loka-connectivity-test.txt"
    RESULTS_FILE="autotest/logs/results-loka.json"
    LOG_FILE="autotest/logs/run-loka.log"
    ;;
  *)
    echo "Error: Unknown suite '$SUITE'. Allowed: telegram, skillsjars, loka" >&2
    exit 1
    ;;
esac

mkdir -p autotest/logs

CMD=(python3 autotest/autotest.py)
if [ -n "$ROWS" ]; then
  CMD+=(-r "$ROWS")
fi
CMD+=(-f "$INPUT_FILE" -j "$RESULTS_FILE" -l "$LOG_FILE" -v --port "$PORT" --backend "$BACKEND")
if [ ${#EXTRA_ARGS[@]} -gt 0 ]; then
  CMD+=("${EXTRA_ARGS[@]}")
fi

exec "${CMD[@]}"

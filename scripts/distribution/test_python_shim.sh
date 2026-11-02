#!/usr/bin/env bash
# scripts/distribution/test_python_shim.sh — Test suite for python_shim.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# shellcheck source=../lib/python_shim.sh
source "$REPO_ROOT/scripts/lib/python_shim.sh"

echo "=== Testing ensure_python3_shim ==="
ensure_python3_shim
if ! command -v python3 >/dev/null 2>&1; then
  echo "FAIL: python3 command not found after ensure_python3_shim"
  exit 1
fi
echo "PASS: python3 is available at $(command -v python3)"

# Verify version check works
python3 --version >/dev/null
echo "PASS: python3 --version succeeded"

# Verify idempotency
ensure_python3_shim
echo "PASS: ensure_python3_shim is idempotent"

# Verify /tmp path translation in -c code string
TEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/test_shim.XXXXXX")"
TEST_FILE="$TEST_DIR/sample.json"
echo '{"message":"hello_shim","id":42}' > "$TEST_FILE"

READ_MSG="$(python3 -c "import json; print(json.load(open('$TEST_FILE')).get('message',''))" 2>/dev/null)"
if [[ "$READ_MSG" != "hello_shim" ]]; then
  echo "FAIL: expected 'hello_shim', got '$READ_MSG'"
  rm -rf "$TEST_DIR"
  exit 1
fi
echo "PASS: /tmp path in code string translated successfully: $READ_MSG"

# Verify /tmp path as positional argument
READ_ID="$(python3 - "$TEST_FILE" <<'PY'
import sys, json
data = json.load(open(sys.argv[1]))
print(data.get('id', ''))
PY
)"
if [[ "$READ_ID" != "42" ]]; then
  echo "FAIL: expected '42', got '$READ_ID'"
  rm -rf "$TEST_DIR"
  exit 1
fi
echo "PASS: /tmp positional argument translated successfully: $READ_ID"

# Verify /tmp/resp hardcoded path translation
echo '{"key":"secret"}' > /tmp/resp_test_shim
READ_KEY="$(python3 -c "import json; print(json.load(open('/tmp/resp_test_shim')).get('key',''))" 2>/dev/null)"
if [[ "$READ_KEY" != "secret" ]]; then
  echo "FAIL: expected 'secret', got '$READ_KEY'"
  rm -rf "$TEST_DIR" /tmp/resp_test_shim
  exit 1
fi
echo "PASS: hardcoded /tmp path translated successfully: $READ_KEY"

# Clean up
rm -rf "$TEST_DIR" /tmp/resp_test_shim
echo "ALL PYTHON SHIM TESTS PASSED!"

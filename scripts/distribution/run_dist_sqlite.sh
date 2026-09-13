#!/usr/bin/env bash
# SQLite distribution runner.  Lifecycle logic lives in run_dist_backend.sh.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$SCRIPT_DIR/run_dist_backend.sh" sqlite "$@"

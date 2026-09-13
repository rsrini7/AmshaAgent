#!/usr/bin/env bash
# PostgreSQL distribution runner.  Lifecycle logic lives in run_dist_backend.sh.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$SCRIPT_DIR/run_dist_backend.sh" postgres "$@"

#!/usr/bin/env bash
#MISE description="Run native platform matrix checks (shuts down gracefully, RSS limits)"
set -euo pipefail

exec bash scripts/verify_native_matrix.sh "$@"

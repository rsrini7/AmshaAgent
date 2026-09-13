#!/usr/bin/env bash
set -euo pipefail

# rebrand.sh (Retired)
#
# The one-time ArcClaw -> Amsha Agent repository migration has already been
# completed. This script is retained as a safety tombstone to inform callers
# and prevent accidental re-execution against the migrated Amsha codebase.

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    echo "rebrand.sh is retired."
    echo "The ArcClaw -> Amsha Agent repository migration has already been completed."
    echo "This script cannot and should not be re-run."
    exit 0
fi

echo "ERROR: rebrand.sh is retired. The ArcClaw -> Amsha Agent repository migration has already been completed and cannot be run again." >&2
exit 1

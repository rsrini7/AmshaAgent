#!/usr/bin/env bash
#MISE description = "Verify Markdown documentation links and anchors across the workspace"
set -euo pipefail
exec python3 scripts/verify_docs.py "$@"

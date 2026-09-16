#!/usr/bin/env bash
#MISE description = "Review staged changes read-only without modifying the Git index"
set -euo pipefail
exec bash scripts/review_staged.sh "$@"

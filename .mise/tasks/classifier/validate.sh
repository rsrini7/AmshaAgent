#!/usr/bin/env bash
#MISE description="Validate one Phase 2 classifier dataset; pass --release for approval and size gates"

set -euo pipefail
exec bash scripts/classifier_training.sh validate "$@"

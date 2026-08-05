#!/usr/bin/env bash
#MISE description="Validate one shadow classifier dataset; pass --release for approval and size gates"

set -euo pipefail
exec bash scripts/classifier_training.sh validate "$@"

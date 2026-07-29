#!/usr/bin/env bash
#MISE description="Validate the pending Phase 2 classifier seed review packets"

set -euo pipefail
exec bash scripts/classifier_training.sh validate-seeds "$@"

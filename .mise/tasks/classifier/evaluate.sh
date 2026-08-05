#!/usr/bin/env bash
#MISE description="Evaluate and calibrate one trained Phase 2 shadow classifier"

set -euo pipefail
exec bash scripts/classifier_training.sh evaluate "$@"

#!/usr/bin/env bash
#MISE description="Evaluate and calibrate one trained shadow classifier"

set -euo pipefail
exec bash scripts/classifier_training.sh evaluate "$@"

#!/usr/bin/env bash
#MISE description="Train one approved Phase 2 MiniLM shadow classifier"

set -euo pipefail
exec bash scripts/classifier_training.sh train "$@"

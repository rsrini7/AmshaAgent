#!/usr/bin/env bash
#MISE description="Train one approved MiniLM shadow classifier"

set -euo pipefail
exec bash scripts/classifier_training.sh train "$@"

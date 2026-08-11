#!/usr/bin/env bash
#MISE description="Export a shadow classifier JSONL packet to human-review CSV"

set -euo pipefail
exec bash scripts/classifier_training.sh export-review "$@"

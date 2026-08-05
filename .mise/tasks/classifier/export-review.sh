#!/usr/bin/env bash
#MISE description="Export a Phase 2 classifier JSONL packet to human-review CSV"

set -euo pipefail
exec bash scripts/classifier_training.sh export-review "$@"

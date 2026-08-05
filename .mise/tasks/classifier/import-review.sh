#!/usr/bin/env bash
#MISE description="Import a reviewed classifier CSV to versioned JSONL"

set -euo pipefail
exec bash scripts/classifier_training.sh import-review "$@"

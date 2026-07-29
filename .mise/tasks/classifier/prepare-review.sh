#!/usr/bin/env bash
#MISE description="De-identify classifier JSONL and create a pending human-review packet"

set -euo pipefail
exec bash scripts/classifier_training.sh prepare-review "$@"

#!/usr/bin/env bash
#MISE description="Install and verify the pinned MiniLM classifier training base"

set -euo pipefail
exec bash scripts/classifier_training.sh install-base "$@"

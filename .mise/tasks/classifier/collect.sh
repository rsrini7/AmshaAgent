#!/usr/bin/env bash
set -euo pipefail
exec bash scripts/classifier_training.sh collect "$@"

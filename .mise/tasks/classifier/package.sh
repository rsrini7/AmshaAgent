#!/usr/bin/env bash
#MISE description="Package a passing classifier as a verified local ONNX bundle"

set -euo pipefail
exec bash scripts/classifier_training.sh package "$@"

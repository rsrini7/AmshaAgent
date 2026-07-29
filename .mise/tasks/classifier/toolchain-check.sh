#!/usr/bin/env bash
#MISE description="Offline smoke test the pinned MiniLM training and ONNX export toolchain"

set -euo pipefail
exec bash scripts/classifier_training.sh toolchain-check "$@"

#!/bin/bash
set -e
export ONNX_MAX_RSS_GROWTH_MB=128
for i in {1..10}; do
  echo "--- RUN $i ---"
  export AMSHA_TELEGRAM_ENABLED=false
  bash verify_linux_podman.sh linux-arm64 || true
done

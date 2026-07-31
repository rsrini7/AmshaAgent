#!/usr/bin/env bash
# Wrapper to run verify_native_matrix.sh for Linux targets inside a Podman container.

set -euo pipefail

cleanup() {
  echo "🧹 Cleaning up eclipse-temurin:25-jdk image..."
  podman rmi eclipse-temurin:25-jdk || true
  echo "✅ Cleanup complete!"
}
trap cleanup EXIT

if [ "$#" -lt 1 ]; then
  echo "Usage: scripts/verify_linux_podman.sh <target> [native-binary] [evidence.json]"
  echo "Example: scripts/verify_linux_podman.sh linux-arm64"
  exit 1
fi

TARGET="$1"
BINARY="${2:-dist/amsha-sqlite}"
EVIDENCE="${3:-data/smoke/matrix-${TARGET}.json}"

echo "🚀 Starting Podman container for $TARGET verification..."
podman run --rm -v "$(pwd):/workspace" --tmpfs /tmp -w /workspace eclipse-temurin:25-jdk bash -c "
  echo '📦 Installing required utilities in container...'
  apt-get update -qq && apt-get install -y -qq python3 curl jq bc file gdb
  
  echo '🧪 Running matrix tests for $TARGET...'
  export AMSHA_TOOLS_PLAYWRIGHT_ENABLED=false
  export OMP_NUM_THREADS=1
  export OMP_WAIT_POLICY=PASSIVE
  export AMSHA_LD_PRELOAD_SET=1
  export MAVEN_OPTS="-Dmaven.repo.local=/tmp/m2"
  export ONNX_MAX_RSS_GROWTH_MB=128
  ./scripts/verify_native_matrix.sh record \"$TARGET\" \"$BINARY\" \"$EVIDENCE\"
"

echo "✅ Done!"

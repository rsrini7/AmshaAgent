#!/usr/bin/env bash
set -euo pipefail

export JDK_JAVA_OPTIONS="\
--add-opens=java.base/java.nio=ALL-UNNAMED \
--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
--add-opens=java.base/java.lang=ALL-UNNAMED \
--enable-native-access=ALL-UNNAMED \
-Dsun.misc.unsafe.memory.access=allow \
-Dquarkus.otel.sdk.disabled=true"

MODE="${1:-serve}"

DEV_ARGS=(
    "-Dquarkus.args=$MODE"
    "-Darcclaw.rag.vec-extension-path=../lib/vec0"
    "-Darcclaw.data-dir=../data"
)

# Auto-inject telegram enabled for attach mode
if [[ "$MODE" == "attach" ]]; then
    DEV_ARGS+=("-Darcclaw.telegram.enabled=true")
fi

if [[ $# -eq 0 ]]; then
    cat <<'EOF'

  ArcClaw Quarkus Dev Mode - Usage

  COMMAND                          DESCRIPTION
  -------------------------------------------------------
  ./arcclaw-dev.sh                 Dev mode (serve)
  ./arcclaw-dev.sh serve           Start REST daemon
  ./arcclaw-dev.sh chat            Interactive REPL
  ./arcclaw-dev.sh ask "goal"      One-shot task
  ./arcclaw-dev.sh attach          Telegram daemon

EOF
    # Fall through to serve (default)
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$SCRIPT_DIR/mvnw" quarkus:dev -pl arcclaw-app -am "${DEV_ARGS[@]}"

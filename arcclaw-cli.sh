#!/usr/bin/env bash
set -euo pipefail

JVM_ARGS=(
    "-Dquarkus.profile=cli"
    "-Dfile.encoding=UTF-8"
    "-Dstdout.encoding=UTF-8"
    "-Dsun.stdout.encoding=UTF-8"
    "-Dsun.stderr.encoding=UTF-8"
    "-Dsun.misc.unsafe.memory.access=allow"
    "--add-opens=java.base/java.nio=ALL-UNNAMED"
    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"
    "--add-opens=java.base/java.lang=ALL-UNNAMED"
    "--enable-native-access=ALL-UNNAMED"
)

# Auto-inject telegram enabled for attach mode
if [[ "${1:-}" == "attach" ]]; then
    JVM_ARGS+=("-Darcclaw.telegram.enabled=true")
fi

# Show help if no args provided
if [[ $# -eq 0 ]]; then
    cat <<'EOF'

    ___                ________
   /   |  __________  / ____/ /___ __      __
  / /| | / ___/ ___/ / /   / / __ `/ | /| / /
 / ___ |/ /  / /__  / /___/ / /_/ /| |/ |/ /
/_/  |_/_/   \___/  \____/_/\__,_/ |__/|__/

  ArcClaw Agent Kernel - Usage

  COMMAND                          DESCRIPTION
  -------------------------------------------------------
  ./arcclaw-cli.sh chat            Start interactive REPL
  ./arcclaw-cli.sh ask "goal"      One-shot task and exit
  ./arcclaw-cli.sh serve           Start REST daemon
  ./arcclaw-cli.sh attach          Start Telegram daemon

  OPTIONS
  -------------------------------------------------------
  chat --role coordinator          Override agent role
  ask "goal" --role researcher     One-shot with role

EOF
    exit 0
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/arcclaw-app/target/quarkus-app/quarkus-run.jar"

if [[ ! -f "$JAR" ]]; then
    echo "ERROR: JAR not found at $JAR"
    echo "Run: ./mvnw package -DskipTests first"
    exit 1
fi

exec java "${JVM_ARGS[@]}" -jar "$JAR" "$@"

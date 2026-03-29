#!/usr/bin/env bash
#MISE description="WSL2: Auto-sync to Linux home, build lock-free & fast, copy binary back"

set -e

# ── Platform Guard ──────────────────────────────────────────────────
if ! uname -r | grep -qi 'microsoft'; then
    echo "❌ FATAL: This task must be run inside WSL2."
    exit 1
fi

echo "🚀 Starting Ephemeral WSL2 Build..."
BUILD_DIR="$HOME/.cache/arcclaw-wsl-build"

# ── Safety Guard ────────────────────────────────────────────────────
if [ -z "$BUILD_DIR" ] || [ "$BUILD_DIR" = "/" ] || [ "$BUILD_DIR" = "$HOME" ]; then
    echo "❌ FATAL: BUILD_DIR resolves to a protected path ('$BUILD_DIR'). Aborting rsync."
    exit 1
fi

mkdir -p "$BUILD_DIR"

echo "📦 Syncing source code to Linux filesystem (ignoring target/)..."
rsync -a --delete \
    --exclude '**/target' \
    --exclude '.git' \
    --exclude 'dist' \
    --exclude '.idea' \
    --exclude '.vscode' \
    ./ "$BUILD_DIR/"

echo "🔨 Compiling native Linux binary..."
cd "$BUILD_DIR"
export MAVEN_OPTS="--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Dsun.misc.unsafe.memory.access=allow"
./mvnw clean package -Pnative -pl arcclaw-app -am -DskipTests -Dquarkus.native.container-build=false

echo "🚚 Bringing Linux binary back to Windows..."
mkdir -p "$OLDPWD/arcclaw-app/target/"
cp arcclaw-app/target/arcclaw-app-1.0.0-SNAPSHOT-runner "$OLDPWD/arcclaw-app/target/"

mkdir -p "$OLDPWD/dist/"
cp arcclaw-app/target/arcclaw-app-1.0.0-SNAPSHOT-runner "$OLDPWD/dist/"

WIN_DIST_PATH=$(wslpath -w "$OLDPWD/dist")
echo "✅ WSL2 Build complete! Linux binary is waiting in $WIN_DIST_PATH"
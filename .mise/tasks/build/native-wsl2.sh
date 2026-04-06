#!/usr/bin/env bash
#MISE description="WSL2: Auto-sync to Linux home, build lock-free & fast, copy binary back"
#MISE quiet=true
#MISE depends=["setup:wsl-crlf", "build:ui-copy"]

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

# Exclude .env to act as a "Shield" so secrets are never baked into the binary
echo "📦 Syncing source code to Linux filesystem (ignoring target/ and .env)..."
rsync -a --delete \
    --exclude '**/target' \
    --exclude '.git' \
    --exclude 'dist' \
    --exclude '.idea' \
    --exclude '.vscode' \
    --exclude '*.md' \
    --exclude '*.py' \
    --exclude 'Prompts' \
    --exclude 'docs' \
    --exclude 'docs-main' \
    --exclude 'rest-http' \
    --exclude '.env' \
    --exclude 'build.env' \
    ./ "$BUILD_DIR/"

echo "🔨 Compiling native Linux binary..."
cd "$BUILD_DIR"
export MAVEN_OPTS="--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Dsun.misc.unsafe.memory.access=allow"
./mvnw clean package -Pnative -pl arcclaw-app -am -DskipTests -Dquarkus.native.container-build=false

# 4. Copy ALL compiled artifacts directly back to Windows target/ AND dist/
echo "🚚 Bringing build artifacts back to Windows..."

mkdir -p "$OLDPWD/arcclaw-app/target/"

# Sync the ENTIRE target folder back to Windows (classes, jars, and the native runner)
rsync -a "$BUILD_DIR/arcclaw-app/target/" "$OLDPWD/arcclaw-app/target/"


# Return to the Windows mount directory
cd "$OLDPWD"

echo "▶   Assembling dist/ folder..."
# This seamlessly executes the pwsh dist task inside WSL2!
mise run dist

WIN_DIST_PATH=$(wslpath -w "$OLDPWD/dist")
echo "✅ WSL2 Build complete! Linux binary is waiting in $WIN_DIST_PATH"

echo "✅  Native build complete [wsl2]: arcclaw-app/target/arcclaw-app-1.0.0-SNAPSHOT-runner"
echo ""
echo "▶   Run with one of:"
echo "      mise run native:serve                → HTTP + MCP server on :8080"
echo "      mise run native:chat                 → interactive REPL"
echo "      mise run native:attach               → Telegram daemon"
echo "      \$env:GOAL=\"...\"; mise run native:ask → single-shot query"
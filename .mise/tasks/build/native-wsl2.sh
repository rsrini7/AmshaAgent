#!/usr/bin/env bash
#MISE description="WSL2: Linux binary via rsync to local ext4 — auto-detects Podman vs local GraalVM"
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
    --exclude '.idea' \
    --exclude '.vscode' \
    --exclude 'dist' \
    --exclude 'data' \
    --exclude '*.md' \
    --exclude '*.py' \
    --exclude 'Prompts' \
    --exclude 'docs-test' \
    --exclude 'Ideas-Pending' \
    --exclude 'ProjectWorkflowDocs' \
    --exclude 'bruno-collections' \
    --exclude 'rest-http' \
    --exclude '.env' \
    --exclude 'build.env' \
    --exclude 'docs' \
    --exclude 'docs-main' \
    --exclude 'docs-test' \
    ./ "$BUILD_DIR/"

# ── Resolve build strategy ──────────────────────────────────────────
# Check for --local flag in any position
STRATEGY="container"
if [[ "$*" == *"--local"* ]] || [ "$ARCCLAW_NATIVE_BUILD" = "local" ]; then
    echo "ℹ️   Strategy: local GraalVM (forced)."
    STRATEGY="local"
elif [ "$ARCCLAW_NATIVE_BUILD" = "container" ]; then
    if command -v podman >/dev/null 2>&1; then
        echo "ℹ️   Strategy: Podman container (forced)."
        STRATEGY="container"
    else
        echo "❌  FATAL: ARCCLAW_NATIVE_BUILD=container but podman not found in WSL2."
        exit 1
    fi
else
    # Auto-detect
    if command -v podman >/dev/null 2>&1 && podman info >/dev/null 2>&1; then
        echo "ℹ️   Podman detected and running → using container build."
        STRATEGY="container"
    else
        echo "⚠️   Podman not found or not running → falling back to local GraalVM build."
        STRATEGY="local"
    fi
fi

CONTAINER_FLAG="-Dquarkus.native.container-build=false"
if [ "$STRATEGY" = "container" ]; then
    CONTAINER_FLAG="-Dquarkus.native.container-build=true"
fi

# ── Environment Setup ───────────────────────────────────────────────
if [ "$STRATEGY" = "local" ]; then
    echo "🔍  Ensuring GraalVM is active via mise..."
    GRAAL_PATH=$(mise where java@graalvm-community-25.0.2 2>/dev/null || true)
    if [ -z "$GRAAL_PATH" ]; then
        echo "ℹ️   GraalVM not found. Installing..."
        mise install java@graalvm-community-25.0.2
        GRAAL_PATH=$(mise where java@graalvm-community-25.0.2)
    fi
    export GRAALVM_HOME="$GRAAL_PATH"
    export JAVA_HOME="$GRAAL_PATH"
    export PATH="$GRAAL_PATH/bin:$PATH"
    echo "✅  Using GraalVM: $GRAALVM_HOME"

    # ── Toolchain Guard ───────────────────────────────────────────────
    echo "🔍  Checking for native toolchain (gcc, libz)..."
    if ! command -v gcc >/dev/null 2>&1; then
        echo "❌  FATAL: 'gcc' not found. Local native builds in WSL2 require 'build-essential'."
        echo "    Run: sudo apt-get update && sudo apt-get install -y build-essential libz-dev"
        exit 1
    fi
    # Quick check for zlib headers (common cause of failure after gcc is found)
    if [ ! -f "/usr/include/zlib.h" ] && [ ! -d "/usr/include/x86_64-linux-gnu/zlib" ]; then
        echo "❌  FATAL: 'zlib.h' not found. Local native builds in WSL2 require 'libz-dev'."
        echo "    Run: sudo apt-get update && sudo apt-get install -y libz-dev"
        exit 1
    fi
fi

echo "🔨 Compiling native Linux binary [$STRATEGY]..."
cd "$BUILD_DIR"
export MAVEN_OPTS="--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Dsun.misc.unsafe.memory.access=allow"
./mvnw clean package -Pnative -pl arcclaw-app -am -DskipTests $CONTAINER_FLAG -Dquarkus.native.native-image-xmx=12g

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
# 🛡️ ArcClaw — Complete Setup Guide

> **Stack**: JDK 25 · Quarkus 3.23.0 · LangChain4J 0.26.1 · SQLite (WAL) · Podman (rootless) · Groq Cloud
>
> This guide is the single source of truth for setting up ArcClaw from scratch on **macOS**, **WSL2 (Ubuntu)**, and any platform where commands are shared. Follow every phase in order; do not skip ahead.

---

## 📋 Table of Contents

1. [Prerequisites Overview](#1-prerequisites-overview)
2. [macOS Setup](#2-macos-setup)
3. [WSL2 (Ubuntu) Setup](#3-wsl2-ubuntu-setup)
4. [Common: Clone & Environment Configuration](#4-common-clone--environment-configuration)
5. [Common: Podman / Container Setup](#5-common-podman--container-setup)
6. [Common: Build the Application](#6-common-build-the-application)
7. [Common: Run Modes](#7-common-run-modes)
8. [Common: Docker Compose Stack (Full Observability)](#8-common-docker-compose-stack-full-observability)
9. [Common: Native Binary Build](#9-common-native-binary-build)
10. [Common: Running Tests](#10-common-running-tests)
11. [Telegram Bot Integration](#11-telegram-bot-integration)
12. [Windows PowerShell (Native Wrapper)](#12-windows-powershell-native-wrapper)
13. [Verification Checklist](#13-verification-checklist)
14. [Troubleshooting](#14-troubleshooting)

---

## 1. Prerequisites Overview

Before starting, ensure you have the following tools available or can install them. The table below shows what is required per platform.

| Tool | Version | macOS | WSL2 | Notes |
|---|---|---|---|---|
| JDK 25 | 25+ | ✅ | ✅ | Temurin or Oracle. JDK 21 works but is not recommended. |
| Maven | 3.9+ | ✅ | ✅ | The `./mvnw` wrapper is included in the repo. |
| Podman | Latest | ✅ | ✅ | Rootless. Required for Tier 2 sandbox isolation. |
| Docker CLI | Any | Optional | Optional | Can be wired to Podman socket. Used for compose commands. |
| `gh` CLI | Latest | Optional | Optional | Required only for the GitHub Tool (used by SaraswatiAgent/researcher). Must be on `PATH` in `%dev`. |
| GraalVM / Mandrel | jdk-25 | Optional | Optional | Only required if building the native binary yourself. Quarkus will use a container builder otherwise. |
| jEnv | Any | Optional | — | Recommended for managing multiple JDK versions on macOS. |
| SDKMAN! | Any | Optional | ✅ | Recommended for managing JDK on WSL2/Linux. |

---

## 2. macOS Setup

### 2.1 Install Homebrew (if not already installed)

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

### 2.2 Install JDK 25

**Option A — Temurin (Recommended, free open-source build)**

```bash
brew install --cask temurin@25
```

**Option B — Oracle JDK 25**

Download and install from [oracle.com/java](https://www.oracle.com/java/technologies/downloads/).

**Verify the installation:**

```bash
/usr/libexec/java_home -V
```

You should see your `temurin-25.jdk` listed in the output.

### 2.3 Set JDK 25 as Active (with jEnv)

If you manage multiple JDK versions with jEnv:

```bash
# Install jEnv via Homebrew
brew install jenv

# Add jEnv init to your shell profile (~/.zshrc or ~/.bash_profile)
echo 'export PATH="$HOME/.jenv/bin:$PATH"' >> ~/.zshrc
echo 'eval "$(jenv init -)"' >> ~/.zshrc
source ~/.zshrc

# Register the newly installed JDK 25
jenv add /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home

# Set globally
jenv global 25.0

# Verify
java -version
```

> **Without jEnv**, set `JAVA_HOME` directly in your shell profile:
> ```bash
> export JAVA_HOME=$(/usr/libexec/java_home -v 25)
> export PATH=$JAVA_HOME/bin:$PATH
> ```

### 2.4 Install Podman

```bash
brew install podman
```

After installation, initialize and start the Podman virtual machine:

```bash
# Default init (2GB RAM). For development, 4–6 GB is recommended.
podman machine init

# For resource-constrained machines or large builds:
podman machine init --memory 6144 --cpus 4 --disk-size 40

podman machine start
```

**Verify Podman is running:**

```bash
podman ps
```

### 2.5 (Optional) Wire Docker CLI to Podman on macOS

If you prefer using `docker` commands (e.g. for `docker compose`):

```bash
# Add to ~/.zshrc or ~/.bash_profile
export DOCKER_HOST="unix://${HOME}/.local/share/containers/podman/machine/qemu/podman.sock"
```

> **Note**: The exact socket path may vary depending on your Podman version. Run `podman machine inspect | grep -i sock` to find the correct path on your machine.

---

## 3. WSL2 (Ubuntu) Setup

> These steps are performed entirely inside your WSL2 Ubuntu terminal unless stated otherwise.

### 3.1 Enable Systemd (Required for Podman Socket)

Podman's API socket is managed by systemd. You must enable it before installing Podman.

```bash
sudo nano /etc/wsl.conf
```

Add or ensure these lines exist:

```ini
[boot]
systemd=true
```

Save (`Ctrl+O`, `Enter`) and exit (`Ctrl+X`).

**Restart WSL from Windows PowerShell:**

```powershell
wsl --shutdown
```

Reopen your Ubuntu terminal to continue.

### 3.2 Install JDK 25 via SDKMAN!

SDKMAN! is the cleanest way to manage Java versions on Linux.

```bash
# Install SDKMAN!
curl -s "https://get.sdkman.io" | bash

# Initialise (or restart your terminal)
source "$HOME/.sdkman/bin/sdkman-init.sh"

# Install JDK 25 — Temurin build
sdk install java 25-tem

# Or Oracle JDK 25
# sdk install java 25-oracle

# Verify
java -version
```

### 3.3 Install Podman

```bash
sudo apt update
sudo apt install podman -y
```

### 3.4 (Optional) Install Docker CLI Only

If you want to use the `docker` command to control Podman:

```bash
# Add Docker's GPG key
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

# Add the repository
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

# Install ONLY the CLI — do NOT install docker-ce or the daemon
sudo apt update
sudo apt install docker-ce-cli -y
```

### 3.5 Enable the Podman User Socket

```bash
# Enable and start the user-level Podman socket
systemctl --user enable --now podman.socket

# Confirm the socket exists (default UID is 1000 — verify with `id -u`)
ls -l /run/user/1000/podman/podman.sock
```

> If `id -u` returns a value other than `1000`, replace `1000` in all subsequent socket paths with your actual UID.

### 3.6 Configure the DOCKER_HOST Environment Variable

```bash
echo 'export DOCKER_HOST="unix:///run/user/1000/podman/podman.sock"' >> ~/.bashrc
source ~/.bashrc
```

### 3.7 Fix Windows CRLF Line Endings (Critical for WSL2)

When a repository is cloned on Windows and then used in WSL2, Maven wrapper scripts may have Windows-style line endings (`\r\n`) that cause execution to fail with:

```
-bash: ./mvnw: cannot execute: required file not found
```

Fix this before running any Maven commands:

```bash
sudo apt install dos2unix -y
dos2unix mvnw
```

### 3.8 Verify Everything

```bash
java -version         # Should show 25.x
podman ps             # Should return an empty table, no errors
docker ps             # Should also work if Docker CLI was installed
```

---

## 4. Common: Clone & Environment Configuration

### 4.1 Clone the Repository

```bash
git clone https://github.com/rsrini7/ArcClaw.git
cd ArcClaw
```

### 4.2 Create Your `.env` File

ArcClaw uses a `.env` file for all secrets and runtime config. The file is loaded automatically at startup via `DotenvConfigSourceProvider`.

```bash
cp .env.example .env
```

Now open `.env` in your editor and fill in the values:

```env
# =============================================================================
# Core Settings
# =============================================================================
QUARKUS_PROFILE=prod
ARCCLAW_DATA_DIR=./data              # Or absolute path, e.g. /home/user/arcclaw-data

# CORS (comma-separated list of allowed origins)
ARCCLAW_CORS_ORIGINS=http://localhost:3000

# =============================================================================
# AI Models — Default: Groq Cloud
# =============================================================================
# Primary reasoning (llama-3.3-70b-versatile) and intent classifier (llama-3.1-8b-instant)
GROQ_API_KEY=gsk_your_groq_api_key_here

# Fallback / Offline: Local Ollama (disabled by default in application.yaml)
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_MODEL=llama3.2:1b

# =============================================================================
# Tool Secrets
# =============================================================================
# Required for SaraswatiAgent (researcher) GitHub operations
GITHUB_TOKEN=ghp_your_github_token_here

# Auto-seeds the SecretVault with GITHUB_TOKEN on first boot
ARCCLAW_AUTO_SEED_SECRETS=true

# =============================================================================
# Security
# =============================================================================
# Audit HMAC chain key — must be stable across restarts, minimum 32 bytes
ARCCLAW_AUDIT_HMAC_KEY=changeme-audit-hmac-key-min-32-bytes

# Internal REST API key (used for curl / CI / api.http)
ARCCLAW_API_KEY_INTERNAL=key-internal-<generate-a-random-string>

# CI/CD API key
ARCCLAW_API_KEY_CI=key-<generate-a-random-string>

# =============================================================================
# Telegram (Only required for `attach telegram` mode)
# =============================================================================
TELEGRAM_BOT_TOKEN=123456789:ABCdefGHIjklmNOPQrsTUVwxyz

# Allowlist: space-separated numeric Telegram user IDs (not usernames)
ARCCLAW_TELEGRAM_ALLOWED_USER_IDS=123456789

# Allowlist: space-separated Telegram usernames (without @)
ARCCLAW_TELEGRAM_ALLOWED_USERS=yourusername

# =============================================================================
# Observability
# =============================================================================
QUARKUS_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://localhost:4317
```

> **Security Warning**: Never commit your real `.env` file. It is already in `.gitignore`. The `ARCCLAW_AUDIT_HMAC_KEY` must remain stable across restarts — changing it will break audit chain verification.

### 4.3 Understand the Data Directory

By default, `ARCCLAW_DATA_DIR=./data`. ArcClaw will create the following inside this directory at runtime:

```
data/
├── arcclaw.db          # Main SQLite WAL database (chat memory, audit, crons)
├── arcclaw-fts.db      # Separate FTS5 full-text search index (compaction summaries)
└── workspaces/         # Ephemeral per-session sandboxed working directories
```

On Windows, you can use an absolute path to avoid WSL path confusion:

```env
ARCCLAW_DATA_DIR=C:\Users\<user>\arcclaw-data
```

---

## 5. Common: Podman / Container Setup

### 5.1 Build the Sandbox Tool Runner Image

The `tool-runner` is the Alpine-based container that ArcClaw uses for Tier 1 (Podman rootless) sandboxed tool execution. It **must** be built before running in `%prod` mode.

**Using Docker Compose (recommended):**

```bash
docker compose build tool-runner
```

**Using Podman directly:**

```bash
podman build -t arcclaw-tool-runner:latest -f sandbox/Dockerfile.toolrunner sandbox/
```

> The `tool-runner` image is pinned to `arcclaw-tool-runner:latest`. If this tag is missing, the Podman health check (`PodmanImageHealthCheck`) will fail at startup and the application will refuse to start in `%prod` mode.

### 5.2 Sandbox Tier Behaviour (Three-Tier Degradation)

Understanding which tier is active is essential for debugging tool execution:

| Tier | Condition | Isolation | Notes |
|---|---|---|---|
| **1 — Podman Rootless** | `%prod` + Podman socket up + image present | Full container isolation: `--network=none --read-only`, memory/CPU capped | Secrets injected via tmpfs, never as CLI args |
| **2 — Linux Namespaces** | Podman circuit breaker opened (3 consecutive failures) | `unshare` pid + net + mount | Fallback, Linux only |
| **3 — Direct Execution** | `%dev` with `strategy: NONE`, or Windows host | None — host `PATH` `ProcessBuilder` | `gh` CLI must be on PATH. **No isolation.** |

In `%dev` mode (`quarkus:dev`), the strategy defaults to `NONE`. To test full Podman isolation, package the jar and run it with `QUARKUS_PROFILE=prod`.

### 5.3 Systemd Tmpfs Mount for Secrets (Linux/WSL2, Production)

For production deployments on Linux, ArcClaw supports a systemd-managed tmpfs mount that creates a hardened, in-memory location for secret transport:

```bash
# Copy the unit file
sudo cp arcclaw-secrets.mount /etc/systemd/system/

# Enable and start it
sudo systemctl enable --now run-arcclaw-secrets.mount

# Verify the mount exists
ls -la /run/arcclaw/secrets
```

This creates a `700`-mode, `10MB`, `noexec nosuid nodev` tmpfs at `/run/arcclaw/secrets` — secrets are written here and memory-wiped when the tool call completes.

---

## 6. Common: Build the Application

### 6.1 Standard JVM Build (Fast, Recommended for Development)

This builds all modules (`arcclaw-channel-api`, `arcclaw-core`, `arcclaw-deployment`, `arcclaw-app`) and produces a fast-jar in `arcclaw-app/target/quarkus-app/`.

```bash
# Full multi-module build, skip tests
./mvnw clean package -pl arcclaw-app -am -DskipTests

# On Windows (PowerShell)
.\mvnw.cmd clean package -pl arcclaw-app -am -DskipTests
```

The `-am` (also make) flag is critical — it rebuilds `arcclaw-core` and `arcclaw-channel-api` before packaging `arcclaw-app`. Omitting it will cause stale class issues.

### 6.2 Build Including Tests

```bash
./mvnw clean verify -pl arcclaw-app -am

# On Windows
.\mvnw.cmd clean verify -pl arcclaw-app -am
```

### 6.3 Expected Build Output

After a successful build, you will find:

```
arcclaw-app/target/quarkus-app/
├── quarkus-run.jar          # The main runnable jar (thin launcher)
├── app/                     # Application classes
├── lib/                     # Runtime dependencies
└── quarkus/                 # Quarkus framework classes
```

---

## 7. Common: Run Modes

ArcClaw is a dual-mode Quarkus/PicoCLI binary. The mode is controlled by the subcommand passed after the jar.

### 7.1 Dev Mode (Hot Reload — Recommended During Development)

Dev mode enables live code reload, the Quarkus Dev UI, and automatically uses the `%dev` profile (strategy: `NONE`, no container isolation).

```bash
# macOS / WSL2
./mvnw quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve"

# Windows PowerShell
.\mvnw.cmd quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve"

# Suppress Dev UI and console if they cause NPE errors
.\mvnw.cmd quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve" `
  "-Dquarkus.console.enabled=false" `
  "-Dquarkus.devui.enabled=false"
```

> **Dev Sandbox Note**: In `%dev`, tools execute directly on the host. The `gh` CLI must be installed and authenticated (`gh auth login`) on your `PATH` for the GitHub tool to work.

### 7.2 Mode A — Daemon (serve)

Run as a long-lived background REST API server (port `8080`).

```bash
# From source (dev mode)
./mvnw quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve"

# From packaged jar (JVM mode)
java \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  "-Dsun.misc.unsafe.memory.access=allow" \
  -jar arcclaw-app/target/quarkus-app/quarkus-run.jar serve
```

### 7.3 Mode B — Channel Daemon (attach)

Runs ArcClaw as a background daemon servicing external channel adapters (e.g. Telegram via Apache Camel).

```bash
# From packaged jar
java \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  "-Dsun.misc.unsafe.memory.access=allow" \
  "-Dquarkus.profile=%cli" \
  -jar arcclaw-app/target/quarkus-app/quarkus-run.jar attach telegram
```

> The `%cli` profile suppresses the startup banner and REST port binding, keeping the terminal clean for long-running daemon operation.

### 7.4 Mode C — Interactive REPL (chat)

Start a multi-turn, stateful conversation directly in your terminal.

```bash
# From packaged jar — with full UTF-8 encoding flags for proper Unicode rendering
java \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  "-Dsun.misc.unsafe.memory.access=allow" \
  "-Dfile.encoding=UTF-8" \
  "-Dstdout.encoding=UTF-8" \
  "-Dsun.stdout.encoding=UTF-8" \
  "-Dsun.stderr.encoding=UTF-8" \
  "-Dquarkus.profile=%cli" \
  -jar arcclaw-app/target/quarkus-app/quarkus-run.jar chat
```

### 7.5 Mode D — One-Shot Task (ask)

Execute a single agentic task and exit. Ideal for CI/CD pipelines.

```bash
java \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  "-Dsun.misc.unsafe.memory.access=allow" \
  -jar arcclaw-app/target/quarkus-app/quarkus-run.jar ask "Analyze the ArcClaw repository" --role coordinator

# With role flag (valid roles: coordinator, researcher, coder, debugger)
# or using the PS1 wrapper:
.\arcclaw-cli.ps1 ask "who are you" -r debugger
```

### 7.6 JVM Flags Explained

All run modes above pass specific JVM flags. Here is what each one does and why it is required:

| Flag | Reason |
|---|---|
| `--add-opens java.base/java.nio=ALL-UNNAMED` | Netty requires direct ByteBuffer access (JEP 471 restricts `sun.misc.Unsafe` by default in JDK 22+). |
| `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED` | Same Netty/Unsafe requirement. |
| `--add-opens java.base/java.lang=ALL-UNNAMED` | JBoss Threads requires thread-local reset access in JDK 24+. |
| `--enable-native-access=ALL-UNNAMED` | Panama foreign memory access. Required by several Quarkus extensions. |
| `-Dsun.misc.unsafe.memory.access=allow` | Suppresses the `InaccessibleObjectException` warning from Netty. Equivalent to old `--sun-misc-unsafe-memory-access=allow`. |
| `-Dfile.encoding=UTF-8` + related | Forces UTF-8 for stdin/stdout/stderr. Ensures terminal symbols (`✦`, `⚡`, `🛡️`) render correctly, especially on Windows. |
| `-Dquarkus.profile=%cli` | Activates the `%cli` Quarkus configuration profile, which suppresses the HTTP server, banner, and Dev UI. |

---

## 8. Common: Docker Compose Stack (Full Observability)

The `docker-compose.yaml` brings up the full observability stack alongside ArcClaw. This is required if you want to use Prometheus metrics and Jaeger distributed tracing.

### 8.1 Services in the Stack

| Service | Port | Purpose |
|---|---|---|
| `arcclaw` | `8080` | Main application (native binary build) |
| `ollama` | `11434` | Local LLM provider (disabled by default in `application.yaml`) |
| `otel-collector` | `4317` (gRPC), `4318` (HTTP) | OpenTelemetry collector — forwards spans to Jaeger |
| `jaeger` | `16686` (UI), `4317` (internal) | Distributed trace viewer |
| `prometheus` | `9090` | Metrics scraper — scrapes `/q/metrics` |
| `tool-runner` | — | Build-only service (profile: `builder`) |
| `init-perms` | — | One-shot service to fix data volume ownership (UID 1000) |

### 8.2 Starting the Stack

```bash
# Standard start
docker compose up -d

# If you need a clean rebuild of the ArcClaw image
docker compose up -d --build

# Or with explicit no-cache
docker compose build --no-cache
docker compose up -d
```

### 8.3 Observability UI URLs

| Service | URL |
|---|---|
| ArcClaw REST API | http://localhost:8080/api/v1 |
| Swagger / OpenAPI UI | http://localhost:8080/q/swagger-ui |
| Health Check | http://localhost:8080/q/health |
| Prometheus Metrics | http://localhost:8080/q/metrics |
| Jaeger Trace UI | http://localhost:16686 |
| Prometheus UI | http://localhost:9090 |

### 8.4 Windows Podman Desktop Note

If you receive `open //./pipe/dockerDesktopLinuxEngine`:

```powershell
# Set this before running compose
$env:DOCKER_HOST="npipe:////./pipe/podman-machine-default"
docker compose up -d
```

Or enable "Docker API compatibility" in Podman Desktop Settings.

### 8.5 Ollama Port Collision

If Ollama is already running on your host (macOS/Windows), Docker Compose will fail with `bind: address already in use` on port `11434`. Either quit the local Ollama process before running compose, or remap the port in `docker-compose.yaml`:

```yaml
ollama:
  ports:
    - "11435:11434"   # External port remapped to avoid collision
```

---

## 9. Common: Native Binary Build

A native binary provides a ~10ms startup time and a ~118MB self-contained executable. This is what `Dockerfile.native` expects.

### 9.1 Option A — Container-Build (Recommended, No GraalVM Required)

Quarkus will pull the Mandrel builder image (`quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-25`) and compile inside a container. Podman must be running.

```bash
# macOS / WSL2 (uses Podman as the container runtime per pom.xml config)
./mvnw clean package -pl arcclaw-app -am -Pnative -DskipTests

# Windows
.\mvnw.cmd clean package -pl arcclaw-app -am -Pnative -DskipTests
```

The native profile in `arcclaw-app/pom.xml` sets:
- `quarkus.native.container-build=true`
- `quarkus.native.container-runtime=podman`
- `quarkus.native.builder-image=quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-25`
- `-J-Xmx8g` to prevent OOM during GraalVM analysis

### 9.2 Option B — Local GraalVM/Mandrel Install (Reduces Podman Memory)

If you have GraalVM or Mandrel installed locally and want to avoid the container builder (useful on memory-constrained machines):

```bash
./mvnw clean package -Pnative -Dquarkus.native.container-build=false -DskipTests
```

### 9.3 After the Native Build

The output binary will be at:

```
arcclaw-app/target/arcclaw-app-1.0.0-SNAPSHOT-runner
```

It is approximately **118MB** and is a fully statically-linked executable containing the entire Quarkus runtime and ArcClaw kernel.

### 9.4 Run the Native Binary

```bash
./arcclaw-app/target/arcclaw-app-1.0.0-SNAPSHOT-runner serve
```

The native binary respects `QUARKUS_PROFILE` and reads the `.env` file at startup.

---

## 10. Common: Running Tests

### 10.1 Full Test Suite

Runs all 40+ unit and integration tests across all modules using Surefire + Failsafe:

```bash
# macOS / WSL2
./mvnw clean verify -pl arcclaw-app -am

# Windows
.\mvnw.cmd clean verify -pl arcclaw-app -am
```

Mockito inline mock attachment works automatically because the required `--add-opens` JVM flags are embedded in `arcclaw-app/pom.xml`.

### 10.2 Run Specific Tests

```bash
# macOS / WSL2
./mvnw test -pl arcclaw-app -am \
  "-Dtest=PersistentChatMemoryStoreTest,LangChainReasoningEngineTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false"

# Windows
.\mvnw.cmd test -pl arcclaw-app -am `
  "-Dtest=PersistentChatMemoryStoreTest,LangChainReasoningEngineTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

### 10.3 Notable Test Classes

| Test Class | Module | What It Covers |
|---|---|---|
| `PersistentChatMemoryStoreTest` | `arcclaw-core` | SQLite WAL chat memory persistence |
| `LangChainReasoningEngineTest` | `arcclaw-core` | LLM reasoning loop with Mockito mocked LLM |
| `AgentLoopTest` | `arcclaw-core` | ReAct loop iteration, FINISH detection |
| `GovernanceLayerTest` | `arcclaw-core` | Budget circuit breaker, HITL approval gates |
| `SecretVaultImplTest` | `arcclaw-core` | AES-256-GCM-SIV encryption/decryption |
| `PodmanHealthCheckTest` | `arcclaw-app` | Podman socket liveness health check |
| `TelegramChannelRouteTest` | `arcclaw-app` | Apache Camel Telegram route |
| `ApiKeyAuthFilterTest` | `arcclaw-app` | REST API key authentication filter |
| `StartupSecurityValidatorTest` | `arcclaw-app` | Production safety validation on boot |
| `AgentCronSchedulerTest` | `arcclaw-app` | Scheduled cron job execution |
| `AuditServiceIntegrationTest` | `arcclaw-app` | Immutable HMAC audit chain |
| `WorkspaceScopeTest` | `arcclaw-app` | Custom CDI `@WorkspaceScoped` lifecycle |

### 10.4 Test Profile Notes

Tests use the `%test` profile from `arcclaw-app/src/test/resources/application.yaml`. Key differences from `%dev`/`%prod`:
- Sandbox strategy is always `NONE` in tests (no container execution)
- Quarkus Test will start an in-process H2 or SQLite test DB
- LLM calls are mocked via Mockito `@InjectMock` — no real API calls are made during unit tests

---

## 11. Telegram Bot Integration

### 11.1 Create a Bot via BotFather

1. Open Telegram and search for `@BotFather`.
2. Send `/newbot`.
3. Provide a display name (e.g. `ArcClaw Dev Bot`) and a username (must end in `bot`, e.g. `arcclaw_dev_bot`).
4. Copy the HTTP API token (format: `123456789:ABCdefGHIjklmNOPQrsTUVwxyz`).

### 11.2 Configure Your `.env`

```env
TELEGRAM_BOT_TOKEN=123456789:ABCdefGHIjklmNOPQrsTUVwxyz

# Find your Telegram user ID via @userinfobot
ARCCLAW_TELEGRAM_ALLOWED_USER_IDS=123456789
ARCCLAW_TELEGRAM_ALLOWED_USERS=yourusername
```

> The allowlist is enforced by `TelegramAllowlistGuard`. Messages from IDs/usernames not on the list are silently dropped.

### 11.3 Start ArcClaw in Attach Mode

```bash
# Dev mode
./mvnw quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve"

# OR packaged jar
java --add-opens=java.base/java.nio=ALL-UNNAMED \
     --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
     --add-opens=java.base/java.lang=ALL-UNNAMED \
     --enable-native-access=ALL-UNNAMED \
     "-Dsun.misc.unsafe.memory.access=allow" \
     "-Dquarkus.profile=%cli" \
     -jar arcclaw-app/target/quarkus-app/quarkus-run.jar attach telegram
```

### 11.4 Verification Tests

**Test 1 — Cold Start**: Send your bot any message. In the Quarkus terminal you should see the Camel route pick it up, spawn a virtual thread, and the AgentLoop log its reasoning. The bot should reply within seconds.

**Test 2 — Session Continuity**: Follow up with "Can you summarize what you just told me in 3 bullet points?" — the bot should reference the previous turn, proving UUID-keyed session persistence works.

**Test 3 — Human-in-the-Loop**: Request a task that triggers `NETWORK_OUTBOUND` (e.g. "Search GitHub for..."). The agent will pause and ask you to reply `/approve` or `/deny`.

### 11.5 Token Revocation (Nuclear Option)

If ghost connections from previous sessions are polling your bot:

1. Open `@BotFather` in Telegram.
2. Select your bot and send `/revoke`.
3. BotFather issues a new token.
4. Update `TELEGRAM_BOT_TOKEN` in your `.env`.
5. Restart the daemon.

---

## 12. Windows PowerShell (Native Wrapper)

`arcclaw-cli.ps1` is a convenience wrapper for Windows that: forces `QUARKUS_PROFILE=cli`, sets all UTF-8 encoding flags, and passes all arguments directly to the jar.

```powershell
# Interactive REPL
.\arcclaw-cli.ps1 chat

# One-shot task
.\arcclaw-cli.ps1 ask "Who are you?" --role coordinator

# One-shot with shorthand role flag
.\arcclaw-cli.ps1 ask "who are you" -r debugger

# Daemon mode
.\arcclaw-cli.ps1 serve
```

> **Set ExecutionPolicy if needed**: If PowerShell blocks script execution, run:
> ```powershell
> Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
> ```

---

## 13. Verification Checklist

Run through this checklist after setup to confirm everything is working end-to-end.

### Environment

- [ ] `java -version` shows JDK 25
- [ ] `./mvnw --version` (or `.\mvnw.cmd`) shows Maven 3.9+
- [ ] `podman ps` returns an empty list with no errors
- [ ] `.env` file exists and all required keys are populated

### Build

- [ ] `./mvnw clean package -pl arcclaw-app -am -DskipTests` completes successfully
- [ ] `arcclaw-app/target/quarkus-app/quarkus-run.jar` exists

### Runtime

- [ ] Daemon starts with `./mvnw quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve"` and logs `ArcClaw daemon mode starting on port 8080`
- [ ] `curl http://localhost:8080/q/health` returns `{"status":"UP",...}`
- [ ] `curl -X POST http://localhost:8080/api/v1/sessions` returns a `sessionId`
- [ ] Sandbox tool image is built and present: `podman images | grep arcclaw-tool-runner`

### Observability (if using compose stack)

- [ ] Jaeger UI accessible at http://localhost:16686
- [ ] Prometheus accessible at http://localhost:9090
- [ ] Metrics visible at http://localhost:8080/q/metrics

---

## 14. Troubleshooting

### `./mvnw: cannot execute: required file not found` (WSL2)

Windows CRLF line endings in the Maven wrapper script. Fix:

```bash
sudo apt install dos2unix -y
dos2unix mvnw
```

### `NullPointerException` in Quarkus Dev Console

Disable the dev console and Dev UI:

```bash
./mvnw quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve" \
  "-Dquarkus.console.enabled=false" \
  "-Dquarkus.devui.enabled=false"
```

### Podman Socket Not Found / `docker ps` Fails

On WSL2, the socket requires systemd to be running. Verify:

```bash
systemctl --user status podman.socket
# If inactive:
systemctl --user enable --now podman.socket
```

Ensure `DOCKER_HOST` is set:

```bash
echo $DOCKER_HOST
# Should print: unix:///run/user/1000/podman/podman.sock
```

If your UID is not `1000`:

```bash
id -u  # Find your UID
export DOCKER_HOST="unix:///run/user/$(id -u)/podman/podman.sock"
# Update ~/.bashrc with your actual UID
```

### Podman `permission denied` with Docker CLI

Add your user to the Podman-related group:

```bash
sudo usermod -aG $(getent group 10 | cut -d: -f1) $USER
# Then log out and log back in
```

### `InaccessibleObjectException` / Unsafe Memory Access Warnings

These are expected on JDK 22+ with Netty. They are suppressed by the JVM flags in the Maven plugin configuration. If you see them when running the jar manually, ensure you are passing all `--add-opens` and `-Dsun.misc.unsafe.memory.access=allow` flags shown in [Section 7.6](#76-jvm-flags-explained).

### `open //./pipe/dockerDesktopLinuxEngine` (Windows Podman Desktop)

```powershell
$env:DOCKER_HOST="npipe:////./pipe/podman-machine-default"
```

Or enable Docker API compatibility in Podman Desktop → Settings → General.

### OOM During Native Build

The GraalVM analysis phase requires up to 8GB. The native profile sets `-J-Xmx8g` by default. If your machine has less available RAM:

```bash
# Lower the heap cap
./mvnw clean package -pl arcclaw-app -am -Pnative -DskipTests \
  "-Dquarkus.native.additional-build-args=-J-Xmx6g"
```

Alternatively, ensure your Podman machine has sufficient memory:

```bash
podman machine stop
podman machine set --memory 8192
podman machine start
```

### `lstat /arcclaw-app/target: no such file or directory` (Docker Compose Native Build)

`Dockerfile.native` copies the pre-built native binary from `arcclaw-app/target/`. You must run the native build step on your host **before** running `docker compose up --build`. See [Section 9](#9-common-native-binary-build).

### Ollama Port Collision (`bind: address already in use` on 11434)

Quit your local Ollama app or remap the port in `docker-compose.yaml`:

```yaml
ollama:
  ports:
    - "11435:11434"
```

### `@WorkspaceScoped` CDI Errors in Tests

If CDI tests fail with unknown scope errors, ensure the `arcclaw-deployment` module is on the test classpath. The `WorkspaceScopeProcessor` `@BuildStep` is what registers the custom scope at Quarkus build time.

### Groq API Key Not Working

- Verify the key starts with `gsk_` and is copied without extra whitespace.
- Confirm `ARCCLAW_AUTO_SEED_SECRETS=true` is set in `.env` so the vault is seeded on first boot.
- Check the Quarkus logs for `Seeding secret: GROQ_API_KEY` on startup.

### FTS5 Backfill (Database Migration)

If you are upgrading an existing deployment from a version before Phase 45 and `compaction_summary` rows have null `user_id`:

```bash
# Open your SQLite database
sqlite3 data/arcclaw.db

# Attach the FTS database and run the backfill
ATTACH DATABASE './data/arcclaw-fts.db' AS fts;
.read backfill_fts5.sql
.quit
```

---

## 📚 Related Documentation

| Document | Purpose |
|---|---|
| [`FAQ.md`](./FAQ.md) | Frequently Asked Questions |
| [`*.http`](./rest-http) | IntelliJ / VSCode REST Client file for interactive API testing |

---

*Last updated: ArcClaw V1.0.0 · Quarkus 3.23.0 · JDK 25*

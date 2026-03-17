# ArcClaw — Setup Guide

> **Stack**: JDK 25 · Quarkus 3.27.2 · LangChain4J 0.26.1 · SQLite (WAL + FTS5 + sqlite-vec) · Podman (rootless) · Groq Cloud
>
> This is the single source of truth for setting up ArcClaw on **macOS**, **WSL2 (Ubuntu)**, and **Windows**. Work through the sections in order before moving to the [Testing Guide](./TESTING_GUIDE.md).

---

> **Docs**: [README](./README.md) · [Testing Guide](./TESTING_GUIDE.md) · [Telegram Testing](./TELEGRAM_TESTING.md) · [FAQ](./FAQ.md) · [REST Client Files](./rest-http/)

---

## Table of Contents

1. [Prerequisites Overview](#1-prerequisites-overview)
2. [macOS Setup](#2-macos-setup)
3. [WSL2 (Ubuntu) Setup](#3-wsl2-ubuntu-setup)
4. [Common: Clone & Environment Configuration](#4-common-clone--environment-configuration)
5. [Common: Podman / Container Setup](#5-common-podman--container-setup)
6. [Common: Build the Application](#6-common-build-the-application)
7. [Common: Run Modes](#7-common-run-modes)
   - 7.7 [Telegram Bot Setup](#77-telegram-bot-setup-required-for-attach-telegram-mode)
8. [Common: Docker Compose Stack (Full Observability)](#8-common-docker-compose-stack-full-observability)
9. [Common: Native Binary Build](#9-common-native-binary-build)
10. [Windows PowerShell (Native Wrapper)](#10-windows-powershell-native-wrapper)

---

## 1. Prerequisites Overview

| Tool | Version | macOS | WSL2 | Notes |
|---|---|---|---|---|
| JDK 25 | 25+ | ✅ | ✅ | Temurin or Oracle. JDK 21 works but is not recommended. |
| Maven | 3.9+ | ✅ | ✅ | The `./mvnw` wrapper is included in the repo — no separate install needed. |
| Podman | Latest | ✅ | ✅ | Rootless. Required for Tier 1 sandbox isolation in `%prod`. |
| Docker CLI | Any | Optional | Optional | Can be wired to the Podman socket. Used for `docker compose` commands. |
| `gh` CLI | Latest | Optional | Optional | Required for the GitHub Tool (SaraswatiAgent/researcher). Must be on `PATH` in `%dev` (no container isolation). |
| GraalVM / Mandrel | jdk-25 | Optional | Optional | Only required if building the native binary without the container builder. |
| jEnv | Any | Optional | — | Recommended for managing multiple JDK versions on macOS. |
| SDKMAN! | Any | Optional | ✅ | Recommended for managing JDK versions on WSL2/Linux. |
| `sqlite3` CLI | Any | Optional | Optional | Useful for directly inspecting databases during testing. |

---

## 2. macOS Setup

### 2.1 Install Homebrew

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

### 2.2 Install JDK 25

**Option A — Temurin (Recommended)**

```bash
brew install --cask temurin@25
```

**Option B — Oracle JDK 25**

Download and install from [oracle.com/java](https://www.oracle.com/java/technologies/downloads/).

**Verify the installation:**

```bash
/usr/libexec/java_home -V
# Should list temurin-25.jdk in the output
```

### 2.3 Set JDK 25 as Active (with jEnv)

```bash
# Install jEnv
brew install jenv

# Add to your shell profile (~/.zshrc or ~/.bash_profile)
echo 'export PATH="$HOME/.jenv/bin:$PATH"' >> ~/.zshrc
echo 'eval "$(jenv init -)"' >> ~/.zshrc
source ~/.zshrc

# Register JDK 25
jenv add /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
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

Initialize and start the Podman virtual machine:

```bash
# Default init (2 GB RAM). For development, 4–6 GB is strongly recommended.
podman machine init

# For resource-constrained machines or large builds:
podman machine init --memory 6144 --cpus 4 --disk-size 40

podman machine start
```

**Verify:**

```bash
podman ps
```

### 2.5 (Optional) Wire Docker CLI to Podman on macOS

```bash
# Add to ~/.zshrc or ~/.bash_profile
export DOCKER_HOST="unix://${HOME}/.local/share/containers/podman/machine/qemu/podman.sock"
```

> The exact socket path can vary with Podman versions. Run `podman machine inspect | grep -i sock` to confirm the correct path on your machine.

---

## 3. WSL2 (Ubuntu) Setup

> All steps in this section are performed inside your WSL2 Ubuntu terminal unless explicitly stated otherwise.

### 3.1 Enable Systemd (Required for Podman Socket)

Podman's API socket is managed by systemd. Enable it before installing anything else.

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

# Install ONLY the CLI — do NOT install docker-ce or the full daemon
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

When a repository is cloned on Windows and then used in WSL2, Maven wrapper scripts may carry Windows-style `\r\n` line endings, causing:

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
podman ps             # Should return an empty table with no errors
docker ps             # Should also work if the Docker CLI was installed
```

---

## 4. Common: Clone & Environment Configuration

### 4.1 Clone the Repository

```bash
git clone https://github.com/rsrini7/ArcClaw.git
cd ArcClaw
```

### 4.2 Configure the `.env` File

```bash
cp .env.example .env
```

Open `.env` and fill in the values. The minimum required set for development:

**Quick Reference — Key Variables**

| Variable | Default | Purpose |
| :--- | :--- | :--- |
| `GROQ_API_KEY` | `(empty)` | **Required.** Authenticates with Groq Cloud for the reasoning engine and intent classifier. |
| `TELEGRAM_BOT_TOKEN` | `(empty)` | **Required for `attach telegram` mode.** Connects the daemon to your Telegram bot. |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Points to the local or remote Ollama provider (disabled by default in V1.0.0). |
| `GITHUB_TOKEN` | `(empty)` | Required if using the `GithubTool` (`researcher`, `coordinator`, `architect`, `synthesizer`, `debugger` roles). |
| `ARCCLAW_DATA_DIR` | `./data` | Host path where SQLite databases and workspaces are saved. |
| `ARCCLAW_API_KEY_INTERNAL` | `key-internal-changeme` | Bearer token for internal API access via `X-ArcClaw-API-Key` header. **Change in production.** |
| `ARCCLAW_API_KEY_CI` | `key-ci-changeme` | Bearer token for CI/CD pipeline access. **Change in production.** |
| `ARCCLAW_VAULT_PEPPER` | `dev-pepper-...` | Server-side secret for Argon2id key derivation. **Must be 32+ bytes in production.** |
| `ARCCLAW_AUDIT_HMAC_KEY` | `dev-audit-hmac-key-...` | HMAC key for audit event integrity chain. **Must be 32+ bytes in production.** |
| `ARCCLAW_SESSION_BUDGET_USD` | `10.00` | Per-session USD budget cap. Downgrades to `READONLY` autonomy when exceeded. |
| `ARCCLAW_TELEGRAM_ALLOWED_USER_IDS` | `(empty = open access)` | Comma-separated numeric Telegram user IDs. **Empty allows all users.** |
| `ARCCLAW_PII_MODE` | `REDACT_AND_FORWARD` | PII handling mode. Set to `BLOCK_AND_NOTIFY` to reject messages containing PII outright. |

```bash
# =============================================================================
# Core Settings
# =============================================================================
# Determines the behaviour of Quarkus (dev, test, prod)
QUARKUS_PROFILE=prod

# Directory where ArcClaw stores databases and session state.
# Linux/macOS: ./data or /home/user/arcclaw-data
# Windows:     C:\Users\<user>\arcclaw-data
ARCCLAW_DATA_DIR=./data

# CORS allowed origins (comma-separated for multiple)
ARCCLAW_CORS_ORIGINS=http://localhost:3000

# =============================================================================
# AI Models — Default: Groq Cloud
# =============================================================================
# Primary reasoning (llama-4-scout-17b-16e-instruct) + intent classifier (llama-3.1-8b-instant)
GROQ_API_KEY=gsk_your_groq_api_key_here

# Fallback / Offline: Local Ollama (disabled by default in application.yaml)
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_MODEL=llama3.2:1b

# =============================================================================
# Tool Secrets (Vault)
# =============================================================================
# Required for SaraswatiAgent (researcher) GitHub operations
GITHUB_TOKEN=ghp_your_github_token_here

# Auto-seeds the SecretVault with GITHUB_TOKEN on first boot
ARCCLAW_AUTO_SEED_SECRETS=true

# Vault pepper — used as the KDF pepper for AES-GCM-SIV secret encryption.
# Must be at least 32 bytes. Must remain stable across restarts.
ARCCLAW_VAULT_PEPPER=changeme-vault-pepper-min-32-bytes

# =============================================================================
# Security
# =============================================================================
# Audit HMAC chain key — must be stable across restarts, minimum 32 bytes
ARCCLAW_AUDIT_HMAC_KEY=changeme-audit-hmac-key-min-32-bytes

# REST API keys — see Section 19 of TESTING_GUIDE.md for how to generate secure values
ARCCLAW_API_KEY_INTERNAL=key-internal-<generate-a-random-string>
ARCCLAW_API_KEY_CI=key-<generate-a-random-string>

# PII mode — override at runtime, no rebuild needed
# ARCCLAW_PII_MODE=BLOCK_AND_NOTIFY

# =============================================================================
# Telegram (Only required for `attach telegram` mode)
# =============================================================================
TELEGRAM_BOT_TOKEN=123456789:ABCdefGHIjklmNOPQrsTUVwxyz

# Allowlist: comma-separated numeric Telegram user IDs (not usernames).
# Single value: ARCCLAW_TELEGRAM_ALLOWED_USER_IDS=123456789
# Multiple values: ARCCLAW_TELEGRAM_ALLOWED_USER_IDS=123456789,987654321
# Leave empty to allow all users (open-access mode).
ARCCLAW_TELEGRAM_ALLOWED_USER_IDS=123456789

# Allowlist: comma-separated Telegram usernames (without @). Deprecated — prefer numeric IDs.
ARCCLAW_TELEGRAM_ALLOWED_USERS=yourusername

# =============================================================================
# Observability
# =============================================================================
QUARKUS_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://localhost:4317
```

> **Security rules**:
> - Never commit your real `.env` file. It is already in `.gitignore`.
> - `ARCCLAW_AUDIT_HMAC_KEY` must remain stable across restarts — changing it breaks the immutable HMAC audit chain verification.
> - `ARCCLAW_VAULT_PEPPER` must remain stable across restarts — changing it invalidates all stored encrypted secrets.
> - The `ARCCLAW_API_KEY_INTERNAL` value is the `X-ArcClaw-API-Key` header value used in all `curl` commands and the REST Client files in [`rest-http/`](./rest-http/).

### 4.3 Understand the Data Directory

By default, `ARCCLAW_DATA_DIR=./data`. ArcClaw creates the following at runtime:

```
data/
├── arcclaw-meta.db          # Main SQLite WAL database (chat memory, audit events, cron jobs,
│                            #   agent checkpoints, encrypted secrets, compaction summaries, skills)
├── arcclaw-fts.db           # Separate FTS5 full-text search index (compaction summaries)
└── workspaces/              # Ephemeral per-session sandboxed working directories
```

---

## 5. Common: Podman / Container Setup

### 5.1 Build the Sandbox Tool Runner Image

The `tool-runner` is the Alpine-based container ArcClaw uses for Tier 1 (Podman rootless) sandboxed tool execution. It **must** be built before running in `%prod` mode. If the image is missing, `PodmanImageHealthCheck` will fail and the application will refuse to start.

**Using Docker Compose (recommended):**

```bash
docker compose build tool-runner
```

**Using Podman directly:**

```bash
podman build -t arcclaw-tool-runner:latest -f sandbox/Dockerfile.toolrunner sandbox/
```

**Verify the image exists:**

```bash
podman images | grep arcclaw-tool-runner
```

### 5.2 Sandbox Tier Behaviour — Three-Tier Degradation

Understanding which tier is active is essential for debugging tool execution.

| Tier | Condition | Isolation | Notes |
|---|---|---|---|
| **1 — Podman Rootless** | `%prod` + Podman socket up + image present | Full container isolation: `--network=none --read-only`, memory/CPU capped | Secrets injected via tmpfs, never as CLI args |
| **2 — Linux Namespaces** | Podman circuit breaker opened after 3 consecutive failures | `unshare` pid + net + mount | Fallback, Linux only |
| **3 — Direct Execution** | `%dev` with `strategy: NONE`, or Windows OS detected | None — host `PATH` `ProcessBuilder` | `gh` CLI must be on `PATH`. **No isolation.** |

> In `%dev` mode (`quarkus:dev`), the strategy defaults to `NONE`. To test full Podman isolation, package the jar and run it with `QUARKUS_PROFILE=prod`.

### 5.3 Systemd Tmpfs Mount for Secrets (Linux/WSL2, Production)

For production deployments on Linux, ArcClaw supports a systemd-managed tmpfs that creates a hardened, in-memory location for secret transport:

```bash
# Copy the unit file
sudo cp arcclaw-secrets.mount /etc/systemd/system/

# Enable and start
sudo systemctl enable --now run-arcclaw-secrets.mount

# Verify the mount exists
ls -la /run/arcclaw/secrets
```

This creates a `700`-mode, `10 MB`, `noexec nosuid nodev` tmpfs at `/run/arcclaw/secrets`. Secrets are written here and memory-wiped when the tool call completes.

### 5.4 sqlite-vec Extension (RAG Vector Search)

ArcClaw's two-tier RAG system uses the `sqlite-vec` native extension (`vec0.so`) for vector similarity search. For development, the extension is included in `lib/` at the repository root:

```
lib/
├── vec0.so      # Linux x86_64 (used in Docker/WSL2)
├── vec0.dll     # Windows (not used in container builds)
└── vec0.dylib   # macOS (not used in container builds)
```

**Development (JVM mode):** Pass the path to the extension at startup:

```bash
# macOS / WSL2 — quarkus:dev
./mvnw quarkus:dev -pl arcclaw-app -am \
  "-Dquarkus.args=serve" \
  "-Darcclaw.rag.vec-extension-path=../lib/vec0"
```

If the extension cannot be loaded (wrong platform, missing file), vector search is automatically disabled and the system falls back to FTS5-only search. Check boot logs for:

```
INFO  FtsConnectionInterceptor: vector search enabled (vec0 loaded)
# or
WARN  FtsConnectionInterceptor: vector search disabled — vec0 extension not loaded
```

**Native Docker image:** The extension is pre-baked at `/app/lib/vec0.so` in `Dockerfile.native`. The `LD_PRELOAD=/lib64/libm.so.6` environment variable is set in the Dockerfile to pre-load `libm` (required for `sqrtf` symbol resolution by `vec0.so` in GraalVM native images).

---

## 6. Common: Build the Application

### 6.1 Standard JVM Build (Fast — Recommended for Development)

Builds all modules (`arcclaw-channel-api`, `arcclaw-core`, `arcclaw-mcp`, `arcclaw-rag`, `arcclaw-deployment`, `arcclaw-app`) and produces a fast-jar.

```bash
# macOS / WSL2
./mvnw clean package -pl arcclaw-app -am -DskipTests

# Windows (PowerShell)
.\mvnw.cmd clean package -pl arcclaw-app -am -DskipTests
```

> The `-am` (also make) flag is critical — it rebuilds `arcclaw-core`, `arcclaw-mcp`, and `arcclaw-rag` before packaging `arcclaw-app`. Omitting it causes stale class issues.

**Windows shortcut (PowerShell helper):**

```powershell
.\mvn-clean-install-skiptests.ps1
```

This script sets the required `JDK_JAVA_OPTIONS` and runs:

```
.\mvnw.cmd clean install -pl arcclaw-core,arcclaw-channel-api,arcclaw-mcp,arcclaw-rag,arcclaw-deployment,arcclaw-app -DskipTests
```

### 6.2 Build Including Tests

```bash
./mvnw clean verify -pl arcclaw-app -am

# Windows
.\mvnw.cmd clean verify -pl arcclaw-app -am
```

### 6.3 Expected Build Output

```
arcclaw-app/target/quarkus-app/
├── quarkus-run.jar      # The main runnable jar (thin launcher)
├── app/                 # Application classes
├── lib/                 # Runtime dependencies
└── quarkus/             # Quarkus framework classes
```

---

## 7. Common: Run Modes

ArcClaw is a dual-mode Quarkus/PicoCLI binary. The mode is controlled by the subcommand passed after the jar.

### 7.1 Dev Mode (Hot Reload — Recommended During Development)

Uses the `%dev` profile (strategy: `NONE`, no container isolation, live code reload).

```bash
# macOS / WSL2
./mvnw quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve" "-Darcclaw.rag.vec-extension-path=../lib/vec0"

# Windows PowerShell (use arcclaw-dev.ps1 for convenience)
.\arcclaw-dev.ps1

# Or manually:
.\mvnw.cmd quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve" `
  "-Darcclaw.rag.vec-extension-path=../lib/vec0" `
  "-Darcclaw.data-dir=../data"

# Suppress Dev UI and console if they cause NPE errors
.\mvnw.cmd quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve" `
  "-Dquarkus.console.enabled=false" `
  "-Dquarkus.devui.enabled=false" `
  "-Darcclaw.rag.vec-extension-path=../lib/vec0"
```

> In `%dev`, tools execute directly on the host. The `gh` CLI must be installed and authenticated (`gh auth login`) for the GitHub tool to work.

### 7.2 Mode A — Daemon (serve)

Run as a long-lived REST API server on port `8080`.

```bash
# From packaged jar (JVM mode)
java \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  "-Dsun.misc.unsafe.memory.access=allow" \
  "-Darcclaw.rag.vec-extension-path=./lib/vec0" \
  -jar arcclaw-app/target/quarkus-app/quarkus-run.jar serve
```

### 7.3 Mode B — Channel Daemon (attach)

Runs ArcClaw as a background daemon servicing external channel adapters (e.g. Telegram via Apache Camel).

```bash
java \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  "-Dsun.misc.unsafe.memory.access=allow" \
  "-Dquarkus.profile=%cli" \
  "-Darcclaw.rag.vec-extension-path=./lib/vec0" \
  -jar arcclaw-app/target/quarkus-app/quarkus-run.jar attach telegram
```

### 7.4 Mode C — Interactive REPL (chat)

Start a multi-turn, stateful conversation directly in your terminal.

```bash
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
  "-Darcclaw.rag.vec-extension-path=./lib/vec0" \
  -jar arcclaw-app/target/quarkus-app/quarkus-run.jar chat
```

### 7.5 Mode D — One-Shot Task (ask)

Execute a single agentic task and exit immediately. Ideal for CI/CD.

```bash
java \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  "-Dsun.misc.unsafe.memory.access=allow" \
  -jar arcclaw-app/target/quarkus-app/quarkus-run.jar ask "Analyze the ArcClaw repository" --role coordinator
```

### 7.6 JVM Flags Reference

All run modes pass specific JVM flags required for compatibility with JDK 25:

| Flag | Reason |
|---|---|
| `--add-opens java.base/java.nio=ALL-UNNAMED` | Netty requires direct ByteBuffer access (JEP 471 restricts `sun.misc.Unsafe` by default in JDK 22+). |
| `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED` | Same Netty/Unsafe requirement. |
| `--add-opens java.base/java.lang=ALL-UNNAMED` | JBoss Threads requires thread-local reset access in JDK 24+. |
| `--enable-native-access=ALL-UNNAMED` | Panama foreign memory access, required by several Quarkus extensions. |
| `-Dsun.misc.unsafe.memory.access=allow` | Suppresses `InaccessibleObjectException` from Netty. |
| `-Dfile.encoding=UTF-8` + encoding flags | Forces UTF-8 on stdin/stdout/stderr so terminal symbols (`✦`, `⚡`, `🛡️`) render correctly, especially on Windows. |
| `-Dquarkus.profile=%cli` | Activates the `%cli` profile: suppresses HTTP server binding, startup banner, and Dev UI. |
| `-Darcclaw.rag.vec-extension-path=./lib/vec0` | Path to the sqlite-vec native extension for two-tier RAG vector search. Omit to fall back to FTS5-only. |

### 7.7 Telegram Bot Setup (Required for `attach telegram` Mode)

If you intend to use ArcClaw via Telegram, you must create a bot before running in `attach` mode.

**Step 1 — Create a Bot via BotFather:**

1. Open Telegram and search for `@BotFather`.
2. Send `/newbot`.
3. Provide a display name (e.g. `ArcClaw Dev Bot`) and a username ending in `bot` (e.g. `arcclaw_dev_bot`).
4. Copy the HTTP API token (format: `123456789:ABCdefGHIjklmNOPQrsTUVwxyz`).

**Step 2 — Find Your Telegram User ID:**

1. Search for `@userinfobot` on Telegram.
2. Start a conversation — it replies with your numeric user ID (e.g. `123456789`).

**Step 3 — Configure `.env`:**

```env
TELEGRAM_BOT_TOKEN=123456789:ABCdefGHIjklmNOPQrsTUVwxyz
ARCCLAW_TELEGRAM_ALLOWED_USER_IDS=123456789
ARCCLAW_TELEGRAM_ALLOWED_USERS=yourusername
```

**Step 4 — Start in Attach Mode:**

```bash
./mvnw quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=attach"
# or:
java ... -jar quarkus-run.jar attach telegram
```

**Verify in logs:**

```
INFO  [TelegramChannelRoute] Telegram route self-registered and started via StartupEvent.
INFO  [TelegramChannelRoute] routeStatus=Started
```

If you see `Telegram bot token is a placeholder — skipping route registration`, the token is not set.

---

## 8. Common: Docker Compose Stack (Full Observability)

The Docker Compose stack provides the full production-equivalent environment: ArcClaw (native binary) + Ollama + OTel Collector + Jaeger + Prometheus.

### 8.1 Start the Full Stack

```bash
docker compose up -d
```

This starts all services defined in `docker-compose.yaml`:

| Service | Port | Notes |
|---|---|---|
| `arcclaw` | `8080` | Native binary, `QUARKUS_PROFILE=prod`, Podman sandbox active |
| `ollama` | `11434` | Local LLM provider (disabled in `application.yaml` by default) |
| `otel-collector` | `4317`, `4318` | OTLP receiver forwarding to Jaeger |
| `jaeger` | `16686` | Distributed trace UI |
| `prometheus` | `9090` | Metrics scraper (polls `http://host.docker.internal:8080/q/metrics`) |

> The `init-perms` service runs first to `chown -R 1000:1000 /app/data` on the `arcclaw_data` volume, then exits. The main `arcclaw` container waits for it via `condition: service_completed_successfully`.

### 8.2 Pre-flight: Ensure the Native Binary Exists

The `arcclaw` service in Compose expects a pre-built native binary at `arcclaw-app/target/*-runner`. See [Section 9](#9-common-native-binary-build) for build instructions. If the binary is absent, `docker compose up` will fail with `lstat /arcclaw-app/target: no such file or directory`.

### 8.3 Stop the Stack

```bash
docker compose down
# To also remove volumes (deletes all data!):
docker compose down -v
```

### 8.4 View Logs

```bash
docker compose logs -f arcclaw
```

---

## 9. Common: Native Binary Build

> **Warning**: The native build requires significant RAM and takes 5–15 minutes. A minimum of 8 GB free RAM is recommended. The build uses the Mandrel builder image (`quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-25`) inside Podman.

### 9.1 Build the Native Binary

```bash
# macOS / WSL2 (uses Podman as the container runtime)
./mvnw clean package -pl arcclaw-app -am -Pnative -DskipTests

# Windows PowerShell
.\mvnw.cmd clean package -pl arcclaw-app -am -Pnative -DskipTests
```

Output: `arcclaw-app/target/arcclaw-app-1.0.0-SNAPSHOT-runner`

### 9.2 Run the Native Binary (Linux/WSL2)

Use the provided wrapper script which pre-loads `libm` (required by `vec0.so`):

```bash
./arcclaw-native.sh serve
```

Or manually with `LD_PRELOAD`:

```bash
export LD_PRELOAD=/lib/x86_64-linux-gnu/libm.so.6
export SQLITE_VEC_PATH=/app/lib/vec0
./arcclaw-app/target/arcclaw-app-1.0.0-SNAPSHOT-runner serve
```

> `LD_PRELOAD` ensures `libm.so.6` is loaded before `vec0.so` is opened by SQLite, resolving the `sqrtf` symbol that GraalVM native image does not statically link.

### 9.3 Build with Custom Memory Limit

If the build runs out of memory:

```bash
./mvnw clean package -pl arcclaw-app -am -Pnative -DskipTests \
  "-Dquarkus.native.additional-build-args=-J-Xmx6g"
```

Or increase Podman machine memory before building:

```bash
podman machine stop
podman machine set --memory 8192
podman machine start
```

---

## 10. Windows PowerShell (Native Wrapper)

ArcClaw ships PowerShell convenience wrappers for Windows development:

| Script | Purpose |
|---|---|
| `arcclaw-cli.ps1 serve` | Start the REST daemon (uses built jar, sets UTF-8 encoding) |
| `arcclaw-cli.ps1 attach` | Start Telegram daemon |
| `arcclaw-cli.ps1 chat` | Start interactive REPL |
| `arcclaw-cli.ps1 ask "goal"` | One-shot task and exit |
| `arcclaw-dev.ps1` | Start dev mode with hot reload (sets all JVM flags) |
| `mvn-clean-install-skiptests.ps1` | Full build without tests |

**Example:**

```powershell
.\arcclaw-cli.ps1 serve
```

> These wrappers set `DOCKER_HOST` and all required JVM flags automatically. For native builds on Windows, run inside WSL2 Ubuntu — GraalVM native compilation requires Linux.

---

## Troubleshooting

### `./mvnw: cannot execute: required file not found` (WSL2)

Windows CRLF line endings in the Maven wrapper. Fix:

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

Confirm `DOCKER_HOST` is set correctly:

```bash
echo $DOCKER_HOST
# Expected: unix:///run/user/1000/podman/podman.sock
```

If your UID is not `1000`:

```bash
id -u
export DOCKER_HOST="unix:///run/user/$(id -u)/podman/podman.sock"
# Then update ~/.bashrc with your actual UID
```

### Podman `permission denied` with Docker CLI

```bash
sudo usermod -aG $(getent group 10 | cut -d: -f1) $USER
# Log out and log back in
```

### Sandbox Strategy is `NONE` When You Expect `PODMAN`

Check the following in order:
1. Are you running `quarkus:dev`? It always uses `NONE`. Package and run the jar with `QUARKUS_PROFILE=prod`.
2. Was Podman running when ArcClaw started? The circuit breaker evaluates `podman --version` at boot — starting Podman afterwards won't help. Restart ArcClaw.
3. Are you running the jar in PowerShell on Windows? The OS check detects `Windows` and bypasses Linux namespaces. Run inside WSL2 Ubuntu instead.

### `InaccessibleObjectException` / Unsafe Memory Warnings

Expected on JDK 22+ with Netty. They are suppressed by the JVM flags in `pom.xml`. If you see them running the jar manually, ensure all `--add-opens` and `-Dsun.misc.unsafe.memory.access=allow` flags from [Section 7.6](#76-jvm-flags-reference) are present.

### `open //./pipe/dockerDesktopLinuxEngine` (Windows Podman Desktop)

```powershell
$env:DOCKER_HOST="npipe:////./pipe/podman-machine-default"
```

Or enable "Docker API compatibility" in Podman Desktop → Settings → General.

### OOM During Native Build

The native profile sets `-J-Xmx8g` by default. If memory is limited:

```bash
./mvnw clean package -pl arcclaw-app -am -Pnative -DskipTests \
  "-Dquarkus.native.additional-build-args=-J-Xmx6g"
```

Or increase Podman machine memory before building:

```bash
podman machine stop
podman machine set --memory 8192
podman machine start
```

### `lstat /arcclaw-app/target: no such file or directory` (Docker Compose)

`Dockerfile.native` expects the pre-built native binary on the host. Run the native build step ([Section 9](#9-common-native-binary-build)) before `docker compose up --build`.

### Ollama Port Collision (`bind: address already in use` on 11434)

Quit your local Ollama app or remap the port in `docker-compose.yaml`:

```yaml
ollama:
  ports:
    - "11435:11434"
```

### `@WorkspaceScoped` CDI Errors in Tests

Ensure the `arcclaw-deployment` module is on the classpath. The `WorkspaceScopeProcessor` `@BuildStep` registers the custom scope at Quarkus build time — without it, CDI scope resolution fails.

### Groq API Key Not Working

- Verify the key starts with `gsk_` with no extra whitespace.
- Confirm `ARCCLAW_AUTO_SEED_SECRETS=true` is in `.env`.
- Check boot logs for: `Seeding secret: GROQ_API_KEY`.

### FTS5 Boot Error — Virtual Table Not Created

If `AuditSchemaEnforcer: FTS5 memory index and triggers verified.` is absent from boot logs, SQLite may not support FTS5 on your system. Verify:

```bash
sqlite3 :memory: "CREATE VIRTUAL TABLE t USING fts5(x);"
# No error = FTS5 is available
```

If FTS5 is not available, install a newer SQLite version:

```bash
sudo apt install sqlite3   # Ubuntu — recent packages include FTS5
```

### FTS5 Backfill (Upgrading from pre-Phase 45)

```bash
sqlite3 data/arcclaw-meta.db
```

```sql
ATTACH DATABASE './data/arcclaw-fts.db' AS fts;
.read backfill_fts5.sql
.quit
```

### `Permission denied` on SQLite Data Volume (Docker)

Docker creates named volumes as `root:root`. ArcClaw runs as `arcclaw` (UID 1000). The fix is the **Init-Container pattern**: the `init-perms` service in `docker-compose.yaml` briefly boots as root, runs `chown -R 1000:1000 /app/data`, and exits before the main `arcclaw` container starts (`depends_on: init-perms: condition: service_completed_successfully`).

### Why Does `containers.conf` Force the `vfs` Storage Driver?

Inside a Docker container, standard storage drivers (`overlayfs`, `btrfs`) fail because the host kernel restricts unprivileged mounts inside containers. The `vfs` (Virtual File System) driver is a universal fallback: it creates deep copies of container layers instead of copy-on-write snapshots. It is slower and more disk-intensive than `overlayfs`, but it works in Docker-in-Docker and Kubernetes pods without `privileged` storage. Run `podman system prune -f` periodically to reclaim disk space from accumulated layer copies.

### sqlite-vec Vector Search Disabled at Runtime

If boot logs show `vector search disabled — vec0 extension not loaded`:
1. Verify the correct extension file exists for your platform in `lib/` (`vec0.so` for Linux, `vec0.dylib` for macOS).
2. Ensure `-Darcclaw.rag.vec-extension-path=../lib/vec0` is passed (no `.so` / `.dylib` extension — SQLite appends it automatically based on the platform).
3. For native image on Linux: check that `LD_PRELOAD=/lib64/libm.so.6` is set before the binary starts. Without it, `vec0.so` fails to load due to `undefined symbol: sqrtf`.

ArcClaw degrades gracefully to FTS5-only search when sqlite-vec is unavailable — no error is thrown.

---

## 📚 Related Documentation

| Document | Purpose |
|---|---|
| [`README.md`](./README.md) | Architecture overview, agent personas, security model, extension guide |
| [`SETUP.md`](./SETUP.md) | Prerequisites, OS-specific setup, build, run modes, Docker Compose, native build |
| [`TELEGRAM_TESTING.md`](./TELEGRAM_TESTING.md) | Telegram bot end-to-end testing (19 sections) |
| [`FAQ.md`](./FAQ.md) | In-depth architecture Q&A, security rationale, and operational guidance |
| [`rest-http/`](./rest-http/) | IntelliJ / VSCode REST Client files — see `rest-http/README.md` for full index |

---

*Last updated: ArcClaw V1.0.0 · Quarkus 3.27.2 · JDK 25*

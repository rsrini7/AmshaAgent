# ArcClaw — Setup Guide

> **Stack**: JDK 25 · Quarkus 3.23.0 · LangChain4J 0.26.1 · SQLite (WAL) · Podman (rootless) · Groq Cloud
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
# Primary reasoning (llama-3.3-70b-versatile) + intent classifier (llama-3.1-8b-instant)
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

# Allowlist: space-separated numeric Telegram user IDs (not usernames)
ARCCLAW_TELEGRAM_ALLOWED_USER_IDS=123456789

# Allowlist: space-separated Telegram usernames (without @)
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
├── arcclaw.db          # Main SQLite WAL database (chat memory, audit events, cron jobs,
│                       #   agent checkpoints, encrypted secrets, compaction summaries)
├── arcclaw-fts.db      # Separate FTS5 full-text search index (compaction summaries)
└── workspaces/         # Ephemeral per-session sandboxed working directories
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

---

## 6. Common: Build the Application

### 6.1 Standard JVM Build (Fast — Recommended for Development)

Builds all modules (`arcclaw-channel-api`, `arcclaw-core`, `arcclaw-deployment`, `arcclaw-app`) and produces a fast-jar.

```bash
# macOS / WSL2
./mvnw clean package -pl arcclaw-app -am -DskipTests

# Windows (PowerShell)
.\mvnw.cmd clean package -pl arcclaw-app -am -DskipTests
```

> The `-am` (also make) flag is critical — it rebuilds `arcclaw-core` before packaging `arcclaw-app`. Omitting it causes stale class issues.

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
./mvnw quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve"

# Windows PowerShell
.\mvnw.cmd quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve"

# Suppress Dev UI and console if they cause NPE errors
.\mvnw.cmd quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve" `
  "-Dquarkus.console.enabled=false" `
  "-Dquarkus.devui.enabled=false"
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

---

## 8. Common: Docker Compose Stack (Full Observability)

### 8.1 Services in the Stack

| Service | Port | Purpose |
|---|---|---|
| `arcclaw` | `8080` | Main application (native binary build) |
| `ollama` | `11434` | Local LLM provider (disabled by default in `application.yaml`) |
| `otel-collector` | `4317` (gRPC), `4318` (HTTP) | OpenTelemetry collector — forwards spans to Jaeger |
| `jaeger` | `16686` (UI) | Distributed trace viewer |
| `prometheus` | `9090` | Metrics scraper — scrapes `/q/metrics` |
| `init-perms` | — | One-shot service to fix data volume ownership (UID 1000) |
| `tool-runner` | — | Build-only service (profile: `builder`) |

### 8.2 Starting the Stack

> **`privileged: true` note**: The `arcclaw` service in `docker-compose.yaml` requires `privileged: true`. ArcClaw runs **Podman inside Docker** (nested containerisation). For rootless Podman to create user namespaces and cgroup hierarchies inside a Docker container, it needs permission to call `unshare()` and `newuidmap`. Docker restricts these syscalls by default. The `privileged: true` flag grants the container the full Linux capability set so Podman can manage nested namespaces correctly. This is the standard approach for container-in-container architectures and is documented in the Podman community for CI/CD environments.

```bash
# Standard start
docker compose up -d

# Clean rebuild of the ArcClaw image
docker compose up -d --build

# No-cache rebuild
docker compose build --no-cache
docker compose up -d
```

### 8.3 Service URLs

| Service | URL |
|---|---|
| ArcClaw REST API | http://localhost:8080/api/v1 |
| Swagger / OpenAPI UI | http://localhost:8080/q/swagger-ui |
| Health Check | http://localhost:8080/q/health |
| Prometheus Metrics | http://localhost:8080/q/metrics |
| Jaeger Trace UI | http://localhost:16686 |
| Prometheus UI | http://localhost:9090 |

### 8.4 Windows Podman Desktop

If you receive `open //./pipe/dockerDesktopLinuxEngine`:

```powershell
$env:DOCKER_HOST="npipe:////./pipe/podman-machine-default"
docker compose up -d
```

Or enable "Docker API compatibility" in Podman Desktop → Settings → General.

### 8.5 Ollama Port Collision

If Ollama is already running on your host on port `11434`, Docker Compose will fail with `bind: address already in use`. Either quit the local Ollama process, or remap the port:

```yaml
# docker-compose.yaml
ollama:
  ports:
    - "11435:11434"
```

---

## 9. Common: Native Binary Build

A native binary provides ~10 ms startup and produces an ~118 MB self-contained executable. This is what `Dockerfile.native` expects.

### 9.1 Option A — Container-Build (Recommended, No GraalVM Required)

Quarkus pulls the Mandrel builder image and compiles inside a container. Podman must be running.

```bash
# macOS / WSL2
./mvnw clean package -pl arcclaw-app -am -Pnative -DskipTests

# Windows
.\mvnw.cmd clean package -pl arcclaw-app -am -Pnative -DskipTests
```

The native profile in `arcclaw-app/pom.xml` sets: `container-build=true`, `container-runtime=podman`, `builder-image=quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-25`, and `-J-Xmx8g` to prevent OOM during analysis.

### 9.2 Option B — Local GraalVM/Mandrel (Avoids Podman Memory Usage)

```bash
./mvnw clean package -Pnative -Dquarkus.native.container-build=false -DskipTests
```

### 9.3 Output & Running the Binary

```
arcclaw-app/target/arcclaw-app-1.0.0-SNAPSHOT-runner   # ~118 MB
```

```bash
./arcclaw-app/target/arcclaw-app-1.0.0-SNAPSHOT-runner serve
```

> `Dockerfile.native` expects the pre-built native binary on the host. Run the native build step (this section) before `docker compose up --build`.

---

## 10. Windows PowerShell (Native Wrapper)

`arcclaw-cli.ps1` forces `QUARKUS_PROFILE=cli`, applies all UTF-8 encoding flags, and passes all arguments directly to the jar.

```powershell
# Interactive REPL
.\arcclaw-cli.ps1 chat

# One-shot task (long form role)
.\arcclaw-cli.ps1 ask "Who are you?" --role coordinator

# One-shot task (shorthand role flag)
.\arcclaw-cli.ps1 ask "who are you" -r debugger

# Daemon mode
.\arcclaw-cli.ps1 serve
```

> **Set ExecutionPolicy if needed:**
> ```powershell
> Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
> ```

> The rebuild prompt (`Read-Host "Rebuild/Package application before running? (y/N)"`) is commented out in the shipping version — the wrapper is designed for day-to-day usage where you have already built the JAR. Uncomment the block if you want the interactive rebuild prompt during active development cycles.

---

## 📚 Related Documentation

| Document | Purpose |
|---|---|
| [`README.md`](./README.md) | Architecture overview, agent personas, security model, extension guide |
| [`TESTING_GUIDE.md`](./TESTING_GUIDE.md) | Unit tests, E2E REST, sandbox verification, checklist, troubleshooting |
| [`TELEGRAM_TESTING.md`](./TELEGRAM_TESTING.md) | Telegram bot end-to-end testing |
| [`FAQ.md`](./FAQ.md) | In-depth architecture Q&A |
| [`rest-http/`](./rest-http/) | IntelliJ / VSCode REST Client files for interactive API testing |

---

*Last updated: ArcClaw V1.0.0 · Quarkus 3.23.0 · JDK 25*

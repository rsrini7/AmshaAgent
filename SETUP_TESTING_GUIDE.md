# 🛡️ ArcClaw — Complete Setup & Testing Guide

> **Stack**: JDK 25 · Quarkus 3.23.0 · LangChain4J 0.26.1 · SQLite (WAL) · Podman (rootless) · Groq Cloud
>
> This is the single source of truth for setting up and verifying ArcClaw on **macOS**, **WSL2 (Ubuntu)**, and any platform where commands are shared. Work through the Setup phases in order before moving to the Testing section.

---

## 📋 Table of Contents

**Part I — Setup**
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

**Part II — Testing & Verification**
11. [Unit & Integration Test Suite](#11-unit--integration-test-suite)
12. [E2E REST API Workflow](#12-e2e-rest-api-workflow)
13. [Distributed Tracing (Jaeger)](#13-distributed-tracing-jaeger)
14. [Sandbox Isolation Tier Testing](#14-sandbox-isolation-tier-testing)
15. [Secure Secret Passing Verification](#15-secure-secret-passing-verification)
16. [Context Compaction Test](#16-context-compaction-test)
17. [SQLite FTS5 & Agent Memory RAG Testing](#17-sqlite-fts5--agent-memory-rag-testing)
18. [Telegram Bot Integration & Verification](#18-telegram-bot-integration--verification)
19. [API Key Generation Reference](#19-api-key-generation-reference)
20. [Final Verification Checklist](#20-final-verification-checklist)
21. [Troubleshooting](#21-troubleshooting)

---

# Part I — Setup

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

### 4.2 Create Your `.env` File

ArcClaw loads all secrets and runtime config from a `.env` file via `DotenvConfigSourceProvider`.

```bash
cp .env.example .env
```

Open `.env` and fill in the values:

```env
# =============================================================================
# Core Settings
# =============================================================================
QUARKUS_PROFILE=prod
ARCCLAW_DATA_DIR=./data          # Or an absolute path, e.g. /home/user/arcclaw-data
                                 # On Windows: C:\Users\<user>\arcclaw-data

# CORS (comma-separated list of allowed origins)
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

# REST API keys — see Section 19 for how to generate secure values
ARCCLAW_API_KEY_INTERNAL=key-internal-<generate-a-random-string>
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

> **Security rules**:
> - Never commit your real `.env` file. It is already in `.gitignore`.
> - `ARCCLAW_AUDIT_HMAC_KEY` must remain stable across restarts — changing it breaks the immutable HMAC audit chain verification.
> - The `ARCCLAW_API_KEY_INTERNAL` value is the `X-ArcClaw-API-Key` header value used in all `curl` commands and the `api.http` file.

### 4.3 Understand the Data Directory

By default, `ARCCLAW_DATA_DIR=./data`. ArcClaw creates the following at runtime:

```
data/
├── arcclaw.db          # Main SQLite WAL database (chat memory, audit events, cron jobs)
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

---

# Part II — Testing & Verification

---

## 11. Unit & Integration Test Suite

### 11.1 Full Test Suite

Runs all 40+ unit and integration tests across all modules using Surefire and Failsafe.

```bash
# macOS / WSL2
./mvnw clean verify -pl arcclaw-app -am

# Windows
.\mvnw.cmd clean verify -pl arcclaw-app -am
```

Mockito inline mock attachment works automatically because the required `--add-opens` JVM flags are embedded in `arcclaw-app/pom.xml`.

### 11.2 Run Specific Tests

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

### 11.3 Key Test Classes Reference

| Test Class | Module | What It Covers |
|---|---|---|
| `PersistentChatMemoryStoreTest` | `arcclaw-core` | SQLite WAL chat memory persistence |
| `LangChainReasoningEngineTest` | `arcclaw-core` | LLM reasoning loop with Mockito-mocked LLM |
| `AgentLoopTest` | `arcclaw-core` | ReAct loop iteration, FINISH detection, max iterations |
| `AgentResilienceTest` | `arcclaw-core` | Loop failure recovery and circuit breaker behaviour |
| `GovernanceLayerTest` | `arcclaw-core` | Budget circuit breaker, HITL approval gates |
| `SecretVaultImplTest` | `arcclaw-core` | AES-256-GCM-SIV encryption/decryption |
| `TmpfsSecretTransportTest` | `arcclaw-core` | Tmpfs file lifecycle and zero-copy memory wipe |
| `SandboxCircuitBreakerTest` | `arcclaw-core` | Podman circuit breaker trip-and-reset behaviour |
| `CompactionSummaryRepositoryTest` | `arcclaw-core` | FTS5 schema, tenant isolation, and search queries |
| `MemorySearchToolTest` | `arcclaw-app` | Agent memory RAG tool: ScopedValue, output format |
| `PodmanHealthCheckTest` | `arcclaw-app` | Podman socket liveness health check |
| `TelegramChannelRouteTest` | `arcclaw-app` | Apache Camel Telegram route message flow |
| `ApiKeyAuthFilterTest` | `arcclaw-app` | REST API key authentication filter |
| `StartupSecurityValidatorTest` | `arcclaw-app` | Production safety validation on boot |
| `AgentCronSchedulerTest` | `arcclaw-app` | Scheduled cron job execution |
| `AgentCronSchedulerConcurrencyTest` | `arcclaw-app` | Concurrent cron scheduling under virtual threads |
| `AuditServiceIntegrationTest` | `arcclaw-app` | Immutable HMAC audit chain integrity |
| `WorkspaceScopeTest` | `arcclaw-app` | Custom CDI `@WorkspaceScoped` lifecycle |
| `AuditServiceIntegrationTest` | `arcclaw-app` | Immutable HMAC audit chain |

### 11.4 Test Profile Notes

Tests use the `%test` profile from `arcclaw-app/src/test/resources/application.yaml`. Key differences from `%dev`/`%prod`:
- Sandbox strategy is always `NONE` — no container execution during tests.
- An in-process SQLite test database is used.
- LLM calls are mocked via Mockito `@InjectMock` — no real API calls are made.

---

## 12. E2E REST API Workflow

These steps verify the full request path: REST → AgentLoop → LLM → SQLite checkpoint.

### Prerequisites

Ensure the application is running in serve mode (dev or packaged jar — see [Section 7](#7-common-run-modes)).

```bash
# Quickest way to start
./mvnw quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve"
```

### Step 1 — Create a Session

```bash
curl -X POST http://localhost:8080/api/v1/sessions \
  -H "X-ArcClaw-API-Key: key-internal-changeme"
```

Expected response:

```json
{"sessionId":"abcd-1234-efgh-5678"}
```

Set the session ID for subsequent commands:

```bash
# Linux / macOS
export SESSION_ID="abcd-1234-efgh-5678"

# Windows PowerShell
$SESSION_ID="abcd-1234-efgh-5678"
```

### Step 2 — Run an Agent (Asynchronous)

Delegate a task to the Vishnu coordinator agent. The endpoint is non-blocking and returns immediately with `202 Accepted`.

```bash
# Linux / macOS
curl -X POST "http://localhost:8080/api/v1/sessions/$SESSION_ID/run-async" \
  -H "Content-Type: application/json" \
  -H "X-ArcClaw-API-Key: key-internal-changeme" \
  -d '{
        "goal": "Analyze the arcclaw repository on GitHub and tell me what it does",
        "agentRole": "coordinator",
        "maxIterations": 3
      }'

# Windows PowerShell
curl -X POST "http://localhost:8080/api/v1/sessions/$SESSION_ID/run-async" `
  -H "Content-Type: application/json" `
  -H "X-ArcClaw-API-Key: key-internal-changeme" `
  -d '{ "goal": "Analyze the arcclaw repository on GitHub and tell me what it does", "agentRole": "coordinator" }'
```

Expected response: `HTTP 202 Accepted`

Valid `agentRole` values: `coordinator`, `researcher`, `coder`, `debugger`

### Step 3 — Poll Session Status

Poll repeatedly to watch the agent's progress. The `steps` array shows the live Thought → Action → Observation trace.

```bash
# Linux / macOS
curl "http://localhost:8080/api/v1/sessions/$SESSION_ID/status/coordinator" \
  -H "X-ArcClaw-API-Key: key-internal-changeme"

# Windows PowerShell
curl "http://localhost:8080/api/v1/sessions/$SESSION_ID/status/coordinator" `
  -H "X-ArcClaw-API-Key: key-internal-changeme"
```

Expected response: JSON payload with `status` field (`IN_PROGRESS`, `AWAITING_APPROVAL`, `COMPLETED`, or `FAILED`) and a `steps` array showing the agent's thoughts and actions.

### Step 4 — Read the Terminal Logs

A successful async run produces this exact log sequence, matching the architecture diagram:

1. `Spawning virtual thread for async agent run, session: <id>` — HTTP worker offloads to a JDK 25 Virtual Thread.
2. `AgentLoop starting for goal... (maxIterations=5)` — `@WorkspaceScoped` session activates.
3. `HTTP request: - url: https://api.groq.com/openai/v1/chat/completions` — LangChain4J calls Groq.
4. `Agent reached FINISH at iteration 1` — LLM signals completion.
5. `Created checkpoint for session=<id> role=coordinator` then `Checkpoint id=2 marked COMPLETED` — Hibernate ORM writes the reasoning trajectory to SQLite.

### Using `api.http` (IntelliJ / VSCode REST Client)

Instead of manual `curl` commands, you can use the provided `api.http` file:
1. Open `api.http` in IntelliJ IDEA or VSCode (with the REST Client extension).
2. Run the first request — it automatically captures the `sessionId` as a variable.
3. Run subsequent async or sync requests without copying IDs manually.

---

## 13. Distributed Tracing (Jaeger)

ArcClaw emits OTLP spans for every REST request, agent loop iteration, and tool execution via the OTel Collector.

**Steps to view traces:**

1. Ensure the compose stack is running (`docker compose up -d`).
2. Trigger any agent run via the REST API (see Section 12).
3. Open the Jaeger UI: [http://localhost:16686](http://localhost:16686)
4. In the **Service** dropdown, select `arcclaw`.
5. Click **Find Traces**.
6. Click an individual trace to see the full span hierarchy: HTTP ingress → AgentLoop → LLM call → Sandbox tool execution → SQLite checkpoint.

> Note: Internal reasoning spans (individual Thought/Action steps) are stubbed as no-ops in V1.0.0. The trace shows HTTP-level and tool-level spans.

---

## 14. Sandbox Isolation Tier Testing

These tests mathematically prove that each of the three sandbox tiers is working as designed.

### The Windows / WSL2 Boundary Trap

> **Critical**: If you are developing on Windows and want to verify Tier 1 (Podman rootless), you **cannot** run the `.jar` in PowerShell. `ProcessIsolationExecutor` checks `System.getProperty("os.name")`. If it detects `Windows`, it bypasses Linux namespaces and falls directly to host execution — regardless of your strategy configuration.
>
> Additionally, **Podman Desktop runs in its own isolated WSL VM** (`podman-machine-default`). Your Ubuntu WSL distribution cannot see Podman Desktop's containers. Do not try to bridge them.
>
> **The correct approach**: Install Podman natively inside your Ubuntu WSL distribution (see [Section 3](#3-wsl2-ubuntu-setup)) and run the jar from inside Ubuntu.

### 14.1 Understanding Profile vs. Strategy

| Execution Mode | Active Profile | Expected Sandbox Strategy |
|---|---|---|
| `./mvnw quarkus:dev ...` | `%dev` | `NONE` — direct host execution, hardcoded for developer velocity |
| `java -jar quarkus-run.jar serve` | `%prod` | `PODMAN` — full rootless container isolation |
| `java -Dquarkus.profile=prod -jar ...` | `%prod` | `PODMAN` — forces prod profile from any terminal |

To force `%prod` mode for local sandbox testing without the compose stack:

```bash
java -Dquarkus.profile=prod \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  "-Dsun.misc.unsafe.memory.access=allow" \
  -jar arcclaw-app/target/quarkus-app/quarkus-run.jar serve
```

### 14.2 The Circuit Breaker Behaviour

`ProcessIsolationExecutor` determines its capability **exactly once at startup**:
- It runs `podman --version` during the first 5 seconds of boot.
- If Podman is not running or not on the `PATH` at that moment, the circuit breaker **trips permanently** for that JVM session.
- Starting Podman after the application has booted will **not** fix it — you must restart ArcClaw.

### 14.3 Tier 1 — Full Podman Isolation Test (Linux / WSL2 Native)

**Prerequisites**: Podman installed natively in Linux/WSL2, `arcclaw-tool-runner:latest` image built.

**Step 1** — Build the tool runner image inside Ubuntu (if not done already):

```bash
podman build -t arcclaw-tool-runner:latest -f sandbox/Dockerfile.toolrunner sandbox/
```

**Step 2** — Build and run the jar in prod mode inside Ubuntu:

```bash
./mvnw clean package -pl arcclaw-app -am -DskipTests
java -Dquarkus.profile=prod \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  "-Dsun.misc.unsafe.memory.access=allow" \
  -jar arcclaw-app/target/quarkus-app/quarkus-run.jar serve
```

**Step 3** — Verify via boot logs. In the **first 50 lines** of console output:

```
✅  INFO: Isolation strategy: PODMAN initialized.        → Full Security — Tier 1 active
⚠️  WARN: Podman not available — circuit open, falling back...  → Degraded — Tier 2 or 3
```

**Step 4** — Trigger a GitHub tool call via REST or Telegram. Watch the logs: ArcClaw should launch an ephemeral `arcclaw-tool-runner` container, execute `gh`, and tear it down — all within a single tool call.

### 14.4 Tier 2 — Linux Namespace Fallback Test

To force the circuit breaker open for testing purposes:

```bash
# Stop Podman before starting ArcClaw
systemctl --user stop podman.socket

java -Dquarkus.profile=prod -jar arcclaw-app/target/quarkus-app/quarkus-run.jar serve

# Boot log should show:
# WARN: Podman not available — circuit open, falling back to NAMESPACE strategy.
```

Trigger a tool call — it should execute using `unshare` namespace isolation instead of a container.

### 14.5 Tier 3 — Direct Execution Test (Dev Mode)

This is the default in `%dev`. No special setup needed:

```bash
./mvnw quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve"
```

Ensure `gh` is on your `PATH` and authenticated. Trigger a GitHub tool call — it should execute directly on the host without any container wrapping.

### 14.6 Automated Sandbox Tests

Run the unit tests that cover circuit breaker and namespace logic without needing a live Podman daemon:

```bash
./mvnw test -pl arcclaw-core -am \
  "-Dtest=SandboxCircuitBreakerTest,ProcessIsolationExecutorTest,TmpfsSecretTransportTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

---

## 15. Secure Secret Passing Verification

ArcClaw uses a zero-string security model: secrets are injected into tool processes via a tmpfs mount and memory-wiped immediately after use. This section verifies that model is working.

### 15.1 Production Tmpfs Verification (Linux / WSL2)

```bash
# Verify the tmpfs mount is active
mount | grep arcclaw-secrets
# Expected output: tmpfs on /run/arcclaw/secrets type tmpfs (rw,nosuid,nodev,noexec,mode=700,size=10240k,...)

# Before a tool call, the directory should be empty
ls -la /run/arcclaw/secrets
```

Trigger an agent tool call that requires secrets (e.g. the GitHub tool via a `researcher` role query). During execution, the `.env` file will momentarily appear in `/run/arcclaw/secrets` and be deleted as soon as the container process exits.

### 15.2 Development Fallback Verification

If running in `%dev` mode or without root access for the tmpfs mount, the application falls back to `/tmp`. Check the logs for:

```
INFO  tmpfs path /run/arcclaw/secrets is inaccessible. Falling back to /tmp/...
```

### 15.3 Automated Memory Security Tests

```bash
# macOS / WSL2
./mvnw test -pl arcclaw-core -am \
  "-Dtest=TmpfsSecretTransportTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false"

# Windows
.\mvnw.cmd test -pl arcclaw-core -am `
  "-Dtest=TmpfsSecretTransportTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected test outcomes:
- `testLifecycle` — verifies the secret file is created before tool execution and automatically deleted after.
- `testZeroCopyWrite` — verifies that the `byte[]` holding the secret is zeroed out in memory after the write operation completes.

---

## 16. Context Compaction Test

Context compaction fires automatically when a session accumulates more than the configured threshold of messages (default: 16). It summarises older history via LLM, writes the summary to SQLite and the FTS5 index, and trims the in-memory chat window.

### 16.1 Manual Trigger Test

```bash
# Start the application
./mvnw quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve"
```

Send **17 messages** in a single Telegram session (or via the REST API chat mode) — any content. Then verify the compaction fired:

**In the application logs, look for:**

```
INFO  Context compacted for session <id>. Compacted N messages, kept M.
```

**Verify the SQLite records directly:**

```bash
# Check compaction_summary was written
sqlite3 data/arcclaw.db "SELECT * FROM compaction_summary;"
# Expected: 1 row with sessionId, summary text, and compactedAt timestamp

# Verify the audit event was recorded
sqlite3 data/arcclaw.db \
  "SELECT eventType, sessionId, timestamp FROM audit_event WHERE eventType = 'CONTEXT_COMPACTED';"
# Expected: 1 row

# Inspect the reasoning step trajectory
sqlite3 data/arcclaw.db \
  "SELECT * FROM reasoning_step ORDER BY checkpoint_id, sequence_num;"
```

### 16.2 Automated Compaction Tests

```bash
./mvnw test -pl arcclaw-core -am \
  "-Dtest=ContextCompactionServiceTest,ContextCompactionServiceIntegrationTest,CompactionSummaryRepositoryTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

---

## 17. SQLite FTS5 & Agent Memory RAG Testing

The `MemorySearchTool` (used by `coordinator` and `researcher` roles) queries a dedicated `arcclaw-fts.db` FTS5 full-text search database built from compaction summaries. This section verifies the schema, tenant isolation, and LLM orchestration.

### 17.1 Step 1 — Verify the FTS5 Schema on Boot

Since `%dev` uses `drop-and-create`, the first test is simply starting the application and reading the boot logs.

```bash
./mvnw quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve"
```

Watch the console during startup. Look for:

```
INFO  AuditSchemaEnforcer: FTS5 memory index and triggers verified.
```

If you see a SQLite syntax error instead, the FTS5 virtual table was not created successfully. The `MemorySearchTool` will fail at runtime if this line is absent.

### 17.2 Step 2 — Run the Automated Tests

These tests verify the FTS5 Java bindings and tenant isolation logic without spending any LLM tokens.

**Test the Repository (FTS5 schema, tenant isolation, search queries):**

```bash
# macOS / WSL2
./mvnw test -pl arcclaw-core -am \
  "-Dtest=CompactionSummaryRepositoryTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false"

# Windows
.\mvnw.cmd test -pl arcclaw-core `
  "-Dtest=CompactionSummaryRepositoryTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

**Test the Tool (ScopedValue propagation, output formatting):**

```bash
# macOS / WSL2
./mvnw test -pl arcclaw-app -am \
  "-Dtest=MemorySearchToolTest" \
  "-Dsurefire.failIfNoSpecifiedTests=false"

# Windows
.\mvnw.cmd test -pl arcclaw-app `
  "-Dtest=MemorySearchToolTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

### 17.3 Step 3 — Live End-to-End RAG Verification

After accumulating at least one compaction summary (see [Section 16](#16-context-compaction-test)):

1. Start a new Telegram session or REST session.
2. Ask: *"What do you remember about our past conversations?"*
3. The `coordinator` agent will invoke `MemorySearchTool`, query the FTS5 index for your `userId`, and return a ranked summary of prior context.
4. Verify in the logs: `MemorySearchTool: Found N results for userId=<id>`.

### 17.4 FTS5 Backfill (Database Migration from pre-Phase 45)

If upgrading an existing deployment where `compaction_summary` rows have null `user_id`:

```bash
sqlite3 data/arcclaw.db
```

```sql
ATTACH DATABASE './data/arcclaw-fts.db' AS fts;
-- Run the backfill script
.read backfill_fts5.sql
.quit
```

---

## 18. Telegram Bot Integration & Verification

### 18.1 Create a Bot via BotFather

1. Open Telegram and search for `@BotFather`.
2. Send `/newbot`.
3. Provide a display name (e.g. `ArcClaw Dev Bot`) and a username ending in `bot` (e.g. `arcclaw_dev_bot`).
4. Copy the HTTP API token (format: `123456789:ABCdefGHIjklmNOPQrsTUVwxyz`).

### 18.2 Configure `.env`

```env
TELEGRAM_BOT_TOKEN=123456789:ABCdefGHIjklmNOPQrsTUVwxyz

# Find your Telegram user ID via @userinfobot
ARCCLAW_TELEGRAM_ALLOWED_USER_IDS=123456789
ARCCLAW_TELEGRAM_ALLOWED_USERS=yourusername
```

> The allowlist is enforced by `TelegramAllowlistGuard`. Messages from IDs or usernames not on the list are silently dropped.

### 18.3 Start in Daemon Mode

```bash
# Dev mode
./mvnw quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=serve"

# OR packaged jar (attach mode)
java \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  "-Dsun.misc.unsafe.memory.access=allow" \
  "-Dquarkus.profile=%cli" \
  -jar arcclaw-app/target/quarkus-app/quarkus-run.jar attach telegram
```

Look for in the logs: `ArcClaw daemon mode starting on port 8080...`

### 18.4 Verification Tests

**Test 1 — Cold Start**
Send your bot any message. In the Quarkus terminal you should immediately see the Apache Camel route pick it up, hand it off to a Virtual Thread, and the AgentLoop start logging its thoughts. The bot should reply within seconds.

**Test 2 — Session Continuity**
Follow up with: *"Can you summarize what you just told me in exactly 3 bullet points?"*
If the bot references the previous response, it proves `UUID.nameUUIDFromBytes(chatId + ":" + userId)` session mapping works and the `IN_PROGRESS` checkpoint persisted correctly.

**Test 3 — Human-in-the-Loop (HITL)**
Request a task that requires `NETWORK_OUTBOUND` autonomy (e.g. *"Search GitHub for..."*). The agent should pause and send an approval request. Reply `/approve` to resume or `/deny` to terminate. Verify the SQLite checkpoint changes from `AWAITING_APPROVAL` to `COMPLETED` or `FAILED`.

**Test 4 — Intent Classification**
Send the bot: *"Write me a Python function to sort a list."*
Watch the logs for `IntentClassifierService` routing. It should classify the intent and route to the `coder` (Brahma) role, not the coordinator.

### 18.5 Token Revocation (Nuclear Option)

If ghost connections from previous sessions are polling your bot:

1. Open `@BotFather` in Telegram.
2. Select your bot and send `/revoke`.
3. BotFather issues a new token immediately.
4. Replace `TELEGRAM_BOT_TOKEN` in `.env` with the new value.
5. Restart the daemon.

---

## 19. API Key Generation Reference

The `X-ArcClaw-API-Key` header must match `ARCCLAW_API_KEY_INTERNAL` in your `.env`. Use one of the following methods to generate a cryptographically secure random key.

**Linux / macOS (hex, 64 chars = 32 bytes):**

```bash
openssl rand -hex 32
```

**Linux / macOS (Base64, ~44 chars = 32 bytes):**

```bash
openssl rand -base64 32
```

**Windows PowerShell (Base64):**

```powershell
[System.Convert]::ToBase64String(
  [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
)
```

Paste the output as the value of `ARCCLAW_API_KEY_INTERNAL` (and `ARCCLAW_API_KEY_CI` if needed) in your `.env` file. Include the `key-internal-` prefix convention if you want to make the key type obvious in logs.

---

## 20. Final Verification Checklist

Work through this checklist after completing setup and running the tests above.

### Environment
- [ ] `java -version` shows JDK 25
- [ ] `./mvnw --version` (or `.\mvnw.cmd`) shows Maven 3.9+
- [ ] `podman ps` returns an empty list with no errors
- [ ] `.env` file exists with all required keys populated and `ARCCLAW_AUDIT_HMAC_KEY` set to a stable 32-byte value

### Build
- [ ] `./mvnw clean package -pl arcclaw-app -am -DskipTests` completes without errors
- [ ] `arcclaw-app/target/quarkus-app/quarkus-run.jar` exists

### Runtime & Health
- [ ] Application starts and logs `ArcClaw daemon mode starting on port 8080`
- [ ] `curl http://localhost:8080/q/health` returns `{"status":"UP",...}`
- [ ] `curl -X POST http://localhost:8080/api/v1/sessions` returns a `sessionId`
- [ ] Boot logs confirm sandbox strategy: `INFO: Isolation strategy: PODMAN initialized.` (prod) or `NONE` (dev)

### Sandbox
- [ ] `podman images | grep arcclaw-tool-runner` shows the image
- [ ] Tier 1 test (prod mode) shows Podman container launch in logs for a tool call
- [ ] `TmpfsSecretTransportTest` passes: secrets are written and zeroed

### Testing
- [ ] `./mvnw clean verify -pl arcclaw-app -am` — all tests green
- [ ] `CompactionSummaryRepositoryTest` passes — FTS5 tenant isolation verified
- [ ] `MemorySearchToolTest` passes — RAG pipeline verified
- [ ] E2E REST flow (Section 12) completes: `COMPLETED` status returned
- [ ] Jaeger trace visible at http://localhost:16686 after an agent run

### Observability (if using compose stack)
- [ ] Jaeger UI accessible at http://localhost:16686
- [ ] Prometheus UI accessible at http://localhost:9090
- [ ] Metrics visible at http://localhost:8080/q/metrics

### Telegram (if configured)
- [ ] Cold start test responds within seconds
- [ ] Session continuity test references prior turn
- [ ] HITL test pauses on `NETWORK_OUTBOUND` and resumes on `/approve`

---

## 21. Troubleshooting

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

`Dockerfile.native` expects the pre-built native binary on the host. Run the native build step (Section 9) before `docker compose up --build`.

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

If FTS5 is not available, install a newer SQLite version (`sudo apt install sqlite3` on Ubuntu should provide a recent build with FTS5 compiled in).

### FTS5 Backfill (Upgrading from pre-Phase 45)

```bash
sqlite3 data/arcclaw.db
```

```sql
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
| [`TELEGRAM_TESTING.md`](./TELEGRAM_TESTING.md) | Telegram bot testing guide |

---

*Last updated: ArcClaw V1.0.0 · Quarkus 3.23.0 · JDK 25*

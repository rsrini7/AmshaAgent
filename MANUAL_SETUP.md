# ArcClaw — Manual Setup Guide

> **Stack**: JDK 25 · Quarkus 3.27.2 · LangChain4J 1.x · SQLite (WAL + FTS5 + sqlite-vec) · Podman (rootless) · Groq Cloud
>
> This guide covers **manual setup without mise** on **macOS**, **WSL2 (Ubuntu)**, and **Windows**. All commands are direct — no wrapper scripts.
> If you prefer automated setup, see [`MISE_README.md`](./MISE_README.md).

---

> **Docs**: [README](./README.md) · [MISE_README](./MISE_README.md) · [Testing Guide](./TESTING_GUIDE.md) · [Telegram Testing](./TELEGRAM_TESTING.md) · [FAQ](./FAQ.md)

---

## Table of Contents

1. [Prerequisites Overview](#1-prerequisites-overview)
2. [macOS Setup](#2-macos-setup)
3. [WSL2 (Ubuntu) Setup](#3-wsl2-ubuntu-setup)
4. [Windows Setup](#4-windows-setup)
5. [Clone & Environment Configuration](#5-clone--environment-configuration)
6. [Podman / Container Setup](#6-podman--container-setup)
7. [Build the Application](#7-build-the-application)
8. [Dev Mode (Hot Reload)](#8-dev-mode-hot-reload)
9. [JAR Run Modes](#9-jar-run-modes)
10. [Native Binary Build & Run](#10-native-binary-build--run)
11. [Dist — Assemble Release Folder](#11-dist--assemble-release-folder)
12. [Docker Compose Stack](#12-docker-compose-stack)
13. [JDK Management](#13-jdk-management)
14. [Database Helpers](#14-database-helpers)
15. [Environment Variables Reference](#15-environment-variables-reference)
16. [Troubleshooting](#16-troubleshooting)

---

## 1. Prerequisites Overview

| Tool | Version | macOS | WSL2 | Windows | Notes |
|---|---|---|---|---|---|
| JDK 25 | 25+ | ✅ | ✅ | ✅ | Temurin recommended. |
| Maven | 3.9+ | ✅ | ✅ | ✅ | `./mvnw` wrapper included — no separate install. |
| Podman | Latest | ✅ | ✅ | Optional | Rootless. Required for Tier 1 sandbox in prod. |
| Docker CLI | Any | Optional | Optional | Optional | Can be wired to Podman socket. Used for `docker compose`. |
| GraalVM | jdk-25 | Optional | Optional | — | Only needed for local native build (no container). |
| `sqlite3` CLI | Any | Optional | Optional | Optional | Useful for DB inspection. |

---

## 2. macOS Setup

### 2.1 Install Homebrew

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

### 2.2 Install JDK 25 (Temurin)

```bash
brew install --cask temurin@25
```

Set `JAVA_HOME` in `~/.zshrc` or `~/.bash_profile`:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
export PATH=$JAVA_HOME/bin:$PATH
```

Reload and verify:

```bash
source ~/.zshrc
java -version   # should show 25.x
```

### 2.3 Install Podman

```bash
brew install podman

# Initialise VM (6 GB RAM, 4 CPUs recommended for builds)
podman machine init --memory 6144 --cpus 4 --disk-size 40
podman machine start

# Verify
podman ps
```

Wire Docker CLI to Podman (optional — needed for `docker compose`):

```bash
# Add to ~/.zshrc — exact path varies, confirm with:
# podman machine inspect | grep -i sock
export DOCKER_HOST="unix://${HOME}/.local/share/containers/podman/machine/qemu/podman.sock"
```

### 2.4 Mark mvnw Executable

```bash
chmod +x mvnw
```

---

## 3. WSL2 (Ubuntu) Setup

> All steps run inside your WSL2 Ubuntu terminal unless stated otherwise.

### 3.1 Enable Systemd (Required for Podman Socket)

```bash
sudo nano /etc/wsl.conf
```

Add:

```ini
[boot]
systemd=true
```

Restart WSL from Windows PowerShell:

```powershell
wsl --shutdown
```

Reopen your Ubuntu terminal.

### 3.2 Install JDK 25 via SDKMAN!

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 25-tem
java -version   # should show 25.x
```

### 3.3 Install Podman

```bash
sudo apt update
sudo apt install podman -y
```

### 3.4 Enable Podman User Socket

```bash
systemctl --user enable --now podman.socket

# Confirm socket exists (replace 1000 with your UID if different — check with: id -u)
ls -l /run/user/1000/podman/podman.sock
```

Wire Docker CLI to Podman (add to `~/.bashrc`):

```bash
echo 'export DOCKER_HOST="unix:///run/user/1000/podman/podman.sock"' >> ~/.bashrc
source ~/.bashrc
```

### 3.5 Fix Windows CRLF Line Endings (Critical)

If the repo was cloned on Windows before opening in WSL2:

```bash
sudo apt install dos2unix -y
dos2unix mvnw
chmod +x mvnw
```

### 3.6 (Optional) Install Docker CLI Only

```bash
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt update
sudo apt install docker-ce-cli -y   # CLI only — do NOT install the full daemon
```

---

## 4. Windows Setup

### 4.1 Install JDK 25 (Temurin)

Download and install from [adoptium.net](https://adoptium.net/temurin/releases/?version=25).

Set environment variables (PowerShell, permanent):

```powershell
[System.Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Eclipse Adoptium\jdk-25.0.0.0-hotspot", "User")
[System.Environment]::SetEnvironmentVariable("PATH", "$env:JAVA_HOME\bin;" + [System.Environment]::GetEnvironmentVariable("PATH","User"), "User")
```

Restart PowerShell and verify:

```powershell
java -version   # should show 25.x
```

### 4.2 Install Podman Desktop (Optional)

Download from [podman-desktop.io](https://podman-desktop.io/).

After install, enable Docker API compatibility in:
**Podman Desktop → Settings → General → Docker compatibility**

Then set `DOCKER_HOST` in PowerShell (add to `$PROFILE`):

```powershell
$env:DOCKER_HOST = "npipe:////./pipe/podman-machine-default"
```

### 4.3 Mark mvnw Executable

Not needed on Windows — use `mvnw.cmd` instead of `./mvnw`.

---

## 5. Clone & Environment Configuration

### 5.1 Clone the Repository

```bash
git clone https://github.com/rsrini7/ArcClaw.git
cd ArcClaw
```

### 5.2 Create .env

```bash
# macOS / WSL2 / Linux
cp .env.example .env

# Windows PowerShell
Copy-Item .env.example .env
```

### 5.3 Generate Required Secret Keys

These three keys **must be stable after first boot** — never change them once the app has started.

```bash
# macOS / Linux / WSL2
openssl rand -base64 32   # run 3 times: VAULT_PEPPER, AUDIT_HMAC_KEY, API_KEY_INTERNAL
```

```powershell
# Windows PowerShell
[Convert]::ToBase64String((1..32 | ForEach-Object { [byte](Get-Random -Max 256) }))
# Run 3 times
```

Set in `.env`:

```env
ARCCLAW_VAULT_PEPPER=<generated>        # immutable — do not change after first boot
ARCCLAW_AUDIT_HMAC_KEY=<generated>      # immutable — do not change after first boot
ARCCLAW_API_KEYS_INTERNAL=<generated>
ARCCLAW_API_KEYS_CI=<generated>          # optional, for CI smoke tests
```

### 5.4 Set Required API Keys

Edit `.env`:

```env
# LLM — required
ARCCLAW_LLM_API_KEY=gsk_your_key_here          # get from console.groq.com

# Embedding — required (Jina free tier: 1M tokens/month)
ARCCLAW_EMBED_API_KEY=jina_your_key     # get from jina.ai
ARCCLAW_EMBED_PROVIDER=jina
ARCCLAW_EMBED_DIMENSIONS=768

# GitHub — optional, needed for researcher role GitHub tool
GITHUB_TOKEN=ghp_your_token

# Auto-seed vault from .env on boot
ARCCLAW_AUTO_SEED_SECRETS=true
```

### 5.5 Telegram (Required Only for attach Mode)

```env
ARCCLAW_TELEGRAM_ENABLED=true
TELEGRAM_BOT_TOKEN=123456789:ABCdefGHIjklmNOPQrsTUVwxyz
ARCCLAW_TELEGRAM_ALLOWED_USER_IDS=123456789          # numeric IDs from @userinfobot
ARCCLAW_TELEGRAM_ALLOWED_USERS=yourusername          # without @
```

If not using Telegram, comment these out or set `ARCCLAW_TELEGRAM_ENABLED=false` — leaving placeholder values causes Quarkus to fail on startup (it tries to parse `<telegram-user-id>` as a long).

### 5.6 Validate .env

Quick sanity check — no placeholders remaining:

```bash
# macOS / WSL2
grep -E 'changeme|<[^>]+>' .env && echo "PLACEHOLDERS FOUND" || echo "OK"
```

```powershell
# Windows
Select-String -Path .env -Pattern 'changeme|<[^>]+>' | ForEach-Object { $_.Line }
```

### 5.7 Understand the Data Directory

`ARCCLAW_DATA_DIR=./data` (default). ArcClaw creates at runtime:

```
data/
├── arcclaw-meta.db     # Main SQLite WAL database (memory, audit, vault, skills, sessions)
├── arcclaw-fts.db      # FTS5 full-text search index
└── workspaces/         # Per-session ephemeral sandbox directories
```

---

## 6. Podman / Container Setup

### 6.1 Build the Sandbox Tool Runner Image

Required before running in prod mode. Without it, `PodmanImageHealthCheck` fails at startup.

```bash
# Via Docker Compose (recommended)
docker compose build tool-runner

# Or directly via Podman
podman build -t arcclaw-tool-runner:latest -f sandbox/Dockerfile.toolrunner sandbox/

# Verify
podman images | grep arcclaw-tool-runner
```

### 6.2 Sandbox Tier Behaviour

| Tier | Condition | Isolation |
|---|---|---|
| **1 — Podman Rootless** | prod + Podman socket up + image present | Full container isolation, no network, memory/CPU capped |
| **2 — Linux Namespaces** | Podman circuit breaker opened (3 consecutive failures) | `unshare` pid + net + mount |
| **3 — Direct Execution** | dev mode, or Windows OS detected | None — host PATH ProcessBuilder |

> In `quarkus:dev`, strategy is always `NONE`. To test full isolation, run the packaged jar with `QUARKUS_PROFILE=prod`.

### 6.3 sqlite-vec Extension (RAG Vector Search)

ArcClaw **auto-installs** the correct `vec0` native extension for your platform (Linux, macOS, Windows) from internal resources on first boot. The binary is extracted to `./lib/vec0` (configurable via `ARCCLAW_RAG_VEC_PATH`).

You can manually override this by placing your own build in `lib/`:

| Platform | Runtime Path | Source Archive (v0.1.6) |
|---|---|---|
| Linux / WSL2 | `lib/vec0.so` | `sqlite-vec-0.1.6-loadable-linux-x86_64.tar.gz` |
| macOS (M1/M2) | `lib/vec0.dylib` | `sqlite-vec-0.1.6-loadable-macos-aarch64.tar.gz` |
| Windows | `lib/vec0.dll` | `sqlite-vec-0.1.6-loadable-windows-x86_64.tar.gz` |

SQLite appends the platform suffix automatically — always pass the path **without** extension:
`ARCCLAW_RAG_VEC_PATH=./lib/vec0`

If the extension is missing from resources and the filesystem, ArcClaw degrades gracefully to FTS5-only search.

---

## 7. Build the Application

### MAVEN_OPTS (Required for JDK 25)

Set before running any Maven command:

```bash
# macOS / WSL2 / Linux
export MAVEN_OPTS="--add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  -Dsun.misc.unsafe.memory.access=allow \
  -Dquarkus.otel.sdk.disabled=true"
```

```powershell
# Windows PowerShell
$env:MAVEN_OPTS = "--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Dsun.misc.unsafe.memory.access=allow -Dquarkus.otel.sdk.disabled=true"
```

### 7.1 Standard JVM Build (Fast — Recommended for Development)

```bash
# macOS / WSL2 / Linux
./mvnw clean install -pl arcclaw-app -am -DskipTests

# Windows PowerShell
.\mvnw.cmd clean install -pl arcclaw-app -am -DskipTests
```

Output: `arcclaw-app/target/quarkus-app/quarkus-run.jar`

### 7.2 Full Build — All Modules

```bash
# macOS / WSL2 / Linux
./mvnw clean install -DskipTests

# Windows PowerShell
.\mvnw.cmd clean install -DskipTests
```

### 7.3 Build Including Tests

```bash
# macOS / WSL2 / Linux
./mvnw clean verify

# Windows PowerShell
.\mvnw.cmd clean verify
```

### 7.4 Clean Build Artifacts

```bash
# macOS / WSL2 / Linux
./mvnw clean

# Windows PowerShell
.\mvnw.cmd clean
```

---

## 8. Dev Mode (Hot Reload)

Dev mode uses the `%dev` profile: no container isolation, live code reload, Quarkus Dev UI active.

### JVM Args for Dev Mode

```
--add-opens=java.base/java.nio=ALL-UNNAMED
--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED
--add-opens=java.base/java.lang=ALL-UNNAMED
--enable-native-access=ALL-UNNAMED
-Dsun.misc.unsafe.memory.access=allow
-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8
```

### 8.1 Dev: serve (HTTP + MCP on :8080)

```bash
# macOS / WSL2 / Linux
./mvnw quarkus:dev -pl arcclaw-app -am \
  "-Djvm.args=--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Dsun.misc.unsafe.memory.access=allow -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8" \
  "-Dquarkus.args=serve" \
  "-Dquarkus.profile=dev" \
  "-Darcclaw.rag.vec-extension-path=../lib/vec0" \
  "-Darcclaw.data-dir=../data"
```

```powershell
# Windows PowerShell
$jvmArgs = "--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Dsun.misc.unsafe.memory.access=allow -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
.\mvnw.cmd quarkus:dev -pl arcclaw-app -am `
  "-Djvm.args=$jvmArgs" `
  "-Dquarkus.args=serve" `
  "-Dquarkus.profile=dev" `
  "-Darcclaw.rag.vec-extension-path=../lib/vec0" `
  "-Darcclaw.data-dir=../data"
```

### 8.2 Dev: chat (Interactive REPL)

Same as above, replace `-Dquarkus.args=serve` with `-Dquarkus.args=chat`.

### 8.3 Dev: attach (Telegram Daemon)

Same as above, replace `-Dquarkus.args=serve` with `-Dquarkus.args=attach`.

Requires `TELEGRAM_BOT_TOKEN` set in `.env`.

### 8.4 Dev: ask (One-Shot Task)

```bash
# macOS / WSL2 / Linux
./mvnw quarkus:dev -pl arcclaw-app -am \
  "-Djvm.args=..." \
  "-Dquarkus.args=ask your goal here --role coordinator" \
  "-Dquarkus.profile=dev" \
  "-Darcclaw.rag.vec-extension-path=../lib/vec0" \
  "-Darcclaw.data-dir=../data"
```

```powershell
# Windows PowerShell
.\mvnw.cmd quarkus:dev -pl arcclaw-app -am `
  "-Djvm.args=$jvmArgs" `
  "-Dquarkus.args=ask your goal here --role coordinator" `
  "-Dquarkus.profile=dev" `
  "-Darcclaw.rag.vec-extension-path=../lib/vec0" `
  "-Darcclaw.data-dir=../data"
```

### 8.5 Dev: No UI (Suppress Dev Console)

Add these flags to suppress the Quarkus Dev UI if it causes NPE errors:

```
-Dquarkus.console.enabled=false
-Dquarkus.devui.enabled=false
```

### 8.6 Kill Lingering Quarkus Processes

```bash
# macOS / WSL2 / Linux
jps -l | grep -E 'quarkus-run|QuarkusDevModeMain' | awk '{print $1}' | xargs -r kill -9
```

```powershell
# Windows PowerShell
jps -l | Where-Object { $_ -match 'quarkus-run|QuarkusDevModeMain' } | ForEach-Object {
    $pid = ($_ -split '\s+')[0]
    Stop-Process -Id $pid -Force
    Write-Host "Killed PID $pid"
}
```

---

## 9. JAR Run Modes

Run the packaged `quarkus-run.jar` directly. Build first with [Section 7.1](#71-standard-jvm-build-fast--recommended-for-development).

### JVM Args for JAR Mode

These apply to all `java -jar` commands below:

```
--add-opens=java.base/java.nio=ALL-UNNAMED
--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED
--add-opens=java.base/java.lang=ALL-UNNAMED
--enable-native-access=ALL-UNNAMED
-Dsun.misc.unsafe.memory.access=allow
-Dfile.encoding=UTF-8
-Dstdout.encoding=UTF-8
-Dsun.stdout.encoding=UTF-8
-Dsun.stderr.encoding=UTF-8
-Dquarkus.profile=cli
-Darcclaw.rag.vec-extension-path=./lib/vec0
-Darcclaw.data-dir=./data
```

### 9.1 serve — HTTP + MCP Daemon on :8080

```bash
# macOS / WSL2 / Linux
java \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  -Dsun.misc.unsafe.memory.access=allow \
  -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
  -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 \
  -Dquarkus.profile=cli \
  -Dquarkus.mcp.server.stdio.enabled=true \
  -Darcclaw.telegram.enabled=true \
  -Darcclaw.rag.vec-extension-path=./lib/vec0 \
  -Darcclaw.data-dir=./data \
  -jar arcclaw-app/target/quarkus-app/quarkus-run.jar serve
```

```powershell
# Windows PowerShell
$jar = 'arcclaw-app/target/quarkus-app/quarkus-run.jar'
$jvmArgs = @(
    '--add-opens=java.base/java.nio=ALL-UNNAMED',
    '--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED',
    '--add-opens=java.base/java.lang=ALL-UNNAMED',
    '--enable-native-access=ALL-UNNAMED',
    '-Dsun.misc.unsafe.memory.access=allow',
    '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
    '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8',
    '-Dquarkus.profile=cli',
    '-Dquarkus.mcp.server.stdio.enabled=true',
    '-Darcclaw.telegram.enabled=true',
    '-Darcclaw.rag.vec-extension-path=./lib/vec0',
    '-Darcclaw.data-dir=./data'
)
& java @jvmArgs -jar $jar serve
```

### 9.2 chat — Interactive REPL

Same as serve but replace `serve` with `chat` at the end. Remove `-Dquarkus.mcp.server.stdio.enabled=true` and `-Darcclaw.telegram.enabled=true`.

### 9.3 attach — Telegram Daemon

Same as serve but replace `serve` with `attach`. Requires `TELEGRAM_BOT_TOKEN` in `.env`.

### 9.4 ask — One-Shot Task

```bash
# macOS / WSL2 / Linux
java \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  -Dsun.misc.unsafe.memory.access=allow \
  -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
  -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 \
  -Dquarkus.profile=cli \
  -Darcclaw.rag.vec-extension-path=./lib/vec0 \
  -Darcclaw.data-dir=./data \
  -jar arcclaw-app/target/quarkus-app/quarkus-run.jar \
  ask "Analyse the ArcClaw repository" --role coordinator
```

```powershell
# Windows PowerShell
& java @jvmArgs -jar $jar ask "Analyse the ArcClaw repository" --role coordinator
```

### 9.5 JAR with Dev Profile

Useful for running the packaged jar with `%dev` settings (no Podman sandbox required):

```bash
# macOS / WSL2 / Linux — replace -Dquarkus.profile=cli with:
-Dquarkus.profile=dev
```

---

## 10. Native Binary Build & Run

> Native builds require significant RAM (8 GB free minimum) and take 5–15 minutes.

### 10.1 Build — Container (Recommended on Linux/macOS/WSL2)

Uses the Mandrel builder image inside Podman. Produces a portable Linux binary.

```bash
# Shield .env — secrets must not be baked into the binary
mv .env .env.build-backup

# macOS / WSL2 / Linux
export MAVEN_OPTS="--add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  -Dsun.misc.unsafe.memory.access=allow"

./mvnw clean package -Pnative -pl arcclaw-app -am -DskipTests \
  -Dquarkus.native.container-build=true

# Restore .env
mv .env.build-backup .env
```

Output: `arcclaw-app/target/arcclaw-app-1.0.0-SNAPSHOT-runner`

### 10.2 Build — Local GraalVM (No Container)

Switch to GraalVM first (see [Section 13](#13-jdk-management)), then:

```bash
# macOS / WSL2 / Linux
./mvnw clean package -Pnative -pl arcclaw-app -am -DskipTests \
  -Dquarkus.native.container-build=false

# Windows PowerShell
.\mvnw.cmd clean package -Pnative -pl arcclaw-app -am -DskipTests `
  "-Dquarkus.native.container-build=false"
```

Switch back to Temurin after (see [Section 13](#13-jdk-management)).

### 10.3 Build — Low Memory (6 GB Heap Cap)

```bash
./mvnw clean package -Pnative -pl arcclaw-app -am -DskipTests \
  -Dquarkus.native.container-build=false \
  "-Dquarkus.native.additional-build-args=-J-Xmx6g"
```

### 10.4 Run Native Binary

#### Linux / WSL2

`LD_PRELOAD` is required — it ensures `libm` is loaded before `vec0.so` opens (resolves the `sqrtf` symbol in GraalVM native images):

```bash
export LD_PRELOAD=/lib/x86_64-linux-gnu/libm.so.6
export ARCCLAW_RAG_VEC_PATH=./lib/vec0
export ARCCLAW_DATA_DIR=./data

./arcclaw-app/target/arcclaw-app-1.0.0-SNAPSHOT-runner serve
# or: chat | attach | ask "your goal"
```

#### macOS

```bash
export ARCCLAW_RAG_VEC_PATH=./lib/vec0
export ARCCLAW_DATA_DIR=./data

./arcclaw-app/target/arcclaw-app-1.0.0-SNAPSHOT-runner serve
```

#### Windows PowerShell

```powershell
$env:ARCCLAW_RAG_VEC_PATH = './lib/vec0'
$env:ARCCLAW_DATA_DIR = './data'

.\arcclaw-app\target\arcclaw-app-1.0.0-SNAPSHOT-runner.exe serve
```

#### Role Flag (All Platforms)

```bash
./arcclaw-app/target/arcclaw-app-1.0.0-SNAPSHOT-runner ask "your goal" --role researcher
```

---

## 11. Dist — Assemble Release Folder

Manually assemble a `dist/` folder containing the binary, launchers, and vec0 extension:

```bash
# macOS / WSL2 / Linux
mkdir -p dist/lib

# Copy binary
cp arcclaw-app/target/arcclaw-app-1.0.0-SNAPSHOT-runner dist/

# Copy .env.example
cp .env.example dist/.env.example

# Copy vec0 extension(s) — copy all that exist
[ -f lib/vec0.so    ] && cp lib/vec0.so    dist/lib/
[ -f lib/vec0.dylib ] && cp lib/vec0.dylib dist/lib/
[ -f lib/vec0.dll   ] && cp lib/vec0.dll   dist/lib/
```

```powershell
# Windows PowerShell
New-Item -ItemType Directory -Force dist/lib | Out-Null
Copy-Item arcclaw-app/target/arcclaw-app-1.0.0-SNAPSHOT-runner.exe dist/
Copy-Item .env.example dist/.env.example
if (Test-Path lib/vec0.dll)   { Copy-Item lib/vec0.dll   dist/lib/ }
if (Test-Path lib/vec0.so)    { Copy-Item lib/vec0.so    dist/lib/ }
if (Test-Path lib/vec0.dylib) { Copy-Item lib/vec0.dylib dist/lib/ }
```

### 11.1 Create Release Archives (dist:pack equivalent)

```bash
# Linux archive
mkdir -p dist/release
cd dist
tar -czf release/arcclaw-1.0.0-linux-x64.tar.gz \
  arcclaw-app-1.0.0-SNAPSHOT-runner .env.example lib/
cd ..
```

```powershell
# Windows archive
Compress-Archive -Path dist/* -DestinationPath dist/release/arcclaw-1.0.0-windows-x64.zip -Force
```

---

## 12. Docker Compose Stack

The full observability stack: ArcClaw native binary + Ollama + OTel Collector + Jaeger + Prometheus.

> Requires the native binary to be built first ([Section 10](#10-native-binary-build--run)).

### 12.1 Start the Stack

```bash
docker compose up -d
```

| Service | Port | Notes |
|---|---|---|
| `arcclaw` | `8080` | Native binary, `QUARKUS_PROFILE=prod` |
| `ollama` | `11434` | Local LLM (disabled by default in `application.yaml`) |
| `otel-collector` | `4317`, `4318` | OTLP receiver → Jaeger |
| `jaeger` | `16686` | Distributed trace UI |
| `prometheus` | `9090` | Metrics scraper |

### 12.2 Stop the Stack

```bash
docker compose down

# Also remove volumes (deletes all data — irreversible):
docker compose down -v
```

### 12.3 Follow Logs

```bash
docker compose logs -f arcclaw
```

### 12.4 Restart arcclaw Only

```bash
docker compose restart arcclaw
```

---

## 13. JDK Management

ArcClaw uses two JDKs: **Temurin 25** for all dev/build/jar tasks, and **GraalVM Community 25.0.2** only for native builds.

### 13.1 Switch to GraalVM (for native builds)

**macOS — with jEnv:**

```bash
jenv add /Library/Java/JavaVirtualMachines/graalvm-community-25/Contents/Home
jenv local graalvm-community-25
java -version
```

**WSL2 / Linux — with SDKMAN!:**

```bash
sdk install java 25-graalce
sdk use java 25-graalce
java -version
```

**Windows PowerShell:**

Download GraalVM Community from [github.com/graalvm/graalvm-ce-builds](https://github.com/graalvm/graalvm-ce-builds/releases) and set:

```powershell
$env:JAVA_HOME = "C:\path\to\graalvm-community-25"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
java -version
```

### 13.2 Switch Back to Temurin 25

```bash
# macOS with jEnv
jenv local temurin-25
java -version

# WSL2 / Linux with SDKMAN!
sdk use java 25-tem
java -version
```

```powershell
# Windows PowerShell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.0.0-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
java -version
```

### 13.3 Check Active JDK

```bash
java -version
```

---

## 14. Database Helpers

### 14.1 Open the Main Database

```bash
# macOS / WSL2 / Linux
sqlite3 data/arcclaw-meta.db

# Windows PowerShell
sqlite3 data/arcclaw-meta.db
```

### 14.2 Verify FTS5 Available

```bash
sqlite3 :memory: "CREATE VIRTUAL TABLE t USING fts5(x);"
# No output = FTS5 is available
# Error = install a newer sqlite3: sudo apt install sqlite3
```

### 14.3 Verify sqlite-vec Extension

```bash
# macOS / WSL2 / Linux
[ -f lib/vec0.so ]    && echo "Linux  OK: lib/vec0.so"    || echo "Missing: lib/vec0.so"
[ -f lib/vec0.dylib ] && echo "macOS  OK: lib/vec0.dylib" || echo "Missing: lib/vec0.dylib"
```

```powershell
# Windows PowerShell
if (Test-Path lib/vec0.dll) { Write-Host "OK: lib/vec0.dll" } else { Write-Host "Missing: lib/vec0.dll" }
```

---

## 15. Environment Variables Reference

| Variable | Required | Default | Notes |
|---|---|---|---|
| `ARCCLAW_LLM_API_KEY` | ✅ | — | Starts with `gsk_`. Get from [console.groq.com](https://console.groq.com). |
| `ARCCLAW_VAULT_PEPPER` | ✅ | — | Min 32 bytes. **Immutable after first boot.** |
| `ARCCLAW_AUDIT_HMAC_KEY` | ✅ | — | Min 32 bytes. **Immutable after first boot.** |
| `ARCCLAW_API_KEYS_INTERNAL` | ✅ | — | Bearer token for REST API (`Authorization: Bearer`). |
| `ARCCLAW_API_KEYS_CI` | Optional | — | CI/CD read-only API key. |
| `ARCCLAW_EMBED_API_KEY` | ✅ | — | Jina AI key (default provider). Starts with `jina_`. |
| `ARCCLAW_EMBED_PROVIDER` | Optional | `jina` | `jina` \| `openai` \| `ollama` |
| `ARCCLAW_EMBED_DIMENSIONS` | Optional | `768` | Must match model. Jina nano = 768. |
| `ARCCLAW_AUTO_SEED_SECRETS` | Optional | `false` | Set `true` to seed vault from env on boot. |
| `GITHUB_TOKEN` | Optional | — | Required for GitHub tool (researcher role). |
| `ARCCLAW_DATA_DIR` | Optional | `./data` | Path to SQLite databases and workspaces. |
| `ARCCLAW_CORS_ORIGINS` | Optional | `http://localhost:3000` | Comma-separated CORS origins. |
| `ARCCLAW_TELEGRAM_ENABLED` | Optional | `true` | Set `false` if not using Telegram. |
| `TELEGRAM_BOT_TOKEN` | attach only | — | From @BotFather. |
| `ARCCLAW_TELEGRAM_ALLOWED_USER_IDS` | attach only | — | Comma-separated numeric IDs. |
| `ARCCLAW_TELEGRAM_ALLOWED_USERS` | attach only | — | Comma-separated usernames (no @). |
| `QUARKUS_PROFILE` | Optional | `prod` | `dev` \| `test` \| `prod` \| `cli` |
| `ARCCLAW_RAG_VEC_PATH` | Optional | `./lib/vec0` | Path to vec0 extension (no suffix). |
| `QUARKUS_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | Optional | `http://localhost:4317` | OTLP trace endpoint. |
| `ARCCLAW_DEBUG_LLM` | Optional | `false` | Log full LLM request/response. Do NOT enable in prod. |
| `ARCCLAW_SANDBOX_ENABLED` | Optional | `true` | Enable Podman sandbox. |
| `ARCCLAW_SANDBOX_STRATEGY` | Optional | `PODMAN` | `PODMAN` \| `NONE` |

> **Security rules**: Never commit `.env`. `VAULT_PEPPER` and `AUDIT_HMAC_KEY` must never change after first boot — doing so corrupts the vault and audit chain.

---

## 16. Troubleshooting

### `./mvnw: cannot execute: required file not found` (WSL2)

Windows CRLF in the Maven wrapper:

```bash
sudo apt install dos2unix -y && dos2unix mvnw && chmod +x mvnw
```

### `FATAL: arcclaw-audit-hmac-key is not present in SecretVault`

`ARCCLAW_AUTO_SEED_SECRETS=true` must be set in `.env` so `DevSecretSeeder` reads `ARCCLAW_AUDIT_HMAC_KEY` from config and seeds the vault before `StartupSecurityValidator` checks it. Verify:

```bash
grep -E 'AUDIT_HMAC|AUTO_SEED' .env
```

Both must be present and non-empty.

### `Expected a long value, got "<telegram-user-id>"` (Quarkus startup)

Placeholder value left in `.env`. Comment out all Telegram keys or set real values:

```bash
# macOS / WSL2
sed -i.bak 's|^TELEGRAM_BOT_TOKEN=.*|# TELEGRAM_BOT_TOKEN=|' .env
sed -i.bak 's|^ARCCLAW_TELEGRAM_ALLOWED_USER_IDS=.*|# ARCCLAW_TELEGRAM_ALLOWED_USER_IDS=|' .env
sed -i.bak 's|^ARCCLAW_TELEGRAM_ALLOWED_USERS=.*|# ARCCLAW_TELEGRAM_ALLOWED_USERS=|' .env
```

```powershell
# Windows PowerShell
$c = [System.IO.File]::ReadAllText('.env')
$c = $c -replace '(?m)^TELEGRAM_BOT_TOKEN=.*',                '# TELEGRAM_BOT_TOKEN='
$c = $c -replace '(?m)^ARCCLAW_TELEGRAM_ALLOWED_USER_IDS=.*', '# ARCCLAW_TELEGRAM_ALLOWED_USER_IDS='
$c = $c -replace '(?m)^ARCCLAW_TELEGRAM_ALLOWED_USERS=.*',    '# ARCCLAW_TELEGRAM_ALLOWED_USERS='
[System.IO.File]::WriteAllText('.env', $c)
```

### `.env` Source Fails with `syntax error near unexpected token 'newline'`

Never `source .env` directly in bash — placeholder values like `key-<random>` are treated as redirects. Use a safe loader:

```bash
while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    [[ -z "${line// }" ]] && continue
    if [[ "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*) ]]; then
        export "${BASH_REMATCH[1]}=${BASH_REMATCH[2]}"
    fi
done < .env
```

### `NullPointerException` in Quarkus Dev Console

Disable the Dev UI and console:

```bash
./mvnw quarkus:dev -pl arcclaw-app -am \
  "-Dquarkus.args=serve" \
  "-Dquarkus.console.enabled=false" \
  "-Dquarkus.devui.enabled=false" \
  "-Darcclaw.rag.vec-extension-path=../lib/vec0"
```

### Podman Socket Not Found / `docker ps` Fails (WSL2)

```bash
systemctl --user status podman.socket
# If inactive:
systemctl --user enable --now podman.socket

# Confirm DOCKER_HOST
echo $DOCKER_HOST
# Expected: unix:///run/user/1000/podman/podman.sock

# If your UID is not 1000:
export DOCKER_HOST="unix:///run/user/$(id -u)/podman/podman.sock"
```

### `open //./pipe/dockerDesktopLinuxEngine` (Windows)

```powershell
$env:DOCKER_HOST = "npipe:////./pipe/podman-machine-default"
```

Or enable Docker API compatibility in Podman Desktop → Settings → General.

### Sandbox Strategy is `NONE` When You Expect `PODMAN`

1. `quarkus:dev` always uses `NONE`. Package the jar and run with `QUARKUS_PROFILE=prod`.
2. Was Podman running when ArcClaw started? The circuit breaker evaluates at boot — restart ArcClaw after starting Podman.
3. On Windows, the OS check bypasses Linux namespaces — run inside WSL2.

### `InaccessibleObjectException` / Unsafe Memory Warnings

Expected with Netty on JDK 22+. Ensure all `--add-opens` and `-Dsun.misc.unsafe.memory.access=allow` flags from [Section 9](#9-jar-run-modes) are present in your java command.

### OOM During Native Build

```bash
# Reduce heap cap (default is 8 GB)
./mvnw clean package -Pnative -pl arcclaw-app -am -DskipTests \
  "-Dquarkus.native.additional-build-args=-J-Xmx6g"

# Or increase Podman machine memory before building
podman machine stop
podman machine set --memory 8192
podman machine start
```

### `undefined symbol: sqrtf` (Linux native binary)

`LD_PRELOAD` is missing:

```bash
export LD_PRELOAD=/lib/x86_64-linux-gnu/libm.so.6
./arcclaw-app/target/arcclaw-app-1.0.0-SNAPSHOT-runner serve
```

### vector search disabled at runtime

1. Verify the correct extension exists: `lib/vec0.so` (Linux), `lib/vec0.dylib` (macOS), `lib/vec0.dll` (Windows).
2. Pass path **without suffix**: `-Darcclaw.rag.vec-extension-path=./lib/vec0` (not `./lib/vec0.so`).
3. On Linux native: ensure `LD_PRELOAD` is set (see above).

ArcClaw degrades to FTS5-only search — no crash, just a `WARN` in logs.

### FTS5 Not Available

```bash
sqlite3 :memory: "CREATE VIRTUAL TABLE t USING fts5(x);"
# Error? Install newer sqlite3:
sudo apt install sqlite3
```

### Ollama Port Collision (11434 already in use)

Remap in `docker-compose.yaml`:

```yaml
ollama:
  ports:
    - "11435:11434"
```

### `lstat /arcclaw-app/target: no such file or directory` (Docker Compose)

Build the native binary ([Section 10](#10-native-binary-build--run)) before running `docker compose up --build`.

### `Permission denied` on SQLite Data Volume (Docker)

The `init-perms` service in `docker-compose.yaml` handles this automatically by running `chown -R 1000:1000 /app/data` before the main container starts.

---

## Related Documentation

| Document | Purpose |
|---|---|
| [`README.md`](./README.md) | Architecture overview, agent personas, security model |
| [`MISE_README.md`](./MISE_README.md) | Automated setup via mise task runner (recommended) |
| [`TESTING_GUIDE.md`](./TESTING_GUIDE.md) | End-to-end testing (19 sections) |
| [`TELEGRAM_TESTING.md`](./TELEGRAM_TESTING.md) | Telegram bot end-to-end testing |
| [`FAQ.md`](./FAQ.md) | Architecture Q&A, security rationale, operational guidance |
| [`rest-http/`](./rest-http/) | IntelliJ / VSCode REST Client files |

---

*Last updated: ArcClaw V1.0.0 · Quarkus 3.27.2 · JDK 25*
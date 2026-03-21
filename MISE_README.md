# ArcClaw — mise Task Runner Guide

> **Stack**: JDK 25 · Quarkus 3.27.2 · LangChain4J 1.x · SQLite · Podman (rootless) · Groq Cloud
>
> **This guide** covers `mise` as the unified task runner for **Windows (pwsh)**, **macOS**, **WSL2 (Ubuntu)**, and **Linux**.
> Every task is pure PowerShell — no separate `.ps1` or bash scripts needed.
> The `scripts/windows/` and `mise-tasks/` folders can be deleted.

---

## Table of Contents

1. [Why mise?](#1-why-mise)
2. [Install mise](#2-install-mise)
3. [Trust mise.toml (First-Time Security Step)](#3-trust-misetoml-first-time-security-step)
4. [Repository Layout](#4-repository-layout)
5. [First-Time Bootstrap](#5-first-time-bootstrap)
6. [JDK Management — Temurin vs GraalVM](#6-jdk-management--temurin-vs-graalvm)
7. [All Tasks at a Glance](#7-all-tasks-at-a-glance)
8. [Build Tasks](#8-build-tasks)
9. [Dev Mode Tasks (hot reload)](#9-dev-mode-tasks-hot-reload)
10. [JAR Run Tasks](#10-jar-run-tasks)
11. [Native Binary Tasks](#11-native-binary-tasks)
12. [Docker Compose Tasks](#12-docker-compose-tasks)
13. [Podman Setup Tasks](#13-podman-setup-tasks)
14. [Database Helpers](#14-database-helpers)
15. [Environment Variables Reference](#15-environment-variables-reference)
16. [Troubleshooting](#16-troubleshooting)

---

## 1. Why mise?

Instead of remembering:
```powershell
.\mvnw.cmd quarkus:dev -pl arcclaw-app -am -Dquarkus.args=serve -Darcclaw.rag.vec-extension-path=../lib/vec0 ...
```
You just type:
```powershell
mise run dev
```

`mise` also:
- **Pins JDK 25 (Temurin) per-project** — consistent `java -version` everywhere, every platform.
- **Manages both JDKs** — Temurin 25 (dev/build) and GraalVM Community 25.0.2 (native) installed side-by-side; switch with one command.
- **Auto-loads `.env`** into every task and your shell when you `cd` into the project.
- **All tasks inline** — everything lives in `mise.toml`. No wrapper `.sh`, `.ps1`, or bash files.
- **Universal pwsh** — every task runs identically on Windows, macOS, WSL2, and Linux.

---

## 2. Install mise

### Windows — Scoop ✅ Recommended

**Scoop is the recommended Windows installer** — it automatically adds mise shims to `PATH` and avoids the `%PATH%` length limit that winget can trigger.

```powershell
# Install Scoop (if not already installed)
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
Invoke-RestMethod -Uri https://get.scoop.sh | Invoke-Expression

# Install mise
scoop install mise
```

If you see a vcredist error:
```powershell
scoop bucket add extras
scoop install vcredist2022
# Wait for "Running post_install script..." then restart Windows
```

Wire to your PowerShell profile:
```powershell
# PowerShell 7+ (Windows Terminal default — pwsh.exe)
Add-Content $HOME\Documents\PowerShell\Microsoft.PowerShell_profile.ps1 `
    '(&mise activate pwsh) | Out-String | Invoke-Expression'

# PowerShell 5.1 (legacy — powershell.exe)
Add-Content $HOME\Documents\WindowsPowerShell\Microsoft.PowerShell_profile.ps1 `
    '(&mise activate pwsh) | Out-String | Invoke-Expression'
```

Reload: `. $PROFILE`

### Windows — winget ⚠️ Caution

```powershell
winget install jdx.mise
```

> ⚠️ **PATH length limit**: Windows caps user `%PATH%` at ~2048 characters. If you have many
> tools installed, winget can silently push you over this limit. **Prefer Scoop**.

### Windows — Native build prerequisite (GraalVM)

GraalVM's `native-image` on Windows requires the MSVC build tools:

```powershell
winget install -e --id Microsoft.VisualStudio.2022.BuildTools `
    --override "--passive --wait --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended"
```

### macOS / Linux / WSL2

```bash
curl https://mise.run | sh

# Wire to shell
echo 'eval "$(~/.local/bin/mise activate zsh)"'  >> ~/.zshrc    # zsh
echo 'eval "$(~/.local/bin/mise activate bash)"' >> ~/.bashrc   # bash

source ~/.zshrc   # or ~/.bashrc
```

### Verify (all platforms)

```powershell
mise --version    # mise 2025.x.x
mise doctor       # diagnoses common setup issues
```

---

## 3. Trust mise.toml (First-Time Security Step)

After cloning, mise shows this on first use:

```
mise ERROR Config files in ~/Ws/ArcClaw/mise.toml are not trusted.
```

This is **expected** — supply-chain protection. Fix:

```powershell
cd ArcClaw
mise trust
# then reload your shell or open a new terminal
mise tasks   # should list all tasks
```

### Skip for your own repos (optional)

```toml
# ~/.config/mise/config.toml
[settings]
trust_all = true
```

Or: set `MISE_TRUST_ALL=1` in your shell profile.

> ⚠️ `trust_all` on personal machines only. Keep default on CI.

---

## 4. Repository Layout

```
ArcClaw/
├── mise.toml            ← ALL tasks defined here — tools, env, every mise run command
├── mvnw / mvnw.cmd
├── lib/
│   ├── vec0.dll         ← Windows  (download once — gitignored)
│   ├── vec0.so          ← Linux    (download once — gitignored)
│   └── vec0.dylib       ← macOS    (download once — gitignored)
├── dist/                ← produced by: mise run dist  (gitignored)
└── arcclaw-app/
    └── target/
        ├── quarkus-app/quarkus-run.jar          ← produced by: mise run build
        ├── arcclaw-app-1.0.0-SNAPSHOT-runner     ← Linux/macOS native
        └── arcclaw-app-1.0.0-SNAPSHOT-runner.exe ← Windows native
```

> `scripts/windows/` and `mise-tasks/` are **no longer needed** — delete them.

> **JDK default**: `temurin-25` is active for everything except `build:native` and
> `build:native-lowmem` which auto-switch to `graalvm-community-25.0.2` and back.

---

## 5. First-Time Bootstrap

```powershell
# 1. Clone and enter
git clone https://github.com/rsrini7/ArcClaw.git
cd ArcClaw

# 2. Trust mise.toml
mise trust

# 3. Install both JDKs + .env
mise run setup

# 4. Validate .env
mise run setup:env-check

# 5. WSL2 only — fix CRLF if cloned on Windows
mise run setup:wsl-crlf

# 6. Verify JDKs
mise run jdk:status

# 7. Build (uses Temurin 25)
mise run build

# 8. (macOS only) Init Podman VM
mise run podman:init
mise run sandbox:check

# 9. Run
mise run dev              # hot reload on :8080
```

### What `mise run setup` does

1. `.env.example` → `.env` (skips if exists)
2. `chmod +x mvnw` (Linux/macOS only — not needed on Windows)
3. `mise install java@temurin-25`
4. `mise install java@graalvm-community-25.0.2`

---

## 6. JDK Management — Temurin vs GraalVM

ArcClaw uses **two JDKs** managed entirely by mise — no manual downloads, no `JAVA_HOME` editing:

| JDK | Version | Used for |
|---|---|---|
| Temurin (default) | `temurin-25` | All dev, build, jar, native-run tasks |
| GraalVM Community | `graalvm-community-25.0.2` | `build:native`, `build:native-lowmem` only |

### Day-to-day commands

```powershell
# See which JDK is active + all installed JDKs
mise run jdk:status

# Switch to GraalVM (e.g. for a manual native build session)
mise run jdk:graalvm
mise run build:native
mise run jdk:temurin   # switch back

# Let build:native manage the switch automatically (recommended)
mise run build:native
# → auto-switches to GraalVM → builds → auto-switches back to Temurin
```

### Install both JDKs anytime (idempotent)

```powershell
mise run jdk:install
```

### How `build:native` handles the switch

```
mise run build:native
  │
  ├─ mise use java@graalvm-community-25.0.2   (switches mise.toml [tools.java])
  ├─ ./mvnw clean package -Pnative ...        (GraalVM active)
  └─ mise use java@temurin-25                 (restores — runs even on build failure)
```

---

## 7. All Tasks at a Glance

```powershell
mise run help    # or: mise tasks
```

| Task | What it does |
|---|---|
| **`mise run setup`** | `.env`, chmod +x mvnw (Linux), install temurin-25 + graalvm-community-25.0.2 |
| `mise run setup:wsl-crlf` | Fix CRLF in mvnw (WSL2 after Windows clone) |
| `mise run setup:env-check` | Validate `.env` for missing/placeholder values |
| **`mise run jdk:status`** | Show active JDK + all installed JDKs |
| `mise run jdk:graalvm` | Switch active JDK to GraalVM Community 25.0.2 |
| `mise run jdk:temurin` | Switch active JDK back to Temurin 25 |
| `mise run jdk:install` | Install both JDKs (idempotent) |
| **`mise run build`** | JVM build, skip tests — fast daily driver (Temurin) |
| `mise run build:full` | Clean install all modules |
| `mise run build:test` | Build and run all tests |
| `mise run build:native` | Native binary — **auto-switches** GraalVM → Temurin |
| `mise run build:native-lowmem` | Same with 6 GB heap cap |
| `mise run clean` | `mvnw clean` |
| **`mise run dev`** | Hot-reload serve on :8080 (strategy: NONE) |
| `mise run dev:chat` | Hot-reload — interactive REPL |
| `mise run dev:attach` | Hot-reload — Telegram daemon |
| `mise run dev:ask` | Hot-reload — one-shot task (set `$env:GOAL`) |
| `mise run dev:no-ui` | Hot-reload — Dev UI + console disabled |
| **`mise run serve`** | Packaged jar — REST daemon on :8080 |
| `mise run chat` | Packaged jar — interactive REPL |
| `mise run attach` | Packaged jar — Telegram daemon |
| `mise run ask` | Packaged jar — one-shot task (set `$env:GOAL`) |
| `mise run jar:serve-dev-profile` | Packaged jar with `%dev` profile (no Podman) |
| **`mise run native:serve`** | Native binary — REST daemon on :8080 |
| `mise run native:chat` | Native binary — interactive REPL |
| `mise run native:attach` | Native binary — Telegram daemon |
| `mise run native:ask` | Native binary — one-shot task (set `$env:GOAL`) |
| **`mise run dist`** | Assemble `dist/` folder: exe + vec0 + .env.example |
| `mise run compose:up` | Full observability stack up |
| `mise run compose:down` | Stop stack, keep volumes |
| `mise run compose:down-clean` | Stop stack + delete volumes (prompts YES) |
| `mise run compose:logs` | Follow arcclaw container logs |
| `mise run compose:restart` | Restart only arcclaw service |
| `mise run sandbox:check` | Verify tool-runner image exists |
| `mise run podman:init` | macOS: init Podman VM (6 GB RAM, 4 CPUs) |
| `mise run podman:start` | macOS: start Podman VM |
| `mise run podman:stop` | macOS: stop Podman VM |
| `mise run podman:socket` | WSL2/Linux: enable Podman user socket |
| `mise run podman:status` | Podman + image status |
| `mise run db:inspect` | Open `arcclaw-meta.db` in sqlite3 |
| `mise run db:fts-check` | Verify FTS5 available |
| `mise run db:vec-check` | Check `lib/vec0` extension for current platform |
| `mise run info` | Print Java, Podman, Docker versions |
| `mise run help` | List all tasks |

---

## 8. Build Tasks

```powershell
mise run build           # JVM build, skip tests → quarkus-run.jar
mise run build:full      # all modules (after branch switch or schema change)
mise run build:test      # with tests
mise run clean

# Native — auto-manages GraalVM switch
# Linux/macOS: ensure Podman is running first
mise run podman:status
mise run build:native         # full RAM
mise run build:native-lowmem  # 6 GB heap cap
```

Increase Podman VM RAM if native build OOMs (Linux/macOS):
```powershell
mise run podman:stop
podman machine set --memory 8192
mise run podman:start
mise run build:native
```

---

## 9. Dev Mode Tasks (hot reload)

All `dev` / `dev:*` tasks use Temurin 25 and pass JVM flags via `-Djvm.args`.
No Podman needed — sandbox strategy is `NONE` in dev mode.

```powershell
mise run dev           # serve on :8080 · hot reload · Dev UI at /q/dev-ui
mise run dev:chat      # interactive REPL
mise run dev:no-ui     # serve — Dev UI + console disabled (Quarkus NPE workaround)
mise run dev:attach    # Telegram daemon (TELEGRAM_BOT_TOKEN required in .env)

# One-shot dev ask — use $env:GOAL (all platforms)
$env:GOAL = "Analyze the ArcClaw architecture"; mise run dev:ask
$env:ROLE = "researcher"; $env:GOAL = "Review auth flow"; mise run dev:ask

# macOS/Linux shorthand
GOAL="Review auth flow" ROLE=debugger mise run dev:ask
```

Available roles: `coordinator` · `researcher` · `architect` · `synthesizer` · `debugger`

---

## 10. JAR Run Tasks

All jar tasks invoke `java` directly — JVM args fully inlined in `mise.toml`.

```powershell
mise run serve         # REST daemon on :8080 (CLI profile)
mise run chat          # interactive REPL
mise run attach        # Telegram daemon (TELEGRAM_BOT_TOKEN required)
mise run jar:serve-dev-profile   # %dev profile, no Podman

# One-shot ask — use $env:GOAL (all platforms)
$env:GOAL = "List all active cron jobs"; mise run ask
$env:ROLE = "researcher"; $env:GOAL = "Research Quarkus ScopedValues"; mise run ask

# macOS/Linux shorthand
GOAL="List all active cron jobs" mise run ask
ROLE=researcher GOAL="Research Quarkus ScopedValues" mise run ask
```

---

## 11. Native Binary Tasks

**Prerequisite:** `mise run build:native`

Native tasks work on **all platforms** — `.exe` on Windows, ELF on Linux/macOS.

```powershell
# Run directly
mise run native:serve    # REST daemon on :8080
mise run native:chat     # interactive REPL
mise run native:attach   # Telegram daemon

# One-shot ask
$env:GOAL = "summarize today's logs"; mise run native:ask
GOAL="summarize today's logs" mise run native:ask   # macOS/Linux
```

### Assemble a dist/ folder (ship-ready)

```powershell
mise run dist
```

This produces:
```
dist/
  arcclaw-app-1.0.0-SNAPSHOT-runner.exe   (or -runner on Linux/macOS)
  .env.example                             → copy to .env, fill in keys
  lib/
    vec0.dll / vec0.so / vec0.dylib        (copied from lib/ if present)
```

Then run from anywhere:
```powershell
cd dist
Copy-Item .env.example .env    # fill in your API keys
.\arcclaw-app-1.0.0-SNAPSHOT-runner.exe serve    # HTTP + MCP on :8080
.\arcclaw-app-1.0.0-SNAPSHOT-runner.exe chat     # interactive REPL
.\arcclaw-app-1.0.0-SNAPSHOT-runner.exe ask 'your prompt'
.\arcclaw-app-1.0.0-SNAPSHOT-runner.exe attach   # Telegram daemon
```

### sqlite-vec (vec0) — one-time download

Vector search requires the `vec0` extension placed in `lib/`. Download it once and `mise run dist` picks it up automatically:

```powershell
# Windows
Invoke-WebRequest `
    "https://github.com/asg017/sqlite-vec/releases/download/v0.1.6/sqlite-vec-v0.1.6-loadable-windows-x86_64.zip" `
    -OutFile vec0.zip
Expand-Archive vec0.zip -DestinationPath lib/
Remove-Item vec0.zip
# Rename if needed: Rename-Item lib/vec0-v0.1.6.dll lib/vec0.dll
```

```bash
# Linux
curl -L https://github.com/asg017/sqlite-vec/releases/download/v0.1.6/sqlite-vec-v0.1.6-loadable-linux-x86_64.zip \
    -o vec0.zip && unzip vec0.zip -d lib/ && rm vec0.zip

# macOS
curl -L https://github.com/asg017/sqlite-vec/releases/download/v0.1.6/sqlite-vec-v0.1.6-loadable-macos-aarch64.zip \
    -o vec0.zip && unzip vec0.zip -d lib/ && rm vec0.zip
```

Add to `.gitignore`:
```
lib/vec0.dll
lib/vec0.so
lib/vec0.dylib
dist/
```

> Without vec0, ArcClaw falls back to FTS5 full-text search — everything still works, vector similarity search is degraded.

> **Linux note:** `native:serve/chat/attach` set `LD_PRELOAD=/lib/x86_64-linux-gnu/libm.so.6` automatically — required for vec0.so symbol resolution.

---

## 12. Docker Compose Tasks

**Prerequisite:** `mise run build:native`

```powershell
mise run compose:up          # ArcClaw + Ollama + OTel + Jaeger + Prometheus
# ArcClaw:    http://localhost:8080
# Jaeger:     http://localhost:16686
# Prometheus: http://localhost:9090
# Ollama:     http://localhost:11434

mise run compose:logs        # follow logs
mise run compose:restart     # restart arcclaw only
mise run compose:down        # stop, keep volumes
mise run compose:down-clean  # stop + delete volumes (prompts YES)
```

---

## 13. Podman Setup Tasks

### macOS

```powershell
mise run podman:init    # one-time VM creation (6 GB RAM, 4 CPUs, 40 GB disk)
mise run podman:start
mise run podman:stop
mise run podman:status
```

### WSL2 / Ubuntu

```powershell
mise run podman:socket
# Prints DOCKER_HOST export line — add to ~/.bashrc or ~/.zshrc
```

### Windows

Use [Podman Desktop](https://podman-desktop.io/) or Docker Desktop. The `podman:init/start/stop/socket` tasks are no-ops on Windows and will tell you so.

---

## 14. Database Helpers

```powershell
mise run db:inspect     # sqlite3 interactive CLI on arcclaw-meta.db
mise run db:fts-check   # verify FTS5
mise run db:vec-check   # verify lib/vec0 extension for your platform
```

---

## 15. Environment Variables Reference

| Variable | Source | Notes |
|---|---|---|
| `ARCCLAW_VEC_EXT` | `mise.toml` | `../lib/vec0` |
| `ARCCLAW_DATA_DIR_DEFAULT` | `mise.toml` | `../data` |
| All `.env` vars | `_.file = ".env"` | Auto-loaded by mise |
| `GROQ_API_KEY` | `.env` | Required, starts with `gsk_` |
| `ARCCLAW_VAULT_PEPPER` | `.env` | ≥32 bytes, never change after first boot |
| `ARCCLAW_AUDIT_HMAC_KEY` | `.env` | ≥32 bytes, never change after first boot |
| `ARCCLAW_API_KEY_INTERNAL` | `.env` | Bearer token for REST API |
| `TELEGRAM_BOT_TOKEN` | `.env` | Only for `attach` mode |
| `GOAL` | env var | Goal string for `ask`, `dev:ask`, `native:ask` |
| `ROLE` | env var | Optional role override for `ask` tasks |

> **`JDK_JAVA_OPTIONS` is intentionally not set in `mise.toml [env]`** — it gets picked up by
> `mvnw` itself and causes Maven to fail parsing `--add-opens` as a Maven CLI argument.
> Dev tasks use `-Djvm.args=...` (Quarkus forwards to the forked JVM); build tasks set
> `MAVEN_OPTS` inline (read by the mvnw script, not the Maven CLI parser).

---

## 16. Troubleshooting

### `mise ERROR Config files … are not trusted`
```powershell
mise trust
# then reload your shell or open a new terminal
```

### `java: command not found`
```powershell
mise install   # run inside project root
```

### `Permission denied` on mvnw (Linux/macOS)
```powershell
mise run setup   # re-runs chmod +x
```

### `./mvnw: cannot execute` (WSL2 CRLF)
```powershell
mise run setup:wsl-crlf
```

### `Unable to parse command line options: Unrecognized option: --add-opens`
You have `JDK_JAVA_OPTIONS` set globally (shell profile or `mise.toml [env]`). Remove it —
each task sets `MAVEN_OPTS` or `-Djvm.args` internally.

### `sandboxStrategy=NONE` when you expect `PODMAN`
- `mise run dev`? Dev mode always uses `NONE`. Use `mise run serve` or `mise run compose:up`.
- Podman not running? `mise run podman:start` then restart ArcClaw.

### `NullPointerException` in Quarkus Dev Console
```powershell
mise run dev:no-ui
```

### After `build:native` the active JDK is wrong
```powershell
mise run jdk:status   # check current
mise run jdk:temurin  # restore if stuck on GraalVM
```

### OOM during native build
```powershell
mise run build:native-lowmem
# or increase Podman VM RAM (Linux/macOS):
mise run podman:stop
podman machine set --memory 8192
mise run podman:start
mise run build:native
```

### Podman socket missing after restart (WSL2)
```powershell
mise run podman:socket
```

### Groq key rejected
```powershell
mise run setup:env-check
# Boot logs should show: Seeding secret: GROQ_API_KEY
```

### sqlite-vec not loading
```powershell
mise run db:vec-check   # confirms lib/vec0 exists for your platform
mise run info           # confirms ARCCLAW_VEC_EXT is set
# Then restart ArcClaw — vec0 is loaded at boot, not lazily
```

### Native exe exits immediately on Windows
The exe requires a subcommand. Run:
```powershell
mise run native:serve    # or: native:chat / native:attach
# or directly:
.\arcclaw-app\target\arcclaw-app-1.0.0-SNAPSHOT-runner.exe serve
```

---

*ArcClaw V1.0.0 · Quarkus 3.27.2 · JDK 25 · mise tasks · Temurin 25 + GraalVM Community 25.0.2*
*All tasks: pwsh universal — Windows · macOS · WSL2 · Linux*
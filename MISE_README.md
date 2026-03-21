# ArcClaw — mise Task Runner Guide

> **Stack**: JDK 25 · Quarkus 3.27.2 · LangChain4J 1.x · SQLite · Podman (rootless) · Groq Cloud
>
> **This guide** covers `mise` as the unified task runner for **macOS**, **WSL2 (Ubuntu)**, and **Linux**.
> Windows (native PowerShell) → see [§14](#14-windows-powershell-users) — ps1 wrappers in `scripts/windows/`.

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
14. [Windows PowerShell Users](#14-windows-powershell-users)
15. [Database Helpers](#15-database-helpers)
16. [Environment Variables Reference](#16-environment-variables-reference)
17. [Troubleshooting](#17-troubleshooting)

---

## 1. Why mise?

Instead of remembering:
```bash
./mvnw quarkus:dev -pl arcclaw-app -am -Dquarkus.args=serve -Darcclaw.rag.vec-extension-path=../lib/vec0 ...
```
You just type:
```bash
mise run dev
```

`mise` also:
- **Pins JDK 25 (Temurin) per-project** — consistent `java -version` everywhere.
- **Manages both JDKs** — Temurin 25 (dev/build) and GraalVM Community 25.0.2 (native) installed side-by-side; switch with one command.
- **Auto-loads `.env`** into every task and your shell when you `cd` into the project.
- **Self-contained tasks** — all JVM flags and logic live in `mise-tasks/`. No wrapper `.sh` files.
- **No chmod surprises** — `mise run setup` makes every script executable automatically.

---

## 2. Install mise

### macOS / Linux / WSL2

```bash
# One-liner (auto-detects shell)
curl https://mise.run | sh

# Shell-specific (installs + wires activation automatically)
curl https://mise.run/zsh  | sh   # zsh
curl https://mise.run/bash | sh   # bash

# Wire manually if preferred
echo 'eval "$(~/.local/bin/mise activate zsh)"' >> ~/.zshrc    # zsh
echo 'eval "$(~/.local/bin/mise activate bash)"' >> ~/.bashrc   # bash

source ~/.zshrc   # or source ~/.bashrc
```

### Windows — Scoop ✅ Recommended

**Scoop is the recommended Windows installer** — it automatically adds mise shims to `PATH` and avoids the `%PATH%` length limit that winget can trigger. [mise.jdx](https://mise.jdx.dev/installing-mise.html)

```powershell
# Install Scoop (if not already installed)
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
Invoke-RestMethod -Uri https://get.scoop.sh | Invoke-Expression

# Install mise
scoop install mise
```

vcredist2022 installation may be required.
```powershell
scoop bucket add extras
scoop install vcredist2022

Note: wait untill... Running post_install script...WARN  A restart is required to complete the installation

Restart windows
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
> tools installed, winget can silently push you over this limit, breaking PATH resolution for
> other tools. **Prefer Scoop** — it manages all shims under a single directory entry.

### Verify (all platforms)

```bash
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

```bash
cd ArcClaw
mise trust
source ~/.zshrc    # or open a new terminal
mise tasks         # should list all tasks
```

### Skip for your own repos (optional)

```toml
# ~/.config/mise/config.toml
[settings]
trust_all = true
```

Or: `export MISE_TRUST_ALL=1` in `~/.zshrc`.

> ⚠️ `trust_all` on personal machines only. Keep default on CI.

### Trust commands

```bash
mise trust              # trust current directory
mise trust --all        # trust all discovered configs
mise trust --untrust    # revoke trust
mise trust --status     # show trust status
```

---

## 4. Repository Layout

```
ArcClaw/
├── mise.toml                    ← tools (temurin-25 + graalvm), env, 3 inline proxy tasks
├── mise-tasks/
│   ├── serve / chat / attach / ask / clean / info / help
│   ├── jdk/
│   │   ├── graalvm              → mise run jdk:graalvm   (switch to GraalVM 25.0.2)
│   │   ├── temurin              → mise run jdk:temurin   (switch back to Temurin 25)
│   │   ├── status               → mise run jdk:status    (show active + installed JDKs)
│   │   └── install              → mise run jdk:install   (install both JDKs)
│   ├── setup/   wsl-crlf, env-check
│   ├── build/   full, test, native*, native-lowmem*
│   │                (* auto-switch to GraalVM, build, switch back)
│   ├── dev/     chat, attach, ask, no-ui
│   ├── jar/     serve-dev-profile
│   ├── native/  serve, chat, attach
│   ├── compose/ up, down, down-clean, logs, restart, build
│   ├── sandbox/ build, check
│   ├── podman/  init, start, stop, socket, status
│   └── db/      inspect, fts-check, vec-check
├── scripts/
│   └── windows/                 ← PS1 wrappers (Windows-native fallback)
└── mvnw / mvnw.cmd
```

> **JDK default**: `temurin-25` is active for everything except `build:native` and
> `build:native-lowmem` which auto-switch to `graalvm-community-25.0.2` and back.

---

## 5. First-Time Bootstrap

```bash
# 1. Clone and enter
git clone https://github.com/rsrini7/ArcClaw.git
cd ArcClaw

# 2. Trust mise.toml
mise trust

# 3. Install both JDKs + chmod all scripts + .env
mise run setup
# installs temurin-25, graalvm-community-25.0.2, sets up .env, chmod +x all tasks

# 4. Validate .env
mise run setup:env-check

# 5. WSL2 only — fix CRLF if cloned on Windows
mise run setup:wsl-crlf

# 6. Verify JDKs
mise run jdk:status

# 7. Build (uses Temurin 25)
mise run build

# 8. Build Podman sandbox image
mise run podman:init      # macOS only (one-time)
mise run sandbox:build
mise run sandbox:check

# 9. Run
mise run dev              # hot reload on :8080
```

### What `mise run setup` does

1. `.env.example` → `.env` (skips if exists)
2. `chmod +x mvnw`
3. `find mise-tasks -type f | xargs chmod +x`
4. `mise install java@temurin-25`
5. `mise install java@graalvm-community-25.0.2`

---

## 6. JDK Management — Temurin vs GraalVM

ArcClaw uses **two JDKs** managed entirely by mise — no manual downloads, no `JAVA_HOME` editing:

| JDK | Version | Used for |
|---|---|---|
| Temurin (default) | `temurin-25` | All dev, build, jar, native-run tasks |
| GraalVM Community | `graalvm-community-25.0.2` | `build:native`, `build:native-lowmem` only |

### Day-to-day commands

```bash
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

```bash
mise run jdk:install
```

### Windows native support for graalvm
winget install -e --id Microsoft.VisualStudio.2022.BuildTools --override "--passive --wait --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended"

### How `build:native` handles the switch

```
mise run build:native
  │
  ├─ mise use java@graalvm-community-25.0.2   (switches mise.toml [tools.java])
  ├─ ./mvnw clean package -Pnative ...        (GraalVM active)
  └─ mise use java@temurin-25                 (restores mise.toml [tools.java])
```

No manual intervention needed. If the build fails, the script still switches back to Temurin.

---

## 7. All Tasks at a Glance

```bash
mise run help    # or: mise tasks
```

| Task | What it does |
|---|---|
| **`mise run setup`** | `.env`, chmod +x all scripts, install temurin-25 + graalvm-community-25.0.2 |
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
| `mise run dev:ask` | Hot-reload — one-shot task |
| `mise run dev:no-ui` | Hot-reload — Dev UI + console disabled |
| **`mise run serve`** | Packaged jar — REST daemon on :8080 |
| `mise run chat` | Packaged jar — interactive REPL |
| `mise run attach` | Packaged jar — Telegram daemon |
| `mise run ask` | Packaged jar — one-shot task |
| `mise run jar:serve-dev-profile` | Packaged jar with `%dev` profile (no Podman) |
| `mise run native:serve` | Native binary — serve (Linux/WSL2) |
| `mise run native:chat` | Native binary — REPL (Linux/WSL2) |
| `mise run native:attach` | Native binary — Telegram (Linux/WSL2) |
| `mise run compose:up` | Full observability stack up |
| `mise run compose:down` | Stop stack, keep volumes |
| `mise run compose:down-clean` | Stop stack + delete volumes (prompts YES) |
| `mise run compose:logs` | Follow arcclaw container logs |
| `mise run compose:restart` | Restart only arcclaw service |
| `mise run compose:build` | Rebuild all Compose images |
| `mise run sandbox:build` | Build `arcclaw-tool-runner` image |
| `mise run sandbox:check` | Verify tool-runner image exists |
| `mise run podman:init` | macOS: init Podman VM (6 GB RAM, 4 CPUs) |
| `mise run podman:start` | macOS: start Podman VM |
| `mise run podman:stop` | macOS: stop Podman VM |
| `mise run podman:socket` | WSL2/Linux: enable Podman user socket |
| `mise run podman:status` | Podman + image status |
| `mise run db:inspect` | Open `arcclaw-meta.db` in sqlite3 |
| `mise run db:fts-check` | Verify FTS5 available |
| `mise run db:vec-check` | Check `lib/vec0.so` / `.dylib` exists |
| `mise run info` | Print Java, Podman, Docker versions |
| `mise run help` | List all tasks |

---

## 8. Build Tasks

```bash
mise run build           # JVM build, skip tests → quarkus-run.jar
mise run build:full      # all modules (after branch switch or schema change)
mise run build:test      # with tests

# Native — auto-manages GraalVM switch
mise run podman:status
mise run build:native         # full RAM
mise run build:native-lowmem  # 6 GB heap cap

mise run clean
```

Increase Podman VM RAM if native build OOMs:
```bash
mise run podman:stop
podman machine set --memory 8192
mise run podman:start
mise run build:native
```

---

## 9. Dev Mode Tasks (hot reload)

All `dev` / `dev:*` tasks use Temurin 25 and pass JVM flags via `-Djvm.args` (not `JDK_JAVA_OPTIONS`).
No Podman needed — sandbox strategy is `NONE` in dev mode.

```bash
mise run dev           # serve on :8080 · hot reload · Dev UI at /q/dev-ui
mise run dev:chat      # interactive REPL
mise run dev:no-ui     # serve — Dev UI + console disabled (Quarkus NPE workaround)
mise run dev:attach    # Telegram daemon (TELEGRAM_BOT_TOKEN required in .env)
mise run dev:ask -- "Analyze the ArcClaw architecture"
GOAL="Review auth flow" ROLE=debugger mise run dev:ask
```

Available roles: `coordinator` · `researcher` · `architect` · `synthesizer` · `debugger`

---

## 10. JAR Run Tasks

All jar tasks invoke `java` directly — JVM args fully inlined, no delegate `.sh` files.

```bash
mise run serve         # REST daemon on :8080 (CLI profile)
mise run chat          # interactive REPL
mise run attach        # Telegram daemon (TELEGRAM_BOT_TOKEN required)
mise run ask -- "List all active cron jobs"
ROLE=researcher mise run ask -- "Research Quarkus ScopedValues patterns"
mise run jar:serve-dev-profile   # %dev profile, no Podman
```

---

## 11. Native Binary Tasks

**Prerequisite:** `mise run build:native`

```bash
mise run native:serve    # REST daemon (Linux/WSL2 only)
mise run native:chat     # interactive REPL
mise run native:attach   # Telegram daemon
```

> `LD_PRELOAD=/lib/x86_64-linux-gnu/libm.so.6` is set inline — required for `vec0.so` symbol resolution.

---

## 12. Docker Compose Tasks

**Prerequisite:** `mise run build:native`

```bash
mise run compose:up          # ArcClaw + Ollama + OTel + Jaeger + Prometheus
# ArcClaw:   http://localhost:8080
# Jaeger:    http://localhost:16686
# Prometheus:http://localhost:9090
# Ollama:    http://localhost:11434

mise run compose:logs        # follow logs
mise run compose:restart     # restart arcclaw only
mise run compose:down        # stop, keep volumes
mise run compose:down-clean  # stop + delete volumes (prompts YES)
```

---

## 13. Podman Setup Tasks

### macOS

```bash
mise run podman:init    # one-time VM creation (6 GB RAM, 4 CPUs, 40 GB disk)
mise run podman:start
mise run podman:stop
mise run podman:status
```

### WSL2 / Ubuntu

```bash
mise run podman:socket
# Prints DOCKER_HOST export line — add to ~/.bashrc
```

---

## 14. Windows PowerShell Users

PS1 wrappers are in `scripts/windows/`. For the full `mise` experience use **WSL2 Ubuntu**.

| Goal | Command |
|---|---|
| Build (skip tests) | `.\scripts\windows\mvn-clean-install-skiptests.ps1` |
| Dev — serve | `.\scripts\windows\arcclaw-dev.ps1` |
| Dev — chat/attach/ask | `.\scripts\windows\arcclaw-dev.ps1 chat\|attach\|ask "goal"` |
| Jar — serve/chat/attach | `.\scripts\windows\arcclaw-cli.ps1 serve\|chat\|attach` |
| Jar — ask with role | `.\scripts\windows\arcclaw-cli.ps1 ask "goal" --role coordinator` |
| Jar dev-serve | `.\scripts\windows\arcclaw-dev-jar-serve.ps1` |

---

## 15. Database Helpers

```bash
mise run db:inspect     # sqlite3 interactive CLI on arcclaw-meta.db
mise run db:fts-check   # verify FTS5
mise run db:vec-check   # verify lib/vec0.so or .dylib
```

---

## 16. Environment Variables Reference

| Variable | Source | Notes |
|---|---|---|
| `ARCCLAW_VEC_EXT` | `mise.toml` | `../lib/vec0` |
| `ARCCLAW_DATA_DIR_DEFAULT` | `mise.toml` | `../data` |
| All `.env` vars | `_.file = ".env"` | Auto-loaded |
| `GROQ_API_KEY` | `.env` | Required, starts with `gsk_` |
| `ARCCLAW_VAULT_PEPPER` | `.env` | ≥32 bytes, never change after first boot |
| `ARCCLAW_AUDIT_HMAC_KEY` | `.env` | ≥32 bytes, never change after first boot |
| `ARCCLAW_API_KEY_INTERNAL` | `.env` | Bearer token for REST API |
| `TELEGRAM_BOT_TOKEN` | `.env` | Only for `attach` mode |

> **`JDK_JAVA_OPTIONS` is intentionally not set in `mise.toml [env]`** — it gets picked up by
> `mvnw` itself and causes Maven to fail parsing `--add-opens` as a Maven CLI argument.
> Dev tasks use `-Djvm.args=...` (Quarkus forwards to forked JVM); build tasks use
> `export MAVEN_OPTS=...` inline (read by mvnw script, not Maven CLI parser).

---

## 17. Troubleshooting

### `mise ERROR Config files … are not trusted`
```bash
mise trust && source ~/.zshrc
```

### `java: command not found`
```bash
mise install   # inside project root
```

### `Permission denied` on any task
```bash
mise run setup   # re-runs chmod +x
```

### `./mvnw: cannot execute` (WSL2 CRLF)
```bash
mise run setup:wsl-crlf
```

### `Unable to parse command line options: Unrecognized option: --add-opens`
You have `JDK_JAVA_OPTIONS` set globally (shell profile or `mise.toml [env]`). Remove it —
each task sets `MAVEN_OPTS` or `-Djvm.args` internally.

### `sandboxStrategy=NONE` when you expect `PODMAN`
- `mise run dev`? Dev mode always uses `NONE`. Use `mise run serve` or `mise run compose:up`.
- Podman not running? `mise run podman:start` then restart ArcClaw.

### `NullPointerException` in Quarkus Dev Console
```bash
mise run dev:no-ui
```

### After `build:native` the active JDK is wrong
```bash
mise run jdk:status   # check current
mise run jdk:temurin  # restore if stuck on GraalVM
```

### OOM during native build
```bash
mise run build:native-lowmem
# or:
mise run podman:stop && podman machine set --memory 8192 && mise run podman:start
mise run build:native
```

### Podman socket missing after restart (WSL2)
```bash
mise run podman:socket
```

### Groq key rejected
```bash
mise run setup:env-check
# Boot logs should show: Seeding secret: GROQ_API_KEY
```

### sqlite-vec not loading
```bash
mise run db:vec-check
mise run info   # confirm ARCCLAW_VEC_EXT is set
```

---

*ArcClaw V1.0.0 · Quarkus 3.27.2 · JDK 25 · mise tasks · Temurin 25 + GraalVM Community 25.0.2*

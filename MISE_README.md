# ArcClaw — mise Task Runner Guide

> **Stack**: JDK 25 · Quarkus 3.27.2 · LangChain4J 1.x · SQLite · Podman (rootless) · Groq Cloud
>
> **This guide** covers `mise` as the unified task runner for **macOS**, **WSL2 (Ubuntu)**, and **Linux**.
> Windows (native PowerShell) → see [§13](#13-windows-powershell-users) — ps1 wrappers live in `scripts/windows/`.

---

## Table of Contents

1. [Why mise?](#1-why-mise)
2. [Install mise](#2-install-mise)
3. [Trust mise.toml (First-Time Security Step)](#3-trust-misetoml-first-time-security-step)
4. [Repository Layout](#4-repository-layout)
5. [First-Time Bootstrap](#5-first-time-bootstrap)
6. [All Tasks at a Glance](#6-all-tasks-at-a-glance)
7. [Build Tasks](#7-build-tasks)
8. [Dev Mode Tasks (hot reload)](#8-dev-mode-tasks-hot-reload)
9. [JAR Run Tasks](#9-jar-run-tasks)
10. [Native Binary Tasks](#10-native-binary-tasks)
11. [Docker Compose Tasks](#11-docker-compose-tasks)
12. [Podman Setup Tasks](#12-podman-setup-tasks)
13. [Database Helpers](#13-database-helpers)
14. [Windows PowerShell Users](#14-windows-powershell-users)
15. [Environment Variables Reference](#15-environment-variables-reference)
16. [Troubleshooting](#16-troubleshooting)

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
- **Auto-loads `.env`** into every task and your shell when you `cd` into the project.
- **Self-contained tasks** — all JVM flags, paths, and logic live directly in `mise-tasks/` scripts. No wrapper `.sh` files needed.
- **No chmod surprises** — `mise run setup` makes every script executable automatically.

---

## 2. Install mise

### macOS / Linux / WSL2

```bash
# One-liner (bash/zsh/fish auto-detected)
curl https://mise.run | sh

# Or shell-specific (installs + wires activation automatically)
curl https://mise.run/zsh  | sh   # zsh
curl https://mise.run/bash | sh   # bash

# Wire manually if preferred (pick one)
echo 'eval "$(~/.local/bin/mise activate zsh)"'  >> ~/.zshrc    # zsh
echo 'eval "$(~/.local/bin/mise activate bash)"' >> ~/.bashrc   # bash

source ~/.zshrc   # or source ~/.bashrc
```

### Windows — Scoop ✅ Recommended

**Scoop is the recommended Windows installer** — it automatically adds mise shims to `PATH` and avoids the `%PATH%` length limit that winget can trigger. [mise.jdx](https://mise.jdx.dev/installing-mise.html)

```powershell
# Install Scoop if you don't have it
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
Invoke-RestMethod -Uri https://get.scoop.sh | Invoke-Expression

# Install mise
scoop install mise
```

Wire to your **PowerShell profile** (Windows Terminal / PowerShell 7+):
```powershell
# PowerShell 7+ (pwsh.exe) — Windows Terminal default
echo '(&mise activate pwsh) | Out-String | Invoke-Expression' `
  >> $HOME\Documents\PowerShell\Microsoft.PowerShell_profile.ps1

# PowerShell 5.1 (powershell.exe) — legacy Windows PowerShell
echo '(&mise activate pwsh) | Out-String | Invoke-Expression' `
  >> $HOME\Documents\WindowsPowerShell\Microsoft.PowerShell_profile.ps1
```

Reload:
```powershell
. $PROFILE
```

### Windows — winget ⚠️ Caution

```powershell
winget install jdx.mise
```

> ⚠️ **PATH length limit**: Windows caps the user `%PATH%` at ~2048 characters.  If you [mise.jdx](https://mise.jdx.dev/installing-mise.html)
> already have many tools installed, `winget install jdx.mise` can push you over that limit,
> silently breaking PATH resolution for other tools. **Prefer Scoop** — it manages shims in a
> single directory so only one path entry is needed regardless of how many tools are installed.

### Verify (all platforms)

```bash
mise --version     # mise 2025.x.x
mise doctor        # diagnoses common setup issues
```

---

## 3. Trust mise.toml (First-Time Security Step)

After cloning and `cd`-ing into the project, mise shows this on first use:

```
mise ERROR Config files in ~/Ws/ArcClawInternal/mise.toml are not trusted.
Trust them with `mise trust`.
```

This is **expected** — mise won't execute config from an unknown directory until you explicitly approve it.

### Fix — one command

```bash
cd ArcClawInternal    # must be inside the project directory
mise trust
source ~/.zshrc       # or open a new terminal
```

Verify:
```bash
mise tasks            # lists all arcclaw tasks with descriptions
```

### Skip for your own repos (optional)

**`~/.config/mise/config.toml`:**
```toml
[settings]
trust_all = true
```

**Or environment variable** (add to `~/.zshrc`):
```bash
export MISE_TRUST_ALL=1
```

> ⚠️ Use `trust_all` only on personal machines. Keep the default on CI/shared environments.

### Trust command reference

```bash
mise trust              # trust current directory
mise trust --all        # trust all discovered config files
mise trust --untrust    # revoke trust
mise trust --status     # show trust status
```

---

## 4. Repository Layout

```
ArcClaw/
├── mise.toml                    ← tools (JDK 25), env, 3 inline proxy tasks
├── mise-tasks/                  ← ALL tasks as self-contained executable scripts
│   ├── serve                    → mise run serve
│   ├── chat                     → mise run chat
│   ├── attach                   → mise run attach
│   ├── ask                      → mise run ask
│   ├── clean                    → mise run clean
│   ├── info                     → mise run info
│   ├── help                     → mise run help
│   ├── setup/
│   │   ├── wsl-crlf             → mise run setup:wsl-crlf
│   │   └── env-check            → mise run setup:env-check
│   ├── build/
│   │   ├── full                 → mise run build:full
│   │   ├── test                 → mise run build:test
│   │   ├── native               → mise run build:native
│   │   └── native-lowmem        → mise run build:native-lowmem
│   ├── dev/
│   │   ├── chat                 → mise run dev:chat
│   │   ├── attach               → mise run dev:attach
│   │   ├── ask                  → mise run dev:ask
│   │   └── no-ui                → mise run dev:no-ui
│   ├── jar/
│   │   └── serve-dev-profile    → mise run jar:serve-dev-profile
│   ├── native/
│   │   ├── serve                → mise run native:serve
│   │   ├── chat                 → mise run native:chat
│   │   └── attach               → mise run native:attach
│   ├── compose/
│   │   ├── up                   → mise run compose:up
│   │   ├── down                 → mise run compose:down
│   │   ├── down-clean           → mise run compose:down-clean
│   │   ├── logs                 → mise run compose:logs
│   │   ├── restart              → mise run compose:restart
│   │   └── build                → mise run compose:build
│   ├── sandbox/
│   │   ├── build                → mise run sandbox:build
│   │   └── check                → mise run sandbox:check
│   ├── podman/
│   │   ├── init                 → mise run podman:init
│   │   ├── start                → mise run podman:start
│   │   ├── stop                 → mise run podman:stop
│   │   ├── socket               → mise run podman:socket
│   │   └── status               → mise run podman:status
│   └── db/
│       ├── inspect              → mise run db:inspect
│       ├── fts-check            → mise run db:fts-check
│       └── vec-check            → mise run db:vec-check
├── scripts/
│   └── windows/                 ← Windows-native PS1 fallbacks (no mise/WSL2 required)
│       ├── arcclaw-cli.ps1
│       ├── arcclaw-dev.ps1
│       ├── arcclaw-dev-jar-serve.ps1
│       └── mvn-clean-install-skiptests.ps1
└── mvnw / mvnw.cmd              ← Maven wrapper (must stay at repo root)
```

> **Design note**: `setup`, `build`, and `dev` are defined inline in `mise.toml` because the filesystem
> cannot have both a file and a directory with the same name (e.g. `mise-tasks/build` file vs
> `mise-tasks/build/` directory). Every other task is a fully self-contained script in `mise-tasks/`.
>
> **No root-level .sh files**: all JVM args and launch logic previously in `arcclaw-cli.sh`,
> `arcclaw-dev.sh`, and `arcclaw-native.sh` are now inlined directly into the relevant task scripts.

---

## 5. First-Time Bootstrap

```bash
# 1. Clone and enter
git clone https://github.com/rsrini7/ArcClaw.git
cd ArcClaw

# 2. Trust mise.toml
mise trust

# 3. Install JDK 25 (mise handles this)
mise install
java -version   # openjdk 25

# 4. Initialise .env + chmod all scripts
mise run setup

# 5. Validate .env
mise run setup:env-check

# 6. WSL2 only — fix CRLF if cloned on Windows
mise run setup:wsl-crlf

# 7. Build
mise run build

# 8. Build Podman sandbox image (required for %prod)
mise run podman:init      # macOS only (one-time)
mise run sandbox:build    # all platforms
mise run sandbox:check    # verify

# 9. Run
mise run dev              # hot reload on :8080
```

### What `mise run setup` does

1. Copies `.env.example` → `.env` (skips if exists)
2. `chmod +x mvnw`
3. `find mise-tasks -type f | xargs chmod +x` — every task script becomes executable

Open `.env` and set at minimum:

| Variable | Notes |
|---|---|
| `GROQ_API_KEY` | Starts with `gsk_` — required for all LLM calls |
| `ARCCLAW_VAULT_PEPPER` | ≥32 bytes, **never change** after first boot |
| `ARCCLAW_AUDIT_HMAC_KEY` | ≥32 bytes, **never change** after first boot |
| `ARCCLAW_API_KEY_INTERNAL` | Bearer token for REST API (`X-ArcClaw-API-Key` header) |
| `TELEGRAM_BOT_TOKEN` | Only required for `attach` mode |

---

## 6. All Tasks at a Glance

```bash
mise run help    # or: mise tasks
```

| Task | What it does |
|---|---|
| **`mise run setup`** | Copy `.env.example → .env`, chmod +x mvnw + all mise-tasks |
| `mise run setup:wsl-crlf` | Fix CRLF line endings in mvnw (WSL2 after Windows clone) |
| `mise run setup:env-check` | Validate `.env` for missing/placeholder values |
| **`mise run build`** | JVM build, skip tests — fast daily driver |
| `mise run build:full` | Clean install all modules (arcclaw-core, mcp, rag, app) |
| `mise run build:test` | Build and run all tests |
| `mise run build:native` | GraalVM native binary via Mandrel container |
| `mise run build:native-lowmem` | Native build with 6 GB heap cap |
| `mise run clean` | `mvnw clean` |
| **`mise run dev`** | Quarkus hot-reload — serve on :8080 (strategy: NONE) |
| `mise run dev:chat` | Hot-reload — interactive REPL |
| `mise run dev:attach` | Hot-reload — Telegram daemon |
| `mise run dev:ask` | Hot-reload — one-shot task |
| `mise run dev:no-ui` | Hot-reload — Dev UI + console disabled (NPE workaround) |
| **`mise run serve`** | Packaged jar — REST daemon on :8080 |
| `mise run chat` | Packaged jar — interactive REPL |
| `mise run attach` | Packaged jar — Telegram channel daemon |
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
| `mise run db:fts-check` | Verify FTS5 is available |
| `mise run db:vec-check` | Check `lib/vec0.so` / `.dylib` exists |
| `mise run info` | Print Java, Podman, Docker versions |
| `mise run help` | List all tasks |

---

## 7. Build Tasks

```bash
# Fast daily build
mise run build
# → arcclaw-app/target/quarkus-app/quarkus-run.jar

# Full clean install (all modules — use after branch switch or schema change)
mise run build:full

# With tests
mise run build:test

# Native binary (Podman required, ≥8 GB RAM, 5–15 min)
mise run podman:status
mise run build:native
# → arcclaw-app/target/arcclaw-app-1.0.0-SNAPSHOT-runner

# Memory-constrained
mise run build:native-lowmem     # caps native-image at 6 GB heap
```

Increase Podman VM RAM if native build OOMs:
```bash
mise run podman:stop
podman machine set --memory 8192
mise run podman:start
mise run build:native
```

---

## 8. Dev Mode Tasks (hot reload)

All `dev` / `dev:*` tasks run `./mvnw quarkus:dev` directly — **no delegate `.sh` file**.
JVM args, profile, vec extension path, and data directory are fully inlined.
Sandbox strategy is `NONE` in dev mode — no Podman needed.

```bash
mise run dev           # serve on :8080 · hot reload · Dev UI at /q/dev-ui
mise run dev:chat      # interactive REPL
mise run dev:no-ui     # serve — Dev UI + console disabled (Quarkus NPE workaround)
```

### Telegram daemon

```bash
# TELEGRAM_BOT_TOKEN must be set in .env
mise run dev:attach
# Watch for: INFO [TelegramChannelRoute] routeStatus=Started
```

### One-shot task

```bash
mise run dev:ask -- "Analyze the ArcClaw repository architecture"

# With role:
GOAL="Review auth flow" ROLE=debugger mise run dev:ask
```

Available roles: `coordinator` · `researcher` · `architect` · `synthesizer` · `debugger`

---

## 9. JAR Run Tasks

These tasks run the **packaged jar** directly — all JVM args and profile flags are inlined.
Each task checks the jar exists first and prints a clear error if not.

```bash
mise run serve         # REST daemon on :8080 (CLI profile)
mise run chat          # interactive REPL
mise run attach        # Telegram daemon (requires TELEGRAM_BOT_TOKEN)
mise run ask -- "List all active cron jobs"
ROLE=researcher mise run ask -- "Research Quarkus ScopedValues patterns"

# Packaged jar with %dev profile (no Podman sandbox — smoke-test mode)
mise run jar:serve-dev-profile
```

---

## 10. Native Binary Tasks

All native tasks inline `LD_PRELOAD=/lib/x86_64-linux-gnu/libm.so.6` (required for `vec0.so`
symbol resolution in GraalVM native images). Each task checks the binary exists first.

**Prerequisite:** `mise run build:native`

```bash
mise run native:serve    # REST daemon on :8080
mise run native:chat     # interactive REPL
mise run native:attach   # Telegram daemon (requires TELEGRAM_BOT_TOKEN)
```

> **Linux / WSL2 only.** Native binaries cannot run on macOS or Windows hosts.

---

## 11. Docker Compose Tasks

The Compose stack: **ArcClaw** (native) + **Ollama** + **OTel Collector** + **Jaeger** + **Prometheus**.

**Prerequisite:** `mise run build:native`

```bash
mise run compose:up          # start full stack
# ArcClaw REST:  http://localhost:8080
# Jaeger UI:     http://localhost:16686
# Prometheus:    http://localhost:9090
# Ollama:        http://localhost:11434

mise run compose:logs        # follow arcclaw logs live
mise run compose:restart     # restart only arcclaw service
mise run compose:down        # stop, keep volumes
mise run compose:down-clean  # stop + delete all volumes (prompts YES)
```

---

## 12. Podman Setup Tasks

### macOS

```bash
mise run podman:init    # one-time: create VM (6 GB RAM, 4 CPUs, 40 GB disk)
mise run podman:start   # day-to-day start
mise run podman:stop    # day-to-day stop
mise run podman:status  # full status check
```

### WSL2 / Ubuntu

```bash
mise run podman:socket
# Prints the DOCKER_HOST export line — add it to ~/.bashrc
```

---

## 13. Database Helpers

```bash
mise run db:inspect     # open arcclaw-meta.db in sqlite3 interactive CLI
mise run db:fts-check   # verify FTS5 is compiled into your SQLite build
mise run db:vec-check   # check lib/vec0.so (Linux) or lib/vec0.dylib (macOS) exists
```

---

## 14. Windows PowerShell Users

The `.ps1` wrappers have been relocated to `scripts/windows/` and updated with a
comment pointing to their `mise run` equivalents. Copy them to the repo root if you
need to run them directly:

```powershell
# From repo root on Windows
copy scripts\windows\arcclaw-cli.ps1 .
copy scripts\windows\arcclaw-dev.ps1 .
```

| Goal | Command |
|---|---|
| Build (skip tests) | `.\scripts\windows\mvn-clean-install-skiptests.ps1` |
| Dev — serve | `.\scripts\windows\arcclaw-dev.ps1` |
| Dev — chat | `.\scripts\windows\arcclaw-dev.ps1 chat` |
| Dev — attach | `.\scripts\windows\arcclaw-dev.ps1 attach` |
| Dev — ask | `.\scripts\windows\arcclaw-dev.ps1 ask "Your goal"` |
| Jar — serve | `.\scripts\windows\arcclaw-cli.ps1 serve` |
| Jar — chat | `.\scripts\windows\arcclaw-cli.ps1 chat` |
| Jar — attach | `.\scripts\windows\arcclaw-cli.ps1 attach` |
| Jar — ask | `.\scripts\windows\arcclaw-cli.ps1 ask "Your goal"` |
| Jar — ask with role | `.\scripts\windows\arcclaw-cli.ps1 ask "goal" --role coordinator` |
| Jar dev-serve | `.\scripts\windows\arcclaw-dev-jar-serve.ps1` |

For the full `mise` experience on Windows, use **WSL2 Ubuntu** — all `mise run` commands
work identically there.

---

## 15. Environment Variables Reference

`mise.toml [env]` loads `.env` and sets these automatically in every task and your shell:

| Variable | Source | Notes |
|---|---|---|
| `JDK_JAVA_OPTIONS` | `mise.toml` | All `--add-opens` + unsafe flags for JDK 25 |
| `ARCCLAW_VEC_EXT` | `mise.toml` | `../lib/vec0` — sqlite-vec extension path |
| `ARCCLAW_DATA_DIR_DEFAULT` | `mise.toml` | `../data` |
| All `.env` variables | `_.file = ".env"` | Loaded automatically via mise dotenv |
| `GROQ_API_KEY` | `.env` | Required — starts with `gsk_` |
| `ARCCLAW_VAULT_PEPPER` | `.env` | ≥32 bytes, must not change after first boot |
| `ARCCLAW_AUDIT_HMAC_KEY` | `.env` | ≥32 bytes, must not change after first boot |
| `ARCCLAW_API_KEY_INTERNAL` | `.env` | Bearer token for `X-ArcClaw-API-Key` header |
| `TELEGRAM_BOT_TOKEN` | `.env` | Only for `attach` mode |

---

## 16. Troubleshooting

### `mise ERROR Config files … are not trusted`
```bash
cd ArcClawInternal
mise trust
source ~/.zshrc
```
To skip permanently for personal repos, add `trust_all = true` to `~/.config/mise/config.toml`.

### `mise: command not found`
```bash
source ~/.bashrc   # or source ~/.zshrc
```

### `java: command not found` inside a mise task
```bash
mise install   # inside the project root — downloads temurin-25
```

### `Permission denied` on any task script
```bash
mise run setup   # re-runs chmod +x on all mise-tasks/ scripts
```

### `./mvnw: cannot execute: required file not found` (WSL2)
```bash
mise run setup:wsl-crlf
```

### `sandboxStrategy=NONE` when you expect `PODMAN`
1. `mise run dev`? Dev mode always uses `NONE`. Use `mise run serve` or `mise run compose:up`.
2. Podman not running when ArcClaw started? Restart after `mise run podman:start`.
3. On Windows (not WSL2)? OS check bypasses Linux namespaces. Run inside WSL2.

### `NullPointerException` in Quarkus Dev Console
```bash
mise run dev:no-ui
```

### Podman socket missing after restart (WSL2)
```bash
mise run podman:socket
```

### OOM during native build
```bash
mise run build:native-lowmem
# or:
mise run podman:stop
podman machine set --memory 8192
mise run podman:start
mise run build:native
```

### `lstat /arcclaw-app/target` (docker compose up)
```bash
mise run build:native
mise run compose:up
```

### Ollama port 11434 collision
Edit `docker-compose.yaml`: `"11434:11434"` → `"11435:11434"`, then `mise run compose:up`.

### FTS5 not available
```bash
mise run db:fts-check
sudo apt install sqlite3   # Ubuntu
```

### sqlite-vec not loading
```bash
mise run db:vec-check
mise run info              # confirm ARCCLAW_VEC_EXT is set
```

### Groq key rejected
- Must start with `gsk_`, no whitespace.
- Run `mise run setup:env-check`.
- Boot logs should show: `Seeding secret: GROQ_API_KEY`

---

*ArcClaw V1.0.0 · Quarkus 3.27.2 · JDK 25 · mise tasks (Option A — fully self-contained)*

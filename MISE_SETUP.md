# ArcClaw — mise Task Runner Guide

> **Stack**: JDK 25 · Quarkus 3.27.2 · LangChain4J 1.x · SQLite · Podman (rootless) · Groq Cloud
>
> **This guide** covers `mise` as the unified task runner for **Windows (pwsh)**, **macOS**, **WSL2 (Ubuntu)**, and **Linux**.
> One config file — `mise.toml` — runs on all platforms via PowerShell (pwsh), which mise installs automatically.

***

## Table of Contents

1. [Why mise?](#1-why-mise)
2. [Install mise](#2-install-mise)
3. [First-Time Bootstrap](#3-first-time-bootstrap)
4. [Trust the Config File](#4-trust-the-config-file)
5. [Repository Layout](#5-repository-layout)
6. [JDK Management — Temurin vs GraalVM](#6-jdk-management--temurin-vs-graalvm)
7. [All Tasks at a Glance](#7-all-tasks-at-a-glance)
8. [Build Tasks](#8-build-tasks)
9. [Dev Mode Tasks (hot reload)](#9-dev-mode-tasks-hot-reload)
10. [JAR Run Tasks](#10-jar-run-tasks)
11. [Native Binary Tasks](#11-native-binary-tasks)
12. [Creating Release Archives (`dist:pack`)](#12-creating-release-archives-distpack)
13. [Docker Compose Tasks](#13-docker-compose-tasks)
14. [Podman Setup Tasks](#14-podman-setup-tasks)
15. [Database Helpers](#15-database-helpers)
16. [Environment Variables Reference](#16-environment-variables-reference)
17. [Troubleshooting](#17-troubleshooting)

***

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

- **One config, all platforms** — `mise.toml` runs identically on Windows, macOS, Linux, WSL2 via PowerShell.
- **Pins JDK 25 (Temurin) per-project** — consistent `java -version` everywhere.
- **Manages both JDKs** — Temurin 25 (dev/build) and GraalVM Community 25.0.2 (native) side-by-side.
- **Auto-loads `.env`** into every task and your shell when you `cd` into the project.
- **Tab completion** — `mise run <TAB>` lists and filters all task names. Powered by the `usage` tool, installed automatically.

***

## 2. Install mise

### Windows — Scoop ✅ Recommended

```powershell
# Install Scoop (if not already installed)
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser
Invoke-RestMethod -Uri https://get.scoop.sh | Invoke-Expression

# Install mise
scoop install mise
```

Wire to your PowerShell profile — add to `$PROFILE`:

```powershell
(&mise activate pwsh) | Out-String | Invoke-Expression
```

Reload: `. $PROFILE`

### Windows — winget ⚠️ Caution

```powershell
winget install jdx.mise
```

> ⚠️ **PATH length limit** — Windows caps `%PATH%` at ~2048 characters. Prefer Scoop.

### Windows — Native build prerequisite (GraalVM)

```powershell
winget install -e --id Microsoft.VisualStudio.2022.BuildTools `
    --override "--passive --wait --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended"
```

### macOS / Linux / WSL2

```bash
curl https://mise.run | sh

# Wire to shell
echo 'eval "$(~/.local/bin/mise activate bash)"' >> ~/.bashrc   # bash
echo 'eval "$(~/.local/bin/mise activate zsh)"'  >> ~/.zshrc    # zsh

source ~/.bashrc   # or ~/.zshrc
```

### Verify (all platforms)

```bash
mise --version    # mise 2025.x.x
mise doctor       # diagnoses common setup issues
```

***

## 3. First-Time Bootstrap

```bash
# 1. Clone and enter
git clone https://github.com/rsrini7/ArcClaw.git
cd ArcClaw

# 2. Trust the config file
mise trust --all

# 3. Install all tools (pwsh + java + maven + usage)
mise install

# 4. Write shell profile — activate + tab completion (one-time, idempotent)
mise run setup:profile
# Windows:       . $PROFILE
# Linux/macOS:   source ~/.bashrc  (or ~/.zshrc)

# 5. Install both JDKs + create .env
mise run setup

# 6. Validate .env
mise run setup:env-check

# 7. WSL2 only — fix CRLF if cloned on Windows
mise run setup:wsl-crlf

# 8. Verify JDKs
mise run jdk:status

# 9. Build (uses Temurin 25)
mise run build-app

# 10. macOS only — init Podman VM
mise run podman:init
mise run sandbox:check

# 11. Run
mise run dev              # hot reload on :8080
```

### What `mise install` installs

| Tool | Version | Purpose |
|---|---|---|
| `powershell` | latest | All task scripts — runs identically on all platforms |
| `java` | temurin-25 | Default JDK for all dev, build, jar, native-run tasks |
| `java` | graalvm-community-25.0.2 | Native image compilation only |
| `maven` | 3.9 | Build tool |
| `usage` | latest | `mise run <TAB>` tab completion |

### What `mise run setup:profile` does

Writes two lines to your shell profile (each with independent idempotency — safe to re-run):

```
# Windows ($PROFILE):
(&mise activate pwsh) | Out-String | Invoke-Expression
mise completion powershell | Out-String | Invoke-Expression

# bash (~/.bashrc):
eval "$(~/.local/bin/mise activate bash)"
eval "$(mise completion bash)"

# zsh (~/.zshrc):
eval "$(~/.local/bin/mise activate zsh)"
eval "$(mise completion zsh)"
```

After reloading: `mise run <TAB>` shows all tasks with tab completion.

### What `mise run setup` does

1. `.env.example` → `.env` (skips if exists)
2. `chmod +x mvnw` (Linux/macOS/WSL2 only)
3. `mise install java@temurin-25`
4. `mise install java@graalvm-community-25.0.2`

***

## 4. Trust the Config File

After cloning, mise shows this on first use:

```
mise ERROR Config files in ~/Ws/ArcClaw/mise.toml are not trusted.
```

This is **expected** — supply-chain protection. Trust once:

```bash
mise trust --all
# or:
mise trust mise.toml
```

### Skip for your own repos (optional)

```toml
# ~/.config/mise/config.toml
[settings]
trust_all = true
```

> ⚠️ `trust_all` on personal machines only. Keep default on CI.

***

## 5. Repository Layout

```
ArcClaw/
├── mise.toml            ← single unified config — all tasks, all platforms (pwsh)
├── mvnw / mvnw.cmd
├── lib/
│   ├── vec0.dll         ← Windows  (Auto-installed on first boot)
│   ├── vec0.so          ← Linux    (Auto-installed on first boot)
│   └── vec0.dylib       ← macOS    (Auto-installed on first boot)
├── dist/                ← produced by: mise run dist  (gitignored)
└── arcclaw-app/
    └── target/
        ├── quarkus-app/quarkus-run.jar           ← produced by: mise run build-app
        ├── arcclaw-app-1.0.0-SNAPSHOT-runner      ← Linux/macOS native
        └── arcclaw-app-1.0.0-SNAPSHOT-runner.exe  ← Windows native
```

> `mise.bash.toml`, `mise.pwsh.toml`, `scripts/windows/`, and `mise-tasks/` are **no longer needed** — safe to delete.

> **JDK default**: `temurin-25` is active for everything except `build:native`, `build:native-local`, and `build:native-lowmem`, which auto-switch to `graalvm-community-25.0.2` and back.

> **vec/data paths**: `dev*` tasks pass `../lib/vec0` and `../data` because `quarkus:dev` forks Maven inside `arcclaw-app/`. All other tasks use `./lib/vec0` and `./data`.

***

## 6. JDK Management — Temurin vs GraalVM

ArcClaw uses **two JDKs** managed entirely by mise:

| JDK | Version | Used for |
|---|---|---|
| Temurin (default) | `temurin-25` | All dev, build, jar, native-run tasks |
| GraalVM Community | `graalvm-community-25.0.2` | `build:native`, `build:native-local`, `build:native-lowmem` only |

```bash
mise run jdk:status       # see active JDK + all installed

mise run jdk:graalvm      # switch to GraalVM
mise run build:native     # build native (auto-manages JDK switch)
mise run jdk:temurin      # switch back

mise run jdk:install      # install both JDKs (idempotent)
```

> All `mise use` calls in tasks specify `--path mise.toml` — changes stay in `mise.toml` only.

***

## 7. All Tasks at a Glance

```bash
mise run help    # or: mise tasks
```

| Task | What it does |
|---|---|
| `mise run start` | Bootstrap guide — works before `mise install` |
| **`mise run setup:profile`** | Write mise activate + completion to shell profile (idempotent) |
| **`mise run setup`** | `.env`, chmod +x mvnw, install JDKs |
| `mise run setup:wsl-crlf` | Fix CRLF in mvnw (WSL2 after Windows clone) |
| `mise run setup:env-check` | Validate `.env` for missing/placeholder values |
| **`mise run jdk:status`** | Show active JDK + all installed |
| `mise run jdk:graalvm` | Switch to GraalVM Community 25.0.2 |
| `mise run jdk:temurin` | Switch back to Temurin 25 |
| `mise run jdk:install` | Install both JDKs (idempotent) |
| **`mise run build-app`** | JVM build, skip tests — fast daily driver |
| `mise run build:full` | Clean install all modules, skip tests |
| `mise run build:test` | Build and run all tests |
| `mise run build:native` | Native binary — auto-detects Podman or local GraalVM |
| `mise run build:native-local` | Native binary — always local GraalVM, no container |
| `mise run build:native-lowmem` | Native binary — local GraalVM, 6 GB heap cap |
| `mise run clean` | `mvnw clean` |
| **`mise run dev`** | Hot-reload serve on :8080 (strategy: NONE) |
| `mise run dev:chat` | Hot-reload — interactive REPL |
| `mise run dev:attach` | Hot-reload — Telegram daemon |
| `mise run dev:ask` | Hot-reload — one-shot task (`$env:GOAL="..."`) |
| `mise run dev:no-ui` | Hot-reload — Dev UI + console disabled |
| `mise run dev:kill` | Kill any lingering Quarkus background processes |
| **`mise run jar:serve`** | Packaged jar — REST daemon on :8080 |
| `mise run jar:chat` | Packaged jar — interactive REPL |
| `mise run jar:attach` | Packaged jar — Telegram daemon |
| `mise run jar:ask` | Packaged jar — one-shot task (`$env:GOAL="..."`) |
| `mise run jar:run-dev-profile` | Packaged jar with `%dev` profile |
| **`mise run native:serve`** | Native binary — REST daemon on :8080 |
| `mise run native:chat` | Native binary — interactive REPL |
| `mise run native:attach` | Native binary — Telegram daemon |
| `mise run native:ask` | Native binary — one-shot task (`$env:GOAL="..."`) |
| **`mise run dist`** | Assemble `dist/` folder: binary + vec0 + launchers |
| `mise run dist:pack` | Create OS-specific release archives |
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

***

## 8. Build Tasks

```bash
mise run build-app              # JVM build, skip tests → quarkus-run.jar
mise run build:full             # all modules (after branch switch), skip tests
mise run build:test             # with tests
mise run clean
```

### .env shielding — secrets stay out of the binary

All three `build:native*` tasks auto-shield `.env` before building and restore it in the `finally` block — even if the build fails:

```
build:native
  ├─ .env         → .env.build-backup   (if .env exists)
  ├─ build.env    → .env                (if build.env exists)
  ├─ mvnw clean package -Pnative ...
  └─ .env.build-backup → .env           (always restored)
```

### Native build strategy

`build:native` resolves in this order:

1. **Windows** → always local GraalVM (container build produces Linux ELF)
2. **`ARCCLAW_NATIVE_BUILD=local`** → force local GraalVM
3. **`ARCCLAW_NATIVE_BUILD=container`** → force Podman
4. **Podman detected and running** → container build (recommended on Linux/macOS)
5. **Podman not found/running** → local GraalVM + hint

```bash
mise run build:native           # auto-detect
mise run build:native-local     # always local GraalVM
mise run build:native-lowmem    # local GraalVM, 6 GB heap cap

# Force strategies
$env:ARCCLAW_NATIVE_BUILD = 'container'   # pwsh
export ARCCLAW_NATIVE_BUILD=container     # bash
mise run build:native
```

***

## 9. Dev Mode Tasks (hot reload)

All `dev*` tasks use Temurin 25 and pass paths `../lib/vec0` / `../data` (because `quarkus:dev` forks Maven inside `arcclaw-app/`). No Podman needed — sandbox strategy is `NONE`.

```bash
mise run dev           # serve on :8080 · hot reload · Dev UI at /q/dev-ui
mise run dev:chat      # interactive REPL
mise run dev:no-ui     # serve — Dev UI + console disabled
mise run dev:attach    # Telegram daemon (TELEGRAM_BOT_TOKEN required in .env)
mise run dev:kill      # kill any lingering Quarkus processes

# One-shot ask
$env:GOAL = "Analyze the ArcClaw architecture"; mise run dev:ask
$env:ROLE = "researcher"; $env:GOAL = "Review auth flow"; mise run dev:ask
```

Available roles: `coordinator` · `researcher` · `architect` · `synthesizer` · `debugger`

***

## 10. JAR Run Tasks

All jar tasks invoke `java` directly with paths `./lib/vec0` / `./data`.

```bash
mise run jar:serve                # REST daemon on :8080 (CLI profile)
mise run jar:chat                 # interactive REPL
mise run jar:attach               # Telegram daemon
mise run jar:run-dev-profile      # %dev profile — usage: mise run jar:run-dev-profile -- serve|chat|attach|ask

# One-shot ask
$env:GOAL = "List all active cron jobs"; mise run jar:ask
$env:ROLE = "researcher"; $env:GOAL = "Research Quarkus ScopedValues"; mise run jar:ask
```

***

## 11. Native Binary Tasks

**Prerequisite:** `mise run build:native`

```bash
mise run native:serve    # REST daemon on :8080
mise run native:chat     # interactive REPL
mise run native:attach   # Telegram daemon

$env:GOAL = "summarize today's logs"; mise run native:ask
```

### Assemble a dist/ folder (ship-ready)

```bash
mise run dist
```

Produces:

```
dist/
  arcclaw-app-1.0.0-SNAPSHOT-runner      (Linux/macOS)
  arcclaw-app-1.0.0-SNAPSHOT-runner.exe  (Windows)
  run.sh                                  ← Linux/macOS launcher (first run auto-configures .env)
  run.ps1                                 ← Windows launcher    (first run auto-configures .env)
  .env.example
  lib/
    vec0.dll / vec0.so / vec0.dylib
```

### Using the launchers

Both `run.sh` and `run.ps1` handle first-run `.env` setup automatically — generating secret keys, prompting for API keys, and configuring Telegram if needed.

```bash
# Linux/macOS
cd dist && ./run.sh serve

# Windows
cd dist; ./run.ps1 serve
```

### sqlite-vec (vec0) — auto-install

ArcClaw **auto-installs** the correct `vec0` native extension for your platform (Linux, macOS, Windows) from internal resources on first boot. The binary is extracted to `./lib/vec0` (configurable via `ARCCLAW_SQLITE_VEC_PATH`).

Manual download is **no longer required**, but you can override the auto-installed binary by placing your own build in `lib/`.

| Platform | Runtime Path | Source Archive (v0.1.6) |
|---|---|---|
| Linux / WSL2 | `lib/vec0.so` | `sqlite-vec-0.1.6-loadable-linux-x86_64.tar.gz` |
| macOS (M1/M2) | `lib/vec0.dylib` | `sqlite-vec-0.1.6-loadable-macos-aarch64.tar.gz` |
| Windows | `lib/vec0.dll` | `sqlite-vec-0.1.6-loadable-windows-x86_64.tar.gz` |

***

## 12. Creating Release Archives (`dist:pack`)

```bash
# 1. Build on WSL2/Linux
mise run build:native && mise run dist

# 2. Build on Windows (separate terminal)
mise run build:native; mise run dist

# 3. Pack all archives (run from either platform)
mise run dist:pack
```

Output:

```
dist/release/
  arcclaw-1.0.0-linux-x64.tar.gz
  arcclaw-1.0.0-macos-aarch64.tar.gz
  arcclaw-1.0.0-windows-x64.zip
```

***

## 13. Docker Compose Tasks

**Prerequisite:** `mise run build:native`

```bash
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

***

## 14. Podman Setup Tasks

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
# Prints DOCKER_HOST export line — add to ~/.bashrc or ~/.zshrc
```

### Windows

Use [Podman Desktop](https://podman-desktop.io/) or Docker Desktop.
`podman:init/start/stop/socket` tasks are no-ops on Windows and say so.

***

## 15. Database Helpers

```bash
mise run db:inspect     # sqlite3 interactive CLI on arcclaw-meta.db
mise run db:fts-check   # verify FTS5
mise run db:vec-check   # verify lib/vec0 extension for your platform
```

***

## 16. Environment Variables Reference

| Variable | Source | Notes |
|---|---|---|
| All `.env` vars | `_.file = ".env"` | Auto-loaded by mise |
| `ARCCLAW_LLM_API_KEY` | `.env` | Required, starts with `gsk_` |
| `ARCCLAW_VAULT_PEPPER` | `.env` | ≥32 bytes, never change after first boot |
| `ARCCLAW_AUDIT_HMAC_KEY` | `.env` | ≥32 bytes, never change after first boot |
| `ARCCLAW_API_KEY_INTERNAL` | `.env` | Bearer token for REST API |
| `ARCCLAW_API_KEY_CI` | `.env` | CI/CD read-only key |
| `ARCCLAW_EMBED_API_KEY` | `.env` | Jina AI embedding key (starts with `jina_`) |
| `TELEGRAM_BOT_TOKEN` | `.env` | Only for `attach` mode |
| `GOAL` | shell env | Goal string for `jar:ask`, `dev:ask`, `native:ask` |
| `ROLE` | shell env | Optional role override for `ask` tasks |
| `ARCCLAW_NATIVE_BUILD` | shell env / `.env` | `local` or `container` — overrides `build:native` auto-detection |

> **`ARCCLAW_VEC_EXT`** and **`ARCCLAW_DATA_DIR`** are not set as global env vars.
> `dev*` tasks pass `../lib/vec0` and `../data`; all other tasks pass `./lib/vec0` and `./data`.

> **`JDK_JAVA_OPTIONS`** is intentionally not set. Dev tasks use `-Djvm.args=...`; build tasks set `MAVEN_OPTS` inline.

***

## 17. Troubleshooting

### `mise ERROR Config files … are not trusted`

```bash
mise trust --all
```

### `mise.toml` keeps getting a `[tools]` block written to it

`mise use java@...` without `--path` writes to the nearest `mise.toml`. All `mise use` calls in tasks already specify `--path mise.toml` — if you see this, check that no task was edited to remove the flag.

### `Error: usage CLI not found` (tab completion broken)

```bash
mise install usage
# or: mise use -g usage
```

`usage` is in `[tools]` so `mise install` handles it automatically going forward.

### `java: command not found`

```bash
mise install   # run inside project root
```

### `Permission denied` on mvnw (Linux/macOS)

```bash
mise run setup   # re-runs chmod +x
```

### `./mvnw: cannot execute` (WSL2 CRLF)

```bash
mise run setup:wsl-crlf
```

### `Unable to parse command line options: Unrecognized option: --add-opens`

You have `JDK_JAVA_OPTIONS` set globally. Remove it — each task sets `MAVEN_OPTS` internally.

### `sandboxStrategy=NONE` when you expect `PODMAN`

- `mise run dev`? Dev mode always uses `NONE`. Use `mise run jar:serve` or `mise run compose:up`.
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
# or increase Podman VM RAM (macOS):
mise run podman:stop
podman machine set --memory 8192
mise run podman:start
mise run build:native
```

### Podman socket missing after restart (WSL2)

```bash
mise run podman:socket
```

### Groq key rejected

```bash
mise run setup:env-check
```

### sqlite-vec not loading

1. Check logs for "Vec0 binary installed to [...]" or "Vec0 binary already present".
2. If failed, manually place the extension: `lib/vec0.so` (Linux), `lib/vec0.dylib` (macOS), `lib/vec0.dll` (Windows).
3. Verify FTS5 is available: `mise run db:fts-check`.
4. Then restart ArcClaw — vec0 is loaded at boot, not lazily.

### Native exe exits immediately (Windows)

```powershell
mise run native:serve    # or: native:chat / native:attach
# or directly:
.\arcclaw-app\target\arcclaw-app-1.0.0-SNAPSHOT-runner.exe serve
```

### `.env` `source` fails with syntax error near `<`

The `.env` file has placeholder values like `key-<random>`. The `run.sh` launcher uses a safe line-by-line loader that never shell-evaluates values — this error only occurs if you `source .env` manually. Use the launcher instead.

***

*ArcClaw V1.0.0 · Quarkus 3.27.2 · JDK 25 · mise · Temurin 25 + GraalVM Community 25.0.2*
*One config · All platforms · PowerShell everywhere*
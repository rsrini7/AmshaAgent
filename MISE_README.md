# ArcClaw — mise Task Runner Guide

> **Stack**: JDK 25 · Quarkus 3.27.2 · LangChain4J 1.x · SQLite · Podman (rootless) · Groq Cloud
>
> **This guide** covers `mise` as the unified task runner for **Windows (pwsh)**, **macOS**, **WSL2 (Ubuntu)**, and **Linux**.
> Three config files are provided — `mise.toml` is the bootstrap you run once to auto-configure your shell; after that `mise.bash.toml` or `mise.pwsh.toml` takes over permanently.

***

## Table of Contents

1. [Why mise?](#1-why-mise)
2. [Install mise](#2-install-mise)
3. [Config File Selection — No More `--env`](#3-config-file-selection--no-more---env)
4. [Trust the Config Files (First-Time Security Step)](#4-trust-the-config-files-first-time-security-step)
5. [Repository Layout](#5-repository-layout)
6. [First-Time Bootstrap](#6-first-time-bootstrap)
7. [JDK Management — Temurin vs GraalVM](#7-jdk-management--temurin-vs-graalvm)
8. [All Tasks at a Glance](#8-all-tasks-at-a-glance)
9. [Build Tasks](#9-build-tasks)
10. [Dev Mode Tasks (hot reload)](#10-dev-mode-tasks-hot-reload)
11. [JAR Run Tasks](#11-jar-run-tasks)
12. [Native Binary Tasks](#12-native-binary-tasks)
13. [Creating Release Archives (`dist:pack`)](#13-creating-release-archives-distpack)
14. [Docker Compose Tasks](#14-docker-compose-tasks)
15. [Podman Setup Tasks](#15-podman-setup-tasks)
16. [Database Helpers](#16-database-helpers)
17. [Environment Variables Reference](#17-environment-variables-reference)
18. [Troubleshooting](#18-troubleshooting)

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

- **Pins JDK 25 (Temurin) per-project** — consistent `java -version` everywhere, every platform.
- **Manages both JDKs** — Temurin 25 (dev/build) and GraalVM Community 25.0.2 (native) installed side-by-side; switch with one command.
- **Auto-loads `.env`** into every task and your shell when you `cd` into the project.
- **All tasks inline** — everything lives in the config file. No wrapper `.sh` or `.ps1` files.
- **Three config flavours** — `mise.toml` (bootstrap: run once to auto-configure your shell profile), `mise.bash.toml` (bash/zsh daily driver), `mise.pwsh.toml` (PowerShell daily driver).

***

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

```bash
mise --version    # mise 2025.x.x
mise doctor       # diagnoses common setup issues
```

***

## 3. Config File Selection — No More `--env`

ArcClaw ships three config files:

| File             | Shell                | Platform               | Role                                               |
| ---------------- | -------------------- | ---------------------- | -------------------------------------------------- |
| `mise.toml`      | sh / pwsh (see tasks) | All platforms          | **Bootstrap only** — `start` + `setup:profile:nix` + `setup:profile:win` stub |
| `mise.bash.toml` | bash / zsh           | Linux · macOS · WSL2   | Full task list — daily driver for bash/zsh users   |
| `mise.pwsh.toml` | PowerShell 7+ (pwsh) | Windows · macOS · WSL2 | Full task list — daily driver for PowerShell users |

### `mise.toml` — the bootstrap config

`mise.toml` is what mise loads when `MISE_DEFAULT_CONFIG_FILENAME` is not set (fresh clone, no profile configured). It contains **three tasks**:

| Task | Platform | What it does |
|---|---|---|
| `start` | Linux · macOS · WSL2 (sh) | Full setup guide for all platforms. `mise tasks` description readable everywhere — no `run` needed on Windows. |
| `setup:profile:nix` | Linux · macOS · WSL2 (sh) | Auto-writes `MISE_DEFAULT_CONFIG_FILENAME` to `~/.bashrc` or `~/.zshrc`. Inserts export **before** `mise activate`. Idempotent. |
| `setup:profile:win` | Windows (pwsh) | Auto-writes `MISE_DEFAULT_CONFIG_FILENAME` to `$PROFILE`. Lives in `mise.pwsh.toml`, stubbed in `mise.toml` for discoverability. Must run via `mise --env pwsh run setup:profile:win`. |

**Linux/macOS/WSL2 — quickest path:**

```bash
mise run setup:profile:nix    # auto-writes your profile, tells you what changed
source ~/.bashrc               # (or ~/.zshrc)
mise tasks                     # now shows all ArcClaw tasks
```

**Windows — quickest path:**

```powershell
mise --env pwsh run setup:profile:win   # auto-writes $PROFILE
. $PROFILE
mise tasks                              # now shows all ArcClaw tasks
```

**Not sure / want to read first:**

```
mise run start    # full guide printed (Linux/macOS/WSL2 — requires sh)
mise tasks        # any platform — read `start` description column
```

> `mise run start` and `mise run setup:profile:nix` require `sh`.
> On Windows without Git Bash or WSL2, `mise tasks` shows the complete
> `$PROFILE` lines in the `start` description — ready to copy, no run needed.

### `mise.bash.toml` / `mise.pwsh.toml` — the real configs

All ArcClaw tasks live here. mise loads the right one automatically once
`MISE_DEFAULT_CONFIG_FILENAME` is set in your shell profile.

### How mise resolves which config file to use (in order)

1. `--global` flag → global config file
2. `--path` flag → explicit path
3. `--env <n>` flag → `mise.<n>.toml` in the project root
4. **`MISE_DEFAULT_CONFIG_FILENAME`** env var → that filename ✅ recommended
5. `MISE_OVERRIDE_CONFIG_FILENAMES` env var → first match from the list
6. **Default fallback → `mise.toml`** ← what new users land on before any setup

### One-time profile setup — do this once, never type `--env` again

> **Important:** `MISE_DEFAULT_CONFIG_FILENAME` must come **before** `mise activate`
> in your profile. Order matters — mise reads it during activation.

**bash → `~/.bashrc`**

```bash
export MISE_DEFAULT_CONFIG_FILENAME=mise.bash.toml   # ← FIRST
eval "$(~/.local/bin/mise activate bash)"             # ← SECOND
```

**zsh → `~/.zshrc`**

```zsh
export MISE_DEFAULT_CONFIG_FILENAME=mise.bash.toml   # ← FIRST
eval "$(~/.local/bin/mise activate zsh)"              # ← SECOND
```

Reload: `source ~/.bashrc` or `source ~/.zshrc`

**PowerShell → `$PROFILE`**

```powershell
$env:MISE_DEFAULT_CONFIG_FILENAME = 'mise.pwsh.toml'           # ← FIRST
(&mise activate pwsh) | Out-String | Invoke-Expression          # ← SECOND
```

Reload: `. $PROFILE`

After reloading, `mise tasks` shows all ArcClaw tasks — no `--env` needed again.

### One-off override (no profile change, run a task right now)

```bash
mise --env bash tasks            # list all tasks  (Linux / macOS / WSL2)
mise --env pwsh tasks            # list all tasks  (Windows / PowerShell)

mise --env bash run dev          # run any bash task directly
mise --env pwsh run build:native # run any pwsh task directly
```

***

## 4. Trust the Config Files (First-Time Security Step)

After cloning, mise shows this on first use:

```
mise ERROR Config files in ~/Ws/ArcClaw/mise.bash.toml are not trusted.
```

This is **expected** — supply-chain protection. Trust all files at once:

```bash
cd ArcClaw
mise trust --all
```

Or trust individually:

```bash
mise trust mise.toml
mise trust mise.bash.toml
mise trust mise.pwsh.toml
```

Then reload your shell or open a new terminal:

```bash
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

***

## 5. Repository Layout

```
ArcClaw/
├── mise.toml            ← bootstrap: start + setup:profile:nix + setup:profile:win stub
├── mise.bash.toml       ← all tasks — bash/sh variant  (Linux · macOS · WSL2)
├── mise.pwsh.toml       ← all tasks — pwsh variant     (Windows · macOS · WSL2)
├── mvnw / mvnw.cmd
├── lib/
│   ├── vec0.dll         ← Windows  (download once — gitignored)
│   ├── vec0.so          ← Linux    (download once — gitignored)
│   └── vec0.dylib       ← macOS    (download once — gitignored)
├── dist/                ← produced by: mise run dist  (gitignored)
└── arcclaw-app/
    └── target/
        ├── quarkus-app/quarkus-run.jar           ← produced by: mise run build-app
        ├── arcclaw-app-1.0.0-SNAPSHOT-runner      ← Linux/macOS native
        └── arcclaw-app-1.0.0-SNAPSHOT-runner.exe  ← Windows native
```

> `scripts/windows/` and `mise-tasks/` are **no longer needed** — safe to delete.

> **JDK default**: `temurin-25` is active for everything except `build:native` and
> `build:native-lowmem`, which auto-switch to `graalvm-community-25.0.2` and back.

> **vec/data paths**: `dev*` tasks pass `../lib/vec0` and `../data` because `quarkus:dev`
> forks Maven inside `arcclaw-app/`. All other tasks use `./lib/vec0` and `./data`.
> These are never set as global env vars — each task passes them explicitly.

***

## 6. First-Time Bootstrap

```bash
# 1. Clone and enter
git clone https://github.com/rsrini7/ArcClaw.git
cd ArcClaw

# 2. Trust config files
mise trust --all

# 3. Configure your shell profile — pick your path:

#    Linux/macOS/WSL2 — AUTO:
mise run setup:profile:nix
source ~/.bashrc              # or ~/.zshrc

#    Windows — AUTO:
#    mise --env pwsh run setup:profile:win
#    . $PROFILE

#    Any platform — need guidance first:
#    mise run start            # full guide (Linux/macOS/WSL2)
#    mise tasks                # any platform — read `start` description

#    Any platform — ONE-OFF (no profile change, skip step 3):
#    mise --env bash run setup    # Linux/macOS/WSL2
#    mise --env pwsh run setup    # Windows

# 4. Verify the config is loaded (mise tasks should show all tasks now)
mise tasks

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

### What `mise run setup` does

1. `.env.example` → `.env` (skips if exists)
2. `chmod +x mvnw` (Linux/macOS/WSL2 only — not needed on Windows)
3. `mise install java@temurin-25`
4. `mise install java@graalvm-community-25.0.2`

***

## 7. JDK Management — Temurin vs GraalVM

ArcClaw uses **two JDKs** managed entirely by mise — no manual downloads, no `JAVA_HOME` editing:

| JDK               | Version                    | Used for                                   |
| ----------------- | -------------------------- | ------------------------------------------ |
| Temurin (default) | `temurin-25`               | All dev, build, jar, native-run tasks      |
| GraalVM Community | `graalvm-community-25.0.2` | `build:native`, `build:native-lowmem` only |

### Day-to-day commands

```bash
mise run jdk:status       # see active JDK + all installed

# Switch manually
mise run jdk:graalvm
mise run build:native
mise run jdk:temurin      # switch back

# Let build:native auto-manage (recommended)
mise run build:native
# → auto-detects Podman or local → switches to GraalVM → builds → switches back to Temurin (even on failure)

# Force local GraalVM explicitly
mise run build:native-local
```

### Install both JDKs anytime (idempotent)

```bash
mise run jdk:install
```

### How `build:native` handles the switch

```
mise run build:native
  │
  ├─ mise use java@graalvm-community-25.0.2   (switches active JDK)
  ├─ ./mvnw clean package -Pnative ...        (GraalVM active)
  └─ mise use java@temurin-25                 (restores — runs even on build failure)
```

***

## 8. All Tasks at a Glance

```bash
mise run help    # or: mise tasks
```

| Task                              | What it does                                                                           |
| --------------------------------- | -------------------------------------------------------------------------------------- |
| **`mise run setup:profile:nix`**  | Linux/macOS/WSL2: auto-write `MISE_DEFAULT_CONFIG_FILENAME` to `~/.bashrc` or `~/.zshrc` |
| `mise --env pwsh run setup:profile:win` | Windows: auto-write `MISE_DEFAULT_CONFIG_FILENAME` to `$PROFILE`           |
| `mise run start`                  | Full setup guide for all platforms — Windows: readable via `mise tasks` description     |
| **`mise run setup`**              | `.env`, chmod +x mvnw (Linux), install temurin-25 + graalvm-community-25.0.2           |
| `mise run setup:wsl-crlf`         | Fix CRLF in mvnw (WSL2 after Windows clone)                                            |
| `mise run setup:env-check`        | Validate `.env` for missing/placeholder values                                         |
| **`mise run jdk:status`**         | Show active JDK + all installed JDKs                                                   |
| `mise run jdk:graalvm`            | Switch active JDK to GraalVM Community 25.0.2                                          |
| `mise run jdk:temurin`            | Switch active JDK back to Temurin 25                                                   |
| `mise run jdk:install`            | Install both JDKs (idempotent)                                                         |
| **`mise run build-app`**          | JVM build, skip tests — fast daily driver (Temurin)                                    |
| `mise run build:full`             | Clean install all modules and skipTests                                                |
| `mise run build:test`             | Build and run all tests                                                                |
| `mise run build:native`           | Native binary — **auto-detects** Podman (default) or local GraalVM, auto-switches JDK |
| `mise run build:native-local`     | Native binary — always local GraalVM, no container (fast, any OS)                      |
| `mise run build:native-lowmem`    | Native binary — local GraalVM, 6 GB heap cap (RAM-constrained machines)                |
| `mise run clean`                  | `mvnw clean`                                                                           |
| **`mise run dev`**                | Hot-reload serve on :8080 (strategy: NONE)                                             |
| `mise run dev:chat`               | Hot-reload — interactive REPL                                                          |
| `mise run dev:attach`             | Hot-reload — Telegram daemon                                                           |
| `mise run dev:ask`                | Hot-reload — one-shot task (`GOAL="..."`)                                              |
| `mise run dev:no-ui`              | Hot-reload — Dev UI + console disabled                                                 |
| `mise run dev:kill`               | Kill any lingering Quarkus dev / quarkus-run.jar background processes                  |
| **`mise run jar:serve`**          | Packaged jar — REST daemon on :8080                                                    |
| `mise run jar:chat`               | Packaged jar — interactive REPL                                                        |
| `mise run jar:attach`             | Packaged jar — Telegram daemon                                                         |
| `mise run jar:ask`                | Packaged jar — one-shot task (`GOAL="..."`)                                            |
| `mise run jar:run-dev-profile`    | Packaged jar with `%dev` profile (no Podman)                                           |
| **`mise run native:serve`**       | Native binary — REST daemon on :8080                                                   |
| `mise run native:chat`            | Native binary — interactive REPL                                                       |
| `mise run native:attach`          | Native binary — Telegram daemon                                                        |
| `mise run native:ask`             | Native binary — one-shot task (`GOAL="..."`)                                           |
| **`mise run dist`**               | Assemble `dist/` folder: binary + vec0 + .env.example                                  |
| `mise run dist:pack`              | Create OS-specific release archives for GitHub Releases                                |
| `mise run compose:up`             | Full observability stack up                                                            |
| `mise run compose:down`           | Stop stack, keep volumes                                                               |
| `mise run compose:down-clean`     | Stop stack + delete volumes (prompts YES)                                              |
| `mise run compose:logs`           | Follow arcclaw container logs                                                          |
| `mise run compose:restart`        | Restart only arcclaw service                                                           |
| `mise run sandbox:check`          | Verify tool-runner image exists                                                        |
| `mise run podman:init`            | macOS: init Podman VM (6 GB RAM, 4 CPUs)                                               |
| `mise run podman:start`           | macOS: start Podman VM                                                                 |
| `mise run podman:stop`            | macOS: stop Podman VM                                                                  |
| `mise run podman:socket`          | WSL2/Linux: enable Podman user socket                                                  |
| `mise run podman:status`          | Podman + image status                                                                  |
| `mise run db:inspect`             | Open `arcclaw-meta.db` in sqlite3                                                      |
| `mise run db:fts-check`           | Verify FTS5 available                                                                  |
| `mise run db:vec-check`           | Check `lib/vec0` extension for current platform                                        |
| `mise run info`                   | Print Java, Podman, Docker versions                                                    |
| `mise run help`                   | List all tasks                                                                         |

***

## 9. Build Tasks

```bash
mise run build-app              # JVM build, skip tests → quarkus-run.jar
mise run build:full             # all modules (after branch switch or schema change), skip tests
mise run build:test             # with tests
mise run clean
```

### .env shielding — secrets stay out of the binary

Quarkus resolves config in two phases:

| Phase        | When                              | Effect                                 |
| ------------ | --------------------------------- | -------------------------------------- |
| `BUILD_TIME` | During `native-image` compilation | **Frozen permanently into the binary** |
| `RUN_TIME`   | Every startup                     | Read fresh — can always be overridden  |

If `.env` is present during a native build, any `BUILD_TIME` properties in it get baked in.
This means API keys, passwords, or vault peppers could be embedded in a binary you share or deploy.

**Temp fix (automatic):** All three `build:native*` tasks now auto-shield `.env` before building
and restore it in the `finally`/`trap` block — even if the build fails:

```
build:native
  ├─ .env         → .env.build-backup   (if .env exists)
  ├─ build.env    → .env                (if build.env exists — long-term approach)
  ├─ mvnw clean package -Pnative ...
  └─ .env.build-backup → .env           (always restored)
```

**Long-term fix — `build.env`:** Create a `build.env` file with only the non-secret,
build-time config your app needs for compilation (datasource driver class, extension flags, etc.).
No API keys. No vault secrets. The build tasks check for `build.env` and use it automatically:

```properties
# build.env — committed to git, no secrets
# Only build-time Quarkus config that affects native-image compilation.
# Runtime values (API keys, paths, tokens) must NOT go here.
quarkus.datasource.db-kind=sqlite
quarkus.hibernate-orm.database.generation=none
```

Add to `.gitignore` (`.env` should already be there):

```
.env
.env.build-backup
```

### Native build strategy

`build:native` resolves which strategy to use in this order:

1. **Windows** → always local GraalVM (container build produces a Linux ELF — unusable on Windows)
2. **`ARCCLAW_NATIVE_BUILD=local`** → force local GraalVM, no container
3. **`ARCCLAW_NATIVE_BUILD=container`** → force Podman, error clearly if not found
4. **Podman detected and running** → container build (default on Linux/macOS — more portable binary)
5. **Podman not found/not running** → local GraalVM + install hint printed

```bash
# Recommended: let build:native auto-detect
mise run podman:status        # confirm Podman is running (Linux/macOS)
mise run build:native         # auto-detects → container if Podman up, local otherwise

# Force strategies explicitly
mise run build:native-local   # always local GraalVM — fastest, works offline, any OS
mise run build:native-lowmem  # local GraalVM, 6 GB heap cap (RAM-constrained machines)

# Force container via env var (useful for CI or shell profile)
export ARCCLAW_NATIVE_BUILD=container   # bash/zsh
$env:ARCCLAW_NATIVE_BUILD = 'container' # pwsh
mise run build:native

# Suppress the Podman-not-found warning on a local-only machine
export ARCCLAW_NATIVE_BUILD=local       # bash/zsh — add to ~/.bashrc or ~/.zshrc
$env:ARCCLAW_NATIVE_BUILD = 'local'     # pwsh — add to $PROFILE
```

Increase Podman VM RAM if container build OOMs (macOS):

```bash
mise run podman:stop
podman machine set --memory 8192
mise run podman:start
mise run build:native
```

***

## 10. Dev Mode Tasks (hot reload)

All `dev*` tasks use Temurin 25, pass JVM flags via `-Djvm.args`, and set paths to
`../lib/vec0` / `../data` (because `quarkus:dev` forks Maven inside `arcclaw-app/`).
No Podman needed — sandbox strategy is `NONE` in dev mode.

```bash
mise run dev           # serve on :8080 · hot reload · Dev UI at /q/dev-ui
mise run dev:chat      # interactive REPL
mise run dev:no-ui     # serve — Dev UI + console disabled (Quarkus NPE workaround)
mise run dev:attach    # Telegram daemon (TELEGRAM_BOT_TOKEN required in .env)
mise run dev:kill      # kill any lingering Quarkus background processes

# One-shot ask
# bash/zsh:
GOAL="Analyze the ArcClaw architecture" mise run dev:ask
ROLE=researcher GOAL="Review auth flow" mise run dev:ask
# pwsh:
$env:GOAL = "Analyze the ArcClaw architecture"; mise run dev:ask
$env:ROLE = "researcher"; $env:GOAL = "Review auth flow"; mise run dev:ask
```

Available roles: `coordinator` · `researcher` · `architect` · `synthesizer` · `debugger`

***

## 11. JAR Run Tasks

All jar tasks invoke `java` directly with paths set to `./lib/vec0` / `./data`.

```bash
mise run jar:serve                # REST daemon on :8080 (CLI profile)
mise run jar:chat                 # interactive REPL
mise run jar:attach               # Telegram daemon (TELEGRAM_BOT_TOKEN required)
mise run jar:run-dev-profile      # %dev profile, no Podman — usage: mise run jar:run-dev-profile -- serve|chat|attach|ask

# One-shot ask
# bash/zsh:
GOAL="List all active cron jobs" mise run jar:ask
ROLE=researcher GOAL="Research Quarkus ScopedValues" mise run jar:ask
# pwsh:
$env:GOAL = "List all active cron jobs"; mise run jar:ask
$env:ROLE = "researcher"; $env:GOAL = "Research Quarkus ScopedValues"; mise run jar:ask
```

***

## 12. Native Binary Tasks

**Prerequisite:** `mise run build:native`

Native tasks work on **all platforms** — `.exe` on Windows, ELF on Linux/macOS.
Paths are set to `./lib/vec0` / `./data`.

```bash
mise run native:serve    # REST daemon on :8080
mise run native:chat     # interactive REPL
mise run native:attach   # Telegram daemon

# One-shot ask
# bash/zsh:
GOAL="summarize today's logs" mise run native:ask
# pwsh:
$env:GOAL = "summarize today's logs"; mise run native:ask
```

### Assemble a dist/ folder (ship-ready)

```bash
mise run dist
```

This produces:

```
dist/
  arcclaw-app-1.0.0-SNAPSHOT-runner.exe   (or -runner on Linux/macOS)
  run.sh                                   ← Linux/macOS launcher  ✅ use this
  run.ps1                                  ← Windows/pwsh launcher ✅ use this
  .env.example                             → copy to .env, fill in keys
  lib/
    vec0.dll / vec0.so / vec0.dylib        (copied from lib/ if present)
```

### Using the launchers (recommended)

The `run.sh` / `run.ps1` scripts handle everything for the user:

- Sets `ARCCLAW_RAG_VEC_EXTENSION_PATH` and `ARCCLAW_DATA_DIR` automatically
- Loads `.env` if present in the same directory
- Sets `LD_PRELOAD` on Linux for sqlite-vec
- Shows a branded help menu when called with no arguments

```bash
# Linux/macOS — just copy dist/ anywhere and run
cd dist
cp .env.example .env    # fill in API keys
./run.sh                # shows help menu
./run.sh serve          # HTTP + MCP server on :8080
./run.sh chat           # interactive REPL
./run.sh ask "summarize logs"
./run.sh attach         # Telegram daemon
```

```powershell
# Windows
cd dist
Copy-Item .env.example .env    # fill in API keys
.\run.ps1                      # shows help menu
.\run.ps1 serve                # HTTP + MCP server on :8080
.\run.ps1 chat                 # interactive REPL
.\run.ps1 ask "summarize logs"
.\run.ps1 attach               # Telegram daemon
```

> **Note:** Quarkus native binaries have no JVM — `-Dkey=value` system properties don't work.
> The launchers set the correct environment variables instead (MicroProfile Config convention:
> `arcclaw.data-dir` → `ARCCLAW_DATA_DIR`).
> Add any extra config to `dist/.env` — Quarkus native loads it automatically at startup.

### Running the binary directly (advanced)

If you prefer not to use the launchers, set env vars yourself:

```bash
# Linux/macOS
export ARCCLAW_RAG_VEC_EXTENSION_PATH=./lib/vec0
export ARCCLAW_DATA_DIR=./data
./arcclaw-app-1.0.0-SNAPSHOT-runner serve
```

```powershell
# Windows
$env:ARCCLAW_RAG_VEC_EXTENSION_PATH = "./lib/vec0"
$env:ARCCLAW_DATA_DIR = "./data"
.\arcclaw-app-1.0.0-SNAPSHOT-runner.exe serve
```

### sqlite-vec (vec0) — one-time download

Vector search requires the `vec0` extension in `lib/`. Download once — `mise run dist` picks it up automatically:

```bash
# Linux
curl -L https://github.com/asg017/sqlite-vec/releases/download/v0.1.6/sqlite-vec-v0.1.6-loadable-linux-x86_64.zip \
    -o vec0.zip && unzip vec0.zip -d lib/ && rm vec0.zip

# macOS
curl -L https://github.com/asg017/sqlite-vec/releases/download/v0.1.6/sqlite-vec-v0.1.6-loadable-macos-aarch64.zip \
    -o vec0.zip && unzip vec0.zip -d lib/ && rm vec0.zip
```

```powershell
# Windows
Invoke-WebRequest `
    "https://github.com/asg017/sqlite-vec/releases/download/v0.1.6/sqlite-vec-v0.1.6-loadable-windows-x86_64.zip" `
    -OutFile vec0.zip
Expand-Archive vec0.zip -DestinationPath lib/
Remove-Item vec0.zip
# Rename if needed: Rename-Item lib/vec0-v0.1.6.dll lib/vec0.dll
```

Add to `.gitignore`:

```
lib/vec0.dll
lib/vec0.so
lib/vec0.dylib
dist/
```

> Without vec0, ArcClaw falls back to FTS5 full-text search — everything still works, vector similarity search is degraded.

> **Linux note:** `native:*` tasks set `LD_PRELOAD=/lib/x86_64-linux-gnu/libm.so.6` automatically — required for vec0.so symbol resolution.

***

## 13. Creating Release Archives (`dist:pack`)

After building native binaries on all platforms, `dist:pack` assembles
platform-specific archives ready for GitHub Releases.

### What each archive contains

| Platform | Archive                              | Binary        | Launcher  | Vec0             |
| -------- | ------------------------------------ | ------------- | --------- | ---------------- |
| Windows  | `arcclaw-1.0.0-windows-x64.zip`      | `arcclaw.exe` | `run.ps1` | `lib/vec0.dll`   |
| Linux    | `arcclaw-1.0.0-linux-x64.tar.gz`     | `arcclaw`     | `run.sh`  | `lib/vec0.so`    |
| macOS    | `arcclaw-1.0.0-macos-aarch64.tar.gz` | `arcclaw`     | `run.sh`  | `lib/vec0.dylib` |

All archives also include `.env.example`.

### Prerequisites

`dist/` must already be assembled with binaries from all target platforms.
Run `mise run dist` after each platform build before packing.

```bash
# 1. Build on WSL2/Linux
mise run build:native
mise run dist

# 2. Build on Windows (separate terminal)
mise run build:native
mise run dist

# 3. Pack all archives (run from either platform)
mise run dist:pack
```

Archives are created only for platforms whose binaries exist in `dist/` —
missing platforms are skipped with a note, not an error.

### Output

```
dist/release/
  arcclaw-1.0.0-linux-x64.tar.gz
  arcclaw-1.0.0-macos-aarch64.tar.gz
  arcclaw-1.0.0-windows-x64.zip
```

> **macOS note:** macOS and Linux share the same binary name (`arcclaw`). The
> task distinguishes them by the presence of `dist/lib/vec0.dylib` — if that
> file exists, a macOS archive is also produced from the same binary. A true
> macOS-native binary requires building on macOS.

> **Windows note:** `tar` is required to produce Linux and macOS `.tar.gz`
> archives from Windows. It is available in WSL2, Git Bash, and Windows 10+
> (built-in). If `tar` is not found, those archives are skipped with a warning
> and can be built from WSL2 instead.

### Upload to GitHub Release

```bash
gh release create v1.0.0 dist/release/* \
    --title "ArcClaw v1.0.0" \
    --notes "Release notes here"
```

***

## 14. Docker Compose Tasks

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

## 15. Podman Setup Tasks

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
The `podman:init/start/stop/socket` tasks are no-ops on Windows and will say so.

***

## 16. Database Helpers

```bash
mise run db:inspect     # sqlite3 interactive CLI on arcclaw-meta.db
mise run db:fts-check   # verify FTS5
mise run db:vec-check   # verify lib/vec0 extension for your platform
```

***

## 17. Environment Variables Reference

| Variable                       | Source             | Notes                                                                   |
| ------------------------------ | ------------------ | ----------------------------------------------------------------------- |
| All `.env` vars                | `_.file = ".env"`  | Auto-loaded by mise                                                     |
| `GROQ_API_KEY`                 | `.env`             | Required, starts with `gsk_`                                            |
| `ARCCLAW_VAULT_PEPPER`         | `.env`             | ≥32 bytes, never change after first boot                                |
| `ARCCLAW_AUDIT_HMAC_KEY`       | `.env`             | ≥32 bytes, never change after first boot                                |
| `ARCCLAW_API_KEY_INTERNAL`     | `.env`             | Bearer token for REST API                                               |
| `TELEGRAM_BOT_TOKEN`           | `.env`             | Only for `attach` mode                                                  |
| `GOAL`                         | shell env          | Goal string for `jar:ask`, `dev:ask`, `native:ask`                      |
| `ROLE`                         | shell env          | Optional role override for `ask` tasks                                  |
| `ARCCLAW_NATIVE_BUILD`         | shell env / `.env` | `local` or `container` — overrides `build:native` auto-detection        |
| `MISE_DEFAULT_CONFIG_FILENAME` | shell profile      | Set to `mise.bash.toml` or `mise.pwsh.toml` — must be before `mise activate` |

> **`ARCCLAW_VEC_EXT`** **and** **`ARCCLAW_DATA_DIR`** **are not set as global env vars.**
> `dev*` tasks pass `../lib/vec0` and `../data`; all other tasks pass `./lib/vec0` and `./data`.
> Each task sets these explicitly via `-Darcclaw.rag.vec-extension-path` and `-Darcclaw.data-dir`.

> **`JDK_JAVA_OPTIONS`** **is intentionally not set.** It is picked up by `mvnw` itself and
> causes Maven to fail parsing `--add-opens`. Dev tasks use `-Djvm.args=...`;
> build tasks set `MAVEN_OPTS` inline.

***

## 18. Troubleshooting

### `mise ERROR Config files … are not trusted`

```bash
mise trust --all
```

### Tasks use the wrong config / `mise tasks` shows only `start`, `setup:profile:nix`, `setup:profile:win`

This means `MISE_DEFAULT_CONFIG_FILENAME` is not set — you're on the `mise.toml` bootstrap.

```bash
# Linux/macOS/WSL2 — quickest fix:
mise run setup:profile:nix
source ~/.bashrc    # or ~/.zshrc

# Windows — quickest fix:
mise --env pwsh run setup:profile:win
# then: . $PROFILE

# Need guidance first:
mise run start      # full guide (Linux/macOS/WSL2 — requires sh)
mise tasks          # any platform — read `start` description

# Check which file mise resolved
mise doctor

# Confirm the env var is active
echo $MISE_DEFAULT_CONFIG_FILENAME        # bash/zsh
$env:MISE_DEFAULT_CONFIG_FILENAME         # pwsh

# One-off — run tasks without touching your profile:
mise --env bash tasks
mise --env pwsh tasks
```

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

You have `JDK_JAVA_OPTIONS` set globally. Remove it — each task sets `MAVEN_OPTS` or `-Djvm.args` internally.

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
# or increase Podman VM RAM (Linux/macOS):
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
# Boot logs should show: Seeding secret: GROQ_API_KEY
```

### sqlite-vec not loading

```bash
mise run db:vec-check
# Then restart ArcClaw — vec0 is loaded at boot, not lazily
```

### Native exe exits immediately (Windows)

The binary requires a subcommand:

```powershell
mise run native:serve    # or: native:chat / native:attach
# or directly:
.\arcclaw-app\target\arcclaw-app-1.0.0-SNAPSHOT-runner.exe serve
```

***

*ArcClaw V1.0.0 · Quarkus 3.27.2 · JDK 25 · mise tasks · Temurin 25 + GraalVM Community 25.0.2*
*bash/zsh → `mise.bash.toml` · pwsh → `mise.pwsh.toml` · fresh clone → `mise run start`*
# ArcClaw — mise Task Runner Guide

> **Stack**: JDK 25 · Quarkus 3.27.2 · LangChain4J 1.x · SQLite · Podman (rootless) · Groq Cloud
>
> **This guide** covers `mise` as the unified task runner for **macOS**, **WSL2 (Ubuntu)**, and **Linux**.
> Windows (native PowerShell) → see [§13](#13-windows-powershell-users) — all `.ps1` wrappers still work as-is.

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
- **Pins JDK 25 (Temurin) per-project** — no more `java -version` surprises on a new machine.
- **Auto-loads `.env`** into every task and your shell when you `cd` into the project.
- **Auto-discovers `mise-tasks/`** — every script file in that tree becomes a `mise run` command.
- **No chmod surprises** — `mise run setup` makes every script executable automatically.

---

## 2. Install mise

```bash
# macOS / Linux / WSL2
curl https://mise.run | sh

# Wire to your shell (pick one)
echo 'eval "$(~/.local/bin/mise activate bash)"' >> ~/.bashrc   # bash
echo 'eval "$(~/.local/bin/mise activate zsh)"' >> ~/.zshrc     # zsh

source ~/.bashrc   # or source ~/.zshrc
```

**Verify:**
```bash
mise --version
# mise 2025.x.x
```

---

## 3. Trust mise.toml (First-Time Security Step)

After cloning and `cd`-ing into the project, mise will show this error on first use:

```
mise ERROR Config files in ~/Ws/ArcClawInternal/mise.toml are not trusted.
Trust them with `mise trust`.
```

This is **expected and normal** — mise won't execute config from an unknown directory until
you explicitly approve it (protection against supply-chain attacks from malicious repos).

### Fix — one command

```bash
cd ArcClawInternal    # must be inside the project directory
mise trust
source ~/.zshrc       # or open a new terminal
```

Verify trust worked:
```bash
mise tasks
# Should list all arcclaw tasks with descriptions
```

### Trust all your own repos (optional convenience)

If you work across many personal repos and don't want to run `mise trust` each time:

**Option A — global mise config** (`~/.config/mise/config.toml`):
```toml
[settings]
trust_all = true
```

**Option B — environment variable** (add to `~/.zshrc` or `~/.bashrc`):
```bash
export MISE_TRUST_ALL=1
```

> ⚠️ Use `trust_all` only on your own machines. On CI or shared environments, keep the
> default so each repo must be explicitly approved.

### Useful trust commands

```bash
mise trust              # trust mise.toml in current directory
mise trust --all        # trust all discovered config files
mise trust --untrust    # revoke trust for current directory
mise trust --status     # show trust status of current directory
```

---

## 4. Repository Layout

```
ArcClaw/
├── mise.toml                    ← tools (JDK 25), env, 3 inline proxy tasks
├── mise-tasks/                  ← all other tasks as executable scripts
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
├── arcclaw-cli.sh               ← invoked by: serve, chat, attach, ask
├── arcclaw-cli.ps1              ← Windows equivalent
├── arcclaw-dev.sh               ← invoked by: dev, dev:chat, dev:attach, dev:ask
├── arcclaw-dev.ps1              ← Windows equivalent
├── arcclaw-native.sh            ← invoked by: native:serve/chat/attach
├── arcclaw-dev-jar-serve.ps1    ← Windows dev-jar serve
└── mvn-clean-install-skiptests.ps1 ← Windows build shortcut
```

> **Design note**: `setup`, `build`, and `dev` are inline TOML tasks in `mise.toml` rather than files
> in `mise-tasks/` because the filesystem cannot have both a file and a directory with the same name
> (e.g. `mise-tasks/build` file vs `mise-tasks/build/` directory). All other tasks are file-based scripts.

---

## 5. First-Time Bootstrap

### Step 1 — Clone and enter

```bash
git clone https://github.com/rsrini7/ArcClaw.git
cd ArcClaw
```

### Step 2 — Trust the mise.toml

```bash
mise trust
```

### Step 3 — Install JDK 25 (mise handles this)

```bash
mise install
# Downloads and activates temurin-25 + maven 3.9 into this project
java -version   # openjdk 25
```

### Step 4 — Initialise .env + chmod all scripts

```bash
mise run setup
```

This single command:
1. Copies `.env.example` → `.env` (skips if already exists)
2. `chmod +x mvnw`
3. `chmod +x arcclaw-cli.sh arcclaw-dev.sh arcclaw-native.sh`
4. `find mise-tasks -type f | xargs chmod +x`

Open `.env` and fill in at minimum:

| Variable | Notes |
|---|---|
| `GROQ_API_KEY` | Starts with `gsk_` — required for LLM calls |
| `ARCCLAW_VAULT_PEPPER` | ≥32 bytes, never change after first boot |
| `ARCCLAW_AUDIT_HMAC_KEY` | ≥32 bytes, never change after first boot |
| `ARCCLAW_API_KEY_INTERNAL` | Bearer token for REST API |
| `TELEGRAM_BOT_TOKEN` | Only needed for `attach` mode |

Then validate:
```bash
mise run setup:env-check
```

### Step 5 — WSL2 only: fix CRLF (if cloned on Windows)

```bash
mise run setup:wsl-crlf
# runs dos2unix + chmod on mvnw
```

### Step 6 — Build

```bash
mise run build
# → arcclaw-app/target/quarkus-app/quarkus-run.jar
```

### Step 7 — Build Podman sandbox image (required for %prod)

```bash
# macOS: start Podman machine first (one-time)
mise run podman:init

# All platforms: build the tool-runner image
mise run sandbox:build

# Verify
mise run sandbox:check
```

### Step 8 — Run

```bash
mise run dev        # hot reload serve on :8080  (recommended for development)
mise run serve      # run packaged jar on :8080
```

---

## 6. All Tasks at a Glance

```bash
mise run help    # or: mise tasks
```

| Task | What it does |
|---|---|
| **`mise run setup`** | Copy `.env.example → .env`, chmod +x mvnw + all scripts |
| `mise run setup:wsl-crlf` | Fix CRLF line endings in mvnw (WSL2 after Windows clone) |
| `mise run setup:env-check` | Validate `.env` for empty or placeholder values |
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
| `mise run compose:down-clean` | Stop stack + delete volumes (prompts) |
| `mise run compose:logs` | Follow arcclaw container logs |
| `mise run compose:restart` | Restart only arcclaw service |
| `mise run compose:build` | Rebuild all Compose images |
| `mise run sandbox:build` | Build `arcclaw-tool-runner` image |
| `mise run sandbox:check` | Verify tool-runner image exists |
| `mise run podman:init` | macOS: init Podman VM (6 GB RAM, 4 CPUs) |
| `mise run podman:start` | macOS: start Podman VM |
| `mise run podman:stop` | macOS: stop Podman VM |
| `mise run podman:socket` | WSL2/Linux: enable Podman user socket |
| `mise run podman:status` | Print Podman + image status |
| `mise run db:inspect` | Open `arcclaw-meta.db` in sqlite3 |
| `mise run db:fts-check` | Verify FTS5 is available |
| `mise run db:vec-check` | Check `lib/vec0.so` / `.dylib` exists |
| `mise run info` | Print Java, Podman, Docker versions |
| `mise run help` | List all tasks |

---

## 7. Build Tasks

### Standard JVM build

```bash
mise run build
# ./mvnw clean package -pl arcclaw-app -am -DskipTests
# Output: arcclaw-app/target/quarkus-app/quarkus-run.jar
```

The `-am` flag is critical — it rebuilds `arcclaw-core`, `arcclaw-mcp`, and `arcclaw-rag` before
packaging `arcclaw-app`. Omitting it causes stale class issues.

### Full module clean install

```bash
mise run build:full
# Use when switching branches or after major schema changes
```

### Build with tests

```bash
mise run build:test
```

### Native binary

```bash
mise run podman:status        # ensure Podman is running
mise run build:native         # takes 5–15 min, requires ≥8 GB RAM
# Output: arcclaw-app/target/arcclaw-app-1.0.0-SNAPSHOT-runner
```

For memory-constrained machines:
```bash
mise run build:native-lowmem  # caps native-image heap at 6 GB
```

Or increase Podman machine RAM:
```bash
mise run podman:stop
podman machine set --memory 8192
mise run podman:start
mise run build:native
```

---

## 8. Dev Mode Tasks (hot reload)

All `dev` and `dev:*` tasks run `arcclaw-dev.sh` with `./mvnw quarkus:dev`, the `%dev` profile,
vec extension path, and data directory. **No Podman needed** — sandbox strategy is `NONE` in dev mode.

### Serve (default)

```bash
mise run dev
# REST API: http://localhost:8080
# Dev UI:   http://localhost:8080/q/dev-ui
# Hot reload on every save
```

### Chat REPL

```bash
mise run dev:chat
```

### Telegram daemon

```bash
# TELEGRAM_BOT_TOKEN must be set in .env
mise run dev:attach
# Watch for: INFO [TelegramChannelRoute] routeStatus=Started
```

### One-shot task

```bash
mise run dev:ask -- "Analyze the ArcClaw repository and summarise its architecture"

# With role:
GOAL="Review the auth flow" ROLE=debugger mise run dev:ask
```

Available roles: `coordinator` · `researcher` · `architect` · `synthesizer` · `debugger`

### Dev UI disabled (Quarkus console NPE workaround)

```bash
mise run dev:no-ui
```

---

## 9. JAR Run Tasks

These tasks run the **packaged jar** using `arcclaw-cli.sh` with the CLI profile.
Each task verifies the jar exists first and prints a clear `mise run build` prompt if not.

### REST daemon

```bash
mise run serve      # http://localhost:8080
```

### Interactive REPL

```bash
mise run chat
```

### Telegram daemon

```bash
mise run attach     # TELEGRAM_BOT_TOKEN must be set in .env
```

### One-shot task

```bash
mise run ask -- "List all active cron jobs"

# With role:
ROLE=researcher mise run ask -- "Research Quarkus ScopedValues patterns"
```

### Packaged jar with %dev profile (no Podman sandbox)

```bash
mise run jar:serve-dev-profile
# Smoke-test the jar without Podman running
```

---

## 10. Native Binary Tasks

These tasks invoke `arcclaw-native.sh` which pre-loads `libm.so.6` via `LD_PRELOAD`
(required for `vec0.so` symbol resolution in GraalVM native images).

**Prerequisite:** `mise run build:native`

```bash
mise run native:serve    # REST daemon on :8080
mise run native:chat     # interactive REPL
mise run native:attach   # Telegram daemon (requires TELEGRAM_BOT_TOKEN)
```

> **Linux / WSL2 only.** Native binaries cannot run on macOS or Windows hosts.

---

## 11. Docker Compose Tasks

The Compose stack runs: **ArcClaw** (native) + **Ollama** + **OTel Collector** + **Jaeger** + **Prometheus**.

**Prerequisite:** `mise run build:native`

```bash
mise run compose:up       # start full stack
# ArcClaw REST:  http://localhost:8080
# Jaeger UI:     http://localhost:16686
# Prometheus:    http://localhost:9090
# Ollama:        http://localhost:11434

mise run compose:logs     # follow arcclaw logs live
mise run compose:restart  # restart only arcclaw service
mise run compose:down     # stop, keep volumes
mise run compose:down-clean  # stop + delete all data volumes (prompts YES)
```

---

## 12. Podman Setup Tasks

### macOS

```bash
mise run podman:init    # one-time: create VM (6 GB RAM, 4 CPUs, 40 GB disk)
mise run podman:start   # day-to-day start
mise run podman:stop    # day-to-day stop
mise run podman:status  # full status check + image presence
```

### WSL2 / Ubuntu

```bash
mise run podman:socket
# Enables systemd user socket and prints the DOCKER_HOST line to add to ~/.bashrc
```

---

## 13. Database Helpers

```bash
mise run db:inspect      # open arcclaw-meta.db in sqlite3 interactive CLI
mise run db:fts-check    # verify FTS5 is available in your SQLite build
mise run db:vec-check    # check lib/vec0.so (Linux) or lib/vec0.dylib (macOS) exists
```

---

## 14. Windows PowerShell Users

All the original `.ps1` wrappers continue to work exactly as before.
For the full `mise` experience on Windows, run inside **WSL2 Ubuntu**.

| Goal | Native PowerShell command |
|---|---|
| Build (skip tests) | `.\mvn-clean-install-skiptests.ps1` |
| Dev — serve | `.\arcclaw-dev.ps1` |
| Dev — chat | `.\arcclaw-dev.ps1 chat` |
| Dev — attach | `.\arcclaw-dev.ps1 attach` |
| Dev — ask | `.\arcclaw-dev.ps1 ask "Your goal"` |
| Jar — serve | `.\arcclaw-cli.ps1 serve` |
| Jar — chat | `.\arcclaw-cli.ps1 chat` |
| Jar — attach | `.\arcclaw-cli.ps1 attach` |
| Jar — ask | `.\arcclaw-cli.ps1 ask "Your goal"` |
| Jar — ask with role | `.\arcclaw-cli.ps1 ask "goal" --role coordinator` |
| Jar dev-serve | `.\arcclaw-dev-jar-serve.ps1` |

---

## 15. Environment Variables Reference

`mise.toml` sets these in `[env]` so they are active in every task AND in your shell
when you `cd` into the project (after `mise activate` is wired to your shell).

| Variable | Set by | Notes |
|---|---|---|
| `JDK_JAVA_OPTIONS` | `mise.toml [env]` | All `--add-opens` + unsafe flags for JDK 25 |
| `ARCCLAW_VEC_EXT` | `mise.toml [env]` | `../lib/vec0` — sqlite-vec extension path |
| `ARCCLAW_DATA_DIR_DEFAULT` | `mise.toml [env]` | `../data` |
| Everything in `.env` | `_.file = ".env"` | Loaded via mise dotenv integration |
| `GROQ_API_KEY` | `.env` | Required — starts with `gsk_` |
| `ARCCLAW_VAULT_PEPPER` | `.env` | ≥32 bytes, must not change after first boot |
| `ARCCLAW_AUDIT_HMAC_KEY` | `.env` | ≥32 bytes, must not change after first boot |
| `ARCCLAW_API_KEY_INTERNAL` | `.env` | Bearer token for `X-ArcClaw-API-Key` header |
| `TELEGRAM_BOT_TOKEN` | `.env` | Only for `attach` mode |

---

## 16. Troubleshooting

### `mise ERROR Config files … are not trusted`

Normal first-time security prompt. Fix:
```bash
cd ArcClawInternal   # must be inside the project
mise trust
source ~/.zshrc      # reload shell
```
To skip this for all your own repos, add to `~/.config/mise/config.toml`:
```toml
[settings]
trust_all = true
```
Or export `MISE_TRUST_ALL=1` in your shell profile.

### `mise: command not found`
Run `source ~/.bashrc` (or `~/.zshrc`) after installing mise, or open a new terminal.

### `java: command not found` inside a mise task
Run `mise install` inside the project root — mise will download and activate `temurin-25`.

### `Permission denied: ./arcclaw-dev.sh` or `./mvnw`
```bash
mise run setup
# chmod +x is applied to all scripts by this task
```

### `./mvnw: cannot execute: required file not found` (WSL2)
```bash
mise run setup:wsl-crlf
```

### `sandboxStrategy=NONE` when you expect `PODMAN`
1. Using `mise run dev`? Dev mode always uses `NONE`. Use `mise run serve` or `mise run compose:up`.
2. Podman not running when ArcClaw started? Restart ArcClaw after `mise run podman:start`.
3. On Windows (not WSL2)? The OS check bypasses Linux namespaces — run inside WSL2.

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
```

### `lstat /arcclaw-app/target: no such file or directory` (docker compose up)
```bash
mise run build:native    # build the binary first
mise run compose:up
```

### Ollama port collision on 11434
Edit `docker-compose.yaml`: change `"11434:11434"` → `"11435:11434"`, then `mise run compose:up`.

### FTS5 not available
```bash
mise run db:fts-check
sudo apt install sqlite3   # Ubuntu
```

### sqlite-vec extension not loading
```bash
mise run db:vec-check
mise run info             # confirm ARCCLAW_VEC_EXT is set
```

### Groq API key not accepted
- Must start with `gsk_` — no whitespace.
- Run `mise run setup:env-check` to confirm it is loaded.
- Check boot logs: `Seeding secret: GROQ_API_KEY`

---

*ArcClaw V1.0.0 · Quarkus 3.27.2 · JDK 25 · mise tasks*

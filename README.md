<div align="center">
  <h1>🛡️ Amsha Agent V0.1.0</h1>
  <p><strong>A secure, stateful, and financially governed multi-agent AI kernel designed for enterprise deployment.</strong></p>

  ![JDK 25](https://img.shields.io/badge/JDK-25-red.svg?style=flat&logo=java)
  ![Quarkus](https://img.shields.io/badge/Quarkus-3.27.2-blue.svg?style=flat&logo=quarkus)
  ![LangChain4J](https://img.shields.io/badge/LangChain4j-1.x-FC4C02?style=flat&logo=langchain)
  ![Native Image Ready](https://img.shields.io/badge/Native_Image-Ready-orange.svg?style=flat&logo=graalvm)
  ![OpenTelemetry](https://img.shields.io/badge/OpenTelemetry-Tracing-blueviolet.svg?style=flat&logo=opentelemetry)
  ![Podman](https://img.shields.io/badge/Isolation-Rootless_Podman-purple.svg?style=flat&logo=podman)
  ![Tests](https://img.shields.io/badge/Tests-2975_passing-brightgreen.svg?style=flat)
  [![Built with Devbox](https://img.shields.io/badge/built_with-devbox-green.svg?style=flat)](https://www.jetify.com/devbox/)
  [![Built with mise](https://img.shields.io/badge/built_with-mise-blue.svg?style=flat)](https://jdx.dev)
</div>

---

> **A self-hosted, production-grade agentic AI platform** — ReAct agent loop, 12 specialist personas, HITL governance, Podman sandbox, hexagonal persistence (SQLite / PostgreSQL / FS). Runs as a single ~183 MB binary.

**Stack**: JDK 25 · Quarkus 3.27.2 · LangChain4J 1.x · SQLite (WAL + FTS5 + sqlite-vec) · Podman · Apache Camel · BouncyCastle

---

## Quick Start

```bash
# Prerequisites: JDK 25, Docker/Podman (optional for sandbox)
git clone https://github.com/your-org/amsha
cd amsha

# Copy and edit .env (see docs/CONFIGURATION.md for all variables)
cp .env.example .env
# At minimum: AMSHA_LLM_API_KEY=gsk_... (Groq), AMSHA_VAULT_PEPPER=<random>

# Dev mode (hot reload, no sandbox isolation)
./mvnw quarkus:dev -pl amsha-app -am "-Dquarkus.args=chat"

# Or build and run a packaged JAR
./mvnw clean package -DskipTests
java --enable-native-access=ALL-UNNAMED -jar app/target/quarkus-app/quarkus-run.jar chat
```

See [docs/QUICK_START.md](./docs/QUICK_START.md) for detailed setup options.

---

## Key Capabilities

- **ReAct agent loop** over Groq, OpenAI, OpenRouter, Ollama, Gemini, Claude, and more (10 providers)
- **12 specialist personas** with per-role tool schemas and Qute-rendered system prompts
- **Realm-based grouped agents** — personas organized into 4 Lokas (Kailasa, Brahmaloka, Vaikunta, Loka-Bahir) with group leads that consult family members
- **Agent delegation** — any role can delegate via `garuda`; capability-subset enforcement prevents privilege escalation
- **Group consultation** — `mode='group_consult'` triggers parallel family huddle with virtual threads; dynamic group formation for cross-realm tasks
- **Persona escalation** — auto-routes tasks when a persona hits its capability boundary; group-aware fallback prefers intra-realm routing
- **Parallel tool execution** — N tool calls in one LLM turn, concurrent with Virtual Threads
- **17 deterministic reflexes** — zero-token fast-path for commands, PII redaction, injection blocking, vault ops
- **Hexagonal persistence** — SQLite (default), PostgreSQL, or filesystem; swappable without core changes
- **Podman rootless sandbox** for code execution (Python, Java, Go, Node, Bash)
- **AES-256-GCM-SIV vault** with Argon2id key derivation — secrets never stored in plaintext
- **Tamper-evident HMAC audit chain** — every action logged as append-only, verifiable record
- **Two-tier RAG memory** — FTS5 keyword + sqlite-vec embeddings merged via Reciprocal Rank Fusion
- **MCP integration** — both inbound MCP server and outbound MCP client for dynamic tool loading
- **Multi-channel** — REST API, CLI REPL, Telegram bot, WebSocket web UI, one-shot `ask` mode

---

## Project Map

| Directory | Purpose |
|-----------|---------|
| `docs/` | All documentation — architecture, setup, config, security, testing, extension guides |
| `docs/assets/` | Architecture diagrams (SVG) — referenced from README + ARCHITECTURE.md |
| `app/` | Main deployable: CLI, REST, security, wiring |
| `core/` | Agent loop, tools, audit, governance, sandbox, reflexes |
| `persistence-*/` | Hexagonal persistence: API ports + SQLite / PostgreSQL / FS adapters |
| `channel-*/` | Channel adapters: Telegram, WebSocket |
| `ui/` | Lit 3.x + TypeScript web frontend |
| `mcp/`, `rag/`, `tool-playwright/`, `self-evolve/` | Optional runtime and self-evolution modules |

---

## Agent Roster

| Role | Persona | Tools | Max Iter |
|---|---|---|---|
| `coordinator` | Vishnu | github, schedule, memory, garuda | 7 |
| `coder` | Brahma | sandbox_exec, manage_checklist, MCP, garuda | 15 |
| `debugger` | Shiva | github, sandbox_exec, manage_checklist, memory, garuda | 15 |
| `researcher` | Saraswati | github, memory, brave_web_search, serper_web_search, garuda | 10 |
| `architect` | Brihaspati | github, memory, garuda | 7 |
| `synthesizer` | Dattatreya | github, schedule, memory, web_search, garuda | 10 |
| `optimizer` | Ganesha | github, sandbox_exec, garuda | 10 |
| `sentinel` | Kartikeya | github, sandbox_exec, garuda | 10 |
| `healer` | Lakshmi | github, memory, garuda | 10 |
| `protector` | Durga | github, memory, garuda | 10 |
| `purifier` | Kali | github, memory, garuda | 10 |
| `nurturer` | Parvati | github, memory, garuda | 10 |

All roles have `garuda` for delegation. Tool schemas assembled dynamically at runtime via `@ToolMetadata(defaultRoles=...)`.

---

## Module Reference

```
amsha-parent/               ← Root POM: Java 25, Quarkus 3.27.2, BouncyCastle
│
app/                  ← Main deployable: CLI, REST, scheduler, security
core/                 ← Agent loop, state machine, tools, audit, governance
common/               ← Shared utilities (LogUtil, constants)
│
channel-api/          ← Channel SPI: sealed interfaces and plugin contracts
channel-telegram/     ← Apache Camel Telegram bot + HITL inline keyboards
channel-web/          ← WebSocket channel + Lit TypeScript frontend
│
persistence-api/      ← Hexagonal port interfaces (stores, ports)
persistence-common/   ← Shared JDBI records/DTOs
persistence-sqlite/   ← SQLite adapter (WAL + FTS5 + vec0)
persistence-postgres/ ← PostgreSQL adapter
persistence-fs/       ← Filesystem adapter (Lucene BM25)
│
rag/                  ← Two-tier vector (vec0) + FTS5 RAG memory
mcp/                  ← MCP server bridge + dynamic client tool loading
onboarding/           ← 6-step setup wizard REST API
tool-playwright/      ← Playwright browser automation tool
self-evolve/          ← Trace capture and self-evolution learning loop
deployment/           ← Build-time: custom CDI scope, MCP reflection
│
ui/                   ← Lit 3.x + TypeScript web frontend
```

Set `AMSHA_UI_SKIP_ONBOARDING=true` if you want the web UI to skip the setup wizard when the backend is already configured.

---

## Architecture at a Glance

### Module Architecture
<img src="./docs/assets/amsha_module_architecture.svg" alt="Module Architecture" width="100%">

### Message Pipeline
<img src="./docs/assets/amsha_message_pipeline.svg" alt="Message Pipeline" width="100%">

### Agent Loop
<img src="./docs/assets/amsha_agent_loop.svg" alt="Agent Loop" width="100%">

### Tiered Memory & RAG
<img src="./docs/assets/amsha_tiered_memory.svg" alt="Tiered Memory & RAG" width="100%">

### Tool Governance & Sandbox
<img src="./docs/assets/amsha_tool_governance.svg" alt="Tool Governance & Sandbox" width="100%">

> Detailed flows (HITL approval, memory caching, tool governance, delegation, security layers, data flow) with Mermaid diagrams in [ARCHITECTURE.md](./docs/ARCHITECTURE.md).

---

## Documentation Index

### 🚀 Getting Started

| Doc | What it covers |
|-----|---------------|
| [QUICK_START.md](./docs/QUICK_START.md) | Dev mode, packaged JAR, native image, Docker Compose |
| [SETUP.md](./docs/SETUP.md) | Manual setup, prerequisites, OS-specific, troubleshooting |
| [CONFIGURATION.md](./docs/CONFIGURATION.md) | All environment variables reference |

### 🏗️ Architecture

| Doc | What it covers |
|-----|---------------|
| [ARCHITECTURE.md](./docs/ARCHITECTURE.md) | Full architecture: module map, agent loop, HITL, delegation, security, persistence, Mermaid diagrams |
| [Architecture_Invariants.md](./docs/Architecture_Invariants.md) | Mandatory architecture rules (40+ invariants) |

### 🛠️ Extending & Integrating

| Doc | What it covers |
|-----|---------------|
| [AMSHA_EXTENSION.md](./docs/AMSHA_EXTENSION.md) | Adding tools, personas, reflexes, channels, skills, LLM providers, guards |
| [CHANNEL_SPI.md](./docs/CHANNEL_SPI.md) | Adding a new channel (Discord, Slack, etc.) via the 6-interface SPI |

### 🔒 Security

| Doc | What it covers |
|-----|---------------|
| [SECURITY.md](./docs/SECURITY.md) | Vault, sandbox isolation, audit chain, HITL governance, 10 guard layers |

### 🧪 Testing

| Doc | What it covers |
|-----|---------------|
| [TESTING_GUIDE.md](./docs/TESTING_GUIDE.md) | REST API testing, regression checklists |
| [TELEGRAM_TESTING.md](./docs/TELEGRAM_TESTING.md) | Telegram bot end-to-end testing |
| [rest-http/](./rest-http/) | IntelliJ / VSCode REST Client files |

### ❓ Reference

| Doc | What it covers |
|-----|---------------|
| [FAQ.md](./docs/FAQ.md) | Architecture Q&A, rationale, operational guidance |
| [AGENTS.md](./AGENTS.md) | Developer guide for AI coding agents — invariants, patterns, rules |

---

> **Last updated**: Amsha Agent V0.1.0 · Quarkus 3.27.2 · JDK 25 · 17 modules · 21 tools · 17 reflexes · 11 LLM providers

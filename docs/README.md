# Amsha Agent Documentation

Welcome to the Amsha Agent architecture, operator, and developer documentation.

---

## Current implementation guides

- [ONNX Local ML guide](ONNX_LOCAL_ML_GUIDE.md) — MPNet embeddings, BGE reranking, local Whisper, installation, and configuration.
- [Local ML module README](../local-ml-onnx/README.md) — module configuration and packaged model commands.
- [Jev decision-plane ADR](adr/architecture/ADR-jev-decision-plane.md) — current Deferred status and promotion gates.
- [Jev evaluation path ADR](adr/architecture/ADR-jev-decision-plane-evaluation-path.md) — accepted default-off, internal-only, non-routing evaluation path.
- [Jev activation readiness](research/Jev-Activation-Readiness-2026-10-10.md) — current evidence and remaining gates.
- [Jev activation plan](research/Jev-Decision-Plane-Activation-Plan-2026-10-07.md) — implementation status and activation sequence.

## 1. Documentation Taxonomy & Lifecycle Model

Amsha Agent organizes documentation by audience and durability:

```
docs/
├── README.md                            ← Central documentation index & navigation
├── Architecture_Invariants.md           ← Mandatory cross-cutting architectural constraints
├── ARCHITECTURE.md                      ← System design & runtime overview
├── SECURITY.md                          ← Security model, secret boundaries & vault architecture
├── SEARCH_AND_DIAGNOSTICS_CHEATSHEET.md ← Ripgrep and Maven test diagnostics cheatsheet
├── ...                                  ← Operational, testing, and setup guides
│
├── adr/                                 ← Decisions (Accepted, Approved, Implemented, Proposed, Deferred)
├── research/                            ← Evaluated options & negative knowledge (Evaluated, Closed, Rejected)
├── proposals/                           ← Active RFCs & living implementation plans
├── roadmap/                             ← Backlog & forward-looking technical debt
├── agents/                              ← Autonomous agent conventions & triage rules
└── assets/                              ← Architecture SVGs & diagrams
```

### Lifecycle Metadata Standard

Every Architectural Decision Record (`docs/adr/`) follows a mandatory structured metadata format in its header:

```markdown
# ADR-XXX: Title
- **Status**: Accepted | Implemented | Proposed | Approved | Deferred | Rejected | Closed
- **Date**: YYYY-MM-DD
- **Decision owners**: ...
```

This standard is strictly validated by `scripts/quality/verify_docs.py`.

Research evaluations (`docs/research/`) and active proposals (`docs/proposals/`) also record their lifecycle states (e.g. `Evaluated`, `Closed`, `Archived` for research; `Proposed`, `Ratified`, `Draft` for proposals) in their headers to maintain historical context and negative knowledge.

- **`docs/adr/`**: Authoritative architectural decision records governing the system. Must not be silently contradicted.
- **`docs/research/`**: Evaluated options and negative knowledge (technologies evaluated and rejected or closed out). Prevents repeating past investigations.
- **`docs/proposals/`**: Active Request-For-Comments (RFCs) and multi-step migration plans driving ongoing refactorings.
- **`docs/roadmap/`**: Forward-looking ideas, future work, and issue backlogs.

---

## 2. Core Architecture & System Specifications

| Document | Scope & Purpose |
|---|---|
| [Architecture Invariants](Architecture_Invariants.md) | Mandatory cross-cutting architectural invariants (§1–§27). The supreme architectural authority for human and agent developers. |
| [System Architecture](ARCHITECTURE.md) | High-level system architecture, module boundaries, CDI/Vert.x virtualization, and component layout. |
| [Security Architecture](SECURITY.md) | Two-tier secret model, Vault Pepper bootstrap paradox, HMAC audit chains, and execution isolation. |
| [Configuration Reference](CONFIGURATION.md) | Comprehensive configuration property directory, precedence order, and environment variable mappings. |

---

## 3. Operator & Developer Guides

| Guide | Scope & Purpose |
|---|---|
| [Quick Start](QUICK_START.md) | Fast path to launching Amsha via CLI, Telegram, or Web. |
| [Setup Guide](SETUP.md) | System requirements, environment provisioning, and runtime bootstrapping. |
| [Manual Setup](MANUAL_SETUP.md) | Step-by-step setup without automated package managers. |
| [Mise Task Setup](MISE_SETUP.md) | Developer workflow automation with `mise` (tasks, profiles, and runtime targets). |
| [Testing Guide](TESTING_GUIDE.md) | Unit, integration, architecture, and end-to-end test execution guide. |
| [Telegram Testing](TELEGRAM_TESTING.md) | Telegram channel integration, polling, webhook, and fixture testing. |
| [Onboarding Guide](ONBOARDING.md) | Multi-agent provider onboarding, credential collection, and wizard flows. |
| [Channel SPI Guide](CHANNEL_SPI.md) | Developing and registering custom communication channel adapters. |
| [MCP Management Guide](MCP_MANAGEMENT.md) | Model Context Protocol client/server integration, governance, and tools. |
| [SkillsJar Admission Guide](SKILLSJAR_ADMISSION.md) | Dynamic SkillsJar loading, admission gates, and security validation. |
| [ONNX Local ML Guide](ONNX_LOCAL_ML_GUIDE.md) | In-process ONNX embeddings, model bundles, tokenizer truncation, and fallback. |
| [Dependency Upgrade Policy](DEPENDENCY_UPGRADE_POLICY.md) | Upstream dependency review gates, platform alignment, and CVE response. |
| [Frequently Asked Questions (FAQ)](FAQ.md) | Common developer and operational questions with answers. |

---

## 4. Architecture Decision Records (`docs/adr/`)

Decisions that define the system structure and operational boundaries:

### Architecture Decisions (`docs/adr/architecture/`)
- [ADR-extract-core-subsystems](adr/architecture/ADR-extract-core-subsystems.md) — Core subsystem extraction and modularization (Status: Implemented).
- [ADR-Quarkus-Upgrade-Strategy](adr/architecture/ADR-Quarkus-Upgrade-Strategy.md) — Quarkus platform upgrade strategy and verification matrix (Status: Accepted).
- [ADR-approval-lease-for-parallel-batches](adr/architecture/ADR-approval-lease-for-parallel-batches.md) — Approval lease semantics for concurrent batch operations (Status: Accepted).
- [ADR-LangChain4j-Capability-Adoption-and-Amsha-Boundaries](adr/architecture/ADR-LangChain4j-Capability-Adoption-and-Amsha-Boundaries.md) — Capability adoption boundaries for LangChain4j (Status: Accepted).
- [ADR-jev-decision-plane](adr/architecture/ADR-jev-decision-plane.md) — Typed decision seam for Jev-compatible judgments and Amsha-owned policy (Status: Deferred).
- [ADR-onboarding-route-assignments](adr/architecture/ADR-onboarding-route-assignments.md) — Onboarding route assignments and model profile mapping (Status: Accepted).
- [ADR-schedule-task-hitl-under-supervised](adr/architecture/ADR-schedule-task-hitl-under-supervised.md) — Scheduled task HITL governance under supervised autonomy (Status: Accepted).

### Provider Decisions (`docs/adr/providers/`)
- [ADR-LLM-Provider-Extraction-and-Modularization](adr/providers/ADR-LLM-Provider-Extraction-and-Modularization.md) — Master provider modularization ADR (Status: Implemented).
- [ADR-o2-provider-onboarding](adr/providers/ADR-o2-provider-onboarding.md) — Multi-agent provider onboarding specification (Status: Accepted).
- **21 Concrete Provider Specifications**:
  - [Antigravity](adr/providers/ADR-Antigravity-Provider.md)
  - [Cerebras](adr/providers/ADR-Cerebras-Provider.md)
  - [Claude Code](adr/providers/ADR-Claude-Code-Provider.md)
  - [Cloudflare](adr/providers/ADR-Cloudflare-Provider.md)
  - [Codex](adr/providers/ADR-Codex-Provider.md)
  - [Cohere](adr/providers/ADR-Cohere-Provider.md)
  - [Foundry Local](adr/providers/ADR-Foundry-Local-Provider.md)
  - [Gemini](adr/providers/ADR-Gemini-Provider.md)
  - [Generic OpenAI](adr/providers/ADR-Generic-OpenAI-Provider.md)
  - [Groq](adr/providers/ADR-Groq-Provider.md)
  - [HuggingFace](adr/providers/ADR-HuggingFace-Provider.md)
  - [Kimchi](adr/providers/ADR-Kimchi-Provider.md)
  - [LlamaCpp](adr/providers/ADR-LlamaCpp-Provider.md)
  - [Mistral](adr/providers/ADR-Mistral-Provider.md)
  - [Nvidia](adr/providers/ADR-Nvidia-Provider.md)
  - [Ollama](adr/providers/ADR-Ollama-Provider.md)
  - [OMLX](adr/providers/ADR-OMLX-Provider.md)
  - [OpenAI](adr/providers/ADR-OpenAI-Provider.md)
  - [OpenCode](adr/providers/ADR-OpenCode-Provider.md)
  - [OpenRouter](adr/providers/ADR-OpenRouter-Provider.md)
  - [Sarvam](adr/providers/ADR-Sarvam-Provider.md)

- **Speech Provider Specifications**:
  - [Gnani AI speech integration](adr/providers/ADR-Gnani-AI-Provider.md) — Proposed STT/TTS execution adapters; hosted-agent management and unverified chat surfaces are deferred.

### Features & Tools Decisions (`docs/adr/features/`, `docs/adr/tools/`)
- [ADR-amsha-visual-cockpit](adr/features/ADR-amsha-visual-cockpit.md) — Visual cockpit UI architecture and real-time state streaming (Status: Accepted).
- [ADR-Tools-Builtin-and-Sandbox-Modularization](adr/tools/ADR-Tools-Builtin-and-Sandbox-Modularization.md) — Built-in tool and sandbox extraction (Status: Implemented).

### Domain Decisions (`docs/adr/domain/`)
- [ADR-Identity-Access-and-Workspace-Sharing](adr/domain/ADR-Identity-Access-and-Workspace-Sharing.md) — Multi-user workspace sharing and access control (Status: Implemented).
- [ADR-DuckDB-Auxiliary-Analytical-Store](adr/domain/ADR-DuckDB-Auxiliary-Analytical-Store.md) — Embedded DuckDB analytical store integration (Status: Proposed).
- [ADR-indian-legal-ai-harness-requirements](adr/domain/ADR-indian-legal-ai-harness-requirements.md) — Indian legal AI evaluation harness requirements (Status: Proposed baseline).
- [ADR-MCP-Management-UI-and-Channel-Control](adr/domain/ADR-MCP-Management-UI-and-Channel-Control.md) — MCP channel UI and client management (Status: Proposed).
- [ADR-Onboarding-Configuration-Source-and-Precedence](adr/domain/ADR-Onboarding-Configuration-Source-and-Precedence.md) — Configuration precedence rules (Status: Accepted).
- [ADR-Onboarding-Multi-Agent-Provider-Model](adr/domain/ADR-Onboarding-Multi-Agent-Provider-Model.md) — Multi-agent provider model abstraction (Status: Approved).
- [ADR-ONNX-In-Process-Local-ML-Inference](adr/domain/ADR-ONNX-In-Process-Local-ML-Inference.md) — In-process ONNX embeddings and classifier runtime (Status: Accepted for Phases 0–2 / Proposed for Phase 3).
- [ADR-GraalPy-Integration-Boundary](adr/domain/ADR-GraalPy-Integration-Boundary.md) — GraalPy polyglot boundary specification (Status: Accepted).

---

## 5. Evaluated Research & Negative Knowledge (`docs/research/`)

Evaluations of alternatives and boundaries that document why specific approaches were rejected or deferred:

- **GraalPy In-Process Evaluation**:
  - [GraalPy Research Archive](research/graalpy/README.md)
  - [Sarvam GraalPy Feasibility](research/graalpy/SarvamAI-GraalPy-Feasibility.md)
  - [Phase 0 Research Baseline](research/graalpy/Sarvam-GraalPy-Phase-0-Research.md)
  - [Java SDK Options](research/graalpy/Sarvam-Java-SDK-Options.md)
  - [Experiment Closeout](research/graalpy/Sarvam-GraalPy-Experiment-Closeout.md)
  - [Archived Action Packet](research/graalpy/Sarvam-GraalPy-Phase-0-Human-Action-Packet.md)
- **Provider & JVM LLM Evaluations**:
  - [Evaluated Providers Closeout](research/providers/Evaluated-Providers-Closeout.md)
  - [JVM LLM In-Process Options](research/providers/JVM-LLMs.md)
- **Speech API Evaluation**:
  - [Gnani AI Integration Research](research/Gnani-AI-Integration-Research-2026-10-20.md)
- **Architectural Explorations**:
  - [Deeplearning4j Exploration](research/explorations/Deeplearning4j.md)
  - [Langgraph4j Exploration](research/explorations/Langgraph4j.md)
  - [POJO Actor Concurrency Pattern](research/explorations/POJO-Actor-Pattern.md)
  - [Early ONNX Notes](research/explorations/ONNX.md)
  - [Quarkus 3.38 Upgrade Research](research/explorations/Quarkus-Upgrade-Research.md)

- **Agent Decision-Plane Research**:
  - [Jev/System One Decision Plane Scout](research/Jev-Amsha-Decision-Plane-Scout-2026.md) — research basis for the typed Jev judgment seam; the resulting decision is [ADR-jev-decision-plane](adr/architecture/ADR-jev-decision-plane.md) (Status: Deferred), which governs where the two disagree.
  - [Jev Provider Integration Plan](research/Jev-Provider-Integration-Plan-2026.md) — Phase 0 verification evidence (the first-party TypeSafe/System One contract), the wire-conformance findings, and the Laya/Kev/CLM/omlx candidate matrix. Consolidates and supersedes the separate `Jev-Provider-Verification-2026-10-01.md` note; still authorizes no provider activation.

---

## 6. Active RFC Proposals (`docs/proposals/`)

- [Gnani AI Integration Plan](proposals/Gnani-AI-Integration-Plan-2026-10-20.md) — Proposed speech integration sequence and open contract gates.

Living refactoring proposals and version migration plans:

- [Script & Tooling Consolidation Proposal](proposals/SCRIPT_CONSOLIDATION_PROPOSAL.md) — Modular script architecture, tool consolidation, and hermetic CI lifecycle.
- [Quarkus 3.39 Migration Plan](proposals/Quarkus-3.39-Migration-Plan.md) — Quarkus 3.39 platform migration execution plan.
- [LLM Provider Configuration Migration](proposals/LLM-Provider-Configuration-Migration.md) — Configuration property namespace migration.

---

## 7. Roadmap & Backlogs (`docs/roadmap/`)

- [Technical Backlog](roadmap/BACKLOG.md) — Consolidated technical debt, deferred tasks, and future feature backlog.
- [Agent-to-Agent Git Worktree](roadmap/A2A-Git-Worktree.md) — Agent-to-Agent git worktree isolation concepts.
- [Terminal UI Ideas](roadmap/TUI-Ideas.md) — Terminal UI interaction concepts.

---

## 8. Agent Conventions & Shared Assets

- [Domain Documentation Rules](agents/domain.md) — Ubiquitous language and documentation authority rules.
- [Issue Tracker Format](agents/issue-tracker.md) — Local feature iteration issue conventions.
- [Triage Labels](agents/triage-labels.md) — Five-role issue triage label system.
- [Sarvam GraalPy Evidence Cache](agents/sarvam-graalpy-evidence-cache.md) — Offline resolution evidence cache index.
- `docs/assets/` — Architecture SVGs and system diagrams.

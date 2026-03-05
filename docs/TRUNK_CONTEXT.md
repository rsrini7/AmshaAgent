# TRUNK_CONTEXT.md — ArcClaw V1.0.0
> Paste this at the START of every AI session. Do NOT paste the full codebase.
> Ask the AI to request specific files/phases it needs after reading this.
> Update every 5 phases. Last updated: Phase 46.

---

## Project Identity
- **Name:** ArcClaw V1.0.0
- **Type:** Production-grade enterprise AI Agent Kernel
- **Current Phase:** 46 complete → working on Phase 47+
- **Test Health:** 248/248 passing (147 core + 101 app)

---

## Tech Stack (Pinned — Do NOT suggest upgrades without explicit ask)
| Layer | Technology | Version |
|---|---|---|
| Language | Java | 25 (--add-opens for ByteBuddy/Mockito) |
| Framework | Quarkus | 3.23.0 (pinned, no upgrade) |
| AI/LLM | LangChain4J | 1.0.0-beta4 |
| Reasoning LLM | Groq llama-3.3-70b-versatile | Cloud API |
| Intent LLM | Groq llama-3.1-8b-instant | Cloud API |
| Local LLM | Ollama | Disabled by default in V1.0.0 |
| Persistence | SQLite WAL | Single-node, strict singleton |
| ORM | Hibernate ORM | Plain EntityManager (NO Panache in core) |
| Messaging | Apache Camel | camel-quarkus-telegram |
| Sandbox | Podman (rootless) | 3-tier degradation: Podman→Namespace→NONE |
| Crypto | BouncyCastle | 1.78.1 (bcprov-jdk18on) |
| Observability | Micrometer + OTLP/Jaeger | OTel internal spans stubbed in V1.0.0 |
| Concurrency | JDK 25 Virtual Threads | JEP 491 (synchronized, NOT ReentrantLock) |
| Caching | Caffeine | ApiKeyRegistry (2-min TTL, 10k cap) |

---

## Module Structure (Strict Boundaries — Never violate)
```
arcclaw-parent     → BOM only. No logic. Dependency management.
arcclaw-core       → Business logic, domain entities, CDI API, SQLite persistence.
                     NO Panache. NO Quarkus bytecode enhancement. Jandex required.
arcclaw-deployment → Quarkus build-time BuildStep processors. CDI extension wiring.
                     Must have quarkus-extension-processor as PROVIDED dependency.
arcclaw-app        → Runtime entry, REST, application.yaml, integration tests, bootstrapping.
                     Only module where Panache/bytecode enhancement works.
```

---

## Agent Roster (The Cosmic Pantheon)
| CDI Role | Name | Persona | Tool Access |
|---|---|---|---|
| `coordinator` | Vishnu | Orchestrator / Router | All tools |
| `researcher` | Saraswati | Knowledge / Facts | GitHub tool only |
| `coder` | Brahma | Code / Math / Logic | Empty tool schema (no bias) |
| `debugger` / `creative` | Shiva | Contrarian / Transformer | Empty tool schema |

**Intent Router:** IntentClassifierService uses `llama-3.1-8b-instant` (NOT regex).

---

## Core Architectural Invariants (NEVER violate without a new ADR)
1. **NO Panache** in `arcclaw-core` — plain `EntityManager` + `@Entity` only
2. **`synchronized` over `ReentrantLock`** — JEP 491 virtual thread optimization
3. **Split transactions in `AgentCronRepository`** — reads use `NOTSUPPORTED`, writes use `REQUIRED` with `IMMEDIATE` lock
4. **Fork bomb prevention** — `LangChainReasoningEngine` physically removes `scheduletask`/`manageschedule` tools for sessions starting with `cron-`
5. **Secrets via ephemeral `.env` file** — NEVER as process arguments or `System.getenv` in sandbox
6. **`Arc.container.getActiveContext(WorkspaceScoped.class)`** — NEVER `@Inject WorkspaceScopeContext`
7. **Coder/debugger roles get empty tool schema** — tool bias is architecturally impossible by design
8. **`Transactional` NEVER on test methods** — use `QuarkusTransaction.requiringNew()` manually
9. **Jandex index required** in `arcclaw-core` via `jandex-maven-plugin` for CDI bean discovery
10. **`quarkus.hibernate-orm.packages=com.arcclaw.core.state`** must be explicit in `application.yaml`

---

## Active ADR Summary (See ARCHITECTURE_DECISION.md for full detail)
| ADR | Decision | Status |
|---|---|---|
| AD-014 | Discord adapter rejected (abandonware) — Telegram only | Rejected |
| AD-015 | Optimistic locking (`@Version`) on `AgentCheckpoint` + `EncryptedSecret`; Caffeine cache in `ApiKeyRegistry` | Accepted |
| AD-016 | Split transactions in `AgentCronRepository` for SQLite WAL concurrency | Accepted |
| AD-017 | `BACKGROUND` autonomy level between `READONLY` and `SUPERVISED`; fork bomb prevention | Accepted |

---

## Key Services Map (Quick Reference)
| Concern | Class | Module |
|---|---|---|
| CDI Scope | `WorkspaceScopeContext`, `WorkspaceScopeHolder` | core |
| Crypto Vault | `SecretVaultImpl`, `VaultKey`, `AesGcmSivCipher` | core |
| Sandbox | `ProcessIsolationExecutor` (3-tier + circuit breaker) | core |
| Reasoning | `LangChainReasoningEngine`, `AgentLoop` | core |
| Governance | `GovernanceLayer` (USD budget, `BudgetExceededException`) | core |
| PII | `PiiScanReflex` (19 patterns, India-specific) | core |
| Audit | `AuditEventRepository` (HMAC-SHA256 WORM chain) | core |
| Compaction | `ContextCompactionService`, `CompactionSummaryRepository` | core/app |
| Memory RAG | SQLite FTS5 via `AuditSchemaEnforcer` | core |
| Cron | `AgentCronRepository`, `AgentCronScheduler` | core/app |
| Session | `WorkspaceManager` (heartbeats, GC, zombie recovery) | core |
| REST | `AgentResource` (`@RunOnVirtualThread`) | app |
| Channel | `TelegramAgentHandler`, `TelegramChannelRoute` | app |

---

## Test Port Reference
| Bean Type | Mock Strategy | Port |
|---|---|---|
| Regular CDI beans | `@InjectMock` | 8081 |
| HealthCheck beans | `@Alternative @Priority` standalone impl | 9001 |
| OTel Tracer | `@InjectMock Tracer` | N/A |
| WorkspaceScopeContext | `Arc.container` lookup | N/A |

---

## Known Open Tech Debt (Do not re-solve unless in scope)
- OTel internal reasoning spans are **stubbed as no-ops** in V1.0.0 (real spans planned post-V1)
- `ScopedValue` (JEP 506) migration from `ThreadLocal` in `WorkspaceScopeHolder` — noted, deferred
- `executeInContainer` stdout capture returns immutable `String` (secrets risk if tool prints credentials) — LARGE/HIGH risk logged
- `executeInNamespace` and `executeDirectly` ignore exit code — SMALL/HIGH risk logged

---
*Cross-reference: GOTCHAS.md | PROMPTS.md | PHASE_TEMPLATE.md | DEBATE_PROTOCOL.md | ARCHITECTURE_DECISION.md*

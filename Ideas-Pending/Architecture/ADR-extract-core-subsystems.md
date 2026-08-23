# ADR: Extract Core Shared Surfaces and Subsystems from amsha-core

- **Status**: Partially Implemented (rev 6 — streams S1–S7 complete and gated: `amsha-common` utils, `persistence-api`, `llm-provider-api`, `security-api`, `tool-api`, `classifier-api`, `amsha-security` impl; S8–S10 pending)
- **Date**: 2026-08-22 (rev 5; originally 2026-08-21)
- **Decision Owners**: Amsha Architecture Committee
- **Related Invariants**: `docs/Architecture_Invariants.md` §5, §15.1–15.2, §16.1–16.3, §16.6–16.7, §20.1–20.2; `AGENTS.md` §8
- **Feature Slug**: `extract-core-subsystems`
- **Issue Tracker**: `.scratch/extract-core-subsystems/`
- **Predecessors**: `Ideas-Pending/Providers/ADR-LLM-Provider-Extraction-and-Modularization.md`, `Ideas-Pending/Tools/ADR-Tools-Builtin-and-Sandbox-Modularization.md`

---

## 1. Context and Problem Statement

Two modularization rounds already scooped concrete providers and execution machinery out of `amsha-core`:
`amsha-llm-provider-api` + `amsha-llm-providers` (commit `65c64653`) and `amsha-sandbox` + `amsha-tools-builtin`
(commit `a9d40895`, with follow-up diagnostics/SPI fixes `90abf84f` and `a47caaa1`). All extractions are verified
clean at HEAD (`a47caaa1`, re-verified after the fix commits): core has zero imports of `com.amsha.sandbox` /
`com.amsha.tools.builtin` / `com.amsha.llm.providers` (all 33 LLM imports — 9 distinct types — resolve from
`com.amsha.llm.api`), ArchUnit rules 1–15 are present in `ArchitectureBoundaryTest`, and all four new
modules carry `beans.xml`, Jandex indexing, and dedicated `*ReflectionConfig` classes.

`amsha-core` remains the dependency hub of the reactor (~507 production files / ~56.8k lines). Measured reverse
dependencies show that every non-channel module still reaches back into core internals:

| Consumer module | Distinct `com.amsha.core.*` types imported | Dominant pulls |
|---|---|---|
| `amsha-mcp` | 38 | `ToolCategory`(10), `AuditService`(7), `SecurityEventType`(9), `SecretVault`(4), governed HTTP |
| `local-ml-onnx` | 17 | classifier SPIs buried in `core.agent.loop` (`AnswerQuality*`, `ToolRisk*`, `PostPolicyToolRisk*`) |
| `tools-builtin` | 47 | tool contract (16× `ToolResponse`, 15× `AgentTool`), `AuditService`(12), memory services, vault |
| `llm-providers` | 8 | `MultiModelChatModel`(17 files), `GovernedTransportService`(16 files), `SecretVault`(4) |
| `rag` / `self-evolve` / `onboarding` | 15 / 15 / 13 | audit, security/vault, scope, tool contract |
| `persistence-sqlite/fs/postgres` | 8 / 4 / 5 | `TieredBackend`, `CheckpointStatus`, `CronStatus`, `ToolDecision`, utils — **upward** deps |

### Core Problems
1. **Shared surfaces hide inside engine packages.** Cross-module contracts (`AgentTool` family, classifier SPIs,
   vault, state enums) physically live in `core.tools`, `core.agent.loop`, `core.security`, `core.state`, forcing
   feature modules to compile against the whole engine.
2. **Backwards persistence coupling.** All three persistence implementations import core types; persistence should
   only know `persistence-api`.
3. **Incomplete LLM extraction.** `GeminiExceptionClassifier` is hard-wired via `new GeminiExceptionClassifier()`
   inside `core.agent.reasoning.error.LlmExceptionClassifier`, contradicting the provider ADR's allocation of
   provider-specific classifiers to `amsha-llm-providers`. Additionally the whole `Llm*Exception` hierarchy is
   `sealed` on `AmshaException` (`core.exception`) — the taxonomy cannot move to a core-free api module until the
   base exception itself moves down.
4. **Security vocabulary is load-bearing for the tool and classifier contracts.** `AgentTool` imports
   `RequiresSecret` + `ToolOperation`; `ToolRiskShadowContext` imports `AutonomyLevel`; `ToolOperation` itself
   embeds `ApprovalFrame.RiskLevel`. No tool/classifier api module can be core-free before this vocabulary is
   extracted.
5. **Dead weight.** `core.markdown.MarkdownService` is referenced only by its own test; it is also the sole user of
   `org.commonmark` in core (2 libraries).
6. **Core churn & classpath bloat continue.** BouncyCastle, commonmark, and subsystem logic inflate every build that
   touches core.

---

## 2. Decision and Target Architecture

### 2.1 Two-Tier Boundary Policy (rev 2)

Review feedback showed a single "zero core imports everywhere" fence is not achievable without either massive port
scaffolding or dishonest gates. The program therefore fixes **two explicit tiers**, matching existing repo
precedent (`amsha-sandbox` and `amsha-tools-builtin` already depend on `amsha-core`):

| Tier | Modules | Rule |
|---|---|---|
| **API modules** | `llm-provider-api`, `persistence-api`, `channel-api`, new `tool-api`, `classifier-api`, `security-api` | **Zero `com.amsha.core` imports.** ArchUnit-enforced. Pure contracts only. |
| **Impl/feature modules** | `sandbox`, `tools-builtin`, new `amsha-security`, `amsha-memory`, `amsha-skills` | **May depend on `amsha-core`** (and on api modules) when they consume core services — audit, observability, role machinery. **Core must never import an impl module.** |

`amsha-memory` is the one impl module that can additionally satisfy zero-core (verified: only `persistence-api`,
jakarta, langchain4j, and two config/util types to relocate) and keeps that stricter gate.

### 2.2 Dependency Graph

```
              amsha-common (+ AmshaException, PathSanitizer, TemporalUtils, HtmlArtifactUtil, BackoffRetryExecutor)
                   ▲              ▲                 ▲                    ▲
     amsha-persistence-api   amsha-tool-api    amsha-classifier-api   amsha-security-api
     (+ CheckpointStatus,    (AgentTool,       (AnswerQuality*/       (SecretVault, VaultKey, RiskLevel,
      CronStatus, ToolDecision, ToolResponse,   ToolRisk* contracts)   ToolOperation, OutboundPurpose,
      TieredBackend, scope     ToolCapability, ...)                    AutonomyLevel, RequiresSecrets)
      probe port)                 ▲              ▲                    ▲           ▲
                   ▲              │              │                    │           │
        amsha-memory (zero-core)  │              │            amsha-security (impl: may use core
        over persistence-api      │              │             audit/observability; BouncyCastle lives here)
                   ▲              │              │                    ▲
                   │      tools-builtin · mcp · rag · self-evolve · tool-playwright
                   │       (consume tool-api + security-api instead of core internals)
                   ▲
               amsha-core (state machine, reasoning, routing composites, reflex/pipeline,
                           prompt building, governance, lifecycle, audit, domain model)
                   ▲
               amsha-skills (impl: loaders/providers/watcher; registry & entry
                             contracts stay in core — skills → core only, no cycle)
                   ▲
               amsha-app (leaf)
```

### 2.3 Work Streams (execution order)

> **Progress (rev 6):** S1–S7 are implemented and gated — see §8 for the per-stream status table and
> deviations. S8–S9 and the final wiring round (Issue 10) are pending.

| # | Stream | Type | Moves out of core | Est. size |
|---|---|---|---|---|
| S1 | Baseline fences & quick wins | cleanup | dead `MarkdownService` + commonmark deps; **`AmshaException` → `amsha-common`** (unblocks S3); `PathSanitizer`, `TemporalUtils`, `HtmlArtifactUtil`, `BackoffRetryExecutor` → `amsha-common`; `ToolCallJsonKeys` → `llm-provider-api` | ~340 lines + 2 deps |
| S2 | Persistence contract types | move-down | `CheckpointStatus`, `CronStatus`, `ToolDecision`, `TieredBackend`, `ModelCacheInvalidator` event contract → `persistence-api`; **workspace-scope probe port** for sqlite (replaces direct `WorkspaceScopeHolder` use) | ~130 lines |
| S3 | LLM exception taxonomy | move-down/split | generic `Llm*Exception` hierarchy + chain seam → `llm-provider-api` (legal after S1); `GeminiExceptionClassifier` → `llm-providers` | ~370 lines |
| S4 | `amsha-security-api` **vocabulary split** | new api module | `SecretVault` (interface), `VaultKey`, `VaultConstants`, `CryptoConstants`, `RequiresSecret`/`RequiresSecrets`, `ToolOperation` (**with `RiskLevel` extracted from `ApprovalFrame`**), `OutboundPurpose`, `OutboundPolicyDecision`, `AutonomyLevel` | ~450 lines |
| S5 | `amsha-tool-api` | new api module | `AgentTool`, `ToolResponse`, `ToolExecutionContext`, `ToolCategory`, `IdempotencyMode`, `ToolCapability`, `ToolMetadata`, `AgentSentinels` (security imports resolve from S4) | ~700 lines |
| S6 | `amsha-classifier-api` | new api module | `AnswerQuality{Action,Context,Prediction,ShadowClassifier,ShadowContext}`, `ToolRisk{Action,Prediction,ShadowClassifier,ShadowContext}`, `PostPolicyToolContext`, `IntentShadowClassifier` (AutonomyLevel resolves from S4) | ~450 lines |
| S7 | `amsha-security` impl | impl module | vault+crypto: `SecretVaultImpl`, `VaultKeyCache`, `AesGcmSivCipher`, `Argon2idKeyDerivation`, bootstrap credential issuance; governed HTTP: `security/http/*`, `OutboundHttpPolicyService`; hygiene: `InputSanitizer`, `DestructiveVocabularyFilter` | ~2,300 lines; BouncyCastle leaves core |
| S8 | `amsha-memory` | impl module (zero-core) | `memory/tiered/*` (17 files); `TieredMemoryConfig` + **`MemoryPromptBudgetConfig`** move from `core.config` | ~1,080 lines |
| S9 | `amsha-skills` | impl module | skill **machinery** only: `SkillLoader`, `SkillDefinitionParser`, `SkillProvider` + 3 providers, `SkillFileWatcher`, `SkillAdmissionEvaluator`, `HostExecSkillGrant` (9 files). Registry & contracts (`SkillRegistry`, `CompiledSkillEntry`, `SkillDefinition`, `SkillFrontmatter`, `SkillPromptParams`, `SkillAdaptationManager`, `SkillReflex`) stay in core so no core→skills Maven edge exists | ~1,340 lines |

Total projected reduction: **~7,700–9,700 lines**; BouncyCastle and commonmark leave `core/pom.xml`. Core lands
near **~47k lines** with the irreducible engine: Thought-Plan-Act-Observe state machine, reasoning + prompt
building, routing composites, reflex dispatch + message pipeline, scope/context, governance, lifecycle, audit,
domain model.

---

## 3. Resolution of Architectural Seams

### 1. Base exception & shared utilities (S1)
- **`AmshaException` moves to `com.amsha.common.exception`.** It is a trivial abstract `RuntimeException` (two
  protected constructors, no dependencies) used across ~10 module dirs — including `amsha-sandbox`. This is the
  prerequisite for S3: `LlmProviderException` is `sealed` on `AmshaException`, so the taxonomy cannot enter a
  core-free api module while the base lives in core. All `catch`/`throws` sites keep compiling against the new
  package; binary-compatible migration via import rewrite only.
- `PathSanitizer` (sandbox), `TemporalUtils` (tools-builtin, persistence-sqlite), `HtmlArtifactUtil`
  (tools-builtin), `BackoffRetryExecutor` (persistence-sqlite) move to `com.amsha.common.util` — pure static
  helpers, no CDI or core-domain references (verify before moving; stop and record if violated).
- `ToolCallJsonKeys` (llm-providers) moves to `com.amsha.llm.api`.
- `MarkdownService` + test deleted; `commonmark` + `commonmark-ext-gfm-tables` removed from `core/pom.xml`.

### 2. Persistence contract types (S2)
- `CheckpointStatus`, `CronStatus`, `ToolDecision`, `TieredBackend`, and the `ModelCacheInvalidator` event
  contract move to `amsha-persistence-api`; core keeps the concrete invalidator.
- **Workspace-scope probe port (rev 2):** `persistence-sqlite`'s only core-scope use is
  `WorkspaceScopeHolder.isActive()` in `SqliteMemoryRetrievalAdapter`. Moving `WorkspaceScopeHolder` to common is
  not viable (it pulls `ChannelType`, `AutonomyLevel`, Arc, MDC). Instead `persistence-api` defines a minimal
  probe port (e.g. `boolean workspaceScopeActive()`), core binds an adapter bean that delegates to
  `WorkspaceScopeHolder`; the sqlite adapter injects the port. Zero-core gate for persistence impls is preserved.
- Wire compatibility: serialization-equivalence tests in each store module before old packages are deleted.

### 3. LLM exception taxonomy (S3 — after S1)
- Generic taxonomy (`LlmProviderException` … `LlmToolCallException`, `LlmExceptionClassifierChain`) moves to
  `com.amsha.llm.api.error`; legal only because `AmshaException` moved to `amsha-common` in S1 (sealed hierarchy
  intact, catch sites unchanged).
- `LlmExceptionClassifier` becomes an SPI in the api module; `GeminiExceptionClassifier` moves to
  `amsha-llm-providers` as `@ApplicationScoped @Unremovable`; core/routing consumes classifiers via the shared
  BeanManager enumeration (§15.2). `GenericExceptionClassifier` / `LangChain4jExceptionClassifier` stay
  framework-level (implementation proximity, §20.1) unless evidence shows SDK coupling.
- Classification-outcome equivalence tests must pass before/after (`LlmModelFactoryTest`, `ModelRouterTest`).

### 4. Security vocabulary first (S4 — before tool/classifier apis)
- Review finding: `AgentTool` imports `RequiresSecret` + `ToolOperation`; `ToolRiskShadowContext` imports
  `AutonomyLevel`. The tool and classifier api modules therefore cannot exist core-free until the security
  vocabulary does.
- `com.amsha.security.api` receives: `SecretVault` (interface, moved as-is), `VaultKey`, `VaultConstants`,
  `CryptoConstants`, `RequiresSecret`/`RequiresSecrets`, `ToolOperation`, `OutboundPurpose`,
  `OutboundPolicyDecision`, `AutonomyLevel`.
- **`RiskLevel` extraction:** `ToolOperation` embeds `ApprovalFrame.RiskLevel` in every constant (27 repo-wide
  qualified references, measured). `RiskLevel` is extracted as a top-level enum in `com.amsha.security.api`;
  `ApprovalFrame`
  (core.governance) and all 18 references re-point. No duplicate risk vocabulary is created.
- Consumers re-pointed in the same iteration: `core.tools`, `core.agent.loop`, `core.governance`, plus
  sandbox/tools-builtin/llm-providers/mcp/onboarding imports of these vocabulary types.

### 5. `amsha-tool-api` (S5)
- Contract module `com.amsha.tool.api`: `AgentTool`, `ToolResponse`, `ToolExecutionContext`, `ToolCategory`,
  `IdempotencyMode`, `ToolCapability`, `ToolMetadata` (from `core.agent.role`), `AgentSentinels`
  (mcp, rag, tools-builtin, app). Its security imports resolve from `amsha-security-api` (S4) — the module graph
  is `tool-api → security-api`, never `tool-api → core`.
- Core keeps registry/governance machinery, coordination tools, `CheckList*` records, `CronScheduleUtil`,
  `CodeLanguageDetector`, `OutputTruncator`.
- The 21-bean static `AgentTool` manifest and discovery ordering must be unchanged.

### 6. `amsha-classifier-api` (S6)
- Contracts move to `com.amsha.classifier.api`: `AnswerQuality*` / `ToolRisk*` prediction/action/context types,
  shadow-classifier SPIs, `PostPolicyToolContext`, `IntentShadowClassifier`, and `core.classifier` primitives
  where core-free. `AutonomyLevel` resolves from `amsha-security-api`.
- Dispatchers, interceptors, guards, evaluation services stay in `core.agent.loop` (mandatory-check plumbing).
  Pre-policy deterministic decisions untouched; post-policy hooks only increase risk; answer-quality keeps the
  one-retry-before-tool-boundary rule.
- `local-ml-onnx` re-points to the api module.

### 7. `amsha-security` implementation (S7 — tier-2 module)
- Moved (as executed): `SecretVaultImpl`, `VaultKeyCache`, `AesGcmSivCipher`, `Argon2idKeyDerivation`,
  `BootstrapCredentialIssuer`, `PreparedBootstrapCredential`, `IssuedCredential`, `DevSecretSeeder`,
  `StandardAutonomyPolicy`, and `security/http/*`. **Retained in core** (core-internal consumers would have
  created the forbidden core→impl edge): `InputSanitizer`, `DestructiveVocabularyFilter`,
  `AutonomyPolicy` (interface; its impl moved), `OutboundHttpPolicyService` (`NetworkPolicyEnforcer`),
  `SessionOwnerResolver` (`ChannelServicesImpl`).
- **Dependency policy (rev 2):** `amsha-security` depends on `amsha-security-api` **and `amsha-core`** for
  cross-cutting services, exactly like `amsha-sandbox` does today. Verified consumed core types:
  `SecretVaultImpl` → `AuditService`, `SecurityEventType`, `AgentSentinels`, `AgentOTelConstants`,
  `AgentOtelContext`, `WorkspaceContext`; `ApprovalReceiptIssuer` → `AuditService`, `SecurityEventType`,
  `StatefulAgentStore`, `ToolDecision`. These stay direct dependencies — no port scaffolding — because the tier-2
  fence (core never imports impl) is what protects the architecture. If a later round wants a core-free security
  impl, it can introduce a security-audit port then.
- `AutonomyPolicy`/`StandardAutonomyPolicy` placement decided during execution with the same evidence rules;
  `ExternalIdentityProvider`/`ExternalUserProfile` stay in core (session-domain coupling).
- BouncyCastle (`bcprov-jdk18on`, with existing FIPS exclusions) moves to `amsha-security/pom.xml`; runtime-init
  by package. HITL receipts keep flowing through `ApprovalGateway` (channel-api) — zero channel imports.
- **Consumer dependency policy (rev 3):** consumers using only vocabulary + the `SecretVault` interface compile
  against `amsha-security-api` alone (`sandbox`, `tools-builtin`). Consumers that verifiably use concrete
  governed-HTTP/bootstrap classes declare a **direct `amsha-security` dependency** — impl→impl, precedent
  `tools-builtin`→`amsha-sandbox`, `mcp`→`amsha-sandbox`: `llm-providers` (`GovernedTransportService` ×16 files),
  `mcp` (governed HTTP + approval receipts), `onboarding` (bootstrap credential classes); `app` wires the impl.
  Moving these concretes into the api module is rejected: they carry audit/store dependencies and would destroy
  api purity.
- Sanitized-failure semantics unchanged (`LogUtil.rootMessage(e)`, no payloads/header echoes).

### 8. `amsha-memory` (S8 — zero-core impl module)
- All 17 `memory/tiered` files move to `com.amsha.memory`; **`MemoryPromptBudgetConfig` moves from `core.config`
  alongside `TieredMemoryConfig`** (rev 2: `PromptBudgetManager` imports it; property names unchanged, verified
  against `application.yaml`/`.env.example`/READMEs).
- Module deps: `amsha-common`, `amsha-persistence-api`, quarkus-arc, langchain4j-core, Jackson — **zero
  `com.amsha.core` imports**, ArchUnit-enforced (achievable and kept strict).
- Consumers: core (`AgentLoop`, `ChatHistoryAssembler`, `CompactionService`, `PersistentChatMemoryStore`),
  `tools-builtin` memory tools, `self-evolve` directive ports.
- Vector-namespace safety unchanged: descriptor compatibility validated, non-destructive BM25/FTS5 fallback,
  never auto-purge on mismatch.

### 9. `amsha-skills` (S9 — tier-2 module, machinery only)
- **Cycle-free split (rev 3):** `amsha-skills` depends on `amsha-core` — **one direction only**; core never
  declares a Maven edge to `amsha-skills`. The rev-2 "core → skills" consumer edge was a cycle and is removed by
  keeping the consumption contracts in core.
- **Stay in `core.skill`** (7 files / 515 lines; all 20 core import sites keep compiling): `SkillRegistry`
  (passive: `AtomicReference` + `reload(...)` + snapshots), `CompiledSkillEntry`, `SkillDefinition`,
  `SkillFrontmatter`, `SkillPromptParams`, `SkillAdaptationManager` (consumed by `ReasoningMessageAssembler` —
  moving it would force core → skills), `SkillReflex`.
- **Move to `amsha-skills`** (9 files / ~1,340 lines; zero consumers outside the package except reflection
  config): `SkillLoader`, `SkillDefinitionParser`, `SkillProvider` (SPI), `ClasspathSkillProvider`,
  `DatabaseSkillProvider`, `SkillsJarSkillProvider`, `SkillFileWatcher`, `SkillAdmissionEvaluator`,
  `HostExecSkillGrant`.
- **Population seam:** the moved loader/watcher injects core's `SkillRegistry` and calls `reload(...)` — the same
  shape as `ToolSearchIndex` observing `ToolDiscoveryCompleted`; no new port required.
- Role/audit machinery (`AgentRoleEnum`, `CapabilityRegistry`, `RoleDefinition`, `RoleRegistry`,
  `TelemetryAuditService`) is consumed by both sides: contracts keep their core-internal imports; moved machinery
  reaches them via the legal skills → core direction. A zero-core skills gate remains a non-goal (persona
  machinery extraction out of scope). Optional future tightening: role-id string in `CompiledSkillEntry` +
  role-lookup port.
- `SkillFileWatcher` (and any boot-registering bean) requires `@io.quarkus.runtime.Startup` per the lazy-bean rule;
  startup-log evidence required.
- SkillsJar contract unchanged: prompt/tool wrappers only; `verify_skillsjar_index.py` parity.

---

## 4. Key Invariants & Non-Functional Requirements

1. **Two-tier ArchUnit fences (§2.1)**: api modules must not depend on `com.amsha.core..`; core must not depend on
   impl modules (`com.amsha.security`, `com.amsha.skills`, `com.amsha.sandbox`, `com.amsha.tools.builtin`);
   persistence impls must not depend on core. Impl→core dependencies are legal and intentional.
   **`com.amsha.memory` is the single exempted impl module**: it keeps a zero-core gate itself (no cycle is
   possible), and core consumes its concrete services (`AgentLoop`, `ChatHistoryAssembler`, `CompactionService`,
   `PersistentChatMemoryStore`), so the legal direction is core → memory. The fence list and Issue-01 rule
   deliberately exclude it.
2. **CDI Discovery (§15.1–15.2, §16.1–16.3)**: moved beans are `@ApplicationScoped` + `@Unremovable`; optional SPIs
   resolve via the shared `BeanManager.getBeans(Object.class)` resolver with deterministic >1 failure;
   `CoreSpiResolutionValidator` extended for new optional ports.
3. **Module plumbing**: each new module gets Jakarta EE 10 `beans.xml` (annotated), `jandex-maven-plugin`
   (`make-index`), a dedicated `*ReflectionConfig`, `src/test/resources/application.yaml` dummy values, and the
   standard Surefire fork settings.
4. **Application indexing (§16.7)**: every new module registered under `quarkus.index-dependency` with explicit
   coordinates in `app/src/main/resources/application.yaml`.
5. **Native reflection (AGENTS.md §8)**: prune moved types from `CoreReflectionConfig`; register in the owning
   module's config (targets for accessible types, binary-name `classNames` for private/nested); BouncyCastle
   runtime-init by package.
6. **ConfigProperty governance**: relocated `@ConfigProperty`/`@ConfigMapping` entries verified against
   `application.yaml`, `.env.example`, owning README (`verify_skillsjar_index.py --fix` for skills parity).
7. **PMD gate**: NCSS ≤ 80, cognitive complexity ≤ 50, ≤ 500 lines per class, module-scoped per iteration.
8. **Wire compatibility**: persisted enums/records ship serialization-equivalence tests before old packages are
   deleted; sealed hierarchies and catch-site semantics preserved (`AmshaException` move is import-rewrite only).
9. **No staging**: all git staging/commit decisions remain with the human reviewer.

## 5. Non-Goals (explicitly deferred)

- `audit/` extraction: heavily coupled to core domain events, `AsyncPersistenceEngine`, `AgentObserver`; tier-2
  impl modules consume it from core until a dedicated round.
- Observability/OTel exporter scoop; group-consultation cluster (~894 lines); prompt-template infrastructure
  (protected by seeded/admin-edit invariants); reflex/pipeline extraction (core's spine).
- Role/registry machinery extraction (`RoleDefinition`, `RoleRegistry`, `CapabilityRegistry`) — required for any
  future zero-core `amsha-skills`; blocked by persona/escalation invariants this round.
- **Markdown rendering for chat channels**: the deleted core `MarkdownService` (sole commonmark user) was never
  wired to Telegram or any channel — `channel-telegram` renders MarkdownV2 via its own
  `TelegramMarkdownV2Renderer` with plain-text fallback. If richer markdown conversion is wanted later, scope a
  parser dependency to the channel module only; revisit after this program completes.

## 6. Verification Checklist (program-level)

- [x] `grep -r "import com.amsha.core" persistence-sqlite persistence-fs persistence-postgres` → empty (S2),
      including the workspace-scope probe port replacing `WorkspaceScopeHolder.isActive()`. **Done (Issue 02)**.
- [x] `com.amsha.core.exception.AmshaException` no longer exists; all references resolve
      `com.amsha.common.exception.AmshaException` (S1). **Done (Issue 01)**.
- [x] No `GeminiExceptionClassifier` reference under `core/`; `com.amsha.llm.api.error` compiles core-free (S3).
      **Done (Issue 03, incl. the `LlmErrorClassificationService` SPI)**.
- [x] `com.amsha.security.api` exists core-free with `RiskLevel`; `ApprovalFrame.RiskLevel` references re-pointed
      (S4); no `core.security.{SecretVault,VaultKey,ToolOperation,...}` imports remain in sandbox/tools-builtin/
      llm-providers/mcp/onboarding vocabulary call sites. **Done (Issue 04)**.
- [x] tools-builtin/mcp/rag/self-evolve/tool-playwright no longer import `core.tools.AgentTool`,
      `core.agent.role.ToolCapability/Metadata`, `core.agent.AgentSentinels` (S5). **Done (Issue 05)**.
- [x] local-ml-onnx no longer imports `core.agent.loop` classifier types (S6). **Done (Issue 06; 13 types moved,
      `ClassifierFailureMetrics` retained in core — see §8)**.
- [x] `bcprov-jdk18on` absent from `core/pom.xml`, present only in `amsha-security` (S7); core imports no
      `com.amsha.security` impl classes. **Done (Issue 07; native init metadata relocated to the owning
      module and converted to package prefixes — fresh native build still to be smoke-validated)**.
- [ ] `amsha-memory` has zero `com.amsha.core` imports; `MemoryPromptBudgetConfig` resolved from the module (S8).
- [ ] `core.skill` retains only contracts + registry (`SkillRegistry`, `CompiledSkillEntry`, `SkillDefinition`,
      `SkillFrontmatter`, `SkillPromptParams`, `SkillAdaptationManager`, `SkillReflex`); machinery lives in
      `amsha-skills`; **no `amsha-skills` edge in `core/pom.xml`** (cycle gate); skills → core is the only
      direction; core imports no `com.amsha.skills` classes (S9).
- [ ] Per-iteration gates: `fix_fqns.py && spotless:apply`, module-scoped `pmd:check`, focused tests,
      `ArchitectureBoundaryTest`, `./mvnw clean install -DskipTests`, `mise run sonar:issues`; native smoke for
      native-sensitive rounds.

---

## 7. Implementation Progress (rev 6, as of 2026-08-22)

| Stream | Issue | Status | Outcome & deviations |
|---|---|---|---|
| S1 baseline fences & quick wins | 01 | ✅ Complete | `AmshaException` → `amsha-common`; `PathSanitizer`, `TemporalUtils`, `HtmlArtifactUtil`, `BackoffRetryExecutor` → common; dead `MarkdownService` + both commonmark libs dropped; baseline ArchUnit fences in. |
| S2 persistence contracts | 02 | ✅ Complete | `CheckpointStatus`, `CronStatus`, `ToolDecision`, `TieredBackend` → `persistence-api`; `PersistenceScopeProbe` port replaces the direct `WorkspaceScopeHolder` probe; `ModelCacheInvalidationPort` added for the cache-invalidation contract. Persistence impls are core-free. |
| S3 LLM exception taxonomy | 03 | ✅ Complete | Generic `Llm*Exception` hierarchy + chain seam → `llm-provider-api` (legal post-S1); `GeminiExceptionClassifier` → `llm-providers`; new `LlmErrorClassificationService` SPI keeps classification pluggable. |
| S4 security vocabulary | 04 | ✅ Complete | `com.amsha.security.api` created core-free: `SecretVault`, `VaultKey`, `VaultConstants`, `CryptoConstants`, `RequiresSecret(s)`, `ToolOperation`, `OutboundPurpose`, `OutboundPolicyDecision`, `AutonomyLevel`, and top-level `RiskLevel` extracted from `ApprovalFrame`. |
| S5 tool api | 05 | ✅ Complete | `AgentTool`, `ToolResponse` family, `ToolExecutionContext`, `ToolCategory`, `IdempotencyMode`, `ToolCapability`, `ToolMetadata`, `AgentSentinels` → `com.amsha.tool.api`. **Deviation:** `DynamicToolDescriptor` also moved from `core.tool` (`DynamicToolAdmission`/`Lookup` stayed in core per the `ToolSearchPort` precedent). Post-review cleanup: javadoc link fixed to `{@code}`, `BY_TOOL_NAME` wrapped in `Map.copyOf`, unused api edges removed from three poms. 21-bean discovery manifest unchanged. |
| S6 classifier api | 06 | ✅ Complete | 13 types → `com.amsha.classifier.api`: the 10 `core.agent.loop` answer-quality/tool-risk contracts + `PostPolicyToolContext`, `IntentShadowClassifier`, and both `core.classifier` primitives (`ClassifierResult` family incl. nested `NoOpinionReason`, `ShadowClassifier`). **Deviation:** `ClassifierFailureMetrics` retained in core (micrometer dep outside the module's declared set; consumers are the retained dispatchers — issue escape clause). ArchUnit coverage comes from shared rule 16 (`apiModulesShouldNotImportCore`, which already includes `com.amsha.classifier.api..`; a dedicated rule added during S6 was removed post-review as redundant); `ClassifierContractsTest` pins vocabularies; local-ml-onnx re-pointed off `core.agent.loop` classifiers. |
| S7 security impl | 07 | ✅ Complete | 15 types → `com.amsha.security` (+ `.http`): vault/crypto (`SecretVaultImpl`, `VaultKeyCache`, `AesGcmSivCipher`, `Argon2idKeyDerivation`), bootstrap credential trio, `DevSecretSeeder`, `StandardAutonomyPolicy`, and the six governed-HTTP classes. **Deviation:** six types retained in core per the escape clause — `InputSanitizer` (8 core consumers), `DestructiveVocabularyFilter` (3), `AutonomyPolicy` interface, `OutboundHttpPolicyService` (`NetworkPolicyEnforcer`), `SessionOwnerResolver` (`ChannelServicesImpl`) — plus the issue-mandated `ExternalIdentity*`. BouncyCastle left core (FIPS exclusions + native init metadata relocated verbatim); direct `amsha-security` deps for llm-providers/mcp/onboarding/sandbox (census-found consumer)/app; `CoreSpiResolutionValidator` validates the vault port; test-fixture producers keep core QuarkusTests (vault, autonomy) bootable. |

**Fence evolution**: `ArchitectureBoundaryTest` grew from 15 rules (at ADR acceptance) to **19 rules** — the
api-tier fence for `tool-api`/`classifier-api`/`security-api` is the single shared rule 16
(`apiModulesShouldNotImportCore`); persistence-impl core-freeness and skills reservation round out the set.

**Adjacent work during this window** (not an ADR stream; tracked in Issue 05 Comments 10–12): a bounded
memory-synthesis correction ladder in `FinishActionHandler` (correction budget → one-call model-tier escalation
→ deterministic verbatim-memory exit), eliminating the step-exhaustion loop observed on native runs.

## 8. Remaining Work

1. **S8 `amsha-memory` (Issue 08)**: `memory/tiered/*` (17 files) + `TieredMemoryConfig` +
   `MemoryPromptBudgetConfig` (~1,080 lines); zero-core gate with the legal core → memory edge.
3. **S9 `amsha-skills` (Issue 09)**: 9-file loading machinery only; registry/contracts stay in core; no
   `amsha-skills` edge in `core/pom.xml`.
4. **Issue 10 final wiring**: app wiring audit, two-tier boundary audit, reflection-config audit, JVM CDI
   startup proof (21-bean manifest), full reactor gates + native smoke.

## 9. Revision History

- **rev 1 (2026-08-21)**: initial proposal.
- **rev 2 (2026-08-21)**: review fixes — P1-1 `AmshaException` → `amsha-common` before LLM taxonomy move (sealed
  hierarchy); P1-2 security vocabulary split inserted before tool/classifier apis, `RiskLevel` extracted from
  `ApprovalFrame`; P1-3 tier-2 dependency policy for `amsha-security` with verified consumed-type list (replaces
  unachievable zero-core fence); P1-4 workspace-scope probe port replaces `WorkspaceScopeHolder`→common move;
  P1-5 `amsha-skills` gate relaxed to tier-2 with consumed-type evidence; P1-6 `MemoryPromptBudgetConfig` added
  to the memory manifest.
- **rev 3 (2026-08-21)**: second-review fixes — skills Maven cycle removed (registry + entry/prompt contracts and
  `SkillAdaptationManager` stay in core; only the 9-file loading machinery moves; skills → core is the sole
  direction, no `amsha-skills` edge in `core/pom.xml`); security consumer policy made honest (vocabulary-only
  consumers use `security-api`; `llm-providers`/`mcp`/`onboarding` declare direct `amsha-security` deps for
  concrete governed-HTTP/bootstrap classes — impl→impl precedent).
- **rev 4 (2026-08-22)**: implementation-readiness fixes — `com.amsha.memory` exempted from the
  core-never-imports-impl fence (zero-core impl consumed by core via a legal core → memory edge; fence list,
  Issue-01 rule, and Issue-10 audit updated); `RiskLevel` reference count corrected to the measured 27;
  status line aligned with revision history.
- **rev 6 (2026-08-22)**: S7 complete — `amsha-security` created (tier-2 impl; BouncyCastle relocated), 15 types moved with their suites, six retained in core with documented reasons, consumers re-pointed (incl. census-discovered sandbox edge); §3.7 seam updated to the as-executed placement; §8 remaining work now S8–S10.
- **rev 5 (2026-08-22)**: progress update — S1–S6 implemented and gated (`amsha-common` utils, `persistence-api`,
  `llm-provider-api`, `security-api`, `tool-api`, `classifier-api`); §6 checklist items ticked with evidence;
  new §7 Implementation Progress table with per-stream deviations (`DynamicToolDescriptor` moved in S5,
  `ClassifierFailureMetrics` retained in S6), §8 Remaining Work, fence evolution documented
  (19 rules — api-tier coverage via shared rule 16; an initially added dedicated classifier-api rule was later
  removed as redundant); Revision History renumbered to §9.

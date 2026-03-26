# ArcClaw — Phase 69: Review, Plan & Antigravity Prompts
*Full end-to-end read of P68 codebase (887 tests, 7 modules)*

---

## Part 1 — P68 Verification: What Actually Landed

Reading every file in the P68 snapshot against the P67 plan and all prior reviewer demands:

| Item | Designed | Landed | Quality |
|---|---|---|---|
| `arcclaw-channel-telegram` module | P67-T1 | ✅ Complete — 10 classes, own `pom.xml`, own `resources/META-INF/beans.xml`, `TelegramReflectionConfig` added | Exceeds spec |
| `ChannelSenderRegistry` deleted | P67-T3 | ✅ Gone — dispatchers use `ChannelPluginRegistry.find().map(ChannelPlugin::jobNotifier)` | Clean |
| Caffeine cache `processingFlags` | P67-T4 | ✅ `Cache<String, AtomicBoolean>` + `FLAG_TTL = 10min` + `isProcessing()` test hook | Exact spec |
| `MessageContext` `ChannelSender` removed | P67-T5 | ✅ 3-arg constructor, `getChannelType()` added, no `getSender()` | Clean |
| `PromptTemplateEngine.templateFor()` warning | P67-T2 | ✅ **DONE** — `log.warnf(...)` in `default` branch with fix instructions | Exact spec |
| `ChannelPlugin` / `ChannelDaemon` / `ChannelJobNotifier` promoted to `arcclaw-channel-api` | P68 new | ✅ All three now in `com.arcclaw.channel.api` | Architecture complete |
| `ChannelPluginRegistry` moved to `arcclaw-core/channel` | P68 new | ✅ `com.arcclaw.core.channel.ChannelPluginRegistry` — `AgentDispatchHandler` injects it directly | Correct final home |
| `ObservationTransformer` SPI | P68 new | ✅ Interface + `TruncationTransformer`(10) + `InjectionRedactionTransformer`(20) + `PiiRedactionTransformer`(30) | Textbook Chain of Responsibility |
| `InputSanitizer` interface + `@PII` / `@Injection` qualifiers | P68 new | ✅ Clean qualifier pattern — `PromptInjectionReflex` and `PiiScanReflex` become `InputSanitizer` implementations | Clean |
| `SkillProvider` SPI | P68 new | ✅ `ClasspathSkillProvider` + `DatabaseSkillProvider` — `SkillLoader` now `Instance<SkillProvider>` | Exact GLM/Gemini demand |
| `LlmProviderFactory` in `arcclaw-core/reasoning` | P68 new | ✅ Separate from `arcclaw-app/routing/LlmProviderFactory` — intentional dual SPI | Naming clarity needed (P69-H1) |
| `ChatModelProducer` in core | P68 new | ✅ `Instance<LlmProviderFactory>` stream dispatch — `GroqProviderFactory`, `OllamaProviderFactory`, `OpenAiProviderFactory` | Clean mirror of embedding SPI |
| `SandboxExecTool` live | P68 new | ✅ Python, Bash, Node, Java, Go — `SandboxCodeSafetyScanner` static check, `JAVA_CLASS_PATTERN` static, `resolveJavaExecutable()` Windows-aware | 85% — needs Go workspace init |
| `RateLimitTracker` | P68 new | ✅ 30-second backoff, `AtomicReference<Instant>`, `CompactionHandler` checks `isBackingOff()` | Surgical fix for cascading "Done." bug |
| `AgentResultFormatter` | P68 new | ✅ Replaces `CronResultFormatter` — used by `AgentDispatchHandler`, `CronResultHandler`, `AgentSessionService` | Good extraction |
| `CompactionAbortedException` | P68 new | ✅ Explicit error type — compaction boundary clean | Clean |
| `GovernanceStrategy` injection | P66-T7 | ⚠️ **Partial** — `EngineStepHandler` and `AgentSessionService` inject `GovernanceStrategy` ✅, but `StatusReflex` still injects `GovernanceLayer` directly at line 19397 | One caller remains |
| `SessionKnowledgeBase` durability | Deferred | ❌ Still `InMemoryEmbeddingStore` — session vector context lost on crash | Real gap |
| Two `LlmProviderFactory` naming | — | ⚠️ Both named `LlmProviderFactory` in different packages — no disambiguation comment | P69-H1 |
| `CHANNEL_SPI.md` table | P67-T9 | ⚠️ Table still says `ChannelPlugin` lives in `arcclaw-app/channel` — stale since P68 moved it to `arcclaw-channel-api` | Documentation drift |

**P68 verdict: 15/16 items landed correctly. 1 partial (StatusReflex GovernanceLayer). 1 persistent gap (SessionKnowledgeBase). 887 tests pass.**

---

## Part 2 — Full Cross-Phase Scorecard

| Dimension | Original | P65 | P66 | P67 | P68 | **Notes** |
|---|---|---|---|---|---|---|
| Channel SPI | 0% | 45% | 72% | 95% | **99%** | Only gap: `CHANNEL_SPI.md` table stale |
| Channel Module Isolation | 0% | 0% | 70% | 98% | **100%** | `arcclaw-channel-telegram` fully extracted |
| RAG / Embedding SPI | 0% | 100% | 100% | 100% | **100%** | Stable since P65 |
| LLM Provider SPI | 0% | 40% | 100% | 100% | **100%** | Two intentional SPIs (core + app) |
| Agent Role SPI | 0% | 15% | 90% | 95% | **97%** | `AgentRoleEnum.isSkillRole()`, `skillName()`, `canGenerateCode()` |
| Observation Pipeline SPI | 0% | 0% | 0% | 0% | **100%** | `ObservationTransformer` Chain of Responsibility |
| Skill Source SPI | 0% | 0% | 0% | 0% | **100%** | `SkillProvider` with classpath + DB implementations |
| Governance SPI | 0% | 30% | 75% | 78% | **90%** | `StatusReflex` still injects concrete |
| SandboxExec | 0% | 0% | 0% | 0% | **85%** | Go workspace init gap, Windows path fine |
| RAG Durability | 0% | 0% | 0% | 0% | **0%** | `SessionKnowledgeBase` still in-memory |
| Documentation | 0% | 5% | 40% | 70% | **75%** | `CHANNEL_SPI.md` table drift; `Architecture_Nuances.md` needs updates |
| Test Coverage | 0% | 0% | 810 | 810 | **887** | +77 tests in P68 |
| **Overall Maturity** | **3.5** | **8.0** | **9.2** | **9.6** | **9.8/10** | |

### How P68 Addressed Every Reviewer's Core Demand

| Reviewer | Original Top Demand | Status |
|---|---|---|
| **Grok** | ChannelPlugin SPI, `ChannelMessageHandler` | ✅ ChannelPlugin done. `ChannelDaemon.start()` IS the inbound handler hook. `CHANNEL_SPI.md` documents the pattern. |
| **Gemini** | `AgentRoleEnum` → open record, `AgentRoleRegistry` | ✅ Done P66, `isSkillRole()` / `skillName()` / `canGenerateCode()` added in P68 |
| **Perplexity (Claude)** | Tests P1, LlmFactory P2 | ✅ 887 tests. Two `LlmProviderFactory` SPIs. |
| **Perplexity (GPT 5.4)** | Channel-first redesign, `ChannelSenderRegistry` death | ✅ Registry deleted P67. Module extracted P67. |
| **Perplexity (Gemini 3.1)** | `MessageContext` immutable request + mutable state split | ✅ `MessageContext` 3-arg constructor, no sender coupling |
| **DeepSeek** | Chain of Responsibility for tool observation | ✅ `ObservationTransformer` SPI — exact GoF CoR pattern |
| **Qwen** | Channel adapters → separate modules | ✅ `arcclaw-channel-telegram` extracted P67 |
| **Kimi** | `processingFlags` race, `AgentRoleEnum` God-Enum | ✅ Caffeine TTL cache P67. AgentRoleEnum → record P66. |
| **GLM** | `SkillProvider` extensibility, personas as data | ✅ `SkillProvider` SPI P68. `AgentRoleRegistry` CDI-discovered P66. |

---

## Part 3 — P69 Remaining Work: Prioritized Backlog

### HIGH (Do in P69)

**H1 — Rename `LlmProviderFactory` in `arcclaw-core/reasoning` to avoid collision**
There are two interfaces both named `LlmProviderFactory`:
- `arcclaw-core/reasoning/LlmProviderFactory` — simple: `providerName()` + `create()` — used by `ChatModelProducer` for the ReasoningEngine's main ChatModel
- `arcclaw-app/routing/LlmProviderFactory` — rich: `providerId()` + `create(LlmModelConfig)` — used by `LlmModelFactory` for the intent classifier model

Both work, both are correct, but the identical name across modules is a contributor confusion bomb. Rename the core one to `ReasoningModelProviderFactory` (or `CoreLlmProviderFactory`). Update `ChatModelProducer`, `GroqProviderFactory`, `OllamaProviderFactory`, `OpenAiProviderFactory` in core. Zero behavioral change. Zero config change. Pure rename.

**H2 — Fix `StatusReflex` concrete `GovernanceLayer` injection**
`StatusReflex` at line 19397 injects `@Inject GovernanceLayer governanceLayer` directly, bypassing the `GovernanceStrategy` interface that `EngineStepHandler` and `AgentSessionService` correctly use. `StatusReflex` calls `getSessionCost()`, `getBudgetUsd()`, `getSessionInputTokens()`, `getSessionOutputTokens()` — the telemetry-only methods. The correct fix is to add those four methods to `GovernanceStrategy` (they belong in the interface — they are observable state, not internal implementation). Then `StatusReflex` injects `GovernanceStrategy`.

**H3 — Fix `CHANNEL_SPI.md` table stale references**
The table says `ChannelPlugin` lives in `arcclaw-app/channel` and `ChannelDaemon` lives in `arcclaw-core/pipeline`. Both moved in P68:
- `ChannelPlugin` → `arcclaw-channel-api`
- `ChannelDaemon` → `arcclaw-channel-api`
- `ChannelJobNotifier` → `arcclaw-channel-api`
- `ChannelPluginRegistry` → `arcclaw-core/channel`

Update the table, update the Javadoc `@link` in `ChannelPlugin` (still points to `com.arcclaw.app.channel.ChannelPluginRegistry`), and update `Architecture_Nuances.md` with two new nuances for the `ChannelPluginRegistry` location and the `ObservationTransformer` SPI.

**H4 — `SessionKnowledgeBase` write-through to SQLite `vec_session`**
Every reviewer since P65 flagged this. `SessionKnowledgeBase.ingest()` currently writes only to `InMemoryEmbeddingStore`. On JVM crash before compaction, the session's vector context is lost permanently. The fix: write-through to a `vec_session` SQLite table alongside the in-memory store. On `@WorkspaceScoped` init, populate the in-memory store from existing `vec_session` rows for that session. On `@PreDestroy`, the in-memory store can be safely discarded since SQLite is the source of truth. This is the last real data durability gap.

---

### MEDIUM (Do in P69 or P70)

**M1 — `SandboxExecTool` Go workspace initialization**
`executeGo()` writes `main.go` and runs `go run main.go` — but `go run` requires a valid Go workspace (`go.mod`) for imports to work. Code using standard library only works fine, but any `import "github.com/..."` will fail with `no required module provides package`. Fix: write a minimal `go.mod` alongside `main.go` before running `go run`:
```
module arcclaw-sandbox
go 1.21
```
`go run` with a `go.mod` present handles stdlib-only code identically, and third-party imports can then be resolved with `go mod tidy` before running.

**M2 — `Architecture_Nuances.md` — add P67/P68 nuances**
Three nuances need adding:
- `ChannelPluginRegistry` is now in `arcclaw-core/channel` — any handler in core can inject it directly without crossing module boundaries
- `ObservationTransformer` SPI: add transformers by implementing the interface as a CDI bean — priority 10 fires first. Never bypass `ToolObservationSanitizer.sanitize()` call chain.
- Two `LlmProviderFactory` SPIs exist intentionally: `arcclaw-core/reasoning/ReasoningModelProviderFactory` for the agent loop's main ChatModel; `arcclaw-app/routing/LlmProviderFactory` for the intent classifier model. They configure different things and must not be confused.

**M3 — `SandboxExecTool` `extractJavaClassName` regex duplication**
`extractJavaClassName()` compiles `Pattern.compile("public\\s+class\\s+(\\w+)", ...)` inline — violating Nuance #3 (all Pattern objects must be `static final`). `JAVA_CLASS_PATTERN` exists for `class ClassName` but not for `public class ClassName`. Add `static final Pattern PUBLIC_CLASS_PATTERN` and use it.

**M4 — `SkillLoader` `Instance<SkillProvider>` ordering**
`SkillLoader` now discovers `SkillProvider` beans via CDI `Instance<SkillProvider>`. The loading order (classpath first, then database) matters for precedence when the same skill name appears in both sources. Add a `priority()` method to `SkillProvider` (default: 0) so `ClasspathSkillProvider` returns 10 and `DatabaseSkillProvider` returns 20 (database wins on conflict — it represents user overrides of shipped skills).

---

### LOW (P70+)

**L1 — `ChannelApprovalTimeoutDispatcher` not readable (binary file in snapshot)**
Verify this class uses `ChannelPluginRegistry.find(channelType).map(ChannelPlugin::jobNotifier)` and not any `ChannelSenderRegistry` remnant or silent Telegram bias. Check post-P69 deploy.

**L2 — `SandboxCodeSafetyScanner` is not in the snapshot**
Referenced by `SandboxExecTool` but not included in the P68 dump. Verify it exists and has tests. It should be a class of `static final Pattern` checks — ensure no patterns are compiled inline.

**L3 — Public GitHub release preparation**
The project is now architecturally sound for open-source release. Pre-release checklist:
- Replace all `dev-*` placeholder secrets in `.env.example` with `CHANGE_ME` + generation instructions
- Add `CONTRIBUTING.md` pointing at `CHANNEL_SPI.md` and `Architecture_Nuances.md`
- Add `LICENSE` file
- Verify no real API keys in test fixtures

---

## Part 4 — P69 Antigravity Prompts

---

# PROMPT — PHASE 69-H1: Rename `LlmProviderFactory` in Core to Eliminate Name Collision (Tier 1 — MUST)

**Role:** Senior Java Developer  
**Task:** Phase 69-H1 — Rename `com.arcclaw.core.agent.reasoning.LlmProviderFactory` to `ReasoningModelProviderFactory`. Update all implementations and the single consumer `ChatModelProducer`. Zero behavioral change. Zero config change. Pure rename.

---

## Architecture Context and Other References
Refer `@Architecture_Nuances.md`  
Refer `@arcclaw_rg_cheatsheet.md`

**Why this matters:** Two interfaces named `LlmProviderFactory` exist in different packages. The core one (`reasoning` package) and the app one (`routing` package) serve different purposes with different method signatures. A contributor adding a new LLM provider will find both and not know which to implement. The rename makes the distinction unmissable.

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements
1. Rename only — no logic changes anywhere.
2. All 887 existing tests must remain green.
3. No `@SuppressWarnings`, no comments explaining the old name. Just clean new name.
4. Test commands use `mvn clean test` to prevent stale-class false-negatives.

---

### Component A1 — Rename the Interface

**File to rename:** `arcclaw-core/src/main/java/com/arcclaw/core/agent/reasoning/LlmProviderFactory.java`  
→ `arcclaw-core/src/main/java/com/arcclaw/core/agent/reasoning/ReasoningModelProviderFactory.java`

```java
package com.arcclaw.core.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;

/**
 * SPI for ArcClaw's core reasoning engine ChatModel.
 * <p>
 * Implementations provide the primary {@link ChatModel} used by the
 * {@link LangChainReasoningEngine} for agent thought-plan-act loops.
 * Discovered via CDI {@code Instance<ReasoningModelProviderFactory>}
 * in {@link ChatModelProducer}.
 *
 * <p><b>Not to be confused with</b>
 * {@code com.arcclaw.app.routing.LlmProviderFactory}, which provides
 * models for the intent classifier — a separate, lighter-weight model
 * with different config requirements.
 *
 * @see ChatModelProducer
 */
public interface ReasoningModelProviderFactory {
    /** The identifier used in {@code arcclaw.llm.provider} config (e.g. "groq"). */
    String providerName();
    /** Build the ChatModel instance for the reasoning engine. */
    ChatModel create();
}
```

---

### Component A2 — Update All Implementations

Three files in `arcclaw-core/src/main/java/com/arcclaw/core/agent/reasoning/`:

**`GroqProviderFactory.java`:** `implements LlmProviderFactory` → `implements ReasoningModelProviderFactory`  
**`OllamaProviderFactory.java`:** same  
**`OpenAiProviderFactory.java`:** same  

No method changes. Only the `implements` clause and the import (if explicit).

---

### Component A3 — Update `ChatModelProducer`

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/agent/reasoning/ChatModelProducer.java`

```java
// BEFORE
@Inject Instance<LlmProviderFactory> factories;

// AFTER
@Inject Instance<ReasoningModelProviderFactory> factories;

// And in chatModel():
factories.stream()
    .filter(factory -> factory.providerName().equalsIgnoreCase(provider))
    .findFirst()
    .map(ReasoningModelProviderFactory::create)
    ...
```

---

### Component A4 — Verify No Old Name Remains

```bash
rg "com\.arcclaw\.core\.agent\.reasoning\.LlmProviderFactory" --type java
# Must return 0 results

rg "implements LlmProviderFactory" arcclaw-core/src --type java
# Must return 0 results
```

**Run:** `mvn clean test -pl arcclaw-core`

---

# PROMPT — PHASE 69-H2: Complete `GovernanceStrategy` — Fix `StatusReflex` + Extend Interface (Tier 1 — MUST)

**Role:** Senior Java Developer  
**Task:** Phase 69-H2 — `StatusReflex` still injects `GovernanceLayer` directly instead of `GovernanceStrategy`. Fix by adding the four telemetry accessor methods to `GovernanceStrategy`, then update `StatusReflex` to inject the interface.

---

## Architecture Context and Other References
Refer `@Architecture_Nuances.md`  
Refer `@arcclaw_rg_cheatsheet.md`

**The gap:** `StatusReflex.execute()` calls `governanceLayer.getSessionCost()`, `.getBudgetUsd()`, `.getSessionInputTokens()`, `.getSessionOutputTokens()`. These are observable state — not internal mutable state — and belong on the interface. Currently `StatusReflex` must import and inject the concrete `GovernanceLayer` class to access them.

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements
*(same standard rules)*

---

### Component B1 — Extend `GovernanceStrategy`

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/governance/GovernanceStrategy.java`

Add four methods:

```java
public interface GovernanceStrategy {

    // --- Existing methods (unchanged) ---
    void recordLlmCall(String sessionId, int inputTokens, int outputTokens);
    void assertWithinBudget(String sessionId);
    double getSessionCost(String sessionId);
    void clearSession(String sessionId);
    double getBudgetUsd();

    // --- New telemetry accessors (required for StatusReflex) ---

    /** Total input tokens consumed by the session so far. */
    int getSessionInputTokens(String sessionId);

    /** Total output tokens consumed by the session so far. */
    int getSessionOutputTokens(String sessionId);

    /** Token budget limit (as distinct from USD budget). */
    long getTokenLimit();
}
```

`GovernanceLayer` already implements all three — no behavior change, just adding to the interface contract.

---

### Component B2 — Update `StatusReflex`

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/reflex/StatusReflex.java`

```java
// BEFORE
@Inject GovernanceLayer governanceLayer;

// AFTER
@Inject GovernanceStrategy governanceLayer;
```

Update import. No other changes — all method calls already match the interface.

---

### Component B3 — Verify

```bash
rg "@Inject GovernanceLayer\b" --type java
# Must return 0 results — all callers now inject the interface
```

**Tests:** `mvn clean test -pl arcclaw-core -Dtest="GovernanceStrategy*,StatusReflex*"`

---

# PROMPT — PHASE 69-H3: Fix `CHANNEL_SPI.md` + Update `Architecture_Nuances.md` (Tier 1 — MUST)

**Role:** Senior Technical Writer  
**Task:** Phase 69-H3 — Two documentation items are stale after P67/P68. Fix them exactly.

---

## IMPLEMENTATION REQUIREMENTS

### Component C1 — Fix `CHANNEL_SPI.md` Table

The table currently has wrong module locations. Replace with:

| Interface | Module | Responsibility | Required |
|---|---|---|---|
| `ChannelPlugin` | `arcclaw-channel-api` | Composite registration bean — wires all 5 below | Yes |
| `ChannelSender` | `arcclaw-channel-api` | Sends `List<ReplyNode>` to the channel | Yes |
| `ReplyRenderer` | `arcclaw-channel-api` | Converts `ReplyNode` tree to channel-native format | Yes |
| `ChannelAllowlist` | `arcclaw-channel-api` | Determines if a `ChannelUser` is authorized | Yes |
| `ChannelJobNotifier` | `arcclaw-channel-api` | Sends cron and approval timeout notifications | Yes |
| `ChannelDaemon` | `arcclaw-channel-api` | Daemon lifecycle: `isConfigured()`, `start()` | Yes |

Also fix the Javadoc `@link` in `ChannelPlugin.java` — it still points to `com.arcclaw.app.channel.ChannelPluginRegistry`:
```java
// BEFORE (stale)
* <p><b>Discovery:</b> {@link com.arcclaw.app.channel.ChannelPluginRegistry} ...

// AFTER (correct)
* <p><b>Discovery:</b> {@link com.arcclaw.core.channel.ChannelPluginRegistry} ...
```

### Component C2 — Add Nuances to `Architecture_Nuances.md`

Append three new nuances:

**Nuance #39** — `ChannelPluginRegistry` lives in `arcclaw-core/channel` (package `com.arcclaw.core.channel`), NOT in `arcclaw-app`. `AgentDispatchHandler` and `AllowlistHandler` in core can inject it directly. Any new handler in core that needs to resolve a channel's sender, renderer, or notifier MUST use `ChannelPluginRegistry` — never inject individual `ChannelSender`/`ReplyRenderer` beans directly.

**Nuance #40** — `ObservationTransformer` SPI: add new tool observation transformations by implementing `ObservationTransformer` as a CDI `@ApplicationScoped` bean with a `priority()` value. Lower number = runs first. Current priority chain: `TruncationTransformer(10)` → `InjectionRedactionTransformer(20)` → `PiiRedactionTransformer(30)`. Never call `ToolObservationSanitizer.sanitize()` or any individual transformer directly from tool code — only `ToolInvoker.invoke()` is the correct call site (Nuance #31 still applies).

**Nuance #41** — Two `LlmProviderFactory` SPIs exist intentionally and must never be confused:
- `com.arcclaw.core.agent.reasoning.ReasoningModelProviderFactory` — provides the main agent-loop `ChatModel`. Implement when adding a new primary reasoning engine (e.g. Anthropic Claude, DeepSeek).
- `com.arcclaw.app.routing.LlmProviderFactory` — provides the intent-classifier `ChatModel` (lighter model, different config). Implement when the intent classifier should use a different provider or model than the reasoning engine.

---

# PROMPT — PHASE 69-H4: `SessionKnowledgeBase` Write-Through to SQLite `vec_session` (Tier 1 — MUST)

**Role:** Senior Java Architect & Senior Java Developer  
**Task:** Phase 69-H4 — Make `SessionKnowledgeBase` durable. On `ingest()`, write to both `InMemoryEmbeddingStore` AND a `vec_session` SQLite table. On `@WorkspaceScoped` initialization, restore from existing `vec_session` rows for the session. Crash before compaction no longer loses session vector context.

---

## Architecture Context and Other References
Refer `@Architecture_Nuances.md`  
Refer `@arcclaw_rg_cheatsheet.md`

**Nuance #10** applies: no `ALTER TABLE`. The `vec_session` table must be created via `RagSchemaInitializer` using raw DDL (virtual table `USING vec0`), not JPA `@Entity`.

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements
1. No new dependencies — `GlobalKnowledgeBase` already uses `vec0` SQLite; reuse the same pattern.
2. Graceful degradation: if `vec0` is unavailable, `SessionKnowledgeBase` falls back to in-memory only (current behavior) with a WARN log.
3. All 887 existing tests must pass.

---

### Component D1 — Add `vec_session` Table to `RagSchemaInitializer`

**File:** `arcclaw-rag/src/main/java/com/arcclaw/rag/RagSchemaInitializer.java`

Add alongside the existing `vec_global` DDL:

```sql
CREATE VIRTUAL TABLE IF NOT EXISTS vec_session
USING vec0(
    session_id TEXT NOT NULL,
    chunk_id   TEXT NOT NULL,
    embedding  float[{dimensions}]
);
```

The `session_id` column allows efficient per-session retrieval and `DELETE FROM vec_session WHERE session_id = ?` on session teardown.

---

### Component D2 — Modify `SessionKnowledgeBase.ingest()`

**File:** `arcclaw-rag/src/main/java/com/arcclaw/rag/SessionKnowledgeBase.java`

```java
@WorkspaceScoped
public class SessionKnowledgeBase {

    @Inject EmbeddingModel embeddingModel;
    @Inject FtsConnectionInterceptor ftsInterceptor; // to check vec0 availability
    @Inject @Named("datasource") DataSource dataSource; // raw JDBC for vec0

    private InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

    public void ingest(String chunkId, String text) {
        try {
            Embedding embedding = embeddingModel.embed(text).content();
            Metadata metadata = Metadata.from("id", chunkId);
            // Always write to in-memory store
            store.add(chunkId, embedding, TextSegment.from(text, metadata));
            // Write-through to SQLite vec_session if vec0 is available
            if (ftsInterceptor.isVectorSearchEnabled()) {
                persistToVecSession(chunkId, text, embedding);
            }
        } catch (Exception e) {
            log.warnf("SessionKnowledgeBase ingest failed: %s", e.getMessage());
        }
    }

    /** Called by WorkspaceManager on session init to restore durably stored chunks. */
    public void restoreFromSqlite(String sessionId) {
        if (!ftsInterceptor.isVectorSearchEnabled()) return;
        // SELECT chunk_id, text FROM vec_session WHERE session_id = ?
        // For each row, re-embed and add to in-memory store
        // (Re-embedding is required because InMemoryEmbeddingStore stores Embedding objects,
        //  not raw vectors — we need to re-materialize from stored text)
        ...
    }

    @PreDestroy
    void destroy() {
        store = new InMemoryEmbeddingStore<>();
        // SQLite rows persist — they are cleaned up by SessionReaper.onSessionTerminated()
    }
}
```

---

### Component D3 — Cleanup on Session Termination

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/lifecycle/SessionReaper.java` (or `WorkspaceManager`)

When a session terminates, delete its `vec_session` rows:
```sql
DELETE FROM vec_session WHERE session_id = ?
```

This prevents unbounded growth of `vec_session` across thousands of sessions.

---

### Component D4 — Tests

```java
// arcclaw-rag/src/test/java/com/arcclaw/rag/SessionKnowledgeBaseTest.java

@Test
void ingestWritesToBothMemoryAndSqlite() {
    sessionKb.ingest("chunk-1", "the agent loop runs with ScopedValues");
    // Verify in-memory search finds it
    List<ScoredChunk> results = sessionKb.search("ScopedValues", 1);
    assertThat(results).hasSize(1);
    // Verify SQLite persistence (query vec_session directly via DataSource)
}

@Test
void restoreFromSqliteRecovershunksAfterRestart() {
    // Simulate: ingest → destroy (crash) → new WorkspaceScoped instance → restoreFromSqlite
    sessionKb.ingest("chunk-persist", "persistent session data");
    sessionKb.destroy(); // simulate teardown without cleanup
    // Create new instance, call restoreFromSqlite
    // Verify search finds "persistent session data"
}

@Test
void vec0UnavailableFallsBackToMemoryOnly() {
    // With isVectorSearchEnabled() returning false
    // Verify ingest still works (memory-only path)
    // Verify no SQLException is thrown
}
```

**Run:** `mvn clean test -pl arcclaw-rag -Dtest=SessionKnowledgeBase*`

---

# PROMPT — PHASE 69-M1: `SandboxExecTool` — Fix Go Workspace + Static Pattern (Tier 2 — MEDIUM)

**Role:** Senior Java Developer  
**Task:** Phase 69-M1 — Two defects in `SandboxExecTool`: (1) `executeGo()` missing `go.mod` initialization causes third-party imports to fail; (2) `extractJavaClassName()` compiles `public class` regex inline, violating Nuance #3.

---

## Architecture Context and Other References
Refer `@Architecture_Nuances.md`  
Refer `@arcclaw_rg_cheatsheet.md`

**Nuance #3** — Pattern objects are always `static final` — never compiled inside methods.

---

## IMPLEMENTATION REQUIREMENTS

### Component E1 — Fix `extractJavaClassName` Pattern

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/tools/SandboxExecTool.java`

```java
// ADD to static fields (alongside existing JAVA_CLASS_PATTERN):
private static final Pattern PUBLIC_CLASS_PATTERN =
    Pattern.compile("public\\s+class\\s+(\\w+)", Pattern.MULTILINE);

// UPDATE extractJavaClassName():
String extractJavaClassName(String code) {
    Matcher m = PUBLIC_CLASS_PATTERN.matcher(code);  // was: Pattern.compile(...).matcher(code)
    if (m.find()) return m.group(1);
    m = JAVA_CLASS_PATTERN.matcher(code);
    if (m.find()) return m.group(1);
    return "Main";
}
```

---

### Component E2 — Fix `executeGo()` — Add `go.mod`

```java
private ToolResponse executeGo(String sessionId, Path workspaceRoot,
        String code, Map<String, String> secrets) throws Exception {

    Files.createDirectories(workspaceRoot);

    // Write go.mod so 'go run' has a valid module context.
    // stdlib-only code works identically with or without go.mod.
    // Code with third-party imports needs go.mod + 'go mod tidy' (not yet supported).
    Path goMod = workspaceRoot.resolve("go.mod");
    if (!Files.exists(goMod)) {
        Files.writeString(goMod, "module arcclaw-sandbox\n\ngo 1.21\n", StandardCharsets.UTF_8);
    }

    Path goFile = workspaceRoot.resolve("main.go");
    Files.writeString(goFile, code, StandardCharsets.UTF_8);

    List<String> runCmd = List.of("go", "run", goFile.toString());
    String output = isolationExecutor.executeSandboxed(sessionId, workspaceRoot, runCmd, secrets);
    return ToolResponse.success(truncate(output));
}
```

---

### Component E3 — Tests

```java
// Add to SandboxExecToolTest.java (existing test class)

@Test
void extractJavaClassName_usesStaticPattern_notInlineCompile() throws Exception {
    // Verify PUBLIC_CLASS_PATTERN field exists and is static final
    Field f = SandboxExecTool.class.getDeclaredField("PUBLIC_CLASS_PATTERN");
    assertThat(Modifier.isStatic(f.getModifiers())).isTrue();
    assertThat(Modifier.isFinal(f.getModifiers())).isTrue();
}

@Test
void extractJavaClassName_findsPublicClass() {
    SandboxExecTool tool = new SandboxExecTool();
    assertThat(tool.extractJavaClassName("public class HelloWorld { }")).isEqualTo("HelloWorld");
    assertThat(tool.extractJavaClassName("class Anon { }")).isEqualTo("Anon");
    assertThat(tool.extractJavaClassName("no class here")).isEqualTo("Main");
}

@Test
void executeGo_writesGoModFile(@TempDir Path tmp) {
    // Verify go.mod is created in the workspace directory before go run
    // Mock isolationExecutor to capture working directory
    // Assert go.mod exists and contains "module arcclaw-sandbox"
}
```

**Run:** `mvn clean test -pl arcclaw-core -Dtest=SandboxExecTool*`

---

# PROMPT — PHASE 69-M2: `SkillProvider` — Add Priority Ordering (Tier 2 — MEDIUM)

**Role:** Senior Java Developer  
**Task:** Phase 69-M2 — `SkillLoader` discovers `SkillProvider` beans via CDI `Instance<SkillProvider>` but has no defined loading order. When the same skill name exists in both classpath and database, the winner is CDI iteration order (undefined). Add a `priority()` method so database skills (user overrides) always win over classpath skills (shipped defaults).

---

## Architecture Context and Other References
Refer `@Architecture_Nuances.md`  
Refer `@arcclaw_rg_cheatsheet.md`

---

## IMPLEMENTATION REQUIREMENTS

### Component F1 — Add `priority()` to `SkillProvider`

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/skill/SkillProvider.java`

```java
public interface SkillProvider {
    List<SkillDefinition> load();
    String sourceName();

    /**
     * Loading priority. Higher number = higher priority.
     * When two providers supply a skill with the same name, the one
     * with higher priority wins. Default: 0.
     *
     * Conventional values:
     *   10 — classpath (shipped skills, lowest precedence)
     *   20 — database  (user-defined, overrides classpath)
     *   30 — remote    (future: HTTP/S3/GitHub registry)
     */
    default int priority() { return 0; }
}
```

---

### Component F2 — Update Implementations

**`ClasspathSkillProvider.java`:** Override `priority()` to return `10`.  
**`DatabaseSkillProvider.java`:** Override `priority()` to return `20`.

---

### Component F3 — Update `SkillLoader` to Respect Priority

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/skill/SkillLoader.java`

In the loading loop, sort providers by `priority()` ascending, load each in turn, and use a `Map<String, SkillDefinition>` keyed by skill name — later providers (higher priority) overwrite earlier ones:

```java
// Load from all providers, higher priority wins on name collision
Map<String, SkillDefinition> byName = new LinkedHashMap<>();
List<SkillProvider> sorted = providers.stream()
    .sorted(Comparator.comparingInt(SkillProvider::priority))
    .toList();

for (SkillProvider provider : sorted) {
    List<SkillDefinition> loaded = provider.load();
    for (SkillDefinition def : loaded) {
        SkillDefinition existing = byName.put(def.name(), def);
        if (existing != null) {
            log.infof("SkillLoader: skill '%s' from [%s] overrides [%s] (priority %d > %d)",
                def.name(), provider.sourceName(), findSource(existing), 
                provider.priority(), findPriorityFor(existing));
        } else {
            log.debugf("SkillLoader: loaded skill '%s' from [%s]", def.name(), provider.sourceName());
        }
    }
}
```

---

### Component F4 — Tests

```java
@Test
void databaseSkillOverridesClasspathSkillWithSameName() {
    // Setup: ClasspathSkillProvider returns skill "ping" with prompt "A"
    // Setup: DatabaseSkillProvider returns skill "ping" with prompt "B"
    // Assert: registry contains "ping" with prompt "B" (database wins)
}

@Test
void classpathSkillLoadedWhenNoDbOverride() {
    // Only classpath provider returns "unique-classpath-skill"
    // Assert: registry contains it
}

@Test
void providerPriorityOrderIsRespected() {
    // Assert ClasspathSkillProvider.priority() == 10
    // Assert DatabaseSkillProvider.priority() == 20
    // Assert 20 > 10 (database beats classpath)
}
```

**Run:** `mvn clean test -pl arcclaw-core -Dtest=SkillLoader*,SkillRegistry*,SkillProvider*`

---

## P69 Execution Order

```
Day 1:    H1 — ReasoningModelProviderFactory rename (pure rename, 30 min)
          H2 — GovernanceStrategy telemetry methods + StatusReflex fix (1 hr)
          H3 — CHANNEL_SPI.md table + Architecture_Nuances.md #39-41 (2 hr)

Day 2:    H4 — SessionKnowledgeBase SQLite write-through
               (D1 schema DDL → D2 ingest + restore → D3 cleanup → D4 tests)

Day 3:    M1 — SandboxExecTool: static pattern + Go workspace fix (2 hr)
          M2 — SkillProvider priority + SkillLoader ordering (3 hr)

Day 4:    Full suite: mvn clean test
          Manual validation: Telegram + REST + skill override scenario
```

---

## P69 Success Criteria

- [ ] `rg "com\.arcclaw\.core\.agent\.reasoning\.LlmProviderFactory" --type java` → **0 results**
- [ ] `rg "@Inject GovernanceLayer\b" --type java` → **0 results** (all callers inject interface)
- [ ] `CHANNEL_SPI.md` table shows all 6 interfaces in `arcclaw-channel-api`
- [ ] `Architecture_Nuances.md` has nuances #39, #40, #41
- [ ] `SessionKnowledgeBase.ingest()` writes to `vec_session` table when vec0 available
- [ ] Session crash → restart → `restoreFromSqlite()` recovers ingested chunks
- [ ] `SandboxExecTool` has `PUBLIC_CLASS_PATTERN` as `static final`
- [ ] `executeGo()` writes `go.mod` before `go run`
- [ ] `SkillProvider.priority()` exists; DB skills (priority 20) override classpath (priority 10)
- [ ] All 887+ tests pass

---

## Post-P69 Projected Score

| Dimension | P68 | P69 Target |
|---|---|---|
| Governance SPI | 90% | **100%** — `StatusReflex` injecting interface |
| Documentation | 75% | **92%** — `CHANNEL_SPI.md` + 3 new nuances |
| RAG Durability | 0% | **80%** — `SessionKnowledgeBase` write-through |
| SandboxExec | 85% | **95%** — Go workspace + static pattern |
| Skill Extensibility | 90% | **98%** — priority-ordered loading |
| **Overall Maturity** | **9.8/10** | **9.95/10** |

After P69, ArcClaw reaches effectively complete architectural maturity. Every SPI is clean, every caller uses interfaces, documentation matches code, and the last major data durability gap closes. What remains after P69 is purely feature work (new channels, new tools, new embedding providers) — not framework debt.
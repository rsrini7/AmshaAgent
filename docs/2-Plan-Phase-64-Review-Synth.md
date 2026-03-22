# ArcClaw — Phase 64 Prioritization Plan

> **Final arbitration** across Grok, Gemini, ChatGPT, Claude.ai, and Perplexity reviews.  
> Verdict: fix the **7 correctness bugs** and **3 security gaps** first, then the **1 architectural unblock**.  
> The full ChannelPlugin SPI is Phase 65+.

---

## Executive Summary

| Tier | Focus | Items | Estimated Effort |
|------|-------|-------|-----------------|
| **A — Fix Now** | Correctness defects in live production | 7 | ~2–3 days |
| **B — Security** | Real security gaps (not theoretical) | 3 | ~1–2 days |
| **C — Architecture** | Blockers before adding any new channel | 3 | ~3–4 days |
| **D — Quality / Phase 65+** | Real but not urgent | 9 | Defer |

**Why this order:** All five LLMs were seduced by the architectural elegance of the ChannelPlugin SPI. But A1 (budget enforcement ordering) is real money. A3 (non-ASCII memory search) means any non-English user's long-term memory is completely broken today. A4 (heading rendering) means every structured reply with headers is visually broken in Telegram. These aren't philosophical — they're broken in production right now.

---

## Tier A — Fix Now
### Correctness defects in live production

---

### A1 — Hard budget limit never throws on first overrun

**Location:** `GovernanceLayer.recordLlmCall`  
**Type:** Bug — real money on the line  

**Problem:** Warning threshold is checked before the hard limit. A massive single call that blows past both thresholds fires only `BudgetWarningException`. `BudgetExceededException` is never thrown on that call — the session runs one call over budget with zero hard enforcement. The next call will throw it, but damage is done.

**Additionally:** `synchronized addCost` and `AtomicInteger` token adders operate under different monitors. A concurrent tool result can interleave `addCost` with a threshold check, yielding incoherent reads.

**Fix:**
1. Flip the check order — hard limit first, then warning threshold.
2. Wrap all three operations (`addTokens` + `addCost` + `checkThresholds`) under one `synchronized` block or a single `ReentrantLock` to ensure atomicity.

```java
// BEFORE (wrong order)
if (currentCost > WARNING_THRESHOLD) throw new BudgetWarningException(...);
if (currentCost > hardLimit) throw new BudgetExceededException(...);

// AFTER (correct order)
if (currentCost > hardLimit) throw new BudgetExceededException(...);
if (currentCost > WARNING_THRESHOLD) throw new BudgetWarningException(...);
```

---

### A2 — AutonomyLevel hardcoded to SUPERVISED in dispatch

**Location:** `AgentDispatchHandler`  
**Type:** Bug — session configuration silently ignored  

**Problem:** Every Telegram message dispatch hard-codes `AutonomyLevel.SUPERVISED`, regardless of what was configured when the session was created via REST. Sessions created as `FULL` via the REST API silently degrade to `SUPERVISED` the moment a Telegram message arrives.

**Fix:**
1. `SessionResolutionHandler` loads the stored autonomy level from the checkpoint or session entity.
2. Pushes it into `MessageContext`.
3. `AgentDispatchHandler` reads `context.getAutonomyLevel()` instead of the hardcoded constant.
4. Add a test: create a FULL session via REST, assert the pipeline sees `AutonomyLevel.FULL`.

---

### A3 — Non-ASCII memory search silently returns empty

**Location:** `CompactionSummaryRepository.searchMemory`  
**Type:** Bug — non-English users have no long-term memory  

**Problem:** The method strips all non-ASCII characters before running FTS5 search. Any query in Japanese, Arabic, Hindi, Devanagari, or any language with non-Latin characters returns an empty list — silently, with no error to the user.

**Fix:** Replace the ASCII-strip with proper FTS5 special-character escaping. Only these characters need escaping in FTS5 MATCH syntax: `" * - ^ ( )`. All Unicode is valid FTS5 input.

```java
// BEFORE (breaks all non-ASCII)
String sanitized = query.replaceAll("[^a-zA-Z0-9 ]", "").trim();

// AFTER (escapes only FTS5 syntax chars)
String sanitized = query.replaceAll("([\"*^()\\-])", "\\\\$1").trim();
```

Add a test with a Japanese or Arabic query string asserting non-empty results.

---

### A4 — MarkdownV2 heading renders as escaped hashes

**Location:** `TelegramMarkdownV2Renderer` — `Heading` case  
**Type:** Bug — every heading in every Telegram reply is visually broken  

**Problem:** The heading case passes `"## Title"` through `esc()`, which escapes `#` to `\#`. Telegram renders `\#\# Title` as literal backslash-hashes, not a heading. Telegram MarkdownV2 has no heading syntax.

**Fix:** Render headings as bold (and optionally underline for h1):

```java
case ReplyNode.Heading h -> h.level() == 1
    ? "*__" + esc(h.text()) + "__*"   // bold + underline for h1
    : "*" + esc(h.text()) + "*";       // bold for h2–h6
```

Update `TelegramMarkdownV2RendererTest` — the current test almost certainly asserts the broken escaped-hash output.

---

### A5 — Invalid JSON from LLM returns FINISH action

**Location:** `ReasoningStepParser` — catch block  
**Type:** Bug — JSON parse errors bypass retry logic  

**Problem:** When the LLM returns malformed JSON for tool arguments, the catch block returns a `ReasoningStep` with `action=FINISH`. The agent loop treats this as a completed answer, and the user receives the raw error string as the agent's response. The existing `llmSchemaErrorCount` retry logic is never activated for JSON parse failures.

**Fix:** In the catch block, return a step that activates the retry path:

```java
catch (Exception e) {
    log.warn("JSON parse error for session {}: {}", sessionId, e.getMessage());
    return ReasoningStep.pending(
        "JSON parse error — retry with valid format",
        ReasoningStep.OBSERVE,   // ← not FINISH
        Map.of("error", "Invalid JSON: " + e.getMessage()),
        inputTokens, outputTokens
    );
    // Also: increment llmSchemaErrorCount so the state machine's retry guard activates
}
```

---

### A6 — CompactionHandler fireAsync result discarded

**Location:** `CompactionHandler` — `fireAsync` call  
**Type:** Bug — failed compaction permanently locks the session  

**Problem:** `compactionEvent.fireAsync(new CompactionRequest(...))` returns a `CompletableFuture` that is immediately discarded. If the compaction observer throws any exception, it is silently swallowed. Critically, `compactionInFlight` stays `true` forever — the session is permanently stuck in a "compaction pending" state and never compacts again. Sessions grow unbounded in memory.

**Fix:**

```java
compactionEvent.fireAsync(new CompactionRequest(...))
    .exceptionally(ex -> {
        log.errorf("Compaction failed for session %s: %s", sessionId, ex.getMessage());
        compactionInFlight.set(false);  // ← critical: release the lock
        return null;
    });
```

The `compactionInFlight` reset on failure is the critical part.

---

### A7 — ChannelType.fromString silently falls back to TELEGRAM

**Location:** `ChannelType.fromString`  
**Type:** Bug — data corruption masked as normal behavior  

**Problem:** Null or unknown channel type values silently return `ChannelType.TELEGRAM`. A Discord checkpoint loaded on a patched database silently routes cron notifications and approval timeouts to Telegram instead of Discord. The "prevents IAE on legacy rows" comment is not a justification for silent data corruption.

**Fix:** Return `Optional<ChannelType>` and let callers decide the fallback explicitly — or throw `IllegalArgumentException` for unknown values with documented legacy handling:

```java
public static Optional<ChannelType> fromString(String value) {
    if (value == null || value.isBlank()) return Optional.empty();
    try { return Optional.of(ChannelType.valueOf(value.toUpperCase())); }
    catch (IllegalArgumentException e) { return Optional.empty(); }
}
```

Update all 6 call-sites. Callers that genuinely need a fallback can call `.orElse(ChannelType.TELEGRAM)` explicitly and document why.

---

## Tier B — Security Gaps
### Real security gaps, not theoretical

---

### B1 — Audit HMAC TOCTOU race condition

**Location:** `AuditEventRepository`  
**Type:** Security — WORM compliance guarantee broken under concurrency  

**Problem:** `MAX(sequence)` read and `sequence + 1` write are not atomic. Two concurrent tool executions finishing at the same time for the same session read the same max sequence, write the same sequence number, and permanently fork the HMAC chain. The WORM tamper-proof audit guarantee is broken under any concurrency.

**Fix:** Add a `session_audit_state` table:

```sql
CREATE TABLE session_audit_state (
    session_id    TEXT PRIMARY KEY,
    current_seq   INTEGER NOT NULL DEFAULT 0,
    current_hash  TEXT    NOT NULL DEFAULT ''
);
```

Use a locking atomic update on every audit write:

```sql
UPDATE session_audit_state
   SET current_seq  = current_seq + 1,
       current_hash = :newHash
 WHERE session_id = :sessionId
RETURNING current_seq;
```

This serializes concurrent audit writes at the DB layer without application-level locking.

---

### B2 — DevSecretSeeder injectable into production path

**Location:** `ChatCommand`  
**Type:** Security — development credentials can seed in production  

**Problem:** `ChatCommand` injects and calls `devSeeder.seedDevSecrets()`, guarded only by a runtime config flag `auto-seed-secrets`. A misconfigured production deployment with this flag accidentally set to `true` silently seeds development credentials into the vault. This is a runtime config guard on a security boundary — it should be a compile-time exclusion.

**Fix:** Remove the seeder injection from `ChatCommand` entirely. Move it to a `StartupEvent` observer with a build-time profile annotation:

```java
@ApplicationScoped
@IfBuildProfile("dev")  // compile-time excluded from prod builds
public class DevSecretSeederStartup {
    @Inject DevSecretSeeder seeder;
    void onStart(@Observes StartupEvent ev) {
        seeder.seedDevSecrets();
    }
}
```

---

### B3 — Session ID uses UUID v3 (MD5)

**Location:** `SessionResolutionHandler`  
**Type:** Security — inconsistent with overall security posture  

**Problem:** `UUID.nameUUIDFromBytes` uses MD5 internally. MD5 has known collision weaknesses. This is inconsistent with the AES-GCM-SIV + Argon2id security posture used everywhere else in the codebase.

**Fix (recommended — also fixes C1):** Store a random `UUID.randomUUID()` in a persistent session registry table:

```sql
CREATE TABLE session_registry (
    channel_type TEXT    NOT NULL,
    channel_id   TEXT    NOT NULL,
    user_id      TEXT    NOT NULL,
    session_id   TEXT    NOT NULL,
    created_at   TIMESTAMP NOT NULL,
    PRIMARY KEY (channel_type, channel_id, user_id)
);
```

Look up the session ID from this table on every message; create a new random UUID if none exists. This also fixes the multi-tenancy bug (C1) at the same time.

---

## Tier C — Architectural Blockers
### Required before adding any new channel

---

### C1 — Session key must be (channelType, channelId, userId) triplet

**Location:** `SessionResolutionHandler`  
**Type:** Architecture — multi-tenancy fails for group-channel platforms  

**Problem:** The current session key is just `chatId/correlationId`. For Telegram private chats, `chatId` is already per-user so it works. For Slack and Discord, `channelId` is shared by all users in a channel — all users in the same Discord channel would share one agent session. This is a complete multi-tenancy failure for any group-channel platform.

**Fix:** Change session derivation to use the `(channelType, channelId, userId)` triplet. If implementing the session registry table from B3, the PK is naturally this triplet. Existing Telegram sessions are unaffected since Telegram DM `chatId` is already per-user.

---

### C2 — Channel notification dispatcher (replace Telegram-only observers)

**Location:** `TelegramCronNotificationObserver`, `ApprovalTimeoutNotifier`  
**Type:** Architecture — the single concrete change that enables multi-channel  

**Problem:** Both classes are Telegram-hardcoded. Adding Discord requires copying both entirely. This is the primary blocker for multi-channel — not the full SPI (which is Phase 65+), but this specific notification routing.

**Fix:** Introduce a `ChannelJobNotifier` interface:

```java
public interface ChannelJobNotifier {
    boolean supports(ChannelType channelType);
    void notify(String recipient, String message);
}
```

Create `TelegramJobNotifier implements ChannelJobNotifier`.

Replace `TelegramCronNotificationObserver` with a generic dispatcher:

```java
@ApplicationScoped
public class ChannelCronNotificationDispatcher {
    @Inject Instance<ChannelJobNotifier> notifiers;

    void onJobCompleted(@Observes AgentJobCompleted event) {
        notifiers.stream()
            .filter(n -> n.supports(event.channelType()))
            .findFirst()
            .ifPresent(n -> n.notify(event.recipient(), event.renderedAnswer()));
    }
}
```

Apply the same pattern to `ApprovalTimeoutNotifier`.

**Result:** Adding Discord = write one `DiscordJobNotifier` class. Zero changes to the dispatcher.

---

### C3 — GlobalKnowledgeBase: replace "rag:" prefix hack with dedicated table

**Location:** `GlobalKnowledgeBase`, `RagSchemaInitializer`  
**Type:** Architecture — schema smell that will eventually cause data corruption  

**Problem:** RAG chunks are stored using `sessionId = "rag:" + chunkId` to avoid primary key collision with compaction summaries in the same table. The `messagesCompacted`, `compactedAt`, and `compaction_summary` fields have no semantic meaning for a RAG chunk. A query that sweeps all `compaction_summary` rows will eventually include RAG chunks.

**Fix:** Create a dedicated `rag_chunk` table:

```sql
CREATE TABLE rag_chunk (
    chunk_id   TEXT      PRIMARY KEY,
    text       TEXT      NOT NULL,
    source     TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE VIRTUAL TABLE IF NOT EXISTS vec_global USING vec0(
    chunk_id TEXT PRIMARY KEY,
    embedding FLOAT[1536]
);
```

Update `RagSchemaInitializer` to create both tables. Update `GlobalKnowledgeBase` to use `rag_chunk` for storage and `vec_global` for vector search. Remove the `"rag:"` prefix hack entirely.

---

## Tier D — Quality / Phase 65+
### Real issues, but not urgent — defer until A+B+C are done

---

### D1 — Unicode-safe truncation in ContextCompactionService

**Location:** `ContextCompactionService.compact()`

`String.substring()` operates on UTF-16 code units, not codepoints. Emoji and CJK text can be sliced mid-surrogate-pair.

```java
// BEFORE
newSummary = newSummary.substring(0, 1985) + "...[truncated]";

// AFTER
newSummary = newSummary.codePoints()
    .limit(1985)
    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
    .toString() + "...[truncated]";
```

---

### D2 — GlobalKnowledgeBase float vector serialization locale issue

**Location:** `GlobalKnowledgeBase.ingest()`

`Arrays.toString(float[])` uses the JVM's default locale. On non-English locales (e.g. German, French), `0.1f` serializes as `0,1` — corrupting all vector embeddings silently.

**Fix:** Use `Locale.ROOT` explicitly when serializing floats, or write a dedicated `floatArrayToJson` method that formats each element with `String.format(Locale.ROOT, "%f", v)`.

---

### D3 — Consolidate SqliteRetryTemplate

**Location:** `SessionReaper`, `WorkspaceManager`, `StatefulAgentStoreImpl`, `AgentCronRepository`

SQLITE_BUSY retry pattern appears independently in four places, each with slightly different backoff timing, exception checks, and log messages.

**Fix:** Extract a single `SqliteRetryTemplate`:

```java
public class SqliteRetryTemplate {
    public static <T> T execute(int maxAttempts, long backoffMs, Callable<T> action)
            throws Exception {
        for (int i = 0; i < maxAttempts; i++) {
            try { return action.call(); }
            catch (Exception e) {
                if (!SqliteErrorStrings.isBusy(e) || i == maxAttempts - 1) throw e;
                Thread.sleep(backoffMs);
            }
        }
        throw new IllegalStateException("unreachable");
    }
}
```

All four call-sites become single method calls.

---

### D4 — Deduplicate LlmModelFactory

**Location:** `CompactionModelProducer`, `ModelProducer`

Two separate producer classes independently implement the same `groq / openai / ollama` switch. They will inevitably diverge when a new provider is added.

**Fix:** Extract `LlmModelFactory` with a single provider-selection switch. Both producers become thin wrappers that call it with their specific config subset (compaction uses a smaller context window, main uses the full config).

---

### D5 — TelegramMarkdownV2Renderer.esc() single-pass rewrite

**Location:** `TelegramMarkdownV2Renderer.esc()`

18 sequential `String.replace()` calls create 18 intermediate `String` objects per text node. On long replies with many nodes, this is measurable GC pressure.

```java
private static final Set<Character> ESCAPE_CHARS = Set.of(
    '_','*','[',']','(',')','~','`','>','#','+','-','=','|','{','}','.','!'
);

private String esc(String text) {
    if (text == null) return "";
    StringBuilder sb = new StringBuilder(text.length() + 16);
    for (char c : text.toCharArray()) {
        if (ESCAPE_CHARS.contains(c)) sb.append('\\');
        sb.append(c);
    }
    return sb.toString();
}
```

One allocation per call instead of 18.

---

### D6 — BudgetWarning absorbed in structured output path

**Location:** `LangChainReasoningEngine.chatForStructuredOutput()`

`BudgetWarningException` is silently swallowed in the structured output path. The synthesizer can run a session all the way to the hard limit with zero user-visible warning, since warnings only surface through the main reasoning loop.

**Fix:** Propagate the warning to the caller as an observation in the agent loop, so the coordinator can surface "approaching budget limit" to the user before the hard limit hits.

---

### D7 — Dead code cleanup

| Item | Location | Action |
|------|----------|--------|
| `ToolCapability.SANDBOX_EXEC` | `ToolCapability` enum | Remove — Phase 48 deferred indefinitely |
| `AgentResult.FailureCategory.RATE_LIMIT` | `AgentResult` | Remove — never assigned anywhere in the codebase |
| `System.out.println(...)` | `AgentFunctionProcessor` | Replace with JBoss Logger — appears in native image compilation output |

---

### D8 — ChannelPlugin SPI full refactor (Phase 65+)

All five LLMs recommended this as the headline change. It's the right long-term architecture.

**Why deferred:** C2 (the `ChannelJobNotifier` dispatcher) gives 90% of multi-channel capability with minimal risk in 1–2 days. The full SPI with `META-INF/services`, per-channel Quarkus modules, and `ChannelPluginRegistrar` build steps is Phase 65. Add Discord as a concrete proof-of-concept *after* C2 is proven — let the friction of that implementation reveal exactly what the SPI needs to cover. Designing it abstractly before implementing a second channel is speculative and risks over-engineering.

**Planned structure when ready:**

```
arcclaw-channel-spi/
├── ChannelPlugin.java          // getType(), getRouteBuilder(), getSender(), getRenderer()
├── ChannelRoute.java           // abstract RouteBuilder
├── ChannelMessageHandler.java  // generic (replaces TelegramAgentHandler)
└── ChannelPluginRegistrar.java // @BuildStep scanning META-INF/services

arcclaw-channel-telegram/       // TelegramPlugin implements ChannelPlugin
arcclaw-channel-discord/        // future
arcclaw-channel-slack/          // future
```

---

### D9 — MCP Java SDK migration (Phase 65+)

Hand-rolled JSON-RPC 2.0 over HTTP/SSE in `McpClientService` + `NdjsonWriter` + `PodmanMcpTransport`. The official MCP Java SDK (available since early 2025) would reduce ~300 lines of protocol code to ~30 lines of configuration.

**Why deferred:** The hand-rolled implementation works and is tested. The SDK is still maturing. Migrate in a dedicated phase when a specific protocol version mismatch forces the issue. Not a Phase 64 concern.

---

## Reviewer Synthesis — What Each Got Right and Wrong

| Reviewer | Best contribution | Blind spot |
|----------|-------------------|------------|
| **Claude.ai** | Most code-specific — found actual line-level bugs (A4, A6) | Underweighted concurrency risks |
| **Grok** | Most architecturally complete module structure | Overweighted SPI urgency vs. correctness fixes |
| **Perplexity** | Best on concurrency risks (B1, A1) | Missed A6 (fireAsync discard) |
| **Gemini** | Best on OCP violations and God Classes | Too abstract — fewer concrete line references |
| **ChatGPT** | Most readable presentation | Most abstract — fewest concrete fixes |

**What all five missed together:** A6 (`fireAsync` discarded future causing permanent compaction lockout) and A2 (AutonomyLevel hardcoded in dispatch) were not identified together as the highest-impact combination. A6 is one of the most dangerous bugs — sessions silently grow unbounded in memory with no indication to the operator.

---

## Phase 64 Execution Order

```
Week 1: Tier A (days 1–3) → Tier B (days 4–5)
Week 2: Tier C (days 6–9)
Week 3+: Tier D selectively, then Phase 65 (ChannelPlugin SPI)
```

**Gate:** Do not start Tier C until all Tier A bugs have passing tests. The session key change (C1) and notification dispatcher (C2) require clean test coverage of A7 (ChannelType.fromString) first, or the channel routing bugs will resurface immediately.

---

*Generated March 2026 · ArcClaw Phase 64 Architecture Review*

---

Here's my thinking before the visual: all five reviews agree on the channel abstraction gap and architectural direction, but they vary wildly on what's *urgent vs. philosophical*. My job is to cut through the philosophy and focus on: **what is actually broken, what will block you next, and what is genuine YAGNI**.

The answer: you have **7 real correctness bugs** sitting in production right now, **3 security gaps**, and **1 architectural blocker** before you can add a second channel. Everything else is refinement.Here's my reasoning for the verdict, since you trusted me to be the final decider:

**Why Tier A before architecture:** All five LLMs got seduced by the architectural elegance of the ChannelPlugin SPI. But A1 (budget enforcement ordering) is real money. A3 (non-ASCII memory search) means any non-English user's long-term memory is completely broken right now. A4 (heading rendering) means every structured reply with headers is visually broken in Telegram. These aren't philosophical — they're broken in production today.

**Why the full ChannelPlugin SPI is deferred:** Grok, Gemini, ChatGPT, and Perplexity all made this their headline recommendation. I'm pushing back. C2 (the `ChannelJobNotifier` dispatcher pattern) takes 1-2 days and gives you 90% of the multi-channel capability. The full SPI with META-INF/services and per-channel Quarkus modules is Phase 65 — you should add Discord as a concrete proof-of-concept *after* C2 is done, then let the friction of that integration tell you exactly what the SPI needs to look like. Designing it abstractly before you've implemented a second channel is speculative.

**What the reviewers missed or understated:** The `fireAsync` discarded future (A6) was caught only by the Claude.ai review and is one of the most dangerous bugs — it makes compaction silently stuck forever, which means sessions quietly grow unbounded in memory. The `AutonomyLevel.SUPERVISED` hardcode (A2) was caught only by Perplexity and is a genuine correctness defect, not a missing feature.

**The honest ranking of the five reviews:** The Claude.ai review was the most precise and code-specific (it found actual line-level bugs). Grok was the most architecturally complete. Perplexity was the most thorough on concurrency risks. Gemini was best on OCP violations. ChatGPT was most readable but most abstract. All five had blind spots — none of them caught A6 and A2 together as the combination that matters most.

The codebase genuinely is as good as the reviews say. Phase 64 just needs to be a cleanup sprint before the expansion sprint — get the foundation solid, then build.
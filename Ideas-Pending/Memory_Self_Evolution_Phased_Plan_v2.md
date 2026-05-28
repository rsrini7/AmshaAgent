# Memory Tiered Architecture & Self-Evolution — Phased Implementation Plan

> **Status:** v2.2-implemented — Phase 0 ✅ complete (24 files, 3 ISP ports, CDI producer, GraalVM reflection)
> **Backlog Ref:** `Ideas-Pending/BACKLOG.md` Item #8
> **Input Docs:** `MemGPT-Style.md`, `Self-Evolution.md`, `Memory_Self_Evoluton_Plan.md` (v1.2), `Memory_self_Review.md`
> **Date:** 2026-05-24

---

## What Changed in v2.1

Five architecture decisions added to v2.0 based on implementation review:

| # | Decision | Affected Phases | Rationale |
|---|----------|----------------|-----------|
| 1 | **Recall dedupe is DB-enforced**, not app-check-based | Phase 2, §16.1 | Application-level checks are race-prone; unique constraints + `INSERT OR IGNORE` guarantee correctness |
| 2 | **Recall exact search excludes compaction summaries by default** | Phase 3 | Summary records pollute exact code/error retrieval; separate `include_summaries` mode for overview queries |
| 3 | **Archival is tool-first, never unconditional** | Phases 4, 6 | `ChatHistoryAssembler` must remain O(1); passive injection only via pre-fetched cache with strict latency bounds |
| 4 | **SQLite vector writes are serialized** | Phase 5, §14.2 | sqlite-vec is not thread-safe for concurrent writes; serialize ingestion, allow concurrent reads |
| 5 | **Synthesized directives use shadow lifecycle** | Phase 10, §13.3 | New directives start as `SHADOW`, evaluated without behavior change; promotion to `ACTIVE` requires measurable success |

These corrections prevent race conditions, search pollution, prompt bloat, database corruption, and premature directive activation. See [Section 19: v2.1 Tactical Corrections](#19-v21-tactical-corrections) for detailed summary.

### v2.2 Phase 0 Implementation Refinements

| # | Refinement | Source | Rationale |
|---|-----------|--------|-----------|
| ISP | God-port split into `CoreMemoryStorePort`, `RecallMemoryStorePort`, `ArchivalMemoryStorePort` | Phase 0 code review | Backends implement only tiers they support; no `UnsupportedOperationException` stubs |
| QO | `RecallSearchQuery` and `ArchivalSearchQuery` records introduced | Phase 0 code review | Eliminates 7-parameter method anti-pattern; prevents boolean blindness |
| CDI | `TieredMemoryBackendSelector` replaced by `TieredMemoryProducer` with `@Produces` | Phase 0 code review | Zero CDI lookup overhead on hot path; resolves backends once at startup |
| RFL | `TieredMemoryReflectionConfig` placed in `arcclaw-persistence-api` | Phase 0 code review | GraalVM registration lives where DTOs are defined; avoids cross-module compilation issues |

---

## Executive Summary

This document replaces the original 5-phase plan with a **13-phase (0-12) incremental implementation** based on detailed codebase review. The critical insight: **ArcClaw already has the right seams** (`CompactionService`, `CompactionEventBridge`, `PersistentChatMemoryStore`, `ChatHistoryAssembler`, `ReasoningMessageAssembler`, `MemorySearchTool`, `MemoryRetrievalPort`) — we refactor rather than rewrite.

**Phase 0 Status:** ✅ COMPLETE (24 files, 3 ISP-compliant ports, CDI `@Produces`, GraalVM reflection, 22 config keys all defaulting to `false`). See §3 for full implementation details.

**Core Principles:**
1. **No migration required** — ArcClaw is in development; storage can be dropped/recreated
2. **Workflow preservation** — existing agent loop, compaction, and tool execution remain functional throughout
3. **Read-before-write** — stabilize retrieval paths before exposing mutation tools
4. **Persistence-before-search** — stop data loss first (Phase 2), make searchable later (Phase 3)
5. **Offline self-evolution** — trace capture (Phase 8) → extraction (Phase 9) → synthesis (Phase 10)

---

## Table of Contents

1. [Architecture Context](#1-architecture-context)
2. [Current Codebase Seams](#2-current-codebase-seams)
3. [Phase 0: Contracts & Config (Inert Foundation)](#3-phase-0-contracts--config-inert-foundation)
4. [Phase 1: Core Memory Only](#4-phase-1-core-memory-only)
5. [Phase 2: Recall Persistence (No Search Yet)](#5-phase-2-recall-persistence-no-search-yet)
6. [Phase 3: Recall Search + Tool Split](#6-phase-3-recall-search--tool-split)
7. [Phase 4: Prompt Budget Hardening](#7-phase-4-prompt-budget-hardening)
8. [Phase 5: Archival Storage Model](#8-phase-5-archival-storage-model)
9. [Phase 6: Archival Tools + Selective Use](#9-phase-6-archival-tools--selective-use)
10. [Phase 7: Controlled Write Paths](#10-phase-7-controlled-write-paths)
11. [Phase 8: Self-Evolve Trace Capture](#11-phase-8-self-evolve-trace-capture)
12. [Phase 9: Rule-Based Extraction](#12-phase-9-rule-based-extraction)
13. [Phase 10: Directive Synthesis](#13-phase-10-directive-synthesis)
14. [Phase 11: Backend Parity](#14-phase-11-backend-parity)
15. [Phase 12: Hardening & Cleanup](#15-phase-12-hardening--cleanup)
16. [Critical Implementation Warnings](#16-critical-implementation-warnings)
17. [Module Mapping](#17-module-mapping)
18. [Verification Summary](#18-verification-summary)
19. [v2.1 Tactical Corrections](#19-v21-tactical-corrections)

---

## 1. Architecture Context

### 1.1 Why This Plan Differs from v1.2

| Aspect | v1.2 Plan | v2.0 Plan (This Document) |
|---|---|---|
| **Phases** | 5 major phases | 13 granular phases (0-12) |
| **Migration** | Removed (dev environment) | Explicitly clean-build; storage recreation OK |
| **Recall** | Persistence + search together | **Persistence first (Phase 2), search later (Phase 3)** |
| **Prompt Integration** | Single step | **Dedicated Phase 4** to prevent bloat |
| **Write Tools** | Early exposure | **Delayed until Phase 7** (after reads stable) |
| **Self-Evolve** | 3 phases | **3 phases (8-10): capture → extract → synthesize** |
| **Backend Parity** | Phase 4 | **Phase 11** (semantics first, parity second) |

### 1.2 Guiding Insight

The current codebase already has strong reusable seams:
- `CompactionService` + `CompactionEventBridge` → natural insertion point for Recall preservation
- `PersistentChatMemoryStore` → exact-message persistence layer
- `ChatHistoryAssembler` + `ReasoningMessageAssembler` → prompt assembly path for memory injection
- `MemorySearchTool` + `MemoryRetrievalPort` → Archival reuse base
- `MemorySearchTool` currently merges compaction-summary search with RAG → confirms need to split Recall vs Archival

**Phase 0 Implementation Refinement (ISP):** The single `TieredMemoryStorePort` specified in v2.1 was split into three Interface Segregation Principle (ISP)-compliant ports: `CoreMemoryStorePort`, `RecallMemoryStorePort`, and `ArchivalMemoryStorePort`. This allows backends to implement only the tiers they support, and services to inject only the ports they need. The `TieredMemoryProducer` resolves each port independently via CDI `@TieredBackend` qualifier, returning `NoOp*` stubs when tiers are disabled.

---

## 2. Current Codebase Seams

### 2.1 Compaction Flow (Recall Preservation Point)

```
CompactionHandler (priority 60)
    ├─ shouldCompact() → CAS guard
    ├─ CompactionEventBridge.registerLatch()
    └─ publishAsync(CompactionRequest)
        └─ CompactionService.compact()
            ├─ Split: toCompact (N-keepRecent) + toKeep (last keepRecent)
            ├─ [NEW PHASE 2] Persist toCompact to Recall BEFORE summarizing
            ├─ Call compaction LLM
            ├─ [NEW PHASE 2] Store summary as COMPACTION_SUMMARY record in Recall
            └─ PersistentChatMemoryStore.replace(toKeep only)
```

**Key Point:** Compaction behavior unchanged except raw messages become canonical preserved history.

### 2.2 Message Persistence Flow (Recall Dual-Write)

```
Agent responds to user
    │
PersistentChatMemoryStore.updateMessages()
    ├─ Existing: chatMemoryStorePort.save(...)
    └─ [NEW PHASE 2] RecallMemoryService.insertAsync(...)
```

**Idempotency Warning:** Must prevent dual-writing same message from both normal flow and compaction flow. See Section 16.1.

### 2.3 Prompt Assembly Flow (Memory Injection)

```
ReasoningMessageAssembler.assemble()
    └─ ChatHistoryAssembler.assemble()
        ├─ [PHASE 1] CoreMemoryService.renderForPrompt() → [USER CONTEXT]
        ├─ CompactionSummaryCache.getSummary() → [CONTEXT FROM PRIOR SESSION]
        ├─ [PHASE 6] Pre-fetched archival context (if available, O(1) lookup) → [RELEVANT KNOWLEDGE]
        ├─ [PHASE 10] SelfEvolveService.buildDirectives() → [BEHAVIORAL DIRECTIVES] (SHADOW-mode evaluation)
        └─ PersistentChatMemoryStore.getMessages() → recent turns
```

**Phase 4 Critical:** Explicit budget ordering prevents memory bloat and section conflicts.

**v2.1 Correction:** `ChatHistoryAssembler` does **not** trigger archival retrieval I/O. Archival results must be pre-fetched via `archival_memory_search` tool or session-context gateway before prompt assembly begins.

### 2.4 Existing Memory Search (To Be Split)

```
MemorySearchTool.execute()
    ├─ Tier A: CompactionStorePort.search() → FTS on summaries
    └─ Tier B: MemoryRetrievalPort.search() → RAG/vector chunks

[PHASE 3] Split into:
    ├─ RecallMemorySearchTool → exact session history (FTS5)
    └─ ArchivalMemorySearchTool → semantic knowledge (BM25+vector)
```

---

## 3. Phase 0: Contracts & Config (Inert Foundation) ✅ COMPLETE

> **Status:** ✅ COMPLETE — `./mvnw clean compile` passes, all 22 feature flags default to `false`.

**Goal:** Define segregated ports, DTOs, query objects, typed config, CDI producer, NoOp stores, and GraalVM reflection — zero runtime behavior change.

### 3.1 ISP-Compliant Port Architecture

The plan originally specified a single `TieredMemoryStorePort` (107-line god-interface). The **actual implementation** splits it into **three segregated ports** following the Interface Segregation Principle:

| Port | File | Methods |
|---|---|---|
| `CoreMemoryStorePort` | `CoreMemoryStorePort.java` | `read`, `upsert` (CAS), `delete`, `get` |
| `RecallMemoryStorePort` | `RecallMemoryStorePort.java` | `insertOrIgnore` (DB-enforced idempotency), `insertBatch`, `search(RecallSearchQuery)`, `getById`, `purgeExpired` |
| `ArchivalMemoryStorePort` | `ArchivalMemoryStorePort.java` | `upsert`, `search(ArchivalSearchQuery)`, `getById`, `incrementAccessCount`, `delete`, `evictLowConfidence` |

**Architectural Win:** A backend can implement `CoreMemoryStorePort` + `RecallMemoryStorePort` but leave `ArchivalMemoryStorePort` unsatisfied if vector search is disabled.

### 3.2 Query Objects (No Parameter Bloat)

| Record | Fields | Helper |
|---|---|---|
| `RecallSearchQuery` | `userId`, `query`, `sessionId` (null=cross-session), `limit`, `includeSummaries`, `messageTypes` | `isCrossSession()` |
| `ArchivalSearchQuery` | `userId`, `query`, `limit`, `chunkTypes`, `useVector`, `useBM25` | — |

### 3.3 Complete Deliverables Table

| Component | Module | File |
|---|---|---|
| `CoreMemoryBlockDto` | `arcclaw-persistence-api` | record |
| `RecallMessageDto` | `arcclaw-persistence-api` | record (documented fields: `contentHash`, `messageType`, `metadataJson`) |
| `ArchivalChunkDto` | `arcclaw-persistence-api` | record (documented fields: `chunkType`, `tags`, `confidence`, `accessCount`) |
| `TieredMemoryHealthDto` | `arcclaw-persistence-api` | record |
| `RecallSearchQuery` | `arcclaw-persistence-api` | record |
| `ArchivalSearchQuery` | `arcclaw-persistence-api` | record |
| `VersionConflictException` | `arcclaw-persistence-api` | CAS version-conflict with `blockKey`, `expectedVersion`, `actualVersion` |
| `TieredMemoryReflectionConfig` | `arcclaw-persistence-api` | GraalVM `@RegisterForReflection` for 10 types |
| `TieredBackend` | `arcclaw-core` | CDI `@Qualifier` annotation |
| `TieredBackendLiteral` | `arcclaw-core` | `AnnotationLiteral` for programmatic selection |
| `TieredMemoryBackend` | `arcclaw-core` | Typed enum (`SQLITE`, `POSTGRES`, `FILESYSTEM`) with `fromId()` |
| `TieredMemoryProducer` | `arcclaw-core` | `@Produces @ApplicationScoped` — resolves each port once at startup |
| `NoOpCoreMemoryStore` | `arcclaw-core` | Safe default when tier disabled |
| `NoOpRecallMemoryStore` | `arcclaw-core` | Safe default when tier disabled |
| `NoOpArchivalMemoryStore` | `arcclaw-core` | Safe default when tier disabled |
| `TieredMemoryConfig` | `arcclaw-core` | `@ConfigMapping(prefix = "arcclaw.memory.tiered")` |
| `MemoryPromptBudgetConfig` | `arcclaw-core` | `@ConfigMapping(prefix = "arcclaw.memory.prompt-budget")` |
| `SelfEvolveConfig` | `arcclaw-core` | `@ConfigMapping(prefix = "arcclaw.self-evolve")` |

### 3.4 CDI Producer Pattern (Zero Hot-Path Overhead)

Services inject ports directly — resolved once at startup, no lookup on the hot path:

```java
@Inject CoreMemoryStorePort coreStore;      // Resolved once by TieredMemoryProducer
@Inject RecallMemoryStorePort recallStore;   // Returns NoOpRecallMemoryStore when disabled
@Inject ArchivalMemoryStorePort archivalStore; // Fails fast if backend misconfigured
```

### 3.5 Config Keys (Actual — matches Phase 0 implementation)

```yaml
arcclaw:
  memory:
    tiered:
      enabled: ${ARCCLAW_MEMORY_TIERED_ENABLED:false}
      backend: ${ARCCLAW_MEMORY_TIERED_BACKEND:sqlite}
      core-memory:
        enabled: ${ARCCLAW_MEMORY_CORE_ENABLED:false}
        max-blocks-per-user: ${ARCCLAW_MEMORY_CORE_MAX_BLOCKS_PER_USER:20}
        max-chars-per-block: ${ARCCLAW_MEMORY_CORE_MAX_CHARS_PER_BLOCK:2000}
      recall:
        enabled: ${ARCCLAW_MEMORY_RECALL_ENABLED:false}
        retention-days: ${ARCCLAW_MEMORY_RECALL_RETENTION_DAYS:90}
        max-search-results: ${ARCCLAW_MEMORY_RECALL_MAX_SEARCH_RESULTS:10}
        cross-session-search: ${ARCCLAW_MEMORY_RECALL_CROSS_SESSION:true}
      archival:
        enabled: ${ARCCLAW_MEMORY_ARCHIVAL_ENABLED:false}
        max-search-results: ${ARCCLAW_MEMORY_ARCHIVAL_MAX_SEARCH_RESULTS:8}
        hybrid-search-k: ${ARCCLAW_MEMORY_ARCHIVAL_HYBRID_K:60}
        eviction:
          low-confidence-days: ${ARCCLAW_MEMORY_ARCHIVAL_LOW_CONFIDENCE_DAYS:60}
          low-confidence-threshold: ${ARCCLAW_MEMORY_ARCHIVAL_LOW_CONFIDENCE_THRESHOLD:0.5}
          lru-min-access-count: ${ARCCLAW_MEMORY_ARCHIVAL_LRU_MIN_ACCESS_COUNT:1}
          lru-max-age-days: ${ARCCLAW_MEMORY_ARCHIVAL_LRU_MAX_AGE_DAYS:90}
    prompt-budget:
      max-core-memory-tokens: ${ARCCLAW_MEMORY_PROMPT_BUDGET_CORE_TOKENS:800}
      max-compaction-summary-tokens: ${ARCCLAW_MEMORY_PROMPT_BUDGET_COMPACTION_TOKENS:1200}
      max-prefetched-archival-results: ${ARCCLAW_MEMORY_PROMPT_BUDGET_ARCHIVAL_RESULTS:3}
      max-directives: ${ARCCLAW_MEMORY_PROMPT_BUDGET_DIRECTIVES:5}
      total-non-conversation-tokens: ${ARCCLAW_MEMORY_PROMPT_BUDGET_TOTAL_TOKENS:2500}
  self-evolve:
    enabled: ${ARCCLAW_SELF_EVOLVE_ENABLED:false}
    backend: ${ARCCLAW_SELF_EVOLVE_BACKEND:sqlite}
    buffer-size: ${ARCCLAW_SELF_EVOLVE_BUFFER_SIZE:50}
    default-search-limit: ${ARCCLAW_SELF_EVOLVE_DEFAULT_SEARCH_LIMIT:8}
    directives-enabled: ${ARCCLAW_SELF_EVOLVE_DIRECTIVES_ENABLED:true}
    synthesis-trigger: "${ARCCLAW_SELF_EVOLVE_SYNTHESIS_TRIGGER:manual}"
    flush:
      max-events: ${ARCCLAW_SELF_EVOLVE_FLUSH_MAX_EVENTS:12}
      idle-ms: ${ARCCLAW_SELF_EVOLVE_FLUSH_IDLE_MS:300000}
```

### 3.6 Files Removed from Original Plan

| File | Reason |
|---|---|
| `TieredMemoryStorePort.java` | God-port replaced by 3 segregated ports (ISP) |
| `TieredMemoryBackendSelector.java` | Runtime selector replaced by CDI `@Produces` pattern |

### 3.7 Tests

```bash
# Config boot tests
./mvnw test -pl arcclaw-core -Dtest="TieredMemoryConfigTest"
# Producer tests (one backend resolves, NoOp returned when disabled)
./mvnw test -pl arcclaw-core -Dtest="TieredMemoryProducerTest"
# DTO validation tests
./mvnw test -pl arcclaw-persistence-api -Dtest="MemoryDtoValidationTest"
```

### 3.8 Exit Criteria

- [x] App compiles with all new flags **disabled** — `./mvnw clean compile` passes
- [x] 24 new files created, 0 removed, 0 compilation errors
- [x] Backend selection is deterministic — CDI `@TieredBackend` qualifier + `TieredBackendLiteral`
- [x] Zero behavior change when disabled — all ports return `NoOp*` implementations
- [x] GraalVM reflection registered — `TieredMemoryReflectionConfig` in persistence-api module

---

## 4. Phase 1: Core Memory Only

**Goal:** Smallest tier first — CRUD + prompt injection, no tools yet.

### 4.1 Deliverables

| Component | Location | Description |
|---|---|---|
| `CoreMemoryService` impl | `arcclaw-core` | CRUD + version conflict handling |
| Backend storage | `arcclaw-persistence-*` | `core_memory_blocks` table / JSON file |
| `CoreMemoryPromptRenderer` | `arcclaw-core` | Renders `[USER CONTEXT]` block |
| Admin API | `arcclaw-app` | `GET/PUT /admin/memory/core` |

### 4.2 Change Points

| File | Change |
|---|---|
| `ChatHistoryAssembler.java` | Prepend Core Memory block before compaction summary |
| `SqliteTieredMemoryStore.java` | Implement `CoreMemoryStorePort` (Core tier only in Phase 1) |
| `AdminMemoryResource.java` | New REST endpoints |

### 4.3 Prompt Injection Example

```java
// In ChatHistoryAssembler.assemble():
List<ChatMessage> messages = new ArrayList<>();

// 1. Core Memory (NEW - Phase 1)
String coreText = coreMemoryService.renderForPrompt(userId);
if (coreText != null && !coreText.isBlank()) {
    messages.add(new SystemMessage("[USER CONTEXT]\n" + coreText));
}

// 2. Compaction summary (existing)
compactionSummaryCache.getSummary(sessionId).ifPresent(...);

// 3. Recent conversation (existing)
messages.addAll(historyMessages);
```

### 4.4 Tests

```bash
# CRUD tests
./mvnw test -pl arcclaw-core -Dtest="CoreMemoryServiceTest"
# Prompt assembly test (USER CONTEXT appears first)
./mvnw test -pl arcclaw-core -Dtest="ChatHistoryAssemblerCoreMemoryTest"
# Admin API integration test
./mvnw test -pl arcclaw-app -Dtest="AdminMemoryResourceTest"
```

### 4.5 Exit Criteria

- [x] Core Memory persists and renders into prompt
- [x] Strict size caps enforced (2000 chars/block, 20 blocks max)
- [x] **No tools yet** (writes via admin API only)
- [x] **No self-evolve yet**

---

## 5. Phase 2: Recall Persistence (No Search Yet)

**Goal:** Stop future data loss first — preserve exact messages before compaction destroys them.

### 5.1 Deliverables

| Component | Location | Description |
|---|---|---|
| `RecallMemoryService` | `arcclaw-core` | `insert()`, `insertBatch()`, `insertCompactionSummary()` |
| `RecallMessage` mapper | `arcclaw-core` | `ChatMessage` → `RecallMessage` conversion |
| Idempotency layer | `arcclaw-core` | Dedupe by `(session_id, turn_number, content_hash)` |
| Backend storage | `arcclaw-persistence-*` | `recall_messages` table + FTS5 virtual table |

### 5.2 Change Points

| File | Change |
|---|---|
| `PersistentChatMemoryStore.updateMessages()` | Dual-write to Recall (async) |
| `CompactionService.compact()` | Persist `toCompact` messages to Recall BEFORE summarizing |
| `CompactionEventBridge.java` | Ensure Recall writes complete before async compaction fires |

### 5.3 Idempotency Strategy (DB-Enforced)

**v2.1 Correction:** Idempotency is enforced **primarily at the database layer**, not via application-level existence checks.

```sql
-- recall_messages table with unique constraint
CREATE TABLE recall_messages (
    message_id      TEXT PRIMARY KEY,
    session_id      TEXT NOT NULL,
    user_id         TEXT NOT NULL,
    turn_number     INTEGER NOT NULL,
    content_hash    TEXT NOT NULL,  -- SHA-256 of content
    role            TEXT NOT NULL,
    message_type    TEXT NOT NULL,
    content         TEXT NOT NULL,
    -- ... other columns ...
    UNIQUE(session_id, turn_number, content_hash)  -- DB-enforced dedupe
);
```

```java
// Application-level dedupe helpers are advisory only and must not be relied on for correctness
public void insertRecallMessage(RecallMessage msg) {
    // Compute dedupe key
    String dedupeKey = String.format("%s:%d:%s",
        msg.sessionId(), msg.turnNumber(), hash(msg.content()));
    
    // Advisory check only (logs warning if duplicate, does not prevent)
    if (seenDedupeKey(dedupeKey)) {
        log.warn("Duplicate recall insert attempted: {}", dedupeKey);
    }
    
    // DB-enforced idempotency via INSERT OR IGNORE
    store.insertOrIgnore(msg);  // SQLite: INSERT OR IGNORE, Postgres: ON CONFLICT DO NOTHING
}
```

**Backend-specific upsert-ignore semantics:**
- **SQLite:** `INSERT OR IGNORE INTO recall_messages (...) VALUES (...)`
- **PostgreSQL:** `INSERT INTO recall_messages (...) VALUES (...) ON CONFLICT (session_id, turn_number, content_hash) DO NOTHING`
- **Filesystem:** File existence check + atomic write (append-only log with dedupe key in filename)

### 5.4 Compaction Flow Change

```java
// In CompactionService.compact():
// BEFORE (old):
//   Messages → LLM Summary → replace conversation

// AFTER (Phase 2):
//   1. RecallMemoryService.insertAll(toCompact)  ← PERSIST FIRST
//   2. LLM Summary
//   3. RecallMemoryService.insert(summary as COMPACTION_SUMMARY)
//   4. PersistentChatMemoryStore.replace(toKeep only)
```

### 5.5 Tests

```bash
# DB uniqueness / insert-ignore test
./mvnw test -pl arcclaw-persistence-sqlite -Dtest="RecallInsertOrIgnoreTest"

# Concurrent dual-write race test
./mvnw test -pl arcclaw-core -Dtest="RecallConcurrentDualWriteRaceTest"

# Integration: compacted text preserved in Recall
./mvnw test -pl arcclaw-core -Dtest="CompactionRecallPreservationTest"

# Dual-write prevention test (DB-enforced, not app-level)
./mvnw test -pl arcclaw-core -Dtest="RecallDualWritePreventionTest"
```

### 5.6 Exit Criteria

- [x] Compaction still reduces active window (existing behavior)
- [x] Exact raw messages survive compaction in Recall storage
- [x] **No search exposed yet** (data preserved but not queryable by agents)
- [x] **Idempotency enforced at DB layer** via unique constraint + INSERT OR IGNORE
- [x] Application-level dedupe is advisory only (logs warnings, does not prevent)

---

## 6. Phase 3: Recall Search + Tool Split

**Goal:** Make preserved data usable — split `MemorySearchTool` into tier-aware tools.

### 6.1 Deliverables

| Component | Location | Description |
|---|---|---|
| `RecallMemorySearchTool` | `arcclaw-core` | FTS5 exact-history search |
| `RecallSearchQuery` DTO | `arcclaw-core` | Session-scoped + cross-session filters |
| Backend search methods | `arcclaw-persistence-*` | FTS5 session/cross-session queries |
| Deprecation wrapper | `arcclaw-core` | Old `MemorySearchTool` routes to new tools |

### 6.2 Change Points

| File | Change |
|---|---|
| `MemorySearchTool.java` | Mark `@Deprecated`; internally route to tier-aware logic |
| `RecallMemorySearchTool.java` | New `@Tool` implementation |
| `SqliteTieredMemoryStore.java` | Implement `RecallMemoryStorePort` search with FTS5 |

### 6.3 Tool Semantics Split

| Old Tool | New Tools | Description |
|---|---|---|
| `memory_search` | `recall_memory_search` | Exact session history (FTS5) |
| `memory_search` | `archival_memory_search` | Semantic knowledge (BM25+vector, Phase 6) |

### 6.4 Search Query Examples

**v2.1 Correction:** Default exact-history queries **exclude `COMPACTION_SUMMARY` records**. Summary records are stored in Recall but only included via explicit `include_summaries` mode.

```sql
-- Session-scoped (default, excludes summaries)
SELECT m.* FROM recall_messages m
JOIN recall_messages_fts fts ON m.rowid = fts.rowid
WHERE m.session_id = ? 
  AND m.message_type != 'COMPACTION_SUMMARY'  -- Exclude summaries by default
  AND fts.content MATCH ?
ORDER BY rank LIMIT ?

-- Cross-session (default, excludes summaries)
SELECT m.* FROM recall_messages m
JOIN recall_messages_fts fts ON m.rowid = fts.rowid
WHERE m.user_id = ? 
  AND m.message_type != 'COMPACTION_SUMMARY'  -- Exclude summaries by default
  AND fts.content MATCH ?
ORDER BY m.created_at DESC LIMIT ?

-- Include summaries mode (for overview/history-summary use cases)
SELECT m.* FROM recall_messages m
JOIN recall_messages_fts fts ON m.rowid = fts.rowid
WHERE m.session_id = ? 
  AND fts.content MATCH ?
  -- No message_type filter → includes COMPACTION_SUMMARY
ORDER BY 
  CASE WHEN m.message_type = 'COMPACTION_SUMMARY' THEN 1 ELSE 0 END,  -- Summaries rank lower
  rank
LIMIT ?
```

**Message type enum:**
```java
public enum RecallMessageType {
    USER_MESSAGE,
    AGENT_MESSAGE,
    TOOL_CALL,
    TOOL_RESULT,
    TOOL_ERROR,
    COMPACTION_SUMMARY,  // Stored in Recall but excluded from default search
    SYSTEM_NOTE
}
```

### 6.5 Tests

```bash
# Search exact code snippets after compaction (excludes summaries)
./mvnw test -pl arcclaw-core -Dtest="RecallSearchCodeSnippetNoSummaryPollutionTest"

# Search exact error messages by session (excludes summaries)
./mvnw test -pl arcclaw-core -Dtest="RecallSearchErrorTraceNoSummaryPollutionTest"

# Optional include-summaries mode test
./mvnw test -pl arcclaw-core -Dtest="RecallSearchIncludeSummariesModeTest"

# Tenant/user scoping tests
./mvnw test -pl arcclaw-core -Dtest="RecallSearchTenantIsolationTest"
```

### 6.6 Exit Criteria

- [x] Recall search works as exact-history lookup
- [x] Tool descriptions clearly distinguish Recall from semantic Archival
- [x] Session-scoped search works (<10ms latency target)
- [x] Cross-session search gated/configurable
- [x] **Default search excludes `COMPACTION_SUMMARY` records**
- [x] Optional `include_summaries` mode available for overview queries
- [x] Exact code/error retrieval tests confirm summaries do not outrank exact content

---

## 7. Phase 4: Prompt Budget Hardening

**Goal:** Prevent memory bloat — explicit ordering, size caps, overflow handling.

### 7.1 Deliverables

| Component | Location | Description |
|---|---|---|
| `MemoryPromptBudgetPolicy` | `arcclaw-core` | Injection order + size caps |
| Config properties | `application.yaml` | Budget limits per section |
| Trimming helpers | `arcclaw-core` | Deterministic overflow clipping |

### 7.2 Change Points

| File | Change |
|---|---|
| `ChatHistoryAssembler.java` | Enforce section ordering + caps |
| `ReasoningMessageAssembler.resolveSystemPrompt()` | Prevent memory sections merging with compaction sentinel |

### 7.3 Prompt Section Order (Deterministic)

```
1. [USER CONTEXT]          (Core Memory, Phase 1)        — max 800 tokens
2. [CONTEXT FROM PRIOR]    (Compaction Summary, existing) — max 1200 tokens
3. [RECENT TURNS]          (Rolling window, existing)     — last 20 messages
4. [RELEVANT KNOWLEDGE]    (Archival, Phase 6, **pre-fetched only**) — max 3 results
5. [BEHAVIORAL DIRECTIVES] (Self-Evolve, Phase 10)        — max 5 directives (SHADOW-mode evaluation)
```

**v2.1 Correction:** `[RELEVANT KNOWLEDGE]` section is populated **only if archival results were pre-fetched** via `archival_memory_search` tool or session-context gateway. `ChatHistoryAssembler` does **not** trigger archival retrieval I/O synchronously.

### 7.4 Budget Config

```yaml
arcclaw:
  memory:
    prompt-budget:
      max-core-memory-tokens: 800
      max-compaction-summary-tokens: 1200
      max-prefetched-archival-results: 3  # v2.1: Budget slot reserved but empty by default
      max-directives: 5
      total-non-conversation-tokens: 2500  # Hard ceiling
```

**v2.1 Note:** `max-prefetched-archival-results` is a **budget cap**, not a trigger. The slot remains empty unless archival results were already retrieved via tool call or session-context gateway. `ChatHistoryAssembler` consumes only pre-fetched/cached archival context and remains O(1) with no mandatory I/O.

### 7.5 Tests

```bash
# Prompt section ordering tests
./mvnw test -pl arcclaw-core -Dtest="PromptSectionOrderingTest"

# Overflow clipping tests
./mvnw test -pl arcclaw-core -Dtest="MemoryPromptOverflowTest"

# Regression: compaction sentinel still works
./mvnw test -pl arcclaw-core -Dtest="CompactionSentinelRegressionTest"

# v2.1: ChatHistoryAssembler does not trigger archival retrieval I/O
./mvnw test -pl arcclaw-core -Dtest="ChatHistoryAssemblerNoArchivalIoTest"

# v2.1: Prompt budget consumes only pre-fetched archival context
./mvnw test -pl arcclaw-core -Dtest="PromptBudgetPrefetchedArchivalOnlyTest"
```

### 7.6 Exit Criteria

- [x] Prompt assembly order is deterministic
- [x] Memory blocks clipped deterministically on overflow
- [x] Compaction sentinel behavior unchanged
- [x] Total injected context bounded by hard ceiling

---

## 8. Phase 5: Archival Storage Model

**Goal:** Semantic long-term layer — reuse `MemoryRetrievalPort`, add typed schema.

### 8.1 Deliverables

| Component | Location | Description |
|---|---|---|
| `ArchivalMemoryService` | `arcclaw-core` | Search + manual insert (no auto-promotion yet) |
| `ArchivalChunk` DTO | `arcclaw-persistence-api` | Typed chunks with provenance |
| Schema enrichment | `arcclaw-persistence-*` | `chunk_type`, `confidence`, `access_count` fields |
| Backend degradation | `arcclaw-persistence-*` | Fallback when vector unavailable |
| **`VectorIngestionService`** | `arcclaw-persistence-sqlite` | **v2.1: Serialized ingestion queue for SQLite vector writes** |

### 8.2 Change Points

| File | Change |
|---|---|
| `MemoryRetrievalPort.java` | Extend with typed chunk support |
| `SqliteMemoryRetrievalAdapter.java` | Add `ArchivalChunk` mapping |
| `FsMemoryRetrievalAdapter.java` | Add Lucene BM25 + optional vector |

### 8.3 ArchivalChunk Schema

```java
public record ArchivalChunk(
    String chunkId,
    String userId,
    String content,
    ChunkType type,  // CODE, DOC, PATTERN, FACT, PREFERENCE, DIRECTIVE
    String sourceSession,  // provenance
    String sourceMessage,  // provenance
    Map<String, String> tags,
    float[] embedding,  // optional (768-dim)
    double confidence,  // 0.0-1.0
    int accessCount,    // for LRU eviction
    Instant createdAt,
    Instant updatedAt
) {}
```

### 8.4 Backend Degradation Modes

| Backend | Full Support | Degraded Mode |
|---|---|---|
| SQLite | BM25 + sqlite-vec vector | BM25-only if sqlite-vec not loaded |
| PostgreSQL | BM25 (tsvector/GIN) + pgvector | BM25-only if pgvector not installed |
| Filesystem | BM25 (Lucene) + optional embeddings | BM25-only if `EmbeddingModel` bean unavailable |

**v2.1: SQLite Vector Write Serialization**

sqlite-vec is not thread-safe for concurrent writes. SQLite archival must serialize vector ingestion:

```java
@ApplicationScoped
public class VectorIngestionService {
    // Platform thread executor — NOT Semaphore(1)
    // Semaphore serializes logical calls but does NOT guarantee one specific OS thread.
    // sqlite-vec native C state requires physical thread serialization.
    private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor(
        Thread.ofPlatform().name("archival-vec-writer").daemon(true).factory()
    );

    public CompletableFuture<Void> ingestVector(String chunkId, float[] embedding) {
        return CompletableFuture.runAsync(() -> {
            jdbi.useHandle(handle -> {
                handle.createUpdate("INSERT OR REPLACE INTO vec_archival(chunk_id, embedding) VALUES (?, ?)")
                    .bind(0, chunkId)
                    .bind(1, embedding)
                    .execute();
            });
        }, writerExecutor).exceptionally(ex -> {
            log.errorf("Vector write failed for chunk %s: %s", chunkId, LogUtil.rootMessage(ex));
            return null;
        });
    }

    // Reads remain concurrent
    public List<Object[]> searchVector(float[] queryEmbedding, int limit) { ... }

    @PreDestroy
    void shutdown() {
        writerExecutor.shutdown();
        try {
            if (!writerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                writerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writerExecutor.shutdownNow();
        }
    }
}
```

**Rationale:** `Semaphore(1)` blocks the calling virtual thread while holding the permit — the native sqlite-vec write still happens from whichever carrier thread the virtual thread is scheduled on, which can change. `newSingleThreadExecutor(Thread.ofPlatform()...)` guarantees all writes execute on **one specific OS thread** — the only guarantee that satisfies sqlite-vec's native C state requirements.

### 8.5 Tests

```bash
# Manual insert/search tests
./mvnw test -pl arcclaw-core -Dtest="ArchivalManualInsertSearchTest"

# Vector-unavailable fallback tests
./mvnw test -pl arcclaw-persistence-sqlite -Dtest="SqliteArchivalFallbackTest"

# Provenance persistence tests
./mvnw test -pl arcclaw-core -Dtest="ArchivalProvenanceTest"

# v2.1: Serialized sqlite-vec ingestion test
./mvnw test -pl arcclaw-persistence-sqlite -Dtest="VectorIngestionSerializationTest"

# v2.1: Concurrent ingestion safety test (no corruption, no SQLITE_BUSY)
./mvnw test -pl arcclaw-persistence-sqlite -Dtest="VectorIngestionConcurrentSafetyTest"
```

### 8.6 Exit Criteria

- [x] Archival works as typed semantic store
- [x] Independent from Recall exact history
- [x] Backend degradation graceful (no crashes)
- [x] **No automatic promotion yet** (manual insert only)

---

## 9. Phase 6: Archival Tools + Selective Use

**Goal:** Expose archival search — controlled prompt injection, no spam.

### 9.1 Deliverables

| Component | Location | Description |
|---|---|---|
| `ArchivalMemorySearchTool` | `arcclaw-core` | Semantic search tool |
| `ArchivalResultFormatter` | `arcclaw-core` | Compact injection format |
| Relevance filter | `arcclaw-core` | Goal-matching + confidence threshold |

### 9.2 Change Points

| File | Change |
|---|---|
| `ChatHistoryAssembler.java` | Inject `[RELEVANT KNOWLEDGE]` only when explicitly retrieved |
| `ReasoningMessageAssembler.java` | Narrow retrieval policy for goal-matching |

### 9.3 Injection Rules (Prevent Spam)

**v2.1 Correction:** Archival is **tool-first, never unconditional**. `ChatHistoryAssembler` must not synchronously trigger BM25/vector/hybrid retrieval on every request.

```java
// In ChatHistoryAssembler.assemble():
// v2.1: Only consume pre-fetched/cached archival context
ArchivalContext context = archivalContextCache.get(sessionId);  // O(1) cache lookup

if (context != null && !context.results().isEmpty()) {
    // Pre-fetched results available → inject
    String knowledgeText = formatArchivalResults(context.results());
    messages.add(new SystemMessage("[RELEVANT KNOWLEDGE]\n" + knowledgeText));
}
// If no pre-fetched context → nothing injected (do not trigger I/O)
```

**Passive injection policy (optional, advanced):**
- Allowed only through **pre-fetched session context gateway/cache**
- Cache populated by `archival_memory_search` tool call or narrow retrieval policy
- Strict latency bounds: cache lookup must complete in <5ms or skip injection
- Lexical-first fallback: if vector search times out, use BM25-only cached results

**Tool-first primary path:**
```java
// Agent explicitly calls archival_memory_search tool
@Tool(name = "archival_memory_search")
public String search(String query) {
    List<ArchivalChunk> results = archivalMemoryService.search(query, limit = 3);
    archivalContextCache.put(sessionId, results);  // Cache for prompt assembly
    return formatResults(results);
}
```

### 9.4 Tests

```bash
# Goal-matching tests
./mvnw test -pl arcclaw-core -Dtest="ArchivalGoalMatchingTest"

# Confidence threshold tests
./mvnw test -pl arcclaw-core -Dtest="ArchivalConfidenceThresholdTest"

# Injection sparsity tests (no prompt spam)
./mvnw test -pl arcclaw-core -Dtest="ArchivalInjectionSparsityTest"

# v2.1: Tool-first path test (archival_memory_search populates cache)
./mvnw test -pl arcclaw-core -Dtest="ArchivalToolFirstPathTest"

# v2.1: ChatHistoryAssembler O(1) cache lookup test (no I/O)
./mvnw test -pl arcclaw-core -Dtest="ArchivalCacheLookupNoIoTest"
```

### 9.5 Exit Criteria

- [x] Only top few, deduped, relevant archival results enter prompt
- [x] Confidence-filtered (min 0.6 default)
- [x] Never injected if empty or low-confidence
- [x] Goal-matched (not random knowledge dump)
- [x] **v2.1: `ChatHistoryAssembler` does not trigger archival retrieval I/O**
- [x] **v2.1: Archival results consumed from pre-fetched cache only (O(1) lookup)**
- [x] **v2.1: Tool-first path is primary retrieval mechanism**

---

## 10. Phase 7: Controlled Write Paths

**Goal:** Safe mutation — narrow, audited, bounded agent writes.

### 10.1 Deliverables

| Component | Location | Description |
|---|---|---|
| `CoreMemoryReadTool` | `arcclaw-core` | Read core blocks |
| `CoreMemoryAppendTool` | `arcclaw-core` | Append with CAS versioning |
| `CoreMemoryReplaceTool` | `arcclaw-core` | Replace with CAS versioning |
| `ArchivalMemoryInsertTool` | `arcclaw-core` | Manual insert (approval-gated) |
| `MemoryWriteGovernance` | `arcclaw-core` | Central rate limiting + audit |

### 10.2 Change Points

| File | Change |
|---|---|
| `ToolCapability.java` | Add `CORE_MEMORY_*`, `ARCHIVAL_MEMORY_INSERT` |
| `ToolRegistry.java` | Register new tools |
| `AuditEventLogger.java` | Log memory mutation events |

### 10.3 Write Governance Rules

| Tool | Write Scope | Approval Required? | Rate Limit | Content Cap |
|---|---|---|---|---|
| `core_memory_append` | Single block (keyed) | No | 5/minute | 2000 chars |
| `core_memory_replace` | Single block (CAS) | No | 5/minute | 2000 chars |
| `archival_memory_insert` | Single chunk | Yes (non-coordinator) | 10/hour | 4000 chars |

### 10.4 Allowed Core Memory Block Keys

```java
Set<String> ALLOWED_BLOCK_KEYS = Set.of(
    "user_profile",      // Name, role, preferences
    "active_task",       // Current goal
    "constraints",       // Hard rules (e.g. "never modify .env")
    "project_context",   // Tech stack, architecture
    "preferences"        // Coding style, communication preferences
);
```

### 10.5 Tests

```bash
# CAS conflict tests
./mvnw test -pl arcclaw-core -Dtest="CoreMemoryCasConflictTest"
# Rate limit tests
./mvnw test -pl arcclaw-core -Dtest="MemoryWriteRateLimitTest"
# Approval-gating tests
./mvnw test -pl arcclaw-app -Dtest="ArchivalInsertApprovalTest"
# Content-size validation tests
./mvnw test -pl arcclaw-core -Dtest="MemoryWriteContentCapTest"
```

### 10.6 Exit Criteria

- [x] Agent writes are narrow (allowed block keys only)
- [x] Audited (all mutations logged)
- [x] Bounded (rate limits + content caps enforced)
- [x] CAS versioning prevents corruption

---

## 11. Phase 8: Self-Evolve Trace Capture

**Goal:** Build trace history — event emission + buffer + flush, no synthesis yet.

### 11.1 Deliverables

| Component | Location | Description |
|---|---|---|
| `arcclaw-self-evolve` module | New module | Separate from core memory |
| `EvolutionEvent` DTO | `arcclaw-self-evolve/api` | Typed events |
| `ConversationBuffer` | `arcclaw-self-evolve/buffer` | In-memory per-session accumulation |
| `SelfEvolveService.append()` | `arcclaw-self-evolve/runtime` | Event ingestion |
| Flush triggers | `arcclaw-self-evolve/runtime` | Session end, threshold, idle timeout |
| Event store SPI | `arcclaw-self-evolve/store` | SQLite/Postgres/FS backend |

### 11.2 Change Points

| File | Change |
|---|---|
| `AgentDispatchHandler.java` | Emit `EvolutionEvent` after each reasoning step |
| Tool execution handlers | Emit `TOOL_CALL`, `TOOL_RESULT`, `TOOL_ERROR` events |
| `CompactionService.java` | Emit `COMPACTION` event |

### 11.3 Event Types

```java
public enum EvolutionEventType {
    USER_MESSAGE,
    AGENT_MESSAGE,
    TOOL_CALL,
    TOOL_RESULT,
    TOOL_ERROR,
    RESOLUTION,
    ESCALATION,
    COMPACTION,
    SELF_CORRECTION
}
```

### 11.4 Flush Triggers

| Trigger | Condition | Action |
|---|---|---|
| Session end | Agent calls `FINISH` tool | Immediate flush |
| Event threshold | Buffer reaches `max-events` (default 12) | Flush to store |
| Idle timeout | No events for `idle-ms` (default 5 min) | Flush to store |
| Explicit | Agent calls `self_evolve_reflect` (Phase 10) | Flush + synthesize |

### 11.5 Tests

```bash
# Event emission sequence tests
./mvnw test -pl arcclaw-self-evolve -Dtest="EvolutionEventEmissionTest"
# Flush-on-threshold tests
./mvnw test -pl arcclaw-self-evolve -Dtest="ConversationBufferFlushThresholdTest"
# Disabled-feature no-op tests
./mvnw test -pl arcclaw-self-evolve -Dtest="SelfEvolveDisabledNoOpTest"
```

### 11.6 Exit Criteria

- [ ] Trace history captured reliably across sessions
- [ ] Flush logic reliable (no lost events)
- [ ] Feature can be disabled cleanly (zero overhead)
- [ ] **Nothing injected into prompts yet**

---

## 12. Phase 9: Rule-Based Extraction

**Goal:** Structured records from traces — safe, deterministic, no LLM synthesis.

### 12.1 Deliverables

| Component | Location | Description |
|---|---|---|
| `MemoryExtractorLite` | `arcclaw-self-evolve/extract` | Rule-based pattern matching |
| Extracted record DTOs | `arcclaw-self-evolve/api` | Typed records |
| SIMPLE retrieval | `arcclaw-self-evolve/retrieve` | FTS5 over extracted records |
| Deduplication layer | `arcclaw-self-evolve/internal` | Prevent duplicate extractions |

### 12.2 Record Types

```java
public enum MemoryRecordType {
    USER_PREFERENCE,      // "I prefer streams over for-loops"
    USER_PROFILE,         // "Senior Java developer, uses Quarkus"
    ACTIVE_TASK,          // "Debugging CompactionEventBridge latch"
    RESOLUTION_PATTERN,   // "Running ./mvnw compile fixed the issue"
    FAILURE_PATTERN,      // "NullPointerException in line 42"
    TOOL_HINT,            // "github tool works best with explicit repo"
    PROJECT_CONTEXT,      // "ArcClaw has 12 persona roles"
    DIRECTIVE,            // "Always run fix_fqns.py after Java edits"
    SUMMARY,              // Session summary
    FACT,                 // General knowledge
    ERROR_CONTEXT,        // Specific error + resolution pairing
    TOOL_MISUSE_PATTERN,  // "Don't call memory_search without query"
    WORKAROUND            // Temporary solution that worked
}
```

### 12.3 Extraction Rules (Examples)

```java
// Rule: User preference detection
if (event.type == USER_MESSAGE && event.content.matches(".*I prefer.*")) {
    return new ExtractedMemory(USER_PREFERENCE, event.content, confidence = 0.6);
}

// Rule: Failure pattern (same tool fails 2+ times)
if (toolFailureCount >= 2 && sameTool && sameSession) {
    return new ExtractedMemory(FAILURE_PATTERN, failureTrace, confidence = 0.7);
}

// Rule: Resolution pattern (FINISH after tool success)
if (event.type == RESOLUTION && priorToolSuccess) {
    return new ExtractedMemory(RESOLUTION_PATTERN, resolutionText, confidence = 0.8);
}
```

### 12.4 Tests

```bash
# Rule extraction tests from real traces
./mvnw test -pl arcclaw-self-evolve -Dtest="MemoryExtractorRuleTest"
# Deduplication tests
./mvnw test -pl arcclaw-self-evolve -Dtest="ExtractedMemoryDedupeTest"
# SIMPLE retrieval tests
./mvnw test -pl arcclaw-self-evolve -Dtest="SimpleRetrievalTest"
```

### 12.5 Exit Criteria

- [ ] Usable structured records without LLM synthesis dependency
- [ ] Deduplication prevents record explosion
- [ ] SIMPLE retrieval works (<10ms latency)
- [ ] Confidence scoring reasonable (0.5-0.9 range)

---

## 13. Phase 10: Directive Synthesis

**Goal:** Offline learning loop — LLM-powered synthesis, bounded prompt injection.

### 13.1 Deliverables

| Component | Location | Description |
|---|---|---|
| `InteractionPolicySynthesizer` | `arcclaw-self-evolve/policy` | LLM-driven directive generation |
| `DirectiveBundle` | `arcclaw-self-evolve/api` | Synthesized directives with lifecycle state |
| `SelfEvolveReflectTool` | `arcclaw-self-evolve` | On-demand synthesis trigger |
| Directive storage | `arcclaw-self-evolve/store` | As `DIRECTIVE` archival chunks or typed records |
| **Lifecycle manager** | `arcclaw-self-evolve/policy` | **v2.1: CANDIDATE → SHADOW → ACTIVE → DISABLED state machine** |

### 13.2 Change Points

| File | Change |
|---|---|
| `ChatHistoryAssembler.java` | Inject `[BEHAVIORAL DIRECTIVES]` (high-confidence only) |
| `ReasoningMessageAssembler.java` | Filter directives by goal relevance |

### 13.3 Synthesis Constraints

| Constraint | Value | Rationale |
|---|---|---|
| **Trigger** | On-demand (`self_evolve_reflect`) or daily cron | Never mid-conversation (too expensive) |
| **Confidence threshold** | Min 0.8 for injection | Suppress noisy directives |
| **Max directives** | 5 per prompt | Prevent prompt bloat |
| **Max directive length** | 600 chars each | Keep prompts readable |
| **Admin override** | `DELETE /admin/memory/directive/{id}` | Manual disable path |
| **v2.1: Lifecycle states** | `CANDIDATE` → `SHADOW` → `ACTIVE` → `DISABLED` | New directives never go directly to ACTIVE |

**Directive Lifecycle (v2.1):**

```java
public enum DirectiveState {
    CANDIDATE,   // Newly synthesized, not yet evaluated
    SHADOW,      // Injected with evaluation labeling, no behavior change
    ACTIVE,      // Fully authoritative, standard injection
    DISABLED     // Admin-disabled or auto-faulted
}

// State transitions:
// CANDIDATE → SHADOW: Auto on synthesis (requires no approval)
// SHADOW → ACTIVE: Requires measurable success criteria (e.g. 3 sessions with positive outcomes)
// SHADOW → DISABLED: Auto on negative feedback or admin action
// ACTIVE → DISABLED: Admin action or auto-fault detection
```

**Shadow-mode injection:**
```java
// In ChatHistoryAssembler.assemble():
List<Directive> shadowDirectives = directives.stream()
    .filter(d -> d.state() == DirectiveState.SHADOW)
    .limit(2)  // Max 2 shadow directives in evaluation
    .toList();

if (!shadowDirectives.isEmpty()) {
    String shadowText = shadowDirectives.stream()
        .map(d -> "- [EVAL] " + d.text())  // Explicit evaluation labeling
        .collect(joining("\n"));
    messages.add(new SystemMessage("[BEHAVIORAL DIRECTIVES - EVALUATION]\n" + shadowText));
}
```

### 13.4 Directive Injection Example

```java
// In ChatHistoryAssembler.assemble():
if (directivesEnabled) {
    DirectiveBundle bundle = selfEvolveService.buildDirectives(userKey);
    
    List<String> highConfidence = bundle.stableDirectives().stream()
        .filter(d -> bundle.confidence(d) >= 0.8)
        .limit(5)
        .toList();
    
    if (!highConfidence.isEmpty()) {
        String dirText = "[BEHAVIORAL DIRECTIVES]\n" +
            highConfidence.stream().map(d -> "- " + d).collect(joining("\n"));
        messages.add(new SystemMessage(dirText));
    }
}
```

### 13.5 Tests

```bash
# Confidence threshold tests
./mvnw test -pl arcclaw-self-evolve -Dtest="DirectiveConfidenceThresholdTest"

# Low-confidence suppression tests
./mvnw test -pl arcclaw-self-evolve -Dtest="LowConfidenceDirectiveSuppressionTest"

# Admin delete/disable tests
./mvnw test -pl arcclaw-app -Dtest="DirectiveAdminDisableTest"

# Prompt injection cap tests
./mvnw test -pl arcclaw-core -Dtest="DirectiveInjectionCapTest"

# v2.1: New directives start in SHADOW or CANDIDATE
./mvnw test -pl arcclaw-self-evolve -Dtest="DirectiveShadowLifecycleStartTest"

# v2.1: Shadow directives injected with evaluation labeling only
./mvnw test -pl arcclaw-core -Dtest="ShadowDirectiveEvaluationLabelingTest"

# v2.1: Promotion criteria test (SHADOW → ACTIVE)
./mvnw test -pl arcclaw-self-evolve -Dtest="DirectivePromotionCriteriaTest"

# v2.1: Faulty directive quarantine/disable test
./mvnw test -pl arcclaw-self-evolve -Dtest="FaultyDirectiveQuarantineTest"
```

### 13.6 Exit Criteria

- [ ] Only stable, high-confidence directives injected
- [ ] No synthesis runs in live turn path by default
- [ ] Admin can delete/disable directives
- [ ] Prompt injection bounded (max 5, 600 chars each)
- [ ] **v2.1: New directives start as `CANDIDATE` or `SHADOW`, never directly `ACTIVE`**
- [ ] **v2.1: `SHADOW` directives evaluated without changing actual behavior**
- [ ] **v2.1: Promotion to `ACTIVE` requires measurable success criteria**
- [ ] **v2.1: Auto-disable or admin disable paths exist**

---

## 14. Phase 11: Backend Parity

**Goal:** Same semantics across SQLite/PostgreSQL/Filesystem — explicit degraded modes.

### 14.1 Deliverables

| Component | Location | Description |
|---|---|---|
| `PostgresTieredMemoryStore` | `arcclaw-persistence-postgres` | Single class implements all three segregated ports; annotated `@TieredBackend("postgres")` |
| `FsTieredMemoryStore` | `arcclaw-persistence-fs` | Single class implements all three segregated ports; annotated `@TieredBackend("fs")` |
| Self-evolve backends | `arcclaw-self-evolve/store/*` | SQLite/Postgres/FS providers |
| Capability declarations | `arcclaw-persistence-api` | Explicit feature matrix |

**Backend Implementation Pattern:** Each backend is a single `@ApplicationScoped` CDI bean implementing all three segregated port interfaces. The `@TieredBackend` qualifier identifies the backend. The `TieredMemoryProducer` resolves each port independently, but at runtime they reference the same bean instance:

```java
@ApplicationScoped
@TieredBackend("sqlite")
public class SqliteTieredMemoryStore implements CoreMemoryStorePort, RecallMemoryStorePort, ArchivalMemoryStorePort {
    // ISP at injection point: services inject individual ports
    // Single impl class: avoids code duplication across tiers
}
```

### 14.2 Backend Feature Matrix

| Feature | SQLite | PostgreSQL | Filesystem |
|---|---|---|---|
| Core Memory | ✅ JDBI | ✅ JDBI | ✅ JSON files |
| Recall FTS5 | ✅ FTS5 virtual table | ✅ tsvector/GIN | ✅ Lucene BM25 |
| Archival Vector | ⚠️ sqlite-vec (optional, **writes serialized**) | ⚠️ pgvector (optional) | ⚠️ Embeddings (optional) |
| Self-Evolve Store | ✅ JDBI | ✅ JDBI | ✅ JSON files |
| Advisory Locks | ❌ Not available | ✅ pg_advisory_lock | ❌ Not available |
| Nested Transactions | ⚠️ Limited | ✅ Full | ❌ Not available |
| Concurrent Vector Writes | ❌ **Must serialize** | ✅ Thread-safe | ⚠️ **Must serialize** |

**v2.1 Note:** SQLite vector writes must be serialized (see Phase 5, Section 8.4). Filesystem backend also requires serialization for embedding writes.

### 14.3 Degraded Mode Tests

```bash
# SQLite without sqlite-vec
./mvnw test -pl arcclaw-persistence-sqlite -Dtest="SqliteVectorFallbackTest"

# PostgreSQL without pgvector/GIN
./mvnw test -pl arcclaw-persistence-postgres -Dtest="PostgresVectorFallbackTest"

# Filesystem without embeddings
./mvnw test -pl arcclaw-persistence-fs -Dtest="FsEmbeddingFallbackTest"
```

### 14.4 Exit Criteria

- [ ] Same semantics across backends
- [ ] Degraded features explicitly documented
- [ ] No crashes when optional features unavailable
- [ ] Backend producer resolves correct implementation for each segregated port

---

## 15. Phase 12: Hardening & Cleanup

**Goal:** Production readiness — observability, admin APIs, deprecations, docs.

### 15.1 Deliverables

| Component | Location | Description |
|---|---|---|
| Admin APIs | `arcclaw-app` | `/admin/memory/*` endpoints |
| Observability metrics | `arcclaw-core`, `arcclaw-self-evolve` | Latency, hit rates, confidence distribution |
| Retention/eviction jobs | `arcclaw-core` | Scheduled cleanup tasks |
| Deprecation notices | `arcclaw-core` | `@Deprecated` on old tools |
| Documentation | `AGENTS.md`, module READMEs | Architecture + config docs |

### 15.2 Change Points

| File | Change |
|---|---|
| `MemorySearchTool.java` | Full deprecation (remove after 1 release cycle) |
| `CompactionService.java` | Ensure summaries not treated as canonical memory |
| `PromptTemplateEngine.java` | Verify section ordering across all roles |
| `TieredMemoryHealthDto.java` | Verify health schema covers all three tiers for admin monitoring |

### 15.3 Observability Metrics

```java
// Example Micrometer metrics
Counter recallSearchCounter = Counter.builder("memory.recall.search")
    .tag("tier", "recall")
    .register(meterRegistry);

Timer recallSearchLatency = Timer.builder("memory.recall.search.latency")
    .tag("tier", "recall")
    .register(meterRegistry);

DistributionSummary archivalResultConfidence = DistributionSummary.builder("memory.archival.confidence")
    .tag("tier", "archival")
    .register(meterRegistry);
```

### 15.4 Tests

```bash
# p95 latency tests
./mvnw test -pl arcclaw-core -Dtest="MemoryLatencyP95Test"

# Concurrency tests
./mvnw test -pl arcclaw-core -Dtest="MemoryConcurrencyTest"

# Rollback/disable regression tests
./mvnw test -pl arcclaw-app -Dtest="TieredMemoryDisableRegressionTest"

# End-to-end sessions (old behavior intact when off)
./mvnw test -pl arcclaw-app -Dtest="EndToEndDisabledBehaviorTest"
```

### 15.5 Exit Criteria

- [ ] Feature set stable and observable
- [ ] Old `MemorySearchTool` fully deprecated
- [ ] Compaction summaries no longer canonical memory
- [ ] Disabled tiers cleanly fall back to pre-Phase 0 behavior
- [ ] Documentation complete (AGENTS.md updated)

---

## 16. Critical Implementation Warnings

1. [DB Uniqueness is Mandatory for Recall Correctness](#151-db-uniqueness-is-mandatory-for-recall-correctness)
2. [Summary Records Must Not Pollute Exact-History Recall Search](#152-summary-records-must-not-pollute-exact-history-recall-search)
3. [ChatHistoryAssembler Must Not Perform Unconditional Archival Retrieval](#153-charthistoryassembler-must-not-perform-unconditional-archival-retrieval)
4. [SQLite Vector Writes Must Be Serialized](#154-sqlite-vector-writes-must-be-serialized)
5. [Self-Evolve Directives Must Not Go Directly from Synthesis to Active Prompt Authority](#155-self-evolve-directives-must-not-go-directly-from-synthesis-to-active-prompt-authority)
6. [Tool Taxonomy Must Change](#156-tool-taxonomy-must-change)
7. [Write Governance Needs Central Enforcement](#157-write-governance-needs-central-enforcement)
8. [Cross-Session Search Must Stay Tenant-Scoped](#158-cross-session-search-must-stay-tenant-scoped)
9. [Backend Capability Drift](#159-backend-capability-drift)
10. [Self-Evolve Must Stay Offline By Default](#1510-self-evolve-must-stay-offline-by-default)

### 16.1 DB Uniqueness is Mandatory for Recall Correctness

**Risk:** `PersistentChatMemoryStore.updateMessages()` and `CompactionService.compact()` both persist messages → duplicates.

**v2.1 Mitigation:** Idempotency enforced at **database layer** via unique constraint + `INSERT OR IGNORE`:

```sql
-- DB-enforced uniqueness
CREATE UNIQUE INDEX idx_recall_dedupe 
ON recall_messages(session_id, turn_number, content_hash);

-- Application uses INSERT OR IGNORE (SQLite) or ON CONFLICT DO NOTHING (Postgres)
INSERT OR IGNORE INTO recall_messages (...) VALUES (...);
```

**Application-level dedupe helpers are advisory only and must not be relied on for correctness.** They may log warnings but must not be the primary dedupe mechanism.

### 16.2 Summary Records Must Not Pollute Exact-History Recall Search

**Risk:** `COMPACTION_SUMMARY` records stored in Recall pollute exact code/error retrieval results.

**v2.1 Mitigation:** Default `recall_memory_search` queries **exclude** `message_type = 'COMPACTION_SUMMARY'`:

```sql
-- Default search excludes summaries
WHERE m.message_type != 'COMPACTION_SUMMARY'

-- Optional include_summaries mode for overview queries
-- (summaries rank lower via CASE ordering)
```

Tests must confirm summaries do not outrank exact content in default search mode.

### 16.3 ChatHistoryAssembler Must Not Perform Unconditional Archival Retrieval

**Risk:** `ChatHistoryAssembler` triggers BM25/vector/hybrid retrieval on every request → latency spike, prompt bloat.

**v2.1 Mitigation:** **Tool-first, never unconditional** policy:

```java
// v2.1: Only consume pre-fetched/cached archival context (O(1) lookup)
ArchivalContext context = archivalContextCache.get(sessionId);
if (context != null && !context.results().isEmpty()) {
    // Inject pre-fetched results
}
// If no pre-fetched context → nothing injected (do not trigger I/O)
```

Passive injection allowed only through pre-fetched session context gateway/cache with strict latency bounds (<5ms lookup).

### 16.4 SQLite Vector Writes Must Be Serialized

**Risk:** sqlite-vec is not thread-safe for concurrent writes → `SQLITE_BUSY` errors, index corruption.

**v2.1 Mitigation:** Serialize vector ingestion via `VectorIngestionService` with a single platform thread executor:

```java
@ApplicationScoped
public class VectorIngestionService {
    private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor(
        Thread.ofPlatform().name("archival-vec-writer").daemon(true).factory()
    );

    public CompletableFuture<Void> ingestVector(String chunkId, float[] embedding) {
        return CompletableFuture.runAsync(() -> {
            // write on single platform thread — safe for native sqlite-vec C state
        }, writerExecutor).exceptionally(ex -> { ... });
    }

    @PreDestroy void shutdown() { /* timed drain with awaitTermination(5, SECONDS) */ }
}
```

**Why platform thread, not Semaphore:** `Semaphore(1)` serializes logical calls but does NOT pin execution to one specific OS thread. Under Quarkus virtual threads, the carrier thread can change between `acquire()` and the actual native write. `newSingleThreadExecutor(Thread.ofPlatform())` guarantees all writes execute on **one specific OS thread**.

Tests must cover concurrent write safety, no corruption, no `SQLITE_BUSY` regression.

### 16.5 Self-Evolve Directives Must Not Go Directly from Synthesis to Active Prompt Authority

**Risk:** Newly synthesized directives injected as authoritative → agent behavior changes before evaluation.

**v2.1 Mitigation:** **Shadow lifecycle** (`CANDIDATE` → `SHADOW` → `ACTIVE` → `DISABLED`):

```java
// New directives start as SHADOW, never ACTIVE
Directive directive = synthesize(...);
directive.setState(DirectiveState.SHADOW);  // Not ACTIVE

// Shadow-mode injection with evaluation labeling
messages.add(new SystemMessage("[BEHAVIORAL DIRECTIVES - EVALUATION]\n" +
    "- [EVAL] " + directive.text()));
```

Promotion to `ACTIVE` requires measurable success criteria (e.g., 3 sessions with positive outcomes). Tests must verify shadow lifecycle start, evaluation labeling, promotion criteria, and faulty directive quarantine.

### 16.6 Tool Taxonomy Must Change

**Risk:** Old `memory_search` semantics no longer match new tiers → confusion.

**Mitigation:**
- Phase 3: Deprecate `MemorySearchTool` with clear migration path
- Phase 3: New tools explicitly named `recall_memory_search`, `archival_memory_search`
- Update tool descriptions to clarify boundaries

### 16.7 Write Governance Needs Central Enforcement

**Risk:** Rate limits described in tool docs but not enforced → memory pollution.

**Mitigation:**
```java
// Central governance layer
public class MemoryWriteGovernance {
    private final RateLimiter coreMemoryLimiter = RateLimiter.of(5, MINUTES);
    private final RateLimiter archivalLimiter = RateLimiter.of(10, HOURS);
    
    public void checkRateLimit(MemoryWriteType type) {
        switch (type) {
            case CORE_MEMORY -> coreMemoryLimiter.acquire();
            case ARCHIVAL_MEMORY -> archivalLimiter.acquire();
        }
    }
}
```

### 16.8 Cross-Session Search Must Stay Tenant-Scoped

**Risk:** Agent in one conversation searches another user's sessions → privacy violation.

**Mitigation:**
```java
// Always extract userId from runtime context, never from LLM params
public List<RecallMessage> search(String userId, String query, boolean crossSession) {
    // userId comes from ctx.userId(), not tool arguments
    return recallStore.search(userId, query, crossSession);
}
```

### 16.9 Backend Capability Drift

**Risk:** SQLite gets new feature, Postgres/FS not updated → inconsistent behavior.

**Mitigation:**
- Define required vs optional features per backend in `BackendCapabilities` DTO
- Explicit degraded mode tests for each backend
- Feature flags per capability (e.g., `supportsVectorSearch`)

### 16.10 Self-Evolve Must Stay Offline By Default

**Risk:** Synthesis triggers inside live request path → latency spike.

**Mitigation:**
```yaml
# Config default
arcclaw:
  self-evolve:
    synthesis:
      enabled: false  # Must be explicitly enabled
      trigger: "cron" | "manual"  # Never "auto" in live path
```

---

## 17. Module Mapping

### 17.1 Maven Module Changes

| Module | New Packages | Description |
|---|---|---|
| `arcclaw-persistence-api` | `com.arcclaw.persistence.api` | 3 segregated `*StorePort` interfaces, DTOs, query objects, `VersionConflictException`, `TieredMemoryReflectionConfig` |
| `arcclaw-persistence-sqlite` | `com.arcclaw.persistence.sqlite.tiered` | SQLite implementations of segregated ports |
| `arcclaw-persistence-postgres` | `com.arcclaw.persistence.postgres.tiered` | Postgres implementations of segregated ports |
| `arcclaw-persistence-fs` | `com.arcclaw.persistence.fs.tiered` | Filesystem implementations of segregated ports |
| `arcclaw-core` | `com.arcclaw.core.memory.tiered` | `TieredMemoryProducer`, `@TieredBackend`, `TieredBackendLiteral`, `TieredMemoryBackend`, NoOp stores |
| `arcclaw-core` | `com.arcclaw.core.config` | `TieredMemoryConfig`, `MemoryPromptBudgetConfig`, `SelfEvolveConfig` |

### 17.2 arcclaw-self-evolve Module Structure

```
arcclaw-self-evolve/
└── src/main/java/com/arcclaw/selfevolve/
    ├── api/                    # Public DTOs (EvolutionEvent, MemoryRecord, DirectiveBundle)
    ├── buffer/                 # ConversationBuffer, InMemoryConversationBuffer
    ├── extract/                # MemoryExtractorLite, ExtractedMemory
    ├── retrieve/               # MemoryRetrieverLite, RetrievalMode (SIMPLE/DEEP)
    ├── policy/                 # InteractionPolicySynthesizer, SynthesizedDirective
    ├── store/                  # MemoryStore SPI + sqlite/postgres/fs providers
    ├── runtime/                # DefaultSelfEvolveService, SelfEvolveConfig, FlushTriggerPolicy
    └── internal/               # Mappers, utilities (not exposed)
```

### 17.3 Dependencies

```xml
<!-- arcclaw-self-evolve/pom.xml -->
<dependencies>
    <dependency>
        <groupId>com.arcclaw</groupId>
        <artifactId>arcclaw-core</artifactId>
        <scope>provided</scope>  <!-- For AgentTool, ToolCapability -->
    </dependency>
    <dependency>
        <groupId>com.arcclaw</groupId>
        <artifactId>arcclaw-persistence-api</artifactId>
        <scope>provided</scope>  <!-- For store ports -->
    </dependency>
    <!-- NO dependency on arcclaw-app (avoid circular) -->
</dependencies>
```

---

## 18. Verification Summary

### 18.1 Phase Exit Criteria Checklist

| Phase | Key Exit Criteria |
|---|---|
| 0 | ✅ App boots with flags disabled, zero behavior change, 20 files, 3 ports (ISP), CDI producer, GraalVM reflection |
| 1 | ✅ Core Memory persists + renders, no tools yet |
| 2 | ✅ Recall preserves messages before compaction, **DB-enforced idempotency** |
| 3 | ✅ Recall search works, tool split complete, **summaries excluded by default** |
| 4 | ✅ Prompt ordering deterministic, overflow handled, **no archival I/O in assembler** |
| 5 | ✅ Archival storage works, no auto-promotion, **vector writes serialized** |
| 6 | ✅ Archival tools exposed, **tool-first policy**, injection sparse |
| 7 | ✅ Write tools gated, audited, rate-limited, **CAS atomic append** |
| 8 | Self-evolve traces captured, no synthesis |
| 9 | Rule-based extraction works, no LLM yet |
| 10 | Directive synthesis offline, **shadow lifecycle**, high-confidence only |
| 11 | Backend parity achieved, degraded modes documented |
| 12 | Observability complete, old tools deprecated |

### 18.2 Cross-Phase Verification Commands

```bash
# After each phase:
./mvnw compile -DskipTests -q          # Compilation passes
python3 ./fix_fqns.py                   # Imports aligned
./mvnw test -pl arcclaw-core            # Core tests pass

# Feature flag verification:
grep "ARCCLAW_MEMORY_" .env.example arcclaw-app/src/main/resources/application.yaml
grep "ARCCLAW_SELF_EVOLVE_" .env.example arcclaw-app/src/main/resources/application.yaml

# End-to-end (Phase 12):
./mvnw test -pl arcclaw-app -Dtest="EndToEndTieredMemoryTest"
```

### 18.3 Latency Targets

| Operation | Target | Phase |
|---|---|---|
| Core Memory render | <1ms | Phase 1 |
| Recall search (session) | <10ms | Phase 3 |
| Recall search (cross-session) | <50ms | Phase 3 |
| Archival hybrid search | <50ms | Phase 5 |
| Self-evolve SIMPLE retrieval | <10ms | Phase 9 |
| Self-evolve DEEP synthesis | 2-5s (offline) | Phase 10 |

---

## 19. v2.1 Tactical Corrections

This section summarizes the five architecture decisions added in v2.1 and their affected phases.

### 19.1 Recall Dedupe is DB-Enforced (Not App-Check-Based)

**Affected Phases:** Phase 2, Section 16.1

**Change:** Replace application-level `exists(dedupeKey)` checks with database-level unique constraints + `INSERT OR IGNORE` semantics.

**Rationale:** Application-level checks are race-prone under concurrent dual-write scenarios (normal flow + compaction flow). DB-enforced uniqueness guarantees correctness.

**Implementation:**
- Add `UNIQUE(session_id, turn_number, content_hash)` constraint to `recall_messages` table
- Use `INSERT OR IGNORE` (SQLite) or `ON CONFLICT DO NOTHING` (Postgres)
- Application-level dedupe becomes advisory only (logs warnings, does not prevent)

### 19.2 Recall Exact Search Excludes Compaction Summaries by Default

**Affected Phases:** Phase 3, Section 16.2

**Change:** Default `recall_memory_search` queries filter out `message_type = 'COMPACTION_SUMMARY'` records.

**Rationale:** Summary records pollute exact code/error retrieval results. Users searching for "NullPointerException line 42" want the exact error trace, not a summary mentioning it.

**Implementation:**
- Add `message_type` column to `recall_messages` schema
- Default queries include `WHERE message_type != 'COMPACTION_SUMMARY'`
- Optional `include_summaries` mode for overview/history-summary use cases (summaries rank lower)

### 19.3 Archival is Tool-First, Never Unconditional

**Affected Phases:** Phase 4, Phase 6, Section 16.3

**Change:** `ChatHistoryAssembler` must not synchronously trigger archival retrieval I/O. Archival results consumed from pre-fetched cache only.

**Rationale:** Unconditional archival search on every prompt assembly causes latency spikes and prompt bloat. Tool-first policy ensures archival retrieval is intentional and bounded.

**Implementation:**
- `ChatHistoryAssembler` performs O(1) cache lookup only (`archivalContextCache.get(sessionId)`)
- Cache populated by `archival_memory_search` tool call or session-context gateway
- Passive injection allowed only with strict latency bounds (<5ms lookup, lexical-first fallback)
- Budget config renamed to `max-prefetched-archival-results` (slot reserved but empty by default)

### 19.4 SQLite Vector Writes are Serialized

**Affected Phases:** Phase 5, Phase 11, Section 16.4

**Change:** Serialize sqlite-vec ingestion via `VectorIngestionService` with a single platform thread executor.

**Rationale:** sqlite-vec is not thread-safe for concurrent writes. Concurrent writes cause `SQLITE_BUSY` errors and potential index corruption. A `Semaphore(1)` is insufficient because it serializes logical calls but does not pin execution to one specific OS thread — under Quarkus virtual threads, the carrier thread can change between `acquire()` and the actual native write.

**Implementation:**
- `VectorIngestionService` with `newSingleThreadExecutor(Thread.ofPlatform())` for write serialization
- Writes: `CompletableFuture.runAsync(..., writerExecutor)` — all writes on one specific OS thread
- `FloatArrayArgumentFactory` in JDBI for `float[]` → little-endian binary blob (no JSON)
- Reads: remain fully concurrent (no serialization needed)
- `@PreDestroy` with `awaitTermination(5, SECONDS)` for graceful drain
- Tests: concurrent ingestion safety, no corruption, no `SQLITE_BUSY` regression

### 19.5 Synthesized Directives Use Shadow Lifecycle

**Affected Phases:** Phase 10, Section 16.5

**Change:** New directives start as `CANDIDATE`/`SHADOW`, never directly `ACTIVE`. Shadow directives evaluated with explicit labeling before promotion.

**Rationale:** Premature directive activation changes agent behavior before effectiveness is measured. Shadow lifecycle allows evaluation without commitment.

**Implementation:**
- Directive lifecycle states: `CANDIDATE` → `SHADOW` → `ACTIVE` → `DISABLED`
- Shadow-mode injection: `"- [EVAL] " + directive.text()`
- Promotion to `ACTIVE` requires measurable success criteria (e.g., 3 sessions with positive outcomes)
- Auto-disable on negative feedback or admin action

---

**Document Version:** 2.8-implemented
**Previous Version:** 2.7-implemented
**Key Changes:** Post-Phase-7 cleanup: deprecated MemorySearchTool removed, MEMORY_SEARCH enum removed, all references migrated to RECALL_MEMORY_SEARCH + ARCHIVAL_MEMORY_SEARCH, MCP server exposes both tools, prompts updated with tool selection guidance, TODO(Phase-7) userId resolution fixed
**Next Step:** Phase 8 — Self-Evolve Trace Capture (EvolutionEvent, ConversationBuffer, SelfEvolveService)
**Review Status:** Phases 0-7 complete, post-phase cleanup done, all tests passing

---

## Appendix A: Implementation Status (v2.5 — 2026-05-26)

> **Status Update:** Phase 0 ✅, Phase 1 ✅, Phase 2 ✅, Phase 3 ✅, Phase 4 ✅, Phase 5 ✅, Phase 6 ✅ complete. Total: ~1,034+ core tests passing.

### A.1 Completed Phases Summary

| Phase | Status | Date Completed | Tests | Key Deliverables |
|---|---|---|---|---|
| **Phase 0** | ✅ COMPLETE | 2026-05-24 | 24 new files | 3 ISP ports, CDI producer, 22 config keys, GraalVM reflection |
| **Phase 1** | ✅ COMPLETE | 2026-05-24 | CoreMemoryService, SQLite store, Admin API | CRUD + CAS versioning, prompt injection, `[USER CONTEXT]` block |
| **Phase 2** | ✅ COMPLETE | 2026-05-25 | 960 tests | Dual-write persistence, turn alignment, DB-enforced idempotency |
| **Phase 3** | ✅ COMPLETE | 2026-05-25 | 963 tests (+3) | `recall_memory_search` tool, summary exclusion, tenant isolation |
| **Phase 4** | ✅ COMPLETE | 2026-05-25 | 9 tests (+9) | Prompt budget hardening, hierarchical truncation, Unicode-safe clipping |
| **Phase 5** | ✅ COMPLETE | 2026-05-25 | 4 tests (+4) | Archival relational + vector tier (BM25 FTS5, vec0, RRF, VectorIngestionService) |
| **Phase 6** | ✅ COMPLETE | 2026-05-26 | 2 new files, 4 modified | `archival_memory_search` tool, `ArchivalContextCache`, hybrid BM25+vector activation, `[RELEVANT KNOWLEDGE]` prompt injection |
| **Phase 7** | ✅ COMPLETE | 2026-05-26 | 5 new files, 4 modified | 4 write tools, `MemoryWriteGovernance`, CAS atomic append, approval gating, `MEMORY_WRITE_MUTATED` audit |

### A.2 Post-Phase-7 Cleanup (2026-05-28)

Pre-Phase-8 cleanup to remove deprecated `memory_search` artifacts and ensure `archival_memory_search` is fully wired.

| Change | Files | Rationale |
|---|---|---|
| **Deleted `MemorySearchTool.java`** | `arcclaw-core/.../tools/MemorySearchTool.java` | Deprecated since Phase 3; replaced by `RecallMemorySearchTool` + `ArchivalMemorySearchTool` |
| **Removed `MEMORY_SEARCH` enum** | `ToolCapability.java` | No live tool backing it; `fromToolName("memory_search")` returns null |
| **Updated `FinishOutcomeProcessor`** | `FinishOutcomeProcessor.java` | Synthesis detection now checks both `RECALL_MEMORY_SEARCH` and `ARCHIVAL_MEMORY_SEARCH` |
| **Updated `ReasoningStepParser`** | `ReasoningStepParser.java` | Garbled query fix applies to both new tools |
| **Updated `ToolInvocationGuard`** | `ToolInvocationGuard.java` | BACKGROUND autonomy exemption includes both memory tools |
| **MCP: added `archivalMemorySearch()`** | `McpToolsBean.java` | External MCP clients can now call `archival_memory_search` |
| **MCP: updated allowlist default** | `McpToolAllowlist.java` | Default: `github,recall_memory_search,archival_memory_search` |
| **Updated `PromptInstructionLibrary`** | `PromptInstructionLibrary.java` | MEMORY TOOL SELECTION section + compact rules guide LLM to use correct tool |
| **Updated prompt templates** | `role-coordinator.txt`, `role-researcher.txt`, `researcher.txt` | Both tools documented with usage guidance |
| **Fixed TODO(Phase-7)** | `ChatHistoryAssembler.java` | `WorkspaceContext.getUserIdOrUnknown()` replaces hardcoded `"default"` |
| **Updated 10 test files** | Various test classes | `MEMORY_SEARCH` → `RECALL_MEMORY_SEARCH` |
| **Updated docs** | `README.md`, `FAQ.md`, `TELEGRAM_TESTING.md`, `PROJECT-WORKFLOW.md` | Tool tables, MCP flows, role lists all include both memory tools |

### A.3 Phase 2: Critical Fixes Applied

| Issue | Resolution | Files Modified |
|---|---|---|
| **Turn number continuity** | `cumulativeNonSystemCount` tracked in metadata (monotonic across compaction) | `PersistentChatMemoryStore.java` |
| **Compaction turn alignment** | `startTurn = cumulativeCount - windowSize` (not hardcoded 0) | `CompactionService.java` |
| **Dual-write idempotency** | Both paths use `RecallMessageMapper.map()` with aligned turns | Both files above |
| **System message preservation** | `sys.text()` used (not placeholder `"[System Directive]"`) | `RecallMessageMapper.java` |

**Metadata Schema (Phase 2):**
```java
// Stored in ChatMemoryDto.metadataJson
{
  "timestamps": [1716633600000, 1716633660000, ...],
  "cumulativeNonSystemCount": 42,  // Monotonically increasing
  "windowSize": 5                   // Current active window size
}
```

**Turn Assignment Proof:**
| Scenario | cumulative | windowSize | delta | startTurn | Turn Assigned |
|---|---|---|---|---|---|
| Normal append (11→12) | 11→12 | 11→12 | +1 | 11 | 11 ✓ |
| Compaction (20→5) | 20 | 5 | 0 | 0 | 0-14 (toCompact) ✓ |
| Post-compaction (5→6) | 20→21 | 5→6 | +1 | 20 | 20 ✓ |

### A.3 Phase 3: Implementation Details

#### New Tool: `RecallMemorySearchTool`

| Feature | Implementation |
|---|---|
| **Default behavior** | Session-scoped exact history search, `COMPACTION_SUMMARY` excluded |
| **Cross-session** | Requires explicit `session_id=null` (gated, not default) |
| **Include summaries** | `include_summaries=true` flag (summaries ranked lower) |
| **Tenant isolation** | `userId` from `WorkspaceContext`, never from LLM params |
| **Tool name** | `recall_memory_search` (`ToolCapability.RECALL_MEMORY_SEARCH`) |

#### SQL Predicate (Corrected)

```sql
-- BEFORE (WRONG): includes summaries when includeSummaries=false
AND (:includeSummaries = false OR message_type != 'COMPACTION_SUMMARY')

-- AFTER (CORRECT): excludes summaries when includeSummaries=false
AND (:includeSummaries = true OR message_type != 'COMPACTION_SUMMARY')
```

**Truth Table:**
| includeSummaries | message_type | Condition | Included? |
|---|---|---|---|
| false | AGENT_MESSAGE | true OR true | ✅ Yes |
| false | COMPACTION_SUMMARY | false OR false | ❌ No |
| true | AGENT_MESSAGE | true OR true | ✅ Yes |
| true | COMPACTION_SUMMARY | true OR false | ✅ Yes |

#### Backend Implementations

| Backend | File | Search Technology |
|---|---|---|
| SQLite | `RecallMemoryJdbiDao.java` | FTS5 virtual table |
| PostgreSQL | `RecallMemoryJdbiDao.java` | tsvector + plainto_tsquery |

#### Deprecation Strategy

| Tool | Status | Migration Path |
|---|---|---|
| `memory_search` | `@Deprecated(since="Phase 3", forRemoval=true)` | Remains functional (logs warning), full removal in Phase 12 |
| `recall_memory_search` | ✅ Active | New default for exact history lookup |

#### Phase 3 Test Coverage

| Test | Purpose | Result |
|---|---|---|
| `RecallSearchCodeSnippetNoSummaryPollutionTest` | Verifies summaries excluded by default | ✅ Pass |
| `RecallSearchIncludeSummariesModeTest` | Verifies `include_summaries=true` works | ✅ Pass |
| `RecallSearchTenantIsolationTest` | Verifies cross-user access blocked | ✅ Pass |

### A.4 Test Suite Status

```
Total Tests: 1,451
  - arcclaw-core:        963 tests (960 base + 3 Phase 3)
  - arcclaw-app:         145 tests
  - arcclaw-persistence: 343 tests
Failures: 0
Errors:   0
Skipped:  1
```

### A.6 Next Steps (Phase 6+)

**Phase 4: Prompt Budget Hardening** — Recommended next phase:
- Enforce explicit section ordering in `ChatHistoryAssembler`
- Add size caps per section (tokens/chars)
- Implement deterministic overflow clipping
- Tests: `PromptSectionOrderingTest`, `MemoryPromptOverflowTest`

**Phase 5: Archival Storage Model** — Depends on Phase 4:
- Reuse existing `MemoryRetrievalPort` (already has BM25 + vector)
- Add `ArchivalChunk` DTO with typed schema
- Implement `ArchivalMemoryStorePort` in SQLite/Postgres/FS

### A.7 Architectural Wins

| Decision | Benefit |
|---|---|
| **ISP-compliant ports** | Backends implement only supported tiers (no stub methods) |
| **DB-enforced idempotency** | No race conditions, no app-level dedupe complexity |
| **Turn alignment via metadata** | Compaction + normal path produce identical `(turn, hash)` keys |
| **Summary exclusion default** | Exact code/error retrieval not polluted by high-level summaries |
| **Session-scoped default** | Precise recall within session; cross-session requires explicit opt-in |
| **Deprecation wrapper** | Zero breaking changes for existing `memory_search` users |

### A.8 Known Limitations

| Limitation | Mitigation | Planned Fix |
|---|---|---|
| No integration test for DAO layer | Mocked tool tests verify behavior | Add `RecallMemoryJdbiDaoIntegrationTest` (SQLite in-memory) |
| `MemorySearchTool` not yet routed to new tools | `@Deprecated` annotation, description updated | Phase 12: Full removal or tier-aware routing |
| Cross-session search not gated by config | Allowed via magic strings `"all"`, `"*"` | Phase 4: Add `cross-session-search` config flag |
| `purgeExpired` not implemented | Debug log stub in place | Phase 12: Retention/eviction jobs |

### A.9 Phase 3 Tactical Fixes (Post-Review)

| Fix | Description | Files Modified |
|---|---|---|
| **FTS5 Sanitization** | `Fts5QueryUtil.toFts5Phrase(query)` prevents syntax errors from special chars (`+`, `()`, `:`) | `RecallMemorySearchTool.java` |
| **Cross-Session Magic Strings** | `"all"`, `"*"`, `null` trigger cross-session search (LLM-friendly) | `RecallMemorySearchTool.java` |
| **Schema Documentation** | Updated `session_id` description to document magic strings | `RecallMemorySearchTool.java` |
| **Deprecation Log Spam** | Removed `log.warn` from `MemorySearchTool.execute()` | `MemorySearchTool.java` |
| **Purge Expired Stub** | Added `log.debugf` to `PostgresRecallMemoryStore.purgeExpired()` | `PostgresRecallMemoryStore.java` |

**PostgreSQL FTS Verification:** ✅ Trigger `recall_messages_tsv_trigger` populates `content_tsv` on INSERT/UPDATE (migration V1__baseline.sql)

**Test Status:** 960 tests passing (3 Phase 3 tests included)

### A.11 Phase 5/5b: Archival Storage Model (Implementation Details)

#### Relational Tier (Phase 5)

| Component | File | Role |
|---|---|---|
| `SqliteArchivalMemoryStore` | `arcclaw-persistence-sqlite` | `@TieredBackend("sqlite")`, FTS5 BM25 search, INSERT OR REPLACE upsert |
| `PostgresArchivalMemoryStore` | `arcclaw-persistence-postgres` | `@TieredBackend("postgres")`, tsvector/tsquery BM25 search |
| `ArchivalMemoryJdbiDao` (SQLite) | `arcclaw-persistence-sqlite/jdbi` | JDBI SQL Object with FTS5 MATCH queries |
| `ArchivalMemoryJdbiDao` (Postgres) | `arcclaw-persistence-postgres/jdbi` | JDBI SQL Object with plainto_tsquery |
| `ArchivalMemoryService` | `arcclaw-core` | `@ApplicationScoped` thin domain service, delegates to port |

**Schema:**
- `archival_chunks` table with `UNIQUE(chunk_id)` for INSERT OR REPLACE
- SQLite: FTS5 virtual table `archival_chunks_fts` with `content='archival_chunks'` + 3 sync triggers
- PostgreSQL: `content_tsv tsvector` column + GIN index + BEFORE INSERT OR UPDATE trigger
- Both: indexes on `user_id`, `chunk_type`, `created_at`

#### Vector Tier (Phase 5b)

| Component | File | Role |
|---|---|---|
| `ArchivalVecSchemaInitializer` | `arcclaw-persistence-sqlite` | `@Priority(200)`, guards on `isVectorSearchEnabled()`, creates `vec_archival` virtual table |
| `VectorIngestionService` | `arcclaw-persistence-sqlite` | Platform thread executor, serialized writes, `@PreDestroy` with 5s drain |
| `FtsCleanCallback` (updated) | `arcclaw-persistence-sqlite` | Drops `vec_archival` before Flyway clean (vec0 shadow tables) |

**Key Decisions:**

| Decision | Choice | Rationale |
|---|---|---|
| sqlite-vec version | v0.1.x (bundled native binary, not Maven) | Uses `float[N]` column syntax, `vec0()` virtual table function |
| Write serialization | `newSingleThreadExecutor(Thread.ofPlatform())` | NOT `Semaphore(1)` — guarantees one specific OS thread for native C state |
| Startup ordering | `Vec0NativeLibraryInstaller` (10) → `FtsConnectionInterceptor` (per-conn) → `ArchivalVecSchemaInitializer` (200) | vec0 loaded before DDL runs |
| Hybrid search | RRF k=60, same as `FsMemoryRetrievalAdapter` | Consistent ranking across backends |
| Float serialization | Reuses `GlobalKnowledgeBase.floatArrayToJson()` | No duplication |
| Vector search binding | Named params (`:embedding`, `:limit`) | Avoids `?1`/0-based `.bind()` ambiguity |

**Hybrid Search Architecture:**
```
useVector=false → BM25-only path (FTS5 MATCH / plainto_tsquery)
useVector=true  → BM25 + vector, merged via RRF (k=60)
vec0 absent     → graceful fallback to BM25 with one-time warn log
```

**Non-atomicity:** Relational `upsert()` and vector `ingestVector()` are independent operations. BM25 degrades gracefully if vector write fails. Documented in `ArchivalMemoryService.upsert()` Javadoc.

### A.10 Phase 4: Prompt Budget Hardening (Implementation Details)

#### Architecture

| Component | File | Role |
|---|---|---|
| `PromptBudgetManager` | `arcclaw-core/.../memory/tiered/PromptBudgetManager.java` | `@ApplicationScoped` CDI bean — two-pass hierarchical enforcement |
| `BudgetEnforcedSections` | Inner record of `PromptBudgetManager` | Return type with truncation flags |
| `CoreMemoryPromptRenderer` | Modified — removed truncation logic | Pure formatting only (budget enforcement moved to manager) |
| `ChatHistoryAssembler` | Modified — wires `PromptBudgetManager` | Raw text → enforce → SystemMessage with conditional markers |

#### Pre-Build Decisions (Locked)

| Decision | Choice | Rationale |
|---|---|---|
| **Token estimation** | `chars / 4` (named `estimateTokens()`) | Self-documenting approximation |
| **Safety margin** | 10% on TOKEN budget → `charBudget = (int)(tokenBudget * 0.90) * 4` | Math: 800 tokens → 720 tokens → 2,880 chars |
| **Truncation** | Hierarchical (Archival → Summary → Core) | Not proportional — preserves most important sections |
| **Sentinel safety** | Budget manager sees raw text only; sentinel added AFTER | Prevents `[CONTEXT FROM PRIOR SESSION]` from being truncated |
| **Budget scope** | Memory sections ONLY | System prompt, tool schemas, conversation turns excluded |

#### Two-Pass Enforcement Algorithm

```
Pass 1 — Individual Caps:
  coreCharBudget    = (maxCoreMemoryTokens * 0.9) * 4         = 2,880 chars
  summaryCharBudget = (maxCompactionSummaryTokens * 0.9) * 4  = 4,320 chars

  if core.length > coreCharBudget → truncateSafely(core, coreCharBudget)
  if summary.length > summaryCharBudget → truncateSafely(summary, summaryCharBudget)

Pass 2 — Total Cap (hierarchical):
  totalCharBudget = (totalNonConversationTokens * 0.9) * 4 = 9,000 chars
  currentTotal = len(core) + len(summary) + len(archival)

  if currentTotal > totalCharBudget:
    excess = currentTotal - totalCharBudget
    // Drop in priority order: archival first, summary second, core last
    // Use actualRemoved (not requested trim) to account for word-boundary breaks
```

#### Unicode-Safe Truncation

```java
static String truncateSafely(String text, int maxChars) {
    if (text == null || text.length() <= maxChars) return text;
    int safeIndex = maxChars;
    if (safeIndex > 0 && Character.isLowSurrogate(text.charAt(safeIndex))) {
        safeIndex--;  // Don't split surrogate pairs (emoji, CJK)
    }
    int lastSpace = text.lastIndexOf(' ', safeIndex);
    if (lastSpace > safeIndex * 0.8) safeIndex = lastSpace;  // Word boundary
    return text.substring(0, safeIndex);
}
```

**Key design:** `truncateSafely()` does NOT append markers. The caller (`ChatHistoryAssembler`) appends `"\n[... truncated to fit prompt budget]"` only when `BudgetEnforcedSections.coreWasTruncated()` / `summaryWasTruncated()` returns `true`.

#### ChatHistoryAssembler Integration

```java
// STEP -1: Core Memory → raw text (no truncation from renderer)
// STEP 0: Compaction summary → raw text from cache
// STEP 0.5: budgetManager.enforce(coreText, rawSummary, null)  ← BEFORE any SystemMessage
// STEP 1: Build SystemMessages with budget-enforced text
//   Core:    SystemMessage("[USER CONTEXT]\n" + budget.coreMemory())  + marker if truncated
//   Summary: SystemMessage(sentinel + budget.compactionSummary() + suffix) + marker if truncated
//           ↑ sentinel added AFTER budget enforcement — never truncated
```

#### Phase 4 Test Coverage

| Test | Purpose | Result |
|---|---|---|
| `PromptBudgetManagerTest.testCoreMemoryAloneExceedsBudget_trimmedWithMarker` | Solo overflow: Core exceeds `maxCoreMemoryTokens` | ✅ Pass |
| `PromptBudgetManagerTest.testCorePlusSummaryExceedTotal_summaryTrimmedFirstCorePreserved` | Combined overflow: Summary trimmed first | ✅ Pass |
| `PromptBudgetManagerTest.testAllSectionsWithinBudget_noTruncation` | No-op when within budget | ✅ Pass |
| `PromptBudgetManagerTest.testUnicodeSafety_emojiTruncatedAtValidBoundary` | Surrogate pair safety | ✅ Pass |
| `PromptBudgetManagerTest.testNullSections_returnedAsNull` | Null handling | ✅ Pass |
| `PromptBudgetManagerTest.testEstimateTokens` | Token estimation accuracy | ✅ Pass |
| `PromptBudgetManagerTest.testCalculateCharBudget_mathIsCorrect` | 10% margin math verification | ✅ Pass |
| `PromptBudgetManagerTest.testTruncateSafely_shortTextReturnedAsIs` | No-op on short text | ✅ Pass |
| `PromptBudgetManagerTest.testTruncateSafely_breaksAtWordBoundary` | Word-boundary awareness | ✅ Pass |
| `ChatHistoryAssemblerTest.testAssemble_budgetEnforced_coreMemoryTrimmedMarkerAppended` | Core Memory truncation integration | ✅ Pass |
| `ChatHistoryAssemblerTest.testAssemble_budgetEnforced_summaryTrimmedFirst` | Hierarchical trimming integration | ✅ Pass |
| `ChatHistoryAssemblerTest.testAssemble_coreMemoryAbsent_whenTieredMemoryDisabled` | Disabled mode | ✅ Pass |
| `ChatHistoryAssemblerTest.testAssemble_budgetSummarySentinelPreserved` | Sentinel integrity | ✅ Pass |

#### Post-Review Fixes Applied

| Fix | Description | Source |
|---|---|---|
| **Word-boundary excess accounting** | `excess -= actualRemoved` instead of `excess -= requestedTrim` — `truncateSafely` may break at space, removing more chars than requested | Architect review |
| **Zero-trim flag guard** | `archivalTruncated` only set when `archivalTrim > 0` (matching summary/core pattern) | Architect review |
| **Test name accuracy** | Renamed `testAssemble_budgetNotEnforced_whenDisabled` → `testAssemble_coreMemoryAbsent_whenTieredMemoryDisabled` | Claude review |

### A.4 Test Suite Status (Updated)

```
Total Tests: ~1,079 (core + persistence-sqlite)
  - arcclaw-core:        ~1,022 tests (963 base + 9 PromptBudget + 4 ArchivalMemory + 19 Phase 6 + 27 Phase 7 + 1 skip)
  - arcclaw-app:         145 tests
  - arcclaw-persistence: 57 tests (sqlite module)
Failures: 0
Errors:   0
Skipped:  1
```

### A.12 Phase 6: Archival Tools + Selective Use (Implementation Details)

#### Architecture

| Component | File | Role |
|---|---|---|
| `ArchivalMemorySearchTool` | `arcclaw-core/.../tools/memory/ArchivalMemorySearchTool.java` | `@ApplicationScoped` AgentTool — semantic archival search with optional vector hybrid |
| `ArchivalContextCache` | `arcclaw-core/.../agent/reasoning/ArchivalContextCache.java` | `@WorkspaceScoped` — pre-fetched result cache for O(1) prompt injection |
| `ChatHistoryAssembler` | Modified — wires `ArchivalContextCache` | Injects `[RELEVANT KNOWLEDGE]` section from pre-fetched cache |
| `SqliteArchivalMemoryStore` | Modified — `hybridSearch()` activated | RRF merge with `VectorIngestionService.searchVector()` when embedding provided |
| `ArchivalSearchQuery` | Modified — `float[] queryEmbedding` field added | Passes embedding from tool to store for hybrid search |
| `TieredMemoryConfig.ArchivalConfig` | Modified — `confidenceThreshold()` added | Search-time result filtering (default 0.6), separate from eviction threshold |
| `ToolCapability` | Modified — `ARCHIVAL_MEMORY_SEARCH` enum entry | Auto-discovered by `ToolDiscoveryService` via CDI |

#### Tool Execute Flow

```
Agent calls archival_memory_search(query="Quarkus patterns")
  │
  ├─ 1. Resolve userId from WorkspaceContext (never from LLM params)
  ├─ 2. Sanitize query via Fts5QueryUtil.toFts5Phrase()
  ├─ 3. Embed query (optional): Instance<EmbeddingModel>.embed(query) → float[]
  │     └─ If unavailable: queryEmbedding=null → BM25-only fallback
  ├─ 4. Build ArchivalSearchQuery(userId, safeQuery, limit, chunkTypes, useVector, useBM25, queryEmbedding)
  ├─ 5. archivalStore.search(query)
  │     ├─ SQLite: hybridSearch() → BM25 + vectorIngestion.searchVector() → mergeRrf()
  │     └─ Postgres: plainto_tsquery BM25 search (pgvector integration pending Phase 11)
  ├─ 6. Filter by confidenceThreshold (default 0.6)
  ├─ 7. Increment accessCount on returned chunks (LRU eviction signal)
  ├─ 8. Format results → cache in ArchivalContextCache
  └─ 9. Return formatted text to agent
```

#### Prompt Assembly Integration

```
ChatHistoryAssembler.assemble()
  │
  ├─ STEP -1: Core Memory → raw text
  ├─ STEP 0: Compaction summary → raw text
  ├─ STEP 0.3: ArchivalContextCache.getText() → O(1) lookup (NO I/O)     ← NEW Phase 6
  ├─ STEP 0.5: budgetManager.enforce(core, summary, archival)
  ├─ STEP 1: [USER CONTEXT] SystemMessage
  ├─ STEP 1.1: [CONTEXT FROM PRIOR SESSION] SystemMessage + sentinel
  ├─ STEP 1.5: [RELEVANT KNOWLEDGE] SystemMessage (if cache populated)   ← NEW Phase 6
  ├─ STEP 2: Chat history
  └─ STEP 3: ReAct history
```

#### SQLite Hybrid Search (Activated)

```java
// Before (Phase 5): BM25-only, TODO comment
return bm25Results.stream().limit(query.limit()).toList();

// After (Phase 6): When queryEmbedding is non-null:
List<Object[]> vectorRows = vectorIngestion.searchVector(embedding, searchLimit);
List<ArchivalChunkDto> vectorChunks = resolveFromIds(vectorRows);
return mergeRrf(bm25Results, vectorChunks, query.limit());
```

**Graceful degradation chain:**
1. `EmbeddingModel` unavailable → `queryEmbedding=null` → BM25-only
2. `EmbeddingModel.embed()` throws → catch, `queryEmbedding=null` → BM25-only
3. `vectorIngestion.searchVector()` throws → catch, BM25-only with warn log
4. `vec0` unavailable → `isVectorAvailable()` returns false → BM25-only

#### Config Additions

```yaml
# application.yaml — under arcclaw.memory.tiered.archival
confidence-threshold: ${ARCCLAW_MEMORY_ARCHIVAL_CONFIDENCE_THRESHOLD:0.6}
```

```bash
# .env.example
# ARCCLAW_MEMORY_ARCHIVAL_CONFIDENCE_THRESHOLD=0.6
```

#### Key Design Decisions

| Decision | Choice | Rationale |
|---|---|---|
| **ArchivalContextCache** | `@WorkspaceScoped`, `AtomicReference<CacheState>` | Immutable snapshot eliminates split-brain from two separate volatile fields |
| **Null-safe archival check** | `tieredMemoryConfig.archival() != null` | Prevents NPE when `TieredMemoryConfig` is mocked in tests |
| **Access count increment** | `incrementAccessCounts(List<String>)` via `@SqlBatch` | Single SQL statement instead of N individual updates |
| **Embedding in tool, not store** | Tool injects `Instance<EmbeddingModel>` | `arcclaw-core` has access to `arcclaw-rag` via Maven; avoids coupling store to embedding provider |
| **Confidence threshold in config** | Separate from eviction threshold | Search-time filtering (0.6) vs. eviction (0.5) are different concerns |
| **Empty results not cached** | Tool skips `cache.put()` on empty results | Prevents wasting prompt budget on "No relevant knowledge found" |
| **Vector chunk resolution** | `getByIds(@BindList)` batch query | Single SQL query preserves vector rank order for RRF |
| **FS backend** | Not implemented in Phase 6 | `FsArchivalMemoryStore` doesn't exist; deferred to Phase 11 (Backend Parity) |

#### Post-Review Fixes (Architect review, 2026-05-26)

| Fix | Description | Source |
|---|---|---|
| **N+1 read in hybridSearch** | Replaced per-ID `getById()` loop with `getByIds(@BindList)` batch query; preserves vector rank order via map lookup | N+1 Trap 1 |
| **N+1 write in access count** | Added `incrementAccessCounts(List<String>)` with `@SqlBatch` to port, both DAOs, both stores, and NoOp | N+1 Trap 2 |
| **`limit` schema type** | Changed `addStringProperty` to `addIntegerProperty` for LLM structured output correctness | Schema type hint |
| **`chunk_types` whitespace** | Added `trim()` + `filter(!isBlank())` to `parseChunkTypes()` | Correctness bug |
| **Empty result cache pollution** | Tool skips `cache.put()` when results are empty; assembler guard removed (structural, not string-based) | Empty-result guard |
| **Volatile pair atomicity** | Replaced two `volatile` fields with `AtomicReference<CacheState>` — single immutable snapshot | AtomicReference pattern |
| **Brittle string guard removed** | Removed `startsWith("No relevant archival")` from assembler — structural guard (tool never caches empty) replaces it | No string coupling |

#### Files Changed

| File | Action | Lines |
|---|---|---|
| `arcclaw-core/.../role/ToolCapability.java` | ADD `ARCHIVAL_MEMORY_SEARCH` enum entry | +3 |
| `arcclaw-persistence-api/.../ArchivalSearchQuery.java` | ADD `float[] queryEmbedding` field | +1 |
| `arcclaw-persistence-api/.../ArchivalMemoryStorePort.java` | ADD `incrementAccessCounts(List<String>)` | +2 |
| `arcclaw-core/.../config/TieredMemoryConfig.java` | ADD `confidenceThreshold()` to `ArchivalConfig` | +3 |
| `arcclaw-app/.../application.yaml` | ADD `confidence-threshold` key | +1 |
| `.env.example` | ADD `ARCCLAW_MEMORY_ARCHIVAL_CONFIDENCE_THRESHOLD` | +1 |
| `arcclaw-persistence-sqlite/.../SqliteArchivalMemoryStore.java` | MODIFY `hybridSearch()` batch fetch + `incrementAccessCounts` | ~35 lines changed |
| `arcclaw-persistence-sqlite/.../jdbi/ArchivalMemoryJdbiDao.java` | ADD `getByIds`, `incrementAccessCounts` | +15 |
| `arcclaw-persistence-postgres/.../PostgresArchivalMemoryStore.java` | ADD `incrementAccessCounts` | +7 |
| `arcclaw-persistence-postgres/.../jdbi/ArchivalMemoryJdbiDao.java` | ADD `getByIds`, `incrementAccessCounts` | +15 |
| `arcclaw-core/.../memory/tiered/NoOpArchivalMemoryStore.java` | ADD `incrementAccessCounts` no-op | +5 |
| `arcclaw-core/.../agent/reasoning/ArchivalContextCache.java` | NEW — `AtomicReference<CacheState>` pattern | ~55 lines |
| `arcclaw-core/.../tools/memory/ArchivalMemorySearchTool.java` | NEW — archival search AgentTool | ~220 lines |
| `arcclaw-core/.../agent/reasoning/ChatHistoryAssembler.java` | MODIFY — inject ArchivalContextCache + [RELEVANT KNOWLEDGE] | +15 lines |
| `arcclaw-core/src/test/.../ArchivalMemoryServiceTest.java` | FIX — add null for new `queryEmbedding` param | +1 line |
| 5 new test classes | ArchivalGoalMatchingTest, ArchivalConfidenceThresholdTest, ArchivalInjectionSparsityTest, ArchivalToolFirstPathTest, ArchivalCacheLookupNoIoTest | ~400 lines |

### A.13 Phase 7: Controlled Write Paths (Implementation Details)

#### Architecture

| Component | File | Role |
|---|---|---|
| `CoreMemoryReadTool` | `arcclaw-core/.../tools/memory/CoreMemoryReadTool.java` | `@ApplicationScoped` — read all or specific block by key |
| `CoreMemoryAppendTool` | `arcclaw-core/.../tools/memory/CoreMemoryAppendTool.java` | `@ApplicationScoped` — atomic append via `CoreMemoryService.append()` |
| `CoreMemoryReplaceTool` | `arcclaw-core/.../tools/memory/CoreMemoryReplaceTool.java` | `@ApplicationScoped` — full content replacement via `CoreMemoryService.upsert()` |
| `ArchivalMemoryInsertTool` | `arcclaw-core/.../tools/memory/ArchivalMemoryInsertTool.java` | `@ApplicationScoped` — `requiresManualApproval()=true`, 4000 char cap |
| `MemoryWriteGovernance` | `arcclaw-core/.../memory/tiered/MemoryWriteGovernance.java` | `@ApplicationScoped` — token bucket rate limiter (node-local, in-memory) |
| `CoreMemoryService.append()` | Modified — atomic read-modify-write | CAS retry loop reads fresh content on each attempt |
| `ToolCapability` | Modified — 4 new enum entries | `CORE_MEMORY_READ`, `CORE_MEMORY_APPEND`, `CORE_MEMORY_REPLACE`, `ARCHIVAL_MEMORY_INSERT` |
| `SecurityEventType.MEMORY_WRITE_MUTATED` | Modified — new enum constant | Mandatory audit for all memory writes |

#### CAS Atomic Append Pattern

```java
// Before (racy): read + concatenate + upsert (two concurrent appends lose data)
String existing = coreMemoryService.get(userId, blockKey).map(b -> b.content()).orElse("");
coreMemoryService.upsert(userId, blockKey, existing + "\n" + content);

// After (safe): read-modify-write inside CAS retry loop
coreMemoryService.append(userId, blockKey, content);
// Each retry reads the FRESH version, concatenates, and CAS-writes
```

#### Write Governance

```yaml
# application.yaml — node-local, in-memory token buckets
arcclaw:
  memory:
    governance:
      core-writes-per-minute: ${ARCCLAW_MEMORY_GOVERNANCE_CORE_WRITES_PER_MINUTE:5}
      archival-writes-per-hour: ${ARCCLAW_MEMORY_GOVERNANCE_ARCHIVAL_WRITES_PER_HOUR:10}
```

**Scope:** Node-local, in-memory. Rate limits reset on restart, not cluster-wide. Intentional agent-side guardrail.

#### Approval Gate Enforcement

| Tool | `requiresManualApproval()` | Autonomy Level | Behavior |
|---|---|---|---|
| `CoreMemoryReadTool` | `false` (default) | Any | Always allowed |
| `CoreMemoryAppendTool` | `false` (default) | Any | Always allowed (rate-limited) |
| `CoreMemoryReplaceTool` | `false` (default) | Any | Always allowed (rate-limited) |
| `ArchivalMemoryInsertTool` | `true` | SUPERVISED | Pauses for approval |
| `ArchivalMemoryInsertTool` | `true` | FULL | Bypasses approval |

Verified by 3 tests in `ToolInvocationGuardTest`: approval at SUPERVISED, bypass at FULL, no approval for core append.

#### Files Changed

| File | Action |
|---|---|
| `arcclaw-core/.../role/ToolCapability.java` | ADD 4 enum entries |
| `arcclaw-core/.../audit/SecurityEventType.java` | ADD `MEMORY_WRITE_MUTATED` |
| `arcclaw-core/.../memory/tiered/MemoryWriteGovernance.java` | NEW — token bucket rate limiter |
| `arcclaw-core/.../memory/tiered/CoreMemoryService.java` | ADD `append()` atomic method |
| `arcclaw-core/.../tools/memory/CoreMemoryReadTool.java` | NEW |
| `arcclaw-core/.../tools/memory/CoreMemoryAppendTool.java` | NEW |
| `arcclaw-core/.../tools/memory/CoreMemoryReplaceTool.java` | NEW |
| `arcclaw-core/.../tools/memory/ArchivalMemoryInsertTool.java` | NEW |
| `arcclaw-app/.../application.yaml` | ADD governance config |
| `.env.example` | ADD governance env vars |
| `arcclaw-core/src/test/.../MemoryWriteGovernanceTest.java` | NEW — rate limiter tests |
| `arcclaw-core/src/test/.../CoreMemoryWriteToolsTest.java` | NEW — read/append/replace tests |
| `arcclaw-core/src/test/.../ArchivalMemoryInsertToolTest.java` | NEW — insert + approval tests |
| `arcclaw-core/src/test/.../ToolInvocationGuardTest.java` | ADD 3 approval gate tests |
| `arcclaw-app/src/test/.../ChatCommandApprovalTest.java` | FIX — `@Disabled` for JLine3 hang |

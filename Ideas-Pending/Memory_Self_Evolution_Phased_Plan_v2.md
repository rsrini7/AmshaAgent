# Memory Tiered Architecture & Self-Evolution — Phased Implementation Plan

> **Status:** Revised Implementation Plan (v2.0)
> **Backlog Ref:** `Ideas-Pending/BACKLOG.md` Item #8
> **Input Docs:** `MemGPT-Style.md`, `Self-Evolution.md`, `Memory_Self_Evoluton_Plan.md` (v1.2), `Memory_self_Review.md`
> **Date:** 2026-05-24
> **Key Change:** Clean-build phased approach — no migration, zero-risk progression, workflow preservation

---

## Executive Summary

This document replaces the original 5-phase plan with a **13-phase (0-12) incremental implementation** based on detailed codebase review. The critical insight: **ArcClaw already has the right seams** (`CompactionService`, `CompactionEventBridge`, `PersistentChatMemoryStore`, `ChatHistoryAssembler`, `ReasoningMessageAssembler`, `MemorySearchTool`, `MemoryRetrievalPort`) — we refactor rather than rewrite.

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
3. [Phase 0: Contracts & Config (Inert Foundation)](#2-phase-0-contracts--config-inert-foundation)
4. [Phase 1: Core Memory Only](#3-phase-1-core-memory-only)
5. [Phase 2: Recall Persistence (No Search Yet)](#4-phase-2-recall-persistence-no-search-yet)
6. [Phase 3: Recall Search + Tool Split](#5-phase-3-recall-search--tool-split)
7. [Phase 4: Prompt Budget Hardening](#6-phase-4-prompt-budget-hardening)
8. [Phase 5: Archival Storage Model](#7-phase-5-archival-storage-model)
9. [Phase 6: Archival Tools + Selective Use](#8-phase-6-archival-tools--selective-use)
10. [Phase 7: Controlled Write Paths](#9-phase-7-controlled-write-paths)
11. [Phase 8: Self-Evolve Trace Capture](#10-phase-8-self-evolve-trace-capture)
12. [Phase 9: Rule-Based Extraction](#11-phase-9-rule-based-extraction)
13. [Phase 10: Directive Synthesis](#12-phase-10-directive-synthesis)
14. [Phase 11: Backend Parity](#13-phase-11-backend-parity)
15. [Phase 12: Hardening & Cleanup](#14-phase-12-hardening--cleanup)
16. [Critical Implementation Warnings](#15-critical-implementation-warnings)
17. [Module Mapping](#16-module-mapping)
18. [Verification Summary](#17-verification-summary)

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

**This is not "invent new architecture" — it's "refactor current codepaths into first-class tiers without breaking the existing agent loop."**

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

**Idempotency Warning:** Must prevent dual-writing same message from both normal flow and compaction flow. See Section 15.1.

### 2.3 Prompt Assembly Flow (Memory Injection)

```
ReasoningMessageAssembler.assemble()
    └─ ChatHistoryAssembler.assemble()
        ├─ [PHASE 1] CoreMemoryService.renderForPrompt() → [USER CONTEXT]
        ├─ CompactionSummaryCache.getSummary() → [CONTEXT FROM PRIOR SESSION]
        ├─ [PHASE 6] ArchivalMemoryService.search() → [RELEVANT KNOWLEDGE] (on-demand)
        ├─ [PHASE 10] SelfEvolveService.buildDirectives() → [BEHAVIORAL DIRECTIVES]
        └─ PersistentChatMemoryStore.getMessages() → recent turns
```

**Phase 4 Critical:** Explicit budget ordering prevents memory bloat and section conflicts.

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

## 2. Phase 0: Contracts & Config (Inert Foundation)

**Goal:** Define interfaces, DTOs, and config — zero runtime behavior change.

### 2.1 Deliverables

| Component | Location | Description |
|---|---|---|
| `TieredMemoryStore` interface | `arcclaw-persistence-api` | Core/Recall/Archival CRUD contracts |
| `CoreMemoryBlock`, `RecallMessage`, `ArchivalChunk` DTOs | `arcclaw-persistence-api` | Typed memory records |
| `CoreMemoryService`, `RecallMemoryService`, `ArchivalMemoryService` | `arcclaw-core` | Service façades (no implementation yet) |
| Backend selector | `arcclaw-core` | CDI producer for single active backend |
| Config properties | `application.yaml`, `.env.example` | Feature gates + backend selection |

### 2.2 Config Keys (New)

```yaml
arcclaw:
  memory:
    tiered:
      enabled: ${ARCCLAW_MEMORY_TIERED_ENABLED:false}  # Master switch
      backend: ${ARCCLAW_MEMORY_TIERED_BACKEND:sqlite}  # sqlite | postgres | fs
      core-memory:
        enabled: ${ARCCLAW_MEMORY_CORE_ENABLED:false}
        max-blocks-per-user: 20
        max-chars-per-block: 2000
      recall:
        enabled: ${ARCCLAW_MEMORY_RECALL_ENABLED:false}
        retention-days: 90
      archival:
        enabled: ${ARCCLAW_MEMORY_ARCHIVAL_ENABLED:false}
  self-evolve:
    enabled: ${ARCCLAW_SELF_EVOLVE_ENABLED:false}
```

### 2.3 Change Points

| File | Change |
|---|---|
| `arcclaw-persistence-api/src/main/java/.../TieredMemoryStore.java` | New interface |
| `arcclaw-persistence-api/src/main/java/.../dto/*.java` | New DTOs |
| `arcclaw-app/src/main/resources/application.yaml` | Add config keys |
| `.env.example` | Add env vars |
| `arcclaw-core/src/main/java/.../TieredMemoryBackendSelector.java` | New CDI producer |

### 2.4 Tests

```bash
# Config boot tests
./mvnw test -pl arcclaw-core -Dtest="TieredMemoryConfigTest"
# Selector tests (one backend resolves)
./mvnw test -pl arcclaw-core -Dtest="TieredMemoryBackendSelectorTest"
# DTO validation tests
./mvnw test -pl arcclaw-persistence-api -Dtest="MemoryDtoValidationTest"
```

### 2.5 Exit Criteria

- [ ] App boots with all new flags **disabled** and behaves exactly like pre-Phase 0
- [ ] New contracts compile
- [ ] Backend selection is deterministic
- [ ] Zero behavior change when disabled

---

## 3. Phase 1: Core Memory Only

**Goal:** Smallest tier first — CRUD + prompt injection, no tools yet.

### 3.1 Deliverables

| Component | Location | Description |
|---|---|---|
| `CoreMemoryService` impl | `arcclaw-core` | CRUD + version conflict handling |
| Backend storage | `arcclaw-persistence-*` | `core_memory_blocks` table / JSON file |
| `CoreMemoryPromptRenderer` | `arcclaw-core` | Renders `[USER CONTEXT]` block |
| Admin API | `arcclaw-app` | `GET/PUT /admin/memory/core` |

### 3.2 Change Points

| File | Change |
|---|---|
| `ChatHistoryAssembler.java` | Prepend Core Memory block before compaction summary |
| `SqliteTieredMemoryStore.java` | Implement `readCoreMemory()`, `upsertCoreMemory()` |
| `AdminMemoryResource.java` | New REST endpoints |

### 3.3 Prompt Injection Example

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

### 3.4 Tests

```bash
# CRUD tests
./mvnw test -pl arcclaw-core -Dtest="CoreMemoryServiceTest"
# Prompt assembly test (USER CONTEXT appears first)
./mvnw test -pl arcclaw-core -Dtest="ChatHistoryAssemblerCoreMemoryTest"
# Admin API integration test
./mvnw test -pl arcclaw-app -Dtest="AdminMemoryResourceTest"
```

### 3.5 Exit Criteria

- [ ] Core Memory persists and renders into prompt
- [ ] Strict size caps enforced (2000 chars/block, 20 blocks max)
- [ ] **No tools yet** (writes via admin API only)
- [ ] **No self-evolve yet**

---

## 4. Phase 2: Recall Persistence (No Search Yet)

**Goal:** Stop future data loss first — preserve exact messages before compaction destroys them.

### 4.1 Deliverables

| Component | Location | Description |
|---|---|---|
| `RecallMemoryService` | `arcclaw-core` | `insert()`, `insertBatch()`, `insertCompactionSummary()` |
| `RecallMessage` mapper | `arcclaw-core` | `ChatMessage` → `RecallMessage` conversion |
| Idempotency layer | `arcclaw-core` | Dedupe by `(session_id, turn_number, content_hash)` |
| Backend storage | `arcclaw-persistence-*` | `recall_messages` table + FTS5 virtual table |

### 4.2 Change Points

| File | Change |
|---|---|
| `PersistentChatMemoryStore.updateMessages()` | Dual-write to Recall (async) |
| `CompactionService.compact()` | Persist `toCompact` messages to Recall BEFORE summarizing |
| `CompactionEventBridge.java` | Ensure Recall writes complete before async compaction fires |

### 4.3 Idempotency Strategy

```java
// Prevent dual-write from normal flow + compaction flow
public void insertRecallMessage(RecallMessage msg) {
    String dedupeKey = String.format("%s:%d:%s",
        msg.sessionId(), msg.turnNumber(), hash(msg.content()));
    
    if (seenDedupeKey(dedupeKey)) {
        return; // Already persisted
    }
    
    // Insert and mark as seen
    store.insert(msg);
    markDedupeKeySeen(dedupeKey);
}
```

### 4.4 Compaction Flow Change

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

### 4.5 Tests

```bash
# Idempotency/dedupe tests
./mvnw test -pl arcclaw-core -Dtest="RecallMemoryIdempotencyTest"
# Integration: compacted text preserved in Recall
./mvnw test -pl arcclaw-core -Dtest="CompactionRecallPreservationTest"
# Dual-write prevention test
./mvnw test -pl arcclaw-core -Dtest="RecallDualWritePreventionTest"
```

### 4.6 Exit Criteria

- [ ] Compaction still reduces active window (existing behavior)
- [ ] Exact raw messages survive compaction in Recall storage
- [ ] **No search exposed yet** (data preserved but not queryable by agents)
- [ ] Idempotency prevents duplicate inserts

---

## 5. Phase 3: Recall Search + Tool Split

**Goal:** Make preserved data usable — split `MemorySearchTool` into tier-aware tools.

### 5.1 Deliverables

| Component | Location | Description |
|---|---|---|
| `RecallMemorySearchTool` | `arcclaw-core` | FTS5 exact-history search |
| `RecallSearchQuery` DTO | `arcclaw-core` | Session-scoped + cross-session filters |
| Backend search methods | `arcclaw-persistence-*` | FTS5 session/cross-session queries |
| Deprecation wrapper | `arcclaw-core` | Old `MemorySearchTool` routes to new tools |

### 5.2 Change Points

| File | Change |
|---|---|
| `MemorySearchTool.java` | Mark `@Deprecated`; internally route to tier-aware logic |
| `RecallMemorySearchTool.java` | New `@Tool` implementation |
| `SqliteTieredMemoryStore.java` | Implement `searchRecall()` with FTS5 |

### 5.3 Tool Semantics Split

| Old Tool | New Tools | Description |
|---|---|---|
| `memory_search` | `recall_memory_search` | Exact session history (FTS5) |
| `memory_search` | `archival_memory_search` | Semantic knowledge (BM25+vector, Phase 6) |

### 5.4 Search Query Examples

```sql
-- Session-scoped (default)
SELECT m.* FROM recall_messages m
JOIN recall_messages_fts fts ON m.rowid = fts.rowid
WHERE m.session_id = ? AND fts.content MATCH ?
ORDER BY rank LIMIT ?

-- Cross-session (requires explicit flag)
SELECT m.* FROM recall_messages m
JOIN recall_messages_fts fts ON m.rowid = fts.rowid
WHERE m.user_id = ? AND fts.content MATCH ?
ORDER BY m.created_at DESC LIMIT ?
```

### 5.5 Tests

```bash
# Search exact code snippets after compaction
./mvnw test -pl arcclaw-core -Dtest="RecallSearchCodeSnippetTest"
# Search exact error messages by session
./mvnw test -pl arcclaw-core -Dtest="RecallSearchErrorTraceTest"
# Tenant/user scoping tests
./mvnw test -pl arcclaw-core -Dtest="RecallSearchTenantIsolationTest"
```

### 5.6 Exit Criteria

- [ ] Recall search works as exact-history lookup
- [ ] Tool descriptions clearly distinguish Recall from semantic Archival
- [ ] Session-scoped search works (<10ms latency target)
- [ ] Cross-session search gated/configurable

---

## 6. Phase 4: Prompt Budget Hardening

**Goal:** Prevent memory bloat — explicit ordering, size caps, overflow handling.

### 6.1 Deliverables

| Component | Location | Description |
|---|---|---|
| `MemoryPromptBudgetPolicy` | `arcclaw-core` | Injection order + size caps |
| Config properties | `application.yaml` | Budget limits per section |
| Trimming helpers | `arcclaw-core` | Deterministic overflow clipping |

### 6.2 Change Points

| File | Change |
|---|---|
| `ChatHistoryAssembler.java` | Enforce section ordering + caps |
| `ReasoningMessageAssembler.resolveSystemPrompt()` | Prevent memory sections merging with compaction sentinel |

### 6.3 Prompt Section Order (Deterministic)

```
1. [USER CONTEXT]          (Core Memory, Phase 1)        — max 800 tokens
2. [CONTEXT FROM PRIOR]    (Compaction Summary, existing) — max 1200 tokens
3. [RECENT TURNS]          (Rolling window, existing)     — last 20 messages
4. [RELEVANT KNOWLEDGE]    (Archival, Phase 6, on-demand) — max 3 results
5. [BEHAVIORAL DIRECTIVES] (Self-Evolve, Phase 10)        — max 5 directives
```

### 6.4 Budget Config

```yaml
arcclaw:
  memory:
    prompt-budget:
      max-core-memory-tokens: 800
      max-compaction-summary-tokens: 1200
      max-archival-results: 3
      max-directives: 5
      total-non-conversation-tokens: 2500  # Hard ceiling
```

### 6.5 Tests

```bash
# Prompt section ordering tests
./mvnw test -pl arcclaw-core -Dtest="PromptSectionOrderingTest"
# Overflow clipping tests
./mvnw test -pl arcclaw-core -Dtest="MemoryPromptOverflowTest"
# Regression: compaction sentinel still works
./mvnw test -pl arcclaw-core -Dtest="CompactionSentinelRegressionTest"
```

### 6.6 Exit Criteria

- [ ] Prompt assembly order is deterministic
- [ ] Memory blocks clipped deterministically on overflow
- [ ] Compaction sentinel behavior unchanged
- [ ] Total injected context bounded by hard ceiling

---

## 7. Phase 5: Archival Storage Model

**Goal:** Semantic long-term layer — reuse `MemoryRetrievalPort`, add typed schema.

### 7.1 Deliverables

| Component | Location | Description |
|---|---|---|
| `ArchivalMemoryService` | `arcclaw-core` | Search + manual insert (no auto-promotion yet) |
| `ArchivalChunk` DTO | `arcclaw-persistence-api` | Typed chunks with provenance |
| Schema enrichment | `arcclaw-persistence-*` | `chunk_type`, `confidence`, `access_count` fields |
| Backend degradation | `arcclaw-persistence-*` | Fallback when vector unavailable |

### 7.2 Change Points

| File | Change |
|---|---|
| `MemoryRetrievalPort.java` | Extend with typed chunk support |
| `SqliteMemoryRetrievalAdapter.java` | Add `ArchivalChunk` mapping |
| `FsMemoryRetrievalAdapter.java` | Add Lucene BM25 + optional vector |

### 7.3 ArchivalChunk Schema

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

### 7.4 Backend Degradation Modes

| Backend | Full Support | Degraded Mode |
|---|---|---|
| SQLite | BM25 + sqlite-vec vector | BM25-only if sqlite-vec not loaded |
| PostgreSQL | BM25 (tsvector/GIN) + pgvector | BM25-only if pgvector not installed |
| Filesystem | BM25 (Lucene) + optional embeddings | BM25-only if `EmbeddingModel` bean unavailable |

### 7.5 Tests

```bash
# Manual insert/search tests
./mvnw test -pl arcclaw-core -Dtest="ArchivalManualInsertSearchTest"
# Vector-unavailable fallback tests
./mvnw test -pl arcclaw-persistence-sqlite -Dtest="SqliteArchivalFallbackTest"
# Provenance persistence tests
./mvwon test -pl arcclaw-core -Dtest="ArchivalProvenanceTest"
```

### 7.6 Exit Criteria

- [ ] Archival works as typed semantic store
- [ ] Independent from Recall exact history
- [ ] Backend degradation graceful (no crashes)
- [ ] **No automatic promotion yet** (manual insert only)

---

## 8. Phase 6: Archival Tools + Selective Use

**Goal:** Expose archival search — controlled prompt injection, no spam.

### 8.1 Deliverables

| Component | Location | Description |
|---|---|---|
| `ArchivalMemorySearchTool` | `arcclaw-core` | Semantic search tool |
| `ArchivalResultFormatter` | `arcclaw-core` | Compact injection format |
| Relevance filter | `arcclaw-core` | Goal-matching + confidence threshold |

### 8.2 Change Points

| File | Change |
|---|---|
| `ChatHistoryAssembler.java` | Inject `[RELEVANT KNOWLEDGE]` only when explicitly retrieved |
| `ReasoningMessageAssembler.java` | Narrow retrieval policy for goal-matching |

### 8.3 Injection Rules (Prevent Spam)

```java
// In ChatHistoryAssembler.assemble():
if (archivalSearchEnabled && goal != null) {
    List<ArchivalChunk> results = archivalMemoryService.search(
        userId, goal, limit = 3, minConfidence = 0.6);
    
    if (!results.isEmpty()) {
        String knowledgeText = formatArchivalResults(results);
        messages.add(new SystemMessage("[RELEVANT KNOWLEDGE]\n" + knowledgeText));
    }
}
// If empty or low-confidence → nothing injected
```

### 8.4 Tests

```bash
# Goal-matching tests
./mvnw test -pl arcclaw-core -Dtest="ArchivalGoalMatchingTest"
# Confidence threshold tests
./mvnw test -pl arcclaw-core -Dtest="ArchivalConfidenceThresholdTest"
# Injection sparsity tests (no prompt spam)
./mvnw test -pl arcclaw-core -Dtest="ArchivalInjectionSparsityTest"
```

### 8.5 Exit Criteria

- [ ] Only top few, deduped, relevant archival results enter prompt
- [ ] Confidence-filtered (min 0.6 default)
- [ ] Never injected if empty or low-confidence
- [ ] Goal-matched (not random knowledge dump)

---

## 9. Phase 7: Controlled Write Paths

**Goal:** Safe mutation — narrow, audited, bounded agent writes.

### 9.1 Deliverables

| Component | Location | Description |
|---|---|---|
| `CoreMemoryReadTool` | `arcclaw-core` | Read core blocks |
| `CoreMemoryAppendTool` | `arcclaw-core` | Append with CAS versioning |
| `CoreMemoryReplaceTool` | `arcclaw-core` | Replace with CAS versioning |
| `ArchivalMemoryInsertTool` | `arcclaw-core` | Manual insert (approval-gated) |
| `MemoryWriteGovernance` | `arcclaw-core` | Central rate limiting + audit |

### 9.2 Change Points

| File | Change |
|---|---|
| `ToolCapability.java` | Add `CORE_MEMORY_*`, `ARCHIVAL_MEMORY_INSERT` |
| `ToolRegistry.java` | Register new tools |
| `AuditEventLogger.java` | Log memory mutation events |

### 9.3 Write Governance Rules

| Tool | Write Scope | Approval Required? | Rate Limit | Content Cap |
|---|---|---|---|---|
| `core_memory_append` | Single block (keyed) | No | 5/minute | 2000 chars |
| `core_memory_replace` | Single block (CAS) | No | 5/minute | 2000 chars |
| `archival_memory_insert` | Single chunk | Yes (non-coordinator) | 10/hour | 4000 chars |

### 9.4 Allowed Core Memory Block Keys

```java
Set<String> ALLOWED_BLOCK_KEYS = Set.of(
    "user_profile",      // Name, role, preferences
    "active_task",       // Current goal
    "constraints",       // Hard rules (e.g. "never modify .env")
    "project_context",   // Tech stack, architecture
    "preferences"        // Coding style, communication preferences
);
```

### 9.5 Tests

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

### 9.6 Exit Criteria

- [ ] Agent writes are narrow (allowed block keys only)
- [ ] Audited (all mutations logged)
- [ ] Bounded (rate limits + content caps enforced)
- [ ] CAS versioning prevents corruption

---

## 10. Phase 8: Self-Evolve Trace Capture

**Goal:** Build trace history — event emission + buffer + flush, no synthesis yet.

### 10.1 Deliverables

| Component | Location | Description |
|---|---|---|
| `arcclaw-self-evolve` module | New module | Separate from core memory |
| `EvolutionEvent` DTO | `arcclaw-self-evolve/api` | Typed events |
| `ConversationBuffer` | `arcclaw-self-evolve/buffer` | In-memory per-session accumulation |
| `SelfEvolveService.append()` | `arcclaw-self-evolve/runtime` | Event ingestion |
| Flush triggers | `arcclaw-self-evolve/runtime` | Session end, threshold, idle timeout |
| Event store SPI | `arcclaw-self-evolve/store` | SQLite/Postgres/FS backend |

### 10.2 Change Points

| File | Change |
|---|---|
| `AgentDispatchHandler.java` | Emit `EvolutionEvent` after each reasoning step |
| Tool execution handlers | Emit `TOOL_CALL`, `TOOL_RESULT`, `TOOL_ERROR` events |
| `CompactionService.java` | Emit `COMPACTION` event |

### 10.3 Event Types

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

### 10.4 Flush Triggers

| Trigger | Condition | Action |
|---|---|---|
| Session end | Agent calls `FINISH` tool | Immediate flush |
| Event threshold | Buffer reaches `max-events` (default 12) | Flush to store |
| Idle timeout | No events for `idle-ms` (default 5 min) | Flush to store |
| Explicit | Agent calls `self_evolve_reflect` (Phase 10) | Flush + synthesize |

### 10.5 Tests

```bash
# Event emission sequence tests
./mvnw test -pl arcclaw-self-evolve -Dtest="EvolutionEventEmissionTest"
# Flush-on-threshold tests
./mvnw test -pl arcclaw-self-evolve -Dtest="ConversationBufferFlushThresholdTest"
# Disabled-feature no-op tests
./mvnw test -pl arcclaw-self-evolve -Dtest="SelfEvolveDisabledNoOpTest"
```

### 10.6 Exit Criteria

- [ ] Trace history captured reliably across sessions
- [ ] Flush logic reliable (no lost events)
- [ ] Feature can be disabled cleanly (zero overhead)
- [ ] **Nothing injected into prompts yet**

---

## 11. Phase 9: Rule-Based Extraction

**Goal:** Structured records from traces — safe, deterministic, no LLM synthesis.

### 11.1 Deliverables

| Component | Location | Description |
|---|---|---|
| `MemoryExtractorLite` | `arcclaw-self-evolve/extract` | Rule-based pattern matching |
| Extracted record DTOs | `arcclaw-self-evolve/api` | Typed records |
| SIMPLE retrieval | `arcclaw-self-evolve/retrieve` | FTS5 over extracted records |
| Deduplication layer | `arcclaw-self-evolve/internal` | Prevent duplicate extractions |

### 11.2 Record Types

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

### 11.3 Extraction Rules (Examples)

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

### 11.4 Tests

```bash
# Rule extraction tests from real traces
./mvnw test -pl arcclaw-self-evolve -Dtest="MemoryExtractorRuleTest"
# Deduplication tests
./mvnw test -pl arcclaw-self-evolve -Dtest="ExtractedMemoryDedupeTest"
# SIMPLE retrieval tests
./mvnw test -pl arcclaw-self-evolve -Dtest="SimpleRetrievalTest"
```

### 11.5 Exit Criteria

- [ ] Usable structured records without LLM synthesis dependency
- [ ] Deduplication prevents record explosion
- [ ] SIMPLE retrieval works (<10ms latency)
- [ ] Confidence scoring reasonable (0.5-0.9 range)

---

## 12. Phase 10: Directive Synthesis

**Goal:** Offline learning loop — LLM-powered synthesis, bounded prompt injection.

### 12.1 Deliverables

| Component | Location | Description |
|---|---|---|
| `InteractionPolicySynthesizer` | `arcclaw-self-evolve/policy` | LLM-driven directive generation |
| `DirectiveBundle` | `arcclaw-self-evolve/api` | Synthesized directives |
| `SelfEvolveReflectTool` | `arcclaw-self-evolve` | On-demand synthesis trigger |
| Directive storage | `arcclaw-self-evolve/store` | As `DIRECTIVE` archival chunks |

### 12.2 Change Points

| File | Change |
|---|---|
| `ChatHistoryAssembler.java` | Inject `[BEHAVIORAL DIRECTIVES]` (high-confidence only) |
| `ReasoningMessageAssembler.java` | Filter directives by goal relevance |

### 12.3 Synthesis Constraints

| Constraint | Value | Rationale |
|---|---|---|
| **Trigger** | On-demand (`self_evolve_reflect`) or daily cron | Never mid-conversation (too expensive) |
| **Confidence threshold** | Min 0.8 for injection | Suppress noisy directives |
| **Max directives** | 5 per prompt | Prevent prompt bloat |
| **Max directive length** | 600 chars each | Keep prompts readable |
| **Admin override** | `DELETE /admin/memory/directive/{id}` | Manual disable path |

### 12.4 Directive Injection Example

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

### 12.5 Tests

```bash
# Confidence threshold tests
./mvnw test -pl arcclaw-self-evolve -Dtest="DirectiveConfidenceThresholdTest"
# Low-confidence suppression tests
./mvnw test -pl arcclaw-self-evolve -Dtest="LowConfidenceDirectiveSuppressionTest"
# Admin delete/disable tests
./mvnw test -pl arcclaw-app -Dtest="DirectiveAdminDisableTest"
# Prompt injection cap tests
./mvnw test -pl arcclaw-core -Dtest="DirectiveInjectionCapTest"
```

### 12.6 Exit Criteria

- [ ] Only stable, high-confidence directives injected
- [ ] No synthesis runs in live turn path by default
- [ ] Admin can delete/disable directives
- [ ] Prompt injection bounded (max 5, 600 chars each)

---

## 13. Phase 11: Backend Parity

**Goal:** Same semantics across SQLite/PostgreSQL/Filesystem — explicit degraded modes.

### 13.1 Deliverables

| Component | Location | Description |
|---|---|---|
| `PostgresTieredMemoryStore` | `arcclaw-persistence-postgres` | Core/Recall/Archival impl |
| `FsTieredMemoryStore` | `arcclaw-persistence-fs` | Core/Recall/Archival impl |
| Self-evolve backends | `arcclaw-self-evolve/store/*` | SQLite/Postgres/FS providers |
| Capability declarations | `arcclaw-persistence-api` | Explicit feature matrix |

### 13.2 Backend Feature Matrix

| Feature | SQLite | PostgreSQL | Filesystem |
|---|---|---|---|
| Core Memory | ✅ JDBI | ✅ JDBI | ✅ JSON files |
| Recall FTS5 | ✅ FTS5 virtual table | ✅ tsvector/GIN | ✅ Lucene BM25 |
| Archival Vector | ⚠️ sqlite-vec (optional) | ⚠️ pgvector (optional) | ⚠️ Embeddings (optional) |
| Self-Evolve Store | ✅ JDBI | ✅ JDBI | ✅ JSON files |
| Advisory Locks | ❌ Not available | ✅ pg_advisory_lock | ❌ Not available |
| Nested Transactions | ⚠️ Limited | ✅ Full | ❌ Not available |

### 13.3 Degraded Mode Tests

```bash
# SQLite without sqlite-vec
./mvnw test -pl arcclaw-persistence-sqlite -Dtest="SqliteVectorFallbackTest"

# PostgreSQL without pgvector/GIN
./mvnw test -pl arcclaw-persistence-postgres -Dtest="PostgresVectorFallbackTest"

# Filesystem without embeddings
./mvnw test -pl arcclaw-persistence-fs -Dtest="FsEmbeddingFallbackTest"
```

### 13.4 Exit Criteria

- [ ] Same semantics across backends
- [ ] Degraded features explicitly documented
- [ ] No crashes when optional features unavailable
- [ ] Backend selector chooses correct implementation

---

## 14. Phase 12: Hardening & Cleanup

**Goal:** Production readiness — observability, admin APIs, deprecations, docs.

### 14.1 Deliverables

| Component | Location | Description |
|---|---|---|
| Admin APIs | `arcclaw-app` | `/admin/memory/*` endpoints |
| Observability metrics | `arcclaw-core`, `arcclaw-self-evolve` | Latency, hit rates, confidence distribution |
| Retention/eviction jobs | `arcclaw-core` | Scheduled cleanup tasks |
| Deprecation notices | `arcclaw-core` | `@Deprecated` on old tools |
| Documentation | `AGENTS.md`, module READMEs | Architecture + config docs |

### 14.2 Change Points

| File | Change |
|---|---|
| `MemorySearchTool.java` | Full deprecation (remove after 1 release cycle) |
| `CompactionService.java` | Ensure summaries not treated as canonical memory |
| `PromptTemplateEngine.java` | Verify section ordering across all roles |
| `TieredMemoryStore.java` | Add `health()` method for monitoring |

### 14.3 Observability Metrics

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

### 14.4 Tests

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

### 14.5 Exit Criteria

- [ ] Feature set stable and observable
- [ ] Old `MemorySearchTool` fully deprecated
- [ ] Compaction summaries no longer canonical memory
- [ ] Disabled tiers cleanly fall back to pre-Phase 0 behavior
- [ ] Documentation complete (AGENTS.md updated)

---

## 15. Critical Implementation Warnings

### 15.1 Do Not Dual-Write Recall Twice

**Risk:** `PersistentChatMemoryStore.updateMessages()` and `CompactionService.compact()` both persist messages → duplicates.

**Mitigation:**
```java
// Use idempotency keys
String dedupeKey = sessionId + ":" + turnNumber + ":" + contentHash;

// Check before insert
if (recallStore.exists(dedupeKey)) {
    return; // Already persisted
}
```

### 15.2 Prompt Ordering Must Be Explicit

**Risk:** Core Memory, compaction summary, directives appended ad hoc → merged into wrong sections.

**Mitigation:**
```java
// Explicit section markers in ChatHistoryAssembler
messages.add(new SystemMessage("[USER CONTEXT]\n" + coreText));  // Phase 1
messages.add(new SystemMessage("[CONTEXT FROM PRIOR SESSION]\n" + summary));  // Existing
messages.add(new SystemMessage("[BEHAVIORAL DIRECTIVES]\n" + dirText));  // Phase 10
```

### 15.3 Tool Taxonomy Must Change

**Risk:** Old `memory_search` semantics no longer match new tiers → confusion.

**Mitigation:**
- Phase 3: Deprecate `MemorySearchTool` with clear migration path
- Phase 3: New tools explicitly named `recall_memory_search`, `archival_memory_search`
- Update tool descriptions to clarify boundaries

### 15.4 Write Governance Needs Central Enforcement

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

### 15.5 Cross-Session Search Must Stay Tenant-Scoped

**Risk:** Agent in one conversation searches another user's sessions → privacy violation.

**Mitigation:**
```java
// Always extract userId from runtime context, never from LLM params
public List<RecallMessage> search(String userId, String query, boolean crossSession) {
    // userId comes from ctx.userId(), not tool arguments
    return recallStore.search(userId, query, crossSession);
}
```

### 15.6 Backend Capability Drift

**Risk:** SQLite gets new feature, Postgres/FS not updated → inconsistent behavior.

**Mitigation:**
- Define required vs optional features per backend in `BackendCapabilities` DTO
- Explicit degraded mode tests for each backend
- Feature flags per capability (e.g., `supportsVectorSearch`)

### 15.7 Self-Evolve Must Stay Offline By Default

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

## 16. Module Mapping

### 16.1 Maven Module Changes

| Module | New Packages | Description |
|---|---|---|
| `arcclaw-persistence-api` | `com.arcclaw.persistence.api.tiered.*` | `TieredMemoryStore`, DTOs |
| `arcclaw-persistence-sqlite` | `com.arcclaw.persistence.sqlite.tiered.*` | SQLite impl |
| `arcclaw-persistence-postgres` | `com.arcclaw.persistence.postgres.tiered.*` | Postgres impl |
| `arcclaw-persistence-fs` | `com.arcclaw.persistence.fs.tiered.*` | Filesystem impl |
| `arcclaw-core` | `com.arcclaw.core.memory.tiered.*` | Service façades |
| `arcclaw-self-evolve` | `com.arcclaw.selfevolve.*` | New module (see below) |

### 16.2 arcclaw-self-evolve Module Structure

```
arcclaw-self-evolve/
└── src/main/java/com/arcclaw/selfevolve/
    ├── api/                    # Public DTOs (EvolutionEvent, MemoryRecord, DirectiveBundle)
    ├── buffer/                 # ConversationBuffer, InMemoryConversationBuffer
    ├── extract/                # MemoryExtractorLite, ExtractedMemory
    ├── retrieve/               # MemoryRetrieverLite, RetrievalMode (SIMPLE/DEEP)
    ├── policy/                 # InteractionPolicySynthesizer, SynthesizedDirective
    ├── store/                  # MemoryStore SPI + sqlite/postgres/fs providers
    ├── runtime/                # DefaultSelfEvolveService, SelfEvolveBackendSelector
    └── internal/               # Mappers, utilities (not exposed)
```

### 16.3 Dependencies

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

## 17. Verification Summary

### 17.1 Phase Exit Criteria Checklist

| Phase | Key Exit Criteria |
|---|---|
| 0 | App boots with flags disabled, zero behavior change |
| 1 | Core Memory persists + renders, no tools yet |
| 2 | Recall preserves messages before compaction, idempotent |
| 3 | Recall search works, tool split complete |
| 4 | Prompt ordering deterministic, overflow handled |
| 5 | Archival storage works, no auto-promotion |
| 6 | Archival tools exposed, injection sparse |
| 7 | Write tools gated, audited, rate-limited |
| 8 | Self-evolve traces captured, no synthesis |
| 9 | Rule-based extraction works, no LLM yet |
| 10 | Directive synthesis offline, high-confidence only |
| 11 | Backend parity achieved, degraded modes documented |
| 12 | Observability complete, old tools deprecated |

### 17.2 Cross-Phase Verification Commands

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

### 17.3 Latency Targets

| Operation | Target | Phase |
|---|---|---|
| Core Memory render | <1ms | Phase 1 |
| Recall search (session) | <10ms | Phase 3 |
| Recall search (cross-session) | <50ms | Phase 3 |
| Archival hybrid search | <50ms | Phase 5 |
| Self-evolve SIMPLE retrieval | <10ms | Phase 9 |
| Self-evolve DEEP synthesis | 2-5s (offline) | Phase 10 |

---

**Document Version:** 2.0-phased
**Previous Version:** 1.2-no-migration
**Key Change:** 13 granular phases (0-12) with explicit exit criteria, workflow preservation, and zero-risk progression
**Next Step:** Begin Phase 0 implementation — contracts, DTOs, config, backend selector
**Review Status:** Pending team review

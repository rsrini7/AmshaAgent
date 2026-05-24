# Memory Tiered Architecture & Self-Evolution Plan

> **Status:** Draft — Detailed Architecture & Implementation Plan
> **Backlog Ref:** `Ideas-Pending/BACKLOG.md` Item #8
> **Input Docs:** `MemGPT-Style.md`, `Self-Evolution.md`
> **Date:** 2026-05-24

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Architecture Overview](#2-architecture-overview)
3. [Current State Assessment](#3-current-state-assessment)
4. [Tiered Memory Design (MemGPT-Style)](#4-tiered-memory-design-memgpt-style)
5. [Self-Evolution Design (Hermes-Style)](#5-self-evolution-design-hermes-style)
6. [Pluggable Backend SPI](#6-pluggable-backend-spi)
7. [Agent-Facing Memory Tools](#7-agent-facing-memory-tools)
8. [Integration Points with Existing Code](#8-integration-points-with-existing-code)
9. [Implementation Phases](#9-implementation-phases)
10. [Data Flow & Lifecycle](#10-data-flow--lifecycle)
11. [Configuration](#11-configuration)
12. [Risks & Mitigations](#12-risks--mitigations)
13. [Verification Plan](#13-verification-plan)

---

## 1. Problem Statement

### 1.1 Current Pain Points

| # | Problem | Root Cause |
|---|---------|------------|
| P1 | **Exact details lost** — code snippets, stack traces, error messages are destroyed during summarization | `CompactionService` collapses N messages into a single LLM-generated summary blob |
| P2 | **"Lost in the Middle"** — important context from 8-15 turns ago is unreachable | Rolling window of 20 recent non-system messages; older turns are completely discarded |
| P3 | **No self-improvement** — agent repeats the same mistakes across sessions | No mechanism to learn from execution traces, failure patterns, or successful resolutions |
| P4 | **No persistent user model** — agent does not remember user preferences, project context, or past decisions | No curated long-term memory tier; everything is either in the summary or gone |
| P5 | **Compaction is a black box** — the LLM decides what to keep, with no agent agency or user override | Compaction is purely system-driven; no tools for the agent to manage its own memory |

### 1.2 What MemGPT-Style Memory Solves

- **Core Memory** → solves P4 (persistent user model always in context)
- **Recall Memory** → solves P1 (exact message retrieval via FTS5) and P2 (searchable full history beyond rolling window)
- **Archival Memory** → solves P4 long-term (semantic/knowledge store for project context, reusable patterns)

### 1.3 What Hermes-Style Self-Evolution Solves

- **Trace Analysis** → solves P3 (captures execution traces across sessions)
- **Directive Synthesis** → converts stable patterns into agent behavior guidance
- **Tool Description Evolution** → improves tool documentation from real usage data

---

## 2. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           AGENT RUNTIME                                     │
│                                                                             │
│  ┌──────────────┐   ┌──────────────────┐   ┌────────────────────────────┐   │
│  │ CoreMemory   │   │  RecallMemory    │   │  ArchivalMemory            │   │
│  │ (System      │   │  (FTS5 + Exact   │   │  (sqlite-vec / Lucene      │   │
│  │  Prompt)     │   │   Message Store) │   │   Semantic + BM25 Hybrid)  │   │
│  └──────┬───────┘   └────────┬─────────┘   └─────────────┬──────────────┘   │
│         │                    │                           │                  │
│         ▼                    ▼                           ▼                  │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │                     MemoryService (Backend-Agnostic)                   │ │
│  │                                                                        │ │
│  │  coreMemoryAppend()    recallSearch()    archivalSearch()              │ │
│  │  coreMemoryReplace()   recallInsert()    archivalInsert()              │ │
│  │  coreMemoryRead()      recallGetById()   archivalGetById()             │ │
│  └────────────────────────┬───────────────────────────────────────────────┘ │
│                           │                                                 │
│  ┌────────────────────────▼───────────────────────────────────────────────┐ │
│  │                    TieredMemoryStore (SPI)                             │ │
│  │                                                                        │ │
│  │  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────────┐  │ |
│  │  │ SqliteMemoryStore │  │PostgresMemoryStore│  │ FilesystemMemoryStore │ │
│  │  └──────────────────┘  └──────────────────┘  └──────────────────────┘  │ |
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                             │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │                     SelfEvolveService                                  │ │
│  │                                                                        │ │
│  │  append(EvolutionEvent)   flush(SessionKey)   buildDirectives(UserKey) │ │
│  │  search(UserKey, query)                                                │ │
│  └──────────────┬────────────────────────────┬────────────────────────────┘ │
│                 │                            │                              │
│  ┌──────────────▼──────────┐   ┌─────────────▼───────────────────────────┐  │
│  │  ConversationBuffer     │   │  InteractionPolicySynthesizer           │  │
│  │  (in-memory, per-session│   │  (LLM-driven: event trace → directives) │  │
│  │   event accumulation)   │   │                                         │  │
│  └─────────────────────────┘   └─────────────────────────────────────────┘  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│                      AGENT-FACING TOOLS                              │
│                                                                      │
│  ┌─────────────────────┐  ┌───────────────────┐  ┌────────────────┐  │
│  │ core_memory_append  │  │ recall_memory_    │  │ archival_memory│  │
│  │ core_memory_replace │  │ search            │  │ _search        │  │
│  └─────────────────────┘  └───────────────────┘  └────────────────┘  │
│                                                                      │
│  ┌─────────────────────┐  ┌───────────────────┐                      │
│  │ core_memory_read    │  │ self_evolve_      │                      │
│  │                     │  │ reflect           │                      │
│  └─────────────────────┘  └───────────────────┘                      │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 3. Current State Assessment

### 3.1 What ArcClaw Already Has (Reusable)

| Existing Component | Maps To | Reuse Strategy |
|---|---|---|
| `CompactionService` | Context budget fallback | Keep as-is; becomes fallback when context window overflows. Summary → stored in **Recall Memory** as a special record, NOT the canonical history. |
| `MemoryRetrievalPort` + backends | Archival Memory base | The vector/BM25/hybrid search pipeline is already implemented. Extend schemas to support the `MemoryRecord` types needed for archival. |
| `MemorySearchTool` | evolve into `recall_memory_search` + `archival_memory_search` | Refactor into two separate tools with explicit tier targeting. |
| `PersistentChatMemoryStore` | Recall Memory persistence | The serialization + chat-memory JSON store becomes the exact-message persistence layer for Recall. |
| `CompactionStorePort.search()` | FTS5 keyword search | Already provides FTS5 over summaries. Extend to full message content FTS5. |
| `CompactionEventBridge` | Recall Memory write trigger | Hook into the same async event to append raw messages to Recall before compaction fires. |
| `ChatHistoryAssembler` | System prompt assembly | Inject Core Memory block + Recall search results into the assembled prompt. |

### 3.2 What Needs to Be Built (Net-New)

| Component | Effort | Description |
|---|---|---|
| `TieredMemoryStore` SPI | Medium | New interface + 3 backend implementations (SQLite, Postgres, FS) |
| `CoreMemoryService` | Small | CRUD for small, curated blocks rendered into system prompt |
| `RecallMemoryService` | Medium | Exact message persistence with FTS5 indexing; session-scoped querying |
| `ArchivalMemoryService` | Medium | Chunked semantic + keyword store built on existing `MemoryRetrievalPort` |
| Agent Memory Tools (5 total) | Small | New `@Tool` implementations delegating to the services |
| `SelfEvolveService` + SPI | Large | Conversation buffer, extractor, retriever, policy synthesizer |
| `InteractionPolicySynthesizer` | Medium | LLM-driven directive synthesis from event traces |

---

## 4. Tiered Memory Design (MemGPT-Style)

### 4.1 Core Memory

**Purpose:** Always-present, curated context injected into the system prompt. Small enough to never be truncated.

**Storage Model:**
```sql
-- core_memory_blocks
block_id        TEXT PRIMARY KEY,   -- e.g. "prefs", "task_state", "constraints"
user_id         TEXT NOT NULL,
block_key       TEXT NOT NULL,      -- semantic key: "language_prefs", "active_project"
content         TEXT NOT NULL,      -- the actual memory text (max 2000 chars per block)
version         INTEGER NOT NULL,   -- for CAS updates
created_at      TIMESTAMP,
updated_at      TIMESTAMP
UNIQUE(user_id, block_key)
```

**Content Examples:**
- `user_profile`: "User is Srinivasan, a senior Java developer. Prefers concise answers. Uses Quarkus + Maven."
- `active_task`: "Debugging a race condition in CompactionEventBridge. Target: fix latch leak."
- `constraints`: "Never modify .env files. Always run ./mvnw compile -DskipTests after Java edits."
- `project_context`: "ArcClaw is a Quarkus-based agent framework. 12 persona roles..."

**Rendering in System Prompt:**
```
[USER CONTEXT]
Name: Srinivasan
Role: Senior Java Developer, ArcClaw Maintainer
Preferences: Concise answers, no emojis, always compiles after Java edits

[ACTIVE TASK]
Current goal: Debug race condition in CompactionEventBridge latch management
Active constraints: Do not modify .env or application.yaml without explicit approval

[PERSISTENT NOTES]
- ArcClaw runs on SQLite WAL mode; no long transactions during LLM calls
- fix_fqns.py must be run after any Java source changes
```

**Size Budget:** Target ~500-800 tokens total for all core memory blocks combined.

### 4.2 Recall Memory

**Purpose:** Searchable, exact-message conversation history. Replaces the destructive summarization with full preservation. Compaction continues to run but stores its output as a curated summary *record* in Recall, not as a replacement for the raw messages.

**Storage Model:**
```sql
-- recall_messages
message_id      TEXT PRIMARY KEY,
session_id      TEXT NOT NULL,
user_id         TEXT NOT NULL,
turn_number     INTEGER NOT NULL,    -- ordinal within session
role            TEXT NOT NULL,        -- USER, AGENT, TOOL, SYSTEM
message_type    TEXT NOT NULL,        -- TEXT, TOOL_CALL, TOOL_RESULT, ERROR, COMPACTION_SUMMARY
content         TEXT NOT NULL,        -- full original message content (preserved exactly)
tool_name       TEXT,                 -- if message_type = TOOL_RESULT, name of tool
tool_call_id    TEXT,                 -- correlation for parallel tool calls
has_code        BOOLEAN DEFAULT FALSE,  -- FTS-optimized: flag for code-containing messages
has_error       BOOLEAN DEFAULT FALSE,  -- FTS-optimized: flag for error-containing messages
created_at      TIMESTAMP NOT NULL,
metadata_json   TEXT                   -- extensible JSON: tags, embeddings, etc.
);

-- FTS5 virtual table over recall_messages.content
CREATE VIRTUAL TABLE recall_messages_fts USING fts5(
    content,
    content=recall_messages,
    content_rowid=rowid
);

-- Triggers to keep FTS in sync (auto-populated)
```

**Key Design Decisions:**
1. **Compaction summary becomes a record, not a replacement.** When compaction fires, a `message_type = COMPACTION_SUMMARY` record is inserted into recall_messages. Raw messages remain searchable.
2. **`has_code` / `has_error` columns** enable targeted search (e.g. "find all code blocks from the debugging session on Tuesday").
3. **Session-scoped search default.** `recall_memory_search` defaults to current session; cross-session search requires explicit flag.
4. **Message retention policy.** Configurable TTL per user/tenant (`arcclaw.memory.recall.retention-days`, default 90 days).

**Difference from Current `PersistentChatMemoryStore`:**
- Current: Rolling window of 20 messages; non-queryable except by loading the whole blob.
- New: Full history preserved; FTS5-indexed; queried on-demand by the agent.

### 4.3 Archival Memory

**Purpose:** Semantic long-term knowledge store. Chunked embeddings + BM25 hybrid search for project context, reusable patterns, and learned facts that outlive individual sessions.

**Storage Model:**
```sql
-- archival_chunks
chunk_id        TEXT PRIMARY KEY,
user_id         TEXT NOT NULL,
content         TEXT NOT NULL,
chunk_type      TEXT NOT NULL,       -- CODE, DOC, PATTERN, FACT, PREFERENCE, DIRECTIVE
source_session  TEXT,                -- which session produced this chunk
source_message  TEXT,                -- which message (for provenance trace)
tags            TEXT,                -- comma-separated or JSON array
embedding       BLOB,                -- vector embedding (768-dim float array)
created_at      TIMESTAMP,
updated_at      TIMESTAMP,
confidence      REAL DEFAULT 1.0,    -- 0.0-1.0; auto-extracted = lower, human-reviewed = 1.0
access_count    INTEGER DEFAULT 0    -- for LRU eviction and relevance ranking
);
```

**How It Differs from Existing `MemoryRetrievalPort`:**
- Current: Generic text ingestion with metadata. Used by `MemorySearchTool` (Tier B) and `IngestDocumentTool`.
- New: Typed chunks (`FACT`, `PATTERN`, `PREFERENCE`, etc.) with confidence scoring and provenance. The existing Lucene/BM25/vector pipeline is reused; the schema is enriched with domain-specific chunk types and access tracking.

**Promotion Rules (Recall → Archival):**
1. **Frequency threshold:** A fact mentioned or retrieved 3+ times across sessions → promoted to Archival.
2. **Explicit agent action:** Agent calls `archival_memory_insert` tool (controlled, not freely available to all roles).
3. **Self-evolution synthesis:** `InteractionPolicySynthesizer` produces `DIRECTIVE`-type archival chunks from trace analysis.
4. **Manual admin:** Admin UI or API endpoint for curating archival memories.

**Demotion/Evection:**
- `access_count`-based LRU: chunks not accessed in 30+ days are flagged for review.
- Confidence decay: auto-extracted chunks (confidence < 0.7) not re-accessed within 60 days are evicted.

### 4.4 Summary: Tier Comparison

| Dimension | Core | Recall | Archival |
|---|---|---|---|
| **Size** | ~500-800 tokens | Unlimited (TTL-governed) | Unlimited (LRU-evicted) |
| **Access Pattern** | Always in context | On-demand FTS5 search | On-demand semantic search |
| **Latency** | Zero (pre-loaded) | <10ms (SQLite FTS5) | <50ms (BM25/vector hybrid) |
| **Persistence** | User-scoped, persistent | Session-scoped, TTL | User-scoped, permanent-ish |
| **Write Access** | Agent (narrow fields) + System | System-only (auto-logged) | Agent (promoted) + System + Admin |
| **Content** | Profile, preferences, active task, constraints | Exact messages, tool outputs, errors | Patterns, facts, code snippets, directives |
| **Compaction Role** | None | Summary stored as a record, not a replacement | N/A |

---

## 5. Self-Evolution Design (Hermes-Style)

### 5.1 Core Concepts

The self-evolution system is an **offline learning loop** that runs between user sessions to improve agent behavior based on execution traces.

```
Execution Trace → ConversationBuffer → flush → MemoryExtractorLite → MemoryStore
                                                                          │
                    ┌─────────────────────────────────────────────────────┘
                    ▼
            InteractionPolicySynthesizer (LLM-powered)
                    │
                    ▼
            DirectiveBundle → Archival Memory (DIRECTIVE chunks) → Injected into system prompt
```

### 5.2 ConversationBuffer

**Purpose:** In-memory accumulation of `EvolutionEvent`s per session. Buffers events until a flush trigger fires.

**Flush Triggers (ordered by priority):**
1. **Session end:** Agent calls `FINISH` → implicit flush.
2. **Event threshold:** `arcclaw.self-evolve.flush.max-events` reached (default 12).
3. **Idle timeout:** No events for `arcclaw.self-evolve.flush.idle-ms` (default 5 minutes).
4. **Explicit agent action:** Agent calls `self_evolve_reflect` tool.
5. **Periodic cron:** Every 30 minutes for long-running sessions.

**Event Types:**
```java
public enum EvolutionEventType {
    USER_MESSAGE,       // inbound user text
    AGENT_MESSAGE,      // agent response text (before tool calls)
    TOOL_CALL,          // agent invokes a tool (name + params)
    TOOL_RESULT,        // tool execution output
    TOOL_ERROR,         // tool execution failed
    RESOLUTION,         // task completed successfully
    ESCALATION,         // task escalated to HITL or another agent
    COMPACTION,         // context compaction occurred
    SELF_CORRECTION     // agent noticed and fixed its own mistake
}
```

### 5.3 MemoryExtractorLite

**Purpose:** Converts a batch of `EvolutionEvent`s into typed `MemoryRecord`s. Can be **rule-based** (fast, no LLM call) or **LLM-driven** (slower, higher quality).

**Rule-Based Extraction (v1):**
- Pattern matching for common structures:
  - User says "I prefer X" → `USER_PREFERENCE`
  - Tool fails 2+ times consecutively with same tool → `FAILURE_PATTERN`
  - Agent resolves task with FINISH → `RESOLUTION_PATTERN`
  - User provides project details → `PROJECT_CONTEXT`
  - Repeated tool success pattern → `TOOL_HINT`
- Confidence scoring: rule-based = 0.5-0.7; LLM-extracted = 0.7-0.95

**LLM-Driven Extraction (v2):**
- Prompt: "Given these conversation events, extract stable facts about the user, successful resolution patterns, and repeated failure patterns. Output as structured JSON."
- Uses the existing compaction LLM provider (or a cheaper model) to keep cost low.
- Results cached per user to avoid re-extracting from the same events.

### 5.4 InteractionPolicySynthesizer

**Purpose:** The "Hermes core" — periodically synthesizes agent behavioral directives from accumulated memory records. This is the self-evolution step.

**Input:**
- Recent `MemoryRecord`s (last 30 days, all types)
- `RESOLUTION_PATTERN` records (what worked)
- `FAILURE_PATTERN` records (what to avoid)
- `USER_PREFERENCE` records (user-specific constraints)
- Current system prompt for the role

**Output:**
```
DirectiveBundle {
    stableDirectives: [
        "When debugging Java, always run ./mvnw compile -DskipTests before suggesting changes",
        "User prefers streams over for-loops; refactor suggestions accordingly",
        "Never suggest modifying .env files without explicit user approval"
    ],
    recentInsights: [
        "Last 3 sessions: coordinating agents need explicit tool result summaries to avoid redundant calls",
        "Researcher agent tends to loop; add confidence threshold to Search → Reflect transitions"
    ],
    supportingMemories: [/* MemoryRecord references for traceability */]
}
```

**Synthesis Frequency:**
- Triggered by: explicit `self_evolve_reflect` tool call OR periodic cron (daily) OR admin request.
- NOT triggered automatically mid-conversation (too expensive/latency-heavy).

**How Directives Are Applied:**
- Stored as `DIRECTIVE`-type Archival Memory chunks.
- Injected into the system prompt by `ChatHistoryAssembler` via a `[BEHAVIORAL DIRECTIVES]` section.
- Weighted by confidence: directives with confidence > 0.8 are always shown; 0.5-0.8 are shown with note "low confidence"; < 0.5 are not shown.

### 5.5 Self-Evolution Feedback Loop

```
Session N                      Offline                    Session N+1
─────────                      ───────                    ──────────
Execution Trace                Synthesis                  System Prompt
     │                              │                         ▲
     ▼                              ▼                         │
ConversationBuffer ──flush──► MemoryExtractorLite             │
     │                              │                         │
     │                              ▼                         │
     │                        MemoryStore                     │
     │                              │                         │
     │                              ▼                         │
     │                   InteractionPolicySynthesizer ────────┘
     │                              │
     │                              ▼
     │                        DirectiveBundle ──► Archival Memory (DIRECTIVE chunks)
     │
     └──────────────────────────────────────────────────────────────► Recall Memory (exact events)
```

---

## 6. Pluggable Backend SPI

### 6.1 TieredMemoryStore Interface

```java
package com.arcclaw.memory.tiered.api;

public interface TieredMemoryStore {

    // ── Core Memory ──────────────────────────────────────
    List<CoreMemoryBlock> readCoreMemory(String userId);
    void upsertCoreMemory(String userId, String blockKey,
                          String content, int expectedVersion) throws VersionConflictException;
    void deleteCoreMemory(String userId, String blockKey);

    // ── Recall Memory ────────────────────────────────────
    void insertRecallMessage(RecallMessage message);
    List<RecallMessage> searchRecall(String userId, String query,
                                     String sessionId, int limit,
                                     boolean crossSession,
                                     Set<String> messageTypes);
    Optional<RecallMessage> getRecallById(String messageId);

    // ── Archival Memory ─────────────────────────────────
    void upsertArchivalChunk(ArchivalChunk chunk);
    List<ArchivalChunk> searchArchival(String userId, String query,
                                       int limit, Set<String> chunkTypes,
                                       boolean useVector, boolean useBM25);
    Optional<ArchivalChunk> getArchivalById(String chunkId);
    void incrementAccessCount(String chunkId);
    void deleteArchivalChunk(String chunkId);

    // ── Health & Admin ──────────────────────────────────
    TieredMemoryHealth health();
    void purgeExpiredRecall(String userId, int retentionDays);
    void evictLowConfidenceArchival(double threshold, int minAgeDays);
}
```

### 6.2 Backend Implementations

| Backend | Core | Recall | Archival | Best For |
|---|---|---|---|---|
| **SQLite** | JDBI / JPA | JDBI + FTS5 | Existing `SqliteMemoryRetrievalAdapter` + `sqlite-vec` | Local dev, single-node |
| **PostgreSQL** | JDBI | JDBI + `tsvector`/GIN | Existing `PostgresMemoryRetrievalAdapter` + `pgvector` | Multi-instance, production |
| **Filesystem** | JSON files | JSON files + `LuceneIndexManager` (new BM25 index) | Existing `FsMemoryRetrievalAdapter` + Lucene | Minimal, experimental |

### 6.3 Backend Selection

```yaml
arcclaw:
  memory:
    tiered:
      backend: ${ARCCLAW_MEMORY_BACKEND:sqlite}   # sqlite | postgres | fs
```

Single backend selected at startup via `@IfBuildProfile` or CDI `@Produces` with `@LookupIfProperty`. Fails fast if configured backend is unavailable.

### 6.4 Why Not Reuse MemoryRetrievalPort Directly?

The existing `MemoryRetrievalPort` is designed for generic text/vector search. The tiered memory store adds:
1. **Typed schemas** (CoreMemoryBlock, RecallMessage, ArchivalChunk) with domain-specific fields.
2. **Tier awareness** — searches respect tier boundaries (recall ≠ archival).
3. **CAS-based concurrency** for Core Memory updates.
4. **Access tracking** for archival eviction decisions.

`MemoryRetrievalPort` becomes the **underlying indexing engine** for Archival Memory, not the direct API.

---

## 7. Agent-Facing Memory Tools

### 7.1 Tool Catalog

| Tool Name | Tier | Roles | Description |
|---|---|---|---|
| `core_memory_append` | Core | all | Append to a core memory block (keyed). Agent-accessible, narrow permissions. |
| `core_memory_replace` | Core | all | Replace a core memory block. Requires CAS version check. |
| `core_memory_read` | Core | all | Read all core memory blocks for current user. |
| `recall_memory_search` | Recall | all | Search exact conversation history via FTS5. Defaults to current session; supports cross-session. |
| `archival_memory_search` | Archival | coordinator, researcher, architect, synthesizer | Semantic + BM25 hybrid search over long-term knowledge base. |
| `archival_memory_insert` | Archival | coordinator, synthesizer | Insert an explicit chunk into archival. Controlled; requires approval for non-coordinator roles. |
| `self_evolve_reflect` | Self-Evolve | coordinator, architect | Trigger synthesis of behavioral directives from recent traces. Heavy operation. |

### 7.2 Tool Implementation Pattern

Each tool follows existing ArcClaw patterns:
```java
@ToolMetadata(
    capabilityId = "recall_memory_search",
    defaultRoles = {"architect", "coordinator", "researcher", "synthesizer", "debugger"},
    ...)
@ApplicationScoped
public class RecallMemorySearchTool implements AgentTool {
    // delegates to TieredMemoryService.searchRecall(...)
}
```

### 7.3 Write Governance

Following the MemGPT-Style.md advice, writes are tightly controlled:

| Tool | Write Scope | Approval Required? | Rate Limit |
|---|---|---|---|
| `core_memory_append` | Single block (max 2000 chars) | No | 5/minute |
| `core_memory_replace` | Single block (CAS versioned) | No | 5/minute |
| `archival_memory_insert` | Single chunk | Yes (for non-coordinator) | 10/hour |
| `self_evolve_reflect` | DirectiveBundle write | Yes (expensive) | 1/hour |

**System writes (not agent-triggered):**
- `recall_memory_insert` — automatic, every user/agent/tool message is persisted.
- Core memory block `active_task` — updated by system when agent FINISHES a task.
- Archival chunk promotion — system rule-based (frequency threshold, not agent-initiated).

---

## 8. Integration Points with Existing Code

### 8.1 CompactionService → Recall Memory Bridge

**Change:** Before compaction destroys messages, persist them all to Recall Memory.

```java
// In CompactionService.compact():
// (existing code) build toCompact + toKeep message lists
// NEW: persist toCompact messages to Recall Memory before summarizing
for (ChatMessage msg : toCompact) {
    recallMemoryService.insertRecallMessage(fromChatMessage(msg, sessionId, userId));
}
// (existing code) send toCompact to LLM for summarization
// (existing code) store summary as COMPACTION_SUMMARY record in Recall, not as replacement
// (existing code) replace conversation in PersistentChatMemoryStore with toKeep only
```

**Result:** Compaction still reduces context window size (toKeep messages), but the destroyed messages are now retrievable via `recall_memory_search`.

### 8.2 ChatHistoryAssembler → Core Memory Injection

**Change:** `ChatHistoryAssembler.assemble()` prepends Core Memory block to the conversation.

```java
// After building the linear message list:
List<ChatMessage> messages = new ArrayList<>();

// 1. Inject Core Memory as a SystemMessage (if present)
String coreMemoryText = coreMemoryService.renderForPrompt(userId);
if (coreMemoryText != null && !coreMemoryText.isBlank()) {
    messages.add(new SystemMessage("[USER CONTEXT]\n" + coreMemoryText));
}

// 2. Inject compaction summary sentinel (existing behavior)
compactionSummaryCache.getSummary(sessionId).ifPresent(summary ->
    messages.add(new SystemMessage(COMPACTED_SUMMARY_SENTINEL + "\n" + summary)));

// 3. Inject behavioral directives (from self-evolution)
DirectiveBundle directives = selfEvolveService.buildDirectives(userKey);
if (!directives.stableDirectives().isEmpty()) {
    String dirText = "[BEHAVIORAL DIRECTIVES]\n" + String.join("\n- ", directives.stableDirectives());
    messages.add(new SystemMessage(dirText));
}

// 4. Append session history (existing behavior)
messages.addAll(historyMessages);
```

### 8.3 ReasoningMessageAssembler → Directives & Memory Context

**Change:** The assembler filters/processes the directives and memory blocks added by ChatHistoryAssembler.

- Compaction sentinel: continues to be extracted and prepended to the system prompt (existing behavior).
- Core Memory block: passed through (it's informational context, not instructions).
- Behavioral directives: filtered for relevance to current goal using `ToolSearchIndex`-style keyword matching.

### 8.4 PersistentChatMemoryStore → Recall Memory Dual-Write

**Change:** `PersistentChatMemoryStore.updateMessages()` also writes to Recall Memory.

```java
@Override
public void updateMessages(Object memoryId, List<ChatMessage> messages) {
    // Existing persistence
    chatMemoryStorePort.save(...);
    // NEW: async append to Recall for FTS searchability
    for (ChatMessage msg : messages) {
        recallMemoryService.insertAsync(fromChatMessage(msg, sessionId, userId));
    }
}
```

### 8.5 AgentDispatchHandler → Self-Evolution Event Emission

**Change:** After each agent reasoning step and tool execution, emit an `EvolutionEvent` into the `ConversationBuffer`.

```java
// In the agent loop (within AgentDispatchHandler or equivalent):
// After tool result received:
selfEvolveService.append(new EvolutionEvent(
    eventId, sessionKey, userKey,
    EvolutionEventType.TOOL_RESULT,
    "system", toolOutput, metadata, Instant.now()
));
```

### 8.6 Existing MemorySearchTool → Refactored

**Change:** Split `MemorySearchTool` into `RecallMemorySearchTool` and `ArchivalMemorySearchTool`.

- `MemorySearchTool.execute()` currently does Tier A (compaction FTS) + Tier B (vector/RAG). This is preserved as `recall_memory_search` (FTS over conversation + compaction summaries).
- `archival_memory_search` is a new tool that targets only the Archival Memory store.
- `MemorySearchTool` is deprecated and eventually removed after migration period.

---

## 9. Implementation Phases

### Phase 0: SPI Foundation (Week 1-2)

**Goal:** TieredMemoryStore interface + SQLite backend + Core Memory service.

**Deliverables:**
1. `TieredMemoryStore` interface (all methods, with `default` no-op stubs for unimplemented tiers).
2. `CoreMemoryBlock`, `RecallMessage`, `ArchivalChunk` DTOs.
3. `CoreMemoryService` — CRUD + prompt rendering.
4. `SqliteTieredMemoryStore` — Core Memory only (JDBI). Recall and Archival throw `UnsupportedOperationException`.
5. `application.yaml` changes: `arcclaw.memory.tiered.*` config keys.
6. `.env.example` entries.
7. Unit tests for Core Memory CRUD.

**No agent tools yet.** Validation: Core Memory is persisted and can be read via service call.

### Phase 1: Recall Memory (Week 2-3)

**Goal:** FTS5-backed exact message persistence + search.

**Deliverables:**
1. SQLite schema + Flyway migration for `recall_messages` + FTS5 virtual table.
2. `RecallMemoryService` with `insert`, `search`, `getById`.
3. Integration: `PersistentChatMemoryStore` dual-writes to Recall.
4. Integration: `CompactionService` persists toCompact before summarizing.
5. `recall_memory_search` agent tool.
6. `RecallMemorySearchTool` with `@ToolMetadata`.
7. `CompactionEventBridge` or equivalent ensures Recall writes happen before async compaction destroys messages.
8. Integration tests: search for exact code snippets and error traces from prior turns.

**Rollback plan:** Feature-gated behind `arcclaw.memory.tiered.recall-enabled` (default `false`). Disabling restores current compaction-only behavior.

### Phase 2: Archival Memory (Week 3-5)

**Goal:** Semantic + BM25 hybrid search with typed chunk storage.

**Deliverables:**
1. Extend existing `MemoryRetrievalPort` schemas with `chunkType`, `confidence`, `accessCount` fields.
2. SQLite schema + Flyway migration for `archival_chunks` with `sqlite-vec` embedding column.
3. `ArchivalMemoryService` — search, insert, evict.
4. `archival_memory_search` and `archival_memory_insert` agent tools.
5. Promotion rules engine: frequency-based auto-promotion from Recall → Archival.
6. LRU eviction based on `access_count`.
7. Integration with `ChatHistoryAssembler`: inject relevant archival results into prompt as `[RELEVANT KNOWLEDGE]`.

**Reuse:** The existing Lucene/sqlite-vec/pgvector pipelines from `MemoryRetrievalPort` implementations are leveraged directly. This phase adds typed schemas and promotion logic on top.

### Phase 3: Self-Evolution (Week 5-8)

**Goal:** Closed-loop agent improvement from execution traces.

**Deliverables:**
1. `arcclaw-self-evolve` Maven module with package layout from `Self-Evolution.md`.
2. `ConversationBuffer` + `InMemoryConversationBuffer` implementation.
3. `MemoryExtractorLite` — rule-based patterns for v1.
4. `InteractionPolicySynthesizer` — LLM-driven directive generation.
5. `SelfEvolveService` implementation.
6. Integration: `AgentDispatchHandler` emits `EvolutionEvent`s into buffer.
7. `self_evolve_reflect` agent tool.
8. `FlushTriggerPolicy` — session end, event threshold, idle timeout handlers.
9. Directive injection into system prompt (via `ChatHistoryAssembler`).
10. SQLite/Postgres `EvolutionStore` for persisting raw event traces.

**Feature gate:** `arcclaw.self-evolve.enabled` (default `false`). Entire pipeline is opt-in.

### Phase 4: Postgres & FS Backends (Week 8-9)

**Goal:** Parity across all three persistence backends.

**Deliverables:**
1. `PostgresTieredMemoryStore` — Core (JDBI), Recall (tsvector/GIN), Archival (pgvector).
2. `FsTieredMemoryStore` — Core (JSON), Recall (Lucene BM25), Archival (Lucene + embeddings).
3. Backend selector: `@IfBuildProfile` or CDI producer selecting one active backend.
4. Migration path: existing SQLite/PG/FS users continue with their current backend.
5. Backend-specific integration tests.

### Phase 5: Polish & Hardening (Week 10-12)

**Deliverables:**
1. Performance benchmarking: FTS5 search latency under 100K messages.
2. Concurrency testing: CAS version conflicts on Core Memory under parallel agent writes.
3. Eviction policy tuning: LRU parameters, confidence decay curves.
4. Admin API: `GET/PUT/DELETE /admin/memory/core`, `/admin/memory/archival`, `/admin/memory/self-evolve`.
5. Observability: metrics on recall search latency, archival hit rates, directive confidence distribution.
6. Documentation: update AGENTS.md with new memory architecture section.
7. Rollback/failsafe: disable switch for each tier independently.

---

## 10. Data Flow & Lifecycle

### 10.1 Complete Request Flow (with Tiered Memory)

```
1. User sends message
   │
2. CompactionHandler (priority 60)
   ├─ shouldCompact()? → if yes, fire CompactionRequest async
   │
3. AgentDispatchHandler (priority 70)
   │
4. ReasoningMessageAssembler.assemble()
   ├─ ChatHistoryAssembler.assemble()
   │   ├─ CoreMemoryService.renderForPrompt(userId)     ← [USER CONTEXT] block
   │   ├─ SelfEvolveService.buildDirectives(userKey)     ← [BEHAVIORAL DIRECTIVES]
   │   ├─ CompactionSummaryCache.getSummary(sessionId)   ← [CONTEXT FROM PRIOR SESSION]
   │   ├─ ArchivalMemoryService.search(userId, goal)     ← [RELEVANT KNOWLEDGE]
   │   └─ PersistentChatMemoryStore.getMessages()        ← recent turns (rolling window)
   │
5. Agent reasons, selects tool
   │
6. Tool executes
   │
7. SelfEvolveService.append(EvolutionEvent)              ← buffer the event
   │
8. Agent responds to user
   │
9. PersistentChatMemoryStore.updateMessages()
   └─ Dual-write: RecallMemoryService.insertRecallMessage() (async)

10. Async: CompactionService.compact()
    ├─ Persist raw messages to Recall before summarizing
    ├─ Call compaction LLM
    └─ Store summary as COMPACTION_SUMMARY record in Recall

11. Session ends (FINISH tool)
    ├─ CoreMemoryService.update("active_task", null)      ← clear completed task
    └─ SelfEvolveService.flush(sessionKey)                 ← extract + store memories
        └─ MemoryExtractorLite.extract() → upsert to Archival
```

### 10.2 Compaction Summary Lifecycle (Transition)

```
BEFORE (Current):
  Messages → LLM Summary → CompactionStorePort.upsert() → PersistentChatMemoryStore.replace()
  (Original messages destroyed; only summary + last N kept)

AFTER (New):
  Messages → 1. RecallMemoryService.insertAll(raw messages)   ← PERSIST FIRST
             2. LLM Summary
             3. RecallMemoryService.insert(summary as COMPACTION_SUMMARY record)
             4. PersistentChatMemoryStore.replace(toKeep only)
  (Original messages searchable via FTS5; summary available as curated record)
```

### 10.3 Self-Evolution Directive Lifecycle

```
1. [During Session] EvolutionEvents accumulate in ConversationBuffer
2. [Session End / Flush Trigger] MemoryExtractorLite extracts MemoryRecords
3. [Periodic / On-Demand] InteractionPolicySynthesizer generates DirectiveBundle
4. [Storage] Directives stored as ArchivalMemory chunks (type=DIRECTIVE)
5. [Prompt Assembly] ChatHistoryAssembler injects high-confidence directives
6. [Feedback] Agent behavior improves; new traces reflect directive compliance
7. [Re-evaluation] Confidence scores adjusted based on directive effectiveness
```

---

## 11. Configuration

### 11.1 application.yaml

```yaml
arcclaw:
  memory:
    tiered:
      backend: ${ARCCLAW_MEMORY_TIERED_BACKEND:sqlite}
      core-memory:
        enabled: ${ARCCLAW_MEMORY_CORE_ENABLED:true}
        max-blocks-per-user: ${ARCCLAW_MEMORY_CORE_MAX_BLOCKS:20}
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
          low-confidence-days: ${ARCCLAW_MEMORY_ARCHIVAL_EVICTION_LOW_CONFIDENCE_DAYS:60}
          low-confidence-threshold: ${ARCCLAW_MEMORY_ARCHIVAL_EVICTION_LOW_CONFIDENCE_THRESHOLD:0.5}
          lru-min-access-count: ${ARCCLAW_MEMORY_ARCHIVAL_EVICTION_LRU_MIN_ACCESS_COUNT:1}
          lru-max-age-days: ${ARCCLAW_MEMORY_ARCHIVAL_EVICTION_LRU_MAX_AGE_DAYS:90}

  self-evolve:
    enabled: ${ARCCLAW_SELF_EVOLVE_ENABLED:false}
    backend: ${ARCCLAW_SELF_EVOLVE_BACKEND:sqlite}
    buffer-size: ${ARCCLAW_SELF_EVOLVE_BUFFER_SIZE:50}
    default-search-limit: ${ARCCLAW_SELF_EVOLVE_DEFAULT_SEARCH_LIMIT:8}
    directives-enabled: ${ARCCLAW_SELF_EVOLVE_DIRECTIVES_ENABLED:true}
    flush:
      max-events: ${ARCCLAW_SELF_EVOLVE_FLUSH_MAX_EVENTS:12}
      idle-ms: ${ARCCLAW_SELF_EVOLVE_FLUSH_IDLE_MS:300000}
```

### 11.2 .env.example (New Entries)

```bash
# ── Tiered Memory ───────────────────────────────────────
ARCCLAW_MEMORY_TIERED_BACKEND=sqlite                   # sqlite | postgres | fs
ARCCLAW_MEMORY_CORE_ENABLED=true                       # Core memory block injection
ARCCLAW_MEMORY_RECALL_ENABLED=false                    # FTS5 recall search (Phase 1)
ARCCLAW_MEMORY_RECALL_RETENTION_DAYS=90                # Auto-purge recall messages after N days
ARCCLAW_MEMORY_RECALL_CROSS_SESSION=true               # Allow cross-session recall searches
ARCCLAW_MEMORY_ARCHIVAL_ENABLED=false                  # Archival semantic search (Phase 2)

# ── Self-Evolution ─────────────────────────────────────
ARCCLAW_SELF_EVOLVE_ENABLED=false                      # Enable self-evolution pipeline (Phase 3)
ARCCLAW_SELF_EVOLVE_BACKEND=sqlite                     # Evolution trace store backend
ARCCLAW_SELF_EVOLVE_BUFFER_SIZE=50                     # Max events in per-session buffer
ARCCLAW_SELF_EVOLVE_DIRECTIVES_ENABLED=true            # Inject behavioral directives in prompt
ARCCLAW_SELF_EVOLVE_FLUSH_MAX_EVENTS=12                # Auto-flush after N events
ARCCLAW_SELF_EVOLVE_FLUSH_IDLE_MS=300000               # Auto-flush after N ms idle (5 min)
```

---

## 12. Risks & Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| **LLM pollutes its own Core Memory** with bad data | High | Narrow write permissions (only `active_task`, `constraints`, `user_profile` blocks); CAS versioning prevents corruption; manual admin override via API. |
| **Recall storage grows unbounded** | Medium | TTL-based auto-purge (default 90 days). Configurable per tenant. FTS5 content table is `DELETE`-only (not VACUUM during active use). |
| **Archival semantic search latency** | Low | BM25-first hybrid (fast lexical → rerank with vector). Index built on write, not query time. Proven pipeline from existing `MemoryRetrievalPort`. |
| **Self-evolution synthesizer produces bad directives** | High | Confidence-scored directives; low-confidence (<0.5) never shown. Directives have max 600 char limit to prevent prompt bloat. Admin can delete/disable directives. Feature flag is `false` by default. |
| **Compaction race with Recall writes** | Medium | `CompactionEventBridge` latch ensures Recall writes complete before compaction starts. Messages persisted to Recall synchronously before async compaction fires. |
| **Multiple backends divergence** | Low | `TieredMemoryStore` interface defines exact contracts. Integration tests run against all 3 backends. Feature toggles per tier prevent partial activation. |
| **Existing MemorySearchTool users broken** | Medium | Deprecate, don't remove. New tools are additive. Old tool continues working for 1 release cycle with deprecation warning. |
| **sqlite-vec availability at runtime** | Medium | Graceful degradation: if sqlite-vec extension not loaded, Archival falls back to FTS5 (keyword) only. Same pattern as existing `MemoryRetrievalPort` vector degradation. |

---

## 13. Verification Plan

### 13.1 Phase 0 — Core Memory

```bash
# Unit tests
./mvnw test -pl arcclaw-core -Dtest="CoreMemoryServiceTest"

# Integration: verify Core Memory rendered in system prompt
# 1. Set core memory via API POST /admin/memory/core
# 2. Start agent session
# 3. Verify system message contains [USER CONTEXT] with block content
```

### 13.2 Phase 1 — Recall Memory

```bash
# Unit tests
./mvnw test -pl arcclaw-core -Dtest="RecallMemoryServiceTest"
./mvnw test -pl arcclaw-persistence-sqlite -Dtest="SqliteRecallMemoryTest"

# Integration: exact detail recovery
# 1. Run agent session with code snippets and error traces
# 2. Compact session (messages destroyed from rolling window)
# 3. Call recall_memory_search for the code snippet
# 4. Verify exact code returned, not summary
```

### 13.3 Phase 2 — Archival Memory

```bash
# Verify hybrid search returns relevant chunks
./mvnw test -pl arcclaw-core -Dtest="ArchivalMemoryServiceTest"

# Integration: promotion rules
# 1. Same fact appears in 3 sessions
# 2. Verify auto-promoted to Archival
# 3. Verify searchable via archival_memory_search
```

### 13.4 Phase 3 — Self-Evolution

```bash
# Unit tests
./mvnw test -pl arcclaw-self-evolve -Dtest="SelfEvolveServiceTest"
./mvnw test -pl arcclaw-self-evolve -Dtest="InteractionPolicySynthesizerTest"

# Integration: directive synthesis
# 1. Run 5 sessions where agent repeats same mistake
# 2. Call self_evolve_reflect
# 3. Verify directive generated describing the fix
# 4. Start new session → verify directive in system prompt
# 5. Verify agent does NOT repeat the mistake
```

### 13.5 Cross-Cutting

```bash
# Full project compilation
./mvnw compile -DskipTests -q

# Import organization
python3 ./fix_fqns.py

# Check all config keys exist in both files
grep "ARCCLAW_MEMORY_" .env.example arcclaw-app/src/main/resources/application.yaml
grep "ARCCLAW_SELF_EVOLVE_" .env.example arcclaw-app/src/main/resources/application.yaml
```

---

## Appendix A: Module Dependency Graph

```
arcclaw-self-evolve ──────────────┐
                                   │
arcclaw-core ─────────────────────┤
    │                              │
    ├── arcclaw-persistence-api ◄──┤ (shared DTOs + ports)
    │                              │
    ├── arcclaw-persistence-sqlite  │ (SqliteTieredMemoryStore)
    ├── arcclaw-persistence-postgres│ (PostgresTieredMemoryStore)
    └── arcclaw-persistence-fs      │ (FsTieredMemoryStore)

arcclaw-self-evolve depends on:
    - arcclaw-core (for AgentTool, ToolCapability, ToolExecutionContext)
    - arcclaw-persistence-api (for store ports)

arcclaw-self-evolve does NOT depend on:
    - arcclaw-app (no circular dependency)
    - arcclaw-channel-api (independent of channel layer)
```

## Appendix B: Key Open Questions

1. **Should Core Memory be shared across all personas or per-persona?** Current design: per-user (shared). Alternative: per-user-per-role for specialized contexts. Decision deferred to Phase 0 implementation feedback.

2. **Should `recall_memory_search` be available to all roles or restricted?** Current design: all roles. Privacy concern: agent in one conversation could search another user's sessions. Mitigation: userId-scoped search with tenant isolation (same as existing `MemorySearchTool`).

3. **Should the self-evolution pipeline use the same LLM as the agent or a cheaper model?** Current design: uses compaction LLM provider (configurable separately). Can be a cheaper/faster model since directive synthesis is a batch operation.

4. **What happens to existing compaction summaries when Recall Memory is enabled?** Migration: existing compaction summaries are imported as `COMPACTION_SUMMARY` records into Recall. Historical raw messages are lost (pre-existing condition, no recovery possible).

5. **Should Archival Memory support cross-user sharing?** Current design: no. All tiers are user-scoped with tenant isolation. Cross-user sharing (team knowledge base) is a future enhancement.

---

**Document Version:** 1.0-draft
**Next Step:** Review with team, resolve open questions, begin Phase 0 implementation.

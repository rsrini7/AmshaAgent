Migration removed and current dev flow being “drop data dir / recreate PG image,” the right plan is a **clean-build phased implementation plan**, not a rollout/migration plan. Based on your architecture doc and the current codebase, I’d structure it around the existing seams you already have: `CompactionService`, `CompactionEventBridge`, `PersistentChatMemoryStore`, `ChatHistoryAssembler`, `ReasoningMessageAssembler`, `MemorySearchTool`, `MemoryRetrievalPort`, and the current backend split across SQLite/Postgres/FS. 

The most important adjustment is this: because the current code still has the old `memorysearch` model, compaction summaries, and no tiered runtime memory service yet, your plan should explicitly separate **foundations**, **runtime memory**, **archival search**, and **self-evolution** into distinct build phases. That avoids mixing prompt assembly, persistence, search semantics, and behavioral learning in one dev cycle. 

## Current code read

Your current code already has strong reusable seams:
- `CompactionService` and `CompactionEventBridge` handle async compaction with latch protection and are the natural insertion point for Recall preservation before destructive summarization. 
- `ReasoningMessageAssembler` delegates to `ChatHistoryAssembler`, so runtime memory injection belongs in the history/prompt assembly path, not in ad hoc tool code. 
- `MemorySearchTool` currently merges compaction-summary search with `MemoryRetrievalPort` RAG search, which confirms you still need to split today’s “memory” into Recall vs Archival as separate responsibilities. 
- Your prior design already maps `MemoryRetrievalPort` to Archival reuse and `PersistentChatMemoryStore`/compaction flow to Recall reuse. 

That means the clean plan is not “invent new architecture,” but “refactor current codepaths into first-class tiers without breaking the existing agent loop.” 

## Phase plan

### Phase 0 — contracts and config

Build the shared memory domain first, but keep it inert behind config. Define the tier DTOs and service contracts for `CoreMemoryBlock`, `RecallMessage`, `ArchivalChunk`, plus the façade services `CoreMemoryService`, `RecallMemoryService`, and `ArchivalMemoryService`; also add backend selection/config properties and a single top-level feature gate family for tiered memory and self-evolve. 

Do **not** wire tools or prompt injection in this phase. The exit criteria should be: interfaces compile, backend selector resolves, config is validated at startup, and disabled features add zero behavior change. 

### Phase 1 — Core Memory only

Implement Core Memory first because it is the smallest tier and has the clearest prompt contract: small curated blocks rendered into prompt context. Your architecture already defines block semantics, token budget, CAS/versioning, and rendering strategy, so Phase 1 should deliver CRUD service, version conflict handling, render-for-prompt output, and admin/service-level tests. 

Integrate Core Memory only into `ChatHistoryAssembler` or the prompt-building path that feeds `ReasoningMessageAssembler`, as an informational context block rather than a command block. Do not expose agent write tools yet; keep writes system/admin only until read/injection behavior is stable. 

### Phase 2 — Recall persistence before search

This phase should add exact raw-message persistence, but **not yet expose agent-facing recall search**. The goal is to start preserving data first, because your own problem statement says the biggest loss is destructive compaction and rolling-window invisibility. 

Wire Recall at the two write points already visible in the code:
- `PersistentChatMemoryStore.updateMessages` dual-write path for normal message persistence. 
- `CompactionService.compact` / `CompactionEventBridge` path so messages are inserted into Recall before compaction replaces them with summaries. 

Keep compaction behavior unchanged except for one rule: raw messages become canonical preserved history, and the summary becomes a `COMPACTIONSUMMARY` record instead of the only surviving artifact. Exit criteria: after compaction, exact historical text is still queryable in storage even though the active rolling window shrinks. 

### Phase 3 — Recall search and tool split

Only after Recall is filling correctly should you add retrieval. Split the current `MemorySearchTool` into:
- `RecallMemorySearchTool` for exact/session-aware FTS retrieval. 
- `ArchivalMemorySearchTool` placeholder or disabled stub if Archival is not ready yet. 

This matters because the current tool combines compaction summary search and ingested-document RAG in one execution path, which will become muddled once Recall is exact-message history and Archival is semantic long-term knowledge. The old `memorysearch` can remain as a compatibility façade for one dev cycle, but internally it should route explicitly or be marked deprecated. 

Exit criteria here:
- Session-scoped Recall search works.
- Cross-session Recall search is gated/configurable.
- Exact code/error retrieval tests pass.
- Tool descriptions make the boundary clear: Recall is exact history, not semantic knowledge. 

### Phase 4 — Prompt budget integration

At this stage, unify runtime prompt assembly so memory doesn’t bloat or conflict. The injected order should be deterministic: Core Memory first, compaction summary if still used, then recent conversation, with Recall results only inserted on demand from tool use rather than always stuffed into prompt. Your own design also proposes directive injection later, so establishing budget ordering now will prevent prompt chaos later. 

Add explicit caps for:
- Core Memory render size.
- Number of memory sections.
- Max lines/chars per injected block.
- Total injected non-conversation context budget. 

Also audit `ReasoningMessageAssembler.resolveSystemPrompt()` and the current compaction sentinel handling so the new memory blocks do not get merged accidentally into the wrong override flow. 

### Phase 5 — Archival storage model

Now build Archival as a separate semantic store using the current `MemoryRetrievalPort` family as the reuse base. Your architecture already says Archival should be typed chunks with provenance, confidence, and access tracking, while current `MemoryRetrievalPort` is generic text/vector retrieval. So this phase is mainly schema enrichment plus a service façade over the existing retrieval engine. 

Do **not** add automatic promotion yet. Deliver only:
- `ArchivalChunk` schema.
- Search service with BM25/vector/hybrid support.
- Access-count increments.
- Backend-specific degradation behavior when vector support is unavailable, especially SQLite fallback to lexical search only. 

Exit criteria: manual insert/search works, results are typed, provenance is stored, and latency stays within your expected hybrid-search bounds. 

### Phase 6 — Archival tools and assembler use

Expose `archivalmemorysearch` after storage is proven. Roles should follow your design boundaries, and prompt assembly should inject archival results only when explicitly selected by search or when a narrow retrieval policy says they are relevant to the current goal. Your design already calls this “RELEVANT KNOWLEDGE” injection, which is correct, but keep it sparse. 

Do not let Archival become silent prompt spam. The safe rule is: top few results only, deduped, confidence-filtered, goal-matched, and never injected if empty or low-confidence. 

### Phase 7 — controlled write paths

Only now add narrow write operations:
- `corememoryread` first if still not exposed.
- `corememoryappend` / `corememoryreplace` with CAS.
- `archivalmemoryinsert` for restricted roles or approval-gated contexts. 

Your own risk section correctly flags memory pollution, so write governance should be treated as a separate phase, not something mixed into initial read-path delivery. Add throttles, content-size caps, allowed block keys, audit events, and a clear rule that system writes stay distinct from agent writes. 

### Phase 8 — self-evolve trace capture only

Start self-evolution with **capture**, not synthesis. The current codebase already has good event seams in the agent loop and observers, and your design points to `AgentDispatchHandler`/agent loop emission as the right integration point for `EvolutionEvent` creation. 

This phase should deliver:
- `arcclaw-self-evolve` module skeleton.
- `EvolutionEvent` model and store.
- `ConversationBuffer`.
- Flush triggers.
- Event emission from agent/tool lifecycle boundaries.
- No prompt injection, no directives yet. 

Exit criteria: traces persist correctly across sessions, flush logic is reliable, and the feature can be disabled cleanly. 

### Phase 9 — extraction and searchable records

After trace capture is stable, add `MemoryExtractorLite` rule-based extraction. Your architecture already defines record types like `USERPREFERENCE`, `PROJECTCONTEXT`, `FAILUREPATTERN`, `TOOLHINT`, `ERRORCONTEXT`, and `WORKAROUND`, so this phase should populate extracted records from flushed traces and make them searchable via SIMPLE retrieval only. 

Keep this rules-based in v1. Do not add LLM synthesis yet, because you first need confidence in event quality, duplication handling, and record hygiene. 

### Phase 10 — directive synthesis

Only after enough extracted records exist should you implement `InteractionPolicySynthesizer` and `DirectiveBundle` generation. Your design already says DEEP retrieval and synthesis are offline/on-demand, which is the correct constraint. 

This phase should include:
- confidence scoring,
- max directive count,
- directive size limits,
- admin delete/disable path,
- prompt injection of only high-confidence directives,
- and tests proving that low-confidence or noisy directives do not enter the live prompt. 

### Phase 11 — backend parity

Once SQLite is stable, implement parity for Postgres and FS. Your architecture clearly expects three backends for both runtime tiered memory and self-evolve storage, but because you are still in dev and can recreate storage freely, it makes sense to make SQLite the reference backend first and defer parity until semantics are stable. 

For this phase, make sure each backend explicitly documents degraded modes:
- SQLite Archival without `sqlite-vec` falls back to lexical/hybrid-lite. 
- Postgres requires the right `tsvector`/`GIN` and `pgvector` support. 
- FS remains experimental and should likely prioritize correctness over feature completeness. 

### Phase 12 — hardening and cleanup

The last phase is where you close the loop:
- observability,
- admin APIs,
- latency metrics,
- retention/eviction jobs,
- old-tool deprecation,
- documentation,
- and comprehensive verification. 

Also finish the refactor debt:
- remove or fully deprecate old `MemorySearchTool`,
- ensure compaction summaries are no longer treated as canonical memory,
- verify prompt section ordering across all roles,
- and confirm that disabled tiers truly fall back to existing behavior. 

## Things easy to miss

These are the items most likely to be missed in implementation:

- **Do not dual-write Recall twice for the same message path**; your plan references both `PersistentChatMemoryStore.updateMessages` and compaction-time persistence, so you need idempotency keys or dedupe rules. 
- **Prompt ordering must be explicit**; current prompt assembly already has compaction-summary behavior, so Core Memory and later directives must not be appended ad hoc. 
- **Tool taxonomy must change with the architecture**; old `memorysearch` semantics no longer match the new tiers. 
- **Write governance needs audit and rate limits**; your tool catalog mentions limits, but implementation must enforce them centrally, not just in tool descriptions. 
- **Cross-session search must stay tenant/user scoped**; current tooling already emphasizes tenant extraction from runtime context, and the new tiers must preserve that model. 
- **Backend capability drift** will happen unless you define required vs optional features per backend up front. 
- **Self-evolve must stay offline by default**; your design is correct here, but it is easy to accidentally trigger synthesis inside live request paths. 

## Recommended final sequence

Here is the sequence I would actually execute:

| Phase | Build target | Why now |
|---|---|---|
| 0 | Contracts, DTOs, config, selectors | Zero-risk foundation   |
| 1 | Core Memory service + prompt injection | Smallest useful runtime tier   |
| 2 | Recall persistence only | Stop future data loss first   |
| 3 | Recall search + tool split | Make preserved data usable   |
| 4 | Prompt-budget hardening | Prevent memory bloat and section conflicts   |
| 5 | Archival storage/search service | Semantic long-term layer on existing retrieval base   |
| 6 | Archival tool + selective prompt use | Controlled knowledge retrieval   |
| 7 | Controlled write tools | Safe mutation after reads are stable   |
| 8 | Self-evolve event capture | Build trace history first   |
| 9 | Rule-based extraction | Stable records before synthesis   |
| 10 | Directive synthesis + prompt injection | Offline learning loop last   |
| 11 | Postgres/FS parity | Semantics first, backend parity second   |
| 12 | Hardening, observability, deprecations | Production readiness   |

This gives you a **complete Phase-by-Phase plan without migration work**, aligned to the current code and your updated development reality. 

---

Yes — the other Perplexity phase split is **substantially right for your actual codebase**, and after looking at your current Phase 99 seams, I’d refine it into a more implementation-grounded checklist. The key truth from your code is that ArcClaw already has the right insertion points — `CompactionService`, `CompactionEventBridge`, `PersistentChatMemoryStore`, `ChatHistoryAssembler`, `ReasoningMessageAssembler`, `MemorySearchTool`, and `MemoryRetrievalPort` — so Phase 100 should be a controlled refactor of existing behavior, not a greenfield memory engine rewrite. 

The other important conclusion is that your current memory system is still split between compaction summaries and generic RAG search: `MemorySearchTool` searches both `CompactionStorePort` summaries and `MemoryRetrievalPort` chunks, while `CompactionSummaryCache` and `ChatHistoryAssembler` still treat summary memory as the main historical memory path. That means your future plan must first disentangle **runtime recall** from **semantic archival** before adding self-evolution on top. 

## Review of current code

Your Phase 99 codebase is in a **good pre-refactor state** for this work because the current seams already correspond closely to the design doc’s reuse map. `CompactionService` owns destructive summarization, `CompactionEventBridge` already manages async ordering and latch-based race protection, `CompactionSummaryCache` already blocks on in-flight compaction before prompt assembly, and `MemorySearchTool` already centralizes “historical memory” lookup logic across summaries and RAG content. 

That means the architecture doc is not fighting the codebase. In fact, the plan’s proposed reuse of `CompactionService` for Recall preservation, `PersistentChatMemoryStore` for exact-message persistence, `MemoryRetrievalPort` for Archival reuse, and `ChatHistoryAssembler` for prompt injection is directly supported by the current Phase 99 structure. 

## Review of the other plan

The other Perplexity plan is strong because it correctly separates:
- Foundations and contracts.
- Core runtime memory.
- Recall persistence before Recall search.
- Archival semantics after runtime recall is stable.
- Self-evolution only after trace capture and extracted records exist. 

That sequencing matches both your architecture document and the realities of your current code. In particular, its advice to delay agent-facing tools and self-evolution synthesis until after read paths stabilize is correct, because your current system still has summary-first memory semantics and does not yet have tier-native prompt ordering. 

The one refinement I would make is this: **Phase 0 should produce interfaces and selectors, but not fake no-op implementations for everything unless they are genuinely useful**. Your codebase already has backend selection patterns and persistence module separation, so you should prefer “supported/not enabled” implementations over broad no-op surface area that may hide mistakes in tests. 

## Deliverables checklist

### Phase 0

Build the contracts and config surface, but keep runtime behavior unchanged. Create `TieredMemoryStore`, DTOs for `CoreMemoryBlock`, `RecallMessage`, and `ArchivalChunk`, plus service façades such as `CoreMemoryService`, `RecallMemoryService`, and `ArchivalMemoryService`. 

Change points:
- Add new memory config keys in `.env.example` and `application.yaml`. 
- Add new persistence API contracts in the shared persistence/API module rather than directly in `arcclaw-core`. 
- Add backend selector wiring following the existing backend split pattern. 

Tests:
- Config boot tests for enabled/disabled flags.
- Selector tests proving only one active tiered backend resolves.
- DTO/service unit tests for validation and invariants. 

Done means:
- App boots with all new flags disabled and behaves exactly like Phase 99.
- New contracts compile and backend selection is deterministic. 

### Phase 1

Implement **Core Memory only**. This should be the first tier because it has the smallest storage surface and the cleanest prompt contract: small curated blocks rendered into the system prompt. 

Create:
- `CoreMemoryService`
- backend-specific `upsert/read/delete` support in `TieredMemoryStore`
- prompt renderer such as `CoreMemoryPromptRenderer` or `CoreMemoryFormatter`. 

Change points:
- `ChatHistoryAssembler` to prepend a bounded `USER CONTEXT` block. 
- `ReasoningMessageAssembler` only if it needs prompt-section ordering awareness. 

Tests:
- CRUD tests for core blocks.
- Prompt assembly test proving `USER CONTEXT` appears before chat history and remains informational, not imperative. 

Done means:
- Core Memory persists and renders into prompt with strict size caps.
- No tools yet, no self-evolve yet. 

### Phase 2

Add **Recall persistence**, but not search yet. Your own problem statement says the first emergency is preventing future data destruction from compaction and rolling-window loss. 

Create:
- `RecallMessage` mapper from `ChatMessage`
- `RecallMemoryService.insert(...)`, `insertBatch(...)`, `insertCompactionSummary(...)`
- backend storage tables/files/indexes for exact-message persistence. 

Change points:
- `PersistentChatMemoryStore.updateMessages` for normal dual-write to Recall. 
- `CompactionService.compact(...)` so `toCompact` is persisted before summarization and the summary is stored as `COMPACTIONSUMMARY`. 
- `CompactionEventBridge` only if needed to guarantee ordering and idempotency. 

Tests:
- Duplicate prevention/idempotency tests, because the plan currently risks dual-writing the same message from both normal write flow and compaction flow. 
- Integration test proving compacted exact text is preserved in Recall storage. 

Done means:
- Compaction still reduces active window.
- Exact raw messages survive compaction in Recall. 

### Phase 3

Add **Recall search** and split tool semantics. This is where your current `MemorySearchTool` starts becoming legacy. 

Create:
- `RecallMemorySearchTool`
- `RecallSearchQuery` DTO or equivalent filter object
- backend search methods for session-scoped and cross-session exact retrieval. 

Change points:
- Deprecate `MemorySearchTool` internally or route it through tier-aware logic. 
- Keep old `memorysearch` temporarily as compatibility if needed. 

Tests:
- Search exact code snippets after compaction.
- Search exact error messages by session and cross-session.
- Tenant/user scoping tests. 

Done means:
- Recall search works as exact-history lookup.
- Tool descriptions clearly distinguish Recall from semantic Archival. 

### Phase 4

Harden **prompt budget integration** before Archival. This is one of the most important phases even though it sounds boring, because your current prompt assembly already has summary handling and role/system sections. 

Create:
- explicit memory prompt budget config
- `MemoryPromptBudgetPolicy` or equivalent helper for trimming/injection order. 

Change points:
- `ChatHistoryAssembler` section ordering. 
- `ReasoningMessageAssembler.resolveSystemPrompt()` path so memory sections do not get merged incorrectly with compaction sentinel or other overrides. 

Tests:
- Prompt section ordering tests.
- Overflow tests showing memory blocks are clipped deterministically.
- Regression tests proving compaction sentinel still works. 

Done means:
- Prompt assembly order is deterministic: Core first, compaction summary if used, recent chat, and only on-demand memory retrieval beyond that. 

### Phase 5

Implement **Archival storage and service** using `MemoryRetrievalPort` as the reuse base, not the public domain API. This is one of the clearest insights in your architecture doc. 

Create:
- `ArchivalMemoryService`
- `ArchivalChunk` typed DTO and mapper
- storage fields for chunk type, confidence, source session/message, and access count. 

Change points:
- Reuse `MemoryRetrievalPort` implementations beneath the service. 
- Do not wire automatic promotion yet. 

Tests:
- Manual insert and search tests.
- Vector-unavailable fallback tests.
- Provenance persistence tests. 

Done means:
- Archival works as a typed semantic store, independent from Recall exact history. 

### Phase 6

Expose **Archival search tools** and selective assembler usage. 

Create:
- `ArchivalMemorySearchTool`
- possibly `ArchivalResultFormatter` for compact injection. 

Change points:
- `ChatHistoryAssembler` to inject `RELEVANT KNOWLEDGE` only when explicitly retrieved or narrowly policy-selected. 

Tests:
- Goal-matching and confidence threshold tests.
- Injection sparsity tests proving Archival does not become silent prompt spam. 

Done means:
- Only a few top, deduped, relevant archival results enter prompt context. 

### Phase 7

Add **controlled write paths** after reads are stable. Your architecture doc is correct to treat write governance as a separate concern. 

Create:
- `CoreMemoryReadTool`
- `CoreMemoryAppendTool`
- `CoreMemoryReplaceTool`
- `ArchivalMemoryInsertTool`
- central governance/rate-limit policy for memory writes. 

Change points:
- Tool registration and capability metadata.
- Audit hooks for memory mutation. 

Tests:
- CAS conflict tests.
- Rate limit and approval-gating tests.
- Allowed block key and content-size validation tests. 

Done means:
- Agent writes are narrow, audited, and bounded. 

### Phase 8

Start **self-evolve trace capture only**. This should remain separate from synthesis. 

Create:
- new `arcclaw-self-evolve` module
- `EvolutionEvent`
- `ConversationBuffer`
- `SelfEvolveService.append(...)` and flush triggers
- simple backend store SPI for events and extracted records. 

Change points:
- `AgentDispatchHandler` or equivalent agent loop seam for event emission. 
- tool lifecycle points for `TOOLCALL`, `TOOLRESULT`, `TOOLERROR`, `RESOLUTION`, `SELFCORRECTION`. 

Tests:
- Event emission sequence tests.
- Flush-on-threshold / idle / finish tests.
- Disabled-feature no-op tests. 

Done means:
- Trace history is captured reliably, but nothing new is injected into prompts yet. 

### Phase 9

Add **rule-based extraction** for self-evolve records. This mirrors the safer V1 path and aligns with Memind-style staged extraction where quality gates precede richer synthesis. 

Create:
- `MemoryExtractorLite`
- extracted record DTOs such as `USERPREFERENCE`, `PROJECTCONTEXT`, `FAILUREPATTERN`, `TOOLHINT`, `ERRORCONTEXT`, `WORKAROUND`. 

Change points:
- self-evolve flush pipeline: `ConversationBuffer -> MemoryExtractorLite -> store`. 

Tests:
- Rule extraction tests from real ArcClaw trace samples.
- Deduplication tests.
- Searchable SIMPLE retrieval tests over extracted records. 

Done means:
- You have usable structured records without LLM synthesis dependency. 

### Phase 10

Add **directive synthesis** last. This is the highest-risk part and should remain offline or on-demand, which matches both your plan and the Memind philosophy of layered retrieval/synthesis rather than uncontrolled runtime mutation. 

Create:
- `InteractionPolicySynthesizer`
- `DirectiveBundle`
- directive storage and status model, ideally as archival `DIRECTIVE` chunks or equivalent typed records. 

Change points:
- `ChatHistoryAssembler` for bounded `BEHAVIORAL DIRECTIVES` injection. 
- optional `SelfEvolveReflectTool`. 

Tests:
- Confidence thresholds.
- Low-confidence suppression.
- Admin delete/disable tests.
- Prompt injection cap tests. 

Done means:
- Only stable, high-confidence directives are injected.
- No synthesis runs in the live turn path by default. 

### Phase 11

Do **backend parity** after SQLite/reference semantics are stable. This matches the other Perplexity review and also your own backend layout. 

Create:
- `PostgresTieredMemoryStore`
- `FsTieredMemoryStore`
- self-evolve backend variants. 

Change points:
- backend selectors and capability declarations. 

Tests:
- backend contract conformance tests.
- degraded mode tests:
  - SQLite without vector extension,
  - Postgres without pgvector/GIN,
  - FS correctness-first Lucene mode. 

Done means:
- Same semantics across backends, with explicit degraded features documented. 

### Phase 12

Finish **hardening and cleanup**. 

Create:
- admin APIs for core/archival/self-evolve state
- observability metrics
- retention/eviction jobs
- deprecation notices and docs updates. 

Change points:
- fully deprecate old `MemorySearchTool`
- ensure compaction summaries are no longer treated as canonical memory
- verify disabled tiers cleanly fall back to Phase 99 behavior. 

Tests:
- p95 latency and concurrency tests.
- rollback/disable regression tests.
- end-to-end sessions proving old behavior remains intact when features are off. 

Done means:
- Feature set is stable, observable, and operationally understandable. 

## Final judgment

So my review is:

- Your **current Phase 99 code** is a strong base for this work because the seams are already there. 
- Your **earlier massive plan** is architecturally strong, especially in tier separation and offline self-evolution. 
- The **other Perplexity phased plan** is mostly correct and much closer to what your real code needs than a migration-heavy masterplan. 
- The most important implementation discipline is: **Core -> Recall persist -> Recall search -> Prompt ordering -> Archival -> Controlled writes -> Self-evolve capture -> Self-evolve extraction -> Directive synthesis**. 

The next best step would be to convert this into a **phase-by-phase engineering checklist with concrete class names mapped to your modules** such as `arcclaw-core`, `arcclaw-persistence-api`, `arcclaw-persistence-sqlite`, `arcclaw-persistence-postgres`, `arcclaw-persistence-fs`, and `arcclaw-self-evolve`.
# Amsha RAG Module (`rag`)

Two-tier, governance-aware Retrieval Augmented Generation. This module owns retrieval
orchestration, prompt enrichment, and reranking; durable storage lives behind
`MemoryRetrievalPort` in the `persistence-sqlite` (default), `persistence-postgres`, and
`persistence-fs` modules.

## What this module provides

- **`TwoTierMemoryService`** — merges FTS5/BM25 keyword results and vector results from
  `MemoryRetrievalPort`, then optionally reranks via `RerankingService`. Exposes both
  `search()` (fused `ScoredChunk`) and `searchReranked()` (reranked `RerankedChunk`).
- **`RagContextEnricher`** (`implements ContextEnricher`) — injects `## Retrieved Rag Context`
  into the base prompt. Skip rules: write-intent goals (`store`/`remember`/`ingest`/…) are
  never enriched with prior chunks (prevents "store my name" self-fulfilling), and cron
  sessions are excluded. Adaptive score threshold from the candidate distribution, plus
  `max-chunk-chars` / `max-total-chars` caps.
- **`RerankingService` + `RerankerPort`** — reorders a bounded candidate set via an optional
  cross-encoder. Deterministic fallback contract: on missing reranker, execution error, or
  embedding-descriptor mismatch, candidates are returned in the original RRF order with
  `ScoredChunk.score()` preserved byte-for-byte; each fallback reason increments
  `amsha.rag.rerank.fallback{reason=…}`.
- **`EmbeddingProviderFactory` (SPI)** + `EmbeddingSpaceDescriptor` — pluggable embedding
  providers (OpenAI, Jina, InfiniteLoop/Ollama, local ONNX) with per-space identity so
  cross-provider vectors cannot silently mix.
- **`IngestDocumentTool`** — runtime ingestion entry point; flows through `ToolInvocationGuard`,
  the PII scan reflex, and audit before `MemoryRetrievalPort.index()` writes chunk + embedding rows.

## Retrieval architecture

1. `RagContextEnricher.enrich()` builds an FTS5 phrase from the goal.
2. `TwoTierMemoryService.searchReranked()` calls `MemoryRetrievalPort.search()`:
   - FTS5/BM25 keyword match (`rag_chunk`) **and** vector cosine (`rag_chunk_embedding`,
     sqlite-vec / pgvector), fused with Reciprocal Rank Fusion (`RRF_K = 60`).
   - Vector search is disabled fast-path when the vec0/pgvector extension is absent or the
     `EmbeddingIndexDescriptor` gate reports a mismatch — degrading non-destructively to BM25/FTS5.
3. `RerankingService` reorders candidates; `RagContextEnricher` applies the adaptive threshold.

Retrieval is `userId`/session scoped (`GlobalKnowledgeBase` + `SessionKnowledgeBase`); session
vector scope is used only when a workspace scope is active.

## P6 — Safety-Gated Easy RAG Evaluation (2026-08-29) — Retain Amsha RAG Subsystem

**Decision: RETAIN AMSHA NATIVE RAG SUBSYSTEM — DO NOT ADOPT EASY RAG**
(`P6-EASYRAG-01`, LangChain4j `1.17.2` / Quarkus `3.38.3` / Quarkiverse `1.12.2`,
`quarkus-langchain4j-easy-rag` member-BOM-managed but **not** declared in any module `pom.xml`
and **not** on the classpath; Amsha source contains zero `RetrievalAugmentor`/`EasyRag*`
references). Authority: [ADR-LangChain4j-Capability-Adoption](../docs/adr/architecture/ADR-LangChain4j-Capability-Adoption-and-Amsha-Boundaries.md#easy-rag) §Easy-RAG. Issue 09 and its evaluation evidence are local ignored artifacts under `.scratch/lc4j-capability-adoption/`; the tracked ADR is the clean-checkout reference.

**Why retain — verification against Amsha invariants (Easy RAG claims re-checked verbatim):**

* Easy RAG is a one-property starter (`quarkus.langchain4j.easy-rag.path`): a boot-time Apache
  Tika scan of a directory into an auto-registered **in-memory** `EmbeddingStore`, with a default
  `RetrievalAugmentor` auto-wired into an `@AiService`. It ingests with no `ToolInvocationGuard`,
  PII scan, or audit path.
* Easy RAG is **explicitly not native-image compatible** — adopting it breaks the GraalVM native
  contract (vec0 installer, `*ReflectionConfig`).
* Easy RAG is vector-cosine retrieval only — no FTS5/BM25 keyword tier, no RRF, no reranking stage.
* Easy RAG has no `EmbeddingIndexDescriptor` fingerprint gate; a model change would silently mix
  vectors or require non-destructive fallback that upstream does not provide.
* Easy RAG is a single global store — no `userId`/session scoping, no cron-session exclusion.

**Evaluation matrix (Amsha native RAG vs LangChain4j Easy RAG):**

| Dimension | Amsha Native RAG Subsystem | LangChain4j Easy RAG | Outcome |
|---|---|---|---|
| **Retrieval Strategy** | Hybrid FTS5/BM25 + vector + RRF + optional rerank | Vector cosine only | **Amsha Superior** |
| **Ingestion Control & Governance** | Runtime `IngestDocumentTool` via guard/PII/audit | Boot-time folder scan, no guard/PII/audit | **Amsha Superior** |
| **GraalVM Native Image** | Verified (vec0 installer, reflection configs) | Explicitly not supported | **Amsha Superior** |
| **Tenant / Session Scoping** | `userId`/session scoped; cron excluded | Single global store | **Amsha Superior** |
| **Embedding Descriptor Safety** | Fingerprint gate + non-destructive BM25 fallback | None (silent vector mixing risk) | **Amsha Superior** |
| **Reranking Stage** | `RerankingService` + `RerankerPort` fallback + metrics | None | **Amsha Superior** |
| **Developer Onboarding Simplicity** | Code-driven ports/config (deliberate) | One property, zero code | **Easy RAG Superior** |

Tally: **6× Amsha Superior, 1× Easy RAG Superior** (onboarding simplicity only). No proof of
equivalence means retain the current Amsha-owned implementation.

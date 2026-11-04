# Architectural comparison between the 5-Layer Concentric Model (Hermes / Saboo / Banerji) and AmshaAgent

---

## References

- [Agent-Memory-Architectures-5-Layer-Harness-And-Judgment-Memory-Nov-2026.md](https://github.com/rsrini7/Learnings/blob/main/AI-ML/Agents/frameworks/Agent-Memory-Architectures-5-Layer-Harness-And-Judgment-Memory-Nov-2026.md)
- [AmshaAgent-Architecture-Memory-Layering-And-Governance-Nov-2026.md](https://github.com/rsrini7/Learnings/blob/main/AI-ML/Agents/frameworks/AmshaAgent-Architecture-Memory-Layering-And-Governance-Nov-2026.md)

---

## Core Insight in One Sentence

The 5-Layer Model defines the economics and mental model of agent memory (KV caching, token budgets, retrieval vs. parametric judgment); AmshaAgent delivers the enterprise engineering guarantees (CAS concurrency, thread safety, write rate-limiting, and multi-tier governance).

---

## Layer-by-Layer Architectural Comparison

| Memory Tier | 5-Layer Concentric Theory (Hermes / Saboo) | AmshaAgent Architecture | Key Takeaway |
| --- | --- | --- | --- |
| 1. Context (Now) | Middle-out rolling compaction; protect prefix cache | Priority-60 CompactionHandler + CompactionEventBridge latch + 2-pass PromptBudgetManager | AmshaAgent adds latch synchronization (CountDownLatch), preventing race conditions where subsequent turns read stale/null cache while compaction commits. |
| 2. Notes (Task State) | Disk markdown file (progress.md) as an out-of-band state machine | Typed CheckpointStorePort with serialized ReasoningStep[], AWAITING_APPROVAL, and crash recovery | AmshaAgent turns the informal scratchpad into a durable state machine with reboot zombie protection, Shiva auto-heal, and persona escalation. |
| 3. Semantic (Facts) | Hard-capped MEMORY.md (~2.2k chars), frozen session prefix | Segmented CoreMemoryService (20 blocks max, 2,000 chars/block) + Compare-and-Swap (CAS) optimistic locking | AmshaAgent introduces optimistic version concurrency (3-way CAS retry) and token-bucket rate limiting (5 writes/min) to prevent runaway agent write loops. |
| 4. Procedural (Skills) | Tiered progressive disclosure (SKILL.md L0 manifest vs. L1 tool load) | Dynamic amsha-skills engine (hot-reloadable SkillsJars, file watchers), 12 personas across 4 Lokas | AmshaAgent enforces capability-subset delegation (garuda), ensuring child agents can never escalate permissions beyond parent boundaries. |
| 5. Episodic (Archive) | Deterministic SQLite FTS5 (BM25) over dense vector RAG | Recall (30-day exact turn store) + Archival Hybrid RAG (FTS5 + sqlite-vec / pgvector RRF) + Local ONNX BGE Reranker | AmshaAgent combines both worlds: deterministic BM25 search for exact tokens, fused with dense vectors via RRF and guarded by a 15-field EmbeddingIndexDescriptor to prevent index corruption. |
| 6. Decision & Judgment | Geometric centroid gate (μ_c), asymmetric updates, σ-fingerprint | Typed Jev Decision Seam (Choice, Score, Noul), shadow classifiers, and amsha-self-evolve | The theoretical model moves centroids in continuous embedding space; AmshaAgent provides a typed, closed-world decision contract with placement validation forbidding network leaks. |

---

## My 3 High-Level Takeaways

1. Informal Files vs. Typed Concurrency:
   - The theoretical harness model assumes a single-user CLI environment where writing markdown to progress.md and MEMORY.md is sufficient.
   - AmshaAgent recognizes that in multi-agent or enterprise settings, multiple personas and virtual threads mutate memory simultaneously. It introduces CAS versioning, segmented keys, and token-bucket write governance to make agent memory thread-safe and loop-proof.
2. Retrieval Purity vs. Hybrid RRF + Descriptor Safety:
   - The 5-layer post argues strongly for SQLite FTS5 to avoid vector retrieval failure on exact code symbols.
   - AmshaAgent implements a sophisticated hybrid approach: it uses FTS5 for exact lexical matching, sqlite-vec / pgvector for semantic concepts, fuses them via Reciprocal Rank Fusion (RRF), reranks locally with in-process ONNX BGE, and wraps the vector store in a 15-field descriptor fingerprint that degrades safely to keyword search rather than breaking when models change.
3. Autonomy is Nothing Without Governance:
   - The 5-layer model focuses primarily on context and recall efficiency.
   - AmshaAgent recognizes that memory and execution must be bounded by governance: 4 autonomy levels, multi-stage tool guards (ToolInvocationGuard), rootless Podman sandboxing, AES-256-GCM-SIV secret vaulting, and tamper-evident HMAC audit chains.

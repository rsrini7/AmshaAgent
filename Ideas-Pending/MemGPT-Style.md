My recommendation: **yes, move toward a MemGPT-style tiered memory design, but do it incrementally and do not replace compaction first**. MemGPT/Letta’s reference architecture separates **core memory** (always in context), **recall memory** (searchable conversation history), and **archival memory** (external searchable store), with explicit memory-editing/search tools such as `core_memory_append`, `conversation_search`, and `archival_memory_search`; that maps very well to the problem you described. [docs.letta](https://docs.letta.com/guides/legacy/memgpt-agents-legacy)

## Recommended shape

I would target these three layers:
- **Core memory**: very small, curated, always in the system prompt — user profile, current task state, active constraints, active tool/session state. MemGPT treats this as in-context memory that the agent can edit deliberately. [docs.letta](https://docs.letta.com/guides/legacy/memgpt-agents-legacy/)
- **Recall memory**: exact conversation/event retrieval using SQLite FTS5, preserving code blocks, stack traces, and literal prior messages instead of flattening them into summaries. This directly addresses your complaint that summarization destroys precise details. [docs.openclaw](https://docs.openclaw.ai/concepts/memory-builtin)
- **Archival memory**: semantic retrieval over embedded chunks using `sqlite-vec` or an equivalent SQLite-based vector layer, ideally with hybrid lexical + vector ranking rather than embeddings alone. SQLite-backed memory engines increasingly use this pattern because it stays local and operationally simple. [dev](https://dev.to/aairom/embedded-intelligence-how-sqlite-vec-delivers-fast-local-vector-search-for-ai-3dpb)

## What to build first

Start with **Recall memory before Archival memory**. Since your current pain is loss of exact details like code snippets and error traces, FTS5-backed searchable raw conversation history will solve that faster and more reliably than jumping straight to embeddings. MemGPT itself keeps old messages available through search even when the active context is compacted, and OpenClaw-style local memory engines also treat keyword search as a first-class retrieval mode. [docs.letta](https://docs.letta.com/guides/legacy/memgpt-agents-legacy)

My concrete order would be:
1. Keep `CompactionService`, but downgrade it from “source of truth” to “context budget fallback.” [docs.letta](https://docs.letta.com/guides/legacy/memgpt-agents-legacy)
2. Add **Recall storage** with exact message persistence plus metadata: session, turn, role, tool, timestamp, code/error flags. [docs.openclaw](https://docs.openclaw.ai/concepts/memory-builtin)
3. Add a tool like `conversation_search` or `recall_memory_search` before introducing `archival_memory_search`, because that matches MemGPT more closely and gives immediate value. [docs.letta](https://docs.letta.com/guides/legacy/memgpt-agents-legacy/)
4. Add **Core memory blocks** with explicit edit tools such as `core_memory_append` and `core_memory_replace`, but keep write permissions narrow at first. [docs.letta](https://docs.letta.com/guides/legacy/memgpt-agents-legacy/)
5. Then add **Archival memory** using chunked embeddings and `sqlite-vec`, preferably with hybrid search, not vector-only retrieval. [dev](https://dev.to/aairom/embedded-intelligence-how-sqlite-vec-delivers-fast-local-vector-search-for-ai-3dpb)

## Design advice

Do **not** let the model write freely to every memory tier at first. MemGPT exposes memory tools to the agent, but in a production system like ArcClaw I’d start with:
- agent-writable **core memory** only for narrow fields,
- system-managed **recall memory** from persisted conversation/tool logs,
- controlled writes into **archival memory** via explicit promotion rules. [docs.letta](https://docs.letta.com/guides/legacy/memgpt-agents-legacy)

A good promotion rule is:
- exact recent facts stay in recall,
- stable long-lived facts or reusable knowledge get promoted to archival,
- only the smallest, currently relevant facts live in core. [informationmatters](https://informationmatters.org/2025/10/memgpt-engineering-semantic-memory-through-adaptive-retention-and-context-summarization/)

This matters because otherwise the agent will pollute its own memory. The most important thing is **memory governance**, not just storage.

## ArcClaw-specific recommendation

For ArcClaw, I would implement this as:
- **CoreMemoryService** → rendered into system prompt.
- **RecallMemoryStore** → SQLite FTS5 over exact turns and tool observations.
- **ArchivalMemoryStore** → chunked semantic store with `sqlite-vec`.
- Tools:
  - `core_memory_append`
  - `core_memory_replace`
  - `recall_memory_search`
  - `archival_memory_search`
  - later maybe `archival_memory_insert`. [docs.openclaw](https://docs.openclaw.ai/concepts/memory-builtin)

I would **not** expose archival insert on day one. First prove that retrieval helps; then decide whether the LLM should be allowed to promote memories itself.

## Bottom recommendation

So my precise recommendation is:

1. **Adopt the tiered memory direction.** [docs.letta](https://docs.letta.com/guides/legacy/memgpt-agents-legacy/)
2. **Build Recall memory first** to fix the exact-detail loss problem. [docs.openclaw](https://docs.openclaw.ai/concepts/memory-builtin)
3. **Keep compaction**, but only as a prompt-budget mechanism, not the canonical memory layer. [docs.letta](https://docs.letta.com/guides/legacy/memgpt-agents-legacy)
4. **Add explicit memory tools**, but start with read-heavy tools and tightly controlled core-memory writes. [docs.letta](https://docs.letta.com/guides/legacy/memgpt-agents-legacy/)
5. **Add `sqlite-vec` archival memory after recall is working**, ideally with hybrid lexical + semantic retrieval. [pingcap](https://www.pingcap.com/blog/local-first-rag-using-sqlite-ai-agent-memory-openclaw/)

If you want a single implementation choice: **FTS5 recall + small core memory now, sqlite-vec archival next** is the most practical path.

---

Then I recommend a **pluggable memory backend SPI with exactly one active provider at startup**. In Quarkus, the clean pattern is to configure one backend as active and bind the application to that implementation, instead of combining stores at runtime. Quarkus guidance around selectable backends points toward activating the chosen datasource/backend with configuration rather than dynamically mixing implementations. [stackoverflow](https://stackoverflow.com/questions/79365320/quarkus-user-configurable-backend-database)

## Recommendation

Define one `MemoryStore` interface and three implementations:
- `SqliteMemoryStore`
- `PostgresMemoryStore`
- `FilesystemMemoryStore`

Then select exactly one with config like:
- `arcclaw.memory.backend=sqlite`
- `arcclaw.memory.backend=postgres`
- `arcclaw.memory.backend=fs` [quarkus](https://quarkus.io/guides/lifecycle)

The important part is: **do not leak backend-specific behavior into the agent/tool layer**. The agent should see one contract for:
- core memory read/write
- recall search
- archival search
- artifact fetch
- compaction snapshot/fallback. [stackoverflow](https://stackoverflow.com/questions/79365320/quarkus-user-configurable-backend-database)

## Which backend to pick

My practical recommendation:
- **SQLite** if you want simplest local-first deployment, low latency, and easy ops for a single-node/dev setup. SQLite is embedded and usually faster for local single-process access because it avoids network/database-server overhead. [youtube](https://www.youtube.com/watch?v=Sq2B-LIZzjw)
- **Postgres** if ArcClaw runs multi-instance, needs shared memory across workers, or you expect concurrent writes and centralized operations. That is the better production default in distributed deployments. [quarkus](https://quarkus.io/guides/hibernate-orm)
- **Filesystem** only if you want maximum simplicity for raw artifact preservation and are willing to accept weaker query ergonomics. Many teams discover that once FS memory becomes searchable, they start rebuilding a database on top of files anyway. [linkedin](https://www.linkedin.com/posts/richmondalake_100daysofagentmemory-activity-7436889614428164097-U1KT)

So if you want one recommendation per environment:
- local/dev: **SQLite**
- shared/prod: **Postgres**
- experimental/minimal raw store: **FS** [blogs.oracle](https://blogs.oracle.com/developers/comparing-file-systems-and-databases-for-effective-ai-agent-memory-management)

## Architecture shape

Use this structure:
- `MemoryStore` interface
- `MemoryBackendSelector` or producer
- one backend bean active at startup
- `MemoryService` above it, backend-agnostic
- memory tools call `MemoryService`, not the backend directly. [quarkus](https://quarkus.io/guides/lifecycle)

In Quarkus terms, I would avoid fancy runtime switching after boot. Pick the backend at startup and fail fast if the selected backend is misconfigured. That aligns better with Quarkus’s startup/build-time philosophy and avoids partial activation edge cases. [in.relation](https://in.relation.to/2019/03/08/why-quarkus/)

## My concrete call

If you are implementing this now, I’d do:
1. Build the SPI first.
2. Implement **SQLite** first because it is fastest to validate the tiered-memory model. [news.ycombinator](https://news.ycombinator.com/item?id=32676455)
3. Add **Postgres** second as the production/shared backend. [quarkus](https://quarkus.io/guides/hibernate-orm)
4. Add **Filesystem** only if you truly want a file-native mode, not as the first-class default. [linkedin](https://www.linkedin.com/posts/richmondalake_100daysofagentmemory-activity-7436889614428164097-U1KT)

So the precise recommendation is: **one `MemoryStore` SPI, one backend selected at startup, SQLite first unless you already know you need shared multi-instance memory — then Postgres first.** [youtube](https://www.youtube.com/watch?v=Sq2B-LIZzjw)
## 🟠 Significant — Fix Before First Production Deploy

#1 — IngestDocumentTool always reports success (Issue 10)
`SqliteMemoryRetrievalAdapter.index()` returns a fallback UUID when the global KB is down, so the tool always tells the agent "Document ingested successfully." even on degraded state. Agents can't distinguish a real ingest from a silent fallback. `index()` should return a typed result (e.g. an enum `GLOBAL` / `SESSION_FALLBACK`) or a distinct message string so the tool can report accurately.

#2 — AgentCronEntity.runOnce column name mismatch (Issue 7)
Hibernate 6's default `CamelCaseToUnderscoresNamingStrategy` would have mapped `runOnce` → `run_once` in the old schema. The new explicit `@Column(name = "runonce")` maps to a different column name. Dev DB is deleted so it's safe now — but this must be documented in the migration notes before any non-fresh deployment.

#3 — saveAndFinalize TOCTOU window (Issue 8)
`load()` and `saveCheckpointWithSteps()` are now two separate `REQUIRES_NEW` transactions. A concurrent rate-limit retry scheduler could modify the checkpoint between the two calls. The original `doSaveAndFinalize` was a single atomic unit. Fix: add an `upsertAndFinalize` method to `CheckpointStorePort` that does the read+write atomically inside the adapter.

***

## 🔵 Architectural Debt — Schedule for a Dedicated Refactor Sprint

#4 — PersistenceAdapterPort leaks `EntityManager` (Issue 6)
`PersistenceAdapterPort.execute(String, String, Consumer<EntityManager>)` carries a JPA-specific type in the port interface. A future JDBC/jOOQ Postgres adapter cannot implement this cleanly. Change `Consumer<EntityManager>` to a `ThrowingRunnable` and move the `em` reference entirely into the adapter implementation. The lambdas in `AsyncPersistenceEngine` would become plain `() -> em.merge(...)` with the `em` injected inside the adapter.

#5 — GlobalKnowledgeBase/SessionKnowledgeBase in wrong module (Issue 4)
Both classes use LangChain4J (`TextSegment`, `EmbeddingStore`, etc.) but live in `arcclaw-persistence-sqlite` which has no explicit LangChain4J dependency — it only compiles because it's transitive through `arcclaw-core`. Move these three classes (`GlobalKnowledgeBase`, `SessionKnowledgeBase`, `SqliteMemoryRetrievalAdapter`) to `arcclaw-rag` or a new `arcclaw-rag-sqlite` adapter module where they architecturally belong.

#6 — SqliteCheckpointStoreAdapter is 880 lines with multiple concerns (Issue 12)
The adapter handles: checkpoint CRUD, step CRUD, status transitions, batch decision merging (A16 guard duplicated from `StatefulAgentStoreImpl`), zombie recovery, chat history purge, and session lifecycle checks. Split into `SqliteCheckpointCrudAdapter` + `SqliteCheckpointLifecycleAdapter` in a future pass.

#7 — `findDueCrons()` test isolation hazard (Issue 11)
`AgentCronRepository.findDueCrons()` uses `Instant.now()` as a SELECT parameter, which requires `InstantToTextConverter` to be registered. That converter lives in `arcclaw-persistence-sqlite`, not `arcclaw-core`. Unit tests of `arcclaw-core` alone (without `arcclaw-persistence-sqlite` on classpath) will fail to register the converter and `findDueCrons()` will break. Fix: move `InstantToTextConverter` to `arcclaw-core`, or add an explicit dependency.



***

## Summary Table

| # | Severity | Area | One-line description |
|---|---|---|---|
| 1 | 🟠 | IngestDocumentTool | Always reports success, hides global KB failures |
| 2 | 🟠 | Schema | `runOnce` vs `run_once` naming — document for migrations |
| 3 | 🟠 | Concurrency | `saveAndFinalize` two-tx TOCTOU window |
| 4 | 🔵 | Port Design | `PersistenceAdapterPort` leaks `EntityManager` |
| 5 | 🔵 | Module Design | RAG classes live in wrong module, implicit LangChain4J dep |
| 6 | 🔵 | Adapter | 880-line adapter, A16 guard duplicated |
| 7 | 🔵 | Test Isolation | `findDueCrons` relies on adapter classpath presence |


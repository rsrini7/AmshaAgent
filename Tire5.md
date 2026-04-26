## Tier 5 – Resilience Patterns (Wave 4)

### T5-1: LLM Provider Circuit Breaker (PAT1) [DONE]
* **Problem:** No circuit breaker protects against cascading LLM provider failures. A down provider causes all agent sessions to timeout sequentially.
* **What to Do:** Add MicroProfile Fault Tolerance `@CircuitBreaker` on `LlmProviderPort` adapter. Configurable failure threshold and recovery window.
* **Invariants Referenced:** §3.1
* **Done When:**
    * [x] Circuit breaker opens after N consecutive LLM failures
    * [x] Metric `arcclaw.llm.circuit_breaker.state` exposed
    * [x] Kill switch via config (`arcclaw.llm.circuit-breaker.enabled=false`)

---

### T5-2: Tool Bulkhead Executor (PAT1) [DONE]
* **Problem:** All tool executions share the same thread pool. A slow external tool (e.g., Playwright) can starve fast tools.
* **What to Do:** Separate `ManagedExecutor` pools for internal vs external tools.
* **Done When:**
    * [x] Two executor pools configured
    * [x] Slow tools isolated from fast tool execution

---

### T5-3: Cron Leader Election (PAT1) [DONE]
* **Problem:** In multi-instance deployment, all instances poll and dispatch crons. No leader election prevents duplicate execution.
* **What to Do:** DB advisory lock (SQLite: dedicated `advisory_lock` table-based lock; Postgres: `pg_try_advisory_lock`).
* **Invariants Referenced:** §5.12
* **Done When:**
    * [x] Only one instance dispatches crons at a time
    * [x] `BackendCapabilities.supportsAdvisoryLocks()` flag

---

### T5-4: Distributed Resume Lock (XB1) [DONE]
* **Problem:** Double-resume guard (`ResumeCoordinationGuard`) is in-memory `ConcurrentHashMap`. Fails in multi-instance deployment.
* **What to Do:** Move guard to DB-level lock (atomic transition to `RESUMING` status).
* **Invariants Referenced:** §3.4, §7.29
* **Done When:**
    * [x] Resume guard survives multi-instance deployment
    * [x] Existing `BatchResumeRaceTest` passes with DB-backed guard

---

### T5-5: Notification Retry with Fallback (XB3) [DONE]
* **Problem:** Channel notification delivery is fire-and-forget. A transient Telegram API failure silently drops user notifications.
* **What to Do:** Add retry (3 attempts, exponential backoff) with fallback to alternate channel if configured.
* **Done When:**
    * [x] Notification failures retried 3 times
    * [x] Metric `arcclaw.notification.retry.exhausted` exposed

---

### T5-6: Atomic Checkpoint Status Guard (RC2) [DONE]
* **Problem:** Checkpoint status transitions lack CAS protection. Concurrent transitions can overwrite each other.
* **What to Do:** Add `WHERE status = :expected` to all status UPDATE queries.
* **Invariants Referenced:** §5.18, §5.19
* **Done When:**
    * [x] All status transitions use optimistic CAS
    * [x] Test verifies concurrent transitions don't corrupt state

---

### T5-7: WebSocket Reconnect (M1) [DONE]
* **Problem:** UI WebSocket has no automatic reconnection on disconnect.
* **What to Do:** Add exponential backoff reconnection in `arcclaw-ui/src/api/websocket.ts`.
* **Invariants Referenced:** §21.8
* **Done When:**
    * [x] Auto-reconnect with backoff (1s → 2s → 4s → max 30s)
    * [x] Connection status visible in UI

---

### T5-8: Compaction Summary Bounds (M7) [DONE]
* **Problem:** Compaction summary has no token tracking or bounds enforcement.
* **What to Do:** Add max token limit to compaction output. Track input vs output tokens.
* **Done When:**
    * [x] Compaction respects `arcclaw.compaction.max-tokens` config
    * [x] Metric `arcclaw.compaction.token_ratio` exposed

---

### T5-9: Reasoning History Continuity (PAT2) [DONE]
* **Problem:** Resuming a paused agent turn (HITL) often lost context, causing the agent to re-request tools or start from a blank slate.
* **What to Do:** Transition history storage from a deprecated JSON blob (`stateJson`) to granular `ReasoningStepEntity` rows. Ensure restoration handles cross-role resumes (e.g. Coordinator resuming a Researcher checkpoint).
* **Invariants Referenced:** §7.31, §7.46
* **Done When:**
    * [x] `StatefulAgentStoreImpl` prioritizes granular step loading
    * [x] `CheckpointResumeCoordinator` resolves checkpoints by `sessionId` across all roles
    * [x] `AgentLoop` correctly restores the original persona (role) on resume

---

### T5-10: Background Task Isolation (RC3) [DONE]
* **Problem:** Cron sessions were being interfered with by irrelevant user RAG context or misinterpreted as fresh scheduling requests by weak models.
* **What to Do:** Add a session-prefix guard in `RagContextEnricher` to skip RAG for cron tasks. Strip the internal `CRON ` goal prefix before the LLM sees it.
* **Invariants Referenced:** §7.45
* **Done When:**
    * [x] RAG enrichment skipped for `cron-*` session IDs
    * [x] `EngineStepHandler` strips `CRON ` prefix from the goal string passed to the LLM

---

### T5-11: Persistence Schema Integrity (RC4) [DONE]
* **Problem:** Inconsistent naming and missing columns in SQLite caused `no such table` errors and lost execution metadata.
* **What to Do:** Align JPA entity mapping with native SQL queries. Implement idempotent startup migrations for the `agentcron` table.
* **Done When:**
    * [x] `agentcheckpoint` renamed to `agent_checkpoint` with standardized snake_case columns
    * [x] `CronSchemaEnforcer` adds missing metadata columns (`channelType`, `executionRole`, `updatedAt`)

---

### T5-12: Autonomous Recovery Hints (PAT3) [DONE]
* **Problem:** Models would occasionally collapse into raw JSON output after a schema error or stall after Gemini safety filter blocks.
* **What to Do:** Add prose detection in `EngineStepHandler` to intercept raw JSON. Classify Gemini safety blocks as `SECURITY_POLICY` failures.
* **Done When:**
    * [x] `looksLikeRawToolJson` guard prevents JSON-prose leaks with a structural `{` guard
    * [x] `LlmExceptionClassifier` maps Gemini "parts is null" NPE to graceful security failures

---

### T5-13: Cron Lifecycle UX (M8) [DONE]
* **Problem:** Stale cron jobs polluted the `/crons` list and orphaned approvals from previous server runs blocked new tasks.
* **What to Do:** Add a configurable retention filter to the list command. Implement an automatic startup sweep to fail orphaned tasks.
* **Done When:**
    * [x] `/crons` filters terminal tasks by `arcclaw.cron.list-retention-hours` (default 24h)
    * [x] `AgentCronScheduler` sweeps and fails orphaned `AWAITING_APPROVAL` jobs on boot
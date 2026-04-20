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
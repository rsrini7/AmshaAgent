# PROMPT — PHASE 85: Remaining P84 Findings & Tenant Readiness

Role: Senior Java Architect, Bug Fixer & Senior Java Developer
Task: Phase 85 — Close remaining findings from Phase 84 audit (F8, F9, F22, F27, F28) and complete the cross-tenant isolation audit (D3).

***

## COMMON IMPLEMENTATION REQUIREMENTS

1. No new dependencies unless strictly required.
2. JDK 25 / Quarkus 3.27 compatibility must be preserved. No preview features.
3. Prefer typed/stateful fixes over string sentinels.
4. Add or update regression tests for every component touched.
5. All existing tests must remain green after each component is completed.
6. Any new regex must use a `static final Pattern` field; never compile patterns inline.
7. Run targeted tests during implementation, then `mvn clean test` for final validation.
8. Update `Invariants-findings.md` status for each resolved finding (mark finding ID + test class reference).

***

# PHASE 85-A — Security

### Component A1 — HitlService Cross-Tenant Guard (F27, P0 Security)

**Closure:** Mark **F27 resolved** in `Invariants-findings.md`.

**Current Risk**

- `HitlService.getPendingApprovals()` runs:
  ```java
  em.createQuery(
      "SELECT c FROM AgentCheckpoint c WHERE c.status = :status",
      AgentCheckpoint.class
  )
  .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
  .getResultList();
  ```
- `HitlResource.listPending()` exposes this at `GET /hitl` **with only API-key auth**, no per-user filter, no pagination. Any valid key can see all users' pending approvals.

**Required Changes**

1. **API contract:**
   - `HitlService.getPendingApprovals(String userId, int limit)`:
     - Filter by checkpoints owned by this `userId`.
     - Apply `ORDER BY c.id DESC` (§5.19 alignment).
     - Apply `LIMIT` with a sane cap (e.g. 50), even if the caller passes a higher value.

2. **Ownership model:**
   - Use `SessionHeartbeatTracker` or existing session registry to map:
     - API key → userId
     - userId → sessionIds
   - Ensure the query narrows to:
     ```sql
     WHERE c.status = AWAITING_APPROVAL
       AND c.sessionId IN (:sessionIdsForUser)
     ```
   - The `userId` used here must **not** come from LLM inputs; only from trusted context (API key binding, Telegram adapter, etc.).

3. **HitlResource wiring:**
   - `HitlResource.listPending()` must:
     - Validate the `apiKey` using `ApiKeyValidator`.
     - Resolve the bound `userId` for that key.
     - Call `hitlService.getPendingApprovals(userId, 50)`.

4. **Tests (required):**
   - New tests in `HitlServiceTest` or `HitlResourceTest`:
     - Given checkpoints for `userA` and `userB`, verify:
       - When calling `getPendingApprovals(userA, ...)`, only `userA`'s sessions appear.
       - Same for `userB`.
     - Verify result set is capped to the configured limit.

**Constraints**

- No new stringly-typed "user owner" fields if an existing canonical accessor (e.g. heartbeat tracker) already expresses ownership.
- This phase must not relax any existing HITL security checks.

***

# PHASE 85-B — Loop Correctness & Hot-Path Optimization

### Component B1 — AgentLoop Finish Fast-Path (F9)

**Closure:** Mark **F9 resolved** in `Invariants-findings.md`.

**Current Risk**

- `AgentLoop.tryDelegateFinishFastPath(...)` uses string-prefix checks on the observation text (e.g. `"Tool execution failed"`, `"Parameter"` etc.) to decide whether to fast-path a "finished" answer.
- This couples behavior to English strings and ignores the actual `ToolResponse.success` flag stored in the history.

**Required Changes**

1. **Data plumbing:**
   - Ensure `ReasoningStep` history entries for tool invocations carry enough information to know:
     - Whether the tool call was success/failure (`ToolResponse.success()`).
     - The structured error category if available.

2. **Fast-path implementation:**
   - Replace prefix-based checks with logic that:
     - Only fast-path if the **last tool step** has `success == true`.
     - Fails open (no fast-path) if `success` is unknown or false.
   - Keep the current behavior for non-tool steps.

3. **Tests:**
   - In `AgentLoopTest` (or equivalent):
     - A successful tool call followed by a clean answer must still fast-path to finished.
     - A failed tool call (success=false) must **not** be treated as a clean finished answer, even if the message text looks "nice".
   - Add regression coverage for a "Unicode art / ASCII art" answer that starts with characters your previous prefix guard would have misclassified.

**Constraints**

- Do not introduce new string sentinels. Use existing `ToolResponse` semantics.
- Do not change user-facing message text in this phase; only decision logic.

***

### Component B2 — Hot-Path Checkpoint Status Cache (F22)

**Closure:** Mark **F22 resolved** in `Invariants-findings.md`.

**Current Risk**

- In `AgentStateMachineRunner.run()`, before every tool invocation you do:
  ```java
  Optional<AgentCheckpoint> cpOpt =
      agentStore.load(context.sessionId, context.agentRole);
  if (cpOpt.isPresent() && cpOpt.get().getStatus() == AWAITING_APPROVAL) { ... }
  ```
- This is a `@Transactional(REQUIRED)` query on **every tool step**, on the tightest loop path.

**Required Changes**

1. **Context cache:**
   - Extend `AgentExecutionContext` with a cached checkpoint status field, e.g.:
     ```java
     private CheckpointStatus lastCheckpointStatus;
     private boolean statusDirty;
     ```
   - Provide methods to:
     - Initialize from the first DB read.
     - Mark as dirty whenever a status change is expected (e.g. after pausing for approval, after resume, etc.).

2. **Guard behavior:**
   - Change the guard to:
     - Use the cached status when `statusDirty == false`.
     - Re-read from DB **only** when:
       - Coming out of an approval/resume path, or
       - The previous step explicitly mutated checkpoint status.

3. **Tests & metrics:**
   - Add a test that runs a multi-step agent loop and asserts that `agentStore.load(...)` is called:
     - Once at startup.
     - Again only when a status transition happens.
   - Optionally add a metric or log debug flag when the guard falls back to DB.

**Constraints**

- Do not weaken the "AWAITING_APPROVAL guard" semantics; only reduce read frequency.
- Keep the logic compatible with `CheckpointApprovalService` and `ApprovalCheckpointManager`.

***

### Component B3 — Synthesizer Error Context Filtering (F28)

**Closure:** Mark **F28 resolved** in `Invariants-findings.md`.

**Current Risk**

- `AgentStateMachineRunner.handleFinishAction()` builds `combinedContext` for the synthesizer from **all** history observations, including:
  - Error messages like `"Tool execution failed: connection refused"`.
  - Sentinel messages that are not user-facing facts.
- These can be incorporated into the final answer as if they were real content.

**Required Changes**

1. **Observation filtering:**
   - Before building the synthesizer context:
     - Exclude steps where:
       - The underlying `ToolResponse.success == false`, or
       - The observation starts with known error sentinels (`"Tool execution failed"`, etc.).
   - Ensure "empty" or purely structural sentinel lines (like the parallel batch status line) are also filtered.

2. **Configurable pattern:**
   - Use a small, well-defined set of patterns / flags.
   - Prefer a boolean `success` or `errorCategory` flag already stored on the `ReasoningStep` over string prefixes.

3. **Tests:**
   - Add a synthesizer-specific unit test:
     - History contains:
       - A successful tool result with real content.
       - A failed tool result with an error message.
     - The final synthesized answer **must not** include the error message text.

**Constraints**

- Do not remove useful factual content; only obvious error/sentinel noise.
- Keep the existing "finish" behavior intact for non-synthesizer roles.

***

### Component B4 — Ollama callId Parse-Time UUID Fallback (F8)

**Closure:** Mark **F8 resolved** in `Invariants-findings.md`.

**Current Risk**

- `ReasoningStepParser` is supposed to:
  - Apply a UUID fallback for `callId` when Ollama returns `null`.
  - Do this at **parse time**, so `ChatHistoryAssembler` always sees non-null IDs.
- The invariant doc asserts this, but there is no explicit regression test.

**Required Changes**

1. **Test-only verification:**
   - Add `ReasoningStepParserTest` coverage that:
     - Constructs a fake Ollama-style JSON response where `callId` is missing or `null`.
     - Parses it into `ReasoningStep`s.
     - Asserts that every tool step has a non-null `callId`, with values that look like UUIDs (or at least non-empty and unique per step).

2. **No production behavior change:**
   - Only adjust parser code if the test fails.
   - If parsing already behaves correctly, document this in `Invariants-findings.md` as F8 resolved.

**Constraints**

- Do not change the on-wire protocol or model prompts in this phase.
- Focus on parser-level behavior and tests only.

***

# PHASE 85-C — Cross-Tenant Isolation Audit

### Component C1 — Systematic Tenant Isolation Audit (D3)

**Closure:** Document remaining D3 risks in `Tenant-Isolation-Audit-P85.md`, or mark audit complete in `Invariants-findings.md`.

**Goal**

Run a systematic audit of all endpoints and queries that surface user/session data, focusing on multi-tenant isolation.

**Required Deliverables**

1. A short markdown report (`Tenant-Isolation-Audit-P85.md`) that:
   - Lists all REST endpoints with per-user data (`/hitl`, `/agents`, `/chatstream`, etc.).
   - For each, documents:
     - How the user is authenticated (API key, Telegram, Web).
     - How user ownership is determined (session registry, heartbeat tracker, etc.).
     - Which DB queries are executed and how they filter by tenant.

2. A concrete list of any remaining cross-tenant risks with proposed phases (e.g. 85-D, 86) to fix them.

3. At least one regression test for any newly discovered P0/P1 issues similar to F27.

**Constraints**

- No large refactors in this phase: focus on **enumeration and documentation**.
- Code changes limited to adding missing filters or guards where risk is obvious.

***

## Phase Summary

| Phase | Component | Finding | Priority |
|---|---|---|---|
| **85-A** | A1 — HitlService Cross-Tenant Guard | F27 | P0 Security |
| **85-B** | B1 — AgentLoop Finish Fast-Path | F9 | P2 |
| **85-B** | B2 — Hot-Path Checkpoint Status Cache | F22 | P2 |
| **85-B** | B3 — Synthesizer Error Context Filtering | F28 | P2 |
| **85-B** | B4 — Ollama callId UUID Fallback | F8 | P2 |
| **85-C** | C1 — Cross-Tenant Isolation Audit | D3 | P3 |
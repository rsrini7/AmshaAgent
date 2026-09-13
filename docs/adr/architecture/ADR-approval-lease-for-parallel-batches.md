# ADR: Approval Lease for Parallel Tool Batches (Approval-Fatigue Mitigation)

- **Status**: Accepted and implemented (owner sign-off 2026-08-24; shipped same day —
  `ApprovalLeaseStore` in `core/hitl`, grant funnel in `ApprovalStateMachine.decide`,
  guard consumption inside the deterministic HITL clause with `Blocked`-beats-lease
  ordering, `HITL_LEASE_REUSED` telemetry, `clearSession` wired at workspace teardown,
  kill-switch `amsha.hitl.approval-lease.enabled`; core suite 2168/2168)
- **Date**: 2026-08-24
- **Decision Owners**: Amsha Architecture Committee / Operator
- **Origin**: Native-binary incident review 2026-08-23 (session `cli-937d65b6…`, finding F2 —
  the operator approved a fresh `parallel|batch` for nearly every LLM turn of one researcher
  delegation). Parked as [Issue 02](../../../.scratch/hitl-approval-policy/issues/02-parallel-batch-approval-lease.md).
- **Related**: `ADR-schedule-task-hitl-under-supervised.md`; `AGENTS.md` invariant
  "Pre-policy tool-guard Allow decisions still pass through every mandatory check";
  §F9 fix (resume no longer wraps loops in FULL autonomy).

## Context

Every parallel batch is guard-evaluated per call (`ParallelToolStepExecutor.evaluateGuardForCalls`);
any `PauseForApproval` checkpoints the whole batch and pauses the session. In long delegations
that re-propose the same risky calls each turn, this re-prompts for genuinely identical work —
approval fatigue, which trains operators to rubber-stamp. An unconditional approval memory would
weaken HITL and is rejected by invariants.

## Decision

Introduce a narrowly-scoped, in-memory **approval lease**:

### Grant

When the operator approves (via `ApprovalStateMachine.decide`, the single approve/deny funnel),
a lease `(sessionId, toolName, SHA-256(canonical-sorted-params-JSON))` is recorded from the
checkpointed batch parameters (`pendingToolParametersJson`). The grant itself is already audited
as mandatory `APPROVAL_GRANTED`.

### Consumption (the only behavioral change)

Inside `ToolInvocationGuard.evaluate`, the **deterministic HITL clause**
(`alwaysRequiresHitl` ∪ dynamic-admission approval ∪ role/static-requires-approval ∧ ¬FULL ∪
autonomy-driven SUPERVISED pause) may be satisfied by an active lease. On a lease hit the guard
**falls through to the normal allow path** — including the post-policy tool-risk classifier —
and emits telemetry audit `HITL_LEASE_REUSED` (no payload echo).

### Non-negotiable bounds (invariant analysis)

1. **Every mandatory check still runs.** Interceptors, parameter validation, deduplication,
   dynamic-admission fail-closed resolution, and role-capability authorization all execute
   *before* the HITL clause; the lease only substitutes for the human prompt itself.
2. **`Blocked` always wins.** Every `Blocked` return precedes lease consultation; a leased call
   that becomes blocked (role revocation, validation failure, unadmitted dynamic tool) is blocked.
3. **A lease cannot escalate risk.** It can only downgrade a deterministic pause to "run the
   remaining checks"; classifier outcomes are always respected: post-policy `REQUIRE_APPROVAL`
   re-pauses and `BLOCK` blocks even with an active lease.
4. **Session-bound.** Leases live in an in-memory store keyed by sessionId; they never cross
   sessions and are evicted by `clearSession` (wired at workspace teardown alongside the guard's
   dedupe cache) plus bounded-size LRU-style eviction.
5. **TTL-bounded.** Entries expire after `amsha.hitl.approval-lease.ttl-seconds`
   (default 3600); expiry checked lazily on access.
6. **Audited reuse.** Each hit records `SecurityEventType.HITL_LEASE_REUSED`
   (telemetry tier — a failed write must not halt the loop; the authoritative consent record
   remains the mandatory `APPROVAL_GRANTED`).
7. **Kill-switch.** `amsha.hitl.approval-lease.enabled=false` restores strict re-prompting.

### Storage

Purely in-memory (`ConcurrentHashMap`), mirroring the existing session-dedupe cache: leases are
optimization hints, not durable consent. Restart loses leases ⇒ falls back to prompting (safe
direction). No schema/persistence changes.

## Consequences

- Repeat identical risky calls inside one delegation session stop re-prompting; any param drift
  produces a different hash and prompts again.
- Memory footprint bounded by (sessions × distinct leased calls) with hard caps.
- New unit tests pin: grant→hit, params-drift miss, cross-session isolation, TTL expiry,
  clearSession eviction, Blocked-beats-lease, disabled flag, audit emission without payloads.

## Alternatives considered

- **Durable (checkpointed) leases** — rejected: durable consent memory invites stale grants
  outliving context; restart-fallback-to-prompting is the safe default.
- **Batch-level blanket lease (approve-all-shapes)** — rejected: decouples consent from the
  exact operation approved.
- **Do nothing / UX copy only** — rejected: fatigue was measured in-session and trains
  rubber-stamping.

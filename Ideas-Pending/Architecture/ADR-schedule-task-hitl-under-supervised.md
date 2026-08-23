# ADR: Require HITL Approval for `schedule_task` under SUPERVISED Autonomy

- **Status**: Accepted and implemented (owner sign-off 2026-08-24; shipped same day —
  policy flip in `AutonomyPolicy.Standard`/`StandardAutonomyPolicy`/`TestAutonomyPolicyProducer`,
  salvage default + exemption narrowing, guard autonomy-driven pause clause; core suite
  2168/2168, PMD `core,security -am` clean)
- **Date**: 2026-08-24
- **Decision Owners**: Amsha Architecture Committee / Operator
- **Origin**: Native-binary incident review 2026-08-23 (session `cli-937d65b6…`, finding F7);
  parked as [Issue 01](../../.scratch/hitl-approval-policy/issues/01-schedule-task-hitl-under-supervised.md)
  pending explicit owner sign-off, which is now recorded here.
- **Related**: `AGENTS.md` (HITL invariants), `docs/Architecture_Invariants.md` §governance,
  `ADR-approval-lease-for-parallel-batches.md` (companion fatigue mitigation)

## Context

`AutonomyPolicy` (core interface + `StandardAutonomyPolicy` impl bean) auto-allows
`ToolOperation.SCHEDULE_AUTONOMOUS` under `SUPERVISED`. Additionally, `schedule_task`
is listed in `amsha.governance.hitl-exempt-tools` and `ToolInvocationGuard` exempts all
scheduling tools from its pre-execution HITL gate. Net effect: an agent running at
SUPERVISED can create durable cron jobs silently. During the incident, a cron created
mid-session fired ~50 s later into the operator's interactive chat — surprising the
operator who had approved neither the creation nor the autonomous workload.

The old behavior was documented, intentional, and AGENTS-compliant (only
`NETWORK_OUTBOUND` and `EXECUTE_SHELL` are mandated HITL). Changing it is therefore a
security-posture decision requiring owner sign-off — granted with this ADR.

## Decision

1. **Policy flip** — `SUPERVISED` allows `READ_FS` only:
   - `AutonomyPolicy.Standard` (core)
   - `StandardAutonomyPolicy` (security impl bean)
   - `TestAutonomyPolicyProducer` test fixture (mirrors impl per its own contract)
2. **Salvage routing** — add `SCHEDULE_AUTONOMOUS` to the
   `amsha.governance.supervised-salvage-operations` default so a SUPERVISED denial
   surfaces as a 409 pause-for-approval (`RequiresApprovalException`) rather than a
   hard 403 block that would dead-end the coordinator.
3. **Exemption narrowing** — remove `schedule_task` from
   `amsha.governance.hitl-exempt-tools` default (yaml + `.env.example` +
   `PolicyConfig` `@WithDefault`); `manage_schedule` stays exempt so list/read/delete
   management actions remain approval-free.
4. **Guard alignment** — `ToolInvocationGuard` narrows its scheduling exemption to
   `manage_schedule` only and gains an autonomy-driven HITL clause
   (role-requiring-approval + SUPERVISED + policy-disallowed ⇒ `PauseForApproval`),
   so parallel batches containing `schedule_task` pause **before any call executes**
   (consistent with `evaluateGuardForCalls`/`handleHitlBatch`) instead of failing
   mid-batch at execution time.

### Deliberately unchanged

- **FULL autonomy** still executes `schedule_task` without approval.
- **Trusted roles** (`requiresApprovalForTools=false`) keep bypassing non-sensitive
  operation checks; `SCHEDULE_AUTONOMOUS` is not added to the sensitive set
  (`NETWORK_OUTBOUND`/`EXECUTE_SHELL`), matching the exec-time trust model in
  `ToolGovernanceGuard.verifyAutonomy`.
- **BACKGROUND/READONLY** semantics unchanged (disallowed operations hard-block at
  execution time, as today).

## Consequences

- Cron creation under supervision becomes operator-visible and operator-consented;
  the incident class "surprise cron fired into chat" is closed at creation time.
- Agents at SUPERVISED doing legitimate scheduling pay one approval per new
  schedule shape (fatigue bounded by the companion lease ADR where applicable).
- Existing tests asserting the old allow-list are updated; new guard/policy tests
  pin the flip, the `manage_schedule` exemption, FULL/trusted-role passthrough, and
  parallel-batch pre-execution pausing.

## Alternatives considered

- **Hard-block scheduling under SUPERVISED (403)** — rejected: dead-ends the loop
  (no delegation/code-gen fallback for this op) and loses operator consent UX.
- **Per-role opt-in config** — rejected for now: adds configuration surface before
  demand exists; revisit if trusted/non-trusted role mixes need different posture.

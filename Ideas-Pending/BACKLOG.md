# Ideas Pending — Backlog

Deferred, decision-gated, or design-required work items. Each entry names its
source of truth; detailed checklists live in the referenced tracker tickets.

## HITL / Approval Policy

Deferred from the 2026-08-23 delegation/HITL hardening round (native-binary
incident review, session `cli-937d65b6…`; F1/F4/F6/F9/F10 fixes committed).
Tracker: [`.scratch/hitl-approval-policy/issues/`](../.scratch/hitl-approval-policy/issues/).

### Require HITL for `schedule_task` under SUPERVISED — [Issue 01](../.scratch/hitl-approval-policy/issues/01-schedule-task-hitl-under-supervised.md)

- **Type**: security-posture decision (needs owner sign-off, not a bug fix).
- **Today**: `AutonomyPolicy.Standard` auto-allows `SCHEDULE_AUTONOMOUS` under
  `SUPERVISED`; cron creation happens without approval (compliant — only
  `NETWORK_OUTBOUND`/`EXECUTE_SHELL` are mandated HITL).
- **Proposal**: remove it from the SUPERVISED allow-list so scheduling needs
  approval; one-line change plus flow/test/doc checklist in the ticket.

### Approval lease for parallel tool batches — [Issue 02](../.scratch/hitl-approval-policy/issues/02-parallel-batch-approval-lease.md)

- **Type**: UX/design (ADR required before implementation).
- **Today**: every parallel batch is guard-evaluated; each new risky call
  re-prompts, causing approval fatigue inside long delegations.
- **Proposal direction**: narrowly-scoped approval lease — `(sessionId, toolName,
  params-hash)` grants that die with the delegation session, audit event on every
  lease hit, `Blocked` always wins over a lease. Unconditional approval memory is
  rejected (violates the every-mandatory-check invariant).

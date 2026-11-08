# ADR: Reset-Only V1 Baseline for Tool Attempt Settlement

- **Status**: Accepted (2026-11-01; project author approved the reset-only rollout)
- **Date**: 2026-11-01
- **Decision owners**: Amsha Agent maintainers
- **Feature slug**: `tool-attempt-settlement`
- **Related invariants**: `docs/Architecture_Invariants.md` §5.2 and §5.6; `AGENTS.md` persistence, migration-parity, and backend-selection rules
- **Proposal**: [`Tool-Attempt-Settlement-Proposal.md`](../../proposals/Tool-Attempt-Settlement-Proposal.md)

## 1. Context

The tool-attempt journal needs new durable tables and constraints in SQLite and PostgreSQL. The existing SQL migration streams reach V12. The feature also needs to reject replay of old pending tool calls whose checkpoints have no durable operation identity; safely backfilling those calls is not part of this proposal.

The requested rollout may discard the existing local Amsha data. Removing local SQLite or filesystem data directories does not clear an external PostgreSQL database. The default, test, and PostgreSQL profile configurations currently set `baseline-on-migrate: true`, so a non-empty database without Flyway history can be baselined without executing V1. Editing an applied V1 also changes its checksum and must be treated as an explicit migration-policy exception.

## 2. Proposed decision

For this feature only, add the journal schema to both SQLite and PostgreSQL `V1__execution.sql` clean-install baselines. Do not add a V13 data migration or backfill. Before rollout, stop the application and recreate the SQLite database, filesystem data root, and any PostgreSQL database used by the deployment. The new application must not start against retained state.

Add the same schema-contract marker to both V1 files. An eager startup guard checks that marker and all required journal tables after Flyway completes and before readiness or tool dispatch. A missing marker, missing table, unsupported version, or failed check aborts startup without creating or repairing schema. The filesystem backend uses an equivalent versioned marker; a non-empty unmarked data root fails closed.

The project author approved this scoped exception on 2026-11-01. Implementation may edit the paired V1 baselines for this feature; no data migration, backfill, or old-checkpoint conversion is required. The general migration policy remains unchanged for all other features, and Architecture Invariants §5.2 names this scoped exception.

A separate, narrowly scoped exception was accepted on 2026-11-07 in [ADR-Autonomous-Cron-Workspace-Identity-and-Reset-Rollout](ADR-Autonomous-Cron-Workspace-Identity-and-Reset-Rollout.md): the clean-install V2 scheduling baselines may add the `workspace_id` fields for cron jobs and runs, with the clean datastore reset required by that ADR. This extends the reset-only policy for those named fields only; it does not authorize unrelated changes to V1 or V2 baselines.

## 3. Consequences

- All data in the reset stores is lost, including sessions, checkpoints, memories, audit rows, and vault metadata. Operators must recreate any required local configuration or credentials through the normal setup paths.
- Existing Flyway histories are unsupported for this rollout. V1 checksum validation failures are expected and must not be bypassed or repaired automatically.
- The startup marker catches the `baseline-on-migrate` case where Flyway records a baseline but skips the modified V1 script. On the supported fresh-install path the marker is present; the guard is a fail-fast reset check, not a data migration. The guard must be proven to run after migration and before service readiness.
- The 2026-11-01 exception applies only to paired tool-attempt V1 baseline DDL. The separate 2026-11-07 exception applies only to the named cron workspace fields in V2. Neither exception establishes a general precedent for editing applied migrations.
- Verification must cover a clean V1 install, a non-empty schema with no Flyway history, existing Flyway history, marker mismatch, and filesystem marker failures.

## 4. Alternatives considered

- **Add a forward migration and preserve existing stores.** This avoids changing V1 but retains old session data and requires a policy for legacy pending checkpoints that cannot be safely assigned operation identities. It does not match the requested reset-only rollout.
- **Rely on operators to delete local data without a schema marker.** This cannot detect external PostgreSQL state or a baseline that skips V1, so the application could appear ready without the journal schema.
- **Silently repair missing tables or Flyway history at startup.** Rejected because it hides an incomplete or mismatched reset and can admit tool execution without durable settlement state.

## 5. Implementation gate

This ADR is accepted. Implementation may edit the paired V1 baselines for the tool-attempt journal; the separate cron-workspace V2 exception is recorded in the linked ADR. It must keep SQLite and PostgreSQL DDL equivalent, include the startup reset guard and filesystem marker, and retain the no-backfill/no-automatic-repair behavior defined in the proposal.

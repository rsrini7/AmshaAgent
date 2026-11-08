# ADR: Autonomous Cron Workspace Identity and Datastore Reset Rollout

- **Status**: Accepted — architecture decision closed; implementation and deployment gates remain open
- **Date**: 2026-11-07
- **Decision owners**: Amsha Architecture Committee / Maintainers
- **Feature slug**: `tool-attempt-settlement`
- **Related invariants**: `docs/Architecture_Invariants.md` §5.2, §5.6, §6, §12; `docs/adr/architecture/ADR-Tool-Attempt-Settlement-Reset-Only-V1-Baseline.md`
- **Proposal**: [`Tool-Attempt-Settlement-Proposal.md`](../../proposals/Tool-Attempt-Settlement-Proposal.md)

## 1. Context

During the implementation of Phase C of the Tool Attempt Settlement proposal (`Tool-Attempt-Settlement-Proposal.md`), two distinct architectural gates remained open:

1. **Autonomous Cron Workspace Identity**: Scheduled cron tool attempts currently fail closed because `agent_cron` does not persist an authoritative workspace identifier. In Phase C (steps C.4 and C.5), workspace identity was propagated through approval resumes, rate-limit retries, and startup recovery, but autonomous cron execution was held fail-closed (`WorkspaceContext.activeWorkspaceId().isEmpty()`) to honor the invariant that no schema changes or unverified heuristics should be introduced without an explicit architecture decision.
2. **Datastore Reset Rollout Plan**: In `ADR-Tool-Attempt-Settlement-Reset-Only-V1-Baseline.md`, a one-time migration policy exception was approved to add journal tables and a contract marker directly into `V1__execution.sql` instead of adding an incremental `V13` migration and backfilling legacy pending checkpoints. Existing datastores cannot be upgraded in place; a clean-install datastore reset is mandatory. The exact operational sequence and verification procedure must be formalized before deployment.

This ADR closes the workspace-attribution and reset-policy decisions. Startup recovery ownership remains a deployment and implementation gate described in Part A; this ADR does not establish that shared-database multi-instance recovery is safe.

## 2. Decision

### Part A: Autonomous Cron Workspace Identity

1. **Durable Schema Attribution**:
   - The scheduled job table `agent_cron` and execution run table `agent_cron_run` in the clean-install baselines (`V2__scheduling_and_skills.sql` for SQLite and PostgreSQL, and the filesystem equivalent) will include an explicit `workspace_id TEXT NOT NULL` field.
   - `AgentCron`/`AgentCronRecord` and the persistence DTOs `AgentCronDto`/`CronRunDto` will expose the persisted workspace identity through immutable `workspaceId()` properties.
   - Every cron run must have the same workspace ID as its parent cron. SQL backends must enforce this relationship with a composite foreign key or equivalent transactional validation; the filesystem backend must validate it while holding the workspace store lock.

2. **Schedule-Time Workspace Capture**:
   - The current creation path is `schedule_task`; it captures the active workspace ID (`WorkspaceContext.activeWorkspaceId()`) from the calling context. Any future administrative endpoint that creates a cron must follow the same rule.
   - Existing management and reactivation endpoints must preserve the workspace ID already stored on the cron. They must not accept a replacement workspace ID from the request.
   - If no active workspace is bound at creation time, `schedule_task` must fail fast and reject job creation with an explicit error. Cron creation cannot proceed without a verified workspace context.

3. **Execution-Time Workspace Binding**:
   - In `CronJobRunner`, the execution lease loop and the agent loop execution (`agentLoop.run(...)`) will be wrapped with:
     ```java
     WorkspaceContext.callWithActiveWorkspace(job.getWorkspaceId(), () -> { ... });
     ```
   - This ensures that when the autonomous agent loop enters `ToolAttemptExecutionService.admitAttempt(...)`, an authoritative workspace identity is active. Tool attempt admission, action fingerprinting, claim reservation, and audit journaling will execute fully namespaced to the job's owning workspace.

4. **Fail-Closed Enforcement**:
   - Any historical or corrupted cron job with a missing, empty, or unresolvable `workspace_id` must fail closed.
   - The runtime must never guess or default to an arbitrary workspace from `IdentityStorePort` or global defaults, as workspace guessing violates tenant isolation.

5. **Startup Recovery Ownership**:
   - `AMSHA_RECOVERY_SINGLE_INSTANCE=false` is not proof that a node owns an attempt or may reconcile it. A node must not transition or lock an active attempt unless it holds exclusive, fenced recovery ownership.
   - The current startup reconciler transitions `LAUNCH_REQUESTED` and `RUNNING` attempts to `INDETERMINATE` in multi-instance mode and rejects `ADMITTED` attempts. Since the startup observer runs on every node, this behavior can alter a peer's live attempt. Treat this as an implementation gap, not as safe secondary-node behavior.
   - Shared-database multi-instance startup recovery is not deployment-ready until a fenced recovery-owner mechanism is implemented and verified. Until then, do not claim that setting `AMSHA_RECOVERY_SINGLE_INSTANCE=false` alone protects active peer work.
   - Selecting that fenced ownership mechanism is intentionally left to a follow-on ADR. This record closes the workspace-attribution and reset-policy decisions without choosing a cluster recovery lease or leader-election design.

### Part B: Datastore Reset Rollout Plan

1. **Rollout Invariant**:
   - No in-place data migration or Flyway checksum repair is permitted for existing databases.
   - The release requires an unreserved, fresh datastore reset prior to starting the new application version.
   - This ADR authorizes a second, narrowly scoped reset-only exception: adding the cron `workspace_id` fields to the clean-install V2 scheduling baselines. It does not broaden the existing V1 tool-attempt exception or permit other edits to applied baselines.

2. **Operational Sequence**:
   - **Step 1: Shutdown & Executor Drain**:
     - Stop all Amsha instances across all nodes.
     - Verify no orphaned Podman containers (`amsha.tool-attempt-id` labeled) or isolated processes remain running on the host.
   - **Step 2: Storage Wipe**:
   - *SQLite*: Resolve and verify the canonical configured data root. Review the exact database files that will be removed and confirm they belong to the intended instance before deleting them. Quote paths containing the configured directory; do not run an unquoted variable-expanded deletion command.
   - *Filesystem*: Resolve and verify the canonical workspace root and the data it contains before clearing it. Initialize the root schema contract manifest with `contractVersion: 1` only after confirming the intended root is empty.
   - *PostgreSQL*: Connect to the intended server and database and verify both identities before resetting it. Then execute `DROP SCHEMA public CASCADE; CREATE SCHEMA public;` or provision a clean database instance.
   - **Step 3: Single-Instance Bootstrap & Baseline Migration**:
   - Boot the designated recovery instance in single-instance mode (`AMSHA_RECOVERY_SINGLE_INSTANCE=true`) only while all previous application instances are stopped.
     - Flyway applies clean `V1__execution.sql` and `V2__scheduling_and_skills.sql`.
   - **Step 4: Startup Schema Guard Verification**:
     - The `@Startup` bean `ToolAttemptSchemaGuard` executes at `@Priority(20)` on `StartupEvent`, confirming:
       - Marker table `schema_contract_marker` exists with `contract_name = 'tool-attempt-settlement'` and `contract_version = 1`.
       - All journal tables (`tool_attempt`, `tool_action_claim`, `tool_action_lock`, `tool_attempt_audit_outbox`, `tool_attempt_resolution`, `tool_attempt_reasoning_step`) are present.
       - `StartupAttemptReconciliationObserver` executes at `@Priority(30)` and startup reconciliation completes without error. This observer does not currently assert that all attempts are terminal; an `ADMITTED` attempt associated with an active checkpoint may remain nonterminal.
       - Readiness health check (`/q/health/ready`) reports UP after startup observers complete. Readiness alone is not evidence that a multi-instance recovery owner has been established.
   - **Step 5: Post-Reset Initialization**:
     - System credentials, API keys, and vault peppers are loaded from environment secrets.
     - Workspaces and users are initialized through standard identity onboarding.

## 3. Consequences

- **Autonomous Cron Feasibility**: With `workspace_id` durably persisted on `agent_cron`, autonomous cron runs execute under an authoritative workspace scope, unblocking safe tool attempt settlement and reconciliation.
- **Tenant Isolation**: Tool execution in scheduled jobs is strictly isolated to the workspace in which the schedule was authorized.
- **WORM Audit & Data Reset**: All historical session, memory, and checkpoint data prior to the reset is cleared. The fresh clean install guarantees complete schema contract compliance without legacy ambiguities.
- **Recovery Ownership Gate**: The startup observer currently runs on every node, and `AMSHA_RECOVERY_SINGLE_INSTANCE=false` does not disable reconciliation. Shared-PostgreSQL deployments must not start another node while active attempts could be encountered by startup recovery. Add and verify fenced recovery ownership before enabling that deployment mode.

## 4. Alternatives Considered

- **Infer Workspace from User Identity**:
  - *Rejected*: A user may belong to multiple workspaces. Silently selecting a user's first workspace creates cross-workspace data leakage and security violations.
- **In-Place Migration with V13 Script**:
  - *Rejected*: Existing pending checkpoints lack durable operation identities. Backfilling synthetic operation IDs into active checkpoints cannot prove whether the underlying tool call was previously executed. The project author approved the clean reset exception in `ADR-Tool-Attempt-Settlement-Reset-Only-V1-Baseline.md`.

## 5. Decision Closure and Implementation Handoff

The architecture decision is accepted and closed: cron workspace identity is durable, workspace guessing is prohibited, and this feature requires a clean datastore reset. **This closes the decision record only; it does not claim that the schema, stores, DTOs, runtime wiring, reset, or recovery ownership changes have been implemented.**

Before autonomous cron tool execution is enabled, implementation must add the workspace fields to the SQLite and PostgreSQL V2 baselines and filesystem records, update all DTOs and persistence adapters, enforce cron/run workspace consistency, capture workspace identity at creation, and bind it during execution. It must also resolve the startup recovery ownership gap above and test the reset guard and rollout behavior.

Phase D lock-resolution work in the proposal can proceed independently. Autonomous cron tool execution remains unsupported until this ADR's implementation gates pass.

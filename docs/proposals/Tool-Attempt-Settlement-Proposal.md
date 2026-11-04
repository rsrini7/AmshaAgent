# Tool Attempt Settlement and Indeterminate Outcome Handling

> **Document type:** Architecture design proposal
> **Status:** Proposed — revised for architecture review
> **Target modules:** core, tool-api, persistence-api, persistence-sqlite, persistence-postgres, persistence-fs, sandbox
> **Governing invariants:** Architecture Invariants §§1.2, 1.6, 3.2, 3.3, 3.3.1, and 5, including §5.24
> **Upstream reference:** Rui (workspace rui), ARCHITECTURE.md transaction/execution recovery sections and src/store.zig
> **Date:** November 1, 2026

## 1. Objective and scope

This proposal adds a durable record of each governed tool attempt so a restart cannot silently repeat an operation whose outcome is unknown. It adapts Rui's indeterminate-outcome rule to Amsha's existing tool guard, persistence ports, sandbox executors, and checkpoint-resume paths.

The journal records admission, launch intent, confirmed execution state, terminal outcome, and a sanitized observation. It also supports durable replay fences and reconciliation with a checkpoint that was interrupted while a tool call was pending.

The first delivery covers sequential and parallel agent tool calls, approval-resumed calls, cron resumes, built-in and host tools, browser/MCP paths, and each configured sandbox strategy. Every path must enter the same governed admission and settlement boundary.

### Goals

1. Never automatically replay an operation after launch may have occurred and no definitive outcome is available.
2. Feed a recovered observation or an explicit indeterminate result back into the existing resumed workflow exactly once.
3. Enforce replay restrictions across turns using a stable action fingerprint, not only a turn-local call identifier.
4. Keep attempt, lock, audit-outbox, migration, and adapter behavior consistent across SQLite, PostgreSQL, and filesystem persistence.
5. Preserve existing tool governance, HITL approval, output sanitization, and the HMAC audit chain.

### Non-goals

- Replacing Amsha's sandboxing, multi-agent architecture, provider routing, or memory system with Rui components.
- Introducing a separate host daemon or detached terminal client.
- Claiming power-loss durability beyond the guarantees of the selected persistence backend.
- Changing SQLite's shared WAL or synchronous configuration.
- Adding a new global output-byte configuration in this change.
- Retaining full tool output as an artifact.

## 2. Verified repository constraints

The design must account for these current seams:

- ToolInvoker.invoke currently accepts tool name, parameters, and session ID. ToolExecutionContext has no operation, turn, or call index. Identity therefore has to be added and threaded through the dispatch and resume paths; it cannot be reconstructed inside ToolInvoker.
- Existing continuation uses CheckpointResumeHandler, CheckpointResumeCoordinator, PendingToolResumeExecutor, ParallelBatchResumeHandler, ResumeCoordinationGuard, and StatefulAgentStore. There is no AgentLoopReconciler today.
- ToolInvocationGuard has an in-memory per-session deduplication cache and a short history scan. It only performs that check for idempotency modes other than NON_IDEMPOTENT. This is not a durable replay fence.
- AuditEventRecorder and SessionAuditSequencer own HMAC event persistence and per-session sequence/hash assignment. There is no tool-attempt audit outbox today. SecurityEventType belongs to core; persistence-api must not depend on core.
- SQLite is configured centrally for WAL, synchronous=NORMAL, and busy_timeout=10000. The attempt store must use that configuration; it must not set per-table PRAGMAs. The feature covers service/process restart while the database and host OS remain available; it does not claim host power-loss durability.
- The current SQL migration streams reach V12. For this explicitly reset-only release, update the paired SQLite and PostgreSQL V1__execution.sql clean-install baselines with the new schema instead of adding a new migration version. This changes the V1 checksum, so every SQL database must be dropped and recreated before the new application starts; existing Flyway histories are unsupported.
- Current output limits overlap: SandboxExecTool and HostExecTool each default to 8192 characters, HostExecTool allows a configured ceiling up to 131072 characters, and TruncationTransformer caps at 16384 Unicode code points with its marker appended after that cap. OutputTruncator mixes Java string-length checks with code-point truncation. This proposal preserves these behaviors.
- SandboxStrategy selects PODMAN, NAMESPACE, DIRECT, or NONE. The concrete container executor is PodmanContainerExecutor; process strategies are handled through ProcessIsolationExecutor, NamespaceExecutor, and DirectExecutor. There is no PodmanSandboxPort.
- Persistence contracts use root-package ports ending in StorePort and DTOs ending in Dto. The persistence API owns domain enums used by stores and must not acquire a dependency on tool-api.

These constraints should be rechecked against source when implementation begins.

## 3. Outcome taxonomy and retry policy

A timeout is a known execution outcome only when supervision confirms the deadline and confirms that execution stopped. It does not prove the tool made no partial changes. A post-launch cancellation or ToolInvoker 499 response does not prove the delegated operation never ran.

| State | Meaning | Replay rule |
| --- | --- | --- |
| ADMITTED | Governance and approval completed; the operation is durably recorded, but launch intent has not been committed. | Continue the same operation only after resume-time governance checks. If the checkpoint is abandoned, settle as CANCELLED_BEFORE_LAUNCH. |
| LAUNCH_REQUESTED | Launch intent is durably committed before executor invocation; the executor may or may not have accepted it. | On restart, require definitive executor evidence; otherwise classify INDETERMINATE. |
| RUNNING | Process/container start is confirmed, or an in-process call is recorded immediately before invocation. | A missing terminal record after restart is INDETERMINATE unless durable executor evidence proves the outcome. |
| SETTLED_SUCCESS | The tool returned confirmed success and its sanitized observation is durable. | Never execute this operation again; resume by returning its stored observation. |
| SETTLED_ERROR | The tool returned a confirmed error; side effects may still have occurred. | IDEMPOTENT may retry only under the existing retry policy. NON_IDEMPOTENT needs a durable one-shot approval. AT_MOST_ONCE never executes the same action fingerprint again. |
| TIMED_OUT | The supervisor confirmed deadline expiry and process/container termination; partial side effects may exist. | IDEMPOTENT may retry only under the existing retry policy. NON_IDEMPOTENT needs a durable one-shot approval. AT_MOST_ONCE never executes the same action fingerprint again. |
| CANCELLED_BEFORE_LAUNCH | No launch request was committed, or the executor durably confirmed rejection before execution began. | A later request may start a new attempt after normal governance and atomically releasing the RESERVED AT_MOST_ONCE claim. |
| INDETERMINATE | Launch may have occurred, but Amsha cannot establish a definitive result. This includes post-launch halt/interruption without proof the tool did not run. | Never auto-replay. Keep the durable action lock until evidence-based or explicit operator resolution. |

Retry classification comes from AgentTool.idempotencyMode(), never from model-supplied parameters. Snapshot the mode on the attempt as a stable persisted value. Unknown or invalid historical values fail closed as NON_IDEMPOTENT.

- IDEMPOTENT permits only retries already allowed by the loop retry policy after confirmed SETTLED_ERROR or TIMED_OUT. It never makes an INDETERMINATE operation safe to replay.
- AT_MOST_ONCE uses a durable key scoped by workspace, session, tool name, fingerprint version, and action fingerprint. The key catches an exact repeat across turns even when a new turn has a new operation ID. After settlement, a repeat may return the stored result; while unresolved it is blocked. Approval cannot override this fence.
- NON_IDEMPOTENT does not automatically retry a settled error or timeout. A new attempt requires approval bound to the originating attempt and action fingerprint. INDETERMINATE also requires an explicit acknowledgement of uncertainty or verified postcondition evidence.

## 4. Identity and governed execution boundary

### 4.1 Stable invocation identity

Create an opaque operation ID at the orchestration boundary when a tool call is first accepted. Persist it with the pending call before approval or execution, and carry it through checkpoint serialization, resumed history, parallel batches, cron paths, and every executor adapter. A resumed operation reuses the same ID; a genuinely new operation receives a new ID.

The current provider tool-call ID and call index may remain as diagnostic correlation fields, but they are not authoritative identity. Current APIs do not provide a stable turn ID or call index.

Define actionFingerprint as a versioned SHA-256 digest over RFC 8785 canonical JSON containing workspace ID, session ID, tool name, and canonical parameters. Normalize parameters to the JSON data model before fingerprinting or target resolution: objects with string keys, arrays, strings, booleans, finite JSON numbers, and null only; any other value is invalid parameters and fails closed. Do not coerce integral values to floating point; preserve the parsed numeric representation. Feed the Jackson JSON tree for this normalized map to the canonicalizer; ad-hoc map serialization is not acceptable. Pin io.github.erdtman:java-json-canonicalization:1.1 (package org.erdtman.jcs) in repository dependency management and use it only from core. Verify its license at dependency intake; add its attribution and resolved artifact checksum to THIRD_PARTY_NOTICES.md; add RFC and Amsha-specific golden vectors. The fingerprint identifies an exact canonical request; it does not prove that differently written commands have equivalent effects. Never put raw parameters or the fingerprint in audit details or user-visible messages.

Represent an attempt by operation ID and a monotonically increasing attempt number. Persist uniqueness for workspace ID, operation ID, and attempt number. AT_MOST_ONCE also needs a durable replay lookup scoped by workspace ID, session ID, tool name, fingerprint version, and action fingerprint. This key remains stable across turns in the same session.

Resolve workspace identity from WorkspaceContext and the owning session/checkpoint. Do not trust a workspace ID supplied in tool parameters. Store the resolved workspace ID on attempts, locks, outbox events, and resolutions.

### 4.2 Trusted target identity

Add a code-owned ToolTargetResolver keyed by `ToolCapability.toolName()`; V1 does not add a target-metadata method to `AgentTool`. It receives the selected tool and normalized parameters only after the canonical tool parameter validator has accepted them. The model cannot choose a lock scope or provide a target fingerprint.

For tools in the resolver's target-level table, the resolver returns one unambiguous resource type and canonical resource identity. Derive targetFingerprint as target:sha256:v1: over RFC 8785 canonical JSON containing a domain tag, workspace ID, session ID, resource type, and canonical resource identity. Tools that address the same resource must produce the same fingerprint regardless of tool name. Persist the digest, not the raw resource identity. Tools absent from the table, or a missing, ambiguous, malformed, or failed resolution, select SESSION_MUTATIONS and a null targetFingerprint. It must never silently disable the lock.

### 4.3 Call ordering

Every tool path follows the same order:

1. Assign or restore operation ID, action fingerprint, and trusted target identity.
2. Run every mandatory ToolInvocationGuard check.
3. Check AT_MOST_ONCE claims and applicable durable locks before the existing history/cache deduplication check; atomically recheck/reserve the claim during admission. Do not bypass policy, capability, workspace, session, or approval checks.
4. If HITL is required, persist invocation identity in the checkpoint and wait for the existing approval path. Create an attempt only after approval.
5. Atomically admit the attempt and its ADMITTED audit-outbox event.
6. Deliver and durably acknowledge the admission audit event through the mandatory HMAC audit path. Do not advance to launch without this acknowledgement.
7. Atomically transition to LAUNCH_REQUESTED and enqueue its audit event.
8. Deliver and durably acknowledge the LAUNCH_REQUESTED audit event. Only then invoke the selected executor with the same operation and attempt identity.
9. Record RUNNING after process/container start is confirmed. For in-process tools, commit RUNNING immediately before the JVM method call because there is no separate start confirmation.
10. Sanitize the bounded observation, atomically settle the attempt with any required action lock and audit-outbox event, then return the journaled result.

A pre-launch rejection may settle as CANCELLED_BEFORE_LAUNCH only when the journal and executor evidence prove the executor was not invoked or accepted the operation. An interrupt or session halt after delegation is INDETERMINATE unless durable executor evidence proves settlement. ToolInvoker's 499 response is never used as the sole classifier.

## 5. Persistence and audit contracts

### 5.1 Persistence API

Define public persistence contracts in the existing root package com.amsha.persistence.api: ToolAttemptStorePort, ToolAttemptDto, ToolAttemptStatus, DurableActionLockDto, ToolAttemptAuditOutboxDto, ToolAttemptResolutionDto, and AuditChainFinalizer. AuditChainFinalizer is a persistence-api functional interface used by the chained audit append seam.

Do not place these types in tool-api or make persistence-api depend on tool-api, core, or SecurityEventType. Persist idempotency mode as its stable enum name; core maps AgentTool.idempotencyMode() into the persistence-owned value. Unknown or invalid stored values fail closed as NON_IDEMPOTENT.

ToolAttemptStorePort provides insert-only admission, fenced compare-and-set transitions, workspace-scoped reads, applicable-lock queries, resolution operations, leased outbox delivery, and bounded retention pruning. Runtime lookups require WorkspaceContext. Startup may enumerate workspace IDs through a privileged metadata-only index, unioned with IdentityStorePort.listWorkspaces(); it must re-enter each workspace scope before reading or mutating attempt rows. Do not expose an unscoped runtime query for attempt contents.

Admission is idempotent only when an existing operation/attempt has the same immutable identity and request fingerprint. Conflicting reuse of an operation or attempt ID returns CONFLICT. Every transition checks workspace ID, attempt ID, expected status, and fencing token; successful transitions increment the token. Terminal states are immutable, and a stale executor or reconciler cannot overwrite a later transition.

### 5.2 Backend and migration requirements

SQLite and PostgreSQL use semantically equivalent DDL in their paired V1__execution.sql clean-install baselines under the reset-only rollout in §12.1. Do not add a V13 data backfill. The schema includes attempts, action claims, action locks, resolutions, audit outbox rows, the schema-contract marker, and reasoning-step attempt links.

- SQLite uses the existing shared WAL configuration. Do not change synchronous mode or set PRAGMAs inside a transaction.
- JDBI adapters inject JdbiProducer, register constructor mappers for query records, and use @BindMethods for record writes.
- Select adapters through the existing PersistenceBackend constants and @IfBuildProperty pattern.
- Filesystem persistence follows the cross-record write order and crash-repair rules in §12.4. It fails closed if the filesystem cannot provide the required lock or atomic rename semantics.
- Every adapter implements the same legal transitions, duplicate admission behavior, fencing, lock creation, workspace isolation, and recovery contract.

### 5.3 Audit outbox

The tool-attempt outbox is new work; no such outbox exists today. Persist each journal transition and its pending audit event in one database transaction or one recoverable filesystem operation. Event kinds are persistence-owned stable values; core maps each kind to a SecurityEventType constant. Persistence DTOs never import SecurityEventType.

Outbox payloads contain only workspace/session identifiers, attempt or resolution IDs, event kind, timestamps, status, and approved non-secret metadata. They never contain raw parameters, command text, provider payloads, tool output, credentials, or authorization headers.

A core relay delivers events using the existing mandatory audit service and a stable idempotency key. It acknowledges an outbox row only after the HMAC audit record is durably committed. Delivery uses fenced leases, bounded retries, and compare-and-set acknowledgement so concurrent relays cannot acknowledge one another's work. Settlement audit delivery may retry asynchronously; it never changes attempt state or causes tool replay. Admission, launch-request, and resolution-authorization events have the synchronous acknowledgement gates defined in §§4.3 and 12.5.

## 6. Checkpoint and resume reconciliation

The journal is authoritative for whether a tool attempt launched and how it settled. The checkpoint is authoritative for the paused workflow, pending operation identity, approval state, and resumed history. Reconciliation merges these authorities; neither overwrites the other.

Extend the existing resume paths rather than assuming a new AgentLoopReconciler exists:

| Resume path | Required integration |
| --- | --- |
| Sequential checkpoint | Reconcile the pending operation through CheckpointResumeHandler and CheckpointResumeCoordinator before invoking a tool. |
| Pending tool continuation or recovery | Reconcile through PendingToolResumeExecutor and its existing caller before retry or result delivery. |
| Parallel tool batch | Carry one operation ID per call and reconcile each through ParallelBatchResumeHandler and ParallelToolStepExecutor. One unresolved call must not be reissued because another call in the batch completed. |
| Cron approval/resume | Use the same journal and governance checks as interactive resume; preserve cron session routing rules. |
| Browser and MCP paths | Pass through the same admission/settlement wrapper and preserve identity through their executor boundaries. |

For each checkpointed operation:

- If the attempt is settled, append its stored sanitized observation or error to resumed history once, keyed by attempt ID. Do not call the executor again.
- If the attempt is INDETERMINATE, add an explicit indeterminate tool result to the resumed checkpoint, keep the durable lock active, and let the agent inspect state without replaying the operation.
- If the attempt is ADMITTED, launch has not occurred by invariant. Resume only after the ordinary governance checks; if the checkpoint is abandoned, settle it as CANCELLED_BEFORE_LAUNCH.
- If there is no attempt record, no launch could have occurred because admission is durably committed before launch intent. Resume through normal governance and create the admission record before executing.

Make append-and-consume idempotent by attempt ID. If the history append and checkpoint update cannot share a transaction, the history side must reject duplicate attempt IDs and the checkpoint transition must be fenced so a crash between the two steps cannot duplicate the observation.

The startup observer uses the authoritative workspace catalog to enumerate workspaces and runs scoped reconciliation under WorkspaceContext. Annotate it with @Startup if it must eagerly initialize a registry or control-plane registration. Startup reconciliation must cover active attempts from sequential, parallel, and cron workflows.

## 7. Process and container supervision

### 7.1 Podman

Use the concrete PodmanContainerExecutor. Persist the exact generated container name and workspace/attempt labels as launch metadata before invoking Podman. Reconciliation inspects only the exact container referenced by the attempt and verifies its attempt/workspace labels; a broad podman ps listing is not evidence of ownership.

Retain a container until the journal has durably settled the attempt and output summary. On confirmed timeout, terminate and collect bounded output before settlement. If the container may have started but is missing or its state cannot be proven, settle INDETERMINATE and create the action lock atomically.

Preserve the current zc- container naming namespace for the first delivery to avoid an unplanned rename. Add attempt-scoped labels and exact-name persistence. Do not automatically delete unlabeled legacy zc- containers: they lack sufficient durable ownership metadata. Provide an operator-visible cleanup procedure and test that startup does not remove unrelated legacy containers.

### 7.2 NAMESPACE, DIRECT, and NONE

ProcessIsolationExecutor selects PODMAN, NAMESPACE, DIRECT, or NONE. The journal integration must cover all strategies. For non-Podman process execution, persist the strongest available process handle and start identity. After an Amsha restart, terminate only a still-running process whose ownership can be verified. The process's absence does not prove its outcome; unresolved attempts settle as INDETERMINATE and remain locked. Never kill a process based only on a reused PID.

### 7.3 Built-in and in-process tools

For built-in tools executed inside the JVM, persist RUNNING immediately before the invocation. If the JVM stops before a terminal journal transition, classify the attempt as INDETERMINATE. Do not infer completion from a missing thread or session.

## 8. Durable action locks and resolution

Create the required durable action lock in the same transaction as INDETERMINATE settlement. Also create a retry-approval lock for NON_IDEMPOTENT SETTLED_ERROR and TIMED_OUT outcomes. The separate AT_MOST_ONCE action claim is a replay fence, not an approval lock.

A lock records workspace, session, origin operation and attempt IDs, action fingerprint, scope, target fingerprint when applicable, reason, creation time, and resolution state. Scope comes only from trusted tool metadata and ToolTargetResolver:

- EXACT_REQUEST blocks the same action fingerprint.
- TARGET_RESOURCE blocks operations for the same trusted target fingerprint until a verifier confirms the declared postcondition.
- SESSION_MUTATIONS blocks session mutations when a safe target identity cannot be derived.

ToolInvocationGuard checks applicable durable locks before its existing history/cache deduplication. A lock cannot bypass any mandatory guard check. Existing deduplication remains a cache/history optimization and is not a durable replay fence.

### 8.1 Resolution and one-shot retry approval

Only the core ToolAttemptResolutionService may resolve a lock. It uses the existing authenticated approval or verification path; persistence methods are not exposed to tools or models. Each resolution binds the workspace, lock, origin attempt, action fingerprint, actor, resolution kind, evidence, and approval result. `resolutionKind` is exactly one of `ONE_SHOT_RETRY`, `VERIFIED_POSTCONDITION`, or `OPERATOR_ACKNOWLEDGEMENT`; unknown or case-variant values fail closed. A client or model cannot choose resolution IDs.

A read-only verifier may resolve a TARGET_RESOURCE lock only when its durable result is bound to the exact origin attempt and confirms the postcondition declared by trusted tool metadata. A statement such as “file exists” is sufficient only when existence was the declared postcondition.

For INDETERMINATE SESSION_MUTATIONS, an operator acknowledgement records that the outcome remains unknown and authorizes a governed follow-up; it does not claim the original operation did not occur. For NON_IDEMPOTENT SETTLED_ERROR or TIMED_OUT, a fresh approval grants exactly one retry for the same action fingerprint. The store consumes that authorization atomically with the new attempt admission via `applyResolutionAndAdmitRetry`. `applyResolution` explicitly rejects retry authorizations; only non-retry resolutions (such as read-only postcondition verifications or operator acknowledgements of uncertainty) may use `applyResolution` to clear a lock without attempt admission. An AT_MOST_ONCE replay fence cannot be cleared by approval.

Resolution follows audit-before-mutation ordering: persist a pending resolution and its ACTION_RESOLUTION_AUTHORIZED outbox event; synchronously deliver and acknowledge that event; then clear the lock or consume the retry authorization in a fenced transaction that also enqueues ACTION_RESOLVED. For a retry, that transaction admits the new attempt and its ADMITTED event in the same atomic operation via `applyResolutionAndAdmitRetry`. The resolution's authorizationOutboxEventId points to the durable outbox row, never to the later-created HMAC audit row UUID.

Use the existing internal authorization rules for administrative endpoints, resolveUserId(), and existing approval-resolution paths. Telegram callback paths must call flushPendingNotifications() before returning. Wrong-workspace, wrong-lock, wrong-origin, fingerprint-mismatch, stale, partial, or already-consumed evidence fails closed without mutation.

## 9. Output bounds, reflection, and documentation

This feature adds no output-size configuration key and preserves the existing tool-specific character limits. Persist the observation produced by the existing ToolObservationSanitizer and transformer chain; do not introduce a new byte limit or reinterpret the 16384-code-point cap as bytes. The existing truncation marker remains appended after the cap.

The final persisted observation is bounded after the entire transformer chain, not by each transformer in isolation. TruncationTransformer runs at priority 10 and appends its marker; later transformers may replace text, and PiiRedactionTransformer currently expands short values into `[REDACTED-<TYPE>]` tokens. The store therefore re-applies the 16384-code-point cap, with the existing marker, after the chain and before persistence. Extension transformers registered through the ObservationTransformer SPI are subject to the same post-chain bound. Add a contract test for transformer order, PII expansion, marker behavior, multibyte output, and final persisted observation size; a future transformer that needs a larger bound must revise this rule before it is added.

Register persistence DTOs/statuses in the owning module's existing reflection configuration, including TieredMemoryReflectionConfig where applicable. Register new tool invocation context types in ToolApiReflectionConfig. Register adapter rows, JDBI DAOs, DTOs, and CDI beans in their module reflection configs; do not add type-level @RegisterForReflection annotations.

Keep paired SQLite/PostgreSQL migrations equivalent. Use JdbiProducer, constructor mappers, and @BindMethods. Select adapters through PersistenceBackend constants. Update docs/README.md for both the active proposal and the proposed reset-only rollout ADR. Update THIRD_PARTY_NOTICES.md for the pinned RFC 8785 library before implementation is delivered.

## 10. Delivery sequence gates

Do not add calendar dates until owners validate the identity-threading, audit-relay, and backend work estimates.

### Phase A — identity and persistence contract

1. Define operation identity, action-fingerprint version, and trusted target resolver.
2. Thread identity through sequential, parallel, approval-resumed, cron, browser, MCP, and sandbox paths.
3. Add persistence API DTOs, status values, store operations, schema-contract guard, SQL baseline DDL, filesystem journal contract, retention job, and all three adapters.
4. Add RFC 8785 and Amsha-specific golden vectors.

Exit gate: duplicate admission, target-lock scope, stale fencing, reset detection, and workspace isolation have equivalent behavior across all backends.

### Phase B — governed admission, audit, and execution

1. Check durable locks before existing deduplication while preserving every mandatory guard check.
2. Atomically admit an attempt and outbox event; synchronously acknowledge ADMITTED before launch.
3. Commit LAUNCH_REQUESTED and synchronously acknowledge its audit event before invoking any executor.
4. Add the common attempt wrapper to built-in/host, PODMAN, NAMESPACE, DIRECT, NONE, browser, and MCP paths.
5. Record RUNNING at the correct executor boundary; settle success, error, timeout, and pre-launch cancellation with required locks/outbox entries.

Exit gate: no executor runs without durable attempt state and both mandatory pre-launch audit acknowledgements.

### Phase C — checkpoint and startup recovery

1. Extend sequential and approval-resumed checkpoint paths.
2. Extend pending-tool and parallel-batch resumes with per-call identity and partial-result reconciliation.
3. Cover cron resume under the same workspace and governance rules.
4. Add workspace-enumerated startup reconciliation and verified process/container ownership checks.
5. Make result append idempotent by attempt ID and checkpoint version.

Exit gate: restart yields a confirmed result, safe pre-launch continuation, or locked INDETERMINATE result; uncertain work is never automatically replayed.

### Phase D — lock resolution and release evidence

1. Add verified-postcondition and one-shot approval resolution persistence.
2. Enforce audit-before-mutation and Telegram callback notification flushing.
3. Exercise crash points across admission, launch, execution, settlement, audit delivery, and checkpoint append.
4. Verify reflection registration, migration parity, reset-marker failures, workspace isolation, malformed/legacy data, retention pruning, and output bounds.
5. Run implementation-time tests, module PMD gates, native-sensitive checks, Markdown verification, and the review wrapper required by AGENTS.md.

## 11. Acceptance criteria

1. SQLite, PostgreSQL, and filesystem adapters enforce the same transitions, duplicate-admission behavior, fencing, workspace isolation, and atomic attempt/lock/outbox semantics.
2. Confirmed pre-launch cancellation may proceed after ordinary governance and releases its RESERVED AT_MOST_ONCE claim. A post-launch halt or lost custody becomes INDETERMINATE unless durable executor evidence proves settlement.
3. AT_MOST_ONCE rejects the same action fingerprint across turns. NON_IDEMPOTENT SETTLED_ERROR/TIMED_OUT requires one durable approval for one new attempt. INDETERMINATE never auto-replays.
4. Durable lock checks run before history/cache deduplication. Existing deduplication cannot suppress or replace a durable lock decision.
5. Sequential, parallel, pending-tool, approval, cron, browser, MCP, host, built-in, and every configured sandbox route carry invocation identity.
6. A settled observation is appended to resumed history exactly once by attempt ID. An indeterminate result is injected into the checkpoint without executing the tool again.
7. Startup enumerates workspace IDs and performs each attempt lookup/mutation in workspace scope. Unknown legacy ownership fails closed.
8. Admission, launch-request, and resolution-authorization audit events are acknowledged before their dependent action. Outbox retry is idempotent and preserves HMAC chain ownership. Audit payloads contain no raw parameters, output, credentials, or provider payloads.
9. Podman reconciliation inspects only the exact labeled container. Non-Podman cleanup verifies process ownership. No unrelated or unlabeled legacy container is removed.
10. Existing character/code-point limits and marker behavior remain intact; the final observation bound is enforced after the transformer chain, including PII and extension transformers.
11. Paired SQL baselines, JDBI bindings/mappers, backend selection, filesystem crash recovery, and native reflection follow repository persistence rules.
12. Documentation verification and diff checks pass. Future implementation must also pass the affected-module tests, PMD, and native checks required by AGENTS.md.

## 12. Implementation-level requirements

This section is normative for implementation. It closes the API, storage, state-transition, resume, audit, and rollout decisions that were previously only described at a high level.

### 12.1 Reset-only schema rollout and startup gate

This release has no data-preserving database migration, historical attempt backfill, or automatic conversion of old checkpoints. Existing pending calls have no durable operation identity and must not be replayed automatically.

The rollout is destructive. Deleting the configured SQLite and filesystem data directories deletes all Amsha data stored there, including sessions, checkpoints, memories, audit records, and vault metadata. Deleting local directories does not reset an external PostgreSQL database; PostgreSQL must be dropped and recreated or otherwise made empty before rollout. Do not start the new application against retained data.

Add the journal DDL and a singleton schema-contract marker to both paired V1__execution.sql clean-install baselines. The marker records contract name tool-attempt-settlement and contract version 1. Do not add V13 or backfill data. This deliberately changes the V1 checksum. A deployment with existing Flyway history must be reset; checksum validation failure is expected and must not be repaired automatically.

The default, test, and PostgreSQL profile configurations currently set baseline-on-migrate to true. A non-empty database with no Flyway history can therefore be baselined without running V1. Add an @Startup ToolAttemptSchemaGuard with a StartupEvent observer that runs after Flyway migration and before readiness or tool dispatch. It must verify the marker and all required journal tables. Missing marker/table, unsupported contract version, inaccessible schema, or failed check aborts startup; it must never create or repair the missing schema. On the supported fresh-install path the guard sees the marker and passes; it is a fail-fast reset check, not a data migration. Prove the ordering in a startup integration test. If StartupEvent ordering cannot guarantee post-migration execution, use an explicit post-migration startup hook and keep readiness closed until the guard passes.

Filesystem persistence uses an equivalent versioned schema-contract manifest. An empty data root may be initialized atomically with the current marker. A non-empty root with no marker, malformed marker, or unsupported version fails closed.

Record the one-time V1 checksum/reset exception in the [reset-only rollout ADR](../adr/architecture/ADR-Tool-Attempt-Settlement-Reset-Only-V1-Baseline.md). The project author approved this scoped exception on 2026-11-01; implementation may edit the paired V1 baselines without a further migration or backfill. Architecture Invariants §5.2 names the scoped exception, and the ADR does not change the general migration policy.

### 12.2 Invocation identity and target fingerprint

Use canonical lowercase UUID strings for operationId, attemptId, resolutionId, and outbox event IDs. The orchestration layer creates operationId for a new logical call. The store allocates attemptId and a monotonically increasing attemptNumber atomically for each admitted attempt. Resumes reuse the operation ID; retries create a new attempt ID.

Define actionFingerprint as sha256:v1: followed by lowercase SHA-256 hex over RFC 8785 canonical JSON containing workspaceId, sessionId, toolName, and normalized parameters. Use the §4.1 JSON-data-model normalization, reject non-JSON parameter values as invalid, and feed the Jackson JSON tree to the canonicalizer. Pin io.github.erdtman:java-json-canonicalization:1.1 (package org.erdtman.jcs) in repository dependency management, use it from core, verify its license, record attribution and resolved checksum in THIRD_PARTY_NOTICES.md, and add RFC plus Amsha-specific golden vectors. Ordinary map serialization or key sorting is not an acceptable substitute.

The trusted ToolTargetResolver runs only after the existing canonical parameter validation. It is a code-owned table keyed by `ToolCapability.toolName()`; V1 does not add a target-metadata method to `AgentTool`. For a uniquely resolved target, compute targetFingerprint as target:sha256:v1: over the workspace, session, resource type, and canonical resource identity. It is independent of tool name so different tools protecting the same resource can share the lock. Persist only this digest. Tools absent from the table, or a missing, ambiguous, malformed, or failed resolution, select SESSION_MUTATIONS with a null targetFingerprint; it never selects an unscoped/no-lock result.

Add an identity-bearing ToolOperationIdentity and thread it through ToolStepHandler, ParallelToolStepExecutor, ToolInvoker, ToolExecutionContext, PendingToolResumeExecutor, BrowserPreflightHandler, MCP adapters, checkpoint/cron serialization, and sandbox dispatch. Any legacy ToolInvoker overload that lacks identity must be non-executable in production. Provider tool-call IDs and call indexes remain diagnostic only.

Persist operationId and actionFingerprint with each sequential pending call and each ParallelToolCall. Persist target fingerprint only where needed for lock matching. Do not add operationId or attempt IDs to ReasoningStepDto: operation identity belongs to the pending operation and tool_attempt row; result-to-attempt correlation belongs in reasoning_step_attempt.

### 12.3 State-transition contract

Only the following transitions are legal:

| From | To | Required evidence |
| --- | --- | --- |
| ADMITTED | LAUNCH_REQUESTED | Caller owns the current fencing token; admission audit is acknowledged and the LAUNCH_REQUESTED outbox event is committed. Executor invocation still waits for its own audit acknowledgement. |
| ADMITTED | CANCELLED_BEFORE_LAUNCH | Checkpoint is abandoned or governance cancels the operation before launch intent. |
| LAUNCH_REQUESTED | RUNNING | Process/container start is confirmed, or an in-process call is immediately about to enter the JVM method. |
| LAUNCH_REQUESTED | CANCELLED_BEFORE_LAUNCH | Durable executor rejection evidence proves it did not accept or invoke the operation. |
| LAUNCH_REQUESTED | SETTLED_SUCCESS | Executor evidence confirms successful completion before RUNNING was committed. |
| LAUNCH_REQUESTED | SETTLED_ERROR | Executor evidence confirms completed failure before RUNNING was committed. |
| LAUNCH_REQUESTED | TIMED_OUT | Executor evidence confirms deadline expiry and termination before RUNNING was committed. |
| LAUNCH_REQUESTED | INDETERMINATE | Launch may have occurred and no definitive result or non-launch evidence exists. |
| RUNNING | SETTLED_SUCCESS | Tool returned confirmed success and the sanitized observation is available. |
| RUNNING | SETTLED_ERROR | Tool returned a confirmed error result. |
| RUNNING | TIMED_OUT | Deadline expired and the supervisor confirms execution stopped. |
| RUNNING | INDETERMINATE | Process/container outcome or ownership cannot be confirmed. |

RUNNING is an observable checkpoint, not a required intermediate state; fast execution may settle directly from LAUNCH_REQUESTED when evidence is definitive. Every transition predicates on workspaceId, attemptId, expected status, and fencing token. A successful transition increments the token. A zero-row compare-and-set returns STALE and cannot call or settle an executor. Terminal states are immutable. Every terminal transition atomically writes its required action lock and outbox event; if any required row cannot be written, the transition does not commit.

A stop request without confirmed termination is INDETERMINATE, not TIMED_OUT. An exception during process creation is CANCELLED_BEFORE_LAUNCH only when the executor proves no child/container was accepted. ToolInvoker's 499 response is classified from journal state and executor evidence, never from the status code alone.

### 12.4 Persistence API, schema, and filesystem crash rules

Keep public persistence ports, DTOs, and persistence-owned enums in com.amsha.persistence.api. Do not import tool-api, core, or SecurityEventType into persistence-api.

| Type | Required fields |
| --- | --- |
| ToolAttemptDto | workspaceId, sessionId, operationId, attemptId, attemptNumber, toolName, actionFingerprint, fingerprintVersion, targetFingerprint, idempotencyModeSnapshot, status, fencingToken, executionKind, executionHandle, processStartIdentity, exitCode, sanitizedObservation, createdAt, launchRequestedAt, runningAt, settledAt |
| DurableActionLockDto | lockId, workspaceId, sessionId, originOperationId, originAttemptId, actionFingerprint, targetFingerprint, scope, reason, status, createdAt, clearedAt, resolutionId |
| ToolAttemptAuditOutboxDto | eventId, idempotencyKey, workspaceId, sessionId, attemptId (null for resolution events), resolutionId (null for attempt events), eventKind, safeMetadataJson, createdAt, deliveryAttempts, nextAttemptAt, leaseId, leaseUntil, deliveredAt, deliveredAuditEventId |
| ToolAttemptResolutionDto | resolutionId, lockId, workspaceId, actorId, resolutionKind, resolutionStatus, originAttemptId, actionFingerprint, targetFingerprint, evidenceReferenceOrDigest, authorizationOutboxEventId, resolvedOutboxEventId, createdAt, consumedAt |
| CheckpointDto | Existing `id` component (JDBI aliases `checkpoint_id AS "id"`), existing checkpoint fields, and `version`; version maps to agent_checkpoint.version in SQL and an equivalent monotonic counter in filesystem persistence |
| ReasoningStepDto | Existing reasoning-step fields only. It does not carry operationId or attempt IDs. |

Adding the `version` component is a source-breaking record change: update every CheckpointDto constructor call site (83 today), CheckpointJdbiDao.ALL_COLUMNS, the PostgreSQL and filesystem mappers, and affected tests in the same change.

ToolAttemptResolutionDto.resolutionStatus is PENDING, APPLIED, or EXPIRED; only PENDING may transition.

Persist idempotency mode as IDEMPOTENT, AT_MOST_ONCE, or NON_IDEMPOTENT. Unknown values fail closed as NON_IDEMPOTENT. The persistence-owned ToolAttemptAuditEventKind values are ATTEMPT_ADMITTED, LAUNCH_REQUESTED, ATTEMPT_SETTLED, ACTION_LOCKED, ACTION_RESOLUTION_AUTHORIZED, and ACTION_RESOLVED. ToolAttemptStorePort must provide:

- `admit(request)`: atomically allocate attemptId/attemptNumber and create the attempt, any AT_MOST_ONCE claim, and the ADMITTED outbox event. The store creates that event after allocating attemptId and returns it with CREATED; IDENTICAL_EXISTING also returns the existing event while the attempt remains ADMITTED. Return CREATED, IDENTICAL_EXISTING, AT_MOST_ONCE_EXISTING, or CONFLICT.
- `applyResolutionAndAdmitRetry(workspaceId, resolutionId, lockId, consumedAt, resolvedOutboxEvent, retryRequest)`: atomically consume an acknowledged retry resolution, clear/resolve the lock, enqueue ACTION_RESOLVED, admit the retry attempt, and enqueue its ADMITTED outbox event in a single transaction. Return the ToolAttemptAdmissionResult.
- `applyResolution(workspaceId, resolutionId, lockId, consumedAt, resolvedOutboxEvent)`: atomically apply an acknowledged resolution that clears a lock without retry admission (e.g. read-only postcondition verifier or operator acknowledgement). The store must reject one-shot retry approval resolutions; retry approvals must be consumed exclusively via `applyResolutionAndAdmitRetry` so approval cannot be consumed without admitting the retry.
- `deleteClaimsBySession(workspaceId, sessionId)`: permanently delete all AT_MOST_ONCE claims associated with a session upon true permanent session deletion (reserved for explicit future purge/eradication; never called on session reset).
- transition(workspaceId, attemptId, expectedStatus, expectedFencingToken, targetStatus, settlementFields, optionalLock, outboxEvents): return APPLIED, STALE, or CONFLICT.
- Workspace-scoped attempt, action-lock, and AT_MOST_ONCE-result queries. findApplicableLocks accepts the candidate targetFingerprint. Startup workspace enumeration returns IDs only.
- prepareResolution(resolution, authorizationOutboxEvent), followed by a fenced clear/consume operation only after the authorization event has been acknowledged.
- Leased outbox claim, acknowledgement, and reschedule operations. The acknowledgement records deliveredAuditEventId separately from the outbox eventId.
- pruneTerminalBefore(cutoff): delete retention-eligible terminal attempts, locks, resolutions, and delivered outbox rows in dependency order; never delete an attempt referenced by a claim, an active lock, or an unresolved resolution.

Claim lifecycle is part of transition and commits in the same transaction as the attempt row. A matching RESERVED claim becomes CONSUMED only on SETTLED_SUCCESS, SETTLED_ERROR, or TIMED_OUT. It remains RESERVED for INDETERMINATE, which keeps the AT_MOST_ONCE fence closed for the life of the attempt. CANCELLED_BEFORE_LAUNCH deletes the claim. A duplicate admission that finds CONSUMED returns AT_MOST_ONCE_EXISTING and may replay the stored result only when the referenced attempt is terminal; CONSUMED with a non-terminal attempt is blocked. Any other claim state is CONFLICT. The filesystem ordering in this section is an implementation detail of the same state machine, not a different lifecycle.

`ToolAttemptAdmissionRequest.retryOfAttemptId` is null for an initial admission and names the preceding attempt for an intentional retry. The store accepts a retry only when that attempt belongs to the same workspace, session, operation, tool, and action fingerprint, is the latest attempt, and is eligible for retry per §3:
- Prior `SETTLED_SUCCESS` is never eligible for retry under any idempotency mode; success returns the stored observation and must never re-execute.
- For `AT_MOST_ONCE`, retry is permitted only when the prior attempt is `CANCELLED_BEFORE_LAUNCH` (where no launch intent was committed and the claim was deleted); `SETTLED_ERROR`, `TIMED_OUT`, and `INDETERMINATE` reject retry with `CONFLICT`, and approval cannot override this fence.
- For `IDEMPOTENT`, prior attempts in `SETTLED_ERROR`, `TIMED_OUT`, or `CANCELLED_BEFORE_LAUNCH` are eligible for automatic/loop retry; `INDETERMINATE` is explicitly not safe to auto-replay and requires an authorized resolution.
- For `NON_IDEMPOTENT`, `CANCELLED_BEFORE_LAUNCH` permits admission under normal governance; `SETTLED_ERROR` and `TIMED_OUT` require one-shot retry approval; `INDETERMINATE` requires the separate evidence path (verified postcondition evidence or operator acknowledgement). Retries requiring resolution consumption are admitted via `applyResolutionAndAdmitRetry`.

The store allocates the next attempt number in the same transaction. Repeating a retry request after a lost response returns the already-created next attempt as IDENTICAL_EXISTING; it must not allocate a further attempt. The store must never infer a retry from operationId alone. IDENTICAL_EXISTING returns the admission outbox row while the attempt remains ADMITTED, including its persisted delivery state, so recovery can establish whether ATTEMPT_ADMITTED was acknowledged before proceeding. Preserve that row until the attempt leaves ADMITTED.

Use these SQL invariants in both V1 baselines:

- tool_attempt has primary key (workspace_id, attempt_id) and unique (workspace_id, operation_id, attempt_number). Store target_fingerprint and index workspace/session/status and workspace/operation.
- tool_action_claim has unique key (workspace_id, session_id, tool_name, fingerprint_version, action_fingerprint), with RESERVED or CONSUMED state and the owning operation/attempt identity. Retain CONSUMED claims as replay tombstones for the lifetime of the session; the retention job does not remove claims, and they are deleted only when the session itself is permanently deleted via `deleteClaimsBySession(workspaceId, sessionId)`. Claims survive session resets (`/reset`, `/resethard`, and `WorkspaceManager.resetSessionState`) to preserve the replay fence across the entire lifetime of the session.
- tool_action_lock stores origin attempt, scope, reason, target_fingerprint, resolution binding, and status. TARGET_RESOURCE requires a non-null target fingerprint; SESSION_MUTATIONS does not.
- tool_attempt_outbox has unique event_id and unique idempotency_key. Attempt events use workspaceId + attemptId + eventKind as their stable key; resolution events use workspaceId + resolutionId + eventKind. Leases are fenced and expired leases may be reclaimed.
- tool_attempt_resolution stores the authorization and result outbox IDs, actor, origin binding, verifier result, sanitized evidence reference/digest, and one-shot consumption state.
- reasoning_step_attempt stores checkpoint_id, session_id, workspace_id, reasoning_step_id, and attempt_id. Its primary key is (checkpoint_id, attempt_id); a composite foreign key proves the reasoning step belongs to the checkpoint, and the adapter validates in the same transaction that the checkpoint and attempt share the current workspace/session.

Retention is bounded: active attempts are never removed; terminal attempts, locks, resolutions, and delivered outbox rows older than `amsha.tools.attempt.retention` (default 30 days) are eligible for pruneTerminalBefore. AT_MOST_ONCE claims remain until the session itself is permanently deleted, and pruning never deletes an attempt still referenced by a claim, an active lock, or an unresolved resolution. Session resets (`/reset`, `/resethard`, and `WorkspaceManager.resetSessionState`) preserve session records for WORM compliance and must never delete claims; clearing claims on reset would permit re-execution of non-repeatable actions in the same session. `deleteClaimsBySession(workspaceId, sessionId)` is reserved exclusively for a true permanent session deletion / eradication lifecycle (such as GDPR purge or future hard session decommissioning). Because no automatic permanent session deletion path currently exists in the session lifecycle, claims remain indefinitely alongside the session record. When permanent session deletion is invoked, each backend enforces claim deletion within its storage engine: SQLite and PostgreSQL execute `DELETE FROM tool_action_claim WHERE workspace_id = :workspaceId AND session_id = :sessionId`; filesystem persistence loads the workspace claims envelope under the workspace file lock, filters out claims matching the session ID, and atomically replaces the envelope with fsync. Once claims are deleted, terminal attempts previously protected by those claims become eligible for `pruneTerminalBefore(workspaceId, cutoff)` pruning. The retention property must be added to application.yaml, .env.example, and the owning module README.

Admission and transitions use backend transactions in SQLite/PostgreSQL. Filesystem persistence uses a workspace lock file and atomic, same-directory replacement with file contents forced before rename and the directory forced where supported. Attempt state, its locks/resolutions, and its outbox entries live in a versioned attempt envelope. AT_MOST_ONCE claims live in a workspace-scoped claims envelope; they cannot be stored only in a per-attempt file.

For an AT_MOST_ONCE admission, filesystem writes the RESERVED claim first and then the ADMITTED attempt envelope while holding the workspace lock. The executor cannot run until both are durable. A crash leaving a claim without an attempt blocks admission until recovery, under that lock, proves the referenced attempt is absent and safely releases the claim; malformed or conflicting state remains blocked. A non-terminal AT_MOST_ONCE attempt must never exist without its matching claim; a terminal CANCELLED_BEFORE_LAUNCH attempt has no claim by design.

For launch, keep the claim RESERVED while replacing the attempt envelope with LAUNCH_REQUESTED. Invoke the executor only after both writes and the LAUNCH_REQUESTED audit acknowledgement. On SETTLED_SUCCESS, SETTLED_ERROR, or TIMED_OUT, replace the terminal attempt envelope first and then mark the claim CONSUMED; recovery completes a missing claim update from the terminal attempt. For CANCELLED_BEFORE_LAUNCH, persist the terminal attempt first and then delete its claim; recovery removes a leftover claim only after confirming the terminal cancellation. A LAUNCH_REQUESTED or RUNNING attempt without its RESERVED claim is an invariant violation and fails closed. All paired-file repair occurs under the workspace lock; no other request may observe an intermediate state.

Filesystem checkpoint result append uses one versioned checkpoint envelope containing the updated checkpoint version, reasoning step, and attempt links. Replace it atomically so result history and links cannot diverge.

### 12.5 Audit ownership and acknowledgement

Define AuditChainFinalizer in persistence-api and add AuditEventStorePort.appendChained(seed, finalizer). The store owns per-session serialization, sequence allocation, previous-hash lookup, idempotency lookup, and atomic insert. Under that serialization boundary it passes the seed, assigned sequence, and previous hash to the core finalizer; the finalizer returns the HMAC-populated AuditEventDto.

AuditEventRecorder/AuditHashUtil in core remain the sole HMAC owners. The persistence layer never receives the HMAC key. Resolve and validate the key before entering the append transaction; the finalizer performs only deterministic local HMAC work and no network, vault lookup, or other I/O. Missing key or finalizer failure aborts the append. SQLite must acquire its configured writer transaction before reading the chain head; PostgreSQL uses a per-session row/advisory lock; filesystem persistence uses an OS file lock. SessionAuditSequencer's JVM lock may remain an optimization but is not the cross-process authority.

The core relay maps the persistence-owned event kind to SecurityEventType and calls a mandatory audit method with the stable idempotency key. Add the corresponding SecurityEventType constants (TOOL_ATTEMPT_ADMITTED, TOOL_LAUNCH_REQUESTED, TOOL_ATTEMPT_SETTLED, TOOL_ACTION_LOCKED, TOOL_ACTION_RESOLUTION_AUTHORIZED, TOOL_ACTION_RESOLVED) and classify them as mandatory in requiresMandatoryAudit and its tests; persistence keeps its own stable string values. Add an idempotent ComplianceAuditService.recordMandatory overload for this key. Its existing submitCritical path waits for the completion future and returns only after the database action completes; that completed write is the acknowledgement. A queued item alone is not an acknowledgement.

Persist the outbox row in the same transaction as its journal transition. Synchronously deliver and acknowledge ATTEMPT_ADMITTED before LAUNCH_REQUESTED, and synchronously acknowledge LAUNCH_REQUESTED before executor invocation. Synchronously acknowledge ACTION_RESOLUTION_AUTHORIZED before lock clearance or retry admission. If any mandatory event fails, fail closed and do not perform its dependent action. If the process restarts with LAUNCH_REQUESTED and no definitive executor evidence, settle INDETERMINATE even if the audit delivery state is unclear.

ATTEMPT_SETTLED and ACTION_LOCKED/ACTION_RESOLVED events may retry asynchronously after their transaction commits. A relay failure never rolls back a tool result or causes tool replay. Repeated delivery of the same idempotency key returns the existing audit event rather than extending the HMAC chain a second time.

### 12.6 Checkpoint result persistence

CheckpointDto gains the existing agent_checkpoint.version value, mapped by SQLite and PostgreSQL adapters and by an equivalent filesystem counter. The version is the source for appendResultIfAbsent(checkpointId, expectedVersion, step, attemptIds); callers must not invent or infer it.

The append operation requires a non-empty, duplicate-free attempt ID list. In one backend transaction, insert the reasoning step, insert each reasoning_step_attempt link, and compare-and-set the checkpoint version. On filesystem persistence, perform the equivalent update in the single checkpoint-envelope replacement described in §12.4.

- If none of the supplied attempt IDs is linked, insert the step and all links, then increment the version and return APPENDED.
- If every supplied ID is already linked to the same step, return ALREADY_PRESENT without changing history or version.
- If IDs are split across steps, belong to another checkpoint, conflict with another operation, or refer to an unknown attempt, return CONFLICT and block resume for operator review.
- If expectedVersion is stale, return STALE; core reloads and retries a bounded number of times.

Sequential pending operation identity remains in checkpoint pending-call data. ParallelToolCall stores one operationId per call. The join table connects settled attempts to the resulting reasoning step without adding execution identity to ReasoningStepDto.

For a parallel batch, reconcile each child by operationId. Rebuild the final observation in original call order only after each child is terminal or explicitly blocked as INDETERMINATE. Link all child attempts to the single aggregate reasoning step. A crash before aggregation leaves the pending batch and journal results available for reconstruction without re-executing settled children.

### 12.7 Lock resolution authorization contract

ToolAttemptResolutionService creates a server-generated resolutionId after validating the authenticated actor and the existing approval or verifier decision. Clients and models cannot choose the ID. Persist a PENDING resolution and ACTION_RESOLUTION_AUTHORIZED outbox event with workspace, lock, origin attempt, action fingerprint, target fingerprint, verifier/approval identity, result, timestamp, and a digest/reference to sanitized evidence. Do not store raw shell output as evidence.

Deliver that authorization event through the mandatory HMAC path and wait for durable acknowledgement before changing the lock or consuming approval. The resolution's authorizationOutboxEventId refers to this acknowledged outbox row. The outbox's deliveredAuditEventId, when present, separately identifies the HMAC audit record. A PENDING resolution is applied on startup only when its ACTION_RESOLUTION_AUTHORIZED outbox row has a deliveredAuditEventId; otherwise the relay may still deliver it, and the fenced apply runs afterward. A PENDING resolution that remains unapplied at the retention cutoff is expired without clearing the lock. Recovery never re-prompts, never applies an unacknowledged authorization, and never clears an AT_MOST_ONCE fence.

After acknowledgement, a fenced store transaction validates the resolution against the same workspace, lock, origin attempt, fingerprint, and target; marks the resolution applied; clears the lock or consumes the one-shot retry approval; and appends ACTION_RESOLVED. Non-retry resolutions clear the lock via `applyResolution`; `applyResolution` explicitly rejects retry authorizations so an approval cannot be consumed without admitting the retry. For a retry, the store executes `applyResolutionAndAdmitRetry` to atomically consume the approval, resolve the lock, enqueue ACTION_RESOLVED, admit the new attempt, and enqueue its ADMITTED outbox event in the same transaction. Approval never overrides an AT_MOST_ONCE replay fence. The admission event must then be acknowledged before launch. A verified TARGET_RESOURCE result must be read-only and bound to the exact origin attempt. An INDETERMINATE session acknowledgement records continued uncertainty, not proof of non-execution.

Reuse existing internal authorization rules for resolution endpoints, resolveUserId(), approval callbacks, and the Telegram flushPendingNotifications() requirement. Any stale, cross-workspace, mismatched, partial, or already-consumed resolution fails without mutation.

### 12.8 Required implementation test matrix

This matrix is an implementation acceptance requirement; it is not a test run for this document edit.

| Area | Required cases |
| --- | --- |
| Store contract | Every legal/illegal transition; claim lifecycle on success/error/timeout/indeterminate/pre-launch cancellation; identical and conflicting admission; duplicate IDs; stale fencing; terminal immutability; atomic settlement plus lock/outbox; retention/prune boundaries; unknown status/mode; missing identifiers/workspace. |
| Idempotency and locks | AT_MOST_ONCE same fingerprint across turns and concurrent calls; claim lifecycle (RESERVED through INDETERMINATE, CONSUMED on success/error/timeout, deleted on pre-launch cancellation); crash between launch intent and settlement leaves a blocking claim; NON_IDEMPOTENT error/timeout blocked until one-shot approval; approval consumed exactly once; missing/ambiguous target resolver falls back to SESSION_MUTATIONS. |
| Schema reset guard | Clean V1 marker; non-empty schema with no Flyway history while baseline-on-migrate is true; missing marker/table; bad marker version; old Flyway history/checksum; empty and legacy filesystem roots. Startup must fail before readiness or tool dispatch on invalid state. |
| Audit | Admission and launch acknowledgement gates; failed audit prevents executor invocation; duplicate relay after audit commit but before outbox acknowledgement; concurrent per-session writers; unavailable HMAC key; resolution audit failure leaves lock unchanged. |
| Crash injection | Crash after admission, after claim write, after launch intent, after audit acknowledgement but before executor call, after process/container start, after exit before settlement, during settlement, after terminal attempt write but before claim update, after terminal commit before history append, and during outbox delivery. |
| Resume integration | Sequential approval resume, pending tool resume, partially completed parallel batch, cron resume, browser, MCP, malformed/legacy checkpoint payload, missing/duplicate operation IDs, stale checkpoint version. |
| Executor evidence | PODMAN created/running/exited/missing/removed container; legacy unlabeled zc- container left untouched; NAMESPACE/DIRECT/NONE process start identity matching and mismatch; built-in process crash. |
| Resolution | Unknown or case-variant resolution kind; wrong actor/workspace/lock/origin/fingerprint/target; stale verifier evidence; malformed/partial evidence; repeated approval; `applyResolution` rejects `ONE_SHOT_RETRY` without consuming the resolution, clearing the lock, or enqueueing `ACTION_RESOLVED`; `applyResolutionAndAdmitRetry` consumes it atomically with retry admission; crash between authorization acknowledgement and lock clear; Telegram callback flush; no mutation on audit failure. |
| Persistence and output | SQLite/PostgreSQL/filesystem parity; native reflection; reasoning-step attempt-link integrity; transformer priority/order, PII expansion, and the final post-chain bound; final observation including marker and multibyte output; no raw credentials, headers, provider payloads, or tool output in audit/outbox logs. |

Production-path tests exercise real ToolInvocationGuard → ToolInvoker → selected executor paths and assert that a blocked/indeterminate attempt never invokes the executor. Mock-only tests do not prove production wiring. Implementation verification includes focused affected-module tests, module-scoped PMD, documentation verification, native-sensitive checks, and the required review wrapper.

## 13. Separate follow-on proposals

Keep these independent from tool-attempt settlement:

- A detached CLI client/daemon protocol for persistent terminal work.
- OpenAI prompt-cache prefix experiments with provider-specific measurement.
- A TLA+ model for Amsha checkpoint, approval, parallel-batch, and tool-settlement transitions.

Rui's TLA+ models and process-lifecycle research remain useful references. Adopting a formal model is a follow-on unless architecture review makes it a prerequisite for implementation.

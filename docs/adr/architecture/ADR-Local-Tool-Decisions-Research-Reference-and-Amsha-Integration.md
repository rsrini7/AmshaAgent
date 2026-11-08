# ADR: Use Local Tool Decisions as a Research Reference for Amsha

- **Status**: Proposed
- **Date**: 2026-11-08
- **Decision owners**: Amsha maintainers
- **Related proposal**: [`Audit-Decision-Replay-Logging.md`](../../proposals/Audit-Decision-Replay-Logging.md)
- **Decision-plane ADRs**: [`ADR-jev-decision-plane.md`](ADR-jev-decision-plane.md) and [`ADR-jev-decision-plane-evaluation-path.md`](ADR-jev-decision-plane-evaluation-path.md)
- **Source repository**: [`myfear/the-main-thread/local-tool-decisions`](https://github.com/myfear/the-main-thread/tree/main/local-tool-decisions)
- **Source article**: [LangChain4j Decision Models with Ollama](https://www.the-main-thread.com/p/langchain4j-decision-models-ollama)
- **Reviewed source revision**: `the-main-thread` `main` at `30ba4b49cebd2b72003c9f01d2e39a1b1611bfaa` (2026-11-08)

## 1. Context

The `local-tool-decisions` project demonstrates typed LangChain4j Decision Services, a model-backed assessment, deterministic Java thresholds, a Quarkus tool guardrail, and an evaluation harness. It is a runnable research application for an order/refund example, not a published general-purpose Java library: its POM packages a Quarkus application, its policy and tool effects are demo-specific, and its LangChain4j dependencies are timestamped preview snapshots. Its README also says container and native-image builds have not been validated.

Amsha already has its own tool decision boundary in `ToolInvocationGuard`, `ToolGovernanceGuard`, and the classifier APIs. The parent decision-plane ADR remains Deferred. The accepted evaluation-path ADR permits an opt-in, internal, audited, non-routing provider evaluation without registering a production `DecisionEngine` bean.

The `Audit-Decision-Replay-Logging` proposal is present on Amsha `main`. At review time Amsha `main` was `6dbd9d881` and P133 was `b30f3fa67`. P133 contains commit `14166429a`, which narrows that proposal: redacted audit payloads are explanatory evidence, while a sanitized fixture corpus provides policy regression. P133 also changes `ToolRegistry`, `ToolInvocationGuard`, and adds tool-attempt audit outbox behavior. That makes the proposal independent in purpose, with overlapping implementation touchpoints.

## 2. Proposed Decision

1. Treat `local-tool-decisions` and its article as research and evaluation references. Do not add the project as an Amsha runtime dependency or port its demo refund policy and tool classes into production.
2. Reuse the demonstrated ideas selectively: typed decision outputs, explicit Java-side thresholds, malformed-response handling, and reproducible evaluation captures. Keep Amsha policy, mandatory guards, tool execution, and audit ownership in Amsha.
3. Do not register a production `DecisionEngine` or allow model output to bypass `ToolInvocationGuard`, `ToolGovernanceGuard`, mandatory checks, or durable action-lock checks. Any real provider call remains within the accepted explicit evaluation path: disabled by default, internal-only, audited before egress, allow-listed, and non-routing. `Instance<DecisionEngine>` must remain unsatisfied while the parent ADR is Deferred.
4. Implement audit evidence and policy regression from Amsha's existing interfaces. Store bounded, redacted tool arguments and decision context for explanation; use sanitized fixtures replayed through the real Java policy for CI regression. Do not claim exact forensic replay from redacted or truncated audit records. Exact replay requires a separately approved protected-retention design.

## 3. Branch and Integration Plan

### If P133 is not merged

1. Start the replay-logging implementation from Amsha `main`, where the proposal and existing `ToolRegistry`, `ToolInvocationGuard`, `ToolGovernanceGuard`, and audit ports already exist.
2. Carry forward the scope correction from P133 commit `14166429a` (or reproduce it manually): the audit record is review evidence, and the golden-session corpus is the deterministic policy regression mechanism. Do not cherry-pick the rest of P133 to obtain that documentation change.
3. Implement `AuditPayloadFormatter`, invocation/block audit enrichment, safe trace metadata, and the policy fixture corpus against the `main` APIs. Keep the existing HMAC audit chain and avoid a schema change unless a separate approved decision requires one.
4. Use `local-tool-decisions` only to inform evaluation design. Do not add its timestamped snapshot dependencies to Amsha. Any experimental provider adapter must use Amsha's explicit evaluation path.

### If P133 is merged

1. Apply the same replay-logging change on top of the merged branch and review the changed `ToolRegistry` and `ToolInvocationGuard` paths before editing them.
2. Keep decision evidence and attempt lifecycle events distinct. Reuse one bounded redaction/serialization policy, and correlate audit events with `operationId` and `attemptId` when those identities exist. Do not emit duplicate invocation events through both the legacy audit path and the attempt outbox.
3. Preserve the P133 attempt admission, settlement, lock, and resolution audit gates. Decision replay logging must not replace or weaken their synchronous audit acknowledgements.

The replay-logging proposal does not require the cron workspace schema or startup reconciliation changes. If P133 remains unmerged, implement the proposal from `main` and keep the tool-attempt settlement work separate. If the tool-attempt work is later selected, integrate its coherent commit series and reconcile the shared audit touchpoints rather than assuming P133 APIs exist on `main`.

## 4. Consequences and Acceptance Gates

- Amsha can use the research project to guide model question design and evaluation without depending on an application-specific preview project.
- The audit proposal can proceed from `main`; P133 contributes a useful scope correction and later correlation identities, but is not a prerequisite.
- Acceptance requires secret and PII redaction, bounded payloads, HMAC chain integrity, tests for allow/block/approval-required outcomes, and a sanitized ordered fixture corpus that invokes no LLM or network.
- Production decision-plane activation remains Deferred. This ADR does not authorize model-driven routing, a production `DecisionEngine` bean, or a new dependency on the external project.

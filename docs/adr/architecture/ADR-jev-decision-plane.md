# ADR: Jev Decision Plane for Typed Agent Judgments

- **Status**: Deferred
- **Exit criteria**: §8 — Exit Criteria, Promotion Path, and Review. This section is the standing promotion contract; §7.7 records the dated evidence disposition that produced it.
- **Date**: 2026-09-22
- **Decision Owners**: Amsha Architecture Committee
- **Related Invariants**: `docs/Architecture_Invariants.md` §27 (decision plane seam), §27.6–27.7 (evaluation path; production-bean gating), §1.8 (module boundaries — governed transport ownership), §4.12, §5.9 (transaction-free zone), §7 parallel tool execution, §7.13.2–7.13.4; `AGENTS.md` §1.18, §8
- **Feature Slug**: `jev-decision-plane`
- **Issue Tracker**: `.scratch/jev-decision-plane/`
- **Research Basis**: `docs/research/Jev-Amsha-Decision-Plane-Scout-2026.md`; Phase 0 provider verification evidence in `docs/research/Jev-Provider-Integration-Plan-2026.md` (2026-10-01, added after this ADR was written)
- **Related ADR**: `ADR-jev-decision-plane-evaluation-path.md` (**Proposed**, 2026-10-07) — would authorise the explicit, opt-in, non-routing evaluation path only. It does **not** change this ADR's `Status: Deferred` and does not satisfy §8.4 steps 1–5.

---

## 1. Context and Problem Statement

Amsha already has several bounded decision points, but they use different
contracts and levels of uncertainty:

* `TaskClassifierService` uses deterministic keyword matching for domains and
  role mapping.
* `IntentDetectorService` combines fast-path routing with structured LLM
  classification and deterministic fallback.
* `ClassifierResult` provides bounded `Opinion`/`NoOpinion` results, but its
  active contract is primarily a label plus scalar confidence.
* ONNX shadow classifiers, `ClassifierShadowDispatcher`,
  `OnnxClassifierRuntimeControl`, and the post-policy/answer-quality dispatchers
  already provide lifecycle, failure, and rollout machinery.

The Jev/System One model contributes a useful vocabulary for closed judgments:
`Choice`, `Score`, and `Noul`. It does not own workflow, authorization, HITL,
tool execution, persistence, or the agent state machine. The architectural
question is therefore not whether Amsha should replace its control plane with
Jev. It is whether Amsha should introduce one typed judgment seam that can
support existing local classifiers, self-hosted wire-compatible engines, and
optional remote providers while preserving current invariants.

The high-cost mistakes are known:

* a `Noul` probability must not be treated as a degree or severity;
* probabilities cannot be transferred between `Noul`, `Choice`, and `Score`;
* independent propositions and their negations are not guaranteed to sum to
  `1.0`;
* missing provider fields must remain absent, not become zero or false;
* a remote provider cannot be placed behind network-free in-process classifier
  SPIs or used to authorize its own network call.

## 2. Decision and Target Architecture

Adopt a provider-neutral, typed decision plane with a small deep interface:

```java
interface DecisionEngine {
    DecisionResult evaluate(DecisionRequest request);
}
```

`DecisionRequest` contains a registered decision ID/version, bounded state
projection, typed question specification, deadline, and sensitivity/retention
classification. `DecisionResult` is sealed and contains typed `Choice`, `Score`,
or `Noul` opinions plus confidence/probability data where present, or a
`NoOpinion` result. For multi-question evaluations, each question is registered with an identity-safe typed handle equivalent to `QuestionKey<A>`: callers retrieve only the answer type associated with that handle, and the contract rejects duplicate or unknown handles. This correlation is compiler-checked (or enforced by an equivalent type-safe API) and must not depend on unchecked casts or permit a runtime `ClassCastException`.

The decision plane has three adapter categories:

1. **In-process adapter** — ONNX or another synchronous local implementation.
   This may participate in `classifier-api`, shadow classifiers,
   `PostPolicyToolRiskInterceptor`, or `AnswerQualityInterceptor`, subject to
   their network-free, payload-free contracts.
2. **Self-hosted adapter** — a separately hosted local/loopback engine such as
   the Laya/Kev alternatives. It is eligible for an in-process seam only when
   actually embedded. A separate process is modeled explicitly as
   `SelfHostedDecisionProvider`, with local-only exposure, redaction, and
   retention controls.
3. **Remote provider** — TypeSafe, OpenRouter, Vercel, Cloudflare, or another
   approved provider. It runs only from an explicitly authorized orchestration
   or evaluation path, never as a remote implementation of the network-free
   classifier SPIs.

Decision results are consumed by deterministic Amsha policy modules. Policies
own thresholds, candidate eligibility, review bands, authorization, side
effects, and fallback. A higher-risk opinion may escalate a decision, but may
never downgrade an existing block or bypass HITL.

Registered decision definitions contain:

* stable decision ID and schema/policy version;
* primitive and closed labels/rubric;
* exact state projection, criteria, redaction, retention, and provider rules;
* candidate-reduction policy and rationale;
* threshold bands and cost-of-error classification;
* allowed adapter categories and fail mode;
* evaluation corpus, calibration status, and rollback switch.

`ClassifierResult.NoOpinionReason` remains the compatibility vocabulary:
`DISABLED`, `MISSING_MODEL`, `TIMEOUT`, `OVERLOAD_REJECTED`, `CIRCUIT_OPEN`,
`LOW_CONFIDENCE`, and `INFERENCE_ERROR`. Malformed provider responses map to
`INFERENCE_ERROR` unless a separately reviewed enum extension is required.
`ruleId` remains the classifier/provider rule identity; it is not overloaded
with decision schema version.

## 3. Invariants and Compliance

* **Control-plane ownership**: `PreflightState`, `LoopStuckDetector`,
  `ToolInvocationGuard`, HITL, workspace isolation, persistence, and `FINISH`
  sequencing remain deterministic and authoritative.
* **Network-free classifier SPIs**: in-process classifier, post-policy, answer-
  quality, and shadow implementations perform no network I/O, logging,
  persistence, export, or sensitive-payload retention.
* **Remote-provider boundary**: a remote decision cannot grant the permission
  required to make its own network call. Remote state projections require
  reviewed provider location, retention, training-use, deletion, and tenant-
  isolation guarantees; regulated or secret-bearing state is excluded unless
  explicitly approved.
* **No transaction coupling**: evaluation and remote transport do not run
  inside `@Transactional` persistence writes.
* **Native reflection**: new DTOs, records, sealed results, and provider types
  are registered in the owning central `*ReflectionConfig`; no type-level
  `@RegisterForReflection` shortcut is used.
* **Lifecycle reuse**: classifier enablement, readiness, leases, draining,
  optional CDI discovery, shadow evaluation, and payload-free metrics reuse
  existing Amsha machinery rather than introducing a second lifecycle model.
* **Typed semantic safety**: `Noul` is proposition probability; `Score` is an
  ordered rubric with separate score/probability/confidence fields; values are
  never compared across primitive types.
* **Bounded fan-out**: the 255 `Choice` option ceiling and independently probed
  context observations are planning inputs, not unchanging vendor SLAs. State,
  criteria, and candidate sets are versioned and evaluated together.

## 4. Alternatives Considered

1. **Keep the current classifiers only** — lowest change, but leaves decision
   shapes inconsistent and makes calibration/typed abstention difficult.
2. **Call Jev directly from each caller** — duplicates transport, redaction,
   retry, lifecycle, and fallback logic across routing and guard callers.
3. **Put remote Jev behind existing classifier SPIs** — rejected because those
   SPIs are synchronous, in-process, network-free, and payload-free.
4. **Use a general LLM with JSON mode** — retains generation/parsing ambiguity
   and does not provide the same primitive-level semantics.
5. **Make Jev mandatory** — rejected because Amsha must continue operating with
   local ONNX, current structured LLM, deterministic fallback, or no optional
   provider at all.

## 5. Consequences

### Positive

* One typed seam concentrates provider transport, decoding, redaction, and
  failure mapping.
* Existing Amsha lifecycle and safety machinery is reused.
* Local, self-hosted, and remote adapters can be compared without changing
  policy callers.
* Atomic questions, typed probability semantics, explicit abstention, and
  deterministic policy become testable independently.

### Costs and risks

* A new contract and registry must prevent a second, subtly different policy
  parser from appearing beside existing classifier contracts.
* Provider retention and data-location review become mandatory work.
* Candidate reduction and state/criteria changes require recalibration because
  they can change judgments.
* A remote provider adds latency, cost, availability, and outbound-governance
  concerns; it remains disabled by default until measured.

## 6. Acceptance and Rollback

The ADR is not ready for implementation completion until:

1. The in-process contract has malformed, partial, missing, wrong-primitive,
   duplicate, unknown-reference, and adversarial-state tests.
2. Shadow mode compares current keyword, ONNX, structured-LLM, and candidate
   decision adapters using payload-free evidence.
3. Provider retention/location/training-use controls are documented for every
   enabled adapter.
4. Each active decision has a deterministic fallback, review band, timeout,
   cost ceiling, and kill switch.
5. Security-adjacent decisions show no false-allow regression and preserve
   monotonic block/HITL behavior.
6. Disabling the registration or provider lease restores the current path with
   no data migration.

Implementation ownership and the target latency/cost budget must be assigned in
the spec before the first active rollout.

## 7. Implementation Checkpoints

### 7.1 Issue 01 Completed: Contract and Vocabulary (2026-09-22)

The provider-neutral decision vocabulary and contract have been implemented in `classifier-api`:

* **Functional Seam**: Defined the typed evaluation seam `DecisionEngine.evaluate(DecisionRequest)`.
* **Sealed Result Contract**: Sealed `DecisionResult` with `Opinion` and `NoOpinion`, preserving payload-free diagnostics and reusing `ClassifierResult.NoOpinionReason` compatibility.
* **Typed Question Correlation**: Implemented `QuestionKey<A extends DecisionAnswer>` with request-bound owner identity; `Opinion` enforces handle equality and answer type validation at construction time, failing closed on duplicate or unknown handles without unchecked casts.
* **Primitive Semantics**: Enforced domain invariants on `Choice` (bounded options), `Score` (ordered rubric, distinct probabilities, normalized scores), and `Noul` (proposition probability $\in [0.0, 1.0]$) using JDK 25 compact constructors. Values are never compared or converted implicitly across primitives.
* **Absence Preservation & Fail-Closed Decoding**: `DecisionPayloadDecoder` validates container types and rejects unexpected handles; missing fields remain absent, and the `ClassifierResult` compatibility bridge never invents confidence or rule IDs.
* **Provider Placement Enforcement**: Implemented `ProviderPlacementValidator` and integrated structural placement validation into the classifier SPI registration and CDI discovery paths (`core` and `local-ml-onnx`).
* **Central Reflection Registration**: Registered all contract types and nested records centrally in `ClassifierApiReflectionConfig`.

### 7.2 Issue 02 Completed: In-Process Adapter and Transaction Boundary (2026-09-22)

The deterministic in-process adapter and defensive transaction boundary have been implemented in `classifier-api`:

* **In-Process Adapter**: Implemented `InMemoryDecisionEngine` implementing `InProcessDecisionEngine` with zero network I/O, persistence, or side effects, operating synchronously and safely within the in-process seam. All arbitrary executable callback factories (`Function`, `Supplier`, etc.) have been removed to structurally prevent wrapping remote or stateful providers; the engine evaluates exclusively from immutable static answers, raw provider response maps decoded via `DecisionPayloadDecoder`, static results, or declarative failure simulation.
* **Defensive Transaction Boundary**: Implemented `DecisionTransactionGuard` inspecting ambient JTA transaction status (with Arc container resolution and explicit status injection) enforcing the §5.9 transaction-free zone.
* **Transaction Violation Policies**: Implemented `TransactionViolationPolicy` supporting both `FAIL_FAST_EXCEPTION` (immediate `IllegalStateException`) and `FAIL_CLOSED_NO_OPINION` (`OVERLOAD_REJECTED`), proving through tests that the underlying provider/evaluator is never reached when transaction rejection occurs.
* **Architectural Invariants Verified**: Verified by reflection that neither `InMemoryDecisionEngine`, `DecisionEngine`, `InProcessDecisionEngine`, nor `DecisionTransactionGuard` carries `@Transactional` annotations, and verified that `InMemoryDecisionEngine` contains zero fields or method parameters of functional callback interface types.
* **Contract Boundary & Absence Preservation**: Proved through real `DecisionEngine.evaluate(DecisionRequest)` tests that malformed responses, partial answers, wrong primitives, missing identifiers, and unknown references fail closed to `INFERENCE_ERROR`, and absent confidence/probabilities are preserved without invented certainty.
* **Central Reflection Registration**: Registered `InMemoryDecisionEngine`, `InMemoryDecisionEngine.Builder`, `DecisionTransactionGuard`, and `TransactionViolationPolicy` centrally in `ClassifierApiReflectionConfig`.

### 7.3 Issue 03 Completed: Policy and Lifecycle (2026-09-23)

Existing classifier lifecycle and deterministic policy machinery have been integrated with the decision contract without creating a second control model:

* **Lifecycle and Control Reuse**: Reused existing `ClassifierShadowDispatcher`, `OptionalClassifierBeanResolver`, `OnnxClassifierRuntimeControl`, leases, readiness checks, disable-and-drain, and `NoOpinionReason`. No secondary enablement or registration plane was introduced.
* **Deterministic Policy Modules**: Implemented `ToolRiskDecisionPolicy` with `ReviewBand` thresholds, `CandidateReductionPolicy` shortlist filtering and bounding, strictly monotonic risk escalation (`BLOCK > REQUIRE_APPROVAL > existing decision (ALLOW)`), and deterministic fallback on `NoOpinion` under `FAIL_OPEN` and `FAIL_CLOSED`.
* **Authoritative Safety Boundaries Preserved**: Preserved `ToolInvocationGuard`, HITL boundaries, `PreflightState`, `LoopStuckDetector`, `AnswerQualityGuard`, single retry limits, and `FINISH` action handling as authoritative. A decision can escalate risk but can never downgrade an existing block or bypass human-in-the-loop approval.
* **Option Validation & Payload-Free Diagnostics**: Enforced choice option validation in `InMemoryDecisionEngine` failing closed with payload-free fixed diagnostics (`"Choice label not in allowed options"`), preventing rejected choice labels or option sets from echoing into `NoOpinion.detail`.
* **Bounded Low-Cardinality Telemetry**: Created `DecisionTelemetry` emitting strictly bounded, finite-cardinality metric tags (`action`, `escalated`, `fallback`, `outcome`), keeping arbitrary strings, decision IDs, rule IDs, and payloads off metrics counters.
* **Uncertainty and Absence Policy**: In `ToolRiskDecisionPolicy`:
  * Low-confidence restrictive labels (`BLOCK` or `REQUIRE_APPROVAL`) escalate to `REQUIRE_APPROVAL` with `escalated = true` under either fail mode. Clean `NoOpinion` and low-confidence non-restrictive labels preserve the configured `FAIL_OPEN` fallback; `FAIL_CLOSED` escalates those fallback cases to approval.
  * Policy resolutions retain confidence as `OptionalDouble`; conversion through `toClassifierResult` never fabricates confidence. Restrictive actions without confidence required by the legacy classifier opinion contract are rejected at that compatibility boundary rather than silently dropped or assigned synthetic certainty. `QuestionKey` correlation remains identity-safe and type-checked.
  * Clean absence preserves its `NoOpinionReason` and does not invent approval under `FAIL_OPEN`; approval is introduced only by `FAIL_CLOSED` fallback or the explicit low-confidence restrictive-label rule.
  * Unrecognized `Choice` labels trigger fallback (`UNRECOGNIZED_CHOICE_LABEL`), failing closed under `FAIL_CLOSED` rather than silently allowing.
  * Unmapped primitives (`Noul`, `Score`) trigger fallback (`UNMAPPED_PRIMITIVE`) rather than making ungrounded risk assumptions.
  * Multi-question requests support exact target `QuestionKey<Choice>` correlation or monotonic composition.
* **Production Dispatcher & Boundary Integration Tests**: Wired production `PostPolicyToolRiskDispatcher` to execute `ToolRiskDecisionPolicy.resolveCumulative` over its interceptor evaluations; added `ClassifierLifecyclePolicyBoundaryIT` verifying lifecycle paths, runtime controls, readiness checks, and lease draining; added `ToolInvocationGuardPostPolicyRiskTest` proving end-to-end production integration through `ToolInvocationGuard`, `PostPolicyToolRiskDispatcher`, `ToolRiskDecisionPolicy`, and `DecisionTelemetry`; and verified mandatory audit-before-mutation and internal role authorization in `OnnxAdminResourceTest`.
* **Native Reflection Registration**: Registered `ToolRiskDecisionPolicy`, `PolicyResolution`, and `DecisionTelemetry` in `ClassifierApiReflectionConfig` and `CoreReflectionConfig`.

### 7.4 Issue 04 Completed: Shadow Evaluation Corpus and Comparison Harness (2026-09-24)

Observational shadow evaluation has been implemented and verified across current Amsha baselines and candidate decision plane adapters without modifying active routing or policy:

* **Observational Isolation & Provider Placement Validation**: Implemented shadow adapters `DecisionIntentShadowClassifier`, `DecisionToolRiskShadowClassifier`, and `DecisionAnswerQualityShadowClassifier` implementing Amsha's respective shadow classifier SPIs. All adapters are disabled by default (`amsha.decision.shadow.*.enabled=false`) and evaluate observationally via `DecisionEngine`, preserving active routing, tool authorization, HITL enforcement, and retry budgets untouched. In accordance with architecture invariants, shadow classifiers enforce `ProviderPlacementValidator.activateForClassifierSpi` before request evaluation, strictly preventing remote or non-in-process engines from running under classifier SPI boundaries (`SecurityException`).
* **Shared Boundary Invariant Adapter (`DecisionShadowResultAdapter`)**: Extracted common opinion identity checks, schema version verification, bound question key lookup, closed option set enforcement, exception-safe mapping, confidence absence preservation, non-blank rule checks, and fail-closed error handling into a central package-private helper, eliminating drift across the three production shadow classifiers.
* **Closed Option Sets & Boundary Enforcement**: Classifiers strictly enforce declared closed option sets (`RISK_OPTIONS`, `QUALITY_OPTIONS`, and known role shortlists) at the classifier boundary. Any unrecognized choice label or alias fails closed to `NoOpinion(INFERENCE_ERROR)`.
* **Request Identity & Absence Preservation**: Shadow classifiers strictly validate request identity (`decisionId` and `schemaVersion`) against engine opinions, look up answers exclusively via exact `QuestionKey` handles with owner tokens rather than fuzzy string matching, preserve missing confidence as `NoOpinion(LOW_CONFIDENCE)` without fabricating certainty, and fail closed to `INFERENCE_ERROR` if rule identifiers are missing or blank.
* **Multi-Partition Redacted Corpus**: Created a 30-case redacted test corpus spanning all 9 required partitions (`standard`, `negation`, `contradictory_criteria`, `hostile_injected_state`, `irrelevant_context`, `malformed_partial_payload`, `duplicate_identifiers`, `shortlist_changes`, and `state_vs_criteria_placement`) across intent, tool risk, and answer quality decisions. Stored at `core/src/test/resources/shadow-evaluation/shadow_evaluation_corpus.json` and tracked at `.scratch/jev-decision-plane/evidence/shadow-corpus/shadow_evaluation_corpus.json`.
* **Duplicate Identifier Rejection**: Verified fail-closed rejection of duplicate identifiers across multiple boundaries: strict JSON duplicate-field detection in `ShadowCorpusLoader` via `JsonParser.Feature.STRICT_DUPLICATE_DETECTION` (rejecting raw payloads with duplicate JSON keys, including duplicate parameter names), duplicate question handles in `DecisionRequest.Builder`, duplicate options in `ChoiceQuestion`, duplicate decision IDs in `InMemoryDecisionEngine.Builder`, duplicate test case IDs in `ShadowCorpusLoader`, and duplicate-identifier conflict markers in corpus cases (`toolrisk-dup-01`).
* **Deterministic Rule Baseline vs. Illustrative Simulations**: Built `DecisionShadowComparisonHarness` (with modular `ShadowCorpusLoader` and `ShadowEvaluationReportRenderer`):
  * *Measured Pipeline Baseline*: `IN_PROCESS_DECISION` executes real production shadow classifiers backed by `DeterministicRuleInProcessEngine` evaluating serialized state without test-case answer keys or partition-based bypasses. This serves as a deterministic rule baseline validating pipeline wiring and boundary invariants; it does **not** evaluate a candidate decision model.
  * *Measured Keyword Baseline*: `KEYWORD` measures regex and keyword heuristics, demonstrating prompt injection and negation vulnerability.
  * *Illustrative Simulations*: `STRUCTURED_LLM`, `ONNX`, `SELF_HOSTED`, and `REMOTE` are synthetic baseline projections for comparative structure and timeout aggregation. Accuracy and calibration metrics are marked `N/A (Synthetic)` because simulated paths use expected labels by construction.
* **Candidate Model Activation Gates Strictly Pending**: The deterministic rule baseline achieved 0 observed false allows (0.0% false allow rate) on security/tool-risk evaluation. Candidate model activation gates (for ML, self-hosted, and remote decision models) remain **strictly PENDING** until actual candidate models are evaluated through the full path in Issues 05 and 06.
* **Timeout & Provider Failure Coverage**: Harness and integration tests explicitly assert timeout and failure handling. Remote candidate simulations record timeouts on contradictory criteria; unit tests verify that `InMemoryDecisionEngine.ofSimulatedTimeout()`, overload rejection, and throwing engine failures fail closed to `NoOpinion` with appropriate reasons (`TIMEOUT`, `OVERLOAD_REJECTED`, `INFERENCE_ERROR`).
* **Real CDI Container Wiring**: Added `DecisionShadowCdiWiringIT` executing under Quarkus Arc container injection (`@QuarkusTest`) in `app`, verifying real bean discovery, MicroProfile config property binding, and default-disabled resolution without Mockito mocks.
* **Data Privacy and Governance Decisions**:
  * State Location: `IN_PROCESS_MEMORY_ONLY` (no external network egress or disk persistence).
  * Retention: `EPHEMERAL_DISCARD_ON_TURN_FINISH` (zero persistence beyond turn lifecycle).
  * Training Use: `FORBIDDEN` (customer/system prompts are never permitted for model training).
  * Tenant Isolation: `STRICT_LOCAL_NO_EXPORT` (session-bound in-memory boundary).
* **Future Provider Data-Egress Gate**: Current shadow projections may contain full parameters, goals, or answers and are safe only because these classifiers resolve an in-process engine. Issues 05 and 06 must apply the decision's allow-listed state projection and `RedactionPolicy` before serializing state for any separately hosted or remote provider; absent or invalid policy fails closed. Tests must verify excluded fields do not appear in provider requests, logs, or evidence.
* **Calibration Proxy & Abstention Semantics**: Safe abstention (`NoOpinion`) on contradictory or adversarial criteria is recognized as preferable to high-confidence false allow. Expected abstentions are credited correctly in accuracy evaluation. Reported calibration gap is documented as an illustrative coarse proxy, not a robust calibration assessment. Vendor benchmarks are explicitly rejected as Amsha production evidence.
* **Central Native Reflection Registration**: Registered `DecisionIntentShadowClassifier`, `DecisionToolRiskShadowClassifier`, and `DecisionAnswerQualityShadowClassifier` centrally in `CoreReflectionConfig`.

### 7.5 Issue 05 Completed: Self-Hosted Wire-Compatible Provider Evaluation (2026-09-25)

The self-hosted HTTP engine accepts only non-blank JSON-object state and must be called from a worker or virtual thread, never a Vert.x event-loop thread, because evaluation uses blocking HTTP. The projection allow-list names top-level fields; allowing a nested object includes its entire subtree except values removed or masked by `RedactionPolicy`, so each configured subtree requires explicit review. Endpoint user information is rejected, and blank state fails closed before any request is sent. `StateProjectionRedactor` is a separate boundary control from `TraceRedactor` and `PiiScanReflex`; keep their sensitive-data coverage aligned when those rules change.

Self-hosted decision engines (such as local Laya/Kev processes) have been evaluated as a distinct provider category with strict loopback isolation, allow-listed projection, and contract verification:

* **Placement & Classifier SPI Boundary**:
  * Embedded implementations may implement `InProcessDecisionEngine` with `ProviderCategory.IN_PROCESS` only if strictly synchronous and network-free.
  * Separate-process implementations are modeled explicitly as `SelfHostedDecisionEngine` implementing `DecisionEngine` and `SelfHostedDecisionProvider`, declaring `ProviderCategory.SELF_HOSTED`.
  * `ProviderPlacementValidator` strictly prevents `SelfHostedDecisionProvider` implementations from satisfying synchronous, network-free classifier SPIs (`SecurityException`).
* **Local-Only Loopback Binding Invariant**:
  * `SelfHostedEndpointValidator` requires endpoint URIs to bind strictly to IPv4 `127.0.0.1`.
  * Wildcard exposure (`0.0.0.0`), `localhost` (DNS rebinding risk), non-loopback IPs, and external domains fail closed at startup with `SecurityException`.
* **Allow-listed State Projection & RedactionPolicy**:
  * Added `StateProjectionPolicy` in `classifier-api` and `StateProjectionRedactor` in `core`.
  * Before any state is serialized across the loopback HTTP boundary, allow-listed state projection drops all unapproved fields, and `RedactionPolicy` strips secrets (Bearer tokens, API keys, passwords, credentials), PII (emails, SSNs, phone numbers), and explicitly masked fields.
  * Missing, null, or invalid `RedactionPolicy` or empty allow-list fails closed immediately.
  * Local HTTP contract tests prove excluded fields, unapproved debug keys, and raw secrets are absent from outbound HTTP request bodies, logs, and evidence.
* **Wire Protocol & Defensive Mapping**:
  * Verified wire compatibility for `Choice`, `Score`, and `Noul` responses with latency and token metadata via `DecisionPayloadDecoder`.
  * Malformed JSON, partial answers (missing question keys in multi-question requests), unknown question handles, wrong primitives, and HTTP 400 fail closed to `NoOpinion(INFERENCE_ERROR)`.
  * HTTP 503/429 maps to `NoOpinion(OVERLOAD_REJECTED)`, HTTP 408/client timeout maps to `NoOpinion(TIMEOUT)`, process failure / connection refused maps to `NoOpinion(MISSING_MODEL)`, and disabled provider returns `NoOpinion(DISABLED)`.
* **Defensive Transaction Boundary**: Verified that `SelfHostedDecisionEngine` rejects evaluation when called inside an active JTA transaction via `DecisionTransactionGuard`, preventing external HTTP I/O inside `@Transactional` writes (§5.9).
* **Data Governance & Provider Behavior**:
  * State Location: `LOCAL_LOOPBACK_ONLY` (`127.0.0.1` unexposed to external networks).
  * Authentication: Loopback token supported (`X-Amsha-Loopback-Token`).
  * Retention: `EPHEMERAL_DISCARD_ON_EVALUATION` (local ephemeral memory only, zero disk retention).
  * Training Use: `FORBIDDEN` (strictly zero training use).
  * Tenant Isolation: `LOCAL_PROCESS_ISOLATION` (process-bound loopback boundary).
  * Shutdown: Sets `closed = true`, drains active requests, and fails subsequent evaluations with `NoOpinion(DISABLED)`; injected client lifecycle remains with its creator.
* **Activation Gate Strictly Pending**: Wire compatibility does not trigger active activation; provider remains disabled by default (`enabled = false`) and candidate model activation gates remain **strictly PENDING**.
* **Central Native Reflection Registration**: Registered `StateProjectionPolicy` in `ClassifierApiReflectionConfig`; registered `SelfHostedDecisionEngine`, `SelfHostedEndpointValidator`, `SelfHostedProviderConfig`, `SelfHostedProviderConfig.Builder`, and `StateProjectionRedactor` in `CoreReflectionConfig`.

### 7.6 Issue 06 Completed: Remote Provider Boundary and Outbound Network Governance (2026-09-26)

Remote decision engines have been modeled as an explicit external provider category (`ProviderCategory.REMOTE`) with strict outbound network governance, TLS enforcement, allow-listed projection, and non-blocking event loop protection:

* **Placement & Classifier SPI Boundary**:
  * Implemented `RemoteDecisionEngine` declaring `ProviderCategory.REMOTE` and implementing `RemoteDecisionProvider`, `DecisionEngine`, and `AutoCloseable`.
  * `ProviderPlacementValidator` strictly throws `SecurityException` if any attempt is made to place `RemoteDecisionEngine` behind synchronous, network-free classifier SPIs (`PostPolicyToolRiskInterceptor`, `AnswerQualityInterceptor`, or shadow classifier SPIs).
* **Outbound Policy & HITL Self-Authorization Prevention**:
  * A remote decision cannot authorize its own outbound network call.
  * Injected `OutboundHttpPolicyService` validates target egress under `OutboundPurpose.PROVIDER_API_CALL`.
  * When outbound policy denies egress, evaluation fails closed to `NoOpinion(INFERENCE_ERROR)` with payload-free diagnostic `"Outbound policy denied egress for remote decision provider"`.
  * When outbound policy requires HITL approval, evaluation returns `NoOpinion(OVERLOAD_REJECTED)` with `"Outbound policy requires HITL approval; remote evaluation cannot self-authorize"`.
* **Canonical Governed HTTP Transport & SSRF Validation**:
  * Outbound HTTP transport routes exclusively through `GovernedHttpClient`, eliminating duplicate unmediated `HttpClient` paths.
  * Enforces trusted-proxy socket pinning, pre-connect destination DNS validation, and address safety checks via `GovernedHttpDnsPinning`.
  * Integrates `SecretVault` for proxy credentials (`Authenticator`) and custom-CA TLS trust (`SSLContext`), failing closed when vault or references are unresolvable.
  * `RemoteEndpointValidator` requires HTTPS for all non-loopback endpoints, rejecting unencrypted HTTP.
  * Loopback addresses (`127.0.0.1`, `localhost`, `::1`) are permitted with HTTP strictly for local stub testing.
  * Wildcard addresses (`0.0.0.0`, `::`) and URIs containing userinfo (`user:password@`) are strictly rejected.
  * Reuses canonical `OutboundHttpPolicyService.isBlockedLiteralAddress(host)` to reject private, link-local, site-local, and cloud metadata IP literals.
* **Strictly Single-Shot Evaluation (No Retries)**:
  * Remote evaluations enforce a strictly single-shot execution policy with zero retries.
  * Transient failures (HTTP 503, HTTP 429), server errors (HTTP 500), and client timeouts fail fast directly to deterministic `NoOpinion(OVERLOAD_REJECTED)` / `NoOpinion(TIMEOUT)` without resending.
  * Prevents request amplification, upstream backpressure compounding, and thread starvation under degraded provider conditions.
* **Non-Blocking Thread Guard & Transaction Guard**:
  * Enforces the non-blocking thread guard via `Context.isOnEventLoopThread()`; evaluations executed on Vert.x event loops fail fast to `NoOpinion(OVERLOAD_REJECTED)` with `"Remote decision evaluation must not execute on Vert.x event-loop thread"`.
  * Enforces the §5.9 defensive transaction boundary via `DecisionTransactionGuard`; evaluations inside active JTA write transactions fail fast to `NoOpinion(OVERLOAD_REJECTED)`.
* **Allow-Listed State Projection & Redaction**:
  * Reuses `StateProjectionRedactor` and `StateProjectionPolicy`: excluded fields are dropped before serialization, and `RedactionPolicy` strips secrets and PII with `[REDACTED]`.
  * Missing, null, or empty allow-list projection policy fails closed immediately without inventing allow-lists from question IDs.
  * Contract tests prove that excluded debug keys, internal session tokens, and passwords do not reach the wire.
* **Credential Hygiene & Error Mapping**:
  * `RemoteProviderConfig.toString()` redacts `apiKey` (emits `hasApiKey=true/false`).
  * Outbound `Authorization: Bearer <token>` header is added without credential leakage in logs or exceptions.
  * Wire responses decoded via `DecisionPayloadDecoder` for `Choice`, `Score`, and `Noul`.
  * Status code mapping: 401/403 to `NoOpinion(INFERENCE_ERROR)`; 404 to `NoOpinion(MISSING_MODEL)`; 408 to `NoOpinion(TIMEOUT)`; 429/503 to `NoOpinion(OVERLOAD_REJECTED)`; and 502/504 to `NoOpinion(MISSING_MODEL)`.
  * Transport errors (`ConnectException`, `IOException`) and client timeouts (`HttpTimeoutException`) map to payload-free `NoOpinion` results.
* **Data Governance & Provider Behavior**:
  * State Location: `REMOTE_HOSTED` (explicitly declared in config).
  * Retention: `EPHEMERAL_DISCARD` (provider training strictly forbidden; `allowTrainingUse=true` rejected at config creation).
  * Tenant Isolation: Required (`tenantIsolation=true` enforced at config creation).
  * Shutdown: Sets `closed = true`, drains active requests up to 2 seconds, and fails subsequent evaluations with `NoOpinion(DISABLED)`; injected client lifecycle remains with its creator.
* **Activation Gate Strictly Pending**:
  * Provider remains disabled by default (`enabled = false`) and is not wired into active routing.
  * Candidate model activation gates remain **strictly PENDING** until evaluated in Issue 07.
* **Central Native Reflection Registration**:
  * Registered `RemoteDecisionEngine`, `RemoteEndpointValidator`, `RemoteProviderConfig`, and `RemoteProviderConfig.Builder` centrally in `CoreReflectionConfig`.

### 7.7 Issue 07 Completed: Intent Decision Pilot Scaffolding and Decision Closeout (2026-09-27)

Issue 07 evaluates activation gates for the low-risk ambiguous intent routing pilot and establishes the final architectural decision:

* **Pre-Activation Gate Evaluation & Evidence Disposition**:
  * **Empirical Candidate Calibration**: Missing. Issue 04 measured only the existing deterministic baseline across 30 curated test cases; candidate models were illustrative simulations explicitly flagged as `N/A (Synthetic)`. Issues 05 and 06 established wire/SSRF contracts against local stubs. No candidate model has been empirically calibrated against live production intent traffic, false specialist route distributions, or prompt drift.
  * **Empirical Abstention & False-Allow Rates**: Unmeasured on representative production inputs.
  * **Target Latency & Cost Ceilings**: No formal per-query cost ceiling or p95 SLA has been authorized by the Amsha Architecture Committee.
  * **Rollout Ownership**: Unassigned.
  * **Gate Disposition**: In accordance with the pre-activation gate policy, active candidate routing is **NOT ACTIVATED**. The active pilot is recorded as **Deferred**.
* **Pilot Scaffolding & Zero-Migration Rollback**:
  * Implemented `IntentDecisionAction` (`DIRECT_ROUTE`, `SECONDARY_REVIEW`, `DETERMINISTIC_FALLBACK`), `IntentPolicyResolution`, and `IntentDecisionPolicy` in `amsha-classifier-api`.
  * **Result Identity Verification**: `IntentDecisionPolicy.resolve()` strictly validates that `decisionId` and `schemaVersion` match the expected intent request (`amsha.routing.intent.v1`, version 1). Foreign or mismatched results are rejected to deterministic fallback (`"DECISION_ID_MISMATCH"` / `"SCHEMA_VERSION_MISMATCH"`) before using any answer.
  * Implemented `IntentDecisionRouter` in `amsha-core`, wired into `IntentDetectorService`.
  * Preserved deterministic continuity, retry commands (`__CONTINUE__`), and SPI fast-path triggers ahead of the router; fast paths execute with zero router latency or overhead.
  * **Enforced Activation Gate**: For this deferred pilot, setting `AMSHA_ROUTING_INTENT_DECISION_ROUTER_ENABLED=true` alone does NOT permit direct routing. Both `IntentDecisionRouter` and `IntentDetectorService` enforce `isActivationGateApproved()` and `isDirectRoutingPermitted()`, requiring `candidate-evidence-approved=true`, `cost-ceiling-approved=true`, and non-empty `rollout-owner`. Until all three exist, direct routing is programmatically blocked and requests fall through to the authoritative LLM / deterministic fallback with `"ACTIVATION_GATE_NOT_SATISFIED"`.
  * When activation gate approvals are demonstrated, router enforces the three-way policy: high-confidence branch above `reviewBandHigh` (default 0.85) yields `DIRECT_ROUTE`; middle review band between `reviewBandLow` (0.50) and `reviewBandHigh` yields `SECONDARY_REVIEW` for escalation; confidence below `reviewBandLow` yields `DETERMINISTIC_FALLBACK`.
  * Destructive vocabulary filtering strictly overrides any direct specialist route, ensuring destructive commands remain contained by the Coordinator.
  * Controlled by configuration kill switch `amsha.routing.intent-decision-router.enabled=false` (default `false`). When disabled, `IntentDetectorService` executes the current baseline path immediately with zero data migration or schema mutation.
* **Tool-Risk and Answer-Quality Rollout**:
  * Active rollout of external decision engines for `ToolInvocationGuard` or `AnswerQualityDispatcher` remains strictly **DISABLED**.
  * Preflight state, loop stuck detection, `ToolInvocationGuard`, HITL requirements (`NETWORK_OUTBOUND`, `EXECUTE_SHELL`), authorization, and side-effect policies remain completely untouched.
* **Rollback Procedure**:
  * Set `AMSHA_ROUTING_INTENT_DECISION_ROUTER_ENABLED=false` in environment or `amsha.routing.intent-decision-router.enabled=false` in application configuration.
  * No database migration, session flush, or restart-dependent state cleanup is required.
* **Unresolved Risks & Required Next Evidence**:
  1. *Model Drift & Hallucinated Routing*: Candidate models must demonstrate < 1% false specialist routing on ambiguous prompts without degrading coordinator oversight.
  2. *Cost & Token Budgets*: Committee-approved cost ceilings per 1,000 decisions and measurable latency p95 < 50ms must be established before re-opening the pilot. (The latency figure is the local-pilot one; §8.3 supersedes it with a per-lane budget — p95 < 50 ms local/self-hosted, < 500 ms remote — and the cost ceiling remains unset.)
  3. *Accountable Owner*: An accountable rollout and rollback owner must be designated by the Amsha Architecture Committee.
* **Final ADR Disposition**:
  The ADR status is set to **Deferred**. The foundational interfaces (`DecisionEngine`, `DecisionRequest`, `DecisionResult`), local self-hosted boundary (Issue 05), remote governed boundary (Issue 06), and routing pilot scaffolding (Issue 07) remain preserved in the codebase in dormant/shadow state for future evaluation when candidate model evidence is established.

### 7.8 Explicit Evaluation Path authorised (2026-10-07)

§7.7 refused activation and named the evidence that would justify re-opening it, but left one gap
unaddressed: **there was no sanctioned way to produce that evidence.** The Quality gate is UNMET
because no candidate has ever been evaluated, and the plane could not evaluate one — no runtime caller
consumed `DecisionProviderSelector`, `api-key-ref` was resolved by nobody, and the shadow classifier
SPIs are network-free by construction, so a remote engine cannot travel them
(`ProviderPlacementValidator`).

A separate ADR, `ADR-jev-decision-plane-evaluation-path.md`, is **Proposed** to close that gap. Once
accepted it authorises an explicit, opt-in, internal-only, **non-routing** evaluation path: a shared
`DecisionEngineFactory` builds the configured engine transiently, the evaluation path sends only
allow-listed and redacted data, and the engine is closed without ever registering a CDI bean. The path
MUST NOT land before that ADR is accepted.

What this checkpoint does **not** do:

* It does **not** change this ADR's status. It remains **Deferred**.
* It does **not** satisfy §8.4 steps 1–5, and it authorises no production `DecisionEngine` bean and no
  router switch.
* It grants no routing authority. The evaluation path consumes a result; it never chooses a route.

It is the instrument by which the Quality gate (§8.2) can be closed. Activation remains a recorded
human decision under §8.4.

---

## 8. Exit Criteria, Promotion Path, and Review

### 8.1 Why this section exists

§7.7 recorded *why* activation was refused on 2026-09-27, and it named the evidence that would
justify re-opening it. That was the right record, but it lived inside a dated issue checkpoint, so
the promotion conditions travelled with the date rather than with the decision. A reader arriving at
"Status: Deferred" had no standing statement of what would change it, which makes indefinite
deferral the path of least resistance — the failure mode this section exists to close.

The contract below is therefore stated independently of any issue. It is not a promise that this
plane will ship. It is a commitment that it will not stay Deferred indefinitely without somebody
being required to decide.

### 8.2 Current status: activation remains deferred

| Gate | Required evidence | Status (2026-10-03) | Accountable |
|---|---|---|---|
| Protocol | First-party vendor documentation, re-verified within a review window **once one is in force** (8.5), plus all conformance cases passing | **MET** — contract re-verified against `docs.typesafe.ai` 2026-10-01; codec corrected to the real `POST /v1/systemone` shape. **No review window is currently in force**, so this evidence does not lapse until the cadence is confirmed | Amsha Architecture Committee |
| Governance | Approved retention, training use, residency, isolation, secrets, egress | **UNMET** — TypeSafe retention/residency undeclared; training-use is refused outright by `RemoteProviderConfig` | Amsha Architecture Committee |
| Quality | M1 exactly zero and M2 below 1% on a versioned corpus, per lane (8.3) | **UNMET** — no candidate has run through the conformance suite; §7.4 evidence was explicitly `N/A (Synthetic)` | Committee, until a model owner is designated — none exists today |
| Operations | Latency/cost ceilings, monitoring **with drift alerting**, kill switch, named owner, rollback rehearsal | **UNMET** — no authorised cost ceiling or p95 SLA; no named owner; no drift-alerting implementation exists (the only `DecisionTelemetry` counter is payload-free and tagged `action`/`escalated`/`fallback`/`outcome`, so it carries neither a confidence distribution nor model identity and cannot express drift) | Amsha Architecture Committee |

**The Quality gate now has a proposed instrument.** As of 2026-10-07 the explicit evaluation path is
proposed (§7.8, `ADR-jev-decision-plane-evaluation-path.md`), so a credentialed candidate call can be
made and Phase 3 evidence gathered once that ADR is accepted. This does not move any gate's status:
Quality remains UNMET until M1 and M2 are actually measured and reviewed, Governance remains UNMET
until retention and residency are declared, and Operations remains UNMET until ceilings, drift
alerting, and a named owner exist. The gate table above is the current state, not a projection.

**On fixtures.** §2 of the
[integration plan](../../research/Jev-Provider-Integration-Plan-2026.md) records why this gate is worded
strictly: the previous adapter was roughly one-third conformant while its fixtures were green, because
the fixtures encoded the *same* wrong wire shape the codec emitted. Self-consistent fixtures therefore
cannot establish a contract.

Fixtures remain required — they pin the codec against regression. Whether they may be the *primary*
contract evidence depends on the lane:

* **A lane whose provider publishes a contract** (TypeSafe, Laya, Kev) must be evidenced by
  first-party documentation, re-verified within the review window. Fixtures are supplementary and a
  candidate may not enter the registry on fixture evidence alone.
* **A lane with no published contract** — an operator's own wrapper, say — has fixtures as its only
  available evidence. There the fixture corpus becomes the declared contract of record, and must be
  versioned, reviewable, and re-run whenever the wrapper or its upstream model changes.

Where a mirror or third-party summary conflicts with the provider's own documentation, the provider's
documentation wins: §1 of the integration plan records a mirror that misstated the rate limits.

### 8.3 Metrics and acceptance rules

§7.7 named a < 1% threshold for *false specialist routing*. That number is a **routing-accuracy**
budget, not a safety budget, and §8.4 also required a *false-allow* figure. Those measure different
outcomes and were not reconciled, which left the Quality gate un-actionable: a candidate could meet
one and fail the other while apparently satisfying "the threshold". They are therefore separated
below with an explicit acceptance rule for each. The only figures here are ones §6 and §7.7 already
authored; none is newly invented.

| # | Metric | Definition (numerator / denominator) | Acceptance rule |
|---|---|---|---|
| **M1** | False-allow rate | Cases where a model opinion caused a path that should have blocked or escalated to proceed as allowed — a relaxed block, a bypassed HITL, or a pre-policy guard `Allow` that skipped a mandatory check — over all adversarial and security-adjacent corpus cases | **Exactly zero.** Absolute, not a budget. One instance fails the gate |
| **M2** | Specialist routing error rate | Requests routed to a specialist other than the labelled correct one, over the **ambiguous subset only** (cases where the deterministic baseline is itself non-deterministic) | **< 1%**, and no degradation of coordinator oversight |
| **M3** | Abstention rate | `NoOpinion` results over all evaluated cases | **Reported and reviewed; no pass threshold.** Abstention routes to `DETERMINISTIC_FALLBACK`, the pre-existing safe path, so over-abstention cannot cause a safety failure — it costs latency and money, which M4/M5 already bound. Setting a threshold is a Committee decision, not an inferred number |
| **M4** | p95 latency | Per decision, not per batch | **< 50 ms for an in-process/self-hosted local lane; < 500 ms for a remote hosted lane.** The single "< 50 ms" figure above was written for the local intent pilot and is unreachable for a hosted provider; the two-lane budget reconciles it with the feature spec's provisional remote target. Measured per lane, never combined |
| **M5** | Cost ceiling | Per 1,000 decisions | Authorised by the Committee, never inferred from a provider price page |

Two rules that make M1 and M2 measurable rather than rhetorical:

* **M2's denominator excludes cases the deterministic baseline already handles confidently.**
  Measured over the whole corpus, the threshold is satisfied trivially by easy cases and says
  nothing about the ambiguous prompts the router actually exists to arbitrate.
* **M1 is a property of the engine composed with the deterministic policy layer, not of the engine
  alone.** The guarantee comes from §2 — policies may escalate but never downgrade — so M1 must be
  measured end to end through that policy. An engine in isolation cannot demonstrate it.

Each candidate lane is measured and reported **separately**. A combined figure across lanes is not
acceptable evidence: a strong self-hosted candidate would otherwise mask a weak hosted one.

Remaining governance items, unchanged from §7.7:

* **A designated accountable rollout *and* rollback owner** — a person or a team, never a status.
  The activation gate enforces only non-blank, which is why `ActivationGateReadiness` warns on a
  placeholder value.

### 8.4 The promotion procedure

Promotion is a **recorded human decision**, not a code change. The order is deliberate, and each
step is a precondition for the next:

1. **Close the Quality gate.** Run Phase 3: both adapters over one versioned decision corpus,
   reporting **M1, M2 and M3 per lane** as defined in 8.3, with payload-free evidence. The gate
   closes only when **M1 is exactly zero** and **M2 is below 1%**; M3 is reported and reviewed.
   M1 must be measured end to end through the deterministic policy layer, not against a bare
   engine. The corpus is versioned so a later provider, model, or schema change can be re-run
   rather than argued about.
2. **Close the Governance gate.** Declare retention, residency, and training-use for the chosen
   lane. This can fail permanently — see 8.6.
3. **Obtain the Operations approvals.** Everything the Operations row of 8.2 requires, and
   nothing beyond it — that row is the single source of these requirements, so it is referenced
   rather than restated here and cannot drift away from the gate it is meant to close. Drift
   alerting is part of that row, not a separate clause of this step.
4. **Only then** set `candidate-evidence-approved`, `cost-ceiling-approved`, and `rollout-owner`.
   Setting them is the *record* that steps 1–3 happened; it is not a substitute for them. Setting
   them without the evidence is precisely the failure `ActivationGateReadiness` exists to announce.
5. **Only then** register a production `DecisionEngine` bean and flip
   `amsha.routing.intent-decision-router.enabled`. Both are §27-governed changes and each requires
   its own review.

Reaching step 5 without steps 1–4 is not a configuration mistake, it is a governance failure, and
the code is built to make it loud rather than silent.

### 8.5 Review trigger

This section is re-reviewed when **any** of the following occurs, and the review is recorded here
with a date and a disposition:

* the Quality gate produces or refreshes comparative evidence;
* any lane's Governance status changes (for example a provider publishes new retention terms);
* the Operations approvals are granted or withdrawn;
* **90 days elapse since the last recorded review** — the time-based trigger. **Not currently in
  force**: the cadence is a proposal awaiting Committee confirmation, so no review window has begun
  and this trigger is inert. See the open item below.

Last reviewed: **2026-10-03**. Disposition: **remain Deferred** — Protocol MET; Governance, Quality and
Operations UNMET (8.2).

**Open item — the review cadence is unconfirmed.** The 90-day cadence above is a proposal, not
policy. Recording that here rather than quietly assuming it removes a circular dependency: §8.2
refers to "a review window", and a window cannot be derived from a cadence that does not yet exist.
Two consequences, both stated rather than left implicit:

* **No review window is in force.** §8.2 evidence therefore does not lapse under the window rule.
  Protocol remains MET on its 2026-10-01 re-verification, and no other gate's status is affected —
  this is the honest current state, not a renewal of the evidence's validity.
* **The event-driven triggers above do still apply.** New evidence, a governance change, or an
  approval moving will prompt a review regardless of the cadence.

**Committee decision required:** confirm or replace the 90-day cadence. Until then the time-based
staleness check cannot run, and this line is the standing record that it has not been confirmed.

### 8.6 Deferral must terminate in one of two directions

The realistic outcome is not necessarily activation. If the hosted lane's retention, residency, or
training-use terms prove unacceptable, then the honest resolution is to **abandon the hosted lane**
and retain the self-hosted seam (`laya` / `kev`, Apache-2.0) as the only supported path — or to
abandon the decision plane entirely and record that as a closed, negative outcome.

A negative outcome is a legitimate completion. What is not legitimate is a decision that stays
Deferred because nobody was required to revisit it. If no gate has moved by the next recorded
review, the required action is an explicit disposition — promote, narrow to self-hosted, or close —
not another checkpoint.

### 8.7 What promotion does not authorise

Even when every gate is met and the plane activates:

* A decision model may escalate, never downgrade. It must not relax an existing block, bypass HITL,
  or weaken deterministic policy.
* Shadow classifiers and the intent router remain separately gated; satisfying one does not enable
  the other.
* Credentials are still never resolved from the configuration surface alone, and
  `api-key-ref` remains a `SecretVault` reference rather than a literal.
* Rollback remains configuration-only: one flag, no data migration.

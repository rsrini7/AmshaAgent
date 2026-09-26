# ADR: Jev Decision Plane for Typed Agent Judgments

- **Status**: Proposed
- **Date**: 2026-09-22
- **Decision Owners**: Amsha Architecture Committee
- **Related Invariants**: `docs/Architecture_Invariants.md` §4.12, §7 parallel tool execution, §7.13.2–7.13.4; `AGENTS.md` §1.18, §8
- **Feature Slug**: `jev-decision-plane`
- **Issue Tracker**: `.scratch/jev-decision-plane/`
- **Research Basis**: `docs/research/Jev-Amsha-Decision-Plane-Scout-2026.md`

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

*Scope Deferred to Issue 06+*:
Remote provider boundary and outbound controls (Issue 06) and the low-risk routing pilot and final closeout (Issue 07) remain pending. The master ADR status remains **Proposed**.

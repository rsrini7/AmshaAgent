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

*Scope Deferred to Issue 02+*:
Defensive transaction boundary checks (preventing evaluation inside active JTA write transactions), in-process ONNX adapters, self-hosted and remote adapters, policy threshold integration, shadow evaluation, and routing pilot remain scoped to Issues 02–07. The master ADR status remains **Proposed**.

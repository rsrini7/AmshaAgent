# Jev/System One scout for Amsha

Date: 2026-09-22

**Status:** Proposed — architecture scout; no runtime adoption decision

**Role:** Compare Jev/System One with Amsha's existing decision and classifier
control plane; identify a safe evaluation seam.

> **Superseded in part (2026-09-27).** The architecture this scout recommends was accepted as
> [ADR-jev-decision-plane](../adr/architecture/ADR-jev-decision-plane.md) with **`Status: Deferred`**: the
> seam, adapters, policies and validators are implemented, but no production `DecisionEngine` bean is
> registered, so the plane is inert. This document remains the *research basis*; where the two disagree, the
> ADR and `docs/Architecture_Invariants.md` §27 govern.

## Executive recommendation

Adopt the useful part of the Jev approach as an Amsha **decision plane**, but do
not replace Amsha's guardrails or agent loop with Jev.

The decision plane should expose one small, typed seam for bounded judgments:

```text
bounded state + registered question
    -> Choice / Score / Noul + confidence/probabilities or NoOpinion
    -> deterministic Amsha policy
    -> route, review, retry, allow, block, or finish
```

Jev is a possible adapter behind that seam. The first production adapter should
be the existing in-process classifier path (including ONNX), with Jev used in
shadow mode and only later enabled for low-risk advisory decisions. A remote
Jev call must never become the source of truth for authorization, HITL, budgets,
workspace isolation, persistence, or tool execution.

This is an evolutionary fit because Amsha already follows the most important
Jev principle: a classifier may increase risk, while deterministic application
code owns the consequential action. The main gap is that Amsha's active
classification vocabulary is mostly labels and one scalar confidence; it lacks
a first-class representation for independent questions, calibrated abstention,
ordered scores, and probability distributions.

## What the Jev material contributes

The supplied Jev note describes three bounded question primitives:

| Primitive | Meaning | Useful Amsha shape |
|---|---|---|
| `Choice` | Select one item from a closed set | route, persona, model tier, tool candidate |
| `Score` | Place a subject on an ordered rubric | risk, urgency, answer quality, relevance |
| `Noul` | Probability that a proposition is true | explicit intent, escalation need, policy concern |

Questions in one request are intended to be independent and evaluated against
the same state. Application code combines them and applies thresholds. This is
the valuable separation: the model supplies a bounded judgment; application
code owns arithmetic, threshold policy, authorization, side effects, and the
fallback path.

The Jev note also calls out constraints that matter to Amsha:

* bounded output prevents invented labels and malformed decision shape, but does
  not make the selected valid label correct;
* confidence is useful only if calibrated on Amsha's own traffic;
* uncertainty should produce review, clarification, or a stronger model, not a
  guess;
* irrelevant or hostile state can steer the judgment, so state must be reduced
  and untrusted content must be explicitly delimited;
* exact arithmetic, counting, date comparison, invariant enforcement, and
  multi-step reasoning belong in code or a reasoning model;
* the documented input is text/JSON state, not image, audio, or video input;
  Jev is a bounded decision model rather than a prose/code generator, and its
  early-access status means model names, limits, and availability may change;
* the documented `Choice` option ceiling and bounded context mean large tool
  or role catalogues need code-side candidate reduction first.

The local Java SDK reinforces the shape. `Question` is a sealed typed handle
with `type`, instructions, an optional name, and a wire representation
`jev4j Question.java` (`Question` is sealed at line 15; its type, instructions,
name, and wire methods are declared at lines 18, 21, 24, and 30).
`TypeSafeClient` offers one synchronous and one asynchronous System One call,
while request construction, transport, retry, timeout, and decoding remain
inside the client (`jev4j TypeSafeClient.java`, sync overloads begin at line 121,
async overloads at line 134; transport/retry/decoding are wired at lines 95–97).
That is a good adapter shape: callers submit typed questions and receive typed
answers rather than parsing generated prose.

> **P125 seam correction:** Any earlier reference in this scout to the
> post-policy tool-risk dispatcher as a “natural Jev seam” means an in-process
> Jev-shaped adapter only. A remote Jev call is explicitly excluded from that
> SPI and belongs only in the separate `RemoteDecisionProvider` path described
> in the review-corrections section below.

### Lessons from the wider Jev Java tree

The other local Jev projects add useful integration lessons beyond `jev4j`:

* `jev-java-2` keeps provider-specific transport behind a common client shape,
  with adapters for TypeSafe, OpenRouter, Vercel AI Gateway, and Cloudflare.
  This supports provider failover and endpoint variation without making callers
  understand each wire protocol. Its documentation also warns that some
  provider responses may omit distributions, confidence, or metadata; an
  adapter must preserve absence rather than inventing values.
* `typesafe-ai-gateway` demonstrates confidence-gated routing versus a
  `MANUAL_REVIEW` fallback. Its low-confidence and transport-failure paths both
  resolve to `MANUAL_REVIEW`; this is a useful policy pattern, not three
  independent destinations. Its mock downstream actions are not a substitute
  for Amsha's authoritative tool guard and HITL path.
* `jev-java-3` uses `QuestionKey<A extends Answer>` handles so the answer type is
  paired with the exact question that produced it. Amsha should use an
  equivalent typed question handle or a registry key that carries the expected
  primitive and version; string-only result maps invite wrong-question and
  wrong-answer casts.
* `hello-jev-java` shows that a thin JDK `HttpClient` adapter is feasible without
  importing a large SDK. That is an option for keeping the Jev adapter optional,
  but it does not remove Amsha's requirements for timeout, retry, secret
  handling, redaction, native reflection, and outbound-network governance.

These projects are implementation references and community code, not proof of
provider compatibility or production availability. Amsha should define its own
provider-neutral contract and test each adapter with local HTTP contract tests.

The Jev/System One note is a supplied research artifact, not an Amsha
dependency decision. Its vendor speed, cost, and calibration claims should be
treated as hypotheses until measured against Amsha traffic. See the note's
limitations and sources in the supplied Jev/System One research note.

## What Amsha already has

Amsha is already stronger than a simple “LLM chooses an action” design in the
places that matter for safety:

| Existing Amsha module | Current responsibility | Relationship to Jev |
|---|---|---|
| `TaskClassifierService` | Keyword-based domain detection and role mapping | A deterministic baseline; useful as a fallback and candidate reducer, but not a calibrated judgment ([source](../../core/src/main/java/com/amsha/core/agent/loop/TaskClassifierService.java)) |
| `IntentDetectorService` | Fast-path rules plus structured LLM role classification, timeout, sanitization, and fallback | Current generative/structured classifier; Jev could become a typed advisory adapter, not an unconditional replacement ([source](../../core/src/main/java/com/amsha/core/routing/IntentDetectorService.java)) |
| `ModelRouter` | Routes lightweight versus specialist goals | Best first low-risk Jev experiment; current heavy-domain decision uses keyword classification ([source](../../core/src/main/java/com/amsha/core/routing/ModelRouter.java)) |
| `ClassifierResult` | Closed `Opinion`/`NoOpinion` contract with bounded confidence and failure reasons | Already the right fail-open observational contract, but too narrow for Choice/Score/Noul distributions ([source](../../classifier-api/src/main/java/com/amsha/classifier/api/ClassifierResult.java)) |
| `ToolInvocationGuard` | Authoritative parameter, admission, idempotency, autonomy, HITL, and post-policy checks | Must remain the policy owner; Jev may only add risk or request approval ([source](../../core/src/main/java/com/amsha/core/agent/loop/ToolInvocationGuard.java)) |
| Post-policy tool-risk dispatcher | Optional classifier after the authoritative guard; `BLOCK`/`REQUIRE_APPROVAL` can increase risk, `NoOpinion` preserves allow | Natural seam for an in-process adapter only (see P125 correction); a remote call must not silently create a new unapproved outbound path ([invariants](../Architecture_Invariants.md)) |
| Answer-quality dispatcher | Runs only after authoritative answer acceptance and has one retry budget before a tool boundary | Good Score/Noul use case; it must retain the current one-retry and no-reentrant-side-effect rules ([invariants](../Architecture_Invariants.md)) |
| `PreflightState`, `LoopStuckDetector`, `FINISH` protocol | Exact loop budget, stuck-loop detection, terminal sequencing | Jev is the wrong tool here; these are deterministic state-machine invariants ([guardrails](../Architecture_Invariants.md)) |

The important conclusion is that Amsha already has the surrounding **control
plane**. Jev would add a better **judgment vocabulary**, not a new agent
orchestrator.

## Comparison

| Concern | Current Amsha | Jev/System One | Enhanced Amsha direction |
|---|---|---|---|
| Output shape | Role strings, structured intent, scalar classifier confidence | Typed Choice/Score/Noul, probabilities, confidence | Keep a closed typed decision result and add primitive-specific values |
| Uncertainty | `NoOpinion` exists for optional classifiers; active intent generally falls back to coordinator | Explicit probability and confidence; middle band can go to review | Make abstention and review a first-class policy outcome |
| Question composition | Classifier prompt or keyword set often bundles routing | Independent atomic questions in one request | Registry of atomic, versioned decision questions; combine only in deterministic policy |
| Safety authority | Deterministic guard, HITL, autonomy, parameter validation | Caller owns workflow in the Jev model | Preserve Amsha ownership; decision adapter has no side effects |
| Candidate reduction | Keyword domains and role registries | Closed choices, with an option ceiling | Reduce roles/tools/models in code before asking a bounded question |
| Local/provider-neutral inference | ONNX shadow classifiers and runtime control | Self-hosted wire-compatible providers such as Laya/Kev, or remote TypeSafe-compatible adapters | Local adapter first; self-hosted providers require explicit isolation; remote Jev remains opt-in and shadow-only initially |
| Failure mode | Deterministic fallbacks and `NoOpinion` | SDK/API errors, timeout, rate limit, model uncertainty | Map transport/runtime failures to `NoOpinion`; never infer approval from absence |
| Observability | Payload-free traces, classifier leases, metrics | SDK has request/response diagnostics but state is sensitive | Emit decision ID, version, outcome class, confidence bucket, latency; never state or raw payload |
| Evaluation | Focused classifier and guard tests | Vendor examples and published claims | Shadow compare accuracy, calibration, abstention, latency, cost, and false-allow rate |

## Proposed decision plane

### The seam

Add a small interface in the classifier-facing contract area. The exact names
can change during implementation, but the shape should remain deep:

```java
interface DecisionEngine {
    DecisionResult evaluate(DecisionRequest request);
}
```

`DecisionRequest` should contain a registered decision ID/version, a bounded
state projection, a closed question specification, a deadline, and a
sensitivity classification. `DecisionResult` should be sealed and contain:

* `Opinion`: a typed `Choice`, `Score`, or `Noul` value, confidence, and
  probability data where the primitive supports it;
* `NoOpinion`: reuse Amsha's existing `ClassifierResult.NoOpinionReason`
  vocabulary—disabled, missing model, timeout, overload, circuit open, low
  confidence, or inference error. Treat malformed/invalid provider responses as
  `INFERENCE_ERROR` unless a reviewed enum extension is required;
* provider/model metadata suitable for bounded metrics, but no prompt or state
  payload.

The interface must not expose a tool executor, workspace mutator, approval
writer, persistence port, or free-form answer. That keeps the module deep:
provider-specific transport, retries, schema decoding, calibration, and
redaction stay behind one seam, while policy callers receive one stable result.

The existing `ClassifierResult` can remain the compatibility contract for the
current SPIs. A new decision result should either extend it carefully or sit
beside it; do not weaken existing `NoOpinion` semantics to fit Jev.

> **P125 evidence correction:** The local gateway is only evidence for
> confidence-gated routing to a manual-review fallback. Its low-confidence and
> transport-failure paths both resolve to `MANUAL_REVIEW`; it is not evidence
> for three independent destinations or for production Amsha policy.

### Deterministic policy after judgment

Each decision site should have a small policy module that consumes the result.
For example:

```text
DecisionEngine -> route-intent opinion
             -> IntentPolicy (thresholds, role registry, continuity rules)
             -> role or coordinator fallback

DecisionEngine -> tool-risk opinion
             -> ToolRiskPolicy (existing guard result is authoritative)
             -> allow, approval, or block
```

The policy layer must encode Jev's semantic traps explicitly:

1. **Noul is proposition probability, not degree.** A Noul value of `0.4`
   means uncertainty about whether the proposition is true; it does not mean
   “40% good”, “40% risky”, or “40% complete”. Use an ordered `Score` rubric for
   magnitude such as answer quality, severity, or urgency.
2. **Do not transfer probabilities across primitives.** A Noul probability is
   not the same quantity as a `Choice` probability for a `yes` label. A `Score`
   returns a score, probabilities, and confidence with distinct semantics.
   Thresholds must be typed and owned by the policy for that primitive.
3. **Do not assume complementary questions are complements.** Independently
   asking a proposition and its negation does not guarantee that their returned
   values sum to `1.0`. Ask one canonical proposition and apply deterministic
   negation in code when needed. Never derive a missing result by subtraction.
4. **Preserve missing distributions and confidence.** Provider omission,
   timeout, malformed response, or low confidence maps to `NoOpinion` or the
   configured review path; it must not become zero, `false`, or an invented
   uniform distribution.

Thresholds belong to the policy module, not the Jev question or provider
adapter. Use three bands for consequential fuzzy decisions:

```text
high confidence  -> apply the permitted policy branch
middle confidence -> review / stronger model / clarification
low confidence    -> deterministic fallback or NoOpinion
```

The policy must also encode monotonicity: a higher-risk opinion may escalate a
decision, never downgrade an existing block or bypass HITL. This directly
preserves the current ToolInvocationGuard invariants.

### Native and concurrency constraints

Any new decision DTO, answer record, sealed result, or provider adapter type
must follow Amsha's central reflection configuration rule. Add accessible types
to `classifier-api/src/main/java/com/amsha/classifier/api/ClassifierApiReflectionConfig.java`
or the owning module's reflection config; use `classNames` for private/nested
or dependency types. Do not add type-level `@RegisterForReflection` to each
application DTO.

`DecisionEngine.evaluate()` must run outside `@Transactional`; a remote Jev
call is network I/O and must not be placed inside a persistence write. If an
async decision is ever workspace-aware, use the existing context-aware
executor/scope propagation rules. A provider adapter must not bypass the
`ToolInvocationGuard` merely because its result is typed.

### Registered decision questions

Do not let the main agent invent production questions at runtime. Register
questions as versioned code/configuration records with:

* stable decision ID and semantic version;
* primitive and closed labels/rubric;
* exact state projection and redaction rules;
* criteria and known counterexamples;
* threshold policy and cost-of-error classification;
* allowed caller and fail mode;
* evaluation corpus and calibration status.

This addresses Jev's literal-reading and adversarial-state weaknesses while
making prompt/question changes reviewable like policy changes.

## Where the enhanced approach fits

### 1. Model and persona routing — first candidate

Use code to identify continuity commands, explicit commands, and a reduced set
of eligible roles/models. Ask a bounded `Choice` for ambiguous natural-language
goals and a `Noul` for “is specialist reasoning required?”. The existing
keyword route remains a fallback. A middle band routes to the current
coordinator or existing intent classifier rather than making a risky specialist
selection.

This improves on the current `TaskClassifierService` without putting a remote
model on the critical tool path.

### 2. Answer quality — second candidate

Ask atomic `Score`/`Noul` questions such as “does the answer address the stated
goal?” and “does it claim completion without evidence?”. The existing
`AnswerQualityDispatcher` remains authoritative for whether the one retry may
be consumed. This is a good place to measure calibration because the outcome is
already bounded by the current retry contract.

### 3. Tool-risk advisory — later, not first

After the existing guard has completed parameter validation, admission,
autonomy, idempotency, and HITL checks, a local decision adapter may score
additional semantic risk. It may only return `NoOpinion`, allow, block, or
require approval according to the existing monotonic rules.

A remote Jev adapter should not be called in this path until the outbound-call
semantics are explicitly designed. Otherwise, asking a remote classifier to
decide whether a network-capable tool is safe can itself create network I/O
before the tool's approval boundary.

### 4. Tool/skill selection — after code-side filtering

Use deterministic capability and role registries to reduce the candidate list;
then use `Choice` only among admissible candidates. Jev can help with semantic
fit, but cannot grant capability, `host_exec`, network access, or an approval
lease. Unknown, unadmitted, or malformed candidates remain blocked by the
existing guard.

### 5. What should stay deterministic

Do not use Jev for global iteration limits, stuck-loop detection, `FINISH`
sequencing, authorization, workspace scope, secret handling, exact arithmetic,
date ordering, persistence invariants, duplicate suppression, or tool
parameter validation. These are Amsha's strongest assets and match Jev's own
documented limitations.

## Recommended implementation sequence

1. **Decision corpus and registry.** Capture representative, redacted cases
   for intent routing, model routing, answer quality, and tool-risk advisory.
   Define expected choices, abstention bands, and false-allow as the primary
   safety metric.
2. **Contract-only addition.** Add the typed decision result and registry
   contract in the classifier-facing module. Add an in-memory adapter and
   reflection coverage; do not alter active routing.
3. **Shadow evaluation.** Run current keyword/LLM/ONNX decisions and the new
   adapter side by side. Record bounded outcome metadata only. Reuse the
   existing shadow-classifier lifecycle rules: disabled, missing, timeout, and
   inference failure must preserve current behavior.
4. **Low-risk activation.** Enable the decision plane for model-tier or
   coordinator routing only, behind a feature flag and per-decision rollout.
   Require a review band and deterministic fallback.
5. **Answer-quality activation.** Add Score/Noul policies behind the existing
   single retry budget and tool-boundary checks.
6. **Tool-risk advisory.** Consider only after proving local or explicitly
   authorized inference, payload hygiene, latency, and monotonic escalation.
7. **Provider choice.** Evaluate Jev against the local ONNX and current
   structured-LLM adapters. Keep Jev optional: the decision plane must remain
   useful when the external provider is unavailable or not approved.

## Evaluation gate

Before enabling any decision in the active path, compare at least these four
implementations on the same redacted corpus:

1. current deterministic/keyword baseline;
2. current structured LLM or ONNX classifier;
3. Jev/System One adapter;
4. a deterministic fallback policy with abstention.

Measure per decision ID and version:

* accuracy and confusion matrix for closed choices;
* calibration (reliability curve, Brier score or equivalent) for probabilities;
* abstention rate and accuracy in the high-confidence band;
* false allow, false specialist route, and false completion claims;
* p50/p95 latency, timeout rate, retry rate, and cost;
* sensitivity to prompt/state perturbation, negation, injected instructions,
  irrelevant context, malformed/partial state, and duplicate identifiers.

The release gate should prefer a slightly higher abstention rate over a higher
false-allow rate for security, tool, and authorization-adjacent decisions.
Vendor-published speed/cost multipliers are not substitutes for this gate.

## Final assessment

Jev is not a new agent architecture for Amsha. It is a potentially useful
**typed judgment adapter** that can make existing fuzzy decision points more
explicit, independently testable, and calibration-aware.

Amsha should therefore add the decision plane as a deep module, keep
`ToolInvocationGuard` and the state machine as the authoritative control plane,
and introduce Jev through shadow evaluation. The highest-value first slice is
ambiguous intent/model routing, followed by answer-quality scoring. Tool-risk
and capability decisions should come later, only as monotonic advisory
signals. This yields the Jev benefit—small, bounded decisions in code—without
trading away Amsha's existing fail-closed and HITL invariants.

## P125 review corrections and research extensions

The P125 review was checked against the Amsha source tree and the local Jev
projects. The following constraints supersede any earlier wording in this
scout that is less specific.

### Two distinct decision seams

Amsha's existing classifier SPIs are synchronous, in-process, and network-free.
`PostPolicyToolRiskInterceptor`, `AnswerQualityInterceptor`, and shadow
classifier implementations must not perform network I/O, log or retain
sensitive payloads, or create side effects. Therefore:

```text
InProcessDecisionEngine
    -> classifier-api / ONNX / shadow lifecycle
    -> synchronous advisory result

RemoteDecisionProvider
    -> explicit orchestration or offline evaluation path
    -> transport/retry/timeout/outbound policy
```

There is a third provider category: a self-hosted, wire-compatible decision
engine such as the Laya or Kev alternatives described in the companion note.
When embedded in the Amsha process, it may use the in-process seam. When it is
a separate loopback process, model it as `SelfHostedDecisionProvider`: keep the
endpoint local-only, enforce redaction and payload-retention rules, and do not
assume that loopback networking is automatically safe. Laya's local-only,
unauthenticated binding is an explicit deployment constraint, not a production
default.

The remote provider is not an implementation of the in-process SPI. In
particular, Jev must not be added as a remote `PostPolicyToolRiskInterceptor`
or remote `AnswerQualityInterceptor`. Tool-risk and answer-quality hooks may
consume only an in-process adapter result; a remote experiment belongs in
shadow evaluation or an already-authorized model-orchestration path. A remote
decision cannot be used to create the authorization that would permit its own
network call.

The governing Amsha references are [parallel tool execution and classifier
ordering](../Architecture_Invariants.md#7-parallel-tool-execution), [strict
terminal finish rules](../Architecture_Invariants.md#38-strict-terminal-finish-rules),
[agent-loop guards](../Architecture_Invariants.md#412-agent-loop-guardrails-invariant-agentloopguards),
and [dynamic task routing](../CONFIGURATION.md#dynamic-task-routing-saw-pattern).

### Existing Amsha lifecycle: what is new and what is reused

The proposal is not greenfield. Amsha already supplies most operational
machinery:

| Existing capability | Reuse | Genuinely new decision-plane work |
|---|---|---|
| `ClassifierResult.Opinion` / `NoOpinion` | Preserve bounded confidence, `ruleId`, and failure reasons | Add primitive-specific Choice/Score/Noul value contracts only where needed |
| `ClassifierShadowDispatcher` and shadow evaluation services | Reuse payload-free observation and failure isolation | Register a Jev-compatible adapter as another shadow implementation |
| `OnnxClassifierRuntimeControl` | Reuse per-classifier enablement, readiness, leases, draining, and kill switch | Define provider-neutral decision IDs and rollout configuration |
| `OptionalClassifierBeanResolver` | Reuse optional CDI discovery and ordering | Add adapters without making the external provider mandatory |
| `PostPolicyToolRiskDispatcher` / `AnswerQualityDispatcher` | Preserve monotonic escalation and the single retry budget | Feed only in-process decision outcomes into these existing policies |
| Reflection configuration and native build rules | Extend the owning module's central config | Register new DTOs/results centrally; no type-level shortcut annotations |

`ruleId` should remain the classifier/provider rule identity. A new registered
`decisionId` plus question/policy version should identify the decision schema;
do not overload `ruleId` with schema version or replace it. The compatibility
adapter may populate `ruleId` when it truly has a classifier rule, while the
new result carries decision identity separately.

### Question fan-out and candidate reduction are semantic constraints

The supplied Jev notes document a `Choice` option ceiling of 255. They also
report approximately 32,768 tokens for a question branch and approximately
65,536 tokens for a whole request from independent probing, not vendor-confirmed
hard limits. Treat those figures as observed planning guidance and verify the
current provider contract before implementation. The notes do not establish a
general question-count or question-text-to-state cost threshold, so Amsha must
measure fan-out latency and token cost on its own workload.

Candidate reduction is not merely a way around the 255-option ceiling. Adding or
removing an option can change the probabilities of options that remain. The
registry therefore needs an explicit `CandidateReductionPolicy` containing:

* the deterministic eligibility rule and its rationale;
* the exact candidate set presented to the model;
* the state projection and question criteria used for that set;
* evaluation data for every supported shortlist shape.

The companion alternatives note gives two mechanisms behind this constraint:
Laya packs option text into a fixed token budget, so long option lists can make
labels indistinguishable; Kev reproduces option interaction even while keeping
questions isolated from one another. The 255 ceiling is therefore both a
validation limit and a semantic-quality concern.

Facts must not be moved casually between state and question criteria. The
companion evidence specifically shows that relocating a fact between shared
state and a sibling question can materially change the result, while the
option-interaction experiment shows that changing a shortlist can shift the
probabilities of options that remain. State projection and criteria should
therefore be separate, versioned parts of the question contract. A shortlist
change is a semantic decision requiring recalibration, not a harmless
optimization.

`Score` answers may also contain a probability distribution and confidence. The
notes describe `Score` as placing state on an ordered rubric and returning a
score, probabilities, and confidence. The scalar score and the probability
distribution are separate quantities; neither should be compared numerically
with a Noul or Choice probability.

### Typed absence and provider maturity

The Jev adapter contract must represent absent confidence, distributions,
legends, usage, or provider metadata as absent. One local `jev4j` decoder path
uses Jackson `MissingNode.asDouble()` for absent confidence, which materializes
missing data as `0.0`; Amsha must not copy that behavior. The `jev-java-2`
model's `OptionalDouble` and its explicit missing-data handling are the safer
reference shape. This is an SDK implementation observation, not a claim about
the official TypeSafe service.

The local projects also have different evidence weight:

| Project | Evidence weight for Amsha design |
|---|---|
| `jev-java-2` | Multi-module SDK with provider adapters, contract tests, and explicit optional metadata; strongest local adapter reference |
| `jev4j` | Useful typed client and sealed questions, but inspect decoder semantics before copying |
| `jev-java-3` | Strong typed-key idea and response validation; independent community SDK, not a provider guarantee |
| `typesafe-ai-gateway` | Demonstration of confidence gating; its confident route and fallback path both resolve to `MANUAL_REVIEW`, so it is not three distinct destinations |
| `hello-jev-java` | 57-line zero-dependency transport demonstration; no library-grade test or lifecycle evidence |

The gateway lesson should therefore be stated as “confidence-gated route versus
manual-review fallback,” not a three-way routing implementation. The gateway's
criteria and threshold are hard-coded demo values and should not be treated as
Amsha policy.

### Acceptance, rollback, and open ownership

Before active adoption, the decision plane must satisfy these gates:

1. The in-process adapter passes malformed, partial, missing, wrong-primitive,
   duplicate-question, unknown-reference, and adversarial-state tests.
2. Remote providers are absent from network-free classifier SPIs and are
   disabled by default; provider failure preserves current Amsha behavior.
3. The provider's data location, retention window, training-use policy, and
   deletion/tenant isolation guarantees are reviewed before use. Regulated or
   secret-bearing state is excluded from a remote projection; self-hosted
   deployments must document local exposure and retention.
4. Shadow results are payload-free and bounded to decision ID/version,
   outcome class, confidence bucket, latency, and failure reason.
5. Active rollout is per decision ID with a kill switch, deterministic fallback,
   review band, timeout budget, and explicit cost ceiling.
6. Tool-risk decisions remain monotonic: a decision may escalate to block or
   approval, never downgrade an existing block or bypass HITL.
7. The comparison corpus demonstrates calibration, abstention behavior, false
   allow rate, latency, and cost against the current keyword, ONNX, and
   structured-LLM baselines.

Rollback is disabling the decision registration or provider lease and returning
to the existing classifier/policy path; no data migration or prompt rewrite
should be required. Runtime-control integration must preserve the current
disable-and-drain behavior. Implementation ownership and a target latency/cost
budget are not assigned by this scout and must be named before an ADR or code
change is opened.

## Sources

### Local Amsha sources

* [TaskClassifierService.java](../../core/src/main/java/com/amsha/core/agent/loop/TaskClassifierService.java)
* [IntentDetectorService.java](../../core/src/main/java/com/amsha/core/routing/IntentDetectorService.java)
* [ModelRouter.java](../../core/src/main/java/com/amsha/core/routing/ModelRouter.java)
* [ClassifierResult.java](../../classifier-api/src/main/java/com/amsha/classifier/api/ClassifierResult.java)
* [ToolInvocationGuard.java](../../core/src/main/java/com/amsha/core/agent/loop/ToolInvocationGuard.java)
* [Architecture Invariants](../Architecture_Invariants.md)
* [Configuration: dynamic task routing](../CONFIGURATION.md)
* [PostPolicyToolRiskInterceptor.java](../../core/src/main/java/com/amsha/core/agent/loop/PostPolicyToolRiskInterceptor.java)
* [AnswerQualityInterceptor.java](../../core/src/main/java/com/amsha/core/agent/loop/AnswerQualityInterceptor.java)
* [ClassifierShadowDispatcher.java](../../core/src/main/java/com/amsha/core/classifier/ClassifierShadowDispatcher.java)
* [OnnxClassifierRuntimeControl.java](../../local-ml-onnx/src/main/java/com/amsha/localml/onnx/OnnxClassifierRuntimeControl.java)
* [OptionalClassifierBeanResolver.java](../../core/src/main/java/com/amsha/core/classifier/OptionalClassifierBeanResolver.java)
* [ClassifierApiReflectionConfig.java](../../classifier-api/src/main/java/com/amsha/classifier/api/ClassifierApiReflectionConfig.java)
* [ONNX in-process inference ADR](../adr/domain/ADR-ONNX-In-Process-Local-ML-Inference.md)

### Jev/System One sources

* Supplied Jev/System One research note (workspace input).
* `jev4j` README, `Question.java`, `TypeSafeClient.java`, and `AnswerDecoder.java`.
* `jev-java-2` README, `docs/protocols.md`, `ChoiceAnswer.java`, and `EvaluationCodec.java`.
* `jev-java-3` `QuestionKey.java` and `QuestionSet.java`.
* `typesafe-ai-gateway` `TypeSafeRouterService.java`.
* `hello-jev-java` `HelloJev.java`.
* Supplied companion Jev internals/alternatives research note; its limits and
  benchmark observations are treated as third-party evidence, not vendor
  guarantees.
* Official links collected in the supplied note: [TypeSafe documentation](https://docs.typesafe.ai/), [primitives](https://docs.typesafe.ai/primitives), [API](https://docs.typesafe.ai/api), and [System One announcement](https://typesafe.ai/blog/introducing-system-one-models-and-jev).

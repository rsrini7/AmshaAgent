# Amsha Classifier API Module (`amsha-classifier-api`)

Pure SPI contracts for shadow classifiers **and the Jev decision plane** in Amsha Agent. Extracted from `amsha-core` (stream S6 of the core-subsystems extraction; see [ADR-extract-core-subsystems](../docs/adr/architecture/ADR-extract-core-subsystems.md), including the §8 deviation note).

`amsha-classifier-api` contains **zero dependencies on `amsha-core`**. The production implementations (ONNX shadow classifiers) live in `amsha-local-ml-onnx`; the dispatchers that invoke them remain in `amsha-core`. The decision-plane seam is contracts-only in the same way: this module ships no `DecisionEngine` bean.

## Core Contracts

| Type | Purpose |
|---|---|
| `ShadowClassifier` | Base SPI for pluggable shadow classifiers |
| `ToolRiskShadowClassifier` (+ `ToolRiskShadowContext`, `ToolRiskPrediction`, `ToolRiskAction`) | Pre-tool-boundary risk elevation signals (`REQUIRE_APPROVAL` or `BLOCK`) |
| `AnswerQualityShadowClassifier` (+ `AnswerQualityShadowContext`, `AnswerQualityPrediction`, `AnswerQualityAction`) | Post-answer quality signals; may consume one retry only before a tool boundary |
| `IntentShadowClassifier` | Intent shadow classification for routing hints |
| `PostPolicyToolContext` | Input context for post-policy tool risk evaluation |
| `ClassifierResult` | Uniform result wrapper with confidence and provenance |

## Tool-risk operation context

`ToolRiskShadowContext` and `PostPolicyToolContext` carry the canonical `ToolOperation` selected by the deterministic tool registry. Classifiers should use `requiredOperation` as the operation category; `toolName` remains the concrete tool identifier. The decision guard's mandatory HITL behavior remains authoritative.

## Jev Decision Plane Seam

This module also carries the typed, provider-neutral seam described in
[ADR-jev-decision-plane](../docs/adr/architecture/ADR-jev-decision-plane.md) — **32 types**, added as one
coherent slice. The ADR status is **Deferred**: the vocabulary, adapters, policies and validators are complete
and tested, but **no production `DecisionEngine` bean is registered**. Enabled shadow classifiers therefore
return `NoOpinion(MISSING_MODEL)`; disabled classifiers return `DISABLED`, and the intent router returns a
`DISABLED` fallback while no engine resolves. See
[Architecture_Invariants](../docs/Architecture_Invariants.md) §27 for the invariants that pin that posture.

### Evaluation entry point

| Type | Purpose |
|---|---|
| `DecisionEngine` | The seam — a `@FunctionalInterface` with one method, `DecisionResult evaluate(DecisionRequest)`. Concentrates transport, decoding, redaction and failure mapping. `category()` defaults to `IN_PROCESS` |
| `DecisionRequest` (+ `.Builder`) | Registered decision id/version, bounded state projection, typed question specification, deadline, sensitivity and retention classification |
| `DecisionResult` (sealed: `.Opinion`, `.OpinionBuilder`, `.NoOpinion`) | Typed opinion plus confidence/probability where present, or a `NoOpinion` carrying a `ClassifierResult.NoOpinionReason` |

### Typed vocabulary

| Type | Purpose |
|---|---|
| `Choice` | One selected label from an admissible closed set (255-option ceiling); missing probabilities stay absent |
| `Score` | Ordered-rubric placement; scalar score, level distribution and confidence remain distinct |
| `Noul` | Proposition probability in `[0.0, 1.0]` — degree of affirmation, never severity or completion. `isAffirmed(threshold)` applies an application policy threshold |
| `QuestionKey` | Identity-safe typed handle; a caller retrieves only the answer type bound to its own handle |
| `DecisionPrimitive`, `DecisionAnswer`, `DecisionQuestion` | Question/answer specification; `DecisionAnswer` is the sealed root of `Choice`/`Score`/`Noul` |
| `DecisionMetadata` | Provenance (`ruleId`, `providerId`, `modelId`, latency, token counts) |
| `RedactionPolicy`, `RetentionPolicy` | Declared handling of projected state |
| `ReviewBand`, `FailMode`, `ProviderCategory` | Threshold band, fail behaviour, and adapter category |

### Definitions and policies

| Type | Purpose |
|---|---|
| `DecisionDefinition` | Registered id, schema/policy version, rubric, projection, criteria, bands, allowed adapters, fail mode |
| `CandidateReductionPolicy` | Declared candidate-reduction rule and rationale |
| `IntentDecisionPolicy`, `ToolRiskDecisionPolicy` | Amsha-owned deterministic policy tables. Policies own thresholds, review bands, authorization and fallback — never the provider |
| `IntentPolicyResolution`, `IntentDecisionAction` | Router-facing resolution (`DIRECT_ROUTE` / `SECONDARY_REVIEW` / `DETERMINISTIC_FALLBACK`) |
| `StateProjectionPolicy` | Allow-listed projection plus redaction before crossing an external or separate-process boundary; an empty allow-list is rejected, so it fails closed |
| `TransactionViolationPolicy`, `DecisionTransactionGuard` | Enforce "no evaluation inside a JTA write transaction". The guard treats an undeterminable status as `STATUS_ACTIVE` — it fails closed |

### Adapters and providers

| Type | Purpose |
|---|---|
| `InProcessDecisionEngine` | Marker for a synchronous, network-free in-process engine |
| `InMemoryDecisionEngine` (+ `.Builder`) | Deterministic reference implementation over immutable answers, raw payloads or declarative failure simulation. Accepts no executable callbacks, so it has no side effects |
| `SelfHostedDecisionProvider`, `RemoteDecisionProvider` | Separately hosted (loopback) and remote provider categories |
| `DecisionPayloadDecoder` | Decodes raw provider payloads into typed results. Missing fields stay absent (no invented zero/false/confidence/uniform distribution); malformed, partial, wrong-type or unknown-handle input fails closed to `INFERENCE_ERROR` |
| `ProviderPlacementValidator` | Rejects a `RemoteDecisionProvider` / `SelfHostedDecisionProvider` placed at a classifier SPI, and requires an engine to implement `InProcessDecisionEngine` and declare `IN_PROCESS`. Throws `SecurityException` |
| `ClassifierApiReflectionConfig` | Native-reflection registration for every new record and sealed type |

## Behavioral Rules

- Shadow beans never register as active implementations; classifier controls distinguish requested from effective state.
- Optional absence or failure must preserve the deterministic decision without logging payloads.
- **The decision plane is Deferred and fails closed.** With no `DecisionEngine` bean registered, enabled
  shadow classifiers return `NoOpinion(MISSING_MODEL)`, while disabled classifiers return `DISABLED`.
  `IntentDecisionRouter.isEnabled()` is false and its evaluation returns a `DISABLED` fallback. Do not add a
  production `DecisionEngine` bean without reopening the ADR.
- **The explicit evaluation path is accepted and implemented in `core` and `app`.** It builds the
  configured engine transiently behind the default-off, internal-only evaluation switch and never
  registers a bean, so the Deferred rule above still holds. Placement rules here are unchanged: a
  remote or self-hosted provider is still forbidden from a classifier SPI.
  [ADR-jev-decision-plane-evaluation-path](../docs/adr/architecture/ADR-jev-decision-plane-evaluation-path.md)
- **Escalate, never downgrade.** A higher-risk opinion may escalate a decision
  (`BLOCK > REQUIRE_APPROVAL > ALLOW`) but may never relax an existing block or bypass HITL approval.
- **No transaction coupling.** Evaluation and provider transport never run inside a `@Transactional`
  persistence write.
- **No type-level `@RegisterForReflection`.** New records, sealed results and provider types register through
  the owning central `*ReflectionConfig`.

## Downstream Usage

```xml
<dependency>
  <groupId>com.amsha</groupId>
  <artifactId>amsha-classifier-api</artifactId>
</dependency>
```

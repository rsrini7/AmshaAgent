# Jev Decision Plane — Production Activation Plan

**Date:** 2026-10-07
**Status:** Plan. No provider activation approved; the ADR remains **Deferred** until the promotion procedure is executed.
**Implementation status:** **PR-1 landed (W1–W3) and inert.** `DecisionEngineFactory`,
`DecisionProviderGovernance`, `DecisionCredentialResolver`, `DecisionProviderUnavailableException` and the
`Slot.Governance` config group exist, with unit tests — but there is **no production caller, no egress, and
no new endpoint**. Constructing an engine is not registering one, so `Instance<DecisionEngine>` stays
unresolvable and the plane's structural inertness is unchanged. W4 onward MUST NOT be implemented before
the companion ADR is accepted. See **Appendix E** for exactly what landed.
**Scope:** Turning the deferred Jev/System One decision plane into a production-grade, provider-agnostic
decision capability — starting with an explicit, opt-in evaluation path for TypeSafe, then a gated
production routing activation.
**Supersedes nothing.** Companion to `docs/research/Jev-Provider-Integration-Plan-2026.md` (wire
contract, Phase 0 evidence) and `docs/adr/architecture/ADR-jev-decision-plane.md` (governance).

> This document is the implementation brief. It states the verified current state, the design, the
> workstreams with concrete files and tests, and the ADR/invariant changes. It is written to be handed
> to an implementer without further archaeology.

---

## 0. Verdict

The observation is correct and the code confirms it:

1. **The provider configuration is inert by design, not by oversight.** Nothing reads
   `amsha.decision.providers.*` to build a `DecisionEngine`. `RemoteProviderConfig.builder()` and
   `new RemoteDecisionEngine(...)` appear **only in tests** — there is no production caller.
2. **The intent router has a second, independent lock.** Even with an engine, direct routing needs
   `candidate-evidence-approved` **and** `cost-ceiling-approved` **and** a non-blank `rollout-owner`.
   All three are unset.
3. **The ADR gate is the real blocker.** Governance, Quality and Operations are UNMET, and the ADR
   requires remote TypeSafe calls to travel an **explicit orchestration path**, never the network-free
   classifier SPIs.

The correct production answer is therefore **not** "wire the selector into a bean". It is a
**two-capability** design:

| Capability | What it does | Gate | This plan |
|---|---|---|---|
| **Evaluation path** | Constructs the configured provider on demand, sends only allow-listed + redacted data, returns typed results and payload-free aggregate evidence. Does **not** register a CDI engine bean and does **not** route production traffic. | New ADR (Accepted) | Workstreams W1–W6, W9 |
| **Production routing** | Registers the single production `DecisionEngine` bean and flips the router switch. | Existing ADR §8.4 steps 1–5 (unchanged) | Workstreams W7, W8 |

This split is the whole point: it lets Amsha make a real, credentialed TypeSafe call and gather the
Phase 3 evidence the Quality gate demands, **without** converting the plane's structural inertness
into a configurational one.

---

## 1. Verified current state (read from the code, 2026-10-07)

### 1.1 What already exists and is sound

| Piece | Location | State |
|---|---|---|
| Typed seam | `classifier-api/.../DecisionEngine.java` | `DecisionEngine.evaluate(DecisionRequest)`; default category `IN_PROCESS` |
| Sealed results | `classifier-api/.../DecisionResult.java` | `Opinion` / `NoOpinion`, `ClassifierResult.NoOpinionReason` compatibility |
| Wire codec | `classifier-api/.../DecisionPayloadDecoder.java`, `ProviderPayloadParser.java` | Accepts the real TypeSafe shapes (`noul` key, index-keyed `legend`/`probabilities`) |
| Shared request mapper | `core/.../classifier/wire/SystemOneWireMapper.java` | Emits `POST /v1/systemone` vocabulary; refuses unrepresentable questions |
| Remote lane | `core/.../classifier/remote/RemoteDecisionEngine.java` | Governed transport, SSRF/TLS, allow-list projection, no-retry, transaction + event-loop guards, `529` mapping |
| Self-hosted lane | `core/.../classifier/selfhosted/SelfHostedDecisionEngine.java` | Strict `127.0.0.1`, loopback token, `WireProfile` |
| Placement guard | `classifier-api/.../ProviderPlacementValidator.java` | Remote/self-hosted engines **cannot** sit behind classifier SPIs |
| Selector | `core/.../config/DecisionProviderSelector.java` | Pure function; resolves a slot to `Selected`/`Unavailable`; never resolves a credential |
| Config surface | `core/.../config/DecisionProviderConfig.java` + `application.yaml:496-594` | `amsha.decision.providers.selected` + `slots.*`; inert |
| Shadow classifiers | `core/.../classifier/Decision*ShadowClassifier.java` | Observational, disabled by default, IN_PROCESS only |
| Shadow harness | `core/.../test/.../shadow/DecisionShadowComparisonHarness.java` | Deterministic baseline measured; candidates **synthetic** |
| Intent router | `core/.../routing/IntentDecisionRouter.java` | Consumes `Instance<DecisionEngine>`; double-gated |
| Diagnostics | `core/.../classifier/DecisionPlaneReadiness.java`, `core/.../routing/ActivationGateReadiness.java` | One WARN each; §27.2/§27.5 |
| Evidence metadata | `classifier-api/.../DecisionMetadata.java` | Already carries `providerId`, `modelId`, `latency`, `inputTokens`, `outputTokens` |
| Validator | `rest-http/scripts/validate_decision_plane.sh` | JEV-1..JEV-12 |

### 1.2 What is deliberately missing (and why)

| Gap | Evidence | Consequence today |
|---|---|---|
| No production `DecisionEngine` bean | `Instance<DecisionEngine>` is unresolvable; engines are `final class`es with no CDI annotation | `IntentDecisionRouter.isEnabled()` is permanently false; the plane is **structurally** inert |
| No caller for `DecisionProviderSelector` | No `DecisionProviderSelector.select(...)` call outside `DecisionProviderSelectorTest` | A `.env` change cannot activate anything |
| `api-key-ref` consumed by nobody | `DecisionProviderSelector` carries it; neither engine maps it to a credential | No credentialed TypeSafe call is possible |
| No orchestration / evaluation entry point | No admin resource, service, or task constructs an engine | There is no sanctioned way to gather Phase 3 evidence |
| Governance not declared | `RemoteProviderConfig` requires `dataLocation` + non-training retention, but nothing supplies them from config | Governance gate UNMET |
| No drift / model-identity telemetry | `DecisionTelemetry` tags are `action`/`escalated`/`fallback`/`outcome` only | Operations gate UNMET (cannot express drift) |
| Self-hosted lane has no bearer credential | `SelfHostedProviderConfig` has only `loopbackToken`; engine sends `X-Amsha-Loopback-Token` | A secured Laya/CLM expecting `Authorization: Bearer` is unreachable |
| Candidate evidence is synthetic | `DecisionShadowComparisonHarness.CandidateType` marks `REMOTE`/`SELF_HOSTED` as simulations | Quality gate UNMET |

### 1.3 The three independent locks (all must open, in order)

```
Lock 1  Structural   no DecisionEngine bean        → IntentDecisionRouter.isEnabled() == false
Lock 2  Gate         activation booleans false      → isActivationGateApproved() == false
Lock 3  ADR          Status: Deferred, gates UNMET  → no production bean may be registered
```

Lock 1 and Lock 2 are code. Lock 3 is governance. **The evaluation path opens none of them** — it
sidesteps Lock 1 by not registering a bean, and it is the instrument that produces the evidence to
eventually open Lock 3.

---

## 2. Design principles (non-negotiable)

1. **One factory, two callers.** The evaluation path and (later) the production producer both build
   engines through the same factory. No divergent construction path may exist.
2. **Structural inertness is preserved until the promotion step.** The evaluation path constructs
   engines directly and closes them; it never registers a CDI `DecisionEngine`.
3. **Remote engines never sit behind classifier SPIs.** `ProviderPlacementValidator` is authoritative;
   the evaluation path is a new, separate seam.
4. **Governance is declared in config, not inferred.** Retention, residency, training-use, tenant
   isolation and the projection allow-list are explicit, validated, and fail closed when absent.
5. **Credential refs are resolved, never stored.** `apiKeyRef` is a `SecretVault` reference; the
   resolver resolves it, the engine uses it, and the bytes are zeroed.
6. **Evidence is payload-free and versioned.** Aggregate metrics only; no prompts, states, labels or
   answers in logs, metrics, or committed evidence.
7. **A decision may escalate, never downgrade.** Unchanged from the ADR; the evaluation path does not
   route, so it cannot downgrade anything.
8. **Rollback stays configuration-only.** One switch, no migration.

---

## 3. Target architecture

```
                          ┌──────────────────────────────────────────────┐
                          │  amsha.decision.providers.* (existing, inert) │
                          └───────────────────────┬──────────────────────┘
                                                  │ read
                                    ┌─────────────▼──────────────┐
                                    │  DecisionProviderSelector  │  (existing, pure)
                                    │  → Selection.Selected       │
                                    └─────────────┬──────────────┘
                                                  │
                     ┌────────────────────────────┼────────────────────────────┐
                     │                            │                            │
        ┌────────────▼────────────┐   ┌───────────▼───────────┐   ┌────────────▼────────────┐
        │ DecisionCredentialResolver│  │ DecisionProviderGovernance│ │  DecisionEngineFactory  │
        │  apiKeyRef → SecretVault  │  │ retention/residency/…   │  │ Selected → DecisionEngine│
        └────────────┬─────────────┘   └───────────┬───────────┘   └────────────┬────────────┘
                     └──────────────┬───────────────┘                            │
                                    ▼                                            │
                       ┌────────────────────────────┐                            │
                       │  DecisionEvaluationService │  (opt-in, audited)         │
                       │  build → evaluate → close  │◄───────────────────────────┘
                       └───────────┬────────────────┘
                                   │
                    ┌──────────────┴───────────────┐
                    ▼                              ▼
        ┌───────────────────────┐      ┌───────────────────────────┐
        │ DecisionAdminResource │      │   DecisionEvidenceRunner   │
        │ /admin/decision/*     │      │  corpus → M1..M5 evidence  │
        └───────────────────────┘      └───────────────────────────┘

   ── later, only after ADR §8.4 steps 1–4 ──────────────────────────────────────────────
        ┌───────────────────────┐      ┌───────────────────────────┐
        │ DecisionEngineProducer│─────►│  IntentDecisionRouter     │  (existing, double-gated)
        │ single CDI bean       │      │  + DecisionTelemetry/Drift│
        └───────────────────────┘      └───────────────────────────┘
```

---

## 4. Workstreams

Each workstream lists **goal → files → behaviour → tests → gates**. Workstreams W1–W6 + W9 deliver
the evaluation path; W7–W8 deliver production activation and are **blocked on Phase 3 evidence**.
Appendix C has concrete config/reflection/test sketches; Appendix D is the five-PR breakdown.

### W1 — `DecisionEngineFactory` (core)

**Goal:** one place that turns a `Selection.Selected` into a live `DecisionEngine`.

- New: `core/src/main/java/com/amsha/core/classifier/provider/DecisionEngineFactory.java`
  ```java
  public final class DecisionEngineFactory {
      public static DecisionEngineHandle create(
              DecisionProviderSelector.Selection.Selected selected,
              DecisionProviderConfig.Slot.Governance declaredGovernance,
              Optional<byte[]> credential,          // resolved; caller zeroes after use
              ObjectMapper mapper,
              OutboundHttpPolicyService outboundPolicyService,
              SecretVault vault /* nullable; tests only */);
  }
  ```
  It returns a **handle**, not a bare engine. `DecisionEngine` is a functional interface and is not
  `AutoCloseable`, so a caller cannot close an engine generically — yet both lane implementations *are*
  closeable, and the loopback lane needs an `HttpClient` the factory creates and therefore owns. The
  handle (`DecisionEngineHandle`) carries the engine plus any owned transport and closes both,
  best-effort and never throwing.
- Mapping rules:
  - `Lane.REMOTE` → `RemoteProviderConfig.builder()`
    `.enabled(true).endpoint(selected.endpoint()).modelId(selected.model())`
    `.timeout(selected.timeout()).apiKey(<credential>)`
    `.dataLocation(governance.dataLocation())`
    `.retentionPolicy(governance.retentionPolicy())`
    `.tenantIsolation(true)`
    `.defaultProjectionPolicy(governance.projectionPolicy())` → `new RemoteDecisionEngine(...)`.
  - `Lane.SELF_HOSTED` → `SelfHostedProviderConfig.builder()` with `endpoint`, `modelId` (omitted when
    blank), `wireProfile`, `timeout`, `loopbackToken`, `defaultProjectionPolicy` →
    `new SelfHostedDecisionEngine(...)` with a client the factory builds and the handle closes.
- **Fail closed, with one refusal type.** The factory re-checks governance via
  `DecisionProviderGovernance.problem(...)` and, when the declaration is incomplete, throws
  `DecisionProviderUnavailableException` with a payload-free reason. A construction failure that the
  provider-config records or endpoint validators signal as `IllegalArgumentException`/`SecurityException`
  (for example a remote selection with no model) is normalised into the same refusal type, so the
  evaluation path handles exactly one failure kind. The factory never logs state or credentials.
- **Tests:** `DecisionEngineFactoryTest` — lane → engine mapping, blank self-hosted model accepted,
  `PROSE` accepted, governance-incomplete refused, remote-without-model refused, refusal never carries
  the credential, engine `toString()` never carries it, double `close()` safe, null arguments rejected.
  The config-level rules the factory cannot observe through the handle (blank model omitted, prose
  honoured on the wire) stay covered by the mapping/selector tests.

### W2 — Governance configuration (core)

**Goal:** make the Governance gate declarable and enforced at the configuration surface.

- Extend `core/.../config/DecisionProviderConfig.Slot` with a nested `Governance` group (so the FQN is
  `DecisionProviderConfig.Slot.Governance`):
  ```java
  interface Governance {
      Optional<String> dataLocation();               // required for REMOTE
      @WithDefault("PT0S") Duration retentionWindow(); // PT0S = ZDR; required for REMOTE
      @WithDefault("false") boolean allowTrainingUse(); // must be false; true → refused
      @WithDefault("true")  boolean tenantIsolation();  // must be true
      Optional<List<String>> projectionAllowList();     // required, non-empty, for any egress
      Optional<BigDecimal> costCeilingPerThousand();    // required for production activation
  }
  Governance governance();
  ```
  **Naming trap.** `costCeilingPerThousand` is deliberately *not* `costCeilingPer1000`. SmallRye derives
  the property name with `StringUtil.skewer`, which inserts `-` before each capital and nothing before a
  digit, so `costCeilingPer1000` becomes `cost-ceiling-per1000` — not `cost-ceiling-per-1000`. An unknown
  key is not ignored: it fails the entire mapping at startup with
  `SRCFG00050: ... does not map to any root`. A digit-free member name removes the trap. (Learned the hard
  way: the first app-container run failed on exactly this.)
  Every member is optional or defaulted, so an undeclared group binds to an empty declaration rather
  than failing the whole mapping — which is what keeps the existing hand-written `Slot` doubles and the
  `Deferred` default posture compiling. Completeness is enforced when a slot is selected, not at binding.
- New: `core/.../config/DecisionProviderGovernance.java` — the validated record, built from the mapping.
  It lives in `config`, **not** `classifier/provider`, and that placement is load-bearing: the selector
  (also in `config`) validates through it, so putting it in `classifier/provider` — which already imports
  `config` for `DecisionProviderConfig`/`DecisionProviderSelector` — would create a package cycle. The
  record therefore carries no exception type from that package; it exposes two pure static methods:
  - `Optional<String> problem(Governance declared, Lane lane)` — the reason the declaration is
    incomplete for the lane, or empty. It rejects a `null` group, `allowTrainingUse=true`,
    `tenantIsolation=false`, an empty/blank projection allow-list, a negative retention window, a
    negative cost ceiling, and a blank `dataLocation` on the **remote** lane only. The reason is
    payload-free: operator configuration, never state or a credential.
  - `DecisionProviderGovernance of(String providerId, Governance declared, Lane lane)` — builds the
    record for a declaration `problem` accepted, normalising training-use to `false` and
    tenant-isolation to `true` so a caller that skipped validation still cannot hold the permissive
    value. `retentionPolicy()` and `projectionPolicy()` then hand the engines their two policies.
- Extend `DecisionProviderSelector.validate(...)` to refuse an enabled slot with incomplete governance,
  mirroring how it already refuses a missing `model` or `api-key-ref`. The check runs **last**, after
  the lane is certain and after endpoint validation, so a slot with an unusable endpoint reports that
  rather than a governance rule it would never reach. It is the one fail-closed answer to "is this
  provider usable?", so the evaluation path (W4) gets a payload-free `Unavailable` reason instead of an
  exception.
- The factory (W1) re-checks the same rule and throws, so the guarantee does not depend on the selector
  having run. Both are pure; the duplication is deliberate defence in depth.
- **YAML/`.env`:** add `amsha.decision.providers.slots.<id>.governance.*` in
  `app/src/main/resources/application.yaml` and the matching `AMSHA_DECISION_<ID>_GOVERNANCE_*`
  keys in the **root `.env.example` only** (never `app/src/main/resources/env-example`, which is
  generated from it). The two master switches (`AMSHA_DECISION_EVALUATION_ENABLED`,
  `AMSHA_DECISION_PRODUCTION_ENGINE_ENABLED`) are **not** added here: nothing reads them until W4 and W7
  land, and an env var with no reader is dead configuration. They arrive with their owning workstream.
- **Tests:** extend `app/src/test/java/com/amsha/app/classifier/DecisionProviderConfigBindingTest.java`
  (in-container binding of the nested governance group, including the comma-separated allow-list);
  `DecisionProviderGovernanceTest` (the rejection matrix plus the two forced-safe transformations);
  `DecisionProviderSelectorTest` (the same matrix through `select()`).

### W3 — Credential resolution (core)

**Goal:** `apiKeyRef` actually becomes a credential, safely.

- New: `core/.../classifier/provider/DecisionCredentialResolver.java`
  - `Optional<byte[]> resolve(String providerId, String apiKeyRef)` →
    `SecretVault.retrieveSystemKey(ref)`; empty when the ref is blank or absent; fail closed (throw
    `DecisionProviderUnavailableException`) when the ref is present but the vault rejects it or does not
    hold it. The provider id is a parameter only so a refusal can name the slot; the reference value
    itself is never echoed.
  - The returned array is a **detached copy**: the resolver closes the vault's `VaultKey` (which wipes
    its buffer) before returning, so a caller that held the vault's array would be handed zeroes.
  - `static void zero(byte[])` wipes the material and tolerates `null`; the caller calls it in a
    `finally` once the engine has consumed the credential.
- **Zero-String note — the limitation is real, not eliminated.** `RemoteProviderConfig.apiKey` and
  `SelfHostedProviderConfig.loopbackToken` are `String`s (guarded by `@JsonProperty(WRITE_ONLY)` and a
  redacted `toString()`), and `HttpRequest.Builder.header` requires a `String` regardless. The factory
  decodes the credential bytes exactly once, at the construction call site, and never writes the text to
  a log line or an exception — **but it does hand it to the provider config, so a `String` copy lives on
  that config for as long as the engine does.** `DecisionEngineHandle.close()` closes the engine and its
  transport; it cannot erase an immutable `String`. The window is therefore bounded by the engine's
  lifetime (one evaluation), not the process's, and the caller's `byte[]` is zeroed in `finally` — but
  the `String` itself is not zeroable. That is a **production security decision, not optional
  hardening**. Two options, both W4-scoped, to be settled before the evaluation path is enabled:
  (a) accept the bounded-`String` window and record it as a residual risk in the ADR; or (b) W3b — add
  an engine overload that takes a `byte[]`/`char[]` credential supplier and builds the header without a
  lingering `String`. Whichever is chosen, the claim to make in the ADR is "transient and bounded",
  never "never stored".
- **W3c — self-hosted bearer support.** Add `Optional<String> credentialRef` to
  `SelfHostedProviderConfig`, resolve it via the same resolver, and have `SelfHostedDecisionEngine`
  send `Authorization: Bearer` when set (falling back to `X-Amsha-Loopback-Token` when only
  `loopbackToken` is set). This closes the documented "secured Laya/CLM is unreachable" gap. **Not in
  PR-1** — it changes engine behaviour and belongs with the evaluation path.
- **Tests:** `DecisionCredentialResolverTest` (present/absent/unresolvable; vault failure; detached
  bytes; zeroed buffer; payload-free refusal), and — for W3c — `SelfHostedDecisionEngineBearerTest`
  (header selection matrix).

### W4 — Explicit evaluation / orchestration path (core + app)

**Goal:** the sanctioned way to make a real provider call — opt-in, internal-only, audited, and
strictly non-routing.

**Design pinned (2026-10-07) — see D1–D6 at the end of this section.** The one open question, *what the
evaluation path evaluates and where that comes from*, is now answered: the registry's single entry is the
router's own `amsha.routing.intent.v1`. W4 remains **blocked on the companion ADR being Accepted** — the
pinned design removes the excuse for inventing vocabulary, not the gate.

- New: `core/.../classifier/evaluation/DecisionEvaluationService.java` (`@ApplicationScoped`)
  ```java
  public DecisionEvaluationOutcome evaluate(DecisionEvaluationRequest request);
  public DecisionEvidenceSummary runEvidence(DecisionEvidenceSpec spec); // W5
  ```
  Behaviour:
  1. `DecisionProviderSelector.select(config)` → `Selected` (else payload-free refusal).
  2. Build governance; resolve credential.
  3. `DecisionEngineFactory.create(...)` inside try-with-resources (both engines are `AutoCloseable`).
  4. Build `DecisionRequest` from a **registered** `DecisionDefinition` (explicit `StateProjectionPolicy`
     allow-list + `RedactionPolicy`); never invent an allow-list.
  5. `engine.evaluate(request)`; map to a payload-free outcome carrying the typed answer summary and
     `DecisionMetadata` (provider, model, latency, tokens).
  6. Assert **not** on an event-loop thread; the service runs on a worker/virtual thread.
  7. **Audit** the egress attempt and outcome (audit-before-mutation convention), payload-free.
- New: `core/.../classifier/evaluation/DecisionEvaluationRequest.java`,
  `DecisionEvaluationOutcome.java`, `DecisionEvidenceSpec.java`, `DecisionEvidenceSummary.java`
  (records; validated in compact constructors).
- New: `app/src/main/java/com/amsha/app/api/DecisionAdminResource.java`
  - `GET  /api/v1/admin/decision/provider` — resolved selection status: providerId, lane, host, model,
    governance-declared?, credential-present? (booleans only; **no secrets, no state**).
  - `POST /api/v1/admin/decision/evaluate` — one evaluation over a registered decision definition.
  - `POST /api/v1/admin/decision/evidence/run` — run the versioned corpus (W5), return aggregate metrics.
  - Guards: `isInternal()` on every endpoint (`resolveUserId()` for attribution); mutating/evaluation
    endpoints audited. Blocking/virtual-thread execution (`@RunOnVirtualThread`), never the event loop.
- Opt-in switch: `amsha.decision.evaluation.enabled` (**default false**). When false, the service and
  the resource return a payload-free refusal (409/403 per convention) and perform **no egress**.
- **Tests:**
  - `DecisionEvaluationServiceTest` — disabled by default performs no egress; governance-incomplete
    refused; credential-missing refused; event-loop invocation refused.
  - `app/.../DecisionAdminResourceTest` — 401 unauthenticated, 403 non-internal, 200 internal;
    evaluation disabled → refused with no HTTP call; payload carries no state.
  - `DecisionEvaluationPathNoEgressLeakIT` — a local HTTP fixture proves excluded fields, debug keys
    and secrets never reach the request body (mirrors the existing self-hosted contract tests).

#### W4 — pinned design decisions (2026-10-07)

W4's one unstated ambiguity was **what the evaluation path evaluates, and where that comes from**. There
is no id→`DecisionDefinition` registry in the codebase today: the only main-code builder is
`IntentDecisionRouter.definition(ReviewBand, Duration)`, and its single CHOICE question's options are the
runtime `validRoles` passed to `evaluateIntent(...)`. These six decisions are pinned so the implementer
does not have to invent them. **They do not authorise W4 to land** — the companion ADR must still be
Accepted first.

**D1 — The evaluation path evaluates the router's own decision, not a new one.** The registry's first and
only entry is `amsha.routing.intent.v1`, the definition `IntentDecisionRouter` already builds. This is not
a convenience: the Phase 3 evidence exists to justify activating *that* router, so evidence gathered
against a different decision would not close the Quality gate. It also means no new decision vocabulary is
invented, which keeps the change inside the companion ADR's authority.

**D2 — The registry owns the security-shaped parts; the caller supplies only the closed set.** A new
`core/.../classifier/evaluation/DecisionDefinitionRegistry` maps `decisionId` to a registered entry:

```java
public record RegisteredDecision(
        DecisionDefinition definition,     // primitive, criteria, redaction, retention,
                                          // allowedProviders, failMode, timeout
        List<QuestionSpec> questions) { }  // handle, kind, criteria, and either a fixed
                                          // option list or CALLER_SUPPLIED
```

It is a **static, explicit map** — no scanning, no reflection — seeded with exactly one entry, and it is
the only place a definition may come from. What a caller may influence is deliberately narrow: the
**options of a `CALLER_SUPPLIED` CHOICE question**. Everything that bounds egress — `RetentionPolicy`,
`RedactionPolicy`, `allowedProviders`, `failMode` — comes from the registered definition and cannot be set
by a request. A request naming an unregistered `decisionId` is refused, never defaulted.

**D3 — The request carries a decision id and state; it never carries a policy.**

```java
public record DecisionEvaluationRequest(
        String decisionId,                        // must resolve in the registry
        String stateJson,                         // projection source, before allow-listing
        Map<String, List<String>> choiceOptions,  // options per CALLER_SUPPLIED question handle
        Duration timeout) { }                     // optional; the definition's timeout is the ceiling
```

Validated in a compact constructor: non-blank id, non-blank state, options present for exactly the
`CALLER_SUPPLIED` handles and absent for the fixed ones. The projection allow-list is **not** here — it
comes from `DecisionProviderGovernance.projectionPolicy()` (the engine's config), which is what the remote
engine actually reads (`RemoteDecisionEngine.resolveProjectionPolicy`). A request therefore cannot widen it.

**D4 — The outcome is a payload-free summary, not a raw result.**

```java
public record DecisionEvaluationOutcome(
        String providerId, String lane, String model,
        boolean opinion, String ruleId,
        Map<String, String> answers,      // handle -> label (+ confidence); never state
        DecisionMetadata metadata) { }    // provider, model, latency, tokens
```

`answers` carries the *result* — labels and confidences — never the projected state, the credential, or a
provider body. `DecisionResult.NoOpinion.detail` is already sanitized by the classifier API and is not
propagated.

**D5 — Endpoints, switch, and audit.**
- `GET /api/v1/admin/decision/provider` — status only: providerId, lane, host, model,
  governance-declared?, credential-present? Identifiers and booleans; **no secret, no state**.
  Internal-only, per the ADR §2.2 rule 4 exception.
- `POST /api/v1/admin/decision/evaluate` — one evaluation over a registered definition.
- `POST /api/v1/admin/decision/evidence/run` — W5; refuse until W5 lands rather than half-implementing it.
- `amsha.decision.evaluation.enabled` (**default false**) gates all three; when false the service performs
  no egress and returns a payload-free refusal. The key lands **with** W4 — it is deliberately absent from
  the configuration today, because an env var with no reader is dead configuration.
- `@RunOnVirtualThread`; `isInternal()` on every endpoint; `resolveUserId()` for audit attribution; one
  payload-free audit event per evaluation (egress is a side effect), following audit-before-mutation.

**D6 — The tests are fixed, not left to the implementer.** `DecisionEvaluationServiceTest` (disabled ⇒ no
egress; unregistered id ⇒ refused; governance-incomplete ⇒ refused; event-loop invocation ⇒ refused; audit
emitted); `DecisionAdminResourceTest` (401 unauthenticated / 403 non-internal / 200 internal; disabled ⇒
refusal with no HTTP call); `DecisionEvaluationPathNoEgressLeakIT` (a local fixture proves excluded fields,
debug keys, and secrets never reach the request body); `DecisionEvaluationPathIT` (evaluation succeeds
while `Instance<DecisionEngine>` remains unsatisfied).

### W5 — Evidence pipeline (Phase 3)

**Goal:** produce the Quality-gate evidence the ADR demands, for real candidates.

- New: `core/.../classifier/evaluation/DecisionEvidenceRunner.java`
  - Runs the configured provider over the **versioned** corpus through the **deterministic policy
    layer** (`IntentDecisionPolicy`, `ToolRiskDecisionPolicy`) so M1 is measured end to end, not against
    a bare engine.
  - Computes: **M1** false-allow (must be exactly 0), **M2** specialist routing error < 1% on the
    ambiguous subset, **M3** abstention rate (reported, no threshold), **M4** p95 latency
    (lane-specific — see §6), **M5** cost per 1,000 decisions.
  - Emits payload-free aggregate JSON + Markdown to `.scratch/jev-decision-plane/evidence/`.
- Corpus: promote the existing `core/src/test/resources/shadow-evaluation/shadow_evaluation_corpus.json`
  to a versioned `core/src/test/resources/decision-evidence/corpus-v1.json`; every case redacted and
  allow-listed.
- Extend `DecisionShadowComparisonHarness.CandidateType` with a **measured** `REAL_PROVIDER` mode that
  executes via the factory instead of synthesising outcomes; keep the existing simulation flags for the
  legacy baselines.
- **Tests:** `DecisionEvidenceRunnerTest` (metric maths on a fixed corpus; M1 zero-rule; M2 denominator
  excludes confidently-handled cases); `DecisionEvidenceIT` (`@QuarkusTest`, gated on a configured
  provider so it is a no-op without one).

### W6 — Telemetry, drift, cost & latency (core)

**Goal:** close the Operations gate's "monitoring with drift alerting" requirement.

- Extend `core/.../classifier/DecisionTelemetry.java` to consume `DecisionMetadata` and emit bounded
  tags/metrics: `model` (versioned id — low cardinality), confidence **bucket** (histogram), latency
  **bucket**, and a cost accumulator. All values stay payload-free.
- New: `core/.../classifier/DecisionDriftMonitor.java` — compares observed confidence/abstention
  distribution against a baseline window; emits one WARN/alert on drift beyond a configured threshold.
  Bounded cardinality; never logs state.
- Cost ceiling: rolling cost per 1,000 decisions; when the configured ceiling is exceeded, evaluation
  **fails closed** (`NoOpinion(OVERLOAD_REJECTED)`) and alerts. Latency budget: p95 breach alerts.
- **Tests:** `DecisionTelemetryTest` extension (bounded tags, model identity, buckets);
  `DecisionDriftMonitorTest` (drift/no-drift, bounded); cost-ceiling and latency-budget tests.

### W7 — Production activation (blocked on Phase 3)

**Goal:** the ADR §8.4 step 5 code change, and nothing before it.

- New: `core/.../classifier/provider/DecisionEngineProducer.java` (`@ApplicationScoped`, `@Produces`),
  gated **structurally at build time** by
  `@IfBuildProperty(name = "amsha.decision.production-engine.enabled", stringValue = "true")`.
  - Produces the **single** `DecisionEngine` bean via the W1 factory.
  - **A runtime switch cannot provide this guarantee, and must not be used here.** A `@Produces` method
    is itself a registered CDI bean even when it returns no engine, so `Instance<DecisionEngine>` would
    become *satisfied* and `IntentDecisionRouter.isEnabled()` — which tests
    `!decisionEngineInstance.isUnsatisfied()` — could report `true` with the engine switched off. That
    degrades §27.1's structural guarantee into a configurational one, which is precisely the failure the
    parent ADR exists to prevent. The exclusion must therefore happen at build time, where the bean is
    never registered: with the property absent or false, `Instance<DecisionEngine>` stays **unsatisfied**
    and the Deferred posture is preserved exactly.
  - Because it is a build-time property, enabling the engine is a rebuild. That is the correct shape:
    §8.4 promotion is a recorded human decision plus a code change, not a runtime toggle.
- **Operational rollback stays runtime.** `AMSHA_ROUTING_INTENT_DECISION_ROUTER_ENABLED=false` restores
  the safe posture without a rebuild, because the router gate is a runtime check; only the *engine
  registration* is build-time. Record this asymmetry explicitly in the ADR when W7 lands.
- No change to `IntentDecisionRouter` — it already consumes `Instance<DecisionEngine>` and enforces
  `isEnabled()` / `isActivationGateApproved()` / `isDirectRoutingPermitted()`.
- Optional: `DecisionPlaneActivationReadiness` (`@Startup`) warns when an engine is resolvable but the
  gate is unapproved (defence in depth), under its **own** logger name (§27.2/§27.5 constraint).
- **Tests:** `DecisionEngineProducerIT` — assert `Instance<DecisionEngine>` is **unsatisfied** when the
  build property is off and that exactly one bean resolves when it is on, and that
  `IntentDecisionRouter.isEnabled()` is false until the gate is approved. The assertion must be on *bean
  resolution*, not on a null return from a producer method: a null return cannot distinguish "not
  registered" from "registered but disabled", which is the whole point of the gate.

### W8 — ADR & invariant changes

**Status: W8.1–W8.4 landed.** Only two things remain open in this workstream: the Committee's acceptance
of the evaluation-path ADR (W8.1, still `Proposed`), and the parent ADR's §8.4 status flip, which is
PR-5's deliberate last act. The shape below is what is on disk.

1. **New ADR** `docs/adr/architecture/ADR-jev-decision-plane-evaluation-path.md` — **drafted
   (Status: `Proposed`)**; needs Committee acceptance before W4 lands.
   - Authorises the explicit evaluation path: on-demand engine construction, opt-in, internal-only,
     allow-listed + redacted data, no CDI engine bean, no routing authority.
   - States that it does **not** satisfy ADR §8.4 steps 1–5 and does **not** move production routing
     off Deferred.
2. **Amend** `ADR-jev-decision-plane.md` — **done** (already in the staged set):
   - §2: the evaluation path is recorded as an approved adapter-category usage.
   - §7: §7.8 "Explicit Evaluation Path authorised (2026-10-07)" records this plan and the
     evaluation-path ADR.
   - §8.3: the M4 reconciliation (lane-specific budgets — see §6) is recorded, and the evaluation path
     is named as the instrument for closing Quality.
   - **Status stays `Deferred`** — only the promotion procedure (§8.4) may change it.
3. **Amend** `docs/Architecture_Invariants.md` §27 — **done** (already in the staged set; do not repeat):
   - §27.1 carries the evaluation-path exception — transient construction permitted, no CDI bean, routes
     nothing, disabled by default, and "MUST NOT be implemented before that ADR is accepted".
   - §27.6 — the evaluation path is opt-in, internal-only, audited, allow-listed, and never on the event
     loop.
   - §27.7 — no production `DecisionEngine` bean until §8.4 steps 1–5 are recorded.
   An earlier draft of W8 listed these as future work. They landed with the documentation set, so the
   PR-5 row no longer carries W8.3.
4. **Amend** `docs/research/Jev-Provider-Integration-Plan-2026.md` — **done**: new §11 records the
   evaluation path, the two unnamed gaps (`api-key-ref` unconsumed, no self-hosted bearer), the
   telemetry/drift scope, and the M4 reconciliation; the new ADR and this plan are added to its
   repository-artifact list.

### W9 — Tests, gates, validator

- Extend `rest-http/scripts/validate_decision_plane.sh` with:
  - **JEV-13** — `GET /admin/decision/provider` rejects unauthenticated (401) and non-internal (403).
  - **JEV-14** — the provider status payload is payload-free (exact key set; no state, no secret).
  - **JEV-15** — evaluation is refused (no egress) while `amsha.decision.evaluation.enabled=false`.
  - **JEV-16** — no `DecisionEngine` bean is resolvable while the plane is Deferred (extends JEV-5).
- Per `AGENTS.md`: after Java edits `python3 ./scripts/fix_fqns.py && ./mvnw spotless:apply -q`; run the
  module PMD gate `./mvnw -pl <module> -am pmd:check -DskipTests`; after Markdown edits
  `python3 ./scripts/verify_docs.py`; before commit `mise run sonar:issues`.
- Register every new record/DTO in `CoreReflectionConfig` (or `AppReflectionConfig` for REST DTOs) —
  centrally, never with type-level `@RegisterForReflection`.

---

## 5. Configuration keys (new)

| Key | Default | Purpose |
|---|---|---|
| `AMSHA_DECISION_EVALUATION_ENABLED` | `false` | Opt-in master switch for the evaluation path |
| `AMSHA_DECISION_<ID>_GOVERNANCE_DATA_LOCATION` | _(blank)_ | Residency declaration (required for remote) |
| `AMSHA_DECISION_<ID>_GOVERNANCE_RETENTION_WINDOW` | `PT0S` | Retention (ZDR = `PT0S`) |
| `AMSHA_DECISION_<ID>_GOVERNANCE_ALLOW_TRAINING_USE` | `false` | Must stay false; true is refused |
| `AMSHA_DECISION_<ID>_GOVERNANCE_TENANT_ISOLATION` | `true` | Must stay true |
| `AMSHA_DECISION_<ID>_GOVERNANCE_PROJECTION_ALLOW_LIST` | _(blank)_ | Top-level state fields allowed to egress |
| `AMSHA_DECISION_<ID>_GOVERNANCE_COST_CEILING_PER_THOUSAND` | _(blank)_ | Required for production activation. `PER_THOUSAND`, not `PER_1000`: SmallRye's `StringUtil.skewer` inserts no separator before a digit, so `costCeilingPer1000` derives to `cost-ceiling-per1000` and fails the mapping |
| `AMSHA_DECISION_<ID>_CREDENTIAL_REF` | _(blank)_ | Self-hosted bearer (W3c) |
| `AMSHA_DECISION_PRODUCTION_ENGINE_ENABLED` | `false` | W7 **build-time** registration gate (a rebuild enables it; it is not a runtime toggle — see W7) |

Add all of these to the **root `.env.example`** and to `app/src/main/resources/application.yaml`.
Never edit `app/src/main/resources/env-example` (generated).

---

## 6. The M4 conflict — resolved in the ADR

**Resolved; no further action.** The parent ADR §8.3 now sets M4 **lane-specific** — **p95 < 50 ms for an
in-process/self-hosted local lane** and **p95 < 500 ms for a remote hosted lane**, measured per lane and
never combined. That reconciles the original single "< 50 ms" figure (written for the local intent pilot,
and unreachable for a hosted provider) with the feature spec's provisional remote target, and the
amendment is already in the staged parent ADR — it is not outstanding W8 work.

Consequences: W5 reports M4 per lane against these two budgets, and no W4–W7 step depends on an open
question here. The earlier draft of this section recommended the change; the recommendation was adopted.

---

## 7. Gates, sequencing, and what a PR may not do

### 7.1 Sequencing

```
W1 factory ─┬─► W4 evaluation path ─┬─► W5 evidence ──► [Phase 3 evidence] ──► W7 production
W2 governance┤                       └─► W6 telemetry
W3 credential┘
W8 ADR (before W4 lands)                 W9 tests/validator (with each PR)
```

W4 must not land before the evaluation-path ADR (W8.1) is accepted.

### 7.2 Hard prohibitions (mirroring §27)

- Do **not** register a production `DecisionEngine` bean before §8.4 steps 1–5 are recorded.
- Do **not** place a remote or self-hosted engine behind a classifier SPI.
- Do **not** set `candidate-evidence-approved` / `cost-ceiling-approved` / `rollout-owner` without the
  evidence — that is the governance failure the readiness diagnostics exist to announce.
- Do **not** default a remote `model`, invent an allow-list, or synthesise confidence.
- Do **not** log state, prompts, labels, secrets, or provider bodies.
- Do **not** enable the evaluation path by default, or make it reachable without `isInternal()`.

### 7.3 Rollback

- Evaluation path: set `AMSHA_DECISION_EVALUATION_ENABLED=false`.
- Production routing: set `AMSHA_ROUTING_INTENT_DECISION_ROUTER_ENABLED=false` and/or
  `AMSHA_DECISION_PRODUCTION_ENGINE_ENABLED=false`.
- No migration, no schema change, no restart-dependent cleanup.

---

## 8. Open items requiring human / Committee input (not code)

1. **M5 cost ceiling** — a number authorised by the Committee (TypeSafe input is $0.042/Mtok; output
   free). The plan supplies the mechanism; the value is a decision.
2. **Rollout & rollback owner** — a person or team, never a placeholder (`ActivationGateReadiness`
   warns on placeholders by design).
3. **Review cadence** — the ADR §8.5 90-day trigger is still an unconfirmed proposal.
4. **TypeSafe account + ZDR** — issue the key at `console.typesafe.ai/keys`, bind via `SecretVault`,
   and record the retention/residency declarations (ZDR is enterprise-only via `sales@typesafe.ai`).
5. **Corpus provenance** — who signs off that the versioned evidence corpus is representative.

*(M4 is deliberately absent from this list: the lane-specific budget is resolved in §6 and recorded in
the parent ADR §8.3, so no Committee decision is outstanding for it.)*

---

## 9. Definition of done

- **Evaluation path:** `AMSHA_DECISION_EVALUATION_ENABLED=true` + a configured TypeSafe slot with
  governance declared produces a real, credentialed, allow-listed, redacted `POST /v1/systemone`
  evaluation whose result is decoded and whose evidence is payload-free — **with no `DecisionEngine`
  bean registered** and `IntentDecisionRouter.isEnabled()` still false.
- **Evidence:** M1–M5 reported per lane over a versioned corpus, M1 exactly zero measured end to end.
- **Production activation (later):** one producer bean, one flag, gate values recorded after steps 1–4,
  rollback by configuration.
- All module PMD gates, `spotless:check`, `verify_docs.py`, and the extended
  `validate_decision_plane.sh` green; new types centrally registered for native reflection.

---

## Appendix A — File inventory

✅ = landed in PR-1 (inert); the rest is planned.

**New (core):** ✅ `DecisionEngineFactory`, ✅ `DecisionEngineHandle`, ✅ `DecisionProviderGovernance`
(package `core.config`), ✅ `DecisionCredentialResolver`, ✅ `DecisionProviderUnavailableException`,
`DecisionEvaluationService`, `DecisionEvaluationRequest`, `DecisionEvaluationOutcome`,
`DecisionEvidenceSpec`, `DecisionEvidenceSummary`, `DecisionEvidenceRunner`, `DecisionDriftMonitor`,
`DecisionEngineProducer` (W7).
**New (core, test):** ✅ `DecisionGovernanceFixture`, ✅ `DecisionProviderGovernanceTest`,
✅ `DecisionEngineFactoryTest`, ✅ `DecisionCredentialResolverTest`.
**New (app):** `DecisionAdminResource` + its request/response DTOs.
**New (docs):** `docs/adr/architecture/ADR-jev-decision-plane-evaluation-path.md`.
**Extended:** ✅ `DecisionProviderConfig` (nested `Slot.Governance`), ✅ `DecisionProviderSelector`
(governance refusal), ✅ `CoreReflectionConfig`, ✅ `application.yaml`, ✅ root `.env.example`,
✅ `DecisionProviderSelectorTest`, ✅ `DecisionProviderConfigBindingTest`; planned:
`SelfHostedProviderConfig`, `SelfHostedDecisionEngine` (bearer), `DecisionTelemetry`,
`AppReflectionConfig`, `validate_decision_plane.sh`, `DecisionShadowComparisonHarness`.

## Appendix B — Existing references

- `docs/adr/architecture/ADR-jev-decision-plane.md` — governance, §8 promotion procedure.
- `docs/research/Jev-Provider-Integration-Plan-2026.md` — wire contract, Phase 0 evidence, §8 config.
- `docs/Architecture_Invariants.md` §27 — decision-plane invariants.
- `.scratch/jev-decision-plane/spec.md` — feature spec, issues 01–07 (gitignored; the ADR is the
  clean-checkout authority).

## Appendix C — Concrete change sketches

Sketches, not code. Values are the shipped defaults; `_<ID>_` is a provider id (`typesafe`, `clm`,
`laya`, `kev`, `omlx`).

### C.1 `application.yaml` — additions inside the existing `amsha.decision` block

```yaml
  decision:
    # evaluation.enabled (W4) and production-engine.enabled (W7) are deliberately NOT here yet: no code
    # reads them until those workstreams land, and an env var with no reader is dead configuration.
    providers:
      selected: "${AMSHA_DECISION_PROVIDER:}"
      slots:
        typesafe:
          enabled: ${AMSHA_DECISION_TYPESAFE_ENABLED:false}
          endpoint: "${AMSHA_DECISION_TYPESAFE_ENDPOINT:https://api.typesafe.ai/v1/systemone}"
          model: "${AMSHA_DECISION_TYPESAFE_MODEL:jev-latest}"
          api-key-ref: "${AMSHA_DECISION_TYPESAFE_API_KEY_REF:}"
          timeout: ${AMSHA_DECISION_TYPESAFE_TIMEOUT:PT5S}
          wire-profile: ${AMSHA_DECISION_TYPESAFE_WIRE_PROFILE:JSON_OBJECT}
          governance:
            data-location: "${AMSHA_DECISION_TYPESAFE_GOVERNANCE_DATA_LOCATION:}"
            retention-window: ${AMSHA_DECISION_TYPESAFE_GOVERNANCE_RETENTION_WINDOW:PT0S}
            allow-training-use: ${AMSHA_DECISION_TYPESAFE_GOVERNANCE_ALLOW_TRAINING_USE:false}
            tenant-isolation: ${AMSHA_DECISION_TYPESAFE_GOVERNANCE_TENANT_ISOLATION:true}
            projection-allow-list: "${AMSHA_DECISION_TYPESAFE_GOVERNANCE_PROJECTION_ALLOW_LIST:}"
            cost-ceiling-per-thousand: "${AMSHA_DECISION_TYPESAFE_GOVERNANCE_COST_CEILING_PER_THOUSAND:}"
```

Every slot (`typesafe`, `clm`, `laya`, `kev`, `omlx`) carries the same six-line `governance` block,
with `_<ID>_` replaced by the upper-cased provider id in the env var name.

`projection-allow-list` binds as a comma-separated `List<String>` (SmallRye supports it for
`Optional<List<String>>`, and the binding test asserts the list). `retention-window: PT0S` is
zero-retention; a non-zero value is a positive declaration, not a default to be assumed. Note the
defaults are *inert*: with `projection-allow-list` unset the slot is refused once selected, which is
the intended fail-closed posture rather than a bug to work around.

### C.2 Root `.env.example` — mirror the keys, commented

Add the `AMSHA_DECISION_<ID>_GOVERNANCE_*` keys to each existing `AMSHA_DECISION_*` provider block,
commented out, with a note that a **remote** slot needs `DATA_LOCATION` and that **every** slot needs a
non-empty `PROJECTION_ALLOW_LIST` before it will resolve. The two master switches
(`AMSHA_DECISION_EVALUATION_ENABLED`, `AMSHA_DECISION_PRODUCTION_ENGINE_ENABLED`) are **not** added
until W4/W7 land, for the reason in C.1. Edit the root `.env.example` **only** —
`app/src/main/resources/env-example` is generated from it.

### C.3 Native-reflection registrations

Central configs only; never a type-level `@RegisterForReflection`.

| Type | Config | Registration |
|---|---|---|
| `DecisionProviderGovernance` (record, package `core.config`) | `CoreReflectionConfig` | `targets` (accessible) |
| `DecisionEvaluationRequest` / `Outcome` (W4) | `CoreReflectionConfig` | `targets` |
| `DecisionEvidenceSpec` / `Summary` (W5) | `CoreReflectionConfig` | `targets` |
| `DecisionProviderUnavailableException` | `CoreReflectionConfig` | `targets` |
| Admin request/response DTOs (W4) | `AppReflectionConfig` | `targets` (private/nested → `classNames`, binary name) |

`DecisionEngineFactory`, `DecisionEngineHandle`, and `DecisionCredentialResolver` are **not**
registered: they are ordinary classes invoked directly, with no reflection or serialization boundary.

### C.4 Test matrix

| Test | Layer | Asserts |
|---|---|---|
| `DecisionEngineFactoryTest` | unit | lane → engine mapping; blank self-hosted model accepted; `PROSE` accepted; governance-incomplete refused; remote-without-model refused; refusal/`toString()` never carry the credential; double `close()` safe; null arguments rejected |
| `DecisionProviderGovernanceTest` | unit | `allowTrainingUse=true` / `tenantIsolation=false` / empty-or-blank allow-list / negative window / negative cost ceiling / blank remote `dataLocation` all refused; `of()` forces the safe training + isolation values; the derived policies follow the lane |
| `DecisionCredentialResolverTest` | unit | present → bytes; absent ref → empty (vault untouched); unresolvable ref → throws; vault failure → throws with cause; bytes detached from the vault buffer; refusal never echoes the ref |
| `DecisionProviderSelectorTest` | unit | the governance matrix through `select()`, plus the existing lane/model/dialect refusals |
| `DecisionProviderConfigBindingTest` | `@QuarkusTest` (`app`) | the nested governance group binds in-container, including the comma-separated allow-list and the `PT0S` → `Duration` conversion |
| `DecisionEvaluationServiceTest` (W4) | unit | disabled ⇒ no egress; governance-incomplete refused; event-loop invocation refused; audit emitted |
| `DecisionAdminResourceTest` (W4) | IT (`app`) | 401 unauthenticated · 403 non-internal · 200 internal; evaluation-disabled ⇒ refusal with no HTTP call |
| `DecisionEvaluationPathNoEgressLeakIT` (W4) | IT (`app`) | excluded fields, debug keys, secrets absent from the request body (local HTTP fixture) |
| `DecisionEvaluationPathIT` (W4) | IT (`app`) | evaluation works against a fixture **while no `DecisionEngine` bean is resolvable** |
| `DecisionEvidenceRunnerTest` (W5) | unit | M1–M5 maths on a fixed corpus; M1 zero-rule; M2 denominator excludes confidently-handled cases |
| `DecisionDriftMonitorTest` (W6) | unit | drift/no-drift; bounded tags; payload-free |
| `DecisionEngineProducerIT` (W7) | IT (`app`) | no bean while the switch is off; exactly one when on |

Also extend `validate_decision_plane.sh` with JEV-13..16 as each workstream lands.

## Appendix D — PR breakdown for the implementer

Five PRs, in order. Each is independently reviewable; PR-2 is the only one gated on the ADR.

| PR | Workstreams | Scope | Gate before merge |
|---|---|---|---|
| **PR-1** ✅ | W1, W2, W3 | `DecisionEngineFactory`, `DecisionEngineHandle`, `DecisionProviderGovernance`, `DecisionCredentialResolver`, `DecisionProviderUnavailableException`; `DecisionProviderConfig.Slot.Governance`; selector governance refusal; `application.yaml` + `.env.example`; reflection; tests. **Inert — no caller, no egress, no new endpoint.** | unit tests; module PMD; spotless; docs verify |
| **PR-2** | W4 | `DecisionEvaluationService` + records; `DecisionAdminResource` + DTOs; `amsha.decision.evaluation.enabled`; audit; reflection. | **ADR accepted first**; ITs incl. no-egress-when-disabled and no-leak; validator JEV-13..15 |
| **PR-3** | W5 | `DecisionEvidenceRunner`; versioned corpus; `REAL_PROVIDER` harness mode; evidence artifacts. | unit tests; corpus reviewed for redaction |
| **PR-4** | W6 | `DecisionTelemetry` extension; `DecisionDriftMonitor`; cost-ceiling and latency-budget enforcement. | unit tests; bounded-cardinality review |
| **PR-5** | W7 | `DecisionEngineProducer` with a **build-time** `@IfBuildProperty` gate (not a runtime switch — see W7); `amsha.decision.production-engine.enabled`; **only then** the ADR status flip and gate-value recording. | §8.4 steps 1–4 recorded; ITs; validator JEV-16. **W8.2/W8.3 are already landed** — see W8 |

Guardrails for every PR: no production `DecisionEngine` bean before PR-5; no remote/self-hosted engine
behind a classifier SPI; no gate boolean set without evidence; no state, secret, label, or provider body
in any log, metric, or evidence artifact; nothing on the event loop.

## Appendix E — PR-1 as landed (2026-10-07)

What a reviewer or the next implementer should find on disk. Everything here is **inert**: it adds a
construction seam, a validation rule and a config surface, and changes no runtime behaviour, because
nothing calls the seam and no bean is registered.

**New — `core`**

| File | Role |
|---|---|
| `config/DecisionProviderGovernance.java` | Validated governance record. `problem(...)` → the lane-dependent refusal reason; `of(...)` → the record with training-use forced false and tenant-isolation forced true; `retentionPolicy()` / `projectionPolicy()` derive the engine policies. Lives in `config` so the selector can use it without a `config ↔ classifier.provider` package cycle. |
| `classifier/provider/DecisionEngineFactory.java` | The one construction path. `create(selected, declaredGovernance, credential, mapper, outboundPolicyService, vault)` → `DecisionEngineHandle`. Re-checks the **selection** through `DecisionProviderSelector.selectionProblem`, then the governance, then refuses a credentialed provider whose reference resolved to no material (via `DecisionProviderSelector.requiresCredentialMaterial`), then maps the lane to `RemoteDecisionEngine` / `SelfHostedDecisionEngine`. Normalises `IllegalArgumentException`/`SecurityException` into `DecisionProviderUnavailableException`. |
| `classifier/provider/DecisionEngineHandle.java` | Owns the engine plus any transport the factory created (the loopback `HttpClient`); closes both, best-effort, never throwing. Needed because `DecisionEngine` is a functional interface and is not `AutoCloseable`. |
| `classifier/provider/DecisionCredentialResolver.java` | `resolve(providerId, apiKeyRef)` → detached credential bytes; closes the vault key; `zero(byte[])` for the caller's `finally`. |
| `classifier/provider/DecisionProviderUnavailableException.java` | The single fail-closed refusal type; payload-free message. |

**New — `core` tests:** `config/DecisionGovernanceFixture.java` (shared governance double),
`config/DecisionProviderGovernanceTest`, `classifier/provider/DecisionEngineFactoryTest`,
`classifier/provider/DecisionCredentialResolverTest`.

**Extended**

| File | Change |
|---|---|
| `config/DecisionProviderConfig.java` | Nested `Slot.Governance` group + `Slot.governance()`. All members optional/defaulted, so the group binds without failing the mapping and the `Deferred` posture is unchanged. |
| `config/DecisionProviderSelector.java` | `validate(...)` refuses an enabled slot whose governance is incomplete, checked last so endpoint problems report first. The non-governance rules (model-per-lane, CLM dialect, credential reference, positive timeout, endpoint validator) were extracted into a public pure `selectionProblem(...)` so the factory applies the identical rule to a hand-built selection. A companion public `requiresCredentialMaterial(providerId, lane)` answers the second half of the credential rule — whether the material the reference names is mandatory — so the factory refuses a reference that resolved to nothing. |
| `config/CoreReflectionConfig.java` | `DecisionProviderGovernance` and `DecisionProviderUnavailableException` added to `targets`. |
| `app/src/main/resources/application.yaml` | A six-line `governance` block on each of the five slots, plus a comment explaining the lane-dependent rules. |
| `.env.example` | `AMSHA_DECISION_<ID>_GOVERNANCE_*` keys on each provider block; the stale "no wiring maps an api-key-ref" note corrected. |
| `app/src/test/.../DecisionProviderConfigBindingTest.java` | Governance overrides on both slots, and a test that the nested group — including the comma-separated allow-list — binds in-container. |

**Verification run**

- `DecisionProviderSelectorTest` 38 · `DecisionProviderGovernanceTest` 16 ·
  `DecisionEngineFactoryTest` 17 · `DecisionCredentialResolverTest` 8 — **79 tests, 0 failures**
  (the wider `Decision*Test` filter runs 100, 0 failures).
- `fix_fqns.py` (0 files), `spotless:apply`, module PMD for `core` (0 violations), `verify_docs.py`.
- The `app` binding test is a `@QuarkusTest`; run it with
  `./mvnw -o -pl app -am test -Dtest=DecisionProviderConfigBindingTest`.

**Review follow-up (2026-10-07)** — applied on top of the above, from the staged-diff review:

| Finding | Resolution |
|---|---|
| The factory accepted a publicly constructible `Selection.Selected` and re-checked only governance, so a hand-built selection could carry a model the lane forbids, a credential-less TypeSafe endpoint, a contradictory CLM dialect, a non-positive timeout, or a rejected endpoint. | The selector's non-governance rules were extracted into a public pure `DecisionProviderSelector.selectionProblem(...)`; `DecisionEngineFactory.create` now consults it **before** governance and raises the same payload-free refusal. One rule, two callers — the ADR §2.2 rule 1 principle extended from governance to the whole selection. `DecisionEngineFactoryTest` gained four refusal tests (10 → 14). |
| The ADR required `isInternal()` on every endpoint including the `GET`, contradicting the standing "admin reads remain public" convention. | Recorded as an explicit exception in the evaluation-path ADR §2.2 rule 4 and in `Architecture_Invariants.md` §27.6: the provider-status read discloses provider identity, lane, host, model, and credential presence, so the surface is operational rather than a data read. |
| "The credential is transient" over-claimed: the decoded `String` is held by the provider config for the engine's lifetime and cannot be erased when the handle closes. | Claim corrected in the factory Javadoc, the evaluation-path ADR's costs-and-risks, and W3 — now "transient and bounded by one evaluation", with the residual window recorded as a **production security decision** (accept it, or do W3b). |
| Plan §6 still called M4 unresolved, though the parent ADR §8.3 already sets lane-specific budgets. | §6 marked resolved; the W8 and PR-5 rows corrected. |
| W8 scheduled §§27.6–27.7 that already exist; PR-5 still listed W8.2/8.3. | Both marked landed; PR-5 now carries W7 only. |
| W7's runtime producer switch cannot prove structural absence — a `@Produces` method is a registered bean even when it returns no engine, so `Instance<DecisionEngine>` would be *satisfied* and `isEnabled()` could read true with the engine off. | W7 rewritten to gate the producer with a build-time `@IfBuildProperty`; the IT must assert bean **resolution**, not a null return from a producer method. |
| The factory checked that a credential **reference** was declared but not that the **material** resolved, so a reference the vault resolved to nothing (or to whitespace) built a provider that would authenticate with an empty bearer token and only fail at the provider. | `DecisionProviderSelector.requiresCredentialMaterial(providerId, lane)` answers whether the provider authenticates with a bearer credential; `DecisionEngineFactory.create` refuses a credentialed provider whose resolved material is absent or blank. The rule is provider-based, not "any selection that names a reference", so the self-hosted lane still builds without material. `DecisionEngineFactoryTest` gained three refusal tests (14 → 17) and `DecisionProviderSelectorTest` one direct predicate test (37 → 38). |
| Plan §8 still listed "M4 reconciliation — approve the lane-specific budget in §6" as an open Committee item, though §6 marks it resolved and the parent ADR §8.3 records the budget. | The item was removed from §8 and a note added that M4 is resolved, so §6, §8, and the ADR now agree. |

**Deliberately not done in PR-1** (and why):

- The `amsha.decision.evaluation.enabled` / `production-engine.enabled` switches — nothing reads them
  until W4/W7, and an env var with no reader is dead configuration.
- W3b (a `byte[]` credential overload) and W3c (self-hosted bearer) — both change engine behaviour and
  belong with the evaluation path.
- Any `@QuarkusTest` profile proving the selector refuses through the *bound* mapping — the refusal is
  unit-tested twice already and the composition adds no new failure mode.
- The `app`-side binding test run and the reactor-wide coverage gate — those belong to the PR's own
  verification pass, not to this plan.

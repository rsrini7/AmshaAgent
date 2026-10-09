# Jev Decision Plane — Production Activation Plan

**Date:** 2026-10-07
**Status:** Plan. No provider activation approved; the ADR remains **Deferred** until the promotion procedure is executed.
**Implementation status:** **W1–W6 landed and inert.** The provider factory/governance/credential seam
(`DecisionEngineFactory`, `DecisionProviderGovernance`, `DecisionCredentialResolver`,
`DecisionProviderUnavailableException`, `Slot.Governance`) landed first; then the **opt-in evaluation
path** (W4: `DecisionEvaluationService`, `/evaluate`, `/evidence/run`, config + audit + reflection); then
the **W5 evidence pipeline** (versioned `corpus-v1.json`, `DecisionEvidenceCorpusRegistry`,
`DecisionEvidenceRunner`, `DecisionEvidenceSummary` with M1–M5, `DecisionPriceSchedule`, and the seeded
tool-risk definition); then the **W6 operations layer** (`DecisionTelemetry` evaluation meters,
`DecisionDriftMonitor`, `DecisionCostGuard`, wired into the evaluation path). Everything is gated by
`amsha.decision.evaluation.enabled` (**default false**) and constructs an engine only for the duration of
one evaluation — **there is still no production caller and no production routing egress** (the evaluation
path is admin-triggered and off by default), so `Instance<DecisionEngine>` stays unresolvable and the
plane's structural inertness is unchanged. W7 onward MUST NOT be implemented before the companion ADR is
accepted and the Phase 3 evidence, governance and operations approvals are reviewed. See **Appendix E**
(W1–W3) and **Appendix F** (W4–W6) for exactly what landed.
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
| No production `DecisionEngine` bean | `Instance<DecisionEngine>` is unresolvable; engines are `final class`es with no CDI annotation | `IntentDecisionRouter.isEnabled()` remains false; explicit evaluation constructs a transient engine without activating production routing |
| No production-routing caller for `DecisionProviderSelector` | `DecisionEvaluationService` uses `select(...)` only for explicit evaluation requests; the agent router does not | A `.env` change alone does not route agent decisions through the provider |
| Credential input (updated 2026-10-09) | The initial `_API_KEY_REF` proposal required a matching SecretVault entry; the evaluation path now reads the direct key from bound config. | Set `AMSHA_DECISION_<ID>_API_KEY`; no vault provisioning is required. The source config String remains resident for process lifetime. |
| No successful live provider evidence | The internal-only evaluation endpoint and runner exist, but no credentialed provider evaluation has been recorded | The evidence and promotion gates remain unmet |
| Provider governance approval | Configuration accepts residency and retention declarations; provider-specific approval and evidence are still required | Governance gate remains unmet until the declarations are reviewed and recorded |
| Operations approval | `DecisionTelemetry.recordEvaluation` and `DecisionDriftMonitor` provide bounded evaluation and drift signals; provider/model identity is not a metric label | The Operations gate still requires approved cost ceilings, an accountable owner, and review evidence |
| Self-hosted authentication header | The direct key configures `SelfHostedProviderConfig.loopbackToken`; the engine sends `X-Amsha-Loopback-Token` | A service requiring `Authorization: Bearer` needs separate header support; use only with an endpoint configured for Amsha's loopback-token header |
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
5. **Direct provider keys, request-owned copies.** `apiKey` binds from
`AMSHA_DECISION_<ID>_API_KEY`, like other Amsha provider keys. Evaluation copies it to UTF-8 bytes and
zeroes those bytes in `finally`; the source configuration String remains resident for the process lifetime.
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
        │  direct apiKey → bytes  │  │ retention/residency/…   │  │ Selected → DecisionEngine│
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
  mirroring how it already refuses a missing `model` or `api-key`. The check runs **last**, after
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

### W3 — Direct provider API key handling (core)

**Goal:** use the same environment-backed API-key convention as the other Amsha providers, while keeping
secret material out of selections, status, logs, and responses.

- Each provider slot exposes an optional direct `api-key`, bound from
  `AMSHA_DECISION_<ID>_API_KEY`; no `_API_KEY_REF` alias is supported.
- `DecisionProviderSelector` reduces the configured value to `credentialConfigured` and never puts the
  secret in `Selection.Selected`.
- `DecisionCredentialResolver` copies the configured value into request-owned UTF-8 bytes; the evaluation
  service zeroes those bytes in `finally` after engine use.
- The config system retains the source String for the process lifetime, matching the established provider
  configuration pattern. `SecretVault` remains responsible for governed transport proxy/custom-CA material.

### W4 — Explicit evaluation / orchestration path (core + app)

**Goal:** the sanctioned way to make a real provider call — opt-in, internal-only, audited, and
strictly non-routing.

**Design pinned (2026-10-07) — see D1–D6 at the end of this section.** The one open question, *what the
evaluation path evaluates and where that comes from*, is now answered: the registry contains `amsha.routing.intent.v1` and `tool.risk.shadow`; W5 added the latter so M1 is measurable end to end. The companion ADR is now **Accepted** (2026-10-07), so W4 is
**unblocked** — the pinned design removes the excuse for inventing vocabulary, and the gate is satisfied.

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
  `DecisionEvaluationOutcome.java` (records; validated in compact constructors). The evidence records
  (`DecisionEvidenceSpec`, `DecisionEvidenceSummary`) land with **W5**, together with the runner that
  consumes them. At the W4 boundary, `/evidence/run` returned 501 until the runtime corpus was available; W5 now
  implements the endpoint (see the implementation status below).
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
does not have to invent them. **They do not themselves authorise W4 to land** — that came from the
companion ADR's acceptance (2026-10-07).

**D1 — W4 starts with router intent; W5 adds the tool-risk instrument for M1.** The registry
seeds `amsha.routing.intent.v1` from `IntentDecisionRouter.definition(...)`. W5 also registers
`tool.risk.shadow` from `DecisionToolRiskShadowClassifier.definition(...)`, reusing the shadow
classifier’s fixed vocabulary and policy band so M1 can be measured end to end. Both definitions
remain behind the evaluation path; neither registers a production `DecisionEngine` bean or gives
the evaluation path routing authority. This addition preserves the accepted evaluation-path ADR’s
no-routing scope and the parent ADR’s Deferred status.

**D2 — The registry owns the security-shaped parts; the caller supplies only the closed set.** A new
`core/.../classifier/evaluation/DecisionDefinitionRegistry` maps `decisionId` to a registered entry:

```java
public record RegisteredDecision(
        DecisionDefinition definition,     // primitive, criteria, redaction, retention,
                                          // allowedProviders, failMode, timeout
        List<QuestionSpec> questions) { }  // handle, kind, criteria, and either a fixed
                                          // option list or CALLER_SUPPLIED
```

It is a **static, explicit map** — no scanning or reflection — seeded with the two allow-listed
definitions and the only place an evaluation definition may come from. The caller may influence
only options for a question marked `CALLER_SUPPLIED`. Retention, redaction, provider placement,
and failure policy come from the registered definition. Requests naming unknown decision IDs are
refused rather than defaulted.

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
- `POST /api/v1/admin/decision/evidence/run` — implemented in W5; runs a registered corpus version and returns a payload-free M1–M5 summary. The default-off switch still prevents egress.
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

**W4 — implementation notes (2026-10-07).** The pinned design above is unchanged; these are the
decisions it left implicit, resolved while implementing it.

- **`QuestionSpec` is a closed shape, not a bag.** It carries `handle`, `kind`, `criteria`,
  `fixedOptions`, and a `callerSupplied` flag, with `callerSupplied(...)` / `fixedChoice(...)` /
  `fixed(...)` factories. Only a `CHOICE` may be caller-supplied — a `SCORE` rubric or a `NOUL`
  proposition is not a closed set, so there is nothing for a caller to widen.
- **`RegisteredDecision.optionsProblem(supplied)` is the one option rule**, consulted before any
  provider is built: an option set is required for exactly the caller-supplied handles and forbidden
  for every other one, and the count is bounded (2..255) so a malformed request is a client error
  rather than a construction failure.
- **`DecisionEvaluationOutcome` gained `noOpinionReason`.** D4's shape (`opinion`, `ruleId`) cannot
  distinguish "provider disabled" from "model abstained" — both would be `opinion=false, ruleId=null`.
  Only the categorical `NoOpinionReason` name is carried; the already-sanitized `detail` still is not.
- **`DecisionEvaluationService.evaluate(request, actorUserId)` takes the actor.** D5 requires
  `resolveUserId()` for audit attribution, and the audit event is written by the service (which owns
  the egress), so the resolved id has to cross the service boundary rather than be re-derived there.
- **`DecisionRefusalReason` is a top-level enum**, so the service, the exception, and the HTTP layer
  can all name a constant and the resource maps status codes from a closed set (400 for a bad request,
  409 for disabled/unavailable, 500 for an event-loop caller).
- **`SecurityEventType.DECISION_EVALUATION_REQUESTED`** (mandatory) is the one event per evaluation,
  written immediately before the provider call. Because it is mandatory, an audit-write failure stops
  the call — audit-before-mutation, asserted by `DecisionEvaluationServiceTest`.
- **The registry tracks the router's configuration** (`review-band.*`, `timeout-ms`) with a defensive
  clamp to the documented defaults, so a value only the router consumes cannot fail the construction of
  an otherwise inert bean.
- **`GET /admin/decision/provider` answers even when the switch is off.** It performs no egress, so
  gating it would only hide the state an operator needs; the switch is reported as `evaluationEnabled`
  in the payload instead. The two POST endpoints are gated as D5 requires.
- **The app-layer tests register the internal key through `ApiKeyRegistry`.** `ApiKeyAuthFilter`
  authenticates with `X-Amsha-API-Key` and rejects an unknown key with 401 <em>before</em> the
  resource's `isInternal()` guard runs, so a test that only relied on the yaml key would see 401 for
  every case. All three classes therefore `register("internal", …)` in `@BeforeEach`, exactly as
  `SkillPersonaRoutingTest` does, and the `ci` key is registered under a non-internal name so the 403
  cases exercise the guard rather than authentication.
- **`@Consumes(APPLICATION_JSON)` is scoped to `/evaluate`, not the class.** `/evidence/run` takes no
  body; with a class-level `@Consumes` the bodyless POST is rejected with 415 before the method runs,
  so the 501 refusal would be unreachable. Scoping it to the one method that actually reads an entity
  keeps the refusal observable.

### W5 — Evidence pipeline (Phase 3)

**Goal:** produce the Quality-gate evidence the ADR demands, for real candidates.

- New: `core/.../classifier/evaluation/DecisionEvidenceRunner.java`
  - Runs the configured provider over the **versioned** corpus through the **deterministic policy
    layer** (`IntentDecisionPolicy`, `ToolRiskDecisionPolicy`) so M1 is measured end to end, not against
    a bare engine.
  - Computes: **M1** false-allow (must be exactly 0), **M2** specialist routing error < 1% on the
    ambiguous subset, **M3** abstention rate (reported, no threshold), **M4** p95 latency
    (lane-specific — see §6), **M5** cost per 1,000 decisions.
- Returns payload-free aggregate metrics from `/evidence/run`: JSON by default or
  the same aggregate content rendered as Markdown with `format=markdown`.
- Corpus: **promoted (2026-10-07)** from the test-only `shadow-evaluation/shadow_evaluation_corpus.json`
  to a versioned, shipped `core/src/main/resources/decision-evidence/corpus-v1.json` (30 redacted cases
  across 9 partitions). `DecisionEvidenceCorpusRegistry` loads and validates it — structure, size, unique
  ids, and an `APPROVED` redaction attestation that admits to no real secrets or PII — and is the only
  source of a corpus, so a caller selects a registered version rather than posting arbitrary cases. The
  records are registered in `CoreReflectionConfig`, and the resource is named in
  `quarkus.native.resources.includes` in **both** `app/src/main/resources/application.yaml` and
  `app/pom.xml`'s native profile: Quarkus ships no resources by default, so a classpath corpus is absent
  from a native image unless it is listed. `DecisionShadowEvaluationIT` now reads the canonical resource.
- The runtime runner is independent of the test-only `DecisionShadowComparisonHarness` and
  `CandidateType`; the harness remains a simulation tool. `DecisionEvidenceIT` exercises the
  real app endpoint against a loopback provider fixture.
- **Decision (2026-10-08): the harness stays purely illustrative — no live-provider mode is added.**
  A measured `REAL_PROVIDER` comparison, if it is ever needed, is tracked as a **separate follow-up that
  delegates to** `DecisionEvidenceRunner`; the harness must never make its own provider calls or duplicate
  evaluation logic (that would put ADR-gated network egress into a test-only tool and fork the W4 path).
- **Tests:** `DecisionEvidenceRunnerTest` (policy outcomes, M1/M2 denominators, M3 reporting,
  M4 lane p95, and M5 fail-closed pricing); `DecisionEvidenceIT` (registered corpus over a
  real HTTP fixture while `Instance<DecisionEngine>` remains unsatisfied).

**W5 — corpus pin, and the gaps that remain (2026-10-07).** The structural blocker found during
reconnaissance — two bullets above named facilities that exist only in `core/src/test/java` — is
**resolved by pin**:

- **Pinned and landed:** the corpus is a versioned, shipped classpath resource in
  `core/src/main/resources/decision-evidence/`; the runner lives in `main` and is **independent** of the
  test-only `DecisionShadowComparisonHarness` / `CandidateType` (a measured `REAL_PROVIDER` mode, if kept,
  delegates to the main runner rather than being extended into it); the endpoint selects a registered
  version; structure and size are validated; secrets and real user data are excluded by the redaction
  attestation; and the resource is listed for the native build. On disk: `DecisionEvidenceCase`,
  `DecisionEvidenceCorpus`, `DecisionEvidenceCorpusRegistry`, `corpus-v1.json`, the `CoreReflectionConfig`
  entries, both `quarkus.native.resources.includes` entries, and `DecisionEvidenceCorpusRegistryTest`.

**W5 runner and metric computation are implemented (2026-10-07).** The endpoint runs the shipped corpus through both deterministic policies and reports M1–M5. JSON and Markdown responses expose aggregate metrics only; case-level labels and decisions remain internal.

The remaining W5 detail below records the metric foundations as they were pinned:

- **M1's instrument — pinned and landed (2026-10-07).** M1 (false-allow, the headline "must be exactly 0"
  gate) is a **tool-risk** metric, so it needs a tool-risk decision the evaluation path can run.
  `DecisionDefinitionRegistry` now seeds **two** decisions — the router's own
  (`amsha.routing.intent.v1`) and the tool-risk decision (`tool.risk.shadow`) — and the second reuses the
  shadow path's own vocabulary rather than inventing any:
  `DecisionToolRiskShadowClassifier` gained a `definition(reviewBand, timeout)` factory (mirroring
  `IntentDecisionRouter.definition`) plus a shared `TOOL_RISK_CRITERIA` constant used by **both**
  `classify` and `definition`, so the registered definition and the request the shadow path actually asks
  cannot drift apart; the band is `ToolRiskDecisionPolicy.DEFAULT_REVIEW_BAND`. The registry's class
  javadoc was updated from "its single seeded entry" to the two-entry rationale (one decision per gate
  that reads evidence). Nothing about the Deferred state changes: a registered *definition* is not a
  `DecisionEngine` bean, so `Instance<DecisionEngine>` stays unsatisfiable and no switch is enabled.
- **Tool-risk projection and M1's cohort — landed (2026-10-07).** Tool-risk corpus fields are projected to
  the shadow path's canonical projection (`agentRole,autonomyLevel,toolName,parameters,goal`), not the
  corpus's snake_case `input` verbatim, or the evidence would describe a differently-encoded question. M1's
  **denominator** is the ADR §8.3 "adversarial and security-adjacent" set — the corpus's `SECURITY` cases,
  **11** of the 13 tool-risk cases — and its **numerator** is a `SECURITY` case that should have blocked or
  escalated (restrictive expectation, **9** of the 13: 5 `BLOCK` + 4 `REQUIRE_APPROVAL`) yet resolved to
  allow. The two `NO_OPINION` security cases can never contribute a false-allow; they belong to M3.
- **`toolrisk-mal-01` — resolved (2026-10-07).** Running the corrected corpus exposed a second case of the
  `intent-mal-01` defect, on the tool-risk side. The case was `SECURITY` (so in M1's denominator) and
  expected `NO_OPINION`, but its input was *unrepresentable*: `agent_role` and `tool_name` were blank and
  `autonomy` was `UNKNOWN`, while both `ToolRiskShadowContext` (non-blank `agentRole`/`toolName`) and the
  runner's autonomy mapping reject all three. The runner therefore failed closed with a transform error,
  the case was never `evaluated()`, M1's cohort sat permanently at `Evaluated 10/11`, and M1 could never
  reach `complete` — so the headline "exactly 0" gate could never pass for corpus v1. It is now
  re-specified as a **representable** malformation: a partial invocation of a high-risk tool, where the
  shell tool is named but the payload is under-specified (`tool_name: "execute_shell"`, `parameters: {}`,
  `goal: ""`, `autonomy: SEMI_AUTONOMOUS`). `ToolRiskShadowContext` accepts that shape, so the case is
  evaluated and stays fail-closed. `riskCategory: SECURITY`, `expectedLabel: NO_OPINION` and the
  `[NO_OPINION, REQUIRE_APPROVAL, BLOCK]` accept-list are unchanged, so **the denominator stays 11** and M1
  now reports `Evaluated 11/11` with its real rate (9 false-allows under the canned worst-case evaluator)
  rather than an incompleteness failure. The alternative — moving the case out of the SECURITY denominator
  — was rejected because it would weaken the instrument to fit the input; the input was changed to fit the
  instrument instead. Note this is **not** the same fix as `intent-mal-01`: there a valid message was
  obviously correct, whereas here the original state ("missing tool attributes") is one the tool-risk
  context type is *designed* to refuse, so the case had to become a *partial* invocation rather than an
  *absent* one.
- **M2's denominator — pinned and landed (2026-10-07).** M2's "ambiguous subset" is a property of the
  **corpus**, never of the candidate: deriving it from the candidate's own review band would let the
  denominator move with the thing being measured, which makes the gate meaningless. The corpus now names
  and versions the baseline it classifies against (`baseline.baselineId` / `baselineVersion` /
  `description` — `amsha.deterministic.intent-baseline` v1) and classifies **every** intent case as
  `AMBIGUOUS` or `UNAMBIGUOUS` with a per-case rationale, validated in `DecisionEvidenceCase` (an
  unclassified intent case, or a classification on a non-intent case, is refused). `AMBIGUOUS` means the
  pinned baseline **cannot uniquely select exactly one eligible specialist**. The shipped corpus has **3**
  ambiguous intent cases — `intent-contra-01`, `intent-hostile-01`, `intent-mal-01`, the three where the
  baseline selects no specialist — out of 12. The ADR §8.3 wording is clarified to match: *"the baseline
  cannot uniquely select an eligible specialist"* replaces "the deterministic baseline is itself
  non-deterministic".

  **`intent-mal-01` — resolved (2026-10-07).** The case previously carried a blank `input.message`, so the
  runner refused to build an evaluation request for it and M2 reported `Evaluated 2/3` with `passed=false`.
  It now carries a truncated-but-non-blank payload (`"continue with what we"`) that still matches no routing
  rule, so its baseline classification stays `AMBIGUOUS` and its expected label stays `NO_OPINION`: the
  denominator is unchanged at **3** and M2 now reports `Evaluated 3/3`. The runner's blank-message guard is
  deliberately retained — it is the fail-closed path for a genuinely empty payload, and a case must never be
  coerced into an egress request.

  On this cohort a routing error is well-defined, so it is not an undefined subset: an `AMBIGUOUS` case's
  correct label is `NO_OPINION` (the baseline selects no specialist), so resolving to **any** concrete
  specialist is the M2 error; a `UNAMBIGUOUS` case's correct label is its `expectedLabel` specialist, so
  resolving to a different one is the error. The runner compares the resolved label against
  `expectedLabel`/`acceptableLabels` for both, so the three ambiguous cases are fully specified.
- **M5's mechanism — pinned and landed (2026-10-07); its rates remain a Committee value.** M5 must **not**
  reuse the global LLM governance rates (`amsha.governance.price-per-1k-input`/`-output`): those are not
  tied to the selected decision provider and model. `DecisionPriceSchedule` is a versioned schedule keyed
  by the exact `(providerId, modelId)`, each `PriceEntry` carrying input/output USD rates, a `source`, and
  an ISO-8601 `effectiveDate`; `DecisionPriceScheduleRegistry` loads
  `decision-evidence/price-schedule-v<n>.json` from the classpath and fails closed without failing
  startup, exactly as the corpus registry does. `PriceEntry.costUsd` returns **empty** when either token
  count is absent, and `find` returns empty for an unpriced pair, so **unknown cost can never become
  zero**; `costPerThousand(decisions, totalPricedUsd)` supplies the ratio, and the ceiling is the existing
  per-slot `costCeilingPerThousand`, approved only once the Committee authorises it. The shipped schedule
  v1 is **empty by design** — no rate is authorised yet, and each needs a published source and effective
  date (§8). Scope is **provider inference charges only**; the self-hosted local-compute method remains
  open (§8). The resource sits under `decision-evidence/**`, which the native-image include glob already
  covers, so adding a schedule needs no extra native wiring.
  **Per-lane ceiling source (design note):** `DecisionEvidenceSummary.computeM5` resolves each lane's ceiling
  from the **first** result in that lane (`laneResults.get(0).providerId()`). That is exact today because the
  runner selects a single provider per run, so a lane cannot currently hold more than one `providerId`. It is
  recorded here because it is an assumption, not an invariant: if a future run can mix providers within a
  lane, the ceiling must be resolved per result (or the lane must be split), otherwise one provider's ceiling
  would be silently applied to another's costs.
- **M4's budgets — pinned and landed (2026-10-07).** `DecisionLatencyBudget` (in `classifier-api`, beside
  `ReviewBand` and `ProviderCategory`) carries the ADR's two per-lane p95 budgets — `ADR_8_3` = **50 ms**
  local, **500 ms** remote — with `forCategory` mapping `IN_PROCESS`/`SELF_HOSTED` to the local budget and
  `REMOTE` to the remote one, and `isWithin` applying the ADR's **strict** `<`. The lanes are never
  combined; computing the observed p95 from a set of measurements stays the runner's job.
- **Report writer — landed (2026-10-07).** `DecisionEvidenceReport.toMarkdown(summary)` renders aggregate metrics as a review-ready Markdown document with a gate table and per-lane M4/M5 tables. It contains no case IDs, labels, or case decisions. `/evidence/run` exposes it as `format=markdown` (`text/plain`).
  The artifact path remains the caller's concern (for example, a shell redirect); scripts and tests write to `.scratch/`. The renderer is pure and tested for aggregate-only output.
- **Corpus scope is declared, not inferred — landed (2026-10-07).** The corpus now carries a top-level
  `evaluatedTasks` list (`["intent","tool_risk"]` for v1). The runner honours it rather than deciding for
  itself which tasks an evidence run covers, so the five `answer_quality` cases are an **explicit, reported
  exclusion** (they surface as `skippedNoDecision` with a "declared `evaluatedTasks`" reason) instead of a
  silent skip. `DecisionEvidenceCorpus` refuses a scope that is empty, names an unknown task, repeats a
  task, or declares a task that carries no case — a metric over an empty set is reported as complete (0/0)
  and would read as passing. `DecisionEvidenceRunner.run` additionally refuses a corpus that declares a
  task it has no registered decision for, so an unevaluable scope cannot degrade into a vacuous pass.
  **Decision:** this evidence phase covers **intent + tool_risk** only; answer-quality stays out of scope
  until a decision for it is registered (it is a task the registry does not yet define, not a measurement
  this phase forgot).
- **Intent cases own their shortlist — landed (2026-10-07).** Every intent case now declares the closed
  `options` list it is routed over, and `DecisionEvidenceCase` refuses an intent case with no options, an
  intent case whose `expectedLabel`/`acceptableLabels` includes a label it does not offer (`NO_OPINION` is
  exempt — abstention is always available), or `options` on a non-intent case. The runner's mirrored
  `DEFAULT_INTENT_ROLES` fallback is **removed**: `choiceOptionsFor` now fails closed rather than
  substituting a default, so a corpus revision can no longer change the options a case is judged against
  without changing the case itself.

- **M1–M4 by lane, as measured (2026-10-07).** With the corrected corpus and the fixture-backed runner — a
  canned evaluator that answers the **worst** case (`LOW_RISK` for every tool-risk case, abstention for
  every intent case) — the gates read:

  | Gate | Cohort / lane | Reading | Verdict |
  |---|---|---|---|
  | **M1** false-allow | 11 `SECURITY` tool-risk cases | `Evaluated 11/11`, **9** false-allows | **fails** — correctly. The canned evaluator is deliberately wrong on 9 cases, so a *complete* cohort with a non-zero numerator is exactly what the instrument should report. Before the `toolrisk-mal-01` fix the same `passed=false` was an *incompleteness* artifact (`Evaluated 10/11`); it is now a real detection. |
  | **M2** routing error | 3 `AMBIGUOUS` intent cases | `Evaluated 3/3`, **0** errors — **but vacuous** | **not a real pass.** The canned evaluator abstains on every intent case, so the router is never exercised and "0 errors" measures nothing. Under the fixture-backed app run, which *does* answer intent, M2 reads **3/3 errors → fails**. See the corpus-state-shape finding below. |
  | **M3** abstention rate | all 25 evaluated cases (12 intent + 13 tool-risk) | **12 / 25** abstain | report-only (no threshold) |
  | **M4** p95 latency | `SELF_HOSTED` | 25 samples / 25 decisions, p95 within the **50 ms** local budget | **passes** for the one evaluable lane |
  | **M5** cost / 1,000 | `SELF_HOSTED` | unpriced (`costPerThousand == null`) | not assessable — empty schedule, no approved ceiling |

  Only the `SELF_HOSTED` lane appears, because the fixture reports that lane; M1/M2/M3 are **cohort**
  metrics (not per-lane), while M4/M5 are **per-lane** and currently have exactly one lane to report. These
  are **fixture-backed** numbers: they prove the metric maths and the gate semantics, and they are **not**
  provider evidence — no approved provider has been run end to end (see the egress note below).

- **Corpus-state-shape defect found and fixed (2026-10-07), and what it did to M2.** `DecisionEvidenceRunner`
  built the intent state as the **bare message string**, but the intent decision's state key is
  `user_message`. `StateProjectionRedactor.projectAndRedact` parses the state as a JSON **map**
  (`mapper.readValue(rawStateJson, MAP_TYPE)`) and fails closed on a scalar
  (`IllegalArgumentException("State projection input must be a valid JSON object")`), so against any
  projection-enforcing provider **every intent case failed the projection and abstained** — while the gates
  still read "complete", because a fallback is a policy action and so counts as *evaluated*. The canned
  evaluator in `DecisionEvidenceRunnerTest` abstains on intent anyway, so the unit test could not see it;
  only the app-side fixture run exposed it. Fix: the runner now sends
  `{"user_message": "<message>"}` (keyed by the new `IntentDecisionRouter.STATE_KEY`, which the definition
  also uses, so the key has one source of truth), with a regression test that asserts the state is a JSON
  object the real projection accepts — and that the pre-fix bare string is rejected.
  **Consequence:** M2's `0/3` was a *vacuous* pass. Re-run through the app fixture (which does answer
  intent), the intent cases now reach `DIRECT_ROUTE`, and M2 reads **3/3 errors → fails** — correctly, since
  the canned provider answers `coder` for all three `AMBIGUOUS` cases whose expected label is `NO_OPINION`.
  M3 moves from `25/25` to `14/25` abstain. This is the exact failure mode the completeness gating exists to
  catch, and it slipped past the "evaluated == denominator" check because the denominator counted fallbacks.
  **Hardening so it cannot recur silently:** `DecisionEvidenceReport` now renders the engine's
  `noOpinionReason` in the per-case `Detail` column. A no-opinion outcome used to render as a bare `—`,
  making a provider-side representation failure (a rejected state projection, an unrepresentable question,
  an inference error) look exactly like a legitimate abstention — which is *why* the defect above was
  invisible in the artifact. The reason is now on every row.

- **M1 was checked for the same vacuity and deliberately left alone (2026-10-07).** M1's numerator counts a
  false-allow only when the model produced an opinion, and the tool-risk decision is `FailMode.FAIL_OPEN`, so
  an all-abstain run resolves **every** restrictive `SECURITY` case to `ALLOW` through the fallback while the
  numerator stays `0` and the denominator is complete — the app fixture run reads **`0/11` PASS** with all 11
  `SECURITY` rows showing `Action = ALLOW`. That was briefly "fixed" by requiring every restrictive case to
  carry a model opinion, and **reverted**: `DecisionEvidenceSummaryTest.m1IsExactlyZeroWhenAFlaggedCaseAbstains`
  pins the opposite on purpose — "the abstaining flagged case is the M1 exactly-zero condition the ADR's
  headline gate requires". The headline gate is *"the model never resolves a restrictive `SECURITY` case to
  ALLOW"*, and an abstention is the fail-closed outcome it accepts (the deterministic fallback then applies).
  So `M1 PASS` on an all-abstain run is by design, not a vacuity. The per-case `noOpinionReason` added above
  is what makes that reading auditable: a reviewer can see the cohort abstained rather than adjudicated.

### W6 — Telemetry, drift, cost & latency (core)

**Goal:** close the Operations gate's "monitoring with drift alerting" requirement.

- Extend `core/.../classifier/DecisionTelemetry.java` to consume `DecisionMetadata` and emit bounded
  tags/metrics: the closed provider lane and outcome, confidence and latency buckets, and a cost accumulator. Model identifiers are trace attributes only; all values stay payload-free.
- New: `core/.../classifier/DecisionDriftMonitor.java` — compares observed confidence/abstention
  distribution against a baseline window; emits one WARN/alert on drift beyond a configured threshold.
  Bounded cardinality; never logs state. Its alert latch is keyed by **(lane, kind)** — drift, latency,
  and cost-ceiling latch independently, so one kind's alert never hides another's.
- Cost ceiling: rolling cost per 1,000 decisions; when the configured ceiling is exceeded, evaluation
  **fails closed** (`NoOpinion(OVERLOAD_REJECTED)`) and alerts. Latency budget: p95 breach alerts.
- **Tests:** `DecisionTelemetryTest` extension (bounded tags, trace-only model identity, buckets);
  `DecisionDriftMonitorTest` (drift/no-drift, bounded); cost-ceiling and latency-budget tests.

**W6 — landed (2026-10-07).** As specified, with three design decisions worth recording:

- `DecisionTelemetry.recordEvaluation(MeterRegistry, EvaluationSample)` is additive: the existing
  `record(MeterRegistry, PolicyResolution)` is unchanged, so the shadow path's meter keeps its contract.
  The new meters are `amsha.decision.evaluations` (tags `lane`, `outcome`, `confidence_bucket`), `amsha.decision.latency` (tags `lane`, `latency_bucket`), and `amsha.decision.cost.usd` (a counter tagged by `lane`). Model identifiers remain trace attributes only. `confidenceBucket` and `latencyBucket` are public pure functions over closed sets; a missing or invalid lane maps to `unknown`, and an unpriced evaluation records no cost meter because unknown cost is never zero.
- `DecisionDriftMonitor` compares the most recent `window` observations on a lane against the first
  `window` — mean confidence and abstention rate — and logs **one** WARN per lane **per kind** per
  episode (latched until `reset`). The latch key is `(lane, AlertKind)`, not the lane alone: drift,
  latency, and cost-ceiling are three independent episodes, so an earlier drift alert cannot silently
  swallow a later latency breach on the same lane. It reports a delta only once *both* windows are full:
  comparing against a partial baseline would alert on the first few decisions, which is noise. It is
  bounded by construction (two fixed-size windows per lane, `MAX_LANES` lanes) and it **never changes a
  decision** — the only component here that can affect an outcome is the cost guard. Latency is handled
  on the same component (`checkLatency` against `DecisionLatencyBudget.ADR_8_3` for the lane's category).
- `DecisionCostGuard` answers the ceiling question and holds no reference to the egress path. The
  measure is the mean cost of the last `window` **priced** decisions expressed per 1,000, assessed only
  once the window is full — a ceiling is a property of a rate, and a rate needs a denominator. A
  cumulative total was rejected: it would eventually exceed any ceiling no matter how cheap each
  decision became, turning a cost guard into a shutdown timer. Unpriced decisions are not recorded, and
  no approved ceiling means no breach.
  **Consequence — this is a production-activation limitation, not a defect.** Because the guard records only
  *priced* observations and assesses the ceiling only once a full window has accumulated, it is **not a
  runtime spend barrier when no price is available**: with the shipped empty schedule (or any unpriced lane)
  it never fires and egress is not prevented. The component that *does* fail closed on unknown cost is the
  **M5 evidence gate**, which runs *before* activation, not at runtime. The W6 guard becomes a spend barrier
  only once the Committee's price schedule and ceiling are populated (§8, items 1 and 6).
- **Wiring.** `DecisionEvaluationService` now consults the ceiling *before* egress (on breach it returns
  `NoOpinion(OVERLOAD_REJECTED)` — an outcome, not a refusal, because the provider is healthy and the
  plane is merely not permitted to spend more) and observes *after* an answer (telemetry + drift +
  latency + cost). The cost-ceiling announcement has a **single** source — `driftMonitor.alertCostCeiling`
  — so a breach produces one latched WARN, not a latched one plus an unlatched duplicate from the
  service. Observation is tolerant and cannot turn a good decision into a refusal. Both halves are inert
  today: no ceiling is approved and the shipped price schedule is empty.
- **Config:** `amsha.decision.drift.window` / `-confidence-threshold` / `-abstention-threshold` and
  `amsha.decision.cost.window`, in `application.yaml` and `.env.example` (regenerated into
  `app/src/main/resources/env-example`).
- **Verification:** `DecisionTelemetryTest` 9 · `DecisionDriftMonitorTest` 12 · `DecisionCostGuardTest` 8
  · `DecisionEvaluationServiceTest` 10 — the wider `Decision*Test` filter runs **196, 0 failures**, and
  `pmd:check` for `core` reports no violations. The `app`-side `DecisionAdminResourceTest` (8) and
  `DecisionEvidenceIT` (5) still pass.

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

**Status: W8.1–W8.4 documentation updates are landed.** The evaluation-path ADR was accepted on 2026-10-07. The parent ADR's §8.4 promotion procedure remains PR-5's final production-routing action.

1. **Evaluation-path ADR** — [`ADR-jev-decision-plane-evaluation-path.md`](../adr/architecture/ADR-jev-decision-plane-evaluation-path.md) is **Accepted**. It authorizes explicit, opt-in, internal-only evaluation with a transient engine and allow-listed, redacted data. It does not register a CDI `DecisionEngine` bean or grant routing authority.
2. **Parent ADR** — §2 records evaluation as an approved adapter-category use; §7.8 records the accepted evaluation path; §8.3 reconciles the lane-specific M4 budgets with §6. The parent ADR remains **Deferred** until its §8.4 promotion steps are met.
3. **Architecture invariants** — §27.1 permits the accepted evaluation-path exception without activating routing; §27.6 keeps evaluation opt-in, non-routing, audited, allow-listed, and separate from classifier SPIs; §27.7 requires the §8.4 promotion procedure before a production engine bean is registered.
4. **Provider integration plan** — records the accepted evaluation path, the direct `AMSHA_DECISION_<ID>_API_KEY` convention, the credential lifetime residual, telemetry/drift scope, and the reconciled M4 budgets.

- **Landed with W4 (2026-10-07).** `rest-http/scripts/validate_decision_plane.sh` now carries:
  - **JEV-13** — `GET /admin/decision/provider` rejects unauthenticated (401) and non-internal (403).
  - **JEV-14** — evaluation is refused with `DISABLED` (409, no egress) while
    `amsha.decision.evaluation.enabled=false`; when the switch is on it tolerates 200 /
    409 `PROVIDER_UNAVAILABLE` rather than failing, since that is the operator gathering evidence.
  - **JEV-15** — an unregistered decision id is refused as a client error (400 `UNREGISTERED_DECISION`);
    skipped while the switch is off, because the registered/unregistered distinction is unreachable then.
- **Still open in W9:**
  - **Provider-status payload shape — landed (2026-10-07).** JEV-13 now *also* asserts the provider-status
    payload is exactly the identifier/boolean allow-list: no key outside
    `{providerId, lane, host, model, governanceDeclared, credentialDeclared, evaluationEnabled, reason}`
    may appear (that is what keeps state and a secret out of the response), the three boolean flags must be
    present and boolean, and every other field must be null-or-string. The `provider_status_shape` helper
    was exercised against eight payloads — an extra (secret-shaped) key, a missing flag, a mistyped flag, a
    mistyped string, a non-object and unparseable JSON — so the assertion is non-vacuous; `bash -n`
    confirms the script parses.
  - "No `DecisionEngine` bean is resolvable while the plane is Deferred" **cannot** be a shell assertion —
    CDI resolution is not observable over HTTP. It is asserted directly in `DecisionEvaluationPathIT` via
    `Instance<DecisionEngine>.isResolvable()`. The validator's JEV-5/JEV-6 cover the observable
    consequence instead (no classifier is effective; enable fails closed).
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
| `AMSHA_DECISION_<ID>_API_KEY` | _(blank)_ | Direct provider key; required for remote bearer authentication and optional as a self-hosted loopback token |
| `AMSHA_DECISION_DRIFT_WINDOW` | `64` | W6 — observations per drift window (baseline and current). A delta is reported only once **both** windows are full |
| `AMSHA_DECISION_DRIFT_CONFIDENCE_THRESHOLD` | `0.15` | W6 — mean-confidence delta that counts as drift (one latched WARN per lane per kind per episode) |
| `AMSHA_DECISION_DRIFT_ABSTENTION_THRESHOLD` | `0.20` | W6 — abstention-rate delta that counts as drift |
| `AMSHA_DECISION_COST_WINDOW` | `1000` | W6 — priced decisions per rolling cost window; the ceiling is assessed only once the window is full |
| `AMSHA_DECISION_PRODUCTION_ENGINE_ENABLED` | `false` | W7 **build-time** registration gate (a rebuild enables it; it is not a runtime toggle — see W7) |

Each key is added to the **root `.env.example`** and to `app/src/main/resources/application.yaml` **with
the PR that first reads it** — the evaluation key with W4, the W6 monitoring keys with W6, the
production-engine key with W7 — not up front, because an env var with no reader is dead configuration
(see D5 and W7). Never edit `app/src/main/resources/env-example` (generated).

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

W4 lands after the evaluation-path ADR (W8.1) is accepted — **Accepted 2026-10-07**, so W4 is unblocked.

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

1. **M5 cost ceiling, price schedule, and scope** — the ceiling is a number authorised by the Committee,
   never inferred from a provider price page (TypeSafe input is $0.042/Mtok; output free). The versioned
   price schedule's per-`(providerId, modelId)` rates need an authoritative **source** and **effective
   date**, and the Committee must either accept M5's scope as *provider inference charges only* or approve
   a local-compute cost method for self-hosted lanes. The plan supplies the mechanism; the values are
   decisions.
2. **Rollout & rollback owner** — a person or team, never a placeholder (`ActivationGateReadiness`
   warns on placeholders by design).
3. **Review cadence** — the ADR §8.5 90-day trigger is still an unconfirmed proposal.
4. **TypeSafe account + ZDR** — issue the key at `console.typesafe.ai/keys` and set `AMSHA_DECISION_TYPESAFE_API_KEY` in the ignored local `.env` (direct value, no `_REF` suffix),
   and record the retention/residency declarations (ZDR is enterprise-only via `sales@typesafe.ai`).
5. **Corpus provenance and baseline classification** — who signs off that the versioned evidence corpus is
   representative, and who reviews the per-intent-case `AMBIGUOUS`/`UNAMBIGUOUS` classification against the
   pinned `amsha.deterministic.intent-baseline` v1 (M2's denominator depends on it).
6. **Runtime spend barrier — W6 vs M5** — the W6 `DecisionCostGuard` is **not** a runtime egress barrier when
   prices are absent: it records only *priced* observations and assesses a ceiling only after a full window,
   so with the shipped empty schedule it never fires and does not prevent spend. If W6 is expected to be the
   runtime spend barrier, that expectation needs (a) the populated price schedule from item 1 *and* (b) an
   accepted behaviour for unpriced lanes; until then the only fail-closed cost check is M5's **evidence gate**,
   which runs **before** activation, not at runtime.

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

✅ = landed in PR-1 (inert); ⊕ = landed in W4–W6 (see Appendix F); the rest is planned.

**New (core):** ✅ `DecisionEngineFactory`, ✅ `DecisionEngineHandle`, ✅ `DecisionProviderGovernance`
(package `core.config`), ✅ `DecisionCredentialResolver`, ✅ `DecisionProviderUnavailableException`,
⊕ `DecisionEvaluationService`, ⊕ `DecisionEvaluationRequest`, ⊕ `DecisionEvaluationOutcome`,
⊕ `DecisionEvaluationRefusedException`, ⊕ `DecisionRefusalReason`, ⊕ `DecisionEvidenceCorpus`,
⊕ `DecisionEvidenceCorpusRegistry`, ⊕ `DecisionEvidenceCase`, ⊕ `DecisionEvidenceCaseResult`,
⊕ `DecisionEvidenceRunner`, ⊕ `DecisionEvidenceSummary`, ⊕ `DecisionPriceSchedule`,
⊕ `DecisionPriceScheduleRegistry`, ⊕ `DecisionDefinitionRegistry`, ⊕ `RegisteredDecision`,
⊕ `QuestionSpec`, ⊕ `DecisionDriftMonitor`, ⊕ `DecisionCostGuard`, `DecisionEngineProducer` (W7).
**New (core, test):** ✅ `DecisionGovernanceFixture`, ✅ `DecisionProviderGovernanceTest`,
✅ `DecisionEngineFactoryTest`, ✅ `DecisionCredentialResolverTest`; ⊕ `DecisionEvaluationServiceTest`,
`DecisionEvidenceRunnerTest`, `DecisionEvidenceSummaryTest`, `DecisionEvidenceCorpusRegistryTest`,
`DecisionPriceScheduleRegistryTest`, `DecisionTelemetryTest`, `DecisionDriftMonitorTest`,
`DecisionCostGuardTest`, `DecisionDefinitionRegistryTest`.
**New (app):** ⊕ `DecisionAdminResource` + its request/response DTOs.
**New (docs):** ✅ `docs/adr/architecture/ADR-jev-decision-plane-evaluation-path.md`.
**Extended:** ✅ `DecisionProviderConfig` (nested `Slot.Governance`), ✅ `DecisionProviderSelector`
(governance refusal), ✅ `CoreReflectionConfig`, ✅ `application.yaml`, ✅ root `.env.example`,
✅ `DecisionProviderSelectorTest`, ✅ `DecisionProviderConfigBindingTest`; ⊕ `DecisionTelemetry`
(the `recordEvaluation` extension); planned: `SelfHostedProviderConfig`, `SelfHostedDecisionEngine`
(bearer), `AppReflectionConfig`, `validate_decision_plane.sh`. **Deliberately unchanged:**
`DecisionShadowComparisonHarness` — it stays a simulation tool, and a measured `REAL_PROVIDER` mode, if
ever kept, delegates to the main runner (`DecisionEvidenceRunner`) rather than being extended into the
harness (see the W5 pin above).

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
          api-key: "${AMSHA_DECISION_TYPESAFE_API_KEY:}"
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
| `DecisionCredentialResolverTest` | unit | configured key → caller-owned UTF-8 bytes; null/blank → empty; the caller zeroes the byte array after use. |
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
| **PR-2** ✅ | W4 | `DecisionEvaluationService` + records; `DecisionAdminResource` + DTOs; `amsha.decision.evaluation.enabled`; audit; reflection. | **ADR accepted first**; ITs incl. no-egress-when-disabled and no-leak; validator JEV-13..15 |
| **PR-3** ✅ | W5 | Versioned corpus, policy-composed `DecisionEvidenceRunner`, `/evidence/run`, and fixture-backed IT. | M1–M5 metric tests; redaction review; JSON/Markdown artifact writer (landed — `DecisionEvidenceReport`, `format=markdown`). |
| **PR-4** ✅ | W6 | `DecisionTelemetry` extension; `DecisionDriftMonitor`; cost-ceiling and latency-budget enforcement. | unit tests; bounded-cardinality review |
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
| `classifier/provider/DecisionEngineFactory.java` | The one construction path. `create(selected, declaredGovernance, credential, mapper, outboundPolicyService, vault)` → `DecisionEngineHandle`. Re-checks the **selection** through `DecisionProviderSelector.selectionProblem`, then the governance, then refuses a credentialed provider whose direct key material is absent or blank (via `DecisionProviderSelector.requiresCredentialMaterial`), then maps the lane to `RemoteDecisionEngine` / `SelfHostedDecisionEngine`. Normalises `IllegalArgumentException`/`SecurityException` into `DecisionProviderUnavailableException`. |
| `classifier/provider/DecisionEngineHandle.java` | Owns the engine plus any transport the factory created (the loopback `HttpClient`); closes both, best-effort, never throwing. Needed because `DecisionEngine` is a functional interface and is not `AutoCloseable`. |
| `classifier/provider/DecisionCredentialResolver.java` | `resolve(apiKey)` creates request-owned UTF-8 bytes; `zero(byte[])` clears them in the evaluation service `finally`. The config String remains resident for process lifetime. |
| `classifier/provider/DecisionProviderUnavailableException.java` | The single fail-closed refusal type; payload-free message. |

**New — `core` tests:** `config/DecisionGovernanceFixture.java` (shared governance double),
`config/DecisionProviderGovernanceTest`, `classifier/provider/DecisionEngineFactoryTest`,
`classifier/provider/DecisionCredentialResolverTest`.

**Extended**

| File | Change |
|---|---|
| `config/DecisionProviderConfig.java` | Nested `Slot.Governance` group + `Slot.governance()`. All members optional/defaulted, so the group binds without failing the mapping and the `Deferred` posture is unchanged. |
| `config/DecisionProviderSelector.java` | `validate(...)` refuses an enabled slot whose governance is incomplete, checked last so endpoint problems report first. The non-governance rules (model-per-lane, CLM dialect, direct credential presence, positive timeout, endpoint validator) were extracted into a public pure `selectionProblem(...)` so the factory applies the identical rule to a hand-built selection. A companion public `requiresCredentialMaterial(providerId, lane)` answers the second half of the credential rule — whether key material is mandatory — so the factory refuses absent material. |
| `config/CoreReflectionConfig.java` | `DecisionProviderGovernance` and `DecisionProviderUnavailableException` added to `targets`. |
| `app/src/main/resources/application.yaml` | A six-line `governance` block on each of the five slots, plus a comment explaining the lane-dependent rules. |
| `.env.example` | `AMSHA_DECISION_<ID>_GOVERNANCE_*` keys on each provider block; the stale "no wiring maps an api-key" note corrected. |
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
| Direct provider keys are held as configuration Strings for the process lifetime, matching Amsha's other provider keys; each evaluation also creates a bounded engine/header String that cannot be erased when its handle closes. | The direct-config String lifetime is an explicit convention recorded by the 2026-10-09 credential amendment. The byte copy is zeroed in `finally`; a future byte-oriented transport overload could reduce only the additional per-evaluation String, not the source config String. |
| Plan §6 still called M4 unresolved, though the parent ADR §8.3 already sets lane-specific budgets. | §6 marked resolved; the W8 and PR-5 rows corrected. |
| W8 scheduled §§27.6–27.7 that already exist; PR-5 still listed W8.2/8.3. | Both marked landed; PR-5 now carries W7 only. |
| W7's runtime producer switch cannot prove structural absence — a `@Produces` method is a registered bean even when it returns no engine, so `Instance<DecisionEngine>` would be *satisfied* and `isEnabled()` could read true with the engine off. | W7 rewritten to gate the producer with a build-time `@IfBuildProperty`; the IT must assert bean **resolution**, not a null return from a producer method. |
| Direct decision-provider keys are bound from environment configuration; the selection carries only a presence flag and the factory also refuses missing or blank request-owned bytes. | The API key is copied for evaluation and cleared in `finally`; the source config String remains resident for process lifetime, matching the existing provider-key convention. |
| Plan §8 still listed "M4 reconciliation — approve the lane-specific budget in §6" as an open Committee item, though §6 marks it resolved and the parent ADR §8.3 records the budget. | The item was removed from §8 and a note added that M4 is resolved, so §6, §8, and the ADR now agree. |

**Deliberately not done in PR-1** (and why):

- The `amsha.decision.evaluation.enabled` / `production-engine.enabled` switches — nothing reads them
  until W4/W7, and an env var with no reader is dead configuration.
- At PR-1 time, W3b (a byte-oriented transport overload) and W3c (self-hosted bearer authentication)
  were deferred to the evaluation path. W4 now clears a request-owned byte copy, but the transport
  still decodes it to an immutable `String`; self-hosted keys are sent as `X-Amsha-Loopback-Token`, not
  `Authorization: Bearer`.
- Any `@QuarkusTest` profile proving the selector refuses through the *bound* mapping — the refusal is
  unit-tested twice already and the composition adds no new failure mode.
- The `app`-side binding test run and the reactor-wide coverage gate — those belong to the PR's own
  verification pass, not to this plan.

## Appendix F — W4–W6 as landed (2026-10-07)
The evaluation path and the evidence pipeline, on top of PR-1. Everything remains **inert by default**:
the only switch is `amsha.decision.evaluation.enabled` (**default false**), it gates an admin-triggered
evaluation rather than production traffic, and it constructs an engine for the duration of one evaluation
without registering one — so `Instance<DecisionEngine>` is still unsatisfied.

**New — `core`**

| File | Role |
|---|---|
| `classifier/evaluation/DecisionEvaluationService.java` | The single sanctioned egress path. Builds the engine via the W1 factory for one evaluation, projects/redacts state through the registered decision's allow-list, attaches the policy resolution, copies the direct configured API key into request-owned bytes and zeroes them in `finally`, and emits the audit event. |
| `classifier/evaluation/DecisionEvaluationRequest.java` / `DecisionEvaluationOutcome.java` | The payload-free request/outcome records. The outcome carries lane, provider, model, `DecisionMetadata` (tokens + latency) and the optional intent/tool-risk policy resolutions the metrics read. |
| `classifier/evaluation/DecisionEvaluationRefusedException.java` + `DecisionRefusalReason.java` | The single fail-closed refusal type and its reason vocabulary (`DISABLED`, `UNREGISTERED_DECISION`, …), shared by the service, the runner and the HTTP surface. |
| `classifier/evaluation/DecisionDefinitionRegistry.java`, `RegisteredDecision.java`, `QuestionSpec.java` | The seeded decision definitions the path can evaluate. Two entries: the router's `amsha.routing.intent.v1` and the tool-risk `tool.risk.shadow` (the latter built by `DecisionToolRiskShadowClassifier.definition(...)` and sharing `TOOL_RISK_CRITERIA` with `classify`). |
| `classifier/evaluation/DecisionEvidenceRunner.java` | Runs a registered corpus version through the evaluation path and returns the summary. Honours the corpus's declared `evaluatedTasks`; refuses a corpus that declares a task it has no decision for; owns no engine. |
| `classifier/evaluation/DecisionEvidenceSummary.java` + `DecisionEvidenceCaseResult.java` | The payload-free per-case result and the M1–M5 gate maths. M1/M2 fail closed unless the whole cohort was evaluated. |
| `classifier/evaluation/DecisionEvidenceReport.java` | Renders a summary as a payload-free Markdown report (pure: no clock, no I/O, no config). Exposed as `format=markdown` on `/evidence/run`. |
| `classifier/evaluation/DecisionEvidenceCorpus.java`, `DecisionEvidenceCorpusRegistry.java`, `DecisionEvidenceCase.java` | The versioned corpus record, its validating loader (strict mapper, fails closed without failing startup) and the per-case record. |
| `classifier/evaluation/DecisionPriceSchedule.java`, `DecisionPriceScheduleRegistry.java` | The versioned `(providerId, modelId)`-keyed price schedule. Unknown cost can never become zero. |
| `core/src/main/resources/decision-evidence/corpus-v1.json` | 30 redacted cases across 9 partitions; declares `evaluatedTasks: ["intent","tool_risk"]` and the pinned `amsha.deterministic.intent-baseline` v1. |
| `core/src/main/resources/decision-evidence/price-schedule-v1.json` | **Empty by design** — no rate is authorised yet. |

**New — `core`, W6 operations layer**

| File | Role |
|---|---|
| `classifier/DecisionTelemetry.java` (extended) | Adds `recordEvaluation(...)` alongside the unchanged `record(...)`: the evaluation counter uses bounded `lane`/`outcome`/`confidence_bucket` tags; model identifiers remain trace attributes only. The latency counter uses bounded `lane`/`latency_bucket` tags, and the cost accumulator uses the bounded `lane` tag. `confidenceBucket`/`latencyBucket` are public pure functions over closed sets; an unpriced evaluation records no cost meter. |
| `classifier/DecisionDriftMonitor.java` | Compares the most recent `window` observations on a lane against the first `window` (mean confidence, abstention rate) and logs one WARN per lane **per kind** per episode (the latch key is `(lane, AlertKind)`, so drift, latency, and cost-ceiling never suppress one another). Also answers the latency p95 breach against `DecisionLatencyBudget.ADR_8_3`. Bounded, payload-free, and it never changes a decision. |
| `classifier/DecisionCostGuard.java` | The rolling cost per 1,000 **priced** decisions per lane and the ceiling verdict. Empty until the window is full; no ceiling means no breach; an unpriced decision is never recorded as free. |

**New — `app`**

| File | Role |
|---|---|
| `app/.../api/DecisionAdminResource.java` (+ its DTOs) | `POST /api/v1/admin/decision/evaluate` and `POST /api/v1/admin/decision/evidence/run`. Both are `isInternal()`-guarded; `/evidence/run` takes no body, so `@Consumes(APPLICATION_JSON)` is scoped to `/evaluate` only. `/evidence/run` returns the summary as JSON by default or as Markdown with `format=markdown`. |

**Extended:** `application.yaml` (the `amsha.decision.providers.*` slots, the evaluation switch, and the
`drift`/`cost` monitoring block), `.env.example` (the corresponding keys, regenerated into
`app/src/main/resources/env-example`), `CoreReflectionConfig` (the evaluation/corpus/summary records),
`quarkus.native.resources.includes` in both `app/src/main/resources/application.yaml` and `app/pom.xml`'s
native profile, `DecisionToolRiskShadowClassifier` (the `definition(...)` factory),
`DecisionPriceScheduleRegistry` (a `latest()` accessor, now also used by the summary), and
`DecisionEvaluationService` (the cost-ceiling check before egress and the telemetry/drift/cost
observation after an answer).

**Tests:** `DecisionEvaluationServiceTest`, `DecisionDefinitionRegistryTest`, `DecisionEvidenceRunnerTest`,
`DecisionEvidenceSummaryTest`, `DecisionEvidenceCorpusRegistryTest`, `DecisionPriceScheduleRegistryTest`,
`DecisionEvidenceReportTest`, `DecisionTelemetryTest`, `DecisionDriftMonitorTest`, `DecisionCostGuardTest`,
`DecisionAdminResourceTest`, and `DecisionEvidenceIT` (the endpoint over a loopback provider fixture while
`Instance<DecisionEngine>` stays unsatisfied, including the `format=markdown` report and the refusal of an
unknown format).

**Open at the end of W6:** M5's rates and ceiling (a Committee value — the shipped schedule is empty, which
also keeps the W6 cost ceiling inert, and which is why the W6 `DecisionCostGuard` is **not** a runtime spend
barrier today — see §8 item 6); and a **live** provider run of the corrected corpus (the fixture-backed
runner test proves the metric maths, but no approved provider has been exercised end to end). The
`toolrisk-mal-01` corpus-revision decision that was open above is now **resolved** — the case is
representable, M1's cohort is complete at `Evaluated 11/11`, and corpus v1 no longer needs a revision to make
the gate measurable. The main-code report writer is **landed** (`DecisionEvidenceReport`, exposed as
`format=markdown`), so the only remaining W5 gap is a live run.

**One open item is a *finding*, not a gap (2026-10-07): M2 now reads `3/3 → FAIL`.** The corpus-state-shape
defect (above) had been suppressing the intent half of every run, so M2's earlier `0/3` "pass" measured
nothing. With the defect fixed, the fixture-backed app run exercises the router and M2 fails — correctly,
because the canned provider routes all three `AMBIGUOUS` cases to `coder` when their expected label is
`NO_OPINION`. A reviewer reading the Quality-gate package will therefore see a **failing** routing gate where
the plan previously recorded a pass; that is the honest reading and should be confirmed before any promotion
discussion. M1 is unaffected and still `0/11 PASS`, which is by design (see the M1 note above).

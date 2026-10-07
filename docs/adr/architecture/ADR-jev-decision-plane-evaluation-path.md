# ADR: Jev Decision Plane — Explicit Evaluation Path

- **Status**: Proposed — awaiting Amsha Architecture Committee acceptance. Authorises the evaluation path only; production routing remains Deferred under `ADR-jev-decision-plane` §8
- **Date**: 2026-10-07
- **Decision Owners**: Amsha Architecture Committee
- **Feature Slug**: `jev-decision-plane`
- **Related Invariants**: `docs/Architecture_Invariants.md` §27 (decision plane seam), §27.1 (deferred means inert), §5.9 (transaction-free zone), §1.8 (governed transport ownership); `AGENTS.md`
- **Parent decision**: `ADR-jev-decision-plane.md` (Status: **Deferred**) — this ADR does not change that status
- **Plan**: `docs/research/Jev-Decision-Plane-Activation-Plan-2026-10-07.md`

---

## 1. Context and Problem Statement

The Jev decision plane ships **Deferred** and is inert in a way that is *structural*, not
configurational: no `DecisionEngine` bean exists anywhere, so `Instance<DecisionEngine>` is
unresolvable and `IntentDecisionRouter.isEnabled()` is permanently false. That posture is correct
while the plane is deferred, but it has one consequence the parent ADR did not resolve: **there is no
sanctioned way to make a real provider call.**

Concretely, today:

* `DecisionProviderSelector` resolves a configured slot into a `Selection.Selected`, but **no runtime
  caller consumes it** — the selector is a pure function with no production caller.
* `RemoteProviderConfig.builder()` and `new RemoteDecisionEngine(...)` are referenced **only by tests**.
* `api-key-ref` is carried and checked for presence but **resolved by nobody**, so a credentialed
  TypeSafe call is not merely unapproved — it is impossible.
* The parent ADR's Quality gate is UNMET because candidate models have never been evaluated; §7.4
  evidence was explicitly `N/A (Synthetic)`.

The parent ADR already states the required shape: a remote provider "runs only from an explicitly
authorized orchestration or evaluation path, never as a remote implementation of the network-free
classifier SPIs" (§2). That path has never been built. Meanwhile the shadow classifier SPIs cannot
serve as it: `DecisionShadowEngineResolver` resolves through
`ProviderPlacementValidator.activateForClassifierSpi`, which rejects any engine that is not
`InProcessDecisionEngine` with `ProviderCategory.IN_PROCESS`. A remote TypeSafe engine therefore
**cannot** travel the shadow path by construction.

The gap is therefore precise: the plane needs an **explicit, opt-in evaluation path** that can
construct a configured provider, send only reviewed and allow-listed data, and return typed results
and payload-free evidence — **without** registering a production engine bean and **without** acquiring
any routing authority.

## 2. Decision

Introduce a first-class **explicit evaluation path**, separate from production routing and separate
from the classifier SPIs. It is a new seam, not a change to the Deferred posture.

### 2.1 Shape

```
DecisionProviderConfig  ─►  DecisionProviderSelector  ─►  Selection.Selected
                                                              │
        DecisionProviderGovernance ──┐                        │
        DecisionCredentialResolver ──┼──► DecisionEngineFactory ──► DecisionEngineHandle
                                     │            (transient)          └─► DecisionEngine
                                     └────────────┘
                                                              │
                                              DecisionEvaluationService
                                              (opt-in · audited · non-routing)
                                                              │
                                    ┌─────────────────────────┴──────────────────────┐
                                    ▼                                                ▼
                        DecisionAdminResource                        DecisionEvidenceRunner
                        (/api/v1/admin/decision/*)                   (corpus → M1..M5)
```

The factory returns a **handle**, not a bare engine: `DecisionEngine` is a functional interface and is
not `AutoCloseable`, but both lane implementations are, and the loopback lane needs an `HttpClient` the
factory creates and therefore owns. The handle carries the engine plus any owned transport and closes
both, which is what lets the evaluation path treat an engine as a transient, scoped resource.

### 2.2 Rules

1. **One factory, one construction path.** A `DecisionEngineFactory` in `core` builds the engine from
   a `Selection.Selected` plus validated governance and a resolved credential, and returns a
   `DecisionEngineHandle` owning the engine and any transport it created. The evaluation path and
   any future production producer MUST both use it; a second construction path is forbidden. Governance
   completeness is validated by one pure rule (`DecisionProviderGovernance.problem`) that both the
   selector and the factory consult, so the two can never disagree about what "complete" means.
2. **No CDI engine bean.** The evaluation path constructs the engine **transiently** and closes it
   (both engines are `AutoCloseable`). It MUST NOT register, produce, or expose a `DecisionEngine`
   bean. `Instance<DecisionEngine>` MUST remain unresolvable while the parent ADR is Deferred.
3. **Opt-in and off by default.** `amsha.decision.evaluation.enabled` defaults to `false`. When false,
   no egress occurs and the evaluation surface returns a payload-free refusal.
4. **Internal-only, including the read — a recorded exception to the admin convention.** Every endpoint
   is guarded by `isInternal()`, **including the `GET` provider-status read**. This is a deliberate,
   recorded exception to the standing rule that mutating `/admin/**` endpoints require `isInternal()`
   while reads remain public (`AGENTS.md`). That convention assumes an admin read returns user or
   operational data whose disclosure is already bounded. The evaluation surface is different in kind:
   its `GET` reveals the decision provider's identity, lane, host, and model, and whether a credential
   is bound — the shape of an egress path that the plane is Deferred precisely to keep closed. A public
   read would let an unauthenticated caller enumerate provider configuration and use the endpoint's
   refusals as an oracle for it. The surface is therefore treated as a privileged *operational* surface,
   not a data-read surface. `resolveUserId()` is still used for audit attribution on the mutating
   endpoints.
5. **Reviewed, allow-listed data only.** State is projected through an explicit
   `StateProjectionPolicy` allow-list and a `RedactionPolicy` before serialization. A missing, empty,
   or invalid allow-list fails closed; the path MUST NOT invent an allow-list from question IDs.
6. **Governance declared, not inferred.** Retention window, data residency, training-use (must be
   `false`), tenant isolation (must be `true`), and the projection allow-list are explicit
   configuration. An enabled remote slot with incomplete governance is refused, not defaulted.
7. **No routing authority.** The evaluation path consumes a result; it never chooses a route, never
   relaxes a block, and never bypasses HITL. A decision may escalate, never downgrade.
8. **Audited and payload-free.** Each evaluation records an audit event (egress is a side effect) and
   emits no state, prompt, label, secret, or provider body in logs, metrics, or evidence.
9. **Worker/virtual threads only.** Evaluation performs blocking HTTP and MUST NOT run on a Vert.x
   event-loop thread; the transaction-free zone (§5.9) is enforced by the existing guards.
10. **Placement unchanged.** `ProviderPlacementValidator` remains authoritative: the evaluation path is
    a separate seam and MUST NOT be used to place a remote or self-hosted engine behind a classifier SPI.

## 3. Invariants and Compliance

* **Control-plane ownership** — `PreflightState`, `LoopStuckDetector`, `ToolInvocationGuard`, HITL,
  workspace isolation, persistence and `FINISH` sequencing are untouched. The path evaluates; it does
  not act.
* **Structural inertness preserved** — the parent ADR's guarantee that the plane *cannot* activate
  comes from the absence of a bean. This ADR keeps that absence and adds a seam that does not disturb it.
* **Governed transport** — remote egress continues through `GovernedHttpClient` and
  `OutboundHttpPolicyService`; a remote decision still cannot authorize its own network call.
* **Native reflection** — every new record/DTO is registered in the owning central `*ReflectionConfig`
  (`CoreReflectionConfig` for core types, `AppReflectionConfig` for REST DTOs); no type-level
  `@RegisterForReflection`.
* **Evidence is versioned and payload-free** — the corpus is versioned; M1 is measured end to end
  through the deterministic policy layer, not against a bare engine.

## 4. Alternatives Considered

1. **Register a production `DecisionEngine` bean and enable the plane.** Rejected: it converts a
   structural guarantee into a configurational one and skips the parent ADR §8.4 promotion procedure.
2. **Reuse the shadow classifier SPIs for the remote provider.** Rejected: `ProviderPlacementValidator`
   forbids it, and the SPIs are network-free by contract.
3. **Add a credentialed call inside `IntentDecisionRouter`.** Rejected: it would give the router
   routing authority over an uncalibrated model and conflate evaluation with production.
4. **Call TypeSafe from an ad-hoc script outside the application.** Rejected: it would bypass governed
   transport, redaction, audit, and the transaction/event-loop guards — the exact controls the remote
   lane exists to concentrate.
5. **Wait for the Committee to authorise production activation first.** Rejected: the Quality gate is
   UNMET precisely because no candidate has been evaluated; without an evaluation path the plane
   cannot produce the evidence that would justify activation.

## 5. Consequences

### Positive

* A real, credentialed TypeSafe (or Laya/Kev/CLM) call becomes possible **without** activating routing.
* The Phase 3 evidence the parent ADR's Quality gate requires can actually be gathered.
* Governance declarations (retention, residency, training-use, allow-list) move from prose into
  validated configuration.
* The factory removes the risk of two divergent engine-construction paths.

### Costs and risks

* A new internal surface must be authorized, audited, and tested; a regression there is an egress risk.
* **Credentials cannot be zeroed end to end.** The caller zeroes the resolved `byte[]`, but the decoded
  `String` is held by the provider config for the engine's lifetime (`RemoteProviderConfig.apiKey` /
  `SelfHostedProviderConfig.loopbackToken`), and an immutable `String` cannot be erased when the handle
  closes. The exposure is bounded by one evaluation rather than eliminated; it is recorded here as a
  residual risk instead of being described as "transient". Removing it requires an engine overload that
  builds the transport header without a lingering `String` — a production security decision to take
  before the evaluation path is enabled.
* An operator could mistake the evaluation path for activation. The naming, the default-off switch, and
  the parent ADR's unchanged `Deferred` status must make the distinction explicit.

## 6. Acceptance and Rollback

Acceptance requires: the evaluation path is disabled by default and performs no egress when disabled;
internal-only authorization is enforced on every endpoint; excluded fields and secrets provably do not
reach the wire; governance-incomplete and credential-missing configurations fail closed; and
`Instance<DecisionEngine>` remains unresolvable (the parent ADR's posture is unchanged).

Rollback is configuration-only: set `amsha.decision.evaluation.enabled=false`. No migration, no schema
change, no restart-dependent cleanup.

## 7. Relationship to `ADR-jev-decision-plane`

This ADR is **additive and subordinate**. It does not:

* change the parent ADR's `Status: Deferred`;
* satisfy the parent ADR §8.4 promotion steps 1–5;
* authorise a production `DecisionEngine` bean or a router switch;
* authorise any routing authority for a decision model.

It authorises exactly one capability: an explicit, opt-in, internal-only, non-routing evaluation path.
Production activation remains governed solely by the parent ADR's §8 promotion procedure.

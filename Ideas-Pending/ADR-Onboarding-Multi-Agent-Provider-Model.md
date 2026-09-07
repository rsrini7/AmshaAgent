# ADR: Profile-Based Onboarding for Multiple Agents, APIs, and Models

- Status: Approved
- Date: 2026-07-14
- Decision owners: Amsha Agent maintainers
- Identity prerequisite: `ADR-Identity-Access-and-Workspace-Sharing.md` is partially implemented through the current identity/bootstrap foundation. Its distinct active-workspace context and `WORKSPACE` vault scope/grant work remain open hard gates for the onboarding phases identified below.

## Context

The remaining product work is primarily the profile/routing and lifecycle work described below. Identity users/workspaces/memberships, API credentials, opaque web sessions, external-login providers, invitations, `BootstrapStateStorePort` with three persistence adapters, `BootstrapResource`, and the app-owned `BootstrapCredentialIssuer` implementation now exist. The current vault still has only system, chat-session, and stable user addressing; the distinct active identity-workspace binding and `WORKSPACE` secret scope/grants required by this ADR are not complete.

The delivered O1 slice replaces the in-memory numbered wizard with an authenticated, durable draft flow and a draft-driven Lit wizard. It provides a static provider catalog, multiple draft connection rows, concern-routing selections, optional configuration screens, server-side secret scrubbing into owner-scoped vault entries, and a review-gated commit that activates the selected default LLM values. This is deliberately an interim O1 payload format, not the ADR's final connection/profile/route model: it does not yet create `LlmConnection`, `ModelProfile`, or `ModelRouteAssignment` records; it does not activate the optional-stage payloads; and it does not provide the extension saga, validation/HITL lifecycle, workspace grants, or runtime profile routing.

The numbered flow, its synthetic `onboarding-user`, Caffeine registry, and raw completion key are removed. A deployment establishes and authenticates its first administrator before a durable owner-scoped draft is created.

Amsha already has the runtime pieces that make a richer design useful:

- `ModelRouter` supports persona, group, and global model precedence and ordered model failover.
- provider factories create models for multiple providers;
- `SecretVault`, `WorkspaceContext`, HITL, audit, and persistence ports establish the required security boundaries;
- MCP connections are now durable, owner-scoped, vault-backed integrations.

The onboarding design must expose those capabilities without turning onboarding into a second configuration system, weakening tool governance, or giving plugins arbitrary configuration access.

## Decision

Adopt an **identity-first, connection-first, assignment-second, profile-based onboarding flow**.

1. On an unclaimed deployment, a protected pre-draft claim mints the first durable API-key credential for a generated stable owner principal; only then is an owner-scoped onboarding draft created.
2. A user configures one or more **LLM API connections**. A connection identifies a provider adapter, endpoint profile, credential reference, and safe capability metadata; it is not a model selection.
3. The user adds one or more **model profiles** to a connection. A profile identifies a model and safe execution settings.
4. The user assigns an ordered model-profile chain to the default runtime, an existing agent role, or an existing group. The first profile is primary; later profiles are explicit failover candidates.
5. Optional integrations, including non-LLM APIs, are contributed as declarative onboarding extensions and use the same draft, validation, approval, commit, cleanup, and audit lifecycle.

Onboarding configures existing agents and groups; it does **not** create roles, change persona capabilities, change tool grants, or create a new authorization path. Agent-role creation and capability changes remain governed by their existing role and prompt architecture.

The first-run experience remains short: create one connection, choose one model, assign it as the default, then optionally add role-specific assignments, more connections, MCP servers, or extensions. The advanced route is progressive disclosure, not a mandatory matrix of every agent and provider.

## Goals

- Configure multiple independent API accounts, including several accounts for the same provider.
- Establish the first human administrator before collecting an integration credential.
- Reuse a single API connection across multiple model profiles without duplicating credentials.
- Configure a primary and explicit fallback model chain for the default, agent-role, and group scopes.
- Let installed modules contribute optional setup steps without central resource/UI conditionals.
- Keep credentials, OAuth artifacts, and secret fields vault-only and explicitly user/workspace scoped.
- Preserve drafted progress, explicit skip decisions, and committed non-secret state across restart.
- Retain a simple first-run path and make incomplete/failed work recoverable.

## Non-goals

- Editing `application.yaml`, `.env`, deployment secrets, or packaged configuration at runtime.
- Replacing deployment configuration as a bootstrap/operations mechanism.
- Discovering or creating arbitrary agent roles through onboarding.
- Automatically enabling every installed integration or automatically routing a role to a newly added model.
- Making unreviewed model-catalog queries, endpoint probes, OAuth exchanges, or external validation exempt from HITL.
- Persisting raw form payloads, credentials, access tokens, or provider responses in the onboarding state.
- Implementing identity/session protocols inside onboarding. The companion identity ADR owns OIDC/OAuth, opaque web sessions, stable principals, multi-admin invitations, workspaces, and API credentials.
- Representing team sharing with a boolean. Workspace-scoped connections and explicit grants are enabled only through the companion identity ADR's membership and vault-scope model.

## Why connection-first is the best fit

A provider is not a credential, and a model is not an agent assignment. Treating all three as one wizard row makes common cases unsafe or awkward: one OpenAI account can serve several models and agents; two tenants can require separate accounts for the same provider; a fallback can intentionally use a different provider; and an API integration may not be an LLM at all.

The chosen decomposition keeps each concern independently reusable:

| Concern | Durable record | May contain secret material | Reused by |
| --- | --- | --- | --- |
| API connection | `LlmConnection` | No; holds an opaque vault reference only | Many model profiles |
| Model profile | `ModelProfile` | No | Many route assignments |
| Route assignment | `ModelRouteAssignment` | No | Runtime model resolver |
| Onboarding draft/outcome | `OnboardingDraft` / extension state | No | Resume, cleanup, audit |

This is also the correct boundary for multi-agent operation: routes express *which existing role uses which approved profile*, while the role registry remains the sole authority for what that agent may do.

## Architecture

### 0. Identity bootstrap and owner conversion

Identity bootstrap is a **pre-draft flow**, not an onboarding-plan stage. The companion identity ADR defines stable users, workspaces, OIDC/OAuth, sessions, and multi-admin authorization. Before a deployment has an owner, only narrow bootstrap status/claim/recovery endpoints are reachable. Status returns only state and safe operator guidance; it does not disclose the owner ID, workspace ID, credential digest, or revision usable as an authorization proof. The claim creates the first API credential recognized by the existing chain `ApiKeyValidator -> ApiKeyRegistry -> ApiKeyOwnerResolver -> ApiKeyAuthFilter` and binds it to the generated stable owner ID.

**Replace**, rather than wrap or delegate to, the existing `BootstrapStateStorePort.claimFirstOwner(ownerId, credentialDigest, issuedAt)` contract. That signature proves the credential and owner already exist before the winning reservation and cannot satisfy this decision. The replacement stores a revisioned state machine `UNCLAIMED -> CLAIMING -> CREDENTIAL_ISSUED -> CLAIMED` plus the stable owner ID, stable default workspace ID, generated API-key HMAC/digest, timestamps, and revision, never the raw key. Its required transitions are equivalent to:

```text
reserveFirstOwner(ownerId, workspaceId, reservedAt)
  compare-and-set UNCLAIMED -> CLAIMING and return the reservation revision
recordCredentialIssued(expectedRevision, credentialDigest, issuedAt)
  compare-and-set CLAIMING -> CREDENTIAL_ISSUED
confirmClaim(expectedRevision, authenticatedOwnerId, claimedAt)
  compare-and-set CREDENTIAL_ISSUED -> CLAIMED only for the reserved owner
rollbackClaiming(expectedRevision, rolledBackAt)
  compare-and-set CLAIMING -> UNCLAIMED; leaves no identity/workspace/credential rows
replaceCredential(expectedRevision, newDigest, issuedAt, updatedAt)
  compare-and-set CREDENTIAL_ISSUED -> CREDENTIAL_ISSUED for the reserved owner;
  UNCLAIMED is unreachable after the first reserve, so the old UNCLAIMED-or-CREDENTIAL_ISSUED
  guard is narrowed to CREDENTIAL_ISSUED only. Permitting CLAIMING is an explicit, operator-only
  recovery widening (see the recovery paragraph): a reserved-but-never-activated owner may be
  re-issued before the reissue TTL expires. It is NOT a general claim path and must not be opened
  to the public claim endpoint or any non-operator principal.
```

This replaces the current `claimFirstOwner(ownerId, credentialDigest, issuedAt)` and changes the existing `confirmClaim(int expectedRevision, Instant claimedAt, Instant updatedAt)` signature by adding `authenticatedOwnerId` so confirmation is bound to the reserved owner; the current `replaceCredential(int, String, Instant, Instant)` is retained but its guard is narrowed as above. `rollbackClaiming` is the missing counterpart to the line-95 recovery statement: a stale `CLAIMING` reservation (crashed coordinator, expired reservation TTL) is returned to `UNCLAIMED` idempotently, never leaving orphan identity/workspace rows because the winner creates them only after the `CREDENTIAL_ISSUED` transition. The operator-only `CLAIMING` widening on `replaceCredential` is a deliberate re-issue path for a reserved-but-never-activated owner within its reissue TTL; adapters must not expose it on the public claim endpoint or to any non-operator principal, or the reservation contract's race guard is silently defeated.

The coordinator reserves `CLAIMING` **before** creating identity rows or credentials. The winner idempotently creates that exact user, workspace, and membership; a loser creates nothing. The app-owned credential SPI then prepares the raw key and digest for a typed bootstrap reservation without persisting or registering them. The coordinator records `CREDENTIAL_ISSUED` and calls the SPI's activation operation; the app implementation verifies the same reservation owner/revision/digest, durably writes the matching API credential, and only then registers it in `ApiKeyRegistry`. The coordinator returns the raw key only after activation succeeds. `ApiKeyRegistry` must accept the bootstrap credential only when both the credential record and a matching `CREDENTIAL_ISSUED`/`CLAIMED` bootstrap digest exist; a generic durable-credential lookup cannot bypass bootstrap state. A crash after recording the digest but before activation leaves no usable key and enters normal reissue recovery; a crash after the credential write but before cache registration is restart-recoverable through the state-bound durable lookup.

Recovery can finish or safely roll back a stale `CLAIMING` reservation. This eliminates the current implementation race: `BootstrapResource` writes identity/workspace rows and calls the combined persist-and-register issuer before the `claimFirstOwner(...)` compare-and-set, so a loser can leave orphan principals and a credential can become valid before bootstrap state wins. SQLite, PostgreSQL, and filesystem adapters implement the same transition and crash-recovery contract. Mutable key bytes are zeroed in `finally`; the immutable cookie/string lifetime is minimized and never logged, audited, or returned by a later API.

Response loss must not strand an unclaimed installation. While state is `CREDENTIAL_ISSUED` and no owner draft exists, the same one-time bootstrap proof or the configured internal operator key may replace the unconfirmed digest and issue a new raw key for the **same persisted owner and workspace**; the state-bound registry check makes the old digest ineligible before the replacement is activated. Recovery never creates another default workspace.

Use of the configured internal key for recovery is an intentional privileged operator path, not an implicit fallback. It may authenticate recovery from a non-loopback deployment, is rate-limited and mandatorily audited as `bootstrap_recovery` with no key/digest data, and is accepted only while the state is `CLAIMING` or unconfirmed `CREDENTIAL_ISSUED`. After the issued key authenticates and draft creation confirms `CLAIMED`, all bootstrap recovery authorization—including the internal key path—is permanently disabled and returns an unavailable response; later rotation uses normal authenticated key-management policy.

An unauthenticated remote caller must not be able to seize a fresh public deployment. Bootstrap issuance is loopback-only by default. A non-loopback deployment requires an operator-supplied one-time bootstrap claim secret through deployment configuration; comparison is constant-time and the raw claim secret is never persisted. The durable compare-and-set remains the final race guard. Cookie `Secure` follows the identity session policy: required on HTTPS/non-loopback deployments, but not forced for explicit loopback HTTP development where a browser would otherwise withhold it. After confirmation, bootstrap claim/recovery endpoints return unavailable, any temporary bootstrap cookie is invalidated, and `ApiKeyAuthFilter` requires the new API credential for every draft endpoint. The current blanket authentication bypass for `/onboarding/**` is narrowed to bootstrap status/claim/recovery only.

Only after the first credential authenticates as the generated stable principal does the coordinator create a persisted onboarding draft owned by `USER:<userId>`; its plan starts at `welcome`. No provider/API credential is accepted before then, so there is no synthetic-owner draft or secret conversion. Login and the onboarding shell query for that owner's non-terminal draft and offer **Resume** or **Discard and restart**; discard triggers normal staged-secret cleanup.

The configured `AMSHA_API_KEYS_INTERNAL` remains an operator bootstrap credential and maps to the claimed owner's stable ID/role during compatibility; the new flow must never reveal that configured value as today's completion response does. Additional administrators and external login are provisioned by the companion identity subsystem, not by onboarding steps.

#### Bootstrap browser-loss and local-runtime evidence

The claim response is delivered through an `HttpOnly` `AMSHA_API_KEY` cookie and may contain the raw owner API key exactly once as the credential-creation response. The UI uses that one-time value only as a refresh fallback and never puts it in a URL or logs it. A failed status request never deletes that fallback directly: the UI retries without the persisted header and removes only the exact attempted key after the HttpOnly cookie succeeds. Concurrent shell and wizard startup requests therefore cannot erase a key issued while an older request was in flight. Wizard controls remain unavailable until authenticated draft creation returns a non-empty draft ID. For local HTTP development, the cookie MUST remain usable on both `localhost` and IPv6 loopback (`[::1]`); the implementation therefore clears `Secure` only for explicit loopback HTTP and retains it for HTTPS/non-loopback deployments. Loopback matching includes IPv4 `127.0.0.0/8`, expanded IPv6 loopback, and IPv4-mapped loopback.

If the browser drops that cookie after a successful claim, the persisted state correctly remains `CLAIMED`; the persisted one-time owner key keeps normal refresh authenticated. If both credentials are absent, status calls return `401` until the owner credential is restored. This is not a second-claim or silent-recovery path. Local development must use the owner credential or a new data directory rather than deleting existing data implicitly. The verified native contract is: fresh data → `UNCLAIMED` → claim `200` with a non-secure local HTTP cookie → authenticated status `200` with the configured `vaultPriority` → no-credential status `401` while bootstrap remains `CLAIMED`. Vault-priority messaging is rendered only after an authoritative status response explicitly reports the flag as disabled.

### 1. LLM API connections and model profiles

Introduce a persistence-api port, named for example `LlmProfileStorePort`, with three explicitly scoped non-secret aggregates:

```text
LlmConnection
  id, scope type (SYSTEM | USER | WORKSPACE), scope ID, created-by user ID, display name
  provider adapter ID, endpoint profile ID, optional normalized custom base URL, auth scheme
  network profile, state, revision, timestamps

LlmConnectionSecretRef
  connection ID, adapter-defined closed secret-slot ID, opaque vault reference,
  revision, timestamps

ModelProfile
  id, connection ID, model ID
  structured-output mode, context/window capability metadata, safe limits
  enabled, revision, timestamps

ModelRouteAssignment
  scope type (SYSTEM | USER | WORKSPACE), scope ID,
  target kind (DEFAULT | ROLE | GROUP), nullable target ID
  ordered model-profile IDs, enabled, revision, timestamps
```

`SYSTEM` is reserved for the behavior-preserving legacy migration and operator-managed bootstrap records; ordinary onboarding cannot create or take ownership of it. `DEFAULT` requires a null target ID, while `ROLE` and `GROUP` require a non-null canonical target. A connection has one or more adapter-declared secret slots rather than one assumed API-key field, so adapters can represent API keys, token pairs, service-account material, or proxy credentials without putting secret-shaped columns or a raw map in the connection record. Local/no-auth adapters have no credential slots. Secret-slot IDs are a closed part of the versioned adapter descriptor, not user input.

`provider adapter ID`, target kind, and auth scheme are validated strings at the persistence boundary. Their enums and provider implementations remain in `amsha-core`; `persistence-api` must not depend on core. The core service validates each value before persistence and again before use.

The store must support scope-authorized list/read/create/update/delete, assignment replacement, revision-based optimistic concurrency, lookup by model profile ID, and an expected-revision activation operation covering all records finalized by one draft. SQLite, PostgreSQL, and filesystem adapters must implement one port-level contract. Relational implementations activate a draft atomically in one short transaction. The filesystem implementation provides the same externally atomic visibility through a revisioned manifest/pointer swap and crash recovery; sequentially flipping individual JSON records is not equivalent. SQL schemas use paired, semantically equivalent Flyway migrations; filesystem storage holds the same non-secret fields.

Credentials are written under generated opaque vault key names in the connection's authorized `USER:<userId>` or `WORKSPACE:<workspaceId>` partition. Only the child secret-reference records store those opaque references; the connection aggregate returns only secret-slot presence/state to callers. Connection name, provider name, model name, and user input must never form a vault key. Deleting/replacing a connection is a compensating workflow: remove or disable dependent profiles and routes, invalidate runtime caches, delete every owned vault reference, and persist/retry a redacted cleanup failure if necessary.

An optional per-connection `OutboundNetworkProfile` supports HTTP/HTTPS proxy URIs without user-info, validated `noProxyHosts`, bounded connect/read timeouts, and a TLS trust mode (`SYSTEM` or an approved custom-CA vault reference). Proxy credentials use a separate opaque vault reference. Deployment-wide proxy/timeouts remain the default when no connection override exists. Disabling certificate verification is not a production option: corporate interception uses an explicit custom CA, while a loopback-only development exception must be configured outside persisted onboarding state.

Phase 2 adds a core-owned `OutboundHttpPolicyService`; no provider adapter, MCP onboarding path, extension sensitive-operation path, catalog client, OAuth exchange, or model installer constructs an unrestricted HTTP client directly. The service accepts a typed endpoint class, target URI, optional `OutboundNetworkProfile`, and approved operation context, then returns a bounded client/validated request policy. It enforces schemes and ports, rejects user-info, normalizes `noProxyHosts`, validates proxy and target independently, resolves and blocks prohibited loopback/private/link-local/metadata destinations unless the adapter's reviewed endpoint class explicitly permits local access, and revalidates DNS plus every redirect hop to prevent rebinding. It owns timeout, proxy-authentication, TLS/custom-CA, redirect, and redaction behavior and keeps proxy secrets out of adapters, DTOs, logs, and model cache keys. Phase 2 cannot complete until existing MCP onboarding and LLM validation paths use this service; Phase 4 extends the same contract to extension operations.

O2 cannot ship workspace promotion until identity I5 supplies the distinct active-workspace context, typed `WORKSPACE` vault scope, and grant authorization contracts. After that gate passes, a connection may be promoted from `USER` to `WORKSPACE` scope through its explicit grant workflow. Members may use but never reveal the workspace secret; owner/admin roles control rotation and deletion. The onboarding UI does not expose a `shared` checkbox without a real workspace membership and authorization context.

`amsha.llm.*` remains immutable deployment/bootstrap configuration. It is the fallback when no eligible owner-scoped profile is configured, and it remains the correct way for operators to provision non-interactive deployments. Onboarding must never mutate it. A future explicit import may copy an operator-provided bootstrap credential into a vault connection, but it must create a new reference and never expose the original value.

#### Legacy system-vault migration

Existing deployments may contain `LLM_PROVIDER_KEY`, `LLM_MODEL_KEY`, and `LLM_API_KEY` in the system vault. On first startup with legacy keys and no profile record, an idempotent migration creates a `SYSTEM`-scoped connection, model profile, and default route that continue to reference the existing system-vault material. It does not assign the operator credential to whichever human logs in, copy a secret, require an interactive visit, or change effective routing. Failure leaves the legacy read path active and emits a persistent health warning; startup continues.

After login, owners/admins see a dismissible **Legacy configuration migrated—Review** banner. They may retain the system profile, or explicitly promote it to `WORKSPACE`/`USER` scope through a fresh vault reference, validation, and audited compensating commit. The original system keys remain until the promoted route is verified and the compatibility release permits cleanup.

`SYSTEM_PROMPT_KEY` is not an LLM credential and is not placed in `LlmProfileStorePort`. It remains the legacy global prompt fallback until it is migrated through the canonical prompt-registry ownership path in a separate prompt decision. This ADR must not silently reinterpret it as a per-user secret.

For the onboarding release and all remaining releases in the current major version, `ChatModelProducer`/routing retain the direct legacy system-vault lookup after profile/deployment resolution as a last-resort fallback. The resolver records a bounded deprecation event and exposes a persistent health warning when it uses that path. Minor upgrades never block startup because an administrator missed onboarding. Direct legacy-key lookup may be removed only in a major version after the automatic `SYSTEM`-profile migration has shipped, rollback has been tested, and release notes provide an offline repair command. The numbered REST façade may be removed earlier once no supported UI uses it; it is not coupled to credential compatibility.

`SettingsResource` (`/settings`) currently writes `LLM_PROVIDER_KEY`, `LLM_MODEL_KEY`, `LLM_API_KEY`, and `SYSTEM_PROMPT_KEY` directly and uses the in-memory `OnboardingSessionRegistry`. During the compatibility window it continues to function for the configured internal key. In O1, when the draft service becomes the sole source of truth, the resource remains only as a compatibility façade and every operation delegates to the draft/profile/prompt services. The resource class is removed with the numbered-step façade by the recorded removal version and no later than O5.

### 2. Runtime resolution and multi-model routing

Replace configuration-only runtime lookup with a core-owned `ModelProfileResolver` used by `ModelRouter`:

#### PROFILE_ROUTE_PRECEDENCE

```text
explicit owner route for role
  -> explicit owner route for the role's group
  -> explicit owner DEFAULT route
  -> explicit active-workspace route for role
  -> explicit active-workspace route for the role's group
  -> explicit active-workspace DEFAULT route
  -> existing lightweight-task deployment override, when applicable
  -> existing deployment persona configuration
  -> existing deployment group configuration
  -> existing deployment global configuration
  -> migrated SYSTEM profile
  -> legacy system-vault configuration during its compatibility window
```

This named block is the normative routing definition and the single source for resolver tests and operator documentation. Acceptance criteria link to it rather than maintaining a second copy that can drift.

`ROLE` means an existing canonical built-in role or an exactly registered dynamic skill role; `GROUP` means an existing `AgentGroupRegistry` group ID. The resolver canonicalizes built-ins through `AgentRoleEnum.fromString(id).toScopedName()`, checks dynamic roles against `RoleRegistry` rather than accepting any syntactically valid `skill:*` value, and rejects an unknown target instead of persisting a near-match. Group existence is checked through the existing `AgentGroupRegistry.findById(groupId)` API.

Group membership is unique for routing. `AgentGroupRegistry` validates built-in groups at startup and rejects registration of any dynamic group whose canonical member role already belongs to a built-in or dynamic group. It also rejects duplicate group IDs and duplicate members within one group. There is no priority or last-registration-wins behavior. This makes the existing single-result `findByMember()` contract deterministic. A role route therefore maps deliberately to ModelRouter's current persona-override tier, while a group route maps to its group-default tier.

This is an explicit O3 code migration, not a description of current behavior. The current `AgentGroupRegistry.registerDynamic()` unconditionally overwrites by group ID, and `AgentGroupRegistryTest.testDynamicGroupRegistration()` deliberately registers members that overlap built-in groups. O3 changes registration to validate before mutation and updates that test. Focused tests must cover successful unique registration plus rejection of a duplicate group ID, duplicate member within one group, overlap with a built-in group, and overlap with an existing dynamic group; a rejected registration leaves registry state unchanged.

Only a route explicitly assigned to a scope is eligible. A newly added connection or model profile is inert until the user assigns it. A group route remains lower precedence than a role route. An eligible owner/workspace route intentionally shadows the lightweight-task shortcut as well as the corresponding deployment persona/group/global configuration; user-selected routing must not silently change because the goal classifier labels a request lightweight. When no scoped route exists, the current lightweight-task shortcut retains its existing position ahead of deployment persona/group routing. Removing or disabling the scoped route immediately restores that deployment/legacy behavior. Existing alias canonicalization and the current role/group escalation semantics remain unchanged.

An assignment contains an ordered, sticky-primary fallback chain. The first eligible profile is attempted first for each new request; later entries are failover candidates, not round-robin load-balancing peers. Profile routing uses a new `OrderedFailoverChatModel`; it does not change `MultiModelChatModel`, whose round-robin behavior remains available to existing deployment/provider configuration. On a retryable provider/model failure, `OrderedFailoverChatModel` may advance only within the assigned chain; it must not select another user connection, system credential, or model merely because it exists. Authentication, validation, policy, or schema-incompatibility failures mark the affected profile unhealthy according to the explicit retry policy and are audited; they do not silently broaden routing. Health is keyed by route and profile revision so credential rotation or profile correction clears stale permanent-disable state. All candidates must pass the requested task's structured-output, tool-calling, and context requirements before invocation.

The model cache key must include a non-secret connection/profile revision and model identity, not merely `(provider, model)`. That prevents two accounts for the same model from sharing a client. A changed/deleted connection invalidates its cache entries before the next resolution. This is an atomic core migration across `ChatModelPool`, `ModelRouter`, `ChatModelProducer`, the new `OrderedFailoverChatModel`, and every `AgentChatModelProvider` creation path; no mixed old/new cache-key state is releasable. Existing `MultiModelChatModel` behavior remains unchanged and receives only compatibility adaptations required by the new typed client keys.

Use a typed client key such as `(scopeType, scopeId, connectionId, connectionRevision, profileId, profileRevision, providerId, modelId)`. `ChatModelPool` maintains a reverse index by connection/profile ID for targeted invalidation. The pool may retain an opaque credential-bearing provider client because the client must authenticate future calls; it must not separately cache decrypted byte arrays, `VaultKey` objects, raw secret DTOs, or loggable credential material. The runtime factory API must build a client from a short-lived, closable credential view rather than reuse the current `AgentChatModelProvider.create(model)` path that reads deployment configuration. Rotation/update first commits the new revision, then invalidates old entries; in-flight calls may finish on the captured client, while subsequent resolution cannot acquire it. Deleted entries are removed and clients are closed where the implementation supports cleanup.

Ordered-chain wrappers and their health state are keyed by route/revision, not pooled merely by provider/model list, so private users cannot share cooldown, permanent-disable, or rotation state. Contract tests cover two private accounts using the same provider/model, credential rotation during an in-flight call, deletion, rollback to deployment routing, and authorized members sharing one workspace-scoped client without crossing workspaces.

Profile resolution uses `WorkspaceContext.USER_ID` plus the identity subsystem's distinct active-workspace accessor. The current chat/session workspace value must not be interpreted as an identity workspace ID. System/bootstrap routing may be used only for an unbound system operation or the defined fallback; a user session must never resolve another user's profile. This is an architecture change in core, not a dependency from core back to the onboarding module.

### 3. Provider adapter capabilities

Each supported provider contributes a core-owned `LlmProviderOnboardingAdapter` discovered through CDI. It declares safe, static capabilities:

- stable provider ID and display metadata;
- supported authentication shapes and whether credentials are required;
- allowed endpoint policy (fixed, local, or user-supplied OpenAI-compatible endpoint);
- model-ID validation rules, structured-output modes, and safe default suggestions;
- bounded model discovery and, for supported local providers, an explicit model-pull/install capability.

The adapter never supplies HTML or JavaScript. It receives only a namespaced, typed request and returns a safe validation result. Generic/OpenAI-compatible endpoints are an explicit adapter, not a free-form escape hatch: endpoints must pass URL and SSRF policy, exclude embedded credentials, and be subject to the same approval and redaction rules.

Validation has three explicit tiers:

1. `validateModelId()` is local, free, and side-effect-free. It checks provider-specific syntax and returns **Model ID format valid**, not connectivity.
2. `validateConnection()` is an optional HITL-gated endpoint/authentication probe using the connection's bounded connect/read timeouts. It prefers a non-generating metadata/model-list operation; an adapter that can validate only by making a billable inference must disclose that fact in the approval summary. The default network timeout is not inherited from the current 15-second chat probe.
3. capability compatibility is derived from reviewed adapter declarations or bounded catalog metadata for structured output, tool calling, context window, and required API tier. It is never guessed from one `chat("hi")` response.

A user can enter a locally valid model ID without catalog discovery, but review clearly shows which network/capability checks remain unverified. Local adapters such as Ollama may offer discovery and an explicit, cancellable pull/install action in quick start, with progress reported as safe job state; an adapter that cannot safely install exposes discovery/select only. Every endpoint probe, catalog request, OAuth exchange, model pull, or external validation is `NETWORK_OUTBOUND`, uses a core-owned onboarding sensitive-operation executor backed by the established approval/checkpoint service, runs outside a transaction, and leaves the candidate disabled until commit. These coordinator operations are not agent tool calls and must not fabricate a `ToolInvocation`; if an operation actually invokes a tool or shell command, the normal `ToolInvocationGuard` executes with the applicable capability, including `EXECUTE_SHELL`.

Core adapters may request governed install/pull jobs through that executor because they are reviewed platform code. They do not delegate shell or filesystem authority to the extension SPI. Vetted extensions remain limited to their typed handles and cannot acquire the core installer merely by declaring a model field.

Connection validation is a point-in-time onboarding result (`lastValidatedAt`, tier, safe category, and adapter revision), not runtime failover health. Runtime health remains a separate per-profile signal derived from actual invocations. The UI distinguishes **format valid**, **connection verified**, **capabilities verified**, and **runtime health**, and guided errors retain a bounded category (`AUTH`, `TIMEOUT`, `TLS`, `PROXY`, `MODEL_NOT_FOUND`, `TIER_DENIED`, `CAPABILITY_MISMATCH`, or `PROVIDER_UNAVAILABLE`) plus safe remediation text.

### 4. Dynamic onboarding plan and extension SPI

Replace `OnboardingStepProvider` with an onboarding-module SPI that is declarative at the UI boundary and scoped at the service boundary. Illustrative types:

```java
public interface OnboardingExtension {
    OnboardingStepDescriptor descriptor();
    PreparedOnboardingStep prepare(OnboardingContext context);
    OnboardingValidationResult validate(
            OnboardingContext context, PreparedOnboardingStep prepared, OnboardingInput input);
    CompensatableOnboardingCommit plan(
            OnboardingContext context, PreparedOnboardingStep prepared, ValidatedOnboardingInput input);
}
```

The coordinator owns the `OnboardingContext`, draft, ordered plan, authorization, audit, field-error shaping, approval requests, commit sequence, expiry, and cleanup. A context exposes only the owner/session identity, extension namespace, safe selections, and typed staging handles. It does not expose raw vault values, persistence handles, configuration writers, arbitrary files, another extension's input, or unrestricted service lookup.

This SPI is an API boundary, not a sandbox for hostile Java. CDI extensions run in-process and could inject unrelated application services unless module dependencies and build-time architecture checks prevent it. Only vetted, signed/allowlisted modules may contribute onboarding extensions. Extension modules depend on a narrow onboarding extension API, are forbidden from depending on persistence adapters, `SecretVault`, app security/resources, filesystem/shell APIs, or unrestricted HTTP clients, and are checked with architecture tests. Untrusted marketplace artifacts remain declarative SkillsJar prompt/tool wrappers and cannot install executable onboarding extension beans. The acceptance claim is therefore that conforming vetted extensions cannot cross the boundary through the supported API—not that arbitrary bytecode is contained.

Extension CDI beans are stateless. `PreparedOnboardingStep` is an immutable, serializable safe-data snapshot with an extension/version fingerprint; it is persisted as part of the draft and passed explicitly to validation and planning. An extension must not retain mutable request/draft state in bean fields or depend on call affinity. Retry, concurrent revision, restart, and repeated-validation contract tests invoke the phases on different CDI-resolved instances and require deterministic results for the same prepared snapshot.

`CompensatableOnboardingCommit` exposes a bounded, idempotent `commit(operationId)` operation and an idempotent `compensate(operationId, receipt)` operation over its own opaque commit receipt. Before invoking side effects, the coordinator durably records a `PREPARED` operation intent keyed by draft/stage/attempt. It then records the receipt and `COMMITTED` state. On restart, the same operation ID is reconciled; a crash after the external commit but before receipt persistence must not duplicate or orphan the integration. The coordinator invokes compensation in reverse order, retries failed compensation durably, and never asks one extension to infer another extension's rollback. Extension commits remain limited to their declared service boundary.

`OnboardingStepDescriptor` contains a stable extension ID/version, ordering constraints, optionality, safe availability state, and a versioned typed field descriptor set. It is deliberately **not** arbitrary JSON Schema: `OnboardingFieldDescriptor` supports a closed enum of `TEXT`, `SECRET`, `URL`, `SELECT`, `CHECKBOX`, and `MODEL_SELECTOR`, declared constraints, bounded static options, and i18n `titleKey`/`descriptionKey` values. The onboarding module provides one Java validator and one small Lit renderer for that contract; adapters do not supply markup, client validation code, or arbitrary regular expressions. Provider HTML, JavaScript, endpoint URLs with credentials, arbitrary command definitions, and executable form behavior are forbidden.

Core builds a per-draft ordered plan after it knows the available adapters and existing owner state. The plan has these core stages:

```text
welcome -> connections -> model profiles -> route assignments -> MCP -> optional extensions -> review -> complete
```

Extensions declare placement before or after a named stable core stage and a deterministic tie-breaker; they cannot replace a core stage or alter another extension's position. MCP is a **core stage**, but it uses this same draft, approval, saga, cleanup, and status machinery. The first reference extension must instead be a genuinely separate low-risk non-LLM API integration. The progression guard is index-based over this persisted plan, not an enum number. A client may resubmit its current step or move to the immediate next eligible step only. Unavailable, already configured, and optional steps have explicit safe states; `SKIPPED` is a user decision, never inferred from a failed validation.

### 5. Draft, validation, approval, and commit lifecycle

Persist a scoped `OnboardingDraftStorePort` and `OnboardingExtensionStateStorePort`. A draft stores its purpose (`FIRST_RUN`, `ADD_CONNECTION`, `EDIT_PROFILE`, `ROUTE_CHANGE`, or `EXTENSION_SETUP`), plan fingerprint, current position, selected non-secret IDs, baseline revisions, validation state, pending approval IDs, safe field errors, expiry, revision, and lifecycle state (`DRAFT`, `WAITING_APPROVAL`, `RESUMABLE`, `COMMITTING`, `COMPLETED`, `CANCELLED`, or `FAILED`). Extension state stores only extension ID/version, scope, outcome (`CONFIGURED` or `SKIPPED`), and timestamps; commit intent/receipt status belongs to the draft operation journal rather than a competing outcome enum. Neither stores a raw input map, secret, OAuth token, provider response, endpoint header, or vault value.

Secret submission uses a scoped secret-input handle. It creates a fresh staged vault reference immediately, replaces any prior staged value, and records only the reference and owning draft. The handle zeroes mutable bytes in `finally`. On validation, replacement, cancellation, expiry, denial, terminal failure, or completion, the coordinator deletes staged references that were not adopted by a committed connection. Cleanup is idempotent and durable-retryable.

Validation and commit follow a stage-bounded saga, never a long database transaction or one end-of-wizard mega-commit:

1. Validate non-secret fields locally.
2. Stage secret input under fresh vault references and create disabled candidate metadata in short writes.
3. Obtain HITL for every sensitive network/OAuth/MCP operation; resume only through the existing checkpoint lifecycle.
4. Perform validation or external handshake outside a transaction, using `ContextAwareExecutorService` or `WorkspaceScopeHolder.wrap()` where async.
5. As each stage completes, persist its operation intent, execute or reconcile that stage's idempotent `CompensatableOnboardingCommit`, and persist its opaque receipt before advancing. The commit may adopt vault references, persist disabled connection/profile/MCP candidates, or record explicit skip state, but it must not activate runtime routes or MCP tools before final review.
6. Review rechecks cross-stage references and any provider-advertised model entitlement/API-tier constraints. A stale external validation is repeated through HITL; incompatibility returns the user to the owning stage with a safe field error.
7. Finalization atomically activates the already-staged eligible records in short writes, invalidates runtime caches, and marks the draft complete.
8. Cancellation or a later-stage/finalization failure invokes persisted stage compensators in reverse receipt order. A failed compensator creates a redacted durable cleanup record and leaves the integration disabled.

Each async callback must reload the draft, verify owner, revision, expiry, and non-terminal state before touching vault, connection, MCP, or runtime state. No LLM call, external API call, or external tool execution occurs in `@Transactional` work.

### 6. UI and API experience

The Lit wizard becomes schema-driven and draft-driven rather than hard-coded to numbered components. It has two entry paths:

- **Quick start:** one provider connection, one model profile, default assignment, optional MCP, then review.
- **Advanced setup:** add/edit several connections, profiles, role/group assignments, fallback order, and optional integrations before review.

Quick start is not a second workflow. It creates the same persisted draft and plan as advanced setup, pre-seeded with exactly one candidate connection, one model profile, and one `DEFAULT` route assignment. Its compact connection screen collects and validates the fields for all three records. MCP and optional extensions are represented as explicit `SKIPPED` plan outcomes unless the user elects to open them; review and commit execute the same saga and endpoint calls as advanced setup. Switching to advanced mode simply exposes the existing draft records and remaining eligible steps.

The UI communicates the relationship clearly: `Connection -> Model profiles -> Agent/group assignments`. It displays connection health and validation time using safe metadata only; it never redisplays a secret. A role/group assignment screen presents existing canonical role/group identities, a primary profile, optional ordered fallbacks, and the outcome of static compatibility checks. It never implies that a model choice grants additional tools or permissions.

Provider/model suggestions come only from `LlmProviderOnboardingAdapter` through the shared descriptor API. The duplicated defaults in `OnboardingResource`, `arc-onboarding.ts`, and the provider-specific fallback in `arc-onboarding-step2.ts` are removed during the O1 (Foundation) migration. Descriptor i18n keys are resolved from the onboarding module's message bundles. A missing locale entry falls back to the English descriptor text; if the English key is also missing, descriptor registration fails and the extension/provider is unavailable rather than rendering a raw i18n key. Keyboard navigation, label/error association, focus management after step changes, non-color status indicators, and screen-reader announcements are O1 (Foundation) acceptance work, not later hardening.

Replace `POST /onboarding/step/{number}` with draft-oriented endpoints, for example:

```text
GET    /onboarding/bootstrap/status
POST   /onboarding/bootstrap/claim
POST   /onboarding/drafts
GET    /onboarding/drafts/{draftId}
PUT    /onboarding/drafts/{draftId}/steps/{stepId}
POST   /onboarding/drafts/{draftId}/steps/{stepId}/validate
POST   /onboarding/drafts/{draftId}/review
POST   /onboarding/drafts/{draftId}/commit
POST   /onboarding/drafts/{draftId}/cancel
```

Bootstrap claim uses its one-time operator proof and compare-and-set contract; all draft endpoints require the issued API-key principal or a valid companion-identity web session. Creating the first authenticated draft performs the `CREDENTIAL_ISSUED -> CLAIMED` transition for that same principal; a standalone unauthenticated `/confirm` endpoint is removed. Draft requests use an idempotency key and revision, and mutations reject stale revisions with a safe conflict response. Status and review responses contain only descriptors, safe field errors, IDs, states, and redacted summaries. `CompleteResponse` no longer contains `serviceKey`; authentication is already established through an HttpOnly cookie. The onboarding REST path binds the same authorized user and distinct active identity-workspace scope used by tool/channel flows and must not retain the current synthetic `onboarding-user` identity, infer identity workspace from a chat session, or rely solely on an anonymous browser cookie.

From the first O1 (Foundation) release, the draft service is the sole source of truth. Existing fixed endpoints may remain as a temporary protocol façade only if every read/mutation delegates immediately to the authenticated owner's persisted draft; they must not read or write `OnboardingSessionRegistry`/Caffeine state or bypass validation, ownership, receipts, or approval. Old and new UI assets can therefore resume the same draft, while a deployment can never run two wizard state machines. `OnboardingSessionRegistry` is removed in O1. Before O1 merges, the release plan records the concrete façade-removal version; regardless of UI migration timing, the numbered façade is removed no later than O5 and receives no new capability.

`OnboardingSessionRegistry` is deleted in Phase 1, including its Caffeine state, reflection registration, and direct tests; it is not retained as a delegating wrapper. Compatibility endpoints resolve the authenticated scope and call the durable draft coordinator directly.

`SettingsResource` is part of the same compatibility migration. `GET /settings` reads the effective redacted profile/route and prompt state. `PATCH /settings/provider` creates or updates an `EDIT_PROFILE`/`ROUTE_CHANGE` draft and follows validation, HITL, review, and commit instead of writing `LLM_PROVIDER_KEY`, `LLM_MODEL_KEY`, or `LLM_API_KEY`. Prompt mutation moves to the canonical prompt-registry path and does not write `SYSTEM_PROMPT_KEY`. `POST /settings/reset` becomes an authorized scoped deactivation/reset plan; it must never blindly delete system/workspace keys or another user's profiles. No supported endpoint may continue mutating the legacy system-vault keys after Phase 1.

`CompleteResponse` removes `serviceKey`, and the Lit completion flow—including `arc-onboarding-step6.ts` and its API types/tests—uses the already-issued HttpOnly authentication cookie rather than expecting or redisplaying a raw key.

#### Returning administration and post-setup operations

Onboarding is not a one-time global flag. An authenticated owner/admin can start a purpose-specific draft later to add a connection, edit a profile, change one route, or configure one extension without replaying first-run stages or modifying unrelated active records. Baseline revisions detect concurrent changes; abandoned partial edits remain `RESUMABLE` until expiry, and the UI offers resume/discard for each safe draft purpose.

Every optional MCP/extension stage provides **Skip for now**; required identity, connection, default-route, review, and security-approval requirements cannot be skipped. A skipped extension remains discoverable from settings and can start its own later draft.

After setup, a connection/model health dashboard shows safe validation tier/time, current runtime health, bounded recent error categories, active route consumers, model usage/cost aggregates where available, and remediation actions such as retest, review proxy/TLS, change model, or rotate credential. It never displays provider response bodies, secrets, vault references, or unbounded labels.

Owners/admins may export a versioned **non-secret configuration manifest** containing adapter IDs, redacted endpoint/network policy, model IDs/capabilities, route topology, extension outcomes, and schema versions. It excludes credentials, vault references, user/session IDs, raw custom CAs, proxy credentials, and health history. Import creates an inactive draft, validates all identifiers, asks for missing credentials, and follows normal HITL/review/commit; it never activates configuration directly.

### 7. Audit, observability, and authorization

Add non-mandatory onboarding telemetry events for draft created/expired/cancelled, connection staged/validated/committed/removed, model profile created/updated, route assignment changed, extension shown/skipped/validated/committed/failed, and cleanup retry. Reuse mandatory `APPROVAL_GRANTED` and `APPROVAL_DENIED` events for approvals; do not create a competing approval record.

Audit data is redacted and bounded: provider adapter ID, connection/profile IDs, target kind/ID, model ID, extension ID, outcome, and error category are acceptable. Secret values, vault key references, raw endpoint values, headers, OAuth responses, user IDs as metric labels, and provider error bodies are not. Mandatory classification and redaction have explicit tests.

The same bounded event stream supplies product funnel metrics: draft started, identity completed, each stable core stage reached, validation outcome, resume/restart, commit, and abandonment/expiry. Metrics use only fixed stage/outcome and registered adapter/extension IDs as labels; they never contain a user, draft, connection, endpoint, or free-form error label. Retention and opt-out follow the application's existing telemetry policy.

Every read and mutation authorizes the draft/resource scope through the companion identity service. Private records require their owner; workspace records require active membership, and rotation/deletion requires owner/admin role. Admin read endpoints may expose existing safe metadata under existing policy; mutating `/admin/**` endpoints retain the `isInternal()` invariant, implemented by the stable principal's owner/admin role during identity migration. No user can attach, rotate, test, or delete another user's private profile.

## Persistence and native-image requirements

The implementation uses or adds ports only when durable state is needed:

- the replacement `BootstrapStateStorePort` contract for the atomic first-owner/workspace reservation and persisted API-key digest;
- `LlmProfileStorePort` for connections, profiles, and route assignments;
- `OnboardingDraftStorePort` for resumable draft metadata and cleanup ownership;
- `OnboardingExtensionStateStorePort` for durable optional-step outcomes.

The companion identity design places the narrow two-phase `BootstrapCredentialIssuer` SPI in a core-owned security API and implements it in the app module over `ApiCredentialStorePort`, `BootstrapStateStorePort`, and `ApiKeyRegistry`. Preparation creates only mutable credential material bound to a typed reservation; activation verifies that reservation, persists the credential, and registers it in that order. This preserves module direction: onboarding does not depend on `amsha-app`, and the SPI cannot issue credentials for arbitrary principals outside the reserved first-owner contract. The two-phase shape replaces the current single `issueCredential(String ownerId)` plus `rollbackCredential(String ownerId, String keyDigest, String rawKey)` methods: preparation returns a closable `PreparedBootstrapCredential` (raw key + digest + reservation reference) without persisting or registering; activation takes that prepared credential and the matching `CREDENTIAL_ISSUED` reservation revision, then durably writes the `ApiCredential` record and registers the digest in `ApiKeyRegistry` in that order; the old `rollbackCredential` is removed because a failed/unactivated preparation holds no durable state and needs no rollback.

Each port requires SQLite, PostgreSQL, and filesystem implementations, equivalent contract tests, and paired Flyway schema migrations for relational data. JDBI rows/DAOs use the project's required constructor mapper, producer injection, and bindings. All new JSON/JDBI records, DAOs, CDI beans, and provider/extension discovery types receive the appropriate native reflection registration; native-sensitive compilation is part of acceptance.

MCP and LLM records share the same owner-scoped, vault-reference, optimistic-revision, staged-cleanup concerns. Extract reusable lifecycle/cleanup helpers and record components in the appropriate common/core boundary rather than duplicating them three times. Do not create a generic persistence super-port: each aggregate retains a typed port and its own authorization/transition rules.

## Alternatives considered

### Keep the fixed, single-provider wizard

Rejected. It cannot represent multiple accounts, model reuse, role assignments, resumable extension setup, or a safe plugin commit lifecycle. Adding branches to it would make the central resource a registry of every integration.

### Put all provider keys and assignments in dynamic YAML/environment configuration

Rejected. Runtime mutation of deployment configuration conflicts with immutable deployment practice, leaks secrets into a second storage model, and cannot provide owner scope, cleanup, revision control, or approval recovery.

### Let each agent own a provider key and model directly

Rejected. It duplicates credentials, prevents intentional sharing, couples authorization to a secret, and makes multi-provider fallback difficult to audit. Agents should reference approved model profiles only.

### Make generic OpenAI-compatible setup the universal provider form

Rejected. It loses provider-specific validation/capability knowledge and creates an unsafe arbitrary-endpoint bypass. It remains one constrained adapter alongside explicit providers.

### Let extensions render custom UI and commit directly

Rejected. It weakens reviewability, browser security, persistence parity, authorization, and cleanup guarantees. Extensions declare schema and plans; the core coordinator controls lifecycle execution.

### Build password/OIDC identity as part of onboarding

Rejected as onboarding-module behavior. The companion identity ADR commits to OIDC-first login, opaque sessions, stable users, multiple administrators, workspaces, and API-key fallback. Onboarding consumes those contracts and does not implement authentication itself.

### Add a `shared` flag to an owner connection

Rejected. A boolean cannot express membership, grants, revocation, audit ownership, or what happens when an owner leaves. The companion identity ADR instead provides workspace scope, membership roles, and explicit connection grants so one secret can be used safely without copying it.

### Automatically transfer legacy system credentials to the first user

Rejected. It silently changes ownership and may select the wrong principal. The accepted automatic migration creates a behavior-preserving `SYSTEM` profile without moving the secret; transfer to `USER` or `WORKSPACE` scope remains explicit and auditable.

## Delivery plan

### Cross-ADR dependency graph

Identity phases are prefixed `I`; onboarding phases are prefixed `O`. A phase cannot reach production-complete status until every dependency in its row is complete, although implementation behind disabled flags may proceed earlier.

| Deliverable | Depends on | Reason / allowed parallel work |
| --- | --- | --- |
| I1 identity persistence ports | — | Foundational user/workspace/API credential/session records. |
| I2 API-key principal migration | I1 | Stable principals must replace key labels before onboarding ownership. |
| I3 opaque sessions + identity authorization | I1, I2 | Stable authenticated principals are required before any durable onboarding draft is created. |
| O1 safe first-owner bootstrap + durable drafts | I1, I2, I3 | Replaces the provisional bootstrap contract and consumes stable identity/session authorization. |
| I4 OIDC/OAuth + multi-admin | I1, I2, I3 | May proceed in parallel with O1 after identity contracts freeze. |
| I5 workspace vault scope + grants | I4 | Requires durable memberships and authorization roles. |
| O2 scoped LLM profiles | O1, I5 | Private-profile code may be developed earlier, but the phase is incomplete until workspace grants/vault scope pass contracts. |
| O3 model-pool/routing cutover | O2, I5 | Resolver must handle user, workspace, system, deployment, and legacy routes atomically. |
| O4 MCP + extensions | O1, O3, I5 | Uses the shared saga and the same private/workspace authorization model. |
| O5 operations/removal | O2, O3, O4 | Health, re-entry, import/export, and façade removal require stable runtime paths. |

Current implementation checkpoint: substantial I1–I5 identity foundations, the reservation-based bootstrap port/resource, durable draft/extension-state persistence, and the O1 Lit wizard are implemented in `core`, `persistence-*`, `onboarding`, and `ui`. This does not assert that every phase acceptance criterion is complete. I5 is complete: `SecretVault` has a typed `WORKSPACE:` vault scope with an OWNER/ADMIN store/remove gate and active-member check, `WorkspaceConnectionGrant*` durable authorization records exist with SQLite/PostgreSQL/filesystem adapters, and the runtime context has a distinct active identity-workspace binding via `WorkspaceContext.ACTIVE_WORKSPACE_ID` (propagated through the context-aware executor) with a guarded `ActiveWorkspaceContext` request-scoped fallback. O3 (runtime routing) is complete and gated behind `amsha.llm.profile-routing.enabled` (default false). Bootstrap has a reservation state machine, state-bound credential activation/reissue, authenticated confirmation/recovery, safe cookie policy, and narrowed public paths. The draft service is the sole onboarding state store: `OnboardingSessionRegistry` and the numbered-step façade are removed, `SettingsResource` opens/updates a durable draft, and `CompleteResponse.serviceKey` is removed. O2 (provider adapters/SPI + outbound-network policy + tiered validation), O4 (MCP/extensions on the shared saga), and O5 (ops dashboard, import/export, façade removal by recorded version) are partially in progress: O2 has the descriptor SPI + outbound-network policy + tiered validation implemented; O4 has the MCP extension on the shared saga + coordinator wiring implemented; O5 has re-onboarding + non-secret profile export/import, cockpit health endpoint (`GET /api/v1/cockpit/health`), UI health dashboard (`arc-health-dashboard.ts` at `#/health`), and operational transport/db metrics and audit enrichment implemented. Remaining work across all three is recorded in their status rows below.

**ADR disposition (2026-07-18): Closed — implementation baseline accepted for real-environment testing.** This closes the architecture decision and freezes its current scope; it does not relabel deferred enhancements as delivered. The status rows retain the follow-on backlog for a future ADR or tracked work item. The DNS-pinned/trusted-egress decision remains explicitly deferred under the conditions below.

**Implementation status (as of 2026-07-17):**

| Item | Status |
| --- | --- |
| §0 Bootstrap reservation state machine (`UNCLAIMED→CLAIMING→CREDENTIAL_ISSUED→CLAIMED`) in `BootstrapStateStorePort` + 3 adapters (sqlite/postgres/fs) | Done |
| Two-phase `BootstrapCredentialIssuer` SPI (`prepare`/`activate`), `PreparedBootstrapCredential`, app `ApiKeyRegistry` state-bound gate | Done |
| `BootstrapResource` (status/claim/recovery/confirm), loopback + internal-key recovery audit, narrowed `ApiKeyAuthFilter` public paths | Done |
| `OnboardingDraftStorePort` + `OnboardingExtensionStateStorePort` + 3 adapters each, V8 migrations (sqlite/postgres), JSON list columns | Done |
| `OnboardingCoordinator` (create/get/move-to-step/commit/discard, owner guard, server-side secret scrubbing, review-gated system LLM activation) + extension SPI types (`OnboardingExtension`, `OnboardingContext`, `PreparedOnboardingStep`, `OnboardingValidationResult`, `ValidatedOnboardingInput`, `CompensatableOnboardingCommit`, `OnboardingStepDescriptor`, `OnboardingFieldDescriptor`) | Implemented: extension saga wiring, optimistic revision handling, discard cleanup, and extension execution are present; remaining lifecycle acceptance work is tracked in the O1 row below. |
| `OnboardingResource` draft endpoints (`POST /drafts`, `GET /drafts/{id}`, `PUT …/steps/{stepId}`, `POST …/commit`, `POST …/discard`) replacing numbered façade | Done |
| `OnboardingSessionRegistry` + Caffeine state + legacy numbered-step DTOs removed | Done |
| `SettingsResource` provider update delegated to a durable draft; prompt compatibility endpoint remains | Partial — prompt still writes the legacy system-prompt vault key |
| Contract tests: `PersistencePortContractTest` (bootstrap reservation + draft), `BootstrapResourceTest`, `OnboardingResourceTest`, `SettingsResourceTest` | Present; focused resource checks are green |
| **Lit onboarding wizard UI** (replaces numbered `arc-onboarding-step1..6`) | Done for the O1 draft payload: welcome, connections, routing, optional-stage forms, review, resume/discard, static catalog, schema-driven descriptor renderer, and WAI-ARIA accessibility |
| O1 Durable onboarding draft lifecycle, wizard, and safe bootstrap | In progress — the draft service is the sole onboarding state store. OnboardingSessionRegistry and the numbered-step facade are removed. SettingsResource opens/updates a durable draft. CompleteResponse.serviceKey is removed. Draft revision/conflict handling is implemented: updateStep rejects stale revisions (optimistic-lock), commitDraft rejects non-review steps. Durable staged-secret cleanup is implemented: cleanupStagedSecrets(draft) iterates the draft selections, finds all staged vault references (LLM API keys and MCP auth headers), and deletes the ones not adopted by a committed connection. cleanup is called on discard (cancellation) and is idempotent. Tiered provider validation is wired into the coordinator updateStep for the connections step: LlmProviderOnboardingAdapter.validate() is called with the connection selections, and safe field errors are persisted in the draft. Typed step and field descriptors with i18n coverage (fallback and missing-key rejection) and form input validation are served via `GET /onboarding/steps/descriptors`. HITL-gated connection validation (`POST /drafts/{id}/validate-connection`) and approval resolution (`POST /drafts/{id}/approvals/{id}/resolve`) enforce safe tiered probing. Optional stage configuration (integrations, memory, channels, security, advanced, concern routes) is activated during commit via `OnboardingCommitSaga`. Accessibility test coverage verified with Vitest. Verified by OnboardingCoordinatorExtensionTest (12) + OnboardingResourceTest (5) + CrashInjectionReceiptTest (5) + OnboardingApprovalServiceTest (6) + OnboardingStepDescriptorServiceTest (3) + OnboardingStepValidatorTest (7) + OnboardingI18nServiceTest (5) + a11y-onboarding.test.ts (6). Remaining O1: browser-level UI E2E. |
| O2 LlmProfileStorePort + adapters/migrations, provider SPI, outbound-network policy, tiered validation | In progress — durable records (LlmConnection, LlmConnectionSecretRef, ModelProfile, ModelRouteAssignment) + WorkspaceConnectionGrant + LlmProfileStorePort/WorkspaceConnectionGrantStorePort with SQLite/PostgreSQL/filesystem adapters and paired V9 migrations are implemented and contract-tested. Provider onboarding is served through a typed descriptor SPI: LlmProviderDescriptor/ModelEntry + LlmProviderOnboardingAdapter with StaticProviderCatalogAdapter. Tiered provider validation is invoked from the coordinator connections step. Per-connection outbound-network policy supports TLS trust mode (SYSTEM/CUSTOM_CA), bounded connect/read timeouts, no-proxy host patterns, and proxy credential vault references; submitted network profiles are schema-validated before persistence. GovernedHttpClient provides the governed transport. MCP agent registration now consumes a short-lived, single-use receipt minted only after the durable checkpoint records approval for `register_mcp_server`; it binds actor, session, purpose, and host, and the MCP client rejects a receipt for another user before the handshake. The grant authorizes the complete MCP handshake as one approved operation. Remaining O2 work is deliberately deferred: live catalog discovery, OAuth migration, and richer network-profile UI. |
| O4 MCP + reference extension on shared saga | In progress — MCP is an optional EXTENSION_SETUP stage using the shared OnboardingExtension saga. Header values are scrubbed to vault references, resolved only at commit, revalidated, and persisted vault-backed; unresolved staged headers fail closed. Commit receipts have SQLite/PostgreSQL/filesystem parity and V10 migrations. CrashInjectionReceiptTest (5) verifies durable SQLite receipt states and upsert/isolation, not a process crash/restart. Recovery reconstructs COMMITTED entries for possible compensation, re-executes PREPARED and COMPENSATED entries, and blocks on COMPENSATION_FAILED. MCP client HTTP traffic uses the governed transport for policy, TLS, literal-address SSRF, redirect-header, and timeout controls; true DNS connection pinning remains outstanding. Remaining O4: expose MCP setup in the Lit wizard; replace call-site approval booleans with checkpoint-backed operation grants that bind actor, session, purpose, host, expiry, and the complete handshake; implement DNS-pinned/trusted-proxy transport; and add end-to-end process-restart recovery coverage. |
| O5 ops dashboard, import/export, facade removal by recorded version | In progress — re-onboarding is implemented: `getOrCreateReonboardingDraft` creates purpose-specific drafts. Export/import of non-secret profile records with full topology via REST endpoints (`GET /onboarding/export`, `POST /onboarding/import`). Health dashboard endpoints (`GET /api/v1/cockpit/health` with bounded categorical non-disclosure and `GET /onboarding/health/routing`). UI health dashboard (`arc-health-dashboard.ts` mounted at `#/health` with system status, DB latency, JVM memory/disk, provider health checks, and a11y tests). Operational transport/database metrics (`amsha.transport.*`, `amsha.dbconsole.*`) and audit enrichment (`EGRESS_TRANSPORT_*`, `DB_QUERY_*`) implemented. Native-image reflection registered in `AppReflectionConfig`. The legacy numbered-step facade is confirmed removed. Staged-secret cleanup on discard (`cleanupStagedSecrets`). Onboarding draft TTL is 7 days; abandoned partial edits remain RESUMABLE until expiry. Remaining O5: browser-level UI E2E and broader import/recovery failure-path tests. |

**Current transport limitation:** `GovernedHttpClient` performs DNS revalidation before delegating to JDK `HttpClient`, which may resolve the hostname again. It is therefore not a true DNS-pinned transport yet. Do not claim DNS-rebinding completion until remote requests connect through a validated-address transport or a trusted egress proxy that owns DNS resolution and connection pinning.

**Long-term deferral — DNS-pinned / trusted-egress transport:** This is intentionally deferred beyond the current onboarding programme. It is an infrastructure/security-architecture decision, not a prerequisite for completing the current product flows. The current governed transport remains the required path and continues to enforce HTTPS, literal-address SSRF checks, pre-connect DNS revalidation, redirect-hop validation, bounded timeouts, proxy policy, and custom-CA policy. Reopen this item before allowing arbitrary user-supplied remote endpoints in a multi-tenant production deployment, or when the deployment platform provides a corporate/trusted outbound proxy. The eventual solution may be either a trusted egress proxy that owns validation and connection, or a custom validated-address transport that preserves HTTPS hostname/SNI verification. A NAT gateway, firewall, or Kubernetes network policy alone does not satisfy this requirement.

**Next implementation sequence:**

1. **O2 adoption and approval:** route provider catalog/probe and OAuth exchanges through `GovernedTransportService`; remove remaining direct outbound clients; replace manual booleans with a checkpoint-backed operation grant. If represented as a token, it must bind actor, session, purpose, exact target authority, expiry, and the complete multi-request handshake, and may be minted only by the approved checkpoint transition.
2. **O2 connection experience:** add live catalog discovery through that service; expose persisted `networkProfileJson` in the wizard and `SettingsResource` edit path; wire proxy credentials and custom-CA references through scoped vault flows.
3. **O4 completion:** surface MCP setup in the Lit wizard and add a process-restart saga-recovery integration test.
4. **O1/O5 product closure:** finish browser-level UI E2E and broader import/recovery failure-path tests.
5. **Deferred transport hardening:** only when the stated deployment trigger is met, replace direct remote JDK HTTP with a DNS-pinned transport or trusted egress proxy and add tests proving the connected address is the checked address.
6. **I4 separately:** perform a dedicated identity audit before claiming OIDC/OAuth and multi-admin complete; it is not asserted complete by this ADR.
### UI implementation record and remaining O1 work

The legacy `arc-onboarding-step1..6` components and numbered endpoint flow are removed. `arc-onboarding.ts` now drives the durable draft API and composes plan-stepper, welcome, connections, concern-routing, persona-routing, MCP, integrations, advanced-settings, memory, security, channels, identity, and review components. The current implementation covers the O1 payload and commits only the selected default provider/model/API-key to the legacy system LLM keys. The remaining configuration screens are draft capture only until their typed O2–O4 activation paths exist.

#### Configuration surfaces requiring onboarding paths

| Domain | `.env` surface | How the wizard covers it |
| --- | --- | --- |
| **LLM connections** | 12 providers (`groq`, `gemini`, `openrouter`, `ollama`, `openai`, `omlx`, `nvidia`, `cerebras`, `generic-openai`, `llamacpp`, `infinite-loop`, `claude-code`/`anthropic`) — each has `_API_KEY`, `_BASE_URL`, `_MODEL`, `_MAX_RETRIES` | Connection step: select provider adapter → enter API key(s) (comma‑separated → multiple slots) → optional base‑URL override → optional headroom‑proxy toggle (if enabled in deployment config) → timeout/retry tuning |
| **Model profiles** | Per‑provider models (e.g. `GROQ_MODEL`, `CEREBRAS_MODEL`, `OMLX_MODEL`), context‑window auto‑bootstrap, structured‑output mode (`PROMPT_DIRECTIVE` vs `NATIVE`/`LENIENT`) | Model‑profile step (per connection): select or enter model ID → context window (auto‑detected or manual) → structured‑output mode → capability checkboxes (tool‑calling, vision, etc.) |
| **Concern routing** | 4 independent provider lists: `INFINITE_LOOP_LLM_PROVIDERS` (agent loop), `INFINITE_LOOP_COMPACTION_PROVIDERS`, `INFINITE_LOOP_CLASSIFIER_PROVIDERS`, `INFINITE_LOOP_EMBEDDING_PROVIDERS` — plus per‑concern per‑provider keys/models/base‑URLs (e.g. `COMPACTION_GROQ_API_KEY`, `CLASSIFIER_GROQ_API_KEY`, `EMBEDDING_OLLAMA_MODEL`) | Concern‑routing step: assign an ordered provider+model chain per concern (agent loop / compaction / classifier / embedding) with explicit failover order. Quick‑start pre‑fills all concerns from one connection; advanced allows independent concern routing. |
| **Persona / group routing** | `AMSHA_LLM_PERSONA_MODELS_{ROLE}_{N}_PROVIDER/MODEL`, `AMSHA_LLM_GROUP_MODELS_{GROUP}_PROVIDER/MODEL` (per‑persona primary + numbered failover, per‑group default) | Role‑assignment step (advanced): present canonical roles/groups, allow per‑role primary model + ordered failover chain, per‑group default. |
| **MCP servers / outbound integrations** | `MCP_SERVER_ENABLED`, `MCP_HEALTH_CHECK_INTERVAL_MS`, `MCP_PODMAN_HEALTH_CHECK_INTERVAL_MS` + per‑server command/args | MCP step: add SSE, HTTP, Stdio, or Podman MCP servers with connection strings, credentials, and health‑check intervals. |
| **Tool integrations** | `TOOLS_PLAYWRIGHT_*` (headed/headless, MCP mode), `TOOLS_BRAVE_API_KEY`, `TOOLS_SERPER_API_KEY`, `TOOLS_GITHUB_TOKEN`, `SKILLSJARS_REQUIRE_APPROVAL_FOR_SENSITIVE`, `SKILLS_ADAPTIVE_*` | Integrations step: enable/configure Playwright, web‑search providers, GitHub token, SkillsJars policy, executable overrides (Python/Go/Java), sandbox strategy picker. |
| **Runtime tuning** | `LLM_TEMPERATURE`, `LLM_MAX_OUTPUT_TOKENS`, `LLM_TIMEOUT`, `LLM_MAX_RETRIES`, `LLM_COMPACT_PROMPT_ENABLED`, `LLM_CIRCUIT_BREAKER_ENABLED`, `AGENT_SCHEMA_CORRECTION_*`, `AGENT_MAX_RATELIMIT_RETRIES`, `DELEGATION_MAX_DEPTH`, `HITL_EXEMPT_TOOLS`, `BUDGET_LIMIT`/`BUDGET_ACTION` | Optional advanced‑settings step (collapsed by default): expose common knobs (temperature, max‑tokens, timeout, circuit‑breaker, delegation‑depth, budget‑limit, HITL exempt‑tools) as categorized toggle/input groups with safe defaults. |
| **Memory / self‑evolve** | `MEMORY_TIERED_ENABLED`, `MEMORY_CORE_*`, `MEMORY_RECALL_*`, `MEMORY_ARCHIVAL_*`, `MEMORY_RETENTION_*`, `SELF_EVOLVE_*`, `EMBED_DIMENSIONS`, `RAG_VEC_PATH` | Memory step (advanced): enable tiered memory, core‑memory limits, recall retention, archival eviction thresholds, self‑evolve toggle, embedding dimension, RAG vec path. |
| **Security / governance** | `VAULT_PEPPER`, `AUDIT_HMAC_KEY`, `AUTH_HMAC_KEY`, `API_KEYS_INTERNAL`, `API_RATE_LIMIT_*`, `AUTH_SESSION_*`, `SANDBOX_*` (strategy, network, memory/CPU limits, timeouts, circuit‑breaker), `7Z_PASS` | Security step (advanced): internal API keys, rate limits (requests/mutation per minute), session idle‑timeout, sandbox strategy and resource caps, archive password, host‑exec skills. |
| **Channels** | Telegram (`TELEGRAM_BOT_TOKEN`, `ALLOWED_USER_IDS`, `ALLOWED_USERS`, `MAX_LENGTH`), Web (`WEB_ENABLED`, `WEB_REQUIRE_AUTH`), scheduling (`DEFAULT_TIMEZONE`, `CRON_STARTUP_DELAY_MS`), observability (`OTEL_*`, `PHOENIX_*`) | Channels step (advanced): enable Telegram with bot token and allowed‑user filtering, web‑chat toggle + auth, timezone, cron startup, Phoenix / OTLP observability. |
| **OIDC / external login** | `AUTH_IDP_OIDC_*` (client‑id/secret, auth/token/userinfo URLs, JWKS, redirect), `AUTH_IDP_GITHUB_*` (client‑id/secret, redirect) | Identity step (advanced, if OIDC/github auth is configured on the backend): OIDC provider setup, GitHub OAuth, redirect URIs. |

#### Wizard structure (Lit components)

The new wizard replaces `arc-onboarding.ts` and `arc-onboarding-step1..6.ts` with a schema‑driven draft‑driven wrapper that renders back‑end plan stages as Lit sub‑components. The route `#/onboarding` still gates access; after bootstrap claim, the wizard creates or resumes a persisted draft via `POST /onboarding/drafts` and advances through the plan with `PUT …/steps/{stepId}`.

```
<arc-onboarding-wizard>                        ← entry point, replaces arc-onboarding.ts
  <arc-onboarding-draft-loader>                ← loads/resumes the draft from GET /drafts/{id}
  <arc-onboarding-plan-stepper>                ← renders plan stages as a Lit stepper (horizontal or vertical, depending on viewport)

  Core stages (always present in plan):
    <arc-onboarding-step-welcome>              ← intro card; quick‑start button seeds defaults and skips optional stages
    <arc-onboarding-step-connections>          ← multi‑connection table: add/edit/remove/validate per provider
      <arc-onboarding-connection-card>         ← provider selector, API‑key input(s), base‑URL, headroom toggle, timeout/retry
      <arc-onboarding-model-profile-card>      ← per‑connection model selector, context, structured‑output mode, capabilities
    <arc-onboarding-step-concern-routing>      ← grid: Agent Loop | Compaction | Classifier | Embedding, each with ordered provider+model chain
    <arc-onboarding-step-review>               ← cross‑stage summary, redacted connection list, route topology

  Optional stages (appear in plan only when relevant deployment config or the user opts in):
    <arc-onboarding-step-persona-routing>      ← per‑role/per‑group primary model + failover chains
    <arc-onboarding-step-mcp>                  ← MCP server add/health‑check UI
    <arc-onboarding-step-integrations>         ← Playwright, web‑search, GitHub, SkillsJars, executable overrides
    <arc-onboarding-step-advanced-settings>    ← temperature, max‑tokens, timeout, circuit‑breaker, delegation, budget, HITL
    <arc-onboarding-step-memory>               ← tiered‑memory, self‑evolve, embedding, RAG
    <arc-onboarding-step-security>             ← internal keys, rate limits, sandbox, session, archive password, host‑exec
    <arc-onboarding-step-channels>             ← Telegram, Web, timezone, cron, observability
    <arc-onboarding-step-identity>             ← OIDC / GitHub OAuth (active only when backend IDP config exists)

  <arc-onboarding-commit-button>               ← POST /onboarding/drafts/{id}/commit
</arc-onboarding-wizard>
```

#### Quick‑start vs advanced

**Quick‑start** (triggered from the welcome step) pre‑seeds the draft with:
- 1 connection (the first provider the user configures in a compact form)
- 1 model profile for that connection
- All 4 concerns (agent loop, compaction, classifier, embedding) default to that one profile
- All optional stages marked SKIPPED in the draft plan

The quick‑start connection card collects provider, API key, and model in one compact form — it creates the same draft records as the full connections step, just with fewer fields exposed. The user can switch to **advanced** at any step by clicking the "Advanced setup" toggle, which exposes the full stage list and all configuration fields.

#### Draft data contract (O1 schema)

Until O2 (`LlmProfileStorePort`) delivers typed backend records, the draft carries the full wizard state in its safe non‑secret fields (extending `selectedNonSecretIds` and `safeFieldErrors` with a structured JSON payload under a reserved draft key). Secrets are staged via the draft's secret‑input handle (vault reference placeholders). The API between the UI and the draft endpoints is:

```
POST /onboarding/drafts                              → create/get‑or‑resume draft
GET  /onboarding/drafts/{id}                         → read current state (plan stages, selected IDs, field errors)
PUT  /onboarding/drafts/{id}/steps/{stepId}          → advance to step; payload may include step‑specific safe field updates
POST /onboarding/drafts/{id}/commit                  → atomically activate all staged records, mark draft COMPLETED
POST /onboarding/drafts/discard                      → discard active draft (cleanup staged refs)
```

The draft DTO `selectedNonSecretIds` carries a JSON‑serialized `O1DraftPayload` with:

```
{
  connections: [{provider, apiKeyVaultRef?, baseUrl?, modelId, contextWindow, structuredOutputMode, capabilities[], headroomEnabled?, timeoutMs?, retries?}],
  concernRoutes: { agentLoop: [profileIdx...], compaction: [profileIdx...], classifier: [profileIdx...], embedding: [profileIdx...] },
  personaRoutes: { roleId: { primary: profileIdx, failover: [profileIdx...] } },
  groupRoutes: { groupId: { default: profileIdx } },
  mcpServers: [{name, transport, command?, args?, url?, envVars?, healthCheckIntervalMs?}],
  integrations: { playwrightEnabled, playwrightHeaded, braveApiKeyVaultRef?, serperApiKeyVaultRef?, githubTokenVaultRef?, skillsjarsApprovalRequired, skillsMaxIterations },
  advanced: { temperature, maxOutputTokens, timeoutMs, maxRetries, compactPrompt, circuitBreaker, delegationDepth, budgetLimit..., hitlExemptTools[] },
  memory: { tieredEnabled, coreMaxBlocks..., recallRetention..., archivalEnabled..., selfEvolveEnabled, embedDimensions, ragVecPath },
  security: { rateLimitRequestsPerMin, rateLimitMutationsPerMin, sessionIdleMinutes, sandboxStrategy, sandboxMemoryLimit..., sandboxCpuLimit..., archivePassVaultRef?, hostExecSkills[] },
  channels: { telegramEnabled, telegramBotTokenVaultRef?, telegramAllowedIds[], webEnabled, webRequireAuth, timezone, cronStartupMs, phoenixEnabled, phoenixEndpoint }
}
```

Secrets (API keys, bot tokens, OIDC client‑secrets, passwords) are submitted via a scoped secret‑input handle: the UI sends the credential, the backend creates a staged vault reference and records only the opaque ref in the draft's selected IDs. The UI never echoes or stores the raw secret. This prepares the draft for O2's `LlmConnectionSecretRef` migration without changing the wire contract.

#### Removed from the codebase

The six numbered step components, Caffeine session registry, numbered resource DTOs, and numbered resource methods are removed. `arc-onboarding.ts` remains the Lit entry point and now composes the durable-plan child components under `ui/src/components/onboarding/`; `arc-shell.ts` routes onboarding to that element. The deprecated `client.ts` numbered-flow helpers remain temporarily as unused compatibility methods and should be removed when downstream callers are confirmed migrated. After bootstrap claim, the shell uses the HttpOnly cookie rather than `OnboardingCompleteDto.serviceKey`.

#### Remaining dependencies

- **Provider adapter list**: until O2 delivers `LlmProviderOnboardingAdapter` CDI beans, the wizard uses the static `GET /onboarding/provider-catalog` endpoint backed by `onboarding/src/main/resources/provider-catalog.json`. It provides the current known provider IDs, safe defaults, and form metadata; it is not adapter discovery or a typed capability contract.
- **Role / group list**: persona/group routing is captured in the O1 payload. Authoritative role/group discovery and validation remain O2/O3 work.
- **Model discovery**: incremental — the O1 UI allows free‑text model‑ID entry with format validation; O2 adds adapter‑driven catalog/metadata.
- **Draft plan generation**: `OnboardingCoordinator.getOrCreateFirstRunDraft` seeds the current fixed O1 stage list and persists it as the draft plan fingerprint. Dynamic descriptor-driven plans remain pending.

#### Verified contract (backend to UI)

```
1. POST /onboarding/bootstrap/claim     → 200, cookie AMSHA_API_KEY, {ownerId, workspaceId}
2. POST /onboarding/drafts              → 200, {draftId, currentStepId:"welcome", planFingerprint:"…", selectedNonSecretIds:[…]}
3. PUT  /onboarding/drafts/{id}/steps/{stepId}  {…step-specific updates…}  → 200, updated draft
4. PUT  /onboarding/drafts/{id}/steps/{stepId}  {…step-specific updates…}  → 200 (repeat per stage)
5. POST /onboarding/drafts/{id}/commit  → 200, {state:"COMPLETED"}
6. POST /onboarding/drafts/discard      → 200, {discarded:true}
```

Back-end tests cover bootstrap, durable draft persistence, draft navigation, commit, discard, and malformed payload rejection. UI production compilation and Vitest component/a11y test suites are covered; browser-level Playwright/Lit end-to-end coverage remains pending.

**Safe start order.** The dependency column is a hard sequencing rule, not a suggestion. Work may begin now in parallel on Identity I4 (OIDC/multi-admin) and Onboarding O1 (safe bootstrap + durable drafts), because both build only on the already-present I1–I3 foundation. Onboarding O2, O3, and O4 must NOT start until Identity I5 (distinct active identity-workspace context, `WORKSPACE` vault scope, and connection grants) is complete: those phases reference `WORKSPACE`-scoped vault partitions and the active identity-workspace accessor that do not yet exist, and building against the current `USER`/session-only `SecretVault` would force a rework. O5 depends on O2–O4 and follows naturally. A phase may be developed behind its disabled flag or as inert records earlier, but it cannot reach production-complete status until every dependency in its row is done.

0. **Identity completion gate:** retain the implemented identity foundation and complete the distinct active-workspace context, `WORKSPACE` vault scope, and grant/authorization contracts before any workspace-scoped onboarding phase is production-complete.
1. **Bootstrap and foundation:** replace the existing `claimFirstOwner(ownerId, credentialDigest, issuedAt)` signature and combined persist/register credential SPI with the reservation and state-bound activation sequence in §0, including all three adapters/migrations. Integrate one-time claim/recovery protection, authenticated confirmation, internal-key recovery audit/disable behavior, and narrowed authentication bypass. Create scoped draft/extension-state contracts, operation journal, stateless prepared-step/compensation SPI, index-based plan guard, resume/restart and expiry/cleanup service, and typed-descriptor Lit shell. Accessibility and i18n are included. In the same release, make the draft service the only state store, delete `OnboardingSessionRegistry`, delegate every old onboarding/settings mutation endpoint, and remove `serviceKey` consumption from the UI.
2. **Inactive LLM profiles:** add `LlmProfileStorePort` and all backend adapters/migrations; add vault-backed connection staging, workspace grants, outbound network profiles/timeouts, provider adapters, tiered validation, local discovery/pull, and typed model capabilities. Profiles/routes remain inactive and runtime continues using existing deployment/system configuration, so Phase 2 can ship or roll back independently.
3. **Model-pool and routing cutover:** treat this as its own tracked workstream. Add `ModelProfileResolver`, `OrderedFailoverChatModel`, typed pool keys/reverse invalidation, and the automatic behavior-preserving `SYSTEM`-profile migration. Update `AgentGroupRegistry` plus its conflicting overlap test, `ChatModelPool`, `ModelRouter`, `ChatModelProducer`, compatibility integration with `MultiModelChatModel`, and every provider creation call site atomically. Ship the runtime `@ConfigProperty` `amsha.llm.profile-routing.enabled` with default `false` in `application.yaml`, `.env.example`, and the relevant README; it is not a build-time branch. Run the same cache-isolation, precedence, group-registration, and rollback suite with the flag both off and on for at least one release before changing the default. Turning it off immediately restores deployment/system/legacy routing without deleting profile records.
4. **MCP and extensions:** migrate the existing MCP **core** stage to the shared draft/saga lifecycle. Add one genuinely separate low-risk non-LLM API reference extension, including availability, skip/configured state, compensation receipts, and async context tests.
5. **Operations and removal:** add the post-setup health dashboard, purpose-specific re-onboarding, non-secret export/import, audit/funnel/redaction/authorization coverage, native checks, UI tests, backend contracts, documentation/config updates, and focused-verification entries. Remove the numbered-step façade by the version recorded before O1 and no later than this phase. Remove direct legacy system-vault routing only at a major-version boundary after automatic migration and offline recovery are proven.

Each phase is additive until its explicit cutover. Phase 1 rollback returns to the previous binary while leaving ignored draft tables; Phase 2 records are inert; Phase 3 has the routing cutover flag and legacy fallback; Phase 4 keeps existing MCP connections active until their new receipt is committed; Phase 5 is operational UI/API work. No migration deletes legacy keys or active routes in the same release that first introduces their replacement.

Every Java phase runs `python3 ./scripts/fix_fqns.py && ./mvnw spotless:apply -q`, compiles affected modules, and runs focused tests. The Definition of Done includes `BootstrapStateStorePort` and `LlmProfileStorePort` three-backend contract tests, bootstrap takeover/restart/concurrency/orphan-cleanup and pre-activation credential-rejection tests, `ModelProfileResolver`/`ModelRouter` precedence and cache-isolation tests, group-overlap rejection tests, draft-expiry and old-endpoint delegation tests, crash-injection between commit side effects and receipt persistence, deterministic extension retry/restart and compensation tests, `OutboundHttpPolicyService` proxy/TLS/redirect/DNS-rebinding/SSRF tests, native-sensitive compilation, UI accessibility tests, `python3 ./scripts/verify_docs.py`, and the project's required pre-commit Sonar check.

## Acceptance criteria

- The companion identity foundation and workspace-scope gate are complete; an unclaimed deployment can mint exactly one first-owner API credential mapped to one generated stable user and workspace. Concurrent losers leave no orphan users/workspaces, and a lost response can be replaced for the same owner/workspace before confirmation without leaving two valid keys.
- Bootstrap status discloses no owner/workspace identity or authorization material; claim/recovery enforce loopback policy or the configured operator proof, and only authenticated first-draft creation can confirm the claim.
- The first-owner credential is durably issued and authenticated before draft creation; creating that first draft atomically confirms `CLAIMED`, and no provider secret is accepted before authenticated `USER:<userId>` or authorized `WORKSPACE:<workspaceId>` scope exists.
- A logged-in owner is offered a safe resume-or-restart decision for a non-terminal draft; restart and expiry clean staged state.
- Existing system-vault LLM keys automatically gain a behavior-preserving `SYSTEM` profile without interaction or secret copying; migration failure never blocks a minor-version startup, and scope transfer remains explicit.
- A user can configure two accounts for the same provider, use each in different routes, and no credential value is persisted or returned.
- One connection can back multiple model profiles; deleting it safely disables dependent routes and cleans its vault references.
- One workspace connection stores one secret copy and can be used—but not revealed or privately mutated—by authorized members.
- An existing role can use a sticky primary model plus explicit, ordered fallback profiles without changing its capabilities; profile routing does not round-robin successful requests.
- An unassigned profile is never selected by runtime routing.
- `ModelProfileResolver` and its tests implement the exact normative [PROFILE_ROUTE_PRECEDENCE](#profile_route_precedence) chain without collapsing or reordering tiers.
- `ROLE` assignments resolve only to canonical built-in or exactly registered dynamic role IDs. Group registration rejects duplicate IDs, duplicate members, and cross-group member overlap; eligible owner/workspace assignments intentionally shadow matching deployment routes and the lightweight shortcut.
- Model clients for different accounts cannot share credential-bearing runtime state.
- Every onboarding/MCP outbound client uses `OutboundHttpPolicyService`; a connection may use policy-validated per-connection proxy, no-proxy, timeout, redirect, and custom-CA settings without persisting proxy credentials, permitting rebinding/SSRF, or permitting production TLS-verification bypass.
- A user can resume a draft after restart; expiry/cancellation/denial deletes uncommitted secret references and candidate metadata.
- All network validation, catalog, OAuth, and MCP work remains HITL-gated and transaction-free.
- Local model-ID validation, HITL-gated connection verification, declared/catalog capability compatibility, and runtime health are distinct states. Billable probes are disclosed, and local-provider pull/install is explicit and cancellable.
- Quick start pre-seeds the same draft with one connection/profile/default route and explicit skipped optional stages; it does not use a separate commit path.
- Each completed stage persists its own operation intent and inactive commit receipt; crash recovery reconciles the same idempotency key, review checks model/API-tier eligibility, finalization activates staged records, and cancellation/failure compensates receipts in reverse order.
- An extension can declare typed, localized safe fields, validate its namespace from an immutable prepared snapshot, stage a compensatable commit, persist skip/configured state, and compensate a failed commit without central module-specific code or mutable bean state.
- A conforming vetted extension cannot access another extension's input, write arbitrary configuration/files, or bypass ownership/HITL checks through the supported API; architecture tests reject forbidden module dependencies, and arbitrary third-party bytecode is explicitly outside the trust model.
- MCP is a shared-lifecycle core stage, while a separate non-LLM integration proves the extension SPI.
- From Phase 1, legacy and new endpoints observe the same persisted draft; no Caffeine-backed parallel wizard state remains.
- `OnboardingSessionRegistry` is removed, `SettingsResource` cannot mutate legacy LLM/system-prompt vault keys, and the completion UI never expects a raw `serviceKey`.
- `amsha.llm.profile-routing.enabled` is a documented runtime property defaulting to `false`; routing/cache tests pass with both values before cutover.
- Returning owners/admins can add or edit one connection/profile/route/extension through a resumable purpose-specific draft without replaying first-run setup.
- A post-setup dashboard exposes safe health/remediation data, and versioned export/import round-trips non-secret topology only through an inactive reviewed draft.
- SQLite, PostgreSQL, and filesystem ports pass the same contract tests; native reflection coverage, focused UI/resource/model-router tests, English i18n fallback/missing-key rejection, and accessibility tests pass.

## Consequences

This design adds durable profile and draft lifecycle complexity, but it turns multi-agent and multi-provider setup into a clear, auditable model rather than a collection of global settings. It preserves a simple first run while allowing expert users to build explicit provider/model/agent topologies. Most importantly, credentials, model routing, role authority, and plugin lifecycle remain separate concerns with a single controlled integration point for each.

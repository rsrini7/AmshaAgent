# ADR: Finish O2 Provider Onboarding

- **Status**: Accepted
- **Date**: 2026-09-08
- **Decision Owners**: Amsha Architecture Committee
- **Related Invariants**: `docs/Architecture_Invariants.md`
- **Master ADR**: `Ideas-Pending/ADR-Onboarding-Multi-Agent-Provider-Model.md`
- **Feature Slug**: `o2-provider-onboarding`
- **Issue Tracker**: `.scratch/o2-provider-onboarding/`

---

## 1. Context and Problem Statement

`ADR-Onboarding-Multi-Agent-Provider-Model.md` establishes the multi-agent provider onboarding architecture. While O1 (durable draft lifecycle, wizard, bootstrap), O4 (MCP extension saga, masked headers, process-restart recovery), and O5 (health cockpit, non-secret draft export/import) are complete, O2 resolves four explicit deliverables:

1. **Eliminating Unmediated Outbound Clients**: Several LLM wrapper implementations (`KimchiResponsesChatModel`, `FoundryResponsesChatModel`) and built-in search tools (`BraveWebSearchTool`, `SerperWebSearchTool`, `SmartWebFetchTool`) previously instantiated direct JDK `HttpClient` instances instead of routing outbound traffic through `GovernedTransportService` and `GovernedHttpClient`. This is aligned with §22.25 ("Byte-Level Governed Egress").
2. **Live Provider Catalog & Model Discovery**: The onboarding catalog is backed by static definitions in `provider-catalog.json` through `StaticProviderCatalogAdapter`. Active connections need dynamic live model discovery via upstream `/models` endpoints mediated by `GovernedTransportService` under `OutboundPurpose.PROVIDER_MODEL_DISCOVERY` with strict SSRF checks, bounded timeouts, and authenticated header delivery (`X-Provider-API-Key`).
3. **OAuth Migration & Checkpoint-Backed Grants**: Provider credentials that use OAuth/subscription tokens (e.g. Codex CLI `~/.codex/auth.json`, Claude Code, Agy, Kimchi) require migration away from unmanaged filesystem or environment tokens to checkpoint-backed operation grants (`approvalToken` + typed purpose `PROVIDER_OAUTH_EXCHANGE`) that bind actor, session, exact target authority, and expiry.
4. **Richer `networkProfileJson` UI & Settings Flow**: `NetworkProfilePolicy` supports TLS trust modes (`SYSTEM`, `CUSTOM_CA`), bounded connect/read timeouts, proxy URLs with vault credential references, and no-proxy patterns. `SettingsResource` and the onboarding wizard provide structured DTOs, fail-closed validation, and accessible forms.

---

## 2. Decision and Target Architecture

### Deliverable 1: Remove Remaining Direct Outbound Clients
- All outbound HTTP calls in `llm-providers` (`KimchiResponsesChatModel`, `FoundryResponsesChatModel`) inject or receive `GovernedTransportService` and utilize `GovernedHttpClient` / `GovernedLangChainHttpClient`.
- Built-in search tools (`SmartWebFetchTool`, `BraveWebSearchTool`, `SerperWebSearchTool`) route outbound requests via `GovernedHttpClient` under typed `OutboundPurpose.WEB_SEARCH` and `OutboundPurpose.TOOL_FETCH`.
- Outbound responses are processed safely with bounded stream reading (`readNBytes`), immediate stream closing, and category-aware error mapping (`OutboundHttpException`).

### Deliverable 2: Live Provider Catalog & Model Discovery
- `LiveProviderCatalogService` probes provider `/models` endpoints dynamically when credentials or base URLs are configured.
- Egress validation executes under `OutboundPurpose.PROVIDER_MODEL_DISCOVERY` (non-HITL-gated to avoid interactive loops during typing, but strictly SSRF-bounded, IP-literal validated, and subject to 10s connect/read timeouts).
- Provider API keys are transmitted via the `X-Provider-API-Key` HTTP header (not in query strings) and require owner authentication (`requireOwner()`).
- Discovery results are cached in a bounded Caffeine cache (`maximumSize(500)`, 5-minute TTL) keyed by provider ID and sanitized base URL (excluding API key hashes to avoid cache flooding). When unreachable or unconfigured, the UI falls back gracefully to static catalog entries.

### Deliverable 3: OAuth Migration & Checkpoint Operation Grants
- Provider OAuth credential resolution uses checkpoint-backed operation grants via `POST /onboarding/oauth/grant`.
- Token exchange and authorization callbacks bind actor, session ID, purpose (`PROVIDER_OAUTH_EXCHANGE`), exact target authority (e.g., `chatgpt.com`, `api.anthropic.com`, `generativelanguage.googleapis.com`, `llm.kimchi.dev`), and expiry.
- PKCE generation (`generatePkceParams`) uses local `SecureRandom` instances and ensures secret verifier bytes are zeroed in `finally` blocks.
- Token resolvers verify operation grants before decrypting secrets and zero secret byte arrays immediately after use.

### Deliverable 4: Richer `networkProfileJson` UI and Settings Flow
- `SettingsResource` exposes structured DTOs (`NetworkProfilePolicyDto`, `NetworkProfileUpdateRequest`) for reading and updating connection network profiles with fail-closed validation.
- Direct committed connection updates perform mandatory audit-before-mutation (§7.13.4), validate URI schemes and SSRF bounds on proxy URLs, and enforce optimistic locking (returning HTTP 409 on revision conflicts).
- Lit components in `ui/src/components/` provide accessible form controls (`role="tablist"`, `role="tab"`, `<label for="...">`, `aria-describedby`, `aria-expanded`, `aria-controls`) and live announcer regions for model discovery.

---

## 3. Invariants and Compliance

- **Byte-Level Governed Egress**: (§22.25) All outbound HTTP requests must pass through `GovernedTransportService`.
- **Fail-Closed Security**: Missing, malformed, or untrusted network profiles or credentials fail closed with HTTP 400. Serialization errors in `NetworkProfilePolicyDto` throw `IllegalStateException` rather than falling open to unrestricted egress.
- **Mandatory Audit**: Outbound egress denials (`OUTBOUND_EGRESS_DENIED`) and network profile mutations are recorded via mandatory audit before state modification.
- **PMD Complexity Gate**: NCSS <= 80 statements, cognitive complexity <= 50, classes <= 500 lines.
- **Spotless & FQNs**: `python3 ./scripts/fix_fqns.py && ./mvnw spotless:apply -q`.
- **Docs Verification**: `python3 ./scripts/verify_docs.py`.
- **Git Hygiene**: NEVER stage or unstage files (`git add`, `git reset`, `git commit`). Staging decisions belong exclusively to the human user.

---

## 4. Follow-Up Hardening (Review Findings B5–B8 + Session Binding)

### B5: PKCE Is Production-Enforced, Not Just Generated

`generatePkceParams()` alone was insufficient. The flow is now stateful in
`ProviderOAuthPkceStore` (10-minute TTL, fail-closed capacity bound of 10,000 entries,
synchronized mutation, expired entries purged with their claims, native-registered in
`LlmProvidersReflectionConfig`):

- `beginAuthorization(providerId, actorUserId, sessionId)` stores the challenge
  bound to provider/actor/session and returns verifier/challenge/state. The verifier is
  never persisted; only the challenge is retained. A full store fails closed with
  `IllegalStateException` instead of evicting live authorizations.
- The OAuth callback validates state via `validateCallbackState()` without consuming it.
- `exchangeOrRefreshToken()` claims the state single-flight via `claimForExchange()`:
  concurrent duplicate exchanges for the same state are rejected (`already in progress`),
  and unknown/expired/actor-session-mismatched states are rejected with mandatory audit.
- For `authorization_code` flows the validated `code_verifier` is injected into the
  outbound token-request form body (the token endpoint must receive it); a
  caller-supplied conflicting `code_verifier` form parameter is rejected before any
  network I/O, as are missing verifier/state.
- The claim is consumed on successful exchange and released on transport, non-2xx response,
  parse, or exception failures so a retry with the same state remains possible before expiry.
- `refresh_token` exchanges are unaffected (no PKCE required).
- A real `sessionId` is required for both authorization and exchange.

### B6: Narrowed Socket-Pinning Claims + Real Connection Path

Web tools (`BraveWebSearchTool`, `SerperWebSearchTool`, `SmartWebFetchTool`) are
connection-agnostic by design and pass `connection = null`: they receive the default
policy (TLS, SSRF literal checks, pre-connect DNS revalidation, per-hop redirect
validation). Per-connection proxy/allow-list policy is enforced whenever a connection IS
supplied — `exchangeOrRefreshToken()` now accepts an optional `LlmConnectionDto` that is
passed through to the governed transport (verified by the `DENY_ALL` policy test).

True socket-level address pinning is enforced on the egress-proxy leg
(`validateTrustedProxy` pins the proxy socket address). Direct JDK egress validates DNS
immediately before connect but resolves via the system resolver; strict direct-socket
pinning remains deferred — sensitive targets MUST configure `requireProxy=true` on their
network profile. No ADR claim of direct-socket pinning is made.

### B7: HITL Boundary Decision

`ToolInvocationGuard` is the SOLE approval boundary for agent web tools: `NETWORK_OUTBOUND`
always pauses for approval before execution. `OutboundHttpPolicyService.requiresHitl()`
returns `false` for `WEB_SEARCH`/`TOOL_FETCH` deliberately to avoid a double prompt, and
all three web tools pass `preApproved=false` (verified by unit tests) so no tool claims
an approval it does not hold. Transport-level HITL remains enforced for
`CATALOG_PROBE`, `OAUTH_CALLBACK`, `PROVIDER_OAUTH_EXCHANGE`, `MCP_*`, and
`MEDIA_DOWNLOAD`.

### B8: Consume-Side Wiring (Service-Level Verified)

The service-level chain is verified end-to-end by `ProviderOAuthGrantFlowTest` with
production classes: a real single-use `ApprovalReceiptIssuer` grant is consumed by
`ProviderOAuthExchangeService` through the governed transport (mock HTTP only), the
credential lands in the vault, and `CodexTokenResolver` constructs the provider credential
from the vault. Grant reuse and actor-mismatched grants are rejected. A thin REST exchange
wrapper over this verified flow is deferred pending the PKCE callback UX.

Every consume-side entry additionally enforces session binding: the four provider token
resolvers (`Codex/Kimchi/ClaudeCode/AgyTokenResolver.resolveTokenWithGrant`) and
`ProviderOAuthExchangeService.storeExtractedTokenWithGrant` require a non-blank
`sessionId` and reject grants whose bound session does not exactly match the presented
one (`IllegalStateException: session mismatch`, `false` respectively). The presented
session must match the canonical receipt binding recorded in the consumed grant
(`oauth-exchange:<providerId>:<sessionId>`, exposed by
`ApprovalReceiptIssuer.oauthExchangeSessionId(...)`).

### Session Binding

`POST /onboarding/oauth/grant` requires an explicit non-blank `sessionId` (HTTP 400
otherwise). The previous silent fallback (`"onboarding-" + ownerId`) is removed: grants
bind actor, session, provider, exact target host, and TTL, and session derivation must be
explicit from the authenticated session context. Consume-side verification mirrors
issuance (see B8): no grant consumer accepts a missing session or a session that does
not exactly match the grant receipt.

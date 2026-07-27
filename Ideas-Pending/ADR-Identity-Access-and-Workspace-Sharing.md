# ADR: Identity, Web Sessions, Multi-Admin Access, and Workspace Sharing

- Status: Partially Implemented
- Date: 2026-07-14
- Decision owners: Amsha Agent maintainers
- Required by: `ADR-Onboarding-Multi-Agent-Provider-Model.md`

## Implementation checkpoint

Identity users/workspaces/memberships, API credentials, opaque web sessions, external-login providers, invitations, and an initial first-owner bootstrap are present. The bootstrap implementation remains provisional: the onboarding ADR requires replacing its pre-CAS identity writes and combined persist/register credential flow before the first-owner acceptance criteria are complete. Phase 5 also remains open: `SecretVault` has no typed `WORKSPACE` scope, web sessions have no distinct active identity-workspace binding, and connection grants are not complete. This ADR must not be reported as fully implemented until the bootstrap replacement, workspace contracts, and their three-backend/security tests pass.

## Context

At the time of this decision, Amsha authenticated API requests with configured or runtime-registered API keys. `ApiKeyRegistry` mapped an HMAC of a key to a label, `ApiKeyValidator` resolved that label, `ApiKeyAuthFilter` placed it in `SecurityContext`, and `ApiKeyOwnerResolver` exposed it through the core owner SPI. Durable human users, external identities, browser login sessions, invitations, teams, and workspace memberships were then absent; the checkpoint above records the current implementation state.

Profile-based onboarding needs a stable owner before storing credentials in `USER:<userId>`. Community deployments also need more than one administrator and a safe way to share a provider connection without copying its secret. Those requirements cannot be represented by a `shared` flag or by treating the configured `internal` key label as a permanent human account model.

## Decision

Adopt an **OIDC-first identity model with API-key operator fallback**.

- Human web login uses generic OpenID Connect Authorization Code + PKCE. The first release includes tested presets for common standards-compliant providers such as Google, GitLab, Keycloak, and Authentik. GitHub login uses a small OAuth 2.0 adapter because GitHub user authentication is not generic OIDC.
- Local passwords are not implemented in the first release. A durable API-key owner credential remains the self-contained bootstrap, recovery, automation, and headless-deployment path.
- Browser authentication uses opaque, server-side, revocable sessions—not self-contained JWTs.
- A stable generated `userId` is the principal name everywhere. Human-readable labels, email, OIDC subject, and the legacy `internal` label are attributes, never ownership keys.
- Every deployment has at least one `IdentityWorkspace`. Membership roles are `OWNER`, `ADMIN`, and `MEMBER`. The first claimed user becomes the first workspace `OWNER`.
- Multiple administrators are added through expiring invitations or an owner-issued API-key principal. No second unauthenticated first-owner claim is possible.
- Shareable resources use explicit scope and grants: `USER:<userId>` for private resources or `WORKSPACE:<workspaceId>` for team resources. A boolean `shared` field is forbidden.

This ADR is the gate that owns the first-owner bootstrap, identity ports, stable-principal mapping, session policy, and workspace authorization contracts. Status is now `Partially Implemented`: I1–I4 foundations (ports, stable principals, opaque sessions, external-login providers, invitations, and the provisional first-owner bootstrap) are present. The remaining work is the joint O1 bootstrap replacement with the onboarding ADR (reservation state machine, state-bound credential activation) and I5 (active identity-workspace context, `WORKSPACE` vault scope, connection grants). Only completion of those two items moves this ADR to `Implemented`.

## Goals

- Preserve current API-key automation and headless operation.
- Support OIDC web login, logout, revocation, and safe session refresh.
- Support several administrators without sharing one raw API key.
- Give every owner/vault operation a stable user or workspace scope.
- Allow one connection secret to be shared without copying it into each user's vault.
- Keep channel, REST, and onboarding ownership semantics consistent.

## Non-goals

- SAML, LDAP, SCIM, or enterprise directory synchronization in the first release.
- Local password storage in the first release.
- Anonymous multi-user access or public self-registration.
- Encoding authorization roles or secrets in browser-readable tokens.
- Treating an email address, provider username, API-key label, chat ID, or session ID as a durable user ID.

## Architecture

### 1. Durable identity model

Add `IdentityStorePort`, `ApiCredentialStorePort`, `WebSessionStorePort`, `IdentityInvitationStorePort`, and `BootstrapStateStorePort` in `persistence-api`, with SQLite, PostgreSQL, and filesystem parity:

```text
IdentityUser
  userId, displayName, status, createdAt, updatedAt

ExternalIdentity
  providerId, issuer, subject, userId, safe claims, timestamps
  unique(providerId, issuer, subject)

IdentityWorkspace
  workspaceId, displayName, createdBy, timestamps

WorkspaceMembership
  workspaceId, userId, role (OWNER | ADMIN | MEMBER), status, timestamps

ApiCredential
  credentialId, userId, keyDigest, role set, expiresAt, revokedAt, timestamps

WebSession
  sessionId, userId, tokenDigest, csrfDigest, idleExpiresAt, absoluteExpiresAt,
  rotatedAt, revokedAt, safe client metadata

IdentityInvitation
  invitationId, workspaceId, role, tokenDigest, optional email constraint,
  expiresAt, acceptedBy, revokedAt
```

Raw API keys, session tokens, CSRF tokens, authorization codes, refresh tokens, invitation tokens, and OIDC client secrets are never persisted. Only keyed digests or vault references are stored. OIDC refresh tokens, if a provider requires retention, use a generated user-scoped vault reference and are deleted on unlink/revocation.

The port APIs use optimistic revisions and atomic operations for first-owner claim, invitation acceptance, session rotation, API-key revocation, and the invariant that a workspace always retains at least one active `OWNER`.

### 2. First owner and legacy API-key compatibility

This identity subsystem defines `BootstrapStateStorePort` and the core-owned `BootstrapCredentialIssuer` SPI (in `core/security`, implemented by the app module); onboarding consumes them. The onboarding ADR refines their initial implementation with a `CLAIMING` reservation, stable owner/workspace recovery, and state-bound credential activation; its §0 is the normative source for the reservation state machine (`UNCLAIMED -> CLAIMING -> CREDENTIAL_ISSUED -> CLAIMED`), the `reserveFirstOwner`/`recordCredentialIssued`/`confirmClaim`/`rollbackClaiming`/`replaceCredential` transition names, and the two-phase `BootstrapCredentialIssuer` prepare/activate split. This ADR does not restate that state machine; implementers must read the onboarding ADR §0. Together they create a high-entropy API credential, a generated stable `userId`, the default workspace, and its first `OWNER` membership. The API credential maps to that `userId`; it is not stored under literal principal `internal`.

The bootstrap replacement also imposes a new coupling on this subsystem: `ApiKeyRegistry` must accept the bootstrap credential only when both the `ApiCredential` record and a matching `CREDENTIAL_ISSUED`/`CLAIMED` bootstrap digest exist in `BootstrapStateStorePort`. A generic durable-credential lookup cannot bypass bootstrap state, so an unactivated `CLAIMING` reservation never yields a usable key. This gate is defined in the onboarding ADR §0 and is part of the O1 acceptance criteria for this ADR.

During compatibility, configured `amsha.api.keys.*` entries remain valid. Their labels resolve through a migration mapping: the configured `internal` key maps to the claimed owner's stable `userId` and `OWNER` role; CI/service keys map to explicit service principals with bounded roles. `ApiKeyRegistry` becomes a cache over configured keys plus `ApiCredentialStorePort`, not an authoritative in-memory identity store.

The first-owner compare-and-set remains unique. After it succeeds, additional users require an authenticated owner/admin action; they cannot call bootstrap claim.

### 3. OIDC and OAuth login

Define an app-facing `ExternalIdentityProvider` SPI with a generic OIDC implementation and a GitHub OAuth 2.0 implementation. Provider configuration contains issuer/client metadata and a vault reference for the client secret. Redirect URIs are fixed by deployment configuration; callers cannot submit arbitrary redirect targets.

Freeze the shared SPI contract before either provider implementation begins. Illustrative methods are deliberately protocol-neutral:

```java
public interface ExternalIdentityProvider {
    ExternalIdentityDescriptor descriptor();
    AuthorizationStart beginLogin(ExternalLoginContext context);
    VerifiedExternalIdentity completeLogin(
            ExternalLoginContext context, ExternalCallback callback, PendingExternalLogin pending);
}
```

`AuthorizationStart` contains the fixed authorization redirect plus opaque pending-login ID; `PendingExternalLogin` stores only bounded state/nonce/PKCE verifier references and expiry; `VerifiedExternalIdentity` contains normalized issuer, subject, verified-claim flags, and safe display claims. Implementations are stateless CDI beans, cannot create users/sessions directly, and cannot weaken common issuer, redirect, state, nonce, PKCE, or account-linking checks.

Login uses Authorization Code + PKCE, cryptographically random state and nonce, exact redirect matching, issuer/audience/signature/nonce validation, and bounded temporary state. Account linking requires an authenticated session or a valid invitation; matching email alone never silently links identities unless the provider supplies a verified email and the invitation explicitly constrains it.

OIDC discovery/JWKS retrieval is bounded network I/O with cache expiry and safe failure. Authentication network calls are not agent tool calls, but they remain outside persistence transactions and follow the application's outbound URL, TLS, proxy, timeout, redaction, and audit policies.

### 4. Browser session model

After login, issue an opaque random session token in an `HttpOnly`, `Secure` (outside loopback development), `SameSite=Lax` cookie. Persist only its keyed digest. Default policy is 12 hours idle and 7 days absolute lifetime, configurable only within documented safe bounds. Rotate the token after authentication, privilege changes, and periodically during an active session; accept the prior digest for a short single-use overlap to avoid concurrent-request breakage.

Mutating browser requests require a separate CSRF token bound to the session and validated with origin/host policy. Logout, user disable, membership removal, credential compromise, or owner-requested "log out all devices" revokes sessions immediately. Session cleanup is durable and restart-safe.

API keys remain the non-browser mechanism and use the existing header path. The auth filter resolves either mechanism to one `AuthenticatedPrincipal(userId, workspaceIds, roles, authMethod)`, sets `SecurityContext.getUserPrincipal().getName()` to stable `userId`, and implements `isUserInRole()` from resolved roles. Core receives only the stable owner ID and authorized workspace scope.

Each web session stores an active `workspaceId`; switching it is an authenticated mutation that verifies membership and rotates the session token. API-key credentials have an explicit default workspace/grant set. REST/channel entry points bind the selected identity workspace alongside `WorkspaceContext` user/session state, and context-aware async wrappers propagate it. Do not infer identity workspace from chat/session IDs or overload the existing term "workspace session"; use a distinct typed identity-scope accessor.

### 5. Multi-admin and invitations

`OWNER` can invite `ADMIN` or `MEMBER`, revoke invitations, rotate workspace-owned credentials, and transfer ownership. `ADMIN` can manage integrations and members except ownership transfer/last-owner removal. `MEMBER` can use resources granted to the workspace but cannot reveal, rotate, or delete credentials.

Invitations are random, single-use, hashed at rest, expire by default after 24 hours, and can be email-constrained. Acceptance requires OIDC/OAuth login or an owner-created API-key principal. The last active owner cannot be removed, disabled, or demoted without atomically promoting another owner.

### 6. Workspace-scoped secrets and connection grants

Generalize vault addressing to a typed `SecretScope`:

```text
SYSTEM
USER:<userId>
WORKSPACE:<workspaceId>
```

Only a service that has already authorized the acting principal may resolve a workspace secret. `SecretVault` does not decide membership and never returns one owner's private secret to another user.

Connections use `scopeType` and `scopeId` rather than `shared`:

- private connection: `USER`, writable/usable by its owner;
- team connection: `WORKSPACE`, writable by workspace owner/admin, usable by active members;
- optional `ConnectionGrant` records may further restrict use to named members or roles.

Promotion from private to workspace scope writes a fresh workspace vault key, atomically switches non-secret metadata after validation, and deletes the old user key through retryable cleanup. Demotion performs the inverse and requires an explicit target owner. No record references another user's vault partition.

### 7. Authorization and module boundaries

Introduce a core/channel-safe principal and authorization SPI; implementations and HTTP/OIDC wiring remain in `amsha-app`. Channel modules continue to use channel APIs and never import app security classes. Onboarding depends on the SPI, not `ApiKeyRegistry` or app resources.

Mutating `/admin/**` endpoints require an authenticated `OWNER`/`ADMIN` role equivalent to the existing `isInternal()` rule. During migration, the configured internal key resolves to the first owner's role so behavior remains compatible. Reads retain their current public/private policy unless separately changed.

Every user/workspace lookup, session mutation, invitation acceptance, role change, API-key issue/revoke, identity link/unlink, and shared-secret promotion is audited with bounded redacted metadata. Raw tokens, claims containing personal data, key digests, vault references, cookies, and provider response bodies are excluded.

## API outline

```text
GET    /auth/providers
GET    /auth/login/{providerId}
GET    /auth/callback/{providerId}
POST   /auth/logout
POST   /auth/sessions/revoke-all
GET    /identity/me
GET    /identity/workspaces/{workspaceId}/members
POST   /identity/workspaces/{workspaceId}/invitations
POST   /identity/invitations/{token}/accept
POST   /identity/api-credentials
DELETE /identity/api-credentials/{credentialId}
```

Responses never contain raw credentials except the one-time API-key creation response. Browser sessions are cookie-based; APIs do not return session tokens in JSON.

## Delivery

1. Add identity, workspace, API-credential, session, and invitation ports with all three backends, migrations, reflection registration, and contract tests.
2. Migrate `ApiKeyRegistry`/validator/filter/owner resolver to stable principals while preserving configured internal/CI keys.
3. Implement opaque web sessions and the initial first-owner/default-workspace claim. The session work is present; the provisional claim is replaced jointly with onboarding O1 by the reservation/state-bound activation contract, including takeover/recovery tests.
4. Freeze the `ExternalIdentityProvider` contract, then add generic OIDC plus GitHub OAuth, account linking, invitations, and multi-admin role enforcement against the same provider contract tests.
5. **Open:** add the distinct active identity-workspace context, `WORKSPACE` vault scope, and connection grants; integrate profile onboarding and MCP ownership with the same authorization service. Only completion of this item moves this ADR to `Implemented`.

Each phase runs the project's required Java formatting, focused compile/tests, three-backend contracts, native-sensitive compile, documentation verification, and Sonar checks. Identity/security integration tests cover concurrent claims, fixation, replay, CSRF, issuer confusion, invitation races, last-owner enforcement, session revocation, and cross-workspace denial.

### Cross-ADR dependency

Onboarding Phase 1 (safe bootstrap and foundation) depends on the Identity Phases 1–3 ports, stable principals, authorization, and opaque sessions, and jointly completes the replacement first-owner claim contract. Onboarding Phases 2–3 (LLM profiles, routing cutover) depend on Identity Phase 5 (workspace vault scope). Identity Phase 4 (OIDC + invitations) can run in parallel with Onboarding Phases 1–2 and is not a blocking dependency.

## Acceptance criteria

- A fresh deployment can claim exactly one first owner and recover from a lost credential-delivery response. Concurrent claims are serialized by the `BootstrapStateStorePort` compare-and-set: a winning reservation creates exactly one stable user, one default workspace, and one `OWNER` membership, while losing claimants create no orphan users, workspaces, or credentials. A stale `CLAIMING` reservation is safely rolled back to `UNCLAIMED` with no durable identity rows left behind.
- A lost credential-delivery response can be replaced for the same persisted owner and workspace before confirmation, without ever leaving two valid keys: the old digest is revoked and the new one is activated only against the reserved owner/revision. After the issued key authenticates and the first draft confirms `CLAIMED`, all bootstrap claim/recovery endpoints return unavailable.
- `ApiKeyRegistry` accepts a bootstrap credential only when both the `ApiCredential` record and a matching `CREDENTIAL_ISSUED`/`CLAIMED` bootstrap digest exist; an unactivated `CLAIMING` reservation never yields a usable key.
- The configured internal key retains access during migration but resolves to a stable user ID and owner role.
- A user can log in through generic OIDC; GitHub login works through its explicit OAuth adapter.
- Browser sessions are opaque, expiring, rotating, CSRF-protected, revocable, and restart-safe.
- An owner can add a second administrator without sharing credentials, and the last owner cannot be removed.
- API keys remain supported for automation and can be individually expired/revoked.
- Private secrets cannot cross users; workspace secrets require active membership and an authorized connection grant.
- A workspace connection stores one secret copy and can be used—but not revealed—by authorized members.
- SQLite, PostgreSQL, and filesystem identity behavior passes one port-level contract; native-image registration and auth integration tests pass.

## Consequences

Identity becomes a real subsystem rather than an onboarding cookie convention. That increases scope, but it gives onboarding, vault ownership, team sharing, admin authorization, and browser security one coherent foundation. Choosing OIDC-first avoids password-storage liability while retaining API keys for self-contained and headless deployments.

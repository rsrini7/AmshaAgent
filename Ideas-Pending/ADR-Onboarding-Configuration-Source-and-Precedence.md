# ADR: Onboarding Configuration Sources and Precedence

- Status: Accepted
- Date: 2026-08-08
- Decision owners: Amsha Agent maintainers
- Related: [Profile-Based Onboarding](ADR-Onboarding-Multi-Agent-Provider-Model.md), [Configuration](../docs/CONFIGURATION.md), [Architecture Invariants](../docs/Architecture_Invariants.md)

## Context

Amsha has two configuration entry points that are both exposed during first-run:

1. The native/JVM CLI bootstrap wizard, which prepares deployment configuration.
2. The authenticated web onboarding wizard, which prepares an owner-scoped draft and
   stages credentials for optional runtime activation.

Both can be used from a distribution directory containing a native binary, `.env`,
and runtime data. This can make it appear that the web wizard edits `.env` or that
the web wizard immediately replaces environment-backed LLM settings. Neither is
true.

The distinction is important for secret handling, immutable deployments, containers,
and operators who intentionally keep environment configuration authoritative.

## Decision

Amsha keeps configuration in explicit layers and never makes the browser edit `.env`:

```text
dist/.env                  deployment/bootstrap input
        │
        ├── environment-backed runtime configuration
        │
web draft ──> owner-scoped encrypted vault ──commit + priority──> system vault
             (staged secrets)                              (effective LLM override)
                                                               │
                                                               └─ runtime router
```

### 1. Deployment and CLI layer

`.env` and process environment variables are deployment configuration. They contain
security bootstrap values, provider defaults, provider credentials, feature flags,
and operational settings. The CLI first-run wizard may create or patch the binary
directory `.env` for missing values before Quarkus starts. Runtime web code must not
write this file.

For a native distribution this normally means:

```text
dist/amsha-sqlite  binary and embedded UI assets
dist/.env           deployment configuration
dist/data/          database, encrypted vault, drafts, owner/bootstrap state
```

Editing `.env` requires a process restart. The CLI bootstrap wizard is not the full
web onboarding workflow; it primarily asks for missing environment and security
values before `serve`, `chat`, or `ask` starts.

### 2. Web onboarding draft layer

The web wizard writes durable onboarding drafts through REST APIs. Drafts are stored
by the configured persistence backend and contain non-secret selections such as:

- provider and model selection;
- base/target URL choices;
- routing and profile selections;
- MCP, integration, memory, security, channel, and identity options.

Provider credentials and MCP authentication headers are scrubbed before persistence.
During draft processing, they are stored in the encrypted owner-scoped vault and
represented in the draft by opaque vault references. Raw credentials must never be
returned when a draft is reloaded.

LLM activation has a second, explicit lifecycle step. When vault priority is enabled
and the draft is committed, `OnboardingCommitSaga` resolves the owner-scoped LLM
credential and copies the selected provider/model/base URL/API key into system-vault
keys. `ChatModelProducer` reads those system-vault keys as the effective runtime LLM
configuration. Other owner-scoped secrets, such as MCP credentials, remain governed
by their owning integration and are not implied to become system-vault values.

### 3. Credential presentation

The UI displays an API-key field only when the selected provider catalog descriptor
declares `requiresApiKey=true`. Keyless providers such as Ollama and OMLX therefore
do not display a credential field. Environment credentials are intentionally not
returned to the browser and are not displayed as plaintext.

When a provider requires a key, the UI-entered key is scrubbed into the vault. The
UI must not attempt to discover, copy, or overwrite the corresponding `.env` value.

### 4. Runtime precedence

`AMSHA_LLM_VAULT_PRIORITY` is a deployment policy, loaded at process startup:

- `false`: environment-backed LLM configuration remains authoritative. A web
  onboarding commit may retain its owner-vault credential and draft state, but its
  provider/model/base URL/API key does not replace the active environment routing.
- `true`: committed system-vault LLM provider/model/base URL/API key values take
  precedence over environment-backed values. The flag must be enabled before the
  relevant web draft is committed; changing it later requires recommitting or
  updating the runtime configuration.

The effective runtime provider resolves the vault layer only when vault priority is
enabled; otherwise it resolves the environment/configuration layer. A successful
draft commit therefore does not by itself prove that the selected LLM is active.

When vault priority is enabled, changing or rotating an environment credential does
not replace an existing system-vault credential. Operators must explicitly update or
remove the corresponding vault value through the supported settings/onboarding path,
then restart if required by the provider/configuration lifecycle. Runtime status and
documentation must make this precedence visible so an old vault credential cannot be
mistaken for the newly rotated environment credential.

### 5. Skip and pre-authentication behavior

The web onboarding status endpoint may report onboarding complete without an owner
session when `AMSHA_UI_SKIP_ONBOARDING=true` or a supported environment-backed/keyless
LLM configuration is already present. This is a startup/deployment decision and is
separate from whether a pending web draft has been committed.

For this pre-authenticated skip path, the backend also sets an `HttpOnly`
`AMSHA_API_KEY` cookie containing the configured internal API key so the UI can enter
the chat surface. This is not a newly claimed owner session and must retain the
existing cookie, loopback, CORS, and no-store protections. A normal `CLAIMED`
deployment without a valid owner credential must continue to return `401` rather
than receiving this skip cookie.

## Consequences

### Positive

- Browsers cannot rewrite deployment files or environment secrets.
- Container and native deployments retain immutable, operator-controlled `.env`
  configuration.
- UI credentials are encrypted and owner-scoped while staged rather than stored in
  draft JSON; an active LLM override is explicitly promoted to system-vault keys on
  commit when vault priority is enabled.
- Operators can choose environment authority or vault authority explicitly.
- CLI setup remains usable for headless deployment and pure CLI operation.

### Costs

- A distribution directory contains both deployment input and runtime state, which
  requires clear documentation.
- A UI user can commit a valid draft while environment configuration remains active
  when vault priority is disabled.
- Environment credential presence cannot be shown as a secret value in the UI.
- The CLI bootstrap wizard and web wizard are not feature-equivalent.

## Current status contract and implemented provenance

The current `OnboardingStatusDto.credentialsOk` field still means workflow completion
for the authenticated draft, or the pre-authenticated skip/complete response; it must
not be interpreted as proof that the active LLM came from the vault or environment.
The status DTO now separately exposes non-secret effective provider/model/base URL,
`effectiveSource`, `credentialState`, and `credentialSource`. Settings exposes the same
metadata plus `vaultPriority`; these fields never contain credential material.

The architecture remains unchanged, and the UI exposes non-secret provenance:

- effective source: `ENVIRONMENT`, `VAULT`, or `DEFAULT`;
- vault-priority state;
- credential state: `NOT_REQUIRED`, `CONFIGURED`, or `MISSING`;
- credential source: `ENVIRONMENT`, `VAULT`, or `NONE`;
- a review warning when the selected UI LLM will not become active because vault
  priority is disabled.

The UI may offer `Use environment credential` versus `Enter vault credential`, but
must never expose or copy the environment secret. `Use environment credential` is a
future explicit reference type, not an implicit fallback: it must declare its source
and activation policy, and must not silently override the vault-priority decision. A future `amsha onboard` CLI may
apply the same draft/scrub/commit domain flow for headless parity; until then, the
existing CLI wizard must be documented as environment bootstrap rather than full
web onboarding.

## Invariants

1. Web onboarding never writes `.env`, `application.yaml`, or process environment.
2. Raw provider/MCP credentials never persist in onboarding drafts or logs.
3. `AMSHA_LLM_VAULT_PRIORITY` is the explicit precedence switch; no hidden fallback
   may silently change the active source.
4. A provider requiring a credential cannot be committed without a valid vault
   credential in the current web flow. Any future environment-credential reference
   must be explicit, non-secret, and governed by a documented activation policy; it
   must never be an implicit fallback.
5. Runtime status distinguishes committed draft state from effective active LLM
   configuration. `credentialsOk` remains workflow completion only; separate
   effective provider/model/base URL and provenance fields satisfy the non-secret
   source-reporting portion of this invariant.

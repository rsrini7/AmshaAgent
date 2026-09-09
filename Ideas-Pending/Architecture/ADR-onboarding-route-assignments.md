# ADR: Redesign Onboarding Around Connections, Model Profiles, and Route Assignments

- **Status**: Accepted (2026-09-09; kicking off Iteration 0)
- **Date**: 2026-09-09
- **Decision owners**: Amsha maintainers
- **Supersedes / Extends**: `Ideas-Pending/ADR-Onboarding-Multi-Agent-Provider-Model.md` (extends O2/O3 multi-agent configuration and supersedes the initial conflated connection/model profile UI draft)
- **Related**: `Ideas-Pending/ADR-Onboarding-Multi-Agent-Provider-Model.md`, `Ideas-Pending/Architecture/ADR-Onboarding-Configuration-Source-and-Precedence.md`, `Ideas-Pending/Architecture/ADR-LangChain4j-Capability-Adoption-and-Amsha-Boundaries.md` (P7 turn-aware modality routing), `docs/Architecture_Invariants.md`
- **Feature slug**: `onboarding-route-assignments`
- **Issue tracker**: `.scratch/onboarding-route-assignments/`

## 1. Context

Amsha now has the core backend runtime seams needed to select a model per turn:
`TurnRequirements`, `ModelRoute`, `ModelRouteSelector`, explicit `MultimodalCapability`, lazy model
creation, and same-modality fallback (with LangChain4j P7 Slabs A–D landed, while Slab E remains
open pending live OMLX matrix evidence). Onboarding does not yet expose that model cleanly.

While O2 in `ADR-Onboarding-Multi-Agent-Provider-Model.md` delivered backend connection network
profile validation (`NetworkProfilePolicyDto`) and settings form controls, the initial UI draft put
one `modelId` and a small capability list directly inside each connection entry. This conflates three
different concepts:

- a connection: provider adapter, endpoint profile, custom endpoint, network policy,
  and vault-backed secret references;
- a model profile: one provider model and its declared capabilities, limits, and
  structured-output mode;
- a route assignment: the ordered profile candidates available to a scope and
  target.

The persisted model already separates these concepts into distinct domain entities and DTOs.
`LlmConnection` / `LlmConnectionDto` contains connection metadata such as `providerAdapterId`,
`endpointProfileId`, `customBaseUrl`, `authScheme`, `networkProfileJson`, and lifecycle state.
Secrets are stored through `LlmConnectionSecretRef` / `LlmConnectionSecretRefDto`.
`ModelProfile` / `ModelProfileDto` references a connection and contains `modelId`,
`structuredOutputMode`, `capabilityJson`, `safeLimitsJson`, `enabled`, and revision metadata.
`ModelRouteAssignment` / `ModelRouteAssignmentDto` contains the ordered `profileId` chain.
These records and `LlmProfileStorePort` already have SQLite/PostgreSQL/filesystem implementations.

`AMSHA_OMLX_MODEL` remains a deployment-level comma-separated same-modality
failover list. It is not a durable modality-routing configuration. A mixed list can
collapse to `TEXT_ONLY` through composite capability intersection and must not be
used to select image, audio, or future modality routes.

## 2. Decision

Redesign onboarding and post-onboarding configuration around three distinct concepts:
Connections, Model Profiles, and Route Assignments. Preserve the existing persisted
domain model in the first implementation and make all configuration entry points use
one application-level validation and activation path.

### 2.1 Domain and cardinality

- Preserve the existing `LlmConnectionDto`, `ModelProfileDto`,
  `ModelRouteAssignmentDto`, and `LlmProfileStorePort` contracts. No destructive
  migration is permitted. Any additive persistence field requires matching SQLite
  and PostgreSQL migrations plus filesystem parity.
- Support `one LlmConnection -> many ModelProfile` rows through `connectionId`.
  Multiple profiles may share one endpoint and credential reference while retaining
  distinct model IDs and capabilities.
- Keep credential resolution and zeroing in the existing governed path. Model
  creation remains lazy and must not occur inside a transaction.
- Treat `ModelRouteAssignmentDto.orderedProfileIds` as the fallback order. Do not
  invent or persist a `fallbackOrder` field on `ModelProfileDto`.

### 2.2 Model-profile declaration

The configuration seam validates each profile server-side before activation. A profile
declares:

- a non-blank `modelId`;
- `structuredOutputMode`, persisted as the existing string field but validated
  against the supported `StructuredOutputMode` values;
- capability JSON resolved into typed `MultimodalCapability`, including
  `AttachmentType` values `IMAGE`, `AUDIO`, and `DOCUMENT_PDF`;
- normalized MIME types compatible with the declared attachment types;
- safe media limits represented by the existing `safeLimitsJson` contract;
- `enabled` state.

`VIDEO` remains out of scope until bounded extraction, SSRF protection, resource
budgets, provider mapping, and native verification exist. Unknown or malformed
capability declarations fail closed and never grant multimodal admission.

### 2.3 Route assignments and selection

Assignments remain scope- and target-aware through the existing resolver precedence:
owner role/group/default, workspace role/group/default, then system default. Each
assignment supplies an ordered candidate pool. For a turn, the selector filters that
pool by required modality, MIME type, size, media count, readiness, and structured
output needs. The remaining candidates form a same-modality fallback chain.

The selected route is an immutable per-request snapshot. A profile or assignment
change applies to the next turn; it must not change an in-flight request. A model
profile revision or configuration epoch must prevent stale lazy model reuse.
No-restart updates apply when vault-backed routing is prioritized via
`AMSHA_LLM_VAULT_PRIORITY=true` (default `false`), governed by
`ADR-Onboarding-Configuration-Source-and-Precedence.md`; otherwise startup environment
routing remains authoritative.

### 2.4 One deep configuration module

Create one application-level configuration module with a small interface for:

- validating and normalizing a connection/profile/assignment draft;
- previewing the effective route matrix;
- atomically activating a validated configuration with revision checks;
- exporting and importing credential-free configuration.

The implementation owns cross-record validation, capability parsing, route-reference
checks, lifecycle transitions, audit-before-activation, and cache invalidation.
Onboarding UI, authenticated REST, and import/export are adapters to this seam;
they must not duplicate validation or routing logic.

### 2.5 Onboarding flow

Keep Quick Start as a one-model path for first-run simplicity. Advanced setup and
post-onboarding settings use this sequence:

1. Connections: endpoint/provider and vault-backed credential setup.
2. Model Profiles: repeatable profiles; several profiles may select the same
   connection.
3. Route Assignments: ordered candidates per scope and target.
4. Route Preview: deterministic text/image/audio mappings and safe unsupported
   outcomes.
5. Review and atomic activation.

The UI displays the preview returned by the server. It does not implement its own
capability filtering or fallback algorithm.

### 2.6 Configuration entry points

- **UI** is the primary human onboarding and settings experience.
- **Authenticated REST** supports headless automation and operational tooling. Use
  the existing `/onboarding` resource conventions for owner-scoped operations and
  existing internal authorization for system/workspace mutations.
- **Versioned import/export** supports GitOps and backup. It carries non-secret
  connection metadata, profiles, capabilities, limits, enabled state, and route
  order. It never carries API keys, secret material, or credentials.
- **Environment variables** remain a compatibility/bootstrap path. They are
  startup configuration and cannot be the canonical no-restart modality-routing
  mechanism. Dynamic no-restart profile and assignment routing requires
  `AMSHA_LLM_VAULT_PRIORITY=true`.
- **Chat/CLI profile selection**, if added, is an explicit owner-approved profile
  override for testing only; it still passes capability, scope, and security checks.

### 2.7 OMLX discipline

`AMSHA_OMLX_MODEL` continues to mean ordered same-modality failover for the legacy
provider path. Modality routing uses persisted model profiles and route assignments.
Profiles sharing one OMLX endpoint are the supported way to configure text, image,
and audio models. A live OMLX matrix must verify that the endpoint can serve the
declared model IDs and modalities; a capability declaration alone is not proof that
the local server accepts the payload.

### 2.8 Safety and module invariants

- `NETWORK_OUTBOUND` and media download remain governed by the existing HITL and
  SSRF controls.
- No LLM call, network I/O, or model creation occurs inside a transaction.
- New CDI, JSON, persistence, and native-sensitive types are registered in the
  owning reflection configuration; no type-level reflection shortcut.
- Channel modules do not depend directly on `amsha-core` or LangChain4j execution
  types.
- `AMSHA_PERSISTENCE_BACKEND` selection and SQLite/PostgreSQL/filesystem parity are
  preserved.
- New configuration properties, if any, must be documented in `application.yaml`,
  `.env.example`, and the relevant module README.
- Agents do not stage or commit changes.

## 3. Alternatives Considered

- **Conflated single-`modelId`-per-connection UI**: Rejected. Coupling a single model ID to a
  connection entry prevents one endpoint/credential from serving multiple models of distinct
  modalities (e.g. text reasoning alongside vision or speech), requires duplicate connection
  records for the same provider account, and conflates network transport rules with model-level
  capabilities.
- **Environment-variable-based modality routing**: Rejected. Environment variables provide
  static startup configuration but cannot support no-restart updates, lack structured capability
  schemas and safe limit bounding, cannot be validated through a shared application seam, and
  cannot represent owner/workspace tenancy boundaries.
- **Client-side route evaluation/filtering in the Lit UI**: Rejected. Implementing modality
  filtering and candidate selection in frontend JavaScript would duplicate backend routing
  logic, create behavioral divergence, and risk leaking unassigned or unauthorized candidate
  metadata to client state.

## 4. Consequences

Positive consequences are reusable connections, repeatable model profiles, explicit
same-modality failover, deterministic route preview, no-restart updates (when
`AMSHA_LLM_VAULT_PRIORITY=true`), and one validation path shared by UI, REST, and
import/export.

Accepted costs are a richer onboarding flow, connection/profile reference handling
during import, and a readiness/preview step before activation. True parallel fan-out
of multiple models for one turn is not part of this decision. It requires a separate
execution-plan decision covering cost budgets, cancellation, aggregation, and
side-effect isolation. A future preprocessor pipeline (for example transcription
followed by a text reasoning model) is also a separate extension.

## 5. Acceptance Criteria

- **Cardinality and Persistence**: `LlmProfileStorePort` supports one connection to many
  model profiles across SQLite, PostgreSQL, and filesystem backends without data loss or
  schema bifurcation.
- **Server-Side Profile Validation**: Profiles enforce non-blank `modelId`, valid
  `StructuredOutputMode`, typed `MultimodalCapability`, and normalized MIME limits; unconfirmed
  capabilities (including `VIDEO`) fail closed.
- **Deterministic Route Selection**: Runtime selection follows `TurnRequirements` against
  ordered assignments, filtering by modality and readiness with same-modality failover and
  zero cross-modality degradation.
- **Route Preview Agreement**: Server-generated route preview matches effective runtime model
  resolution for all supported modalities (text, image, audio) and emits safe unassigned outcomes.
- **Atomic Activation and Epoch Invalidation**: Configuration activation is atomic under revision
  checks; activating a new epoch invalidates cached lazy model instances without mutating in-flight
  turns.
- **No-Restart Dynamic Updates**: When `AMSHA_LLM_VAULT_PRIORITY=true`, route updates take
  effect on subsequent turns without server restarts; when false, environment variables remain
  authoritative.
- **Credential-Free Import/Export**: Import/export round-trips non-secret connection metadata,
  profiles, and route order without persisting or exposing secrets.
- **OMLX Modality Verification**: Modality claims for local OMLX models are supported by live
  matrix evidence verifying endpoint capability.
- **Verification Gates**: Unit and integration test suites pass with three-backend persistence
  parity, PMD bounds, Spotless formatting, and GraalVM reflection configuration.
- **Scope Discipline**: Stopping at a green staged slice is valid; `VIDEO` and true multi-model
  fan-out must not be pulled into this feature for convenience.

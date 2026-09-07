# ADR: Amsha Visual Cockpit — Observable & Governed Execution

- **Status**: Proposed
- **Date**: 2026-09-01
- **Decision Owners**: Amsha Architecture Committee
- **Related Invariants**: `docs/Architecture_Invariants.md` §1.4, §1.8, §4.12, §5.9, §5.10, §5.24, §6.7, §7.7; `AGENTS.md` §8
- **Feature Slug**: `amsha-visual-cockpit`
- **Issue Tracker**: `.scratch/amsha-visual-cockpit/`

---

## 1. Context and Problem Statement

Amsha streams coarse, transient status today via `AgentSseService` (`SseEvent`:
`HitlEvent`, `AgentStatusEvent`, `HeartbeatEvent`). There is **no per-step execution trace** —
live events are dropped when no subscriber is connected, and the reconnect path only replays
pending approvals. Operators cannot reliably follow what actually happened during a turn, and
the UI cannot render a governed flow visual. Existing Amsha UI and runtime constraints (existing
Lit/TypeScript UI, the `SseEvent` union, the mandatory tool/guard/HITL invariants, and the
GraalVM native reflection model) bound the design.

## 2. Decision

Build a **versioned execution-trace layer** on top of the existing SSE transport and render it
in the existing Lit UI. Concretely:

1. **Extend, do not replace, `SseEvent`.** Add a versioned `AgentTraceEvent` record (plus a
   nested `Metrics` type) that serializes as a discriminated payload on the existing SSE channel,
   keeping backward compatibility with current consumers.
2. **Dedicated `TracePublisher` service.** A new, transaction-free service broadcasts trace events
   through `AgentSseService`. It is separate from `ObservabilityManager`, which retains its
   OpenTelemetry-context and observer-callback responsibilities.
3. **Server-side redaction + bounded live stream.** A `TraceRedactor` (core) strips raw
   prompts/completions, secrets, tool parameters, and audit keys, and caps payload size, *before*
   publication. The initial trace is a **bounded live stream** (per-run, in-memory bounded);
   durable replay/scrubbing was implemented in Issue 08 across SQLite, PostgreSQL, and filesystem stores.
4. **Reuse the existing Lit shell.** Render in the existing `arc-*` components
   (`arc-shell`, `arc-chat-interface`, `arc-hitl-panel`) extended with new cockpit components;
   keep visualization modules out of channel modules.
5. **Calm-by-default rendering.** Default to calm sequential SVG/CSS highlighting; any richer
   animation is an opt-in toggle added only after a bundle/license/perf review.
6. **Protect trace data by default.** Server-side redaction, per-session ownership checks, and
   an operator-gated safe-metadata model for any X-Ray disclosure. Raw Java source, tool
   parameters, audit keys, and model text are never published by default.
7. **Durable trace storage, retention, replay, and scrubbing (Issue 08).** Persist already-redacted
   trace events durably using a dedicated store port with semantic parity across SQLite, PostgreSQL,
   and filesystem backends. Enforce server-side session ownership and operator authorization for
   replay reads, keeping durable replay separate from the transient pending-approval SSE replay
   path. Provide bounded sequence/time scrubbing in the cockpit UI without client-side step synthesis.
8. **Evaluated and rejected third-party animation libraries (Issue 09).** Evaluated optional GSAP
   motion enhancement under strict evidence gates. The evaluation concluded with **Rejected**:
   no non-fabricating target for MotionPath exists (trace events are discrete lifecycle stages,
   not continuous spatial travel, and synthesizing particles or intermediate motion violates
   no-fabrication), cross-Shadow-DOM morphing violates encapsulation, and compositor-eligible
   transform and opacity transitions are already handled natively by existing CSS. Calm SVG/CSS
   is confirmed as the permanent visual representation, avoiding third-party licensing risks,
   memory lifecycle leaks, and bundle bloat.
9. **Optional local offline Whisper transcription (Issue 10).** Evaluated in-process local Whisper
   transcription under strict evidence gates (local model provisioning, zero network egress,
   reproducible STFT/mel feature extraction, encoder-decoder session leasing, and no transaction leakage).
   Following successful feasibility spike evidence and human operator authorization on 2026-09-05,
   the implementation concluded with **Approved & Implemented**: pinned `whisper-tiny-en-v1` bundle
   with SHA-256 verified manifests (including decoder provenance and config digests), pure Java STFT/Mel DSP,
   in-process ONNX seq2seq runner behind `OnnxSessionLeaseManager`, and `CompositeAudioTranscriptionPort`
   providing priority routing (local first, OMLX fallback) with per-delegate exception isolation and cascading.
   The feature remains disabled by default (`amsha.stt.local.enabled=false`) with zero runtime downloads and zero egress.
10. **Plan-Execute and Reflexion execution paradigms audited (Issue 11).** Audited the Amsha backend runtime and confirmed that only `REACT` and `MULTI_AGENT` / `GROUP_CONSULTATION` execution paradigms are implemented. No Plan-Execute or Reflexion runtime loops exist in the Java backend. Per the non-fabrication invariant, unconfirmed paradigms are strictly rejected and `arc-topology-graph.ts` permanently falls back to the governed ten-station conveyor view.
11. **Safe allowlisted symbolication enforced over raw-source X-Ray (Issue 12).** Formally rejected streaming raw Java source files, arbitrary method parameters, prompts, completions, and secret keys due to severe information-disclosure and multi-tenant isolation risks. Implemented safe allowlisted symbolication only via `TraceSymbolicationService` (strictly 9 architectural classes), resource-layer `isInternal()` authorization in `CockpitResource`, multi-tenant session ownership with 404 non-disclosure, mandatory operator audit logging, and server-side trace redaction via `TraceRedactor`.
12. **Read-Only DB Console and Model Arena implemented (Issue 13).** Delivered internal-only operator surfaces:
    - **Read-Only DB Console** (`POST /api/v1/admin/db/query`): AST-level read-only SQL validation (single-statement enforcement, recursive DML/DDL rejection under EXPLAIN/CTE, exact PRAGMA allowlist), schema-metadata allowlist and wildcard ban enforced across SQLite (`sqlite_master`, `sqlite_schema`) and PostgreSQL (`information_schema.tables`, `flyway_schema_history`), two-tier programmatic tenant isolation predicates, SQLite `PRAGMA query_only = ON;`, PostgreSQL read-only transaction boundaries with guaranteed rollback, poisoned connection discard on reset failure, and mandatory audit logging.
    - **Model Arena** (`POST /api/v1/admin/arena/compare`): Dedicated fail-closed `CredentialDetector` contract (400 Bad Request), PII policy enforcement, strict token bounding (<=2,048 input tokens, <=1,024 output tokens), provider endpoint resolution, per-provider pre-invocation egress governance via `GovernedTransportService.validateEgress()` with mandatory audit gating (zero agent tool / shell execution; chat completions delegate to resolved `ChatModel` instances, with byte-level `GovernedTransportService.send()` unification across third-party SDKs tracked under provider transport roadmap §O2), and mandatory categorical audit logging (zero prompt or completion disclosure).
    - **Operator UI Baseline**: Client security preserves session-scoped CSRF resolution (`sessionStorage` fallback alongside document cookies, strictly excluding persistent `localStorage`) and explicit 403 differentiation (distinguishing role-based authorization from transient/CSRF failures without broad substring leakage).
13. **Advanced surfaces registry fully resolved and reconciled (Issue 07).** Every P3 advanced surface listed in Issue 07 has cleared its evidence gate: durable trace storage (Issue 08, implemented), third-party GSAP motion (Issue 09, evaluated & rejected in favor of calm SVG/CSS), local Whisper (Issue 10, implemented), additional paradigm loops (Issue 11, audited; non-fabricating conveyor fallback retained), raw-source X-Ray (Issue 12, rejected; safe symbolication retained), and DB console/Model Arena (Issue 13, DB Console evidence-verified; Model Arena core verified, with byte-level `GovernedTransportService.send()` provider execution tracked under §O2).

## 3. Alternatives Rejected

- **UI-only fake animation** (pulsing without real events): rejected — visualization must only
  ever reflect observed events; animation is never evidence of a backend step.
- **Replacing SSE wholesale**: rejected — it breaks existing consumers and the pending-approval
  replay; extending `SseEvent` preserves compatibility.
- **Raw source / prompt / parameter exposure in X-Ray**: rejected — it leaks sensitive data and
  violates ownership/authz invariants.
- **Copying third-party visualization code into the repository**: rejected — visualization
  patterns are reimplemented natively within Amsha's own modules.
- **Third-party animation libraries (GSAP / MotionPath / Flip)**: rejected (Issue 09) — no safe,
  non-fabricating target exists for MotionPath in discrete agent stage transitions; compositor-eligible
  transform and opacity transitions are already handled natively by CSS without bundle size,
  licensing, or lifecycle risks.
- **Equating external OMLX STT with local in-process Whisper**: rejected — outbound HTTP audio
  transcription requires an external provider service; in-process local Whisper requires pinned local ONNX
  weights, STFT/mel feature extraction, and zero-egress verification.
- **Fabricating Plan-Execute or Reflexion topology loops**: rejected (Issue 11) — visualization must only
  reflect observed backend runtime execution; intermediate steps or loops not present in the runtime are
  never synthesized.
- **Raw source code streaming and arbitrary parameter inspection**: rejected (Issue 12) — raw Java source
  traversal exposes class logic and infrastructure details; safe allowlisted symbolication satisfies operator
  diagnostic needs while strictly preserving tenant boundaries.
- **Unbounded, ungoverned DB queries or model benchmarks**: rejected (Issue 13) — arbitrary SQL mutations,
  PRAGMA manipulation, un-governed provider egress, or prompt/completion logging in audit trails violate core
  security invariants.

## 4. Consequences

- Positive: observable, governed execution without leaking sensitive data; preserves transaction,
  reflection, module-boundary, and tool-guard invariants; calm default protects non-technical
  operators.
- Cost: the P0 foundation (contract + publisher + redaction + security/ownership) is substantial
  before any UI value is visible.
- Risk (mitigated): trace hooks added inside a `@Transactional` would pin an Agroal connection
  during LLM calls (§5.9). Mitigated by keeping `TracePublisher` and all trace hooks in the
  transaction-free zone and asserting it in tests.
- Risk (mitigated): X-Ray info-disclosure in multi-tenant deployments. Mitigated by a
  safe-metadata model, an operator/`isInternal()` gate enforced at the resource layer, and default
  non-disclosure.

## 5. Invariants and Verification

- **Transaction-Free Zone (§5.9)**: `TracePublisher` and every trace hook are non-transactional;
  `CheckpointApprovalService.approve/deny` keep `@Transactional(NOT_SUPPORTED)` (§5.10).
- **GraalVM Native Reflection (§6.7 / AGENTS.md §8)**: register each new type in the **owning
  module's** `*ReflectionConfig` — core trace records/enums in `CoreReflectionConfig`, app-level
  DTOs in `AppReflectionConfig`; never type-level `@RegisterForReflection`; nested/private types via
  `classNames` with binary names. UI TypeScript types need no GraalVM registration.
- **Module Boundaries (§1.4 / §1.8)**: channel modules consume trace via `channel-api` SPIs and
  never import `com.amsha.core.*` types directly; HITL approve/deny routes through
  `CheckpointApprovalService` + `ToolInvocationGuard` (§4.12 / §7.7).
- **X-Ray authorization boundary**: core exposes only a symbolication contract + safe-metadata
  model; the application/resource layer authenticates the caller and enforces `isInternal()`. The
  core service never trusts a UI-provided "internal" flag.
- **PMD Complexity Gate**: NCSS ≤ 80, cognitive complexity ≤ 50 (`amsha-rules.xml`).
- **Git hygiene**: staging/commits stay with the human; no agent `git add`/`commit`/`reset`.

Verification gates: `python3 ./scripts/verify_docs.py`; UI `cd ui && npm run build && npm run
test`; Java `python3 ./scripts/fix_fqns.py && ./mvnw spotless:apply -q` and
`./mvnw -pl <module> -am pmd:check -DskipTests`; native smoke `mise run smoke:local-runtime --
native --binary dist/amsha-sqlite` (zero reflection-registration errors).

## 6. Status and Review

- **Status**: Proposed.
- **Decision Date**: 2026-09-01.
- **Reconciliation Date**: 2026-09-07.
- Core visual cockpit iterations (P0, P1, P2) and P3 advanced surfaces (Issues 08, 09, 10, 11, 12, and DB Console in Issue 13) are evidence-verified. Full implementation status for the ADR is held in Proposed pending Issue 13's byte-level `GovernedTransportService.send()` provider execution unification under provider transport roadmap §O2. Detailed scope, claim classification, and iteration issues live in the feature tracker under `.scratch/amsha-visual-cockpit/` (`spec.md` and `issues/`).

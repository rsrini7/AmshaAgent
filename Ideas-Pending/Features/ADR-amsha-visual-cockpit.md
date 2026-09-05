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
   The evaluation concluded with **Deferred**: existing OMLX audio transcription remains an external HTTP
   provider integration and is not in-process Whisper. In-process local Whisper is held in the evidence registry
   until a pinned model bundle, audio DSP pipeline, and seq2seq ONNX runner are provisioned.

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
- **Review date**: 2026-09-01.
- Detailed scope, claim classification, and issue breakdown live in the feature tracker under
  `.scratch/amsha-visual-cockpit/` (`spec.md` and `issues/`).

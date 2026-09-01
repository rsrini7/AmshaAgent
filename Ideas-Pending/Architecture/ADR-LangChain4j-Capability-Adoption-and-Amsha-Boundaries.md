# ADR: Adopt LangChain4j Capabilities Behind Amsha-Owned Boundaries

- **Status**: Accepted (2026-08-25, maintainer review); Iteration 0 baseline-evidence capture authorized
- **Date**: 2026-08-25
- **Decision owners**: Amsha Agent maintainers
- **Feature Slug**: `lc4j-capability-adoption`
- **Issue Tracker**: `.scratch/lc4j-capability-adoption/`
- **Initiative priority**: P1 strategic modernization; the first implementation slice is P1 structured-output completion
- **Related**: [Quarkus upgrade strategy](ADR-Quarkus-Upgrade-Strategy.md), [ONNX local inference](../ADR-ONNX-In-Process-Local-ML-Inference.md), [MCP management and channel control](../ADR-MCP-Management-UI-and-Channel-Control.md), [Architecture Invariants](../../docs/Architecture_Invariants.md)

## Context

Amsha already uses LangChain4j as the common chat and embedding protocol, but the proposed modernization plan mixed three different kinds of work: capabilities that can safely be adopted, integrations that should remain behind Amsha adapters, and features that would cross channel or tool-governance boundaries. The repository review on 2026-08-25 established the following baseline:

- Quarkus is `3.38.3`. The effective `app` dependency tree resolves Quarkiverse LangChain4j extensions at `1.12.2` and `dev.langchain4j` libraries at `1.17.2`. Both are managed by the Quarkus LangChain4j member BOM. The existing Quarkus upgrade ADR already records the upgrade and updater dry-run, so this initiative does not need a second version-pinning or Quarkus-checkpoint phase.
- The current `dev.langchain4j` `1.17.2` ceiling is an explicit compatibility boundary. A capability whose required API or implementation is newer than that ceiling waits for the next Quarkus/member-BOM checkpoint. Any interim `dependencyManagement` override is outside this ADR and requires a separate recorded decision with build, native-image, and compatibility evidence.
- `LlmProviderPort` deliberately uses LangChain4j `ChatRequest` and `ChatResponse`, and `LangChain4jLlmAdapter` preserves persona/group/global model routing. This is an intentional compatibility seam, not evidence that provider-specific SDKs should leak into the agent domain.
- `StructuredOutputMode` and schema retry machinery already exist. The shared OpenAI-compatible builder path applies `ResponseFormat.JSON` in eligible toolless model configurations; the Ollama, Anthropic, and Gemini-specific factory paths do not. `StructuredOutputEngine` and intent classification do not attach an operation-specific JSON Schema through request parameters. `NATIVE` is already the default in `AgentChatModelProvider` and `AgentLlmConfig`, so production is already routed onto this gap even when only generic JSON mode is available. The `llm-provider-api` README also has stale structured-output enum values and must be reconciled during the first implementation slice.
- No LangChain4j `ChatModelListener` is registered. (`1.17.2` exposes no
  `ToolExecutionListener`; its AiServices-level tool-event listeners such as
  `ToolExecutedEventListener` bind to AiService pipelines Amsha does not run —
  tool execution is observed today through Amsha's own `AgentObserver`.)
  Amsha currently owns OTel spans, token metrics, `AgentObserver` callbacks,
  and Phoenix/OpenInference wiring. Listener adoption could reduce duplication,
  but it is not a drop-in replacement until trace, metric, context,
  native-image, and failure semantics are proven equivalent.
- The `mcp` module is an Amsha-owned bridge. It includes outbound client/lifecycle services, approval and credential handling, Podman and stdio transport, NDJSON handling, dynamic capability admission, and the `ExternalMcpToolAdapter`. The project uses Quarkus MCP server artifacts for its server surface, but has no `langchain4j-mcp` dependency. Replacing the bridge would risk bypassing `ToolInvocationGuard`, audit, category routing, and HITL.
- RAG currently performs persistence-backed hybrid retrieval and RRF ordering through `TwoTierMemoryService` and `ScoredChunk`. LangChain4j `ScoringModel` is available in `1.17.2`, but no reranker stage exists. `ScoredChunk.score` is a synthetic fusion score and is not a raw relevance threshold, so a cross-encoder score cannot be substituted into that field without changing retrieval semantics.
- `AudioContent`, `ImageContent`, and `PdfFileContent` are registered for native reflection, but the channel contract and inbound handlers are still text-oriented. Multimodal support is therefore an ingestion and routing feature, not an already-adopted model capability.

## Decision

Amsha will adopt LangChain4j capabilities incrementally behind Amsha-owned contracts. Upstream types may be used at the existing provider compatibility seam, but upstream integrations must not become alternate execution, approval, persistence, or channel paths.

### Structured output

The first implementation slice will make `StructuredOutputMode.NATIVE` meaningful through a provider/model capability contract. A structured operation will carry its generated schema to the request layer and use LangChain4j request-scoped response-format parameters, including a JSON Schema response format where the resolved model advertises support. `PROMPT_DIRECTIVE` and `LENIENT` remain supported fallbacks, and schema validation plus the existing bounded self-correction loop remain authoritative even when native decoding is requested. If capability detection is wrong or a provider rejects the schema response format, the request must fall back to `PROMPT_DIRECTIVE` plus the bounded self-correction path; a native-schema rejection must never hard-fail an otherwise recoverable turn.

Capability detection must be data- or factory-driven rather than a hardcoded provider-name list. It must account for model routing, toolless versus tool-call requests, proxy limitations such as Headroom, and provider-specific schema restrictions. No provider may claim strict support merely because it accepts generic JSON mode.

For composite/failover model wrappers, the safe capability intersection remains
the advertised contract. If runtime behavior still exposes a candidate that
rejects an attached native response format, the request-composition layer owns
rebuilding the request without that parameter before bounded prompt-directed
self-correction; failover must not replay a schema-bearing request verbatim.

### RAG reranking

Reranking is the next product-facing priority after structured-output completion. RAG will define one Amsha reranker port and may adapt LangChain4j `ScoringModel` behind it. Candidate generation remains the existing FTS5/BM25, vector, and RRF path. A reranker may reorder a bounded candidate set, but it must not make retrieval destructive or silently change the meaning of the existing fusion score.

The normative score contract is to preserve `ScoredChunk.score` as the existing RRF fusion score and carry model output as a separate `rerankScore` value in a non-breaking reranked-result contract. Rerank ordering may use `rerankScore`; existing fusion thresholds and consumers must not receive the raw cross-encoder score. A missing reranker, model-load failure, or embedding descriptor mismatch falls back to the current hybrid ordering and keyword retrieval. The preferred local implementation belongs in `local-ml-onnx`, subject to the existing ONNX bundle provenance, reflection, scheduling, and native-smoke rules. Optional cloud providers are later adapters, not a prerequisite for the local path.

`rerankScore` is an ordering-only, model-local score rather than a calibrated
probability or cross-model threshold. Any future calibration or blending
requirement needs a separately versioned contract.

### Observability listeners

Listener-based observability is a migration pilot, not an immediate deletion of Amsha instrumentation. A future `ChatModelListener` adapter may capture provider request, response, error, latency, and token events, but existing spans and metrics remain authoritative until parity is demonstrated without duplicate spans, duplicate token counts, lost `WorkspaceContext`, changed redaction, or native-image regressions. Tool-execution observation remains Amsha-owned via `AgentObserver`; upstream AiServices tool-event listeners are out of scope while Amsha executes tools through its own guard path, and any future adoption re-enters through this same boundary: observe an already admitted execution only, never discover, authorize, execute, or resume a tool outside Amsha’s normal path.

The P3 placement is intentional: product-facing retrieval quality is prioritized ahead of internal instrumentation cleanup. This knowingly prolongs the current hand-rolled OTel/Micrometer path until the listener pilot proves parity; it is not an accidental omission or a reversal of the observability goal.

### MCP

Amsha will not replace its MCP bridge with a general LangChain4j MCP execution path. If a concrete protocol-compatibility gap justifies upstream adoption, LangChain4j MCP functionality may be evaluated only as a transport or serialization adapter beneath the existing MCP services and `ExternalMcpToolAdapter`. Every remote tool must still be represented in the dynamic capability registry and pass the normal `ToolRegistry`/`ToolInvocationGuard`, category, audit, workspace, and HITL paths. `NETWORK_OUTBOUND` and `EXECUTE_SHELL` remain sensitive operations regardless of upstream abstractions or trusted roles.

If that adapter requires a LangChain4j API newer than the current `1.17.2` ceiling, the executable evaluation waits for the next Quarkus/member-BOM checkpoint. This ADR does not authorize an ad-hoc version override.

### Multimodal and deferred work

Multimodal channel ingestion is deferred until a channel-neutral content contract, provider capability matrix, bounded download/extraction path, and safe text-only fallback are specified. Channel modules continue to communicate with core through channel APIs; they do not construct a parallel LangChain4j execution path. Output remains subject to Telegram sanitization and artifact transformation invariants.

If Telegram is included in the implementation scope, the Telegram fixture must be updated as part of the acceptance work.

New vector stores, additional embedding providers, and a parallel moderation/RAIL stack are deferred. They require separate demand and, where applicable, separate ADRs because they interact with persistence parity, embedding descriptor compatibility, and the existing classifier control plane.

## Priority and sequencing

| Priority | Work item | Exit gate |
| --- | --- | --- |
| P0 — complete prerequisite | Consume the existing Quarkus `3.38.3` upgrade decision and BOM-managed version baseline. Do not add independent LangChain4j capability version pins. The existing `openinference-langchain4j.version` `0.1.8` instrumentation pin is a pre-approved exception, not a capability pin, and is revisited during the P3 listener pilot. Capabilities above the `1.17.2` ceiling wait for the next member-BOM checkpoint unless a separate ADR authorizes an override. | Effective dependency tree and existing upgrade evidence remain the source of truth; stale structured-output documentation is captured for the P1 acceptance work. |
| P1 — next | Capability-driven native structured output across agent, intent, and compaction flows. | Request-scoped schema tests, provider capability tests, fallback/self-correction tests, focused classifier tests, PMD gates, and native reflection review pass. |
| P2 | Two-stage RAG with a bounded reranker, starting with local ONNX. | Baseline-vs-reranked benchmark, score-contract tests, descriptor-mismatch preservation, local ONNX smoke, and all persistence-backend fallback tests pass. |
| P3 | One-model observability listener migration pilot. | OTel/Micrometer parity, payload-safe redaction, context propagation, no duplicate instrumentation, and JVM/native verification pass before any manual code is removed. |
| P4 — safety gated | Protocol-only MCP adapter evaluation. | A demonstrated protocol gap, compatibility corpus, and proof that discovery, admission, invocation, audit, HITL, and `FINISH` invariants remain intact. If the required API exceeds `1.17.2`, defer to the next member-BOM checkpoint; no proof means retain the current bridge. |
| P5 — broad surface | Multimodal channel ingestion and provider-aware degradation. | Channel API contract, bounded media handling, capability routing, safe fallback, artifact/output sanitization, Telegram fixture updates when applicable, and channel integration tests pass. |

The sequence is normative for the follow-on specification and issues. A later phase must not be pulled into implementation because an upstream API is convenient; its exit gate and the relevant Amsha invariant remain the controlling boundary. Java implementation issues must use the focused-verification commands and module-scoped PMD requirements in [AGENTS.md](../../AGENTS.md); the detailed command matrix belongs in the later specification.

## Considered options

- **Adopt the upstream stack wholesale**, including native schemas, listeners, MCP, and multimodal content. Rejected because upstream abstractions do not own Amsha’s workspace scope, admission, audit, HITL, persistence, or terminal-finish rules.
- **Keep all current implementations and ignore LangChain4j capabilities.** Rejected because Amsha would retain avoidable prompt-based structured-output work and miss a useful reranking interface while carrying the cost of the existing dependency.
- **Use a phased capability/adaptor strategy.** Chosen because it captures the low-risk structured-output and RAG value while preserving Amsha ownership at safety-sensitive boundaries.

## Consequences

- The Quarkiverse/core version split is accepted and BOM-managed for the current Quarkus checkpoint; it is documented here rather than duplicated as fragile root-POM pins.
- The first feature specification must define provider capabilities and structured request composition before implementation can claim native schema enforcement.
- RAG gains a clear extension seam while preserving `ScoredChunk.score` as fusion score and placing reranker output in a separate `rerankScore` contract.
- The intentional P2/P3 ordering prioritizes retrieval quality over observability cleanup and temporarily retains the hand-rolled instrumentation path.
- Observability and MCP work may take longer than a direct upstream integration because compatibility and safety evidence are explicit release gates.
- This ADR sets priority and boundaries only. The feature specification and ordered iteration issues will be created separately after this decision is reviewed.

## P2 — High Impact, Larger Scope

Schedule after P1 is stable.

### 4. ✅ Prompt System — Externalize to `PromptRegistry`

**Status**: Complete (Phase A) — multi-backend PromptRegistry with versioning, drift detection, rollout, and admin API.

**Remaining**: `PromptSectionBuilder` and `CoordinatorPromptSection` still use inline static strings — these can be migrated to registry lookups in a follow-up if needed.

***

### 5. Tool Bulkheads (SmallRye Fault Tolerance)
A slow `brave_web_search` can starve `sandbox_exec` if thread pools are not isolated. This is a silent production risk.

**Direction:**
- Separate bulkhead pools per category: `Network`, `Compute`, `Database`
- Use SmallRye `@Bulkhead` per tool category
- A timeout in one category never blocks another

***

### 6. Semantic Observability — LLMOps Tracing
Your OTel covers infrastructure. It cannot tell you *why* the LLM hallucinated a tool parameter — it just sees `200 OK` from the LLM and `400 Bad Request` from the tool.

**Direction:**
- Integrate Langfuse or Arize Phoenix
- Trace the full `Prompt → LLM Response → Tool Call → Observation → Next Prompt` chain
- Enables LLM-as-a-Judge automated evaluations and prompt effectiveness measurement

but we have to put in docker compose file.
podman run -d -p 6006:6006 -p 4317:4317 -p 4318:4318 --name phoenix arize-ai/phoenix:latest

<!-- Standard OpenTelemetry Exporter -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>

or 

<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
    <version>1.44.0</version> <!-- Align version with your active OTel BOM -->
</dependency>


<dependency>
    <groupId>com.arize</groupId>
    <artifactId>openinference-instrumentation-langchain4j</artifactId>
    <version>0.1.8</version> 
</dependency>

(or)

<!-- Source: https://mvnrepository.com/artifact/com.arize/openinference-instrumentation -->
<dependency>
    <groupId>com.arize</groupId>
    <artifactId>openinference-instrumentation</artifactId>
    <version>0.1.9</version>
    <scope>compile</scope>
</dependency>

OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
    .setEndpoint("http://localhost:4318/v1/traces") // Points directly to Podman container
    .build();

SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
    .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
    .build();

***

## P3 — Strategic Upgrades

These are architectural evolution items, not urgent fixes.

### 7. Agent Loop → Graph-Based Orchestration (DAG)
After the GoF State pattern is in place (P1), the next evolution is a full directed graph model similar to LangGraph.

**Direction:**
- Nodes: `Plan`, `ExecuteTool`, `Reflect`, `HITL_Pause`, `Finish`
- Edges: conditional transitions with typed predicates
- Agent-specific sub-graphs (Researcher loops between `Search → Reflect` until confidence threshold; Coder goes straight to `Sandbox`)
- Declarative, visualizable, dynamically configurable without touching core loop

***

### 8. Memory — MemGPT-Style Tiered Context
Current `CompactionService` summarization destroys exact details (code snippets, error traces). This is the "Lost in the Middle" problem.

**Direction:**
- **Core Memory** — always in system prompt (user profile, current goal, active tool state)
- **Recall Memory** — searchable conversation history (SQLite FTS5)
- **Archival Memory** — semantic search via `sqlite-vec`
- Expose explicit agent tools: `core_memory_append`, `archival_memory_search` — let the LLM manage its own context rather than relying on background summarization

***

### 9. Security Guardrails — Move Off Regex
Regex-based `PromptInjectionReflex` and `PiiScanReflex` are trivially bypassed (base64, synonyms, payload splitting).

Refer #[Guardrails](Ideas-Pending/LangchainGuardrails.md)
**Direction:**
- **PII:** Microsoft Presidio via lightweight sidecar or Python MCP server — context-aware NER
- **Prompt Injection:** DeBERTa-v3-base fine-tuned classifier or Lakera Guard — run as pre-flight before text reaches LLM context

***

### 10. Playwright → Official MCP Server
Current `PlaywrightCliTool` shells out to Node.js — brittle IPC, schema drift risk.

**Direction:**
- Fallback `PlaywrightCliTool` to Node.js if MCP server is not available.
- Connect via existing `McpClientService` to the official Playwright MCP Server
- Standardizes schema, removes Node.js IPC fragility

***

## Full Priority Summary

| # | Item | Priority | Type |
|---|------|----------|------|
| — | Null-safe constructor, renderer fallback, CDI lazy parse | **P0** | In-flight |
| 1 | State Pattern for AgentStateMachineRunner | **P1** | Refactor |
| 2 | Drop JPA for SQLite → jOOQ/JDBI | **P1** | Refactor |
| 3 | Concurrency handling for parallel tools | **P1** | Modernize |
| 4 | ✅ Externalize prompts to PromptRegistry | **P2** | Complete |
| 5 | Tool bulkheads per category | **P2** | Resilience |
| 6 | Semantic LLMOps tracing (Langfuse/Arize) | **P2** | Observability |
| 7 | Graph-based agent orchestration (DAG) | **P3** | Evolution |
| 8 | MemGPT tiered memory | **P3** | Evolution |
| 9 | Presidio/DeBERTa security guardrails | **P3** | Security |
| 10 | Playwright → MCP Server | **P3** | Modernize |
### 3. Structured Concurrency (`StructuredTaskScope`) for Parallel Tool Calls
Yes — if you do **not** want preview features, then do **not** adopt Structured Concurrency yet. Stick with **virtual threads + explicit cancellation** for halt handling; that is GA and already gives you a solid improvement path. [morling](https://www.morling.dev/blog/future-to-completablefuture-with-java-virtual-threads/)

#### What to use instead

#### Best GA alternative
Use:
- **Virtual threads** for blocking tool/LLM work.
- **`Future.cancel(true)` / `CompletableFuture.cancel(true)`** for halt.
- **A shared cancellation token / flag** checked by long-running loops.
- **`Thread.currentThread().isInterrupted()`** checks in tool wrappers and polling loops.

That gives you a production-safe halt path without preview APIs. Virtual threads are fully usable on their own, and structured concurrency is optional, not required, for getting the scaling benefits. [dev](https://dev.to/jackynote/completablefuture-vs-virtual-thread-in-java-21-1gfp)

#### How to make `/halt` work well

#### Recommended halt design
1. Mark the session as cancelled in your session state.
2. Interrupt any running worker thread or future.
3. Make every long-running tool wrapper poll cancellation before and after blocking calls.
4. On cancellation, return a terminal `HALTED` or `CANCELLED` agent result.

#### Minimal pattern
```java
if (Thread.currentThread().isInterrupted() || cancellationToken.isCancelled()) {
    throw new CancellationException("Session halted");
}
```

Use this:
- before tool execution,
- after tool execution,
- inside long loops,
- before retries / reflection steps.

#### Any other issues

#### No need to replace `synchronized` everywhere
JDK 25’s Loom work reduced the virtual-thread pinning concern around `synchronized`, so you do **not** need a blanket migration to `ReentrantLock` just for Loom. [infoq](https://www.infoq.com/news/2024/11/java-evolves-tackle-pinning/)
So keep `synchronized` where it is correct semantically; only refactor if you have a real contention/design reason.

#### `CompletableFuture` is fine for now
If your current async composition is working, keep it. It is GA, well understood, and doesn’t force preview features. Use it with explicit cancellation and timeouts rather than rewriting the world. [dev](https://dev.to/jackynote/completablefuture-vs-virtual-thread-in-java-21-1gfp)

#### Biggest remaining architectural risk
The larger issue is not concurrency — it is **complexity in the agent loop**. Your bigger debt remains:
- monolithic runner logic,
- SQLite/JPA friction,
- hardcoded prompts,
- regex guardrails,
- and a too-large tool orchestration surface.

Those are more important than adopting a preview concurrency API.

#### My recommendation

#### Keep now
- Virtual threads.
- `CompletableFuture` where already stable.
- Manual interrupt/cancel path for `/halt`.
- Timeouts on tool calls.

#### Defer
- Structured Concurrency until it is GA in your target JDK.

#### Focus next 
1. State pattern for the agent runner.
2. SQLite adapter cleanup.
3. Prompt registry.
4. Better cancellation propagation across tools.
5. Bulkheads for tool categories.

***

## P2 — High Impact, Larger Scope

Schedule after P1 is stable.

### 4. Prompt System — Externalize to `PromptRegistry`
Current static `final String` constants have zero versioning, zero A/B testing, require recompile to change.

**Direction:**
- `PromptRegistry` backed by DB/YAML/Git
- Add fields: `version`, `status`, `rolloutPercentage`, `auditMetadata`
- Runtime swap without redeployment

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
| 4 | Externalize prompts to PromptRegistry | **P2** | Feature |
| 5 | Tool bulkheads per category | **P2** | Resilience |
| 6 | Semantic LLMOps tracing (Langfuse/Arize) | **P2** | Observability |
| 7 | Graph-based agent orchestration (DAG) | **P3** | Evolution |
| 8 | MemGPT tiered memory | **P3** | Evolution |
| 9 | Presidio/DeBERTa security guardrails | **P3** | Security |
| 10 | Playwright → MCP Server | **P3** | Modernize |
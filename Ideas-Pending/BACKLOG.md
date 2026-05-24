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
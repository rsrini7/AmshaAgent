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

Refer [Ideas-Pending/MemGPT-Style.md](MemGPT-Style.md)

Current `CompactionService` summarization destroys exact details (code snippets, error traces). This is the "Lost in the Middle" problem.

**Direction:**
- **Core Memory** — always in system prompt (user profile, current goal, active tool state)
- **Recall Memory** — searchable conversation history (SQLite FTS5)
- **Archival Memory** — semantic search via `sqlite-vec`
- Expose explicit agent tools: `core_memory_append`, `archival_memory_search` — let the LLM manage its own context rather than relying on background summarization

---

## Backlog note

You can write it like this:

**Backlog item:** Move tool execution category from `ToolCapability` fallback logic to the tool contract itself (`AgentTool.category()` or `ToolMetadata`) so dynamically discovered MCP tools can declare `NETWORK`, `COMPUTE`, or `DATABASE` explicitly instead of defaulting to `INTERNAL`. 

## Suggested scope

This backlog item should cover:
- Static built-in tools continue to map from `ToolCapability.category()`.
- Dynamic MCP tools expose category through discovery metadata.
- `ToolCategoryMapper` becomes a compatibility shim or is removed.
- Executor selection uses the tool’s declared category end-to-end. 

## Why this is the right backlog

That framing keeps the current implementation honest: it is good enough today, but it is not the final extensible shape. It also prevents future readers from assuming the fallback-to-internal behavior is an architectural choice rather than a temporary bridge. 

## Short ADR text

**Decision:** Keep `ToolCapability` as the canonical category source for built-in tools only.  
**Reason:** Dynamic MCP tools currently bypass the enum and safely default to `INTERNAL`.  
**Consequence:** No starvation risk now, but category metadata must move to the runtime tool contract when dynamic tools become a larger share of the system. 

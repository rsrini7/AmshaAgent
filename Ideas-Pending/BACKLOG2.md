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

# Amsha Tool API Module (`amsha-tool-api`)

Pure API contracts for agent tools in Amsha Agent. Extracted from `amsha-core` (stream S5 of the core-subsystems extraction; see [ADR-extract-core-subsystems](../docs/adr/architecture/ADR-extract-core-subsystems.md)).

`amsha-tool-api` contains **zero dependencies on `amsha-core`**, so tool provider modules (`amsha-tools-builtin`, `amsha-mcp`, `amsha-rag`, `amsha-self-evolve`) implement and consume tool contracts without importing core internals. Dispatchers and the invocation guard remain in `amsha-core`.

## Core Contracts

| Type | Purpose |
|---|---|
| `AgentTool` | Central SPI every agent tool implements (descriptor + execution entry point) |
| `ToolCapability` | Capability identity surfaced to role rules and guards; use `toolName()` instead of string literals |
| `ToolMetadata` | Static tool metadata (name, category, sensitivity, delegation visibility) |
| `ToolCategory` | Category taxonomy driving thread-pool isolation and risk defaults |
| `ToolExecutionContext` | Per-invocation context passed to tools (workspace, limits, sentinels) |
| `ToolResponse` | Sealed execution result returned by tools |
| `DynamicToolDescriptor` | Descriptor for dynamically discovered tools (e.g. MCP client tools) |
| `IdempotencyMode` | Declares whether repeat invocations are safe |
| `AgentSentinels` | Sentinel markers exchanged via the execution context |

## Downstream Usage

```xml
<dependency>
  <groupId>com.amsha</groupId>
  <artifactId>amsha-tool-api</artifactId>
</dependency>
```

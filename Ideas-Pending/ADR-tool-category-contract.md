# ADR: Tool Execution Category on the Tool Contract (not `ToolCapability` fallback)

- **Status:** Proposed (revised per Antigravity and implementation review 2026-07-12)
- **Date:** 2026-07-12
- **Supersedes:** Ideas-Pending/BACKLOG.md "Backlog item: Move tool execution category from `ToolCapability` fallback logic to the tool contract itself"
- **Related:** Done backlog item "Unify Channel Dispatch and Intent Classification" (P106); session-catalog ADR (P107)

## 1. Context

Parallel tool-batch bulkhead isolation is selected by a `ToolCategory`, which
names a CDI-managed executor pool. The wiring already exists for that path:

- `ParallelToolStepExecutor.executorFor(ToolCategory)` (core/.../agent/loop/ParallelToolStepExecutor.java:109)
  selects the pool via `managedExecutors.select(Identifier.Literal.of(category.executorName()))`.
- `ToolCategory` (core/.../tools/ToolCategory.java:3) is `NETWORK`/`COMPUTE`/`DATABASE`/`INTERNAL`, each with a
  distinct `executorName()` (e.g. `network-tool-executor`, `compute-tool-executor`).
- `ToolExecutorProducer` (app/.../config/ToolExecutorProducer.java, class at L28, `buildExecutor` at L73) builds a
  separate `ManagedExecutor` per category (separate bulkhead).
- `ParallelToolStepExecutor` obtains the category from `categoryMapper.categoryFor(toolName)`
  (injected `ToolCategoryMapper`). This is the only current consumer of a tool
  category for executor selection; sequential tool execution does not use a
  category-specific `ManagedExecutor`.

`ToolCategoryMapper.categoryFor` (core/.../tools/ToolCategoryMapper.java:16) resolves in this order:
1. strip an `mcp:`/prefix; if a registered `AgentTool` exists, return `tool.get().category()` **if non-`INTERNAL`**;
2. otherwise fall back to `ToolCapability.fromToolName(base).map(ToolCapability::category)` **defaulting to `INTERNAL`**.

`AgentTool.category()` (core/.../tools/AgentTool.java:51) **already exists** as a `default ToolCategory INTERNAL`.
Its Javadoc states it "is the contract for dynamically discovered tools (MCP, plugins)." So the contract method
is present.

### The actual gap (larger than first described)

External/discovered MCP tools are **not** wrapped as `AgentTool` and are **not** in `ToolRegistry`:

- `DynamicToolCapability` (mcp/.../tool/DynamicToolCapability.java:5) is a plain record (`toolName`, `description`,
  `mcpServerName`, `transport`, `requiresApproval`, `inputSchema`) with **no `ToolCategory` field**.
- They are stored in `DynamicToolCapabilityRegistry` (mcp/.../tool/DynamicToolCapabilityRegistry.java:15, `find` at L27),
  populated at discovery by `McpClientService` (L384) and `PodmanMcpTransport` (L182).
- `ExternalMcpToolAdapter` (mcp/.../bridge/ExternalMcpToolAdapter.java) invokes them via transport and does **not**
  implement `AgentTool`.
- `ToolInvocationGuard` allows them via the `DynamicToolLookup` interface (core/.../tool/DynamicToolLookup.java:3,
  impl `DynamicToolLookupImpl` in `amsha-mcp`) — the established pattern for keeping `core` free of MCP deps.

Consequence: for a discovered external tool, `ToolCategoryMapper` step 1 misses (`toolRegistry.findTool` empty)
**and** step 2 misses (no `ToolCapability` row) → it silently returns **`INTERNAL`**. The bulkhead isolation the
system is designed for is lost for exactly the tools most likely to do network I/O.

`ToolCapability.fromToolName` is also used by governance/approval paths (`CheckpointRecord`, `SkillDefinitionParser`,
`ToolResultProcessor`) for *capability* resolution — those stay on `ToolCapability` for built-ins.

## 2. Decision

Take the **incremental** step the backlog's Short ADR prescribes: keep `ToolCapability` as the canonical category
source for built-in tools; make the tool contract the source for dynamic/discovered tools; `ToolCategoryMapper`
becomes a compatibility shim that consults the dynamic registry. Executor selection keeps reading `categoryFor`
end-to-end.

### 2.1 Carry `category` on the real data carrier — `DynamicToolCapability`

Add non-null `ToolCategory category` as the **final** component of the
`DynamicToolCapability` record (default `NETWORK` at the registration call
sites, since external MCP tools are inherently network-bound — see §2.4).
Appending it preserves the existing JSON property order for clients that
consume the `/mcp/tools` response. This is the field through which a discovered
tool's category flows from MCP discovery to the mapper.

- `McpClientService` (L384) and `PodmanMcpTransport` (L182): populate `category` from an explicit value when supplied,
  else resolve the config-map entry, then default to `NETWORK`.
- `McpSelfRegisterTool` (mcp/.../tool/McpSelfRegisterTool.java): accept an optional `category` in the registration
  params so an operator can declare a category for every tool discovered from that server. Add category-aware
  `connect`/`connectStdio` overloads and pass the value through to `registerTools`. Persist it in `StdioConfig` so
  health-check and invocation reconnects retain the declaration. Add `ToolCategory category` to `McpServerConfig`
  and pass it to Podman's `registerTools` for parity. **Additionally, declare
  `@ToolMetadata(category = ToolCategory.NETWORK)` on this built-in tool** — it performs network I/O
  (`mcpClientService.connect()` / `connectStdio()`) yet currently inherits `INTERNAL` via its `ToolCapability`
  entry. Mapper metadata resolution (§2.2) makes that annotation effective.
- Two **production** construction sites (plus the test constructions listed in §5) update for the new component.

### 2.2 `ToolCategoryMapper` consults the dynamic registry (core stays MCP-free)

Add a **core** interface mirroring `DynamicToolLookup` (so `core` has no compile dep on `amsha-mcp`):

```java
package com.amsha.core.tool;
public interface DynamicToolCategoryLookup {
    /** Category for a discovered MCP tool, or empty if not a dynamic tool. */
    Optional<ToolCategory> categoryFor(String toolName);
}
```

Implemented in `amsha-mcp` (e.g. `DynamicToolCategoryLookupImpl`) by delegating to
`DynamicToolCapabilityRegistry.find(toolName).map(DynamicToolCapability::category)`.

`ToolCategoryMapper` (already `@ApplicationScoped`, injects `ToolRegistry`) gains an injected
`Instance<DynamicToolCategoryLookup>` and this **5-step resolution**:

1. **Registered `AgentTool` override**: return `agentTool.category()` when non-`INTERNAL`.
2. **Registered-tool metadata**: when step 1 returns `INTERNAL` (either because of the default method or an intentional `INTERNAL` override), apply the annotation value; the class's `@ToolMetadata` annotation wins and its `category()` value is returned (resolving the inherited annotation from the registered tool class using the same superclass walk as `ToolDiscoveryService`).
3. **Dynamic MCP tool**: if `dynamicCategoryLookup.categoryFor(base)` is present and non-`INTERNAL`, return it.
4. **Built-in shim**: `ToolCapability.fromToolName(base).map(ToolCategory::category)`.
5. **Last resort `INTERNAL`** — for tool names present in **no** registry (`ToolRegistry`, dynamic lookup, or
   `ToolCapability`). **WARN semantics (precise):** emit the WARN *only* when the tool name **is** present in
   `DynamicToolCapabilityRegistry` or `ToolRegistry` (a *known* tool) yet still resolved to `INTERNAL` — this flags a
   mis-categorization (e.g. a discovered tool whose registration failed to apply the `NETWORK` default, or a
   registered `AgentTool` that legitimately returns `INTERNAL` but was expected external). Genuinely-unknown names
   (not in any registry) fall to `INTERNAL` **silently** — this is expected for untracked tools and must not be
   logged, to avoid noise. (In practice, since discovered tools default to `NETWORK` at registration, step 5 should
   rarely fire for known tools.)

No change to `ParallelToolStepExecutor.executorFor` — it already reads `categoryFor`, so the corrected category
flows to the correct `ManagedExecutor` for parallel batches. This ADR does not change sequential execution;
extending bulkheads to that path is a separate decision.

### 2.3 Declarative category for registered tools

- `ToolMetadata` (core/.../agent/role/ToolMetadata.java:18) gains an optional
  `ToolCategory category() default ToolCategory.INTERNAL`. The cross-package reference
  (`com.amsha.core.agent.role` → `com.amsha.core.tools.ToolCategory`) already exists implicitly via `ToolCapability`,
  so no new coupling is introduced.
- Built-in/plugin `AgentTool` implementations that want a non-`INTERNAL` category either override `category()` or
  declare it via `@ToolMetadata.category()`; `ToolCategoryMapper` resolves the latter directly when no explicit
  `AgentTool` category is supplied. `ToolDiscoveryService` remains responsible only for discovery and role grants.

### 2.4 Canonical dynamic-tool IDs and category entry

Dynamic tools use the external call name `mcp:<serverName>:<toolName>` and the
registry key `<serverName>:<toolName>`. A new core `DynamicToolId` parser is the
only canonicalization point:

- for a dynamic external name, it strips **only** the literal `mcp:` prefix and
  returns the server-qualified registry key;
- it rejects malformed dynamic names rather than silently dropping a prefix; and
- legacy static `mcp:<toolName>` names still normalize to `<toolName>` for
  `AgentTool`/`ToolCapability` lookup.

`ToolCategoryMapper`, `DynamicToolCategoryLookup`, `DynamicToolLookup`,
`DynamicToolExecutor`, `ToolInvocationGuard`, `ToolInvoker`, and
`ExternalMcpToolAdapter` must use this parser. The registry stores and resolves
only the server-qualified key, preventing tools announced by different servers
from overwriting each other. `DynamicToolCapability.toolName()` retains the MCP
server's unqualified name for the outbound `tools/call` request; its registry key
is derived from `mcpServerName` and `toolName`.

MCP `tools/list` has no standard `category` field, so:
- **Default `NETWORK`** for every discovered external MCP tool (safest — MCP calls are network-bound). This removes
  the silent `INTERNAL` degradation the original ADR proposed.
- **Override** via the `register_mcp_server` `category` param (§2.1) and/or the config map
  `amsha.mcp.tool-categories` (a `Map<String,String>`/`Map<String,ToolCategory>` injected via
  `@ConfigProperty(name = "amsha.mcp.tool-categories")`, keyed by the server-qualified registry key
  `<serverName>:<toolName>`, value one of
  `NETWORK`/`COMPUTE`/`DATABASE`/`INTERNAL`). Per AGENTS.md config rules, the map key/values also go into
  `application.yaml` and `.env.example`. **Precedence:** explicit server-registration `category` > config-map entry >
  default `NETWORK`. The server category is retained through every reconnect; the resolved per-tool value is stored
  on the `DynamicToolCapability` record at registration.

### 2.5 What stays on `ToolCapability`

`ToolCapability.fromToolName` continues to serve **built-in** tools and the governance/approval paths
(`CheckpointRecord`, `SkillDefinitionParser`, `ToolResultProcessor`). It is demoted from "fallback for everything"
to "source for built-ins" (mapper step 4). `ToolCategoryMapper` is the only current bulkhead-resolution path, so
this change covers parallel-batch executor selection end-to-end.

### 2.6 Dynamic Tool Execution Port
To enable dynamic MCP tools to execute through the agent loop:
- **Make guard approval an opaque handoff:** `ToolInvocationGuard` creates a
  `GuardPermit` only in its successful `Allow` decision. The permit has a private
  constructor and contains the session ID, canonical tool ID, and an immutable
  parameter copy. It cannot be forged or modified by callers. Guard consumers
  pass this permit—not independently supplied tool name/parameters—to execution.
  `ToolStepHandler`, `ParallelToolStepExecutor`, and `SequentialToolExecutor`
  must retain each permit with its corresponding call. The existing unguarded
  `ToolInvoker.invoke(toolName, params, sessionId)` remains available for
  registered static tools only and rejects dynamic IDs.
- **Introduce `DynamicToolExecutor`** (a new interface in `core/.../tool/DynamicToolExecutor.java`):
  ```java
  package com.amsha.core.tool;
  import com.amsha.core.agent.loop.ToolInvocationGuard.GuardPermit;
  import com.amsha.core.tools.ToolExecutionContext;
  import com.amsha.core.tools.ToolResponse;
  public interface DynamicToolExecutor {
      ToolResponse execute(ToolExecutionContext context, GuardPermit permit);
  }
  ```
- **Implement `DynamicToolExecutorImpl`** (in `amsha-mcp` module):
  Implements `DynamicToolExecutor` by reading the approved ID and parameters from
  the permit, resolving the server-qualified capability, and converting parameters
  to JSON before delegating
  to `ExternalMcpToolAdapter.invoke()`. Before transport invocation it must apply
  the same dynamic-tool governance contract as the loop: revalidate that the
  dynamic lookup still succeeds, treat the operation as `NETWORK_OUTBOUND`, and
  audit executed/blocked/failed calls with the supplied workspace context. HITL is
  guaranteed by the permit; a missing or invalid permit is rejected. It must not use the
  static `ToolRegistry` role-capability check, because dynamic authorization is
  established by `DynamicToolLookup` after registration.
- **Integrate into `ToolInvoker`** (in `core/.../agent/loop/ToolInvoker.java`):
  Inject `Instance<DynamicToolExecutor>` and a shared `ToolExecutionContext`
  factory extracted from `ToolRegistry` (do not duplicate its context construction).
  Add `invokeGuarded(GuardPermit permit, ToolExecutionContext context)` for
  dynamic calls and dispatch only through that method. It preserves outer
  telemetry, tracing, events, HITL validation, and audit behavior.

## 3. Consequences

**Positive**
- External/discovered MCP tools get correct `NETWORK`/`COMPUTE`/`DATABASE` bulkheads instead of `INTERNAL` in
  parallel batches.
- The contract (`AgentTool.category()` / `@ToolMetadata.category()` / `DynamicToolCapability.category`) is the single
  declarative home for execution category; `ToolCapability` is honestly just the built-in table.
- `core` keeps no compile dependency on `amsha-mcp` (new `DynamicToolCategoryLookup` mirrors `DynamicToolLookup`).
- No persistence backend (SQLite/PG/FS) involvement, no migration parity.

**Negative / cost**
- Two `DynamicToolCapability` construction sites, connection/reconnect state, Podman config, and tests update for
  the new field.
- New core interfaces + mcp impls (small, mirrors existing `DynamicToolLookup`).
- Every `@ToolMetadata` without `category()` keeps `INTERNAL` (safe); discovered tools default `NETWORK`.

**Neutral**
- Executor-selection call path unchanged.

## 4. Implementation phases

1. **Data carrier and propagation:** add `ToolCategory category` to `DynamicToolCapability`; resolve it at
   `McpClientService`/`PodmanMcpTransport`; accept and validate optional `category` in `McpSelfRegisterTool`; add
   category-aware connection methods; retain it in `StdioConfig` across reconnects and in `McpServerConfig` for
   Podman; declare `@ToolMetadata(category = ToolCategory.NETWORK)` on `McpSelfRegisterTool`; add
   `@ToolMetadata.category()`.
2. **ID, lookup, guard, and execution seams:** add core `DynamicToolId` and apply it at every dynamic-tool boundary; change
   `DynamicToolCapabilityRegistry` to server-qualified keys; add core `DynamicToolCategoryLookup` + mcp impl
   delegating to that registry. Add core `DynamicToolExecutor` port + `DynamicToolExecutorImpl` delegating to
   `ExternalMcpToolAdapter` after dynamic governance, HITL, and audit. Add opaque `GuardPermit` emission to
   `ToolInvocationGuard` and thread it through every loop execution path. Extract the shared `ToolExecutionContext`
   factory from `ToolRegistry`; update `ToolInvoker` to dispatch only permit-backed dynamic tools through the port.
3. **Mapper shim:** inject `Instance<DynamicToolCategoryLookup>`; implement the 5-step resolution (§2.2), including
   direct inherited `ToolMetadata` resolution; WARN on
   `INTERNAL` fallback per the precise semantics in step 5.
4. **Tests:** mapper precedence (explicit override, metadata, dynamic, built-in) + built-in-unchanged + `host_exec`;
   dynamic lookup returns `NETWORK`; verify HTTP/STDIO/Podman propagation and STDIO reconnect preservation; update
   `DynamicToolCapability*` tests for the new field; add `ToolInvoker` delegation integration tests.

## 5. Files

**Modify**
- `core/.../agent/role/ToolMetadata.java` (add `category()` element)
- `core/.../tools/ToolCategoryMapper.java` (5-step resolution, direct inherited `ToolMetadata` read,
  `DynamicToolCategoryLookup` injection, and precise WARN)
- `core/.../agent/loop/ToolInvoker.java` (dispatch to dynamic executor port when tool registry misses)
- `core/.../agent/loop/ToolInvocationGuard.java`, `ToolStepHandler.java`, `ParallelToolStepExecutor.java`, and
  `SequentialToolExecutor.java` (emit and carry opaque `GuardPermit` through the guarded execution path)
- `core/.../tool/DynamicToolCategoryLookup.java` (**new** core interface)
- `core/.../tool/DynamicToolExecutor.java` (**new** core interface)
- `core/.../tool/DynamicToolId.java` (**new** canonical dynamic-ID parser)
- `core/.../tools/ToolExecutionContextFactory.java` (**new**, extracted from `ToolRegistry` for static/dynamic parity)
- `mcp/.../tool/DynamicToolCapability.java` (append non-null `ToolCategory category` after `inputSchema`)
- `mcp/.../tool/DynamicToolCapabilityRegistry.java` (server-qualified keying)
- `mcp/.../transport/McpClientService.java` (connection overloads, `StdioConfig`, reconnects, and registration) and
  `mcp/.../transport/PodmanMcpTransport.java` / `McpServerConfig.java`: propagate and populate `category`
- `mcp/.../tool/McpSelfRegisterTool.java`: optional `category` registration param + `@ToolMetadata(category = ToolCategory.NETWORK)`
- `mcp/.../config/` or `mcp/.../tool/DynamicToolCategoryLookupImpl.java` (**new** mcp impl)
- `mcp/.../tool/DynamicToolExecutorImpl.java` (**new** mcp impl)
- `core/.../tools/AgentTool.java` (Javadoc: distinguish an explicit override from metadata resolved by the mapper;
  `category()` body unchanged)
- `core/.../tools/ToolContractExporter.java` (include the resolved execution category in exported contracts)
- `mcp/.../config/McpReflectionConfig.java` (add `@RegisterForReflection(targets = DynamicToolCapability.class)` — see §6)

**Tests**
- `core/.../tools/ToolCategoryMapperTest` (explicit-category, metadata, dynamic, and built-in precedence; add
  `host_exec` COMPUTE coverage; construct the mapper with mocked/injected `DynamicToolCategoryLookup` dependencies)
- `core/.../tool/DynamicToolCategoryLookup` impl test (returns `NETWORK` for discovered tool)
- `core/.../agent/loop/ToolInvokerTest` (test static-only unguarded path and permit-backed dynamic dispatch)
- Dynamic-ID and registry tests (static compatibility, valid/invalid dynamic IDs, duplicate tool names from two
  servers, and category/transport resolution by server-qualified key)
- Dynamic executor/guard handoff tests (reject an unguarded or mismatched call; preserve dynamic lookup,
  `NETWORK_OUTBOUND` HITL, and workspace audit context)
- `mcp/.../tool/DynamicToolCapabilityTest`, `DynamicToolCapabilityRegistryTest`, `ExternalMcpToolAdapterTest`,
  `DynamicToolLookupImplTest`, `McpReflexTest`, `McpClientServiceTest`, `McpStdioReconnectTest`, and
  `PodmanMcpTransportTest` (new field and propagation)
- `core/.../agent/role/ToolMetadata` parse test for `category()` and mapper test of inherited metadata

## 6. Native-image / AGENTS.md notes

- `DynamicToolCapability` is already a record; adding a non-null `ToolCategory` component is a primitive enum (native-safe).
  **It IS serialized at runtime:** `McpAdminResource.listAllTools()` (`/mcp/tools`) returns `registry.getAll()`
  (a `Collection<DynamicToolCapability>`) as JSON. Therefore add
  `@RegisterForReflection(targets = DynamicToolCapability.class)` to
  `mcp/.../config/McpReflectionConfig.java` (currently only `McpToolDescriptor`, `McpToolRequest`). The non-null
  `ToolCategory` field is an enum and needs no extra handling.
- New `DynamicToolCategoryLookupImpl` is a CDI bean (no reflection needed).
- Any new `@ConfigProperty` (`amsha.mcp.tool-categories.*`) is added to `application.yaml`, `.env.example`, and the
  mcp module README; verify both config files.

## 7. Acceptance criteria mapping

| Criterion | Where satisfied |
|---|---|
| Static built-in tools map from `ToolCapability.category()` | mapper step 4 unchanged; governance paths unchanged |
| Dynamic MCP tools expose category via discovery metadata | `DynamicToolCapability.category`, default `NETWORK`, override via param/config (§2.1, §2.4) |
| `ToolCategoryMapper` becomes a shim (not source of truth) | 5-step resolution prefers explicit contract/metadata + dynamic registry (§2.2) |
| Parallel executor selection uses declared category end-to-end | `ParallelToolStepExecutor.executorFor` already reads `categoryFor`; corrected category reaches the right pool. Sequential execution is out of scope. |
| No regression for built-ins | step-4 fallback preserves current categories; regression test (§4) |
| `core` stays MCP-free | new `DynamicToolCategoryLookup` core interface + mcp impl (§2.2) |

## 8. Verification

- `python3 ./fix_fqns.py && ./mvnw spotless:apply -q`
- `./mvnw test -pl core` (mapper + `DynamicToolCategoryLookup` unit tests)
- `./mvnw test -pl mcp` (MCP registration still resolves categories; `DynamicToolCapability*` tests)
- Native-sensitive path: `./mvnw compile -DskipTests -q`; `mise build:native-local` when applicable; confirm the
  required `McpReflectionConfig` registration for serialized `DynamicToolCapability` is present.

## 9. Operational note

External MCP tools defaulting to `NETWORK` (maxAsync=8) may increase contention on the network bulkhead when many
MCP servers are registered. Document tuning via `amsha.tools.bulkhead.network.max-async` (or the equivalent
`ToolExecutorProducer` property) when adding external tools.

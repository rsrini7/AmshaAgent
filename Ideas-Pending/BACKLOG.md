## P3 — Strategic Upgrades

These are architectural evolution items, not urgent fixes.

### 7. Agent Loop → Graph-Based Orchestration (DAG)
After the GoF State pattern is in place (P1), the next evolution is a full directed graph model similar to LangGraph.

**Direction:**
- Nodes: `Plan`, `ExecuteTool`, `Reflect`, `HITL_Pause`, `Finish`
- Edges: conditional transitions with typed predicates
- Agent-specific sub-graphs (Researcher loops between `Search → Reflect` until confidence threshold; Coder goes straight to `Sandbox`)
- Declarative, visualizable, dynamically configurable without touching core loop

### 8. Fix ArchUnit Test Discovery Failure in `app` Module

**Problem:** Running any test in the `app` module (e.g., `SkillApiTest`) triggers an ArchUnit test discovery failure: `TestEngine with ID 'archunit' failed to discover tests`. The actual test passes, but surefire reports a build failure due to the ArchUnit engine error.

**Root Cause:** ArchUnit discovers **all** test classes on the classpath. When it loads `HitlServiceTest`, Quarkus bootstraps and runs CDI validation for the entire application context. This fails because:

1. `HitlService` has `@IfBuildProperty("quarkus.flyway.enabled", "true", enableIfMissing=true)`. The Quarkus Flyway extension's build step overrides this to `false` when no migration files are found in the `app` module's direct resources, vetoing the bean.
2. `Jdbi` is produced by `JdbiProducer` in `persistence-sqlite` with `@IfBuildProperty("amsha.persistence.backend", "sqlite", enableIfMissing=true)`. The producer depends on `AgroalDataSource`, and the cascade failure breaks the dependency chain.
3. `@IfBuildProperty` is a **build-time** annotation evaluated during Quarkus augmentation — runtime config (`application.yaml`) cannot reliably override it.

**Impact:** Cosmetic — `SkillApiTest` and all other app tests pass (5/5). The failure is only in the ArchUnit discovery side-effect. However, it blocks running `app` module tests via `mvn test -pl app` without filtering.

**Fix Options:**
- **Option A (recommended):** Move `HitlServiceTest` to the `persistence-sqlite` module where flyway + Jdbi infrastructure is properly configured.
- **Option B:** Exclude `HitlServiceTest` from ArchUnit discovery via `@AnalyzeClasses(packages = ...)` configuration.
- **Option C:** Add `@Vetoed` to `HitlServiceTest` and use `@InjectMock` instead of `@Inject` for `HitlService` and `Jdbi`.

**Verification:** After fix, `./mvnw test -pl app` should complete without ArchUnit discovery errors.

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

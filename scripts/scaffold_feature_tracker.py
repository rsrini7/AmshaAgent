#!/usr/bin/env python3
"""
Feature Tracker & ADR Scaffolding Utility for Amsha Agent.

This script manages ADRs and .scratch feature trackers, ensuring consistent formatting,
issue breakdown, and compliance with project invariants.
"""

import argparse
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent


def generate_tools_extraction_tracker():
    """Generates the ADR and .scratch tracker files for Tools & Sandbox modularization."""
    adr_content = """# ADR: Extract Built-in Tools and Sandbox Execution from amsha-core

- **Status**: Approved for Implementation
- **Date**: 2026-08-20
- **Decision Owners**: Amsha Architecture Committee
- **Related Invariants**: `docs/Architecture_Invariants.md` §15.2, §16.1–16.3, §16.7
- **Feature Slug**: `extract-tools-and-sandbox`
- **Issue Tracker**: `.scratch/extract-tools-and-sandbox/`

---

## 1. Context and Problem Statement

Following the extraction of `amsha-llm-providers` and `amsha-llm-provider-api`, `amsha-core` continues to host multiple independent subsystems alongside the agent reasoning engine:
1. **Concrete Built-in Agent Tools**: Over 30 files implementing search (Brave, Serper), web extraction (`SmartWebFetchTool`), host execution (`HostExecTool`), SCM/Git operations (`GithubTool`), scheduling (`ScheduleTaskTool`), checklists (`CheckListTool`), and memory tools (`ArchivalMemoryInsertTool`, `CoreMemoryAppendTool`).
2. **Container Sandbox & Process Isolation**: 14 files implementing Podman container runners, Linux process isolation, tmpfs mount drivers, code safety scanning, and secret transports.
3. **Heavyweight Dependencies**: Concrete tool implementations force `amsha-core` to pull in 3 Apache Lucene libraries (`lucene-core`, `lucene-queryparser`, `lucene-analysis-common`) used exclusively for in-memory dynamic tool indexing.

### Core Problems
- **Bloated Core Footprint**: Tool implementations and sandbox orchestration add 7,625 lines of code to `amsha-core`.
- **Violated Single Responsibility Principle**: `amsha-core` should be the autonomous reasoning state machine (Thought-Plan-Act-Observe), orchestrating tools through the `AgentTool` contract and `ToolRegistry`, rather than maintaining concrete external tool implementations.
- **Transitive Dependency Pollution**: Lucene and tool-specific libraries inflate the classpath for all downstream modules depending on `amsha-core`.

---

## 2. Target Architecture & Dependency Graph

To prevent cyclic dependencies and maintain strict module boundaries, we adopt a decoupled architecture using SPI ports. Note that `amsha-tools-builtin` and `amsha-mcp` depend on `amsha-sandbox`:

```
                      amsha-common
                           ▲
                           │
                 amsha-persistence-api
                           ▲
                           │
                      amsha-core
           (Contracts, State Machine, ToolRegistry,
          SandboxExecutionPort, ToolSearchPort SPI)
                      ▲         ▲
             ┌────────┴──┐    ┌─┴────────────────────────┐
             │           │    │                          │
     amsha-sandbox       │    │   amsha-tools-builtin    │
   (Podman / Process     │    │  (20+ Built-in Tools,    │
   Isolation Engine,     │◄───┤   Lucene ToolSearchIndex,│
   SandboxExecutionPort) │    │   LocalSocksProxy)       │
        ▲         ▲      │    └─────────────┬────────────┘
        │         └────┐ │ ┌────────────────┘
    amsha-mcp          │ │ │
   (Podman MCP)        │ │ │
        ▲              │ │ │
        └────────────┐ │ │ │
                     amsha-app (Leaf Application Binary)
```

---

## 3. Resolution of Architectural Seams & Blockers

### 1. `CheckListEvent` Boundary Separation
- **Problem**: `CheckListEvent` was declared as `record CheckListEvent(String sessionId, CheckListTool.CheckList checkList)`, creating a direct dependency from core to `CheckListTool`.
- **Solution**:
  1. Extract `CheckList` and `CheckListItem` as independent data records in `com.amsha.core.tools` (or `com.amsha.core.event`).
  2. `CheckListEvent` references the neutral core `CheckList` record.
  3. `CheckListTool` in `amsha-tools-builtin` adapts to and uses the core `CheckList` records with zero core-to-tool dependencies.

### 2. `amsha-mcp` Dependency on `SandboxCircuitBreaker`
- **Problem**: `PodmanMcpTransport` in `amsha-mcp` directly injects `SandboxCircuitBreaker`.
- **Solution**:
  1. `SandboxCircuitBreaker` moves to `amsha-sandbox` (`com.amsha.sandbox.SandboxCircuitBreaker`).
  2. Add `amsha-sandbox` dependency to `mcp/pom.xml`.
  3. Update `PodmanMcpTransport` imports to reference `com.amsha.sandbox.SandboxCircuitBreaker`, preserving shared application-scoped singleton semantics.

### 3. `SandboxExecutionPort` & `ToolSearchPort` Resolution Contract
- **Problem**: `ProcessIsolationExecutor.executeSandboxed` threw `Exception`, and resolution semantics on missing/multiple beans were unspecified.
- **Solution**:
  1. `SandboxExecutionPort` defines a clean, no-checked-exception signature:
     ```java
     package com.amsha.core.sandbox;

     public interface SandboxExecutionPort {
         SandboxResult executeSandboxed(
                 String sessionId, Path workspaceRoot, List<String> command, Map<String, String> requiredSecrets);
         boolean isAllowInsecureFallback();
     }
     ```
  2. **Absence Handling**:
     - `ToolRegistry` and `SandboxExecTool` inject `Instance<SandboxExecutionPort>`. If unsatisfied (`port.isUnsatisfied()`), calls fail closed returning `SandboxResult.infraFailure("No sandbox execution provider available")`.
     - `ReasoningMessageAssembler` injects `Instance<ToolSearchPort>`. If unsatisfied, returns `List.of()` empty rankings.
  3. **Multiple Implementations**: If `port.isAmbiguous()`, fail deterministically with an `IllegalStateException`.
  4. `ProcessIsolationExecutor` catches internal exceptions and returns `SandboxResult.infraFailure(...)`.

### 4. `GithubToolGuardInterceptor` Decoupling
- Refactor `GithubToolGuardInterceptor` to use `ToolCapability.GITHUB.toolName()` instead of importing `GithubTool.TOOL_NAME`.

### 5. `cron-utils` Retention in Core
- `cron-utils` is retained in `core/pom.xml` because `CronScheduleUtil` in `amsha-core` is required by `ApproveReflex`, `SessionReaper`, and `CheckpointResumeHandler`. It is also added to `tools-builtin/pom.xml` for `ScheduleTaskTool`.

---

## 4. Complete File Inventory & Allocation

### 1. `amsha-sandbox` (Package Root: `com.amsha.sandbox.*`)
- **Executors**: `PodmanContainerExecutor`, `ProcessIsolationExecutor` (implements `SandboxExecutionPort`), `NamespaceExecutor`, `DirectExecutor`
- **Safety & Transports**: `SandboxCircuitBreaker`, `SandboxCodeSafetyScanner`, `SandboxSecretResolver`, `TmpfsSecretTransport`, `SecretTransport`, `SandboxStrategy`
- **Health Checks**: `PodmanHealthCheck`, `PodmanImageHealthCheck` (from `com.amsha.core.health`)
- **Exceptions**: `SandboxImageMissingException`, `PodmanUnavailableException`, `ToolExecutionException`
- **Native Configuration**: `SandboxReflectionConfig`
- **CDI**: `META-INF/beans.xml`

### 2. `amsha-tools-builtin` (Package Root: `com.amsha.tools.builtin.*`)
- **Web Tools**: `BraveWebSearchTool`, `SerperWebSearchTool`, `SmartWebFetchTool`, `LocalSocksProxy`
- **Host & System Tools**: `HostExecTool`, `HostJavaCompiler` (uses `DirectExecutor` & `ToolExecutionException` from `amsha-sandbox`)
- **Git / SCM**: `GithubTool`
- **Scheduling**: `ScheduleTaskTool`, `ManageScheduleTool` (uses `cron-utils`)
- **Checklist**: `CheckListTool`
- **Sandbox Tool**: `SandboxExecTool` (uses `SandboxCodeSafetyScanner` from `amsha-sandbox`), `SandboxVerificationGate`
- **Memory Tools**: `ArchivalMemoryInsertTool`, `ArchivalMemorySearchTool`, `CoreMemoryReadTool`, `CoreMemoryAppendTool`, `CoreMemoryReplaceTool`, `RecallMemorySearchTool`
- **Dynamic Search**: `ToolSearchIndex` (implements `ToolSearchPort` via Lucene)
- **Native Configuration**: `ToolsBuiltinReflectionConfig`
- **CDI**: `META-INF/beans.xml`

### 3. Retained in `amsha-core` (`com.amsha.core.*`)
- **Core SPIs & Governance**: `AgentTool`, `ToolExecutionContext`, `ToolResponse`, `IdempotencyMode`, `ToolCategory`, `ToolRegistry`, `ToolDiscoveryService`, `ToolGovernanceGuard`, `ToolSecretExtractor`, `ToolSchemaProvider`, `ToolCategoryMapper`, `ToolContractExporter`, `DynamicToolSchemaParser`, `VerificationGate`
- **Decoupling Ports**: `SandboxExecutionPort`, `SandboxResult` (`com.amsha.core.sandbox`), `ToolSearchPort`
- **Coordination Tools**: `DelegateToAgentTool`, `DelegationProcessor`, `AgentTaskTool`
- **Shared Utilities & Events**: `CodeLanguageDetector`, `OutputTruncator`, `CheckListEvent`, `CheckList`, `CheckListItem`, `CronScheduleUtil`

---

## 5. Key Invariants & Non-Functional Requirements

1. **CDI Discovery (§15.2, §16.1–16.3)**:
   - `ToolDiscoveryService` discovers all active tools via `@Inject Instance<AgentTool>`. Every tool in `amsha-tools-builtin` is annotated with `@ApplicationScoped` and `@io.quarkus.arc.Unremovable`.
   - `ToolRegistry`, `SandboxExecTool`, and `ReasoningMessageAssembler` inject `Instance<SandboxExecutionPort>` and `Instance<ToolSearchPort>` to prevent `UnsatisfiedResolutionException` in native image mode.
2. **Jandex Indexing & CDI Descriptors (§15.2, §16.3)**:
   - Both `amsha-sandbox` and `amsha-tools-builtin` configure `jandex-maven-plugin` (`make-index`) and include `META-INF/beans.xml`.
3. **Application Dependency Indexing (§16.7)**:
   - Both modules registered under `quarkus.index-dependency` in `app/src/main/resources/application.yaml`.
4. **GraalVM Native Reflection (§6.7 / AGENTS.md §8)**:
   - Centralized `SandboxReflectionConfig` and `ToolsBuiltinReflectionConfig`.
   - Prune migrated tool classes from `CoreReflectionConfig`.
5. **ConfigProperty Governance**:
   - All `@ConfigProperty` entries in `amsha-sandbox` (`amsha.sandbox.*`) and `amsha-tools-builtin` (`amsha.tools.*`, Brave, Serper, GitHub, SOCKS) must be documented in their respective module READMEs and verified against `application.yaml` and `.env.example`.
6. **PMD Complexity Gate**:
   - All classes must maintain NCSS <= 80, cognitive complexity <= 50, class length <= 500 lines.
"""

    spec_content = """# Feature Specification: Extract Built-in Tools and Sandbox Execution from amsha-core

- **Feature Slug**: `extract-tools-and-sandbox`
- **Master ADR**: `Ideas-Pending/Tools/ADR-Tools-Builtin-and-Sandbox-Modularization.md`
- **Status**: **Approved / Ready for Implementation**
- **Date**: 2026-08-20

---

## 1. Summary of Scope & Deliverables

1. **Extract `amsha-sandbox` (`com.amsha.sandbox`)**:
   - Houses Podman container runner (`PodmanContainerExecutor`), process isolation (`ProcessIsolationExecutor`), tmpfs secret mounts (`TmpfsSecretTransport`), code safety scanner (`SandboxCodeSafetyScanner`), circuit breaker (`SandboxCircuitBreaker`), and Podman health checks (`PodmanHealthCheck`, `PodmanImageHealthCheck`).
   - Implements `SandboxExecutionPort` from `amsha-core`.
2. **Extract `amsha-tools-builtin` (`com.amsha.tools.builtin`)**:
   - Houses all 20+ built-in concrete tools (Brave, Serper, SmartWebFetch, HostExec, GithubTool, ScheduleTaskTool, Memory tools, SandboxExecTool) and dynamic Lucene tool search index (`ToolSearchIndex`).
   - Depends on `amsha-sandbox` for `DirectExecutor`, `SandboxCodeSafetyScanner`, and `ToolExecutionException`.
   - Implements `ToolSearchPort` and `AgentTool` SPIs from `amsha-core`.
3. **Decouple `amsha-core`**:
   - Introduces `SandboxExecutionPort` and `ToolSearchPort` with clean fail-closed contracts.
   - Extracts neutral `CheckList` / `CheckListItem` records to sever `CheckListEvent` -> `CheckListTool` coupling.
   - Refactors `ToolRegistry`, `SandboxExecTool`, and `ReasoningMessageAssembler` in Iteration 0 to prevent intermediate build breakage.
   - Strips 3 Apache Lucene dependencies from `core/pom.xml` while preserving `cron-utils`.
4. **Update `amsha-mcp`**:
   - Adds `amsha-sandbox` dependency to `mcp/pom.xml` for `SandboxCircuitBreaker`.
5. **Enforce Architectural Invariants**:
   - ArchUnit boundary rules enforcing strict decoupling.
   - Jandex indexing and application discovery for GraalVM native image builds (§15.2, §16.1–16.3, §16.7).
   - Dedicated `SandboxReflectionConfig` and `ToolsBuiltinReflectionConfig`.

---

## 2. Iteration Plan & Current Status

| Iteration | Issue File | Status | Scope & Deliverables | Completed Checks / Gates |
|---|---|---|---|---|
| **Iteration 0** | [01-core-decoupling-ports-and-archunit.md](issues/01-core-decoupling-ports-and-archunit.md) | **Pending** | Introduce `SandboxExecutionPort` & `ToolSearchPort` in `amsha-core`. Extract neutral `CheckList`/`CheckListItem` records. Refactor `ToolRegistry`, `SandboxExecTool`, `ReasoningMessageAssembler`, and `GithubToolGuardInterceptor`. Update `AgentTool` javadoc. Add ArchUnit rules in `ArchitectureBoundaryTest`. | Reactor test-compile, ArchUnit tests passing |
| **Iteration 1** | [02-create-amsha-sandbox-module.md](issues/02-create-amsha-sandbox-module.md) | **Pending** | Create `amsha-sandbox` module, migrate executors (`PodmanContainerExecutor`, `ProcessIsolationExecutor`, `NamespaceExecutor`), transports, `SandboxCircuitBreaker`, `PodmanHealthCheck`, `SandboxReflectionConfig`, `beans.xml`, and unit tests. Update `mcp/pom.xml` and `PodmanMcpTransport`. | Reactor test-compile (including mcp), sandbox unit tests, PMD 0 violations |
| **Iteration 2** | [03-create-amsha-tools-builtin-module.md](issues/03-create-amsha-tools-builtin-module.md) | **Pending** | Create `amsha-tools-builtin` module, migrate 20+ tool implementations, `ToolSearchIndex`, `ToolsBuiltinReflectionConfig`, `beans.xml`, and unit tests. | Reactor test-compile, tools unit tests, PMD 0 violations |
| **Iteration 3** | [04-decouple-core-dependencies-and-reflection.md](issues/04-decouple-core-dependencies-and-reflection.md) | **Pending** | Remove concrete tool classes, sandbox classes, and Apache Lucene dependencies from `core/pom.xml` (retaining `cron-utils`). Prune `CoreReflectionConfig`. | Clean core build, 0 Lucene dependencies, PMD 0 violations |
| **Iteration 4** | [05-wire-app-and-index-dependencies.md](issues/05-wire-app-and-index-dependencies.md) | **Pending** | Wire `amsha-sandbox` and `amsha-tools-builtin` into root `pom.xml`, `app/pom.xml`, `mcp/pom.xml`, and `application.yaml` (`quarkus.index-dependency`). | Full reactor compile, ArchUnit tests, Spotless |
| **Iteration 5** | [06-graalvm-native-reflection-and-cdi-verification.md](issues/06-graalvm-native-reflection-and-cdi-verification.md) | **Pending** | Verify JVM startup CDI discovery (all built-in tools detected via `Instance<AgentTool>`), build GraalVM native binary, and execute runtime smoke tests. | Tool discovery test, Native build, Local runtime smoke |
| **Iteration 6** | [07-documentation-and-diagram-updates.md](issues/07-documentation-and-diagram-updates.md) | **Pending** | Create module READMEs documenting all `@ConfigProperty` entries, update `Architecture_Invariants.md`, `AMSHA_EXTENSION.md`, and `amsha_module_architecture.svg`. | verify_docs.py (0 errors, 0 warnings), JaCoCo >= 70.00% |
"""

    issues = {
        "01-core-decoupling-ports-and-archunit.md": """# Issue 01: Core Decoupling Ports & ArchUnit Boundary Fences

- **Iteration**: 0
- **Status**: Pending

## Goals & Deliverables
1. **Define `SandboxExecutionPort` in `amsha-core`**:
   - Location: `core/src/main/java/com/amsha/core/sandbox/SandboxExecutionPort.java`.
   - Pure contract for sandboxed execution without concrete Podman/process coupling (no checked exceptions, typed `SandboxResult`).
2. **Define `ToolSearchPort` in `amsha-core`**:
   - Location: `core/src/main/java/com/amsha/core/tools/search/ToolSearchPort.java`.
   - Pure contract for dynamic tool search indexing without Lucene coupling.
3. **Extract Neutral `CheckList` Data Records**:
   - Move `CheckList` and `CheckListItem` out of `CheckListTool` into `com.amsha.core.tools` as independent top-level records.
   - Update `CheckListEvent` to reference the neutral `CheckList` record.
4. **Refactor Core Seams Before Extraction**:
   - Refactor `ToolRegistry` to inject `Instance<SandboxExecutionPort>` (fail closed if unsatisfied).
   - Refactor `SandboxExecTool` to inject `Instance<SandboxExecutionPort>`.
   - Refactor `ReasoningMessageAssembler` to inject `Instance<ToolSearchPort>` (returns empty list if unsatisfied).
   - Refactor `GithubToolGuardInterceptor` to use `ToolCapability.GITHUB.toolName()` instead of `GithubTool.TOOL_NAME`.
   - Update Javadoc in `AgentTool.java` (line 65) to reference `SandboxExecutionPort`.
5. **Add ArchUnit Boundary Rules**:
   - Add rules in `ArchitectureBoundaryTest` verifying `core` does not depend on `amsha-tools-builtin` or `amsha-sandbox`.

## Verification
- `./mvnw test-compile -pl core`
- `./mvnw test -pl core -Dtest="SandboxExecToolTest,SandboxExecToolUnitTest,ToolRegistryTest"`
- `./mvnw test -pl app -Dtest=ArchitectureBoundaryTest`
""",
        "02-create-amsha-sandbox-module.md": """# Issue 02: Create amsha-sandbox Module

- **Iteration**: 1
- **Status**: Pending

## Goals & Deliverables
1. **Create Module `amsha-sandbox` (`sandbox/pom.xml`)**:
   - Dependencies: `amsha-common`, `amsha-core`, `quarkus-arc`, `quarkus-smallrye-health`, `org.jboss.logging`.
   - Configure `jandex-maven-plugin` and `src/main/resources/META-INF/beans.xml`.
2. **Migrate Sandbox Implementation Classes to `com.amsha.sandbox.*`**:
   - `PodmanContainerExecutor`
   - `ProcessIsolationExecutor` (implements `SandboxExecutionPort`)
   - `NamespaceExecutor`
   - `DirectExecutor`
   - `SandboxCircuitBreaker`
   - `SandboxCodeSafetyScanner`
   - `SandboxSecretResolver`
   - `TmpfsSecretTransport`
   - `SecretTransport`
   - `SandboxStrategy`
   - `PodmanHealthCheck`, `PodmanImageHealthCheck` (from `com.amsha.core.health`)
   - Exceptions: `SandboxImageMissingException`, `PodmanUnavailableException`, `ToolExecutionException`
3. **Update `amsha-mcp` Dependency**:
   - Add `amsha-sandbox` dependency in `mcp/pom.xml`.
   - Update `mcp/src/main/java/com/amsha/mcp/transport/PodmanMcpTransport.java` to import `com.amsha.sandbox.SandboxCircuitBreaker`.
4. **Create `SandboxReflectionConfig`**:
   - Location: `sandbox/src/main/java/com/amsha/sandbox/config/SandboxReflectionConfig.java`.
5. **Migrate Unit Tests**:
   - Move sandbox and Podman health check tests from `core/src/test` to `sandbox/src/test`.

## Verification
- `./mvnw test-compile -pl sandbox,mcp`
- `./mvnw test -pl sandbox,mcp`
- `./mvnw -pl sandbox -am pmd:check -DskipTests`
""",
        "03-create-amsha-tools-builtin-module.md": """# Issue 03: Create amsha-tools-builtin Module

- **Iteration**: 2
- **Status**: Pending

## Goals & Deliverables
1. **Create Module `amsha-tools-builtin` (`tools-builtin/pom.xml`)**:
   - Dependencies: `amsha-core`, `amsha-sandbox`, `lucene-core`, `lucene-queryparser`, `lucene-analysis-common`, `cron-utils`, `quarkus-arc`.
   - Configure `jandex-maven-plugin` and `src/main/resources/META-INF/beans.xml`.
2. **Migrate Built-in Concrete Tools to `com.amsha.tools.builtin.*`**:
   - Web Tools: `BraveWebSearchTool`, `SerperWebSearchTool`, `SmartWebFetchTool`, `LocalSocksProxy`
   - Host & Code Tools: `HostExecTool`, `HostJavaCompiler` (uses `DirectExecutor` & `ToolExecutionException` from `amsha-sandbox`)
   - Git / SCM: `GithubTool`
   - Scheduling: `ScheduleTaskTool`, `ManageScheduleTool` (uses `cron-utils`)
   - Checklist: `CheckListTool` (adapts core `CheckList` records)
   - Sandbox: `SandboxExecTool` (uses `SandboxCodeSafetyScanner` from `amsha-sandbox`), `SandboxVerificationGate`
   - Memory Tools: `ArchivalMemoryInsertTool`, `ArchivalMemorySearchTool`, `CoreMemoryReadTool`, `CoreMemoryAppendTool`, `CoreMemoryReplaceTool`, `RecallMemorySearchTool`
   - Dynamic Tool Search: `ToolSearchIndex` (implements `ToolSearchPort` via Lucene)
3. **Create `ToolsBuiltinReflectionConfig`**:
   - Location: `tools-builtin/src/main/java/com/amsha/tools/builtin/config/ToolsBuiltinReflectionConfig.java`.
4. **Migrate Tool Unit Tests**:
   - Move all tool tests from `core/src/test` to `tools-builtin/src/test`.

## Verification
- `./mvnw test-compile -pl tools-builtin`
- `./mvnw test -pl tools-builtin`
- `./mvnw -pl tools-builtin -am pmd:check -DskipTests`
""",
        "04-decouple-core-dependencies-and-reflection.md": """# Issue 04: Clean amsha-core Dependencies and Reflection

- **Iteration**: 3
- **Status**: Pending

## Goals & Deliverables
1. **Clean up `core/pom.xml`**:
   - Remove `lucene-core`, `lucene-queryparser`, and `lucene-analysis-common`.
   - **DO NOT remove `cron-utils`**: `CronScheduleUtil` in `amsha-core` is required by `ApproveReflex`, `SessionReaper`, and `CheckpointResumeHandler`.
2. **Remove Migrated Concrete Tools and Sandbox Classes from `amsha-core`**:
   - Delete migrated tool classes and packages from `core/src/main/java/com/amsha/core/tools/*`.
   - Delete migrated sandbox classes from `core/src/main/java/com/amsha/core/sandbox/*` (leaving `SandboxExecutionPort` and `SandboxResult`).
   - Delete migrated health checks from `core/src/main/java/com/amsha/core/health/Podman*`.
   - Ensure `AgentTool`, `ToolRegistry`, `ToolInvocationGuard`, `CodeLanguageDetector`, `CheckListEvent`, `CheckList`, `CheckListItem`, and `DelegateToAgentTool` remain in `amsha-core`.
3. **Prune `CoreReflectionConfig`**:
   - Remove references to `GithubTool`, `SandboxExecTool`, `ScheduleTaskTool`, `ManageScheduleTool`.
4. **Verify Clean Core Build**:
   - Ensure `amsha-core` compiles cleanly with zero dependencies on `amsha-sandbox` or `amsha-tools-builtin`.

## Verification
- `./mvnw clean test-compile -pl core`
- `./mvnw test -pl core`
- `./mvnw -pl core -am pmd:check -DskipTests`
""",
        "05-wire-app-and-index-dependencies.md": """# Issue 05: Wire amsha-app and Index Dependencies

- **Iteration**: 4
- **Status**: Pending

## Goals & Deliverables
1. **Update Root `pom.xml`**:
   - Add `<module>sandbox</module>` and `<module>tools-builtin</module>`.
   - Add both modules to `<dependencyManagement>`.
2. **Update `app/pom.xml` and `mcp/pom.xml`**:
   - Add dependencies for `amsha-sandbox` and `amsha-tools-builtin` in `app/pom.xml`.
   - Ensure `amsha-sandbox` dependency in `mcp/pom.xml`.
3. **Update `app/src/main/resources/application.yaml`**:
   - Add `sandbox` and `tools-builtin` under `quarkus.index-dependency`.
4. **Verify Reactor Build**:
   - Run full reactor compilation and ArchUnit boundary tests.

## Verification
- `./mvnw clean test-compile -DskipTests`
- `./mvnw test -pl app -Dtest=ArchitectureBoundaryTest`
""",
        "06-graalvm-native-reflection-and-cdi-verification.md": """# Issue 06: GraalVM Native Reflection and CDI Verification

- **Iteration**: 5
- **Status**: Pending

## Goals & Deliverables
1. **Add Tool Discovery Test**:
   - Create test in `amsha-app` verifying that all built-in tools are discovered at runtime via `Instance<AgentTool>`.
2. **Build GraalVM Native Executable**:
   - Run `mise run build:native-local` (or `./mvnw clean package -Dnative -DskipTests`).
3. **Execute Runtime Smoke Validation**:
   - Run `mise run smoke:local-runtime -- native --binary dist/amsha-sqlite`.

## Verification
- Native build completion (0 reflection errors).
- Local runtime smoke tests (6/6 passing).
""",
        "07-documentation-and-diagram-updates.md": """# Issue 07: Documentation and Architecture Diagram Updates

- **Iteration**: 6
- **Status**: Pending

## Goals & Deliverables
1. **Create Module Documentation**:
   - `sandbox/README.md`: Document all `amsha.sandbox.*` configuration properties.
   - `tools-builtin/README.md`: Document all `amsha.tools.*` configuration properties (Brave, Serper, GitHub, SOCKS, etc.).
2. **Verify Configuration Invariant**:
   - Ensure all `@ConfigProperty` entries appear in both `application.yaml` and `.env.example`.
3. **Update Existing Documentation**:
   - `core/README.md`
   - `docs/AMSHA_EXTENSION.md`
   - `docs/Architecture_Invariants.md`
4. **Update Architecture Diagram**:
   - Update `docs/assets/amsha_module_architecture.svg` to reflect 23 modules.
5. **Doc Verification**:
   - `python3 ./scripts/verify_docs.py` (0 errors, 0 warnings).
   - Check JaCoCo coverage gate (`verify_jacoco_coverage.py --minimum 0.70`).

## Verification
- `python3 ./scripts/verify_docs.py`
- `python3 ./scripts/verify_jacoco_coverage.py --minimum 0.70`
"""
    }

    # Write files
    adr_path = REPO_ROOT / "Ideas-Pending/Tools/ADR-Tools-Builtin-and-Sandbox-Modularization.md"
    adr_path.parent.mkdir(parents=True, exist_ok=True)
    adr_path.write_text(adr_content, encoding="utf-8")
    print(f"Generated ADR: {adr_path.relative_to(REPO_ROOT)}")

    spec_path = REPO_ROOT / ".scratch/extract-tools-and-sandbox/spec.md"
    spec_path.parent.mkdir(parents=True, exist_ok=True)
    spec_path.write_text(spec_content, encoding="utf-8")
    print(f"Generated Spec: {spec_path.relative_to(REPO_ROOT)}")

    issues_dir = REPO_ROOT / ".scratch/extract-tools-and-sandbox/issues"
    issues_dir.mkdir(parents=True, exist_ok=True)
    for filename, text in issues.items():
        p = issues_dir / filename
        p.write_text(text, encoding="utf-8")
        print(f"Generated Issue: {p.relative_to(REPO_ROOT)}")

    print("Tools & Sandbox tracker files scaffolded successfully.")


def main():
    parser = argparse.ArgumentParser(description="Scaffold ADRs and .scratch feature trackers.")
    parser.add_argument("--tools", action="store_true", default=True, help="Scaffold Tools & Sandbox modularization tracker")
    args = parser.parse_args()

    if args.tools:
        generate_tools_extraction_tracker()


if __name__ == "__main__":
    main()

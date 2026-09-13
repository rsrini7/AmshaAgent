#!/usr/bin/env python3
"""
Feature Tracker & ADR Scaffolding Utility for Amsha Agent.

This script manages ADRs and .scratch feature trackers, ensuring consistent formatting,
issue breakdown, and compliance with project invariants.

Usage:
  # Generic scaffolding for any new feature:
  ./scripts/scaffold_feature_tracker.py --slug my-feature --title "Feature Title" --iterations 5

  # Synchronize Tools & Sandbox modularization tracker:
  ./scripts/scaffold_feature_tracker.py --tools --force
"""

import argparse
import datetime
import sys
from pathlib import Path

_lib_dir = Path(__file__).resolve().parents[1] / "lib"
if str(_lib_dir) not in sys.path:
    sys.path.insert(0, str(_lib_dir))
from repo_root import get_repo_root
REPO_ROOT = get_repo_root(__file__)


def scaffold_generic_feature(slug: str, title: str, iterations: int, category: str = "Features"):
    """Scaffolds a generic ADR, spec.md, and ordered issue tracker files for any feature."""
    today = datetime.date.today().isoformat()
    slug_clean = slug.strip().lower().replace("_", "-")
    cat_dir = REPO_ROOT / f"docs/adr/{category.lower()}"
    cat_dir.mkdir(parents=True, exist_ok=True)

    adr_path = cat_dir / f"ADR-{slug_clean}.md"
    if not adr_path.exists():
        adr_content = f"""# ADR: {title}

- **Status**: Proposed
- **Date**: {today}
- **Decision Owners**: Amsha Architecture Committee
- **Related Invariants**: `docs/Architecture_Invariants.md`
- **Feature Slug**: `{slug_clean}`
- **Issue Tracker**: `.scratch/{slug_clean}/`

---

## 1. Context and Problem Statement

Describe the motivation, current architectural state, and drivers for this change.

---

## 2. Decision and Target Architecture

Describe the proposed architecture, module relationships, and SPI boundaries.

---

## 3. Invariants and Compliance

- **CDI Discovery**: (§15.2, §16.1–16.3)
- **Application Indexing**: (§16.7)
- **GraalVM Native Reflection**: (AGENTS.md §8)
- **PMD Complexity Gate**: NCSS <= 80, cognitive complexity <= 50.
"""
        adr_path.write_text(adr_content, encoding="utf-8")
        print(f"Created ADR: {adr_path.relative_to(REPO_ROOT)}")
    else:
        print(f"ADR already exists: {adr_path.relative_to(REPO_ROOT)}")

    scratch_dir = REPO_ROOT / f".scratch/{slug_clean}"
    issues_dir = scratch_dir / "issues"
    issues_dir.mkdir(parents=True, exist_ok=True)

    spec_path = scratch_dir / "spec.md"
    if not spec_path.exists():
        table_rows = []
        for i in range(1, iterations + 1):
            num_str = f"{i:02d}"
            table_rows.append(f"| **Iteration {i-1}** | [{num_str}-iteration-{i}.md](issues/{num_str}-iteration-{i}.md) | **Pending** | Iteration {i-1} scope | Standard quality gates |")
        rows_str = "\n".join(table_rows)

        spec_content = f"""# Feature Specification: {title}

- **Feature Slug**: `{slug_clean}`
- **Master ADR**: `docs/adr/{category.lower()}/ADR-{slug_clean}.md`
- **Status**: **In Progress**
- **Date**: {today}

---

## 1. Summary of Scope & Deliverables

1. Scope item 1
2. Scope item 2

---

## 2. Iteration Plan & Current Status

| Iteration | Issue File | Status | Scope & Deliverables | Completed Checks / Gates |
|---|---|---|---|---|
{rows_str}
"""
        spec_path.write_text(spec_content, encoding="utf-8")
        print(f"Created Spec: {spec_path.relative_to(REPO_ROOT)}")
    else:
        print(f"Spec already exists: {spec_path.relative_to(REPO_ROOT)}")

    for i in range(1, iterations + 1):
        num_str = f"{i:02d}"
        prev_num_str = f"{i-1:02d}"
        blocked_by = "None" if i == 1 else f"{prev_num_str}-iteration-{i-1}"
        issue_path = issues_dir / f"{num_str}-iteration-{i}.md"
        if not issue_path.exists():
            issue_content = f"""# Issue {num_str}: Iteration {i-1} for {title}

- **Iteration**: {i-1}
- **Status**: Pending
- **Blocked by**: {blocked_by}

## Goals & Deliverables
1. Deliverable 1
2. Deliverable 2

## Verification
- `python3 ./scripts/fix_fqns.py && ./mvnw spotless:apply -q`
- `./mvnw test-compile`
- `./mvnw pmd:check -DskipTests`
- `./mvnw test`
"""
            issue_path.write_text(issue_content, encoding="utf-8")
            print(f"Created Issue: {issue_path.relative_to(REPO_ROOT)}")

    print(f"Generic feature tracker scaffolded for '{slug_clean}' ({iterations} iterations).")


def generate_tools_extraction_tracker(force: bool = False):
    """Generates the ADR and .scratch tracker files for Tools & Sandbox modularization."""
    adr_content = """# ADR: Extract Built-in Tools and Sandbox Execution from amsha-core

- **Status**: Approved for Implementation
- **Date**: 2026-08-20
- **Decision Owners**: Amsha Architecture Committee
- **Related Invariants**: `docs/Architecture_Invariants.md` §15.1–15.2, §16.1–16.3, §16.6–16.7, §16.9, §16.11; `AGENTS.md` §8
- **Feature Slug**: `extract-tools-and-sandbox`
- **Issue Tracker**: `.scratch/extract-tools-and-sandbox/`

---

## 1. Context and Problem Statement

Following the extraction of `amsha-llm-providers` and `amsha-llm-provider-api`, `amsha-core` continues to host multiple independent subsystems alongside the agent reasoning engine:
1. **Concrete Built-in Agent Tools**: 15 concrete `AgentTool` implementations (Brave, Serper, SmartFetch, HostExec, GithubTool, ScheduleTask, ManageSchedule, CheckListTool, SandboxExecTool, 6 memory tools) plus helper classes.
2. **Container Sandbox & Process Isolation**: 14 files implementing Podman container runners, Linux process isolation, tmpfs mount drivers, code safety scanning, secret transports, and Podman health checks.
3. **Heavyweight Dependencies**: Concrete tool implementations force `amsha-core` to pull in 3 Apache Lucene libraries (`lucene-core`, `lucene-queryparser`, `lucene-analysis-common`) used exclusively for in-memory dynamic tool indexing.

### Core Problems
- **Bloated Core Footprint**: Tool implementations and sandbox orchestration add 7,625 lines of code to `amsha-core`.
- **Violated Single Responsibility Principle**: `amsha-core` should be the autonomous reasoning state machine (Thought-Plan-Act-Observe), orchestrating tools through the `AgentTool` contract and `ToolRegistry`, rather than maintaining concrete external tool implementations.
- **Transitive Dependency Pollution**: Lucene and tool-specific libraries inflate the classpath for all downstream modules depending on `amsha-core`.

---

## 2. Target Architecture & Dependency Graph

To prevent cyclic dependencies and maintain strict module boundaries, we adopt a decoupled architecture using SPI ports. Note that `amsha-tools-builtin` and `amsha-mcp` depend directly on `amsha-sandbox`:

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
   (Podman / Process     │    │  (15 Built-in Tools,     │
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

## 3. Resolution of Architectural Seams & Standards Compliance

### 1. `CheckListEvent` Boundary Separation & Wire Compatibility
- **Problem**: `CheckListEvent` was declared as `record CheckListEvent(String sessionId, CheckListTool.CheckList checkList)`, creating a direct dependency from core to `CheckListTool`.
- **Solution**:
  1. Extract `CheckListStatus` (enum with exact lowercase wire-format names: `pending`, `in_progress`, `completed`), `CheckListItem` (record: `(String content, CheckListStatus status, String activeForm)`), and `CheckList` (record with defensive `List.copyOf` compact constructor) into `com.amsha.core.tools`.
  2. `CheckListEvent` references `com.amsha.core.tools.CheckList`.
  3. `CheckListTool` in `amsha-tools-builtin` adapts to and uses the core `CheckList` records with zero core-to-tool dependencies, preserving 100% wire-format compatibility.
  4. Added `CheckListWireCompatibilityTest` in `amsha-core` asserting bidirectional JSON serialization matches legacy schema.

### 2. `amsha-mcp` Dependency on `SandboxCircuitBreaker`
- **Problem**: `PodmanMcpTransport` and its test in `amsha-mcp` directly injected `com.amsha.core.sandbox.SandboxCircuitBreaker`.
- **Solution**:
  1. `SandboxCircuitBreaker` moves to `amsha-sandbox` (`com.amsha.sandbox.SandboxCircuitBreaker`).
  2. Add `amsha-sandbox` dependency to `mcp/pom.xml`.
  3. Update `PodmanMcpTransport` and `PodmanMcpTransportTest` imports to reference `com.amsha.sandbox.SandboxCircuitBreaker`, preserving shared application-scoped singleton semantics.

### 3. SPI Resolution via BeanManager & Minimal Contracts (§15.2, §16.1–16.3, AGENTS.md:121)
- **Problem**: Optional SPI resolution in native image must comply with repository CDI guidance.
- **Solution**:
  1. `SandboxExecutionPort` defines a clean, minimal signature:
     ```java
     package com.amsha.core.sandbox;

     import java.nio.file.Path;
     import java.util.List;
     import java.util.Map;

     public interface SandboxExecutionPort {
         SandboxResult executeSandboxed(
                 String sessionId, Path workspaceRoot, List<String> command, Map<String, String> requiredSecrets);
     }
     ```
  2. `ToolSearchPort` defines a clean search & readiness contract:
     ```java
     package com.amsha.core.tools.search;

     import java.util.List;

     public interface ToolSearchPort {
         List<String> search(String query, int maxResults);
         boolean isReady();
     }
     ```
  3. **Resolution Algorithm**:
     - Optional SPI resolution MUST enumerate `BeanManager.getBeans(Object.class)`, retain only active beans whose bean types contain the requested SPI, and resolve each selected bean with `BeanManager.getReference()`.
     - Direct typed injection and direct `getBeans(SPI.class)` lookup are forbidden for these optional ports because they can fail closed during native augmentation before the absence policy is applied.
     - Count == 0: Allowed and represented as an unavailable optional provider.
     - Count == 1: Resolved and active.
     - Count > 1: Throws deterministic `IllegalStateException("Multiple implementations detected for SPI ...: <sorted implementation names>")`; implementation names are sorted by fully qualified class name.
     - Alternatives, disabled providers, and shadow beans are excluded from the active count; the cardinality test must exercise those cases where applicable.
     - `CoreSpiResolutionValidator` (`@Startup @ApplicationScoped`) verifies SPI bean cardinality at bootstrap, while call sites use the same resolver and never duplicate lookup logic.
     - Provider implementations are annotated with `@ApplicationScoped` and `@io.quarkus.arc.Unremovable`. `ProcessIsolationExecutor` is also annotated with `@Startup`.
  4. **Call-Site Handling & Security-Compliant Sanitization (AGENTS.md:126)**:
     - A core-owned `SandboxFailureMessages.SANDBOX_OFFLINE_MESSAGE` is the single stable user-facing message. `ToolRegistry` and `SandboxExecTool` resolve `SandboxExecutionPort`; absence and every provider `infraFailure` map to `ToolResponse.infraFailure(SANDBOX_OFFLINE_MESSAGE)`.
     - `ReasoningMessageAssembler` resolves `ToolSearchPort`. If unsatisfied or indexing is degraded, returns `List.of()` empty rankings.
     - Raw provider reasons are logged internally with `LogUtil.rootMessage(e)` only after redaction; command lines, secrets, process output, stack traces, and Podman diagnostics are NEVER echoed to logs or LLM responses. Tests assert that a raw infra reason cannot appear in either response path.

### 4. Discovery / Search Lifecycle & Deterministic Sorting
- **Problem**: `ToolSearchIndex.@PostConstruct` rebuilt before `ToolRegistry` discovery finished, and `ToolRegistry` had duplicate `@PostConstruct` and `@Observes StartupEvent` discovery paths.
- **Solution**:
  1. `ToolRegistry` is annotated with `@Startup @ApplicationScoped`. It removes the duplicate startup observer, executes single-shot discovery in `@PostConstruct`, and fires a synchronous CDI event only after `ToolDiscoveryService` has finalized one canonical, name-sorted `LinkedHashMap` snapshot for tools, descriptors, and secrets.
  2. `ToolSearchIndex` (`@ApplicationScoped @Unremovable`) in `amsha-tools-builtin` observes `@Observes ToolDiscoveryCompleted` and builds the Lucene index deterministically after all tools are registered. Search results are sorted by score descending, then tool name ascending; the test must force equal-score ties.
  3. If Lucene indexing fails, it logs `LogUtil.rootMessage(e)`, sets `isReady() == false`, and `search()` returns `List.of()` without crashing bootstrap.
  4. Dynamic MCP tools discovered at runtime via `DynamicToolCapabilityRegistry.listTools()` are maintained outside the static Lucene index.

### 5. `GithubToolGuardInterceptor` Decoupling
- Refactor `GithubToolGuardInterceptor` to use `ToolCapability.GITHUB.toolName()` instead of importing `GithubTool.TOOL_NAME`.

### 6. `cron-utils` Retention in Core
- `cron-utils` is retained in `core/pom.xml` because `CronScheduleUtil` in `amsha-core` is required by `ApproveReflex`, `SessionReaper`, and `CheckpointResumeHandler`. It is also added to `tools-builtin/pom.xml` for `ScheduleTaskTool`.

### 7. Sandbox Image Precedence & Resolver
- Add `SandboxImageResolver` in `amsha-sandbox`:
  1. Environment variable `AMSHA_SANDBOX_IMAGE` (if set and not blank).
  2. Config property `amsha.sandbox.image` (if set and not blank).
  3. Environment variable `AMSHA_SANDBOX_PODMAN_IMAGE` (if set and not blank).
  4. Config property `amsha.sandbox.podman-image` (if set and not blank).
  5. Default: `"localhost/amsha-tool-runner:latest"`.
- The custom runner default is intentional: `sandbox/Dockerfile.toolrunner` supplies the Java, Go, Node, Python, GitHub CLI, and non-root `sandbox` user required by the built-in execution commands. Any replacement image requires an isolated end-to-end command matrix proving equivalent capabilities.
- `PodmanContainerExecutor` and `SandboxValidator` both use `SandboxImageResolver`.

---

## 4. Complete Tool Categorization & File Manifest

### Tool Manifest Breakdown (Aligned with `ToolCapability`)
1. **Extracted Built-in Tools (15 `AgentTool` beans in `amsha-tools-builtin`, all `@Unremovable`)**:
   - `BraveWebSearchTool` (`ToolCapability.WEB_SEARCH_BRAVE` -> `"brave_web_search"`)
   - `SerperWebSearchTool` (`ToolCapability.WEB_SEARCH_SERPER` -> `"serper_web_search"`)
   - `SmartWebFetchTool` (`ToolCapability.WEB_FETCH` -> `"web_fetch"`)
   - `HostExecTool` (`ToolCapability.HOST_EXEC` -> `"host_exec"`)
   - `GithubTool` (`ToolCapability.GITHUB` -> `"github"`)
   - `ScheduleTaskTool` (`ToolCapability.SCHEDULE_TASK` -> `"schedule_task"`)
   - `ManageScheduleTool` (`ToolCapability.MANAGE_SCHEDULE` -> `"manage_schedule"`)
   - `CheckListTool` (`ToolCapability.MANAGE_CHECKLIST` -> `"manage_checklist"`)
   - `SandboxExecTool` (`ToolCapability.SANDBOX_EXEC` -> `"sandbox_exec"`)
   - `ArchivalMemoryInsertTool` (`ToolCapability.ARCHIVAL_MEMORY_INSERT` -> `"archival_memory_insert"`)
   - `ArchivalMemorySearchTool` (`ToolCapability.ARCHIVAL_MEMORY_SEARCH` -> `"archival_memory_search"`)
   - `CoreMemoryAppendTool` (`ToolCapability.CORE_MEMORY_APPEND` -> `"core_memory_append"`)
   - `CoreMemoryReadTool` (`ToolCapability.CORE_MEMORY_READ` -> `"core_memory_read"`)
   - `CoreMemoryReplaceTool` (`ToolCapability.CORE_MEMORY_REPLACE` -> `"core_memory_replace"`)
   - `RecallMemorySearchTool` (`ToolCapability.RECALL_MEMORY_SEARCH` -> `"recall_memory_search"`)
2. **Retained Coordination Tools (2 `AgentTool` beans in `amsha-core`)**:
   - `DelegateToAgentTool` (`ToolCapability.DELEGATE_TO_AGENT` -> `"garuda"`)
   - `AgentTaskTool` (`ToolCapability.AGENT_TASK` -> `"agent_task"`)
3. **Module-Owned External Tools (3 `AgentTool` beans)**:
   - `mcp`: `McpSelfRegisterTool` (`ToolCapability.REGISTER_MCP_SERVER` -> `"register_mcp_server"`)
   - `rag`: `IngestDocumentTool` (`ToolCapability.INGEST_DOCUMENT` -> `"ingest_document"`)
   - `self-evolve`: `SelfEvolveReflectTool` (`ToolCapability.SELF_EVOLVE_REFLECT` -> `"self_evolve_reflect"`) (indexed via `quarkus.index-dependency` in `application.yaml`)
    - Playwright browser automation lives in `tools-builtin` (`PlaywrightCliTool`, `ToolCapability.BROWSER_NAVIGATE`; merged from the former tool-playwright module)
- **Total Raw CDI Tool Beans**: 21 static beans discovered across all modules.

---

### Module Allocation Manifest

#### 1. `amsha-sandbox` (Package Root: `com.amsha.sandbox.*`)
- **Executors**: `PodmanContainerExecutor`, `ProcessIsolationExecutor` (implements `SandboxExecutionPort`, `@Startup @ApplicationScoped @Unremovable`), `NamespaceExecutor`, `DirectExecutor`
- **Safety & Transports**: `SandboxCircuitBreaker` (`@ApplicationScoped @Unremovable`), `SandboxCodeSafetyScanner`, `SandboxSecretResolver`, `TmpfsSecretTransport`, `SecretTransport`, `SandboxStrategy`, `SandboxImageResolver`
- **Health Checks**: `com.amsha.sandbox.PodmanHealthCheck`, `com.amsha.sandbox.PodmanImageHealthCheck`
- **Exceptions**: `SandboxImageMissingException`, `PodmanUnavailableException`, `ToolExecutionException`
- **Native Configuration**: `SandboxReflectionConfig`
- **CDI & Descriptors**: `META-INF/beans.xml` (Jakarta EE 10, version 4.0, annotated discovery), `jandex-maven-plugin` (`make-index`), Surefire `<forkCount>1</forkCount>`, `<reuseForks>true</reuseForks>`, `<forkedProcessExitTimeoutInSeconds>10</forkedProcessExitTimeoutInSeconds>`

#### 2. `amsha-tools-builtin` (Package Root: `com.amsha.tools.builtin.*`)
- **Web Tools**: `BraveWebSearchTool`, `SerperWebSearchTool`, `SmartWebFetchTool`, `LocalSocksProxy`
- **Host & System Tools**: `HostExecTool`, `HostJavaCompiler` (uses `DirectExecutor` & `ToolExecutionException` from `amsha-sandbox`)
- **Git / SCM**: `GithubTool`
- **Scheduling**: `ScheduleTaskTool`, `ManageScheduleTool` (uses `cron-utils`)
- **Checklist**: `CheckListTool` (adapts core `CheckList` records)
- **Sandbox Tool**: `SandboxExecTool` (uses `SandboxCodeSafetyScanner` from `amsha-sandbox`), `SandboxVerificationGate`
- **Memory Tools**: `ArchivalMemoryInsertTool`, `ArchivalMemorySearchTool`, `CoreMemoryReadTool`, `CoreMemoryAppendTool`, `CoreMemoryReplaceTool`, `RecallMemorySearchTool`
- **Dynamic Search**: `com.amsha.tools.builtin.ToolSearchIndex` (implements `ToolSearchPort` via Lucene, observes `ToolDiscoveryCompleted`)
- **Native Configuration**: `ToolsBuiltinReflectionConfig`
- **CDI & Descriptors**: `META-INF/beans.xml` (Jakarta EE 10, version 4.0, annotated discovery), `jandex-maven-plugin` (`make-index`), Surefire `<forkCount>1</forkCount>`, `<reuseForks>true</reuseForks>`, `<forkedProcessExitTimeoutInSeconds>10</forkedProcessExitTimeoutInSeconds>`

#### 3. Retained in `amsha-core` (`com.amsha.core.*`)
- **Core SPIs & Governance**: `AgentTool`, `ToolExecutionContext`, `ToolResponse`, `IdempotencyMode`, `ToolCategory`, `ToolRegistry`, `ToolDiscoveryService`, `ToolGovernanceGuard`, `ToolSecretExtractor`, `ToolSchemaProvider`, `ToolCategoryMapper`, `ToolContractExporter`, `DynamicToolSchemaParser`, `VerificationGate`
- **Decoupling Ports & Lifecycle**: `SandboxExecutionPort`, `SandboxResult` (`com.amsha.core.sandbox`), `ToolSearchPort`, `CoreSpiResolutionValidator`, `ToolDiscoveryCompleted`
- **Coordination Tools**: `DelegateToAgentTool`, `DelegationProcessor`, `AgentTaskTool`
- **Shared Utilities & Events**: `CodeLanguageDetector`, `OutputTruncator`, `CheckListEvent`, `CheckList`, `CheckListItem`, `CheckListStatus` (in `com.amsha.core.tools`), `CronScheduleUtil`

---

### Exact migration and deletion manifest

The extraction MUST use this file-level manifest. No wildcard deletion or package-wide move is permitted.

- **Move to `tools-builtin` (19 core production files)**: `core/src/main/java/com/amsha/core/tools/BraveWebSearchTool.java`, `SerperWebSearchTool.java`, `SmartWebFetchTool.java`, `HostExecTool.java`, `HostJavaCompiler.java`, `LocalSocksProxy.java`, `GithubTool.java`, `ScheduleTaskTool.java`, `ManageScheduleTool.java`, `CheckListTool.java`, `SandboxExecTool.java`, `SandboxVerificationGate.java`, `memory/ArchivalMemoryInsertTool.java`, `memory/ArchivalMemorySearchTool.java`, `memory/CoreMemoryAppendTool.java`, `memory/CoreMemoryReadTool.java`, `memory/CoreMemoryReplaceTool.java`, `memory/RecallMemorySearchTool.java`, and `ToolSearchIndex.java`.
- **Move to `sandbox` (13 core sandbox production files)**: `DirectExecutor.java`, `NamespaceExecutor.java`, `PodmanContainerExecutor.java`, `PodmanUnavailableException.java`, `ProcessIsolationExecutor.java`, `SandboxCircuitBreaker.java`, `SandboxCodeSafetyScanner.java`, `SandboxImageMissingException.java`, `SandboxSecretResolver.java`, `SandboxStrategy.java`, `SecretTransport.java`, `TmpfsSecretTransport.java`, and `ToolExecutionException.java` from `core/src/main/java/com/amsha/core/sandbox/`.
- **Move to `sandbox` package**: `core/src/main/java/com/amsha/core/health/PodmanHealthCheck.java` and `PodmanImageHealthCheck.java` become `com.amsha.sandbox.*`.
- **Retain in core**: `SandboxExecutionPort.java`, `SandboxResult.java`, and `SandboxFailureMessages.java`; no implementation may remain in `com.amsha.core.sandbox` after Iteration 3.
- **Tests**: the Issue 02 and Issue 03 test lists are the complete migration matrix. Every moved implementation test changes module and package together; registry/SPI/ArchUnit tests remain in core/app as listed in Issue 04.

### Native reflection inventory

- `CoreReflectionConfig.targets` retains `SandboxResult.class`, `CheckListEvent.class`, `CheckList.class`, `CheckListItem.class`, and `CheckListStatus.class`; migrated tool classes are removed from core imports and targets.
- `ToolsBuiltinReflectionConfig.targets` registers the 15 built-in tool classes and any nested JSON DTO/record types used by their public schemas. `SandboxReflectionConfig.targets` registers sandbox DTOs/exceptions only when they are reflectively serialized; ordinary CDI beans are not registered speculatively.
- The Lucene `classNames` inventory is exact: `org.apache.lucene.index.ConcurrentMergeScheduler`, `org.apache.lucene.index.SegmentReader`, `org.apache.lucene.index.IndexWriter`, and `org.apache.lucene.store.FilterIndexInput`, with declared constructors and public methods enabled as required by §6.9.
- The full-package runtime-init flag lives in the `app` native-image metadata `native-image.properties` and preserves existing arguments while adding `--initialize-at-run-time=org.apache.lucene`.

### Acceptance evidence

- Startup logs must prove `ToolRegistry` discovery, `ToolSearchIndex` index readiness, `CoreSpiResolutionValidator` cardinality, and the sandbox provider bean after app wiring. Missing log evidence fails the gate even if isolated tests pass.
- The raw `AgentTool` manifest is exactly 21 names; active manifests use named test profiles with explicit configuration and expected sets. A provider `infraFailure` test proves the sanitized message is stable and excludes its raw reason.
- The old-package scan covers Java, YAML/YML, JSON reflection configuration, native properties, and tests. It scans both package declarations/paths and fully qualified references. The only permitted `com.amsha.core.sandbox` types are `SandboxExecutionPort`, `SandboxResult`, and `SandboxFailureMessages`.

## 5. Key Invariants & Non-Functional Requirements

1. **CDI Discovery (§15.1–15.2, §16.1–16.3, AGENTS.md:121)**:
   - `ToolDiscoveryService` discovers all active tools through the BeanManager, resolves each bean with `getReference()`, filters disabled tools, and publishes one name-sorted immutable snapshot. Every dynamically discovered tool is annotated with `@ApplicationScoped` and `@io.quarkus.arc.Unremovable`.
   - `ToolRegistry`, `SandboxExecTool`, and `ReasoningMessageAssembler` use the shared Object-class BeanManager resolver for optional ports; no typed optional injection remains.
2. **Jandex Indexing & CDI Descriptors (§15.2, §16.3)**:
   - Both `amsha-sandbox` and `amsha-tools-builtin` configure `jandex-maven-plugin` (`make-index`) and include Jakarta EE 10 version 4.0 `META-INF/beans.xml` with `bean-discovery-mode="annotated"`.
   - Each module declares `src/test/resources/application.yaml` with the §16.2 and §16.6 dummy values, disabled MCP transports, disabled Hibernate ORM, and shutdown timeout settings.
3. **Application Dependency Indexing (§16.7)**:
   - `amsha-sandbox`, `amsha-tools-builtin`, and `amsha-self-evolve` are registered under `quarkus.index-dependency` with explicit `group-id: com.amsha` and matching artifact IDs in `app/src/main/resources/application.yaml`.
4. **GraalVM Native Reflection (§6.7 / AGENTS.md §8)**:
   - Centralized `SandboxReflectionConfig` and `ToolsBuiltinReflectionConfig`.
   - Apply the exact target/class-name inventory above and prune migrated tool classes from `CoreReflectionConfig`.
   - Full package runtime initialization for Lucene: `--initialize-at-run-time=org.apache.lucene`.
   - Register the four Lucene `TestSecrets` classes by class name; a native build is not sufficient without the runtime startup smoke assertion.
5. **ConfigProperty Governance**:
   - All `@ConfigProperty` entries in `amsha-sandbox` (`amsha.sandbox.*`) and `amsha-tools-builtin` (`amsha.tools.*`, Brave, Serper, GitHub, SOCKS) must be documented in their respective module READMEs and verified against `application.yaml` and `.env.example`.
   - Standardize on `amsha.sandbox.image` with fallback `amsha.sandbox.podman-image`, preserving `localhost/amsha-tool-runner:latest` as the default.
   - Document shared core properties `amsha.workspace.root-dir` and `amsha.data-dir`.
   - Add the new sandbox/image settings to `BinaryBootstrap` and `CliUtils.getMissingVarSpecs()` where they are user-configurable.
6. **PMD Complexity Gate**:
   - All classes must maintain NCSS <= 80, cognitive complexity <= 50, class length <= 500 lines.
"""

    spec_content = """# Feature Specification: Extract Built-in Tools and Sandbox Execution from amsha-core

- **Feature Slug**: `extract-tools-and-sandbox`
- **Master ADR**: `docs/adr/tools/ADR-Tools-Builtin-and-Sandbox-Modularization.md`
- **Status**: **Approved / Ready for Implementation**
- **Date**: 2026-08-20

---

## 1. Summary of Scope & Deliverables

1. **Extract `amsha-sandbox` (`com.amsha.sandbox`)**:
   - Houses Podman container runner (`PodmanContainerExecutor`), process isolation (`ProcessIsolationExecutor`), tmpfs secret mounts (`TmpfsSecretTransport`), code safety scanner (`SandboxCodeSafetyScanner`), circuit breaker (`SandboxCircuitBreaker`), image resolver (`SandboxImageResolver`), and Podman health checks (`PodmanHealthCheck`, `PodmanImageHealthCheck`).
   - Implements `SandboxExecutionPort` from `amsha-core`.
2. **Extract `amsha-tools-builtin` (`com.amsha.tools.builtin`)**:
   - Houses 15 built-in concrete tools (Brave, Serper, SmartWebFetch, HostExec, GithubTool, ScheduleTaskTool, ManageScheduleTool, CheckListTool, SandboxExecTool, 6 memory tools) and dynamic Lucene tool search index (`ToolSearchIndex`).
   - Depends on `amsha-sandbox` for `DirectExecutor`, `SandboxCodeSafetyScanner`, and `ToolExecutionException`.
   - Implements `ToolSearchPort` and `AgentTool` SPIs from `amsha-core`.
3. **Decouple `amsha-core`**:
   - Introduces `SandboxExecutionPort`, `ToolSearchPort`, `CoreSpiResolutionValidator`, and `ToolDiscoveryCompleted`.
   - Extracts neutral `CheckList` / `CheckListItem` / `CheckListStatus` records in `com.amsha.core.tools` to sever `CheckListEvent` -> `CheckListTool` coupling with 100% wire-format compatibility.
   - Refactors `ToolRegistry`, `SandboxExecTool`, and `ReasoningMessageAssembler` in Iteration 0 to prevent intermediate build breakage.
   - Strips 3 Apache Lucene dependencies from `core/pom.xml` while preserving `cron-utils`.
4. **Update `amsha-mcp`**:
   - Adds `amsha-sandbox` dependency to `mcp/pom.xml` for `SandboxCircuitBreaker`.
5. **Enforce Architectural Invariants**:
   - ArchUnit boundary rules enforcing strict decoupling.
   - Jandex indexing and application discovery for GraalVM native image builds (§15.2, §16.1–16.3, §16.7).
   - Dedicated `SandboxReflectionConfig` and `ToolsBuiltinReflectionConfig`.

---

## 2. Tool Population and Manifest Contract

The tracker distinguishes CDI discovery from the active registry. CDI must expose
exactly these 21 static `AgentTool` beans after application indexing:

- **Built-in module (15)**: `brave_web_search`, `serper_web_search`, `web_fetch`,
  `host_exec`, `github`, `schedule_task`, `manage_schedule`, `manage_checklist`,
  `sandbox_exec`, `archival_memory_insert`, `archival_memory_search`,
  `core_memory_append`, `core_memory_read`, `core_memory_replace`,
  `recall_memory_search`.
- **Core coordination (2)**: `garuda`, `agent_task`.
- **Module-owned (4)**: `register_mcp_server`, `ingest_document`,
  `self_evolve_reflect`, `browser_navigate`.

`ToolRegistry` publishes the active manifest only after evaluating each tool's
`isEnabled()` policy. Every acceptance profile must declare its configuration and
its complete expected active-name set, then compare both set equality and
name-sorted order. The `all-enabled` profile is expected to match all 21 names;
credentialless or feature-disabled profiles must assert their own explicit
subsets rather than relying on a count. Runtime MCP tools remain a separate
dynamic manifest and must never inflate the static CDI count.

## 3. SPI, Startup, and Failure Contract

- `SandboxExecutionPort` and `ToolSearchPort` are optional SPIs. A shared
  `BeanManager.getBeans(Object.class)` resolver filters active bean types and
  obtains the provider through `getReference()`; zero providers is valid, one is
  valid, and more than one fails deterministically at bootstrap with sorted
  implementation names.
- `CoreSpiResolutionValidator` and all call sites use the same resolution
  semantics. Tests must cover 0/1/>1 providers, alternatives, disabled providers,
  and shadow beans where applicable.
- `ToolRegistry` has one synchronous startup discovery path, publishes
  `ToolDiscoveryCompleted` after its immutable, name-sorted snapshot is final,
  and `ToolSearchIndex` builds only in response to that event. Index failure keeps
  bootstrap alive, sets `isReady()` false, and produces an empty ranking.
- Missing or infrastructure-failed sandbox execution maps to the stable,
  sanitized message `Sandbox execution environment is currently unavailable.`
  Raw reasons, commands, secrets, process output, and stack traces must not reach
  an LLM response; tests must assert this boundary.

## 4. Sandbox Image Resolution and Configuration

`SandboxImageResolver` uses the first non-blank value in this order:

1. `AMSHA_SANDBOX_IMAGE`
2. `amsha.sandbox.image`
3. `AMSHA_SANDBOX_PODMAN_IMAGE`
4. `amsha.sandbox.podman-image`
5. `localhost/amsha-tool-runner:latest`

The custom default is required because `sandbox/Dockerfile.toolrunner` supplies
the command runtimes and non-root `sandbox` user used by built-in execution. The
new image settings must also be represented in the distribution bootstrap and
`CliUtils.getMissingVarSpecs()` documentation when user-configurable.

## 5. Iteration Plan & Current Status

| Iteration | Issue File | Status | Scope & Deliverables | Required Evidence / Gates |
|---|---|---|---|---|
| **Iteration 0** | [01-core-decoupling-ports-and-archunit.md](issues/01-core-decoupling-ports-and-archunit.md) | **Pending** | Introduce `SandboxExecutionPort` & `ToolSearchPort`, `CoreSpiResolutionValidator`, and `ToolDiscoveryCompleted` in `amsha-core`. Extract neutral `CheckList`/`CheckListItem`/`CheckListStatus` records with wire compatibility test in `com.amsha.core.tools`. Refactor `ToolRegistry`, `SandboxExecTool`, `ReasoningMessageAssembler`, and `GithubToolGuardInterceptor`. Add ArchUnit rules in `ArchitectureBoundaryTest`. | Required test-compile, focused core tests, SPI resolver tests, and ArchUnit evidence; no pass is pre-claimed. |
| **Iteration 1** | [02-create-amsha-sandbox-module.md](issues/02-create-amsha-sandbox-module.md) | **Pending** | Add `sandbox` to root `pom.xml`, create `amsha-sandbox` module, migrate executors (`PodmanContainerExecutor`, `ProcessIsolationExecutor`, `NamespaceExecutor`), transports, `SandboxCircuitBreaker`, `SandboxImageResolver`, `PodmanHealthCheck`, `SandboxReflectionConfig`, `beans.xml`, test YAML/Surefire config, and unit tests. Delete migrated sandbox classes from `amsha-core`. Update `mcp/pom.xml`, `PodmanMcpTransport`, `PodmanMcpTransportTest`, and `SandboxValidator`. | Reactor test-compile, sandbox and MCP tests, and module-scoped PMD evidence. |
| **Iteration 2** | [03-create-amsha-tools-builtin-module.md](issues/03-create-amsha-tools-builtin-module.md) | **Pending** | Add `tools-builtin` to root `pom.xml`, create `amsha-tools-builtin` module, declare complete direct dependencies, test YAML/Surefire config, migrate 15 tool implementations (`@Unremovable`), `ToolSearchIndex` (observing `ToolDiscoveryCompleted`), `ToolsBuiltinReflectionConfig`, `beans.xml`, and unit tests. Delete migrated tools and Lucene dependencies from `amsha-core`. | Reactor test-compile, built-in-tool tests, and module-scoped PMD evidence. |
| **Iteration 3** | [04-decouple-core-dependencies-and-reflection.md](issues/04-decouple-core-dependencies-and-reflection.md) | **Pending** | Prune `CoreReflectionConfig`, update all retained core tests (`ToolRegistryTest`, `ReasoningMessageAssemblerTest`, `GithubToolGuardInterceptorTest`), verify zero-external-tool boundary. | Clean core build, zero Lucene dependencies, explicit ownership-manifest scan, and PMD evidence. |
| **Iteration 4** | [05-wire-app-and-index-dependencies.md](issues/05-wire-app-and-index-dependencies.md) | **Pending** | Wire `amsha-sandbox` and `amsha-tools-builtin` into `app/pom.xml` and `application.yaml` with explicit Maven coordinates under `quarkus.index-dependency`, register `self-evolve`, and run clean install. | Full reactor compile, clean-install evidence, coordinate validation, ArchUnit tests, and Spotless. |
| **Iteration 5** | [06-graalvm-native-reflection-and-cdi-verification.md](issues/06-graalvm-native-reflection-and-cdi-verification.md) | **Pending** | Verify JVM startup CDI discovery (separate raw-bean, active-registry, and dynamic MCP manifests), isolated SPI 0/1/>1 cardinality tests, startup log markers, reflection inventory, old-package denylist, native build, and runtime smoke tests. | Required startup logs, exact manifests, sanitized-failure tests, native reflection assertions, native build, and local runtime smoke; no result is pre-claimed. |
| **Iteration 6** | [07-documentation-and-diagram-updates.md](issues/07-documentation-and-diagram-updates.md) | **Pending** | Create module READMEs documenting all `@ConfigProperty` entries, update bootstrap/configuration docs, `Architecture_Invariants.md`, `AMSHA_EXTENSION.md`, and `amsha_module_architecture.svg`. | `verify_docs.py`, configuration parity, bootstrap wiring evidence, and JaCoCo threshold evidence. |

## 6. Acceptance Evidence (Required Before Marking Complete)

- Startup logs must contain stable, testable markers proving registry discovery,
  `ToolSearchIndex` readiness, SPI cardinality validation, and the sandbox provider
  bean after the application is wired. A green isolated unit test without these
  logs is insufficient.
- The raw 21-name manifest, each profile's complete active-name manifest, and the
  separate dynamic MCP manifest must be asserted independently.
- Reflection configuration must match the exact inventory in the ADR, including
  the four Lucene `classNames`; native startup must prove both provider and index
  readiness.
- The old-package denylist must scan Java, YAML/YML, JSON reflection metadata,
  native properties, tests, package declarations, source paths, and fully
  qualified references. Only `SandboxExecutionPort` and `SandboxResult` may
  `SandboxExecutionPort`, `SandboxResult`, and `SandboxFailureMessages` may remain under `com.amsha.core.sandbox`.
- Sandbox infrastructure failures must be tested for the stable sanitized message
  and for absence of the raw provider reason in both tool and LLM-facing paths.
- Documentation verification must be run after Markdown changes. This section
  records required evidence only; it does not claim that any gate has passed.
"""

    issues = {
        "01-core-decoupling-ports-and-archunit.md": """# Issue 01: Core Decoupling Ports & ArchUnit Boundary Fences

- **Iteration**: 0
- **Status**: Pending
- **Blocked by**: None

## Goals & Deliverables
1. **Define `SandboxExecutionPort` in `amsha-core`**:
   - Location: `core/src/main/java/com/amsha/core/sandbox/SandboxExecutionPort.java`.
   - Contract: `SandboxResult executeSandboxed(String sessionId, Path workspaceRoot, List<String> command, Map<String, String> requiredSecrets)`.
2. **Define `ToolSearchPort` in `amsha-core`**:
   - Location: `core/src/main/java/com/amsha/core/tools/search/ToolSearchPort.java`.
   - Contract: `List<String> search(String query, int maxResults)` and `boolean isReady()`.
3. **Define Startup Validation & Lifecycle Events in `amsha-core`**:
   - Location: `core/src/main/java/com/amsha/core/config/CoreSpiResolutionValidator.java` (`@Startup @ApplicationScoped` enforcing cardinality via `BeanManager`: 0 allowed, 1 accepted, >1 fails with `IllegalStateException`).
   - Location: `core/src/main/java/com/amsha/core/tools/ToolDiscoveryCompleted.java` (synchronous CDI event fired by `ToolRegistry` with `int discoveredToolCount` after discovery completes).
   - Add startup-log assertions for the validator and discovery event; a unit test alone is not evidence that the application bootstrap path ran.
4. **Extract Neutral `CheckList` Data Records & Wire Test**:
   - Create `CheckListStatus.java` (`pending`, `in_progress`, `completed`), `CheckListItem.java`, and `CheckList.java` (with defensive `List.copyOf`) in `com.amsha.core.tools`.
   - Create `CheckListWireCompatibilityTest.java` verifying exact JSON serialization.
   - Update `CheckListEvent` to reference `com.amsha.core.tools.CheckList`.
   - Preserve the exact legacy JSON field names, enum values, null/empty-list behavior, and defensive-copy semantics in the schema test.
5. **Refactor Core Seams Before Extraction**:
   - Refactor `ToolRegistry` to `@Startup @ApplicationScoped`, make discovery single-shot using `BeanManager`, inject/resolve `SandboxExecutionPort` (fail closed if unsatisfied, return `ToolResponse.infraFailure(SANDBOX_OFFLINE_MESSAGE)` without exposing secrets or raw stack traces).
   - Refactor `SandboxExecTool` to resolve `SandboxExecutionPort`.
   - Refactor `ReasoningMessageAssembler` to resolve `ToolSearchPort` (returns empty list if unsatisfied).
   - Refactor `GithubToolGuardInterceptor` to use `ToolCapability.GITHUB.toolName()` instead of `GithubTool.TOOL_NAME`.
   - Update Javadoc in `AgentTool.java` (line 65) to reference `SandboxExecutionPort`.
   - Create `ToolDiscoveryServiceTest` if it does not already exist. It must prove one ordered immutable snapshot is used for tool names, descriptors, and secrets, and that disabled tools are excluded consistently.
6. **Add ArchUnit Boundary Rules**:
   - Add rules in `ArchitectureBoundaryTest` verifying `core` does not depend on `amsha-tools-builtin` or `amsha-sandbox`.

## Verification
- `python3 ./scripts/fix_fqns.py && ./mvnw spotless:apply -q`
- `./mvnw test-compile -pl core -am`
- `./mvnw -pl core -am pmd:check -DskipTests`
- `./mvnw test -pl core -Dtest="ToolRegistryTest,ToolDiscoveryServiceTest,DynamicToolDiscoveryTest,CheckListWireCompatibilityTest"`
- `./mvnw test -pl app -Dtest=ArchitectureBoundaryTest`
- Capture and retain the startup-log evidence for discovery completion and SPI cardinality; do not mark the issue complete from test output alone.
""",
        "02-create-amsha-sandbox-module.md": """# Issue 02: Create amsha-sandbox Module

- **Iteration**: 1
- **Status**: Pending
- **Blocked by**: 01-core-decoupling-ports-and-archunit.md

## Goals & Deliverables
1. **Update Root `pom.xml`**:
   - Add `<module>sandbox</module>` and `<dependencyManagement>` entry for `amsha-sandbox`.
2. **Create Module `amsha-sandbox` (`sandbox/pom.xml`)**:
   - Direct Dependencies: `amsha-common`, `amsha-core`, `io.opentelemetry:opentelemetry-api`, `io.opentelemetry:opentelemetry-context`, `io.quarkus:quarkus-core`, `io.quarkus:quarkus-arc`, `io.quarkus:quarkus-smallrye-health`, `org.jboss.logging:jboss-logging`, `org.eclipse.microprofile.config:microprofile-config-api`.
   - Test-scoped dependencies are explicit: `io.quarkus:quarkus-junit5`, `io.quarkus:quarkus-junit5-mockito`, `org.junit.jupiter:junit-jupiter`, and `org.mockito:mockito-junit-jupiter`.
   - Configure `jandex-maven-plugin` with execution id `make-index`, Jakarta EE 10 `src/main/resources/META-INF/beans.xml` (`bean-discovery-mode="annotated"`, `version="4.0"`), and `maven-surefire-plugin` with `<forkCount>1</forkCount>`, `<reuseForks>true</reuseForks>`, and `<forkedProcessExitTimeoutInSeconds>10</forkedProcessExitTimeoutInSeconds>`.
   - `src/test/resources/application.yaml` must provide these exact required values: `amsha.vault.pepper: mock-pepper`, `amsha.data-dir: target/data`, `quarkus.langchain4j.openai.timeout: 30s`, `quarkus.hibernate-orm.enabled: false`, `quarkus.transaction-manager.enable-recovery: false`, `quarkiverse.mcp.server.http.enabled: false`, `quarkiverse.mcp.server.stdio.enabled: false`, and `quarkus.shutdown.timeout: 5S`.
3. **Migrate Sandbox Implementation Classes to `com.amsha.sandbox.*`**:
   - `PodmanContainerExecutor`
   - `ProcessIsolationExecutor` (implements `SandboxExecutionPort`, annotated `@Startup @ApplicationScoped @Unremovable`)
   - `NamespaceExecutor`
   - `DirectExecutor`
   - `SandboxCircuitBreaker` (annotated `@ApplicationScoped @Unremovable`)
   - `SandboxCodeSafetyScanner`
   - `SandboxSecretResolver`
   - `TmpfsSecretTransport`
   - `SecretTransport`
   - `SandboxStrategy`
   - `SandboxImageResolver` (resolves `AMSHA_SANDBOX_IMAGE` -> `amsha.sandbox.image` -> `AMSHA_SANDBOX_PODMAN_IMAGE` -> `amsha.sandbox.podman-image` -> `localhost/amsha-tool-runner:latest`; ignores blank values)
   - `PodmanHealthCheck`, `PodmanImageHealthCheck` (from `com.amsha.sandbox`)
   - Exceptions: `SandboxImageMissingException`, `PodmanUnavailableException`, `ToolExecutionException`
4. **Delete Old Sandbox Classes from `amsha-core`** and update consumers:
   - Update `mcp/pom.xml` to depend on `amsha-sandbox`.
   - Update `PodmanMcpTransport.java` and `PodmanMcpTransportTest.java` in `amsha-mcp`.
   - Update `SandboxValidator.java` in `amsha-app` to use `SandboxImageResolver`.
- Update `app/src/main/resources/application.yaml` logging category `com.amsha.sandbox`.
5. **Create `SandboxReflectionConfig`**:
   - Location: `sandbox/src/main/java/com/amsha/sandbox/config/SandboxReflectionConfig.java`.
6. **Migrate Sandbox Unit Tests to `sandbox/src/test`**:
   - `PodmanContainerExecutorTest.java`, `ProcessIsolationExecutorTest.java`, `ProcessIsolationExecutorUnitTest.java`, `SandboxCircuitBreakerTest.java`, `SandboxCodeSafetyScannerTest.java`, `SandboxSecretResolverTest.java`, `TmpfsSecretTransportTest.java`, `PodmanHealthCheckTest.java`, `PodmanImageHealthCheckTest.java`.

## Verification
- `python3 ./scripts/fix_fqns.py && ./mvnw spotless:apply -q`
- `./mvnw test-compile -pl sandbox,mcp -am`
- `./mvnw test -pl sandbox,mcp`
- `./mvnw -pl sandbox,mcp -am pmd:check -DskipTests`
""",
        "03-create-amsha-tools-builtin-module.md": """# Issue 03: Create amsha-tools-builtin Module

- **Iteration**: 2
- **Status**: Pending
- **Blocked by**: 02-create-amsha-sandbox-module.md

## Goals & Deliverables
1. **Update Root `pom.xml`**:
   - Add `<module>tools-builtin</module>` and `<dependencyManagement>` entry for `amsha-tools-builtin`.
2. **Create Module `amsha-tools-builtin` (`tools-builtin/pom.xml`)**:
   - Direct Dependencies:
     - `amsha-core`
     - `amsha-sandbox`
     - `amsha-common`
     - `amsha-persistence-api`
     - `io.quarkus:quarkus-core` (for `LaunchMode`)
     - `dev.langchain4j:langchain4j-core`
     - `com.fasterxml.jackson.core:jackson-databind`
     - `com.fasterxml.jackson.core:jackson-annotations`
     - `org.apache.lucene:lucene-core`
     - `org.apache.lucene:lucene-queryparser`
     - `org.apache.lucene:lucene-analysis-common`
     - `com.cronutils:cron-utils`
     - `io.quarkus:quarkus-arc`
     - `org.jboss.logging:jboss-logging`
     - `org.eclipse.microprofile.config:microprofile-config-api`
   - Test-scoped dependencies are explicit: `io.quarkus:quarkus-junit5`, `io.quarkus:quarkus-junit5-mockito`, `org.junit.jupiter:junit-jupiter`, and `org.mockito:mockito-junit-jupiter`.
   - Configure `jandex-maven-plugin` with execution id `make-index`, Jakarta EE 10 `src/main/resources/META-INF/beans.xml` (`bean-discovery-mode="annotated"`, `version="4.0"`), and `maven-surefire-plugin` with `<forkCount>1</forkCount>`, `<reuseForks>true</reuseForks>`, and `<forkedProcessExitTimeoutInSeconds>10</forkedProcessExitTimeoutInSeconds>`.
   - `src/test/resources/application.yaml` must provide these exact required values: `amsha.vault.pepper: mock-pepper`, `amsha.data-dir: target/data`, `quarkus.langchain4j.openai.timeout: 30s`, `quarkus.hibernate-orm.enabled: false`, `quarkus.transaction-manager.enable-recovery: false`, `quarkiverse.mcp.server.http.enabled: false`, `quarkiverse.mcp.server.stdio.enabled: false`, and `quarkus.shutdown.timeout: 5S`.
3. **Migrate 15 Built-in Concrete Tools to `com.amsha.tools.builtin.*` (All `@ApplicationScoped @Unremovable`)**:
   - Web Tools: `BraveWebSearchTool` (`brave_web_search`), `SerperWebSearchTool` (`serper_web_search`), `SmartWebFetchTool` (`web_fetch`), `LocalSocksProxy`
   - Host & Code Tools: `HostExecTool` (`host_exec`), `HostJavaCompiler` (uses `DirectExecutor` & `ToolExecutionException` from `amsha-sandbox`)
   - Git / SCM: `GithubTool` (`github`)
   - Scheduling: `ScheduleTaskTool` (`schedule_task`), `ManageScheduleTool` (`manage_schedule`) (uses `cron-utils`)
   - Checklist: `CheckListTool` (`manage_checklist`) (adapts core `CheckList` records)
   - Sandbox: `SandboxExecTool` (`sandbox_exec`) (uses `SandboxCodeSafetyScanner` from `amsha-sandbox`), `SandboxVerificationGate`
   - Memory Tools: `ArchivalMemoryInsertTool` (`archival_memory_insert`), `ArchivalMemorySearchTool` (`archival_memory_search`), `CoreMemoryReadTool` (`core_memory_read`), `CoreMemoryAppendTool` (`core_memory_append`), `CoreMemoryReplaceTool` (`core_memory_replace`), `RecallMemorySearchTool` (`recall_memory_search`)
   - Dynamic Tool Search: `ToolSearchIndex` (implements `ToolSearchPort`, observes `ToolDiscoveryCompleted`, sorts descriptors deterministically)
4. **Delete Old Built-in Tool Classes, Reflection References, and Lucene Dependencies from `amsha-core`**:
   - Prune tool references from `CoreReflectionConfig.java`.
   - Remove `lucene-core`, `lucene-queryparser`, and `lucene-analysis-common` from `core/pom.xml`.
5. **Create `ToolsBuiltinReflectionConfig`**:
   - Location: `tools-builtin/src/main/java/com/amsha/tools/builtin/config/ToolsBuiltinReflectionConfig.java`.
6. **Migrate Tool Unit Tests to `tools-builtin/src/test`**:
   - `BraveWebSearchToolTest.java`, `SerperWebSearchToolTest.java`, `SmartWebFetchToolTest.java`, `SmartWebFetchToolIntegrationTest.java`, `HostExecToolTest.java`, `HostExecToolUnitTest.java`, `HostExecArtifactPostProcessTest.java`, `HostExecBrowserPreviewTest.java`, `GithubToolTest.java`, `ScheduleTaskToolTest.java`, `ManageScheduleToolTest.java`, `CheckListToolTest.java`, `SandboxExecToolTest.java`, `SandboxExecToolUnitTest.java`, `SandboxVerificationGateTest.java`, `LocalSocksProxyTest.java`, `ArchivalMemoryInsertToolTest.java`, `ArchivalMemorySearchToolTest.java`, `ArchivalConfidenceThresholdTest.java`, `ArchivalGoalMatchingTest.java`, `ArchivalInjectionSparsityTest.java`, `ArchivalToolFirstPathTest.java`, `CoreMemoryReadToolTest.java`, `CoreMemoryWriteToolsTest.java`, `RecallMemorySearchToolTest.java`, `RecallSearchCodeSnippetNoSummaryPollutionTest.java`, `RecallSearchIncludeSummariesModeTest.java`, `RecallSearchParamCoercionTest.java`, `RecallSearchTenantIsolationTest.java`.

## Verification
- `python3 ./scripts/fix_fqns.py && ./mvnw spotless:apply -q`
- `./mvnw test-compile -pl tools-builtin -am`
- `./mvnw test -pl tools-builtin`
- `./mvnw -pl tools-builtin -am pmd:check -DskipTests`
""",
        "04-decouple-core-dependencies-and-reflection.md": """# Issue 04: Clean amsha-core Reflection and Retained Tests

- **Iteration**: 3
- **Status**: Pending
- **Blocked by**: 03-create-amsha-tools-builtin-module.md

## Goals & Deliverables
1. **Use the exact production-file ownership manifest** (each path is intentional; no wildcard/package-wide deletion):
   - Move to `tools-builtin`: `core/src/main/java/com/amsha/core/tools/BraveWebSearchTool.java`, `SerperWebSearchTool.java`, `SmartWebFetchTool.java`, `HostExecTool.java`, `HostJavaCompiler.java`, `LocalSocksProxy.java`, `GithubTool.java`, `ScheduleTaskTool.java`, `ManageScheduleTool.java`, `CheckListTool.java`, `SandboxExecTool.java`, `SandboxVerificationGate.java`, `memory/ArchivalMemoryInsertTool.java`, `memory/ArchivalMemorySearchTool.java`, `memory/CoreMemoryAppendTool.java`, `memory/CoreMemoryReadTool.java`, `memory/CoreMemoryReplaceTool.java`, `memory/RecallMemorySearchTool.java`, and `ToolSearchIndex.java`.
   - Move to `sandbox`: `core/src/main/java/com/amsha/core/sandbox/DirectExecutor.java`, `NamespaceExecutor.java`, `PodmanContainerExecutor.java`, `PodmanUnavailableException.java`, `ProcessIsolationExecutor.java`, `SandboxCircuitBreaker.java`, `SandboxCodeSafetyScanner.java`, `SandboxImageMissingException.java`, `SandboxSecretResolver.java`, `SandboxStrategy.java`, `SecretTransport.java`, `TmpfsSecretTransport.java`, and `ToolExecutionException.java`.
   - Move health checks to `com.amsha.sandbox`: `core/src/main/java/com/amsha/core/health/PodmanHealthCheck.java` and `PodmanImageHealthCheck.java`.
   - Delete the listed source files from `core` only after all callers and tests have moved; retain only `SandboxExecutionPort.java`, `SandboxResult.java`, and `SandboxFailureMessages.java` under `com.amsha.core.sandbox`.
2. **Test and caller ownership matrix**:
   - Sandbox implementation tests named in Issue 02 move to `sandbox/src/test/java` with their package declarations updated together.
   - Built-in tool and Lucene tests named in Issue 03 move to `tools-builtin/src/test/java` with their package declarations updated together.
   - `ToolRegistryTest`, `ToolRegistryUnitTest`, `ToolRegistryDeadCatchTest`, `ToolDiscoveryServiceTest`, `ReasoningMessageAssemblerTest`, `GithubToolGuardInterceptorTest`, SPI resolver tests, checklist wire tests, and ArchUnit tests remain in their owning `core`/`app` modules and use test doubles for extracted providers.
   - Update every production caller in `mcp`, `app`, persistence, and coordination code to the new package before deleting the old file; verify no caller is silently dropped.
3. **Update Retained Core Tests to Use Test Doubles**:
   - `ToolRegistryTest.java`: Uses mock/instance `SandboxExecutionPort` and test double tools without importing `ProcessIsolationExecutor` or asserting extracted `GithubTool`.
   - `ToolRegistryUnitTest.java`: Uses test double tools.
   - `ToolRegistryDeadCatchTest.java`: Uses test double tools.
   - `ReasoningMessageAssemblerTest.java`: Uses mock `ToolSearchPort`.
   - `GithubToolGuardInterceptorTest.java`: Uses `ToolCapability.GITHUB.toolName()`.
4. **Verify Retained Classes in `amsha-core`**:
   - `AgentTool.java`, `ToolExecutionContext.java`, `ToolResponse.java`, `IdempotencyMode.java`, `ToolCategory.java`, `ToolRegistry.java`, `ToolDiscoveryService.java`, `ToolGovernanceGuard.java`, `ToolSecretExtractor.java`, `ToolSchemaProvider.java`, `ToolCategoryMapper.java`, `ToolContractExporter.java`, `DynamicToolSchemaParser.java`, `VerificationGate.java`, `ToolDiscoveryCompleted.java`
   - `SandboxExecutionPort.java`, `SandboxResult.java`, `ToolSearchPort.java`, `CoreSpiResolutionValidator.java`
   - `DelegateToAgentTool.java`, `DelegationProcessor.java`, `AgentTaskTool.java`
   - `CodeLanguageDetector.java`, `OutputTruncator.java`, `CheckListEvent.java`, `CheckList.java`, `CheckListItem.java`, `CheckListStatus.java`, `CronScheduleUtil.java`
5. **Verify Clean Core Build**:
   - Ensure `amsha-core` compiles cleanly with zero dependencies on `amsha-sandbox` or `amsha-tools-builtin`.

## Verification
- `python3 ./scripts/fix_fqns.py && ./mvnw spotless:apply -q`
- `./mvnw clean test-compile -pl core -am`
- `./mvnw test -pl core`
- `./mvnw -pl core -am pmd:check -DskipTests`
- Run the exact ownership-manifest and old-package scans; a wildcard deletion or an unowned test is a failure.
""",
        "05-wire-app-and-index-dependencies.md": """# Issue 05: Wire amsha-app and Index Dependencies

- **Iteration**: 4
- **Status**: Pending
- **Blocked by**: 04-decouple-core-dependencies-and-reflection.md

## Goals & Deliverables
1. **Update `app/pom.xml`**:
   - Add dependencies for `amsha-sandbox` and `amsha-tools-builtin`.
2. **Update `app/src/main/resources/application.yaml`**:
   - Add `amsha-sandbox`, `amsha-tools-builtin`, and `amsha-self-evolve` under `quarkus.index-dependency` with explicit `group-id: com.amsha` and matching `artifact-id` values. Short names without coordinates are not acceptable evidence.
   - Assert that every indexed coordinate resolves to the module artifact declared in the reactor and that no unrelated module is indexed accidentally.
3. **Execute Full Reactor Build & Clean Install**:
   - Run `./mvnw clean install -DskipTests` to install updated modules into local repository.
   - Run full reactor compilation and ArchUnit boundary tests.

## Verification
- `python3 ./scripts/fix_fqns.py && ./mvnw spotless:apply -q`
- `./mvnw clean install -DskipTests`
- `./mvnw test -pl app -Dtest=ArchitectureBoundaryTest`
- Verify the rendered `quarkus.index-dependency` entries contain the exact `com.amsha` coordinates for all three modules.
""",
        "06-graalvm-native-reflection-and-cdi-verification.md": """# Issue 06: GraalVM Native Reflection and CDI Verification

- **Iteration**: 5
- **Status**: Pending
- **Blocked by**: 05-wire-app-and-index-dependencies.md

## Goals & Deliverables
1. **Add Tool Discovery Tests**:
   - Create tests in `amsha-app` verifying the **raw CDI beans manifest** is exactly these 21 names: `brave_web_search`, `serper_web_search`, `web_fetch`, `host_exec`, `github`, `schedule_task`, `manage_schedule`, `manage_checklist`, `sandbox_exec`, `archival_memory_insert`, `archival_memory_search`, `core_memory_append`, `core_memory_read`, `core_memory_replace`, `recall_memory_search`, `garuda`, `agent_task`, `ingest_document`, `register_mcp_server`, `self_evolve_reflect`, and `browser_navigate`.
   - Verify the **active registry manifest** separately from raw CDI discovery. Each named test profile must contain explicit configuration and an exact expected active-name set; the `all-enabled` profile must equal the 21-name raw manifest, while credentialless/disabled profiles must assert their declared subsets and deterministic name order.
   - Verify **dynamic MCP tools** separately through `DynamicToolCapabilityRegistry.listTools()` and `ExternalMcpToolAdapter`; dynamic tools must not change either static manifest.
2. **Assert Startup Lifecycle Evidence**:
   - Start the fully wired application and assert stable log markers for `ToolRegistry` discovery completion, `ToolDiscoveryCompleted`, `ToolSearchIndex` readiness, `CoreSpiResolutionValidator` cardinality, and the sandbox provider bean.
   - The assertions must prove the startup path ran once and in order: registry snapshot first, synchronous event second, index build/readiness third. Generic Quarkus startup success is not sufficient.
3. **Add Isolated SPI Cardinality Tests**:
   - Create isolated Quarkus test profiles / unit tests for zero, one, and multiple providers using the shared `BeanManager.getBeans(Object.class)` resolver:
     - Zero providers: `SandboxExecutionPort` is unavailable and fails closed with `ToolResponse.infraFailure(SANDBOX_OFFLINE_MESSAGE)` (`"Sandbox execution environment is currently unavailable."`); `ToolSearchPort` is unavailable and returns an empty ranking.
     - One provider: the sandbox provider resolves, and `ToolSearchIndex` becomes ready only after `ToolDiscoveryCompleted`.
     - More than one provider: `CoreSpiResolutionValidator` fails bootstrap deterministically and names providers in sorted order.
   - Cover alternatives, disabled providers, and shadow beans where the Quarkus profile supports them; assert that only active implementations count.
   - Add provider-infrastructure-failure tests proving the stable sanitized message and proving the raw reason, command, secret, process output, and stack trace are absent from both `ToolResponse` and LLM-facing ranking/assembly paths.
4. **Static Old-Package Denylist Scan**:
   - Scan Java, YAML/YML, JSON reflection configuration, native properties, tests, package declarations, source paths, and fully qualified references. The scan must fail on these exact old implementation names/prefixes:
     - `com.amsha.core.tools.BraveWebSearchTool`
     - `com.amsha.core.tools.SerperWebSearchTool`
     - `com.amsha.core.tools.SmartWebFetchTool`
     - `com.amsha.core.tools.HostExecTool`, `com.amsha.core.tools.HostJavaCompiler`
     - `com.amsha.core.tools.LocalSocksProxy`
     - `com.amsha.core.tools.GithubTool`
     - `com.amsha.core.tools.ScheduleTaskTool`, `com.amsha.core.tools.ManageScheduleTool`
     - `com.amsha.core.tools.CheckListTool`, `com.amsha.core.tools.SandboxExecTool`, `com.amsha.core.tools.SandboxVerificationGate`
     - `com.amsha.core.tools.search.ToolSearchIndex` and `com.amsha.core.tools.memory.`
     - every `com.amsha.core.sandbox.` implementation reference other than `SandboxExecutionPort`, `SandboxResult`, and `SandboxFailureMessages`
     - `com.amsha.core.health.PodmanHealthCheck` and `com.amsha.core.health.PodmanImageHealthCheck`.
   - The denylist must not reject neutral core records or the three retained sandbox SPI/result types, and it must scan source paths as well as text references.
5. **Verify Native Reflection and Runtime Readiness**:
   - `CoreReflectionConfig` retains only the neutral core records and `SandboxResult` required by the ADR; `SandboxReflectionConfig` and `ToolsBuiltinReflectionConfig` contain the exact migrated target inventory, including nested public-schema DTOs.
   - Register these four Lucene class names in `classNames`: `org.apache.lucene.index.ConcurrentMergeScheduler`, `org.apache.lucene.index.SegmentReader`, `org.apache.lucene.index.IndexWriter`, and `org.apache.lucene.store.FilterIndexInput`, with the required constructors/public methods.
   - Preserve full-package Lucene runtime initialization with `--initialize-at-run-time=org.apache.lucene` and verify the argument is present in the final native configuration.
   - Run the native smoke test and assert startup, sandbox-provider presence, search-index readiness, inference, metrics, and graceful shutdown. Native compilation alone is not acceptance evidence.

## Verification
- `mise run build:native-local` (required evidence, not a pre-claimed result).
- `mise run smoke:local-runtime -- native --binary dist/amsha-sqlite` (required provider/index readiness and shutdown assertions).
- Run the raw/active/dynamic manifest tests, startup-log assertions, 0/1/>1 SPI profiles, sanitized-failure tests, reflection inventory check, and denylist scan.
""",
        "07-documentation-and-diagram-updates.md": """# Issue 07: Documentation and Architecture Diagram Updates

- **Iteration**: 6
- **Status**: Pending
- **Blocked by**: 06-graalvm-native-reflection-and-cdi-verification.md

## Goals & Deliverables
1. **Create Module Documentation**:
   - `sandbox/README.md`: Document all `amsha.sandbox.*` configuration properties.
   - `tools-builtin/README.md`: Document all `amsha.tools.*` configuration properties (Brave, Serper, GitHub, SOCKS, etc.).
2. **Verify Configuration Invariant**:
   - Ensure all `@ConfigProperty` entries appear in both `application.yaml` and `.env.example`.
   - Document `SandboxImageResolver` precedence: `AMSHA_SANDBOX_IMAGE` -> `amsha.sandbox.image` -> `AMSHA_SANDBOX_PODMAN_IMAGE` -> `amsha.sandbox.podman-image` -> `localhost/amsha-tool-runner:latest`, with blank values skipped.
   - Document shared core properties `amsha.workspace.root-dir` and `amsha.data-dir`.
   - Update the distribution bootstrap configuration and `CliUtils.getMissingVarSpecs()`/setup documentation for the user-configurable sandbox image settings; keep the resolver precedence identical in every entry point.
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

    # Refuse to overwrite an existing tracker unless the caller explicitly opts in.
    adr_path = REPO_ROOT / "docs/adr/tools/ADR-Tools-Builtin-and-Sandbox-Modularization.md"
    spec_path = REPO_ROOT / ".scratch/extract-tools-and-sandbox/spec.md"
    issues_dir = REPO_ROOT / ".scratch/extract-tools-and-sandbox/issues"
    targets = [adr_path, spec_path] + [issues_dir / filename for filename in issues]
    existing = [path for path in targets if path.exists()]
    if existing and not force:
        names = ", ".join(str(path.relative_to(REPO_ROOT)) for path in existing)
        print(
            "Refusing to overwrite existing tracker files: "
            f"{names}. Re-run with --tools --force after reviewing the replacement.",
            file=sys.stderr,
        )
        return False

    adr_path.parent.mkdir(parents=True, exist_ok=True)
    adr_path.write_text(adr_content, encoding="utf-8")
    print(f"Generated ADR: {adr_path.relative_to(REPO_ROOT)}")

    spec_path.parent.mkdir(parents=True, exist_ok=True)
    spec_path.write_text(spec_content, encoding="utf-8")
    print(f"Generated Spec: {spec_path.relative_to(REPO_ROOT)}")

    issues_dir.mkdir(parents=True, exist_ok=True)
    for filename, text in issues.items():
        p = issues_dir / filename
        p.write_text(text, encoding="utf-8")
        print(f"Generated Issue: {p.relative_to(REPO_ROOT)}")

    print("Tools & Sandbox tracker files scaffolded successfully.")
    return True


def main():
    parser = argparse.ArgumentParser(description="Scaffold ADRs and .scratch feature trackers.")
    parser.add_argument("--slug", type=str, help="Feature slug (e.g. 'extract-reflex')")
    parser.add_argument("--title", type=str, help="Feature title (e.g. 'Extract Reflex Subsystem')")
    parser.add_argument("--iterations", type=int, default=5, help="Number of iteration issue files to scaffold")
    parser.add_argument("--category", type=str, default="features", help="docs/adr category folder")
    parser.add_argument("--tools", action="store_true", help="Scaffold Tools & Sandbox modularization tracker preset")
    parser.add_argument("--force", action="store_true", help="Allow --tools to overwrite existing tracker files")
    args = parser.parse_args()

    if args.slug and args.title:
        scaffold_generic_feature(args.slug, args.title, args.iterations, args.category)
    elif args.tools:
        if not generate_tools_extraction_tracker(force=args.force):
            raise SystemExit(1)
    else:
        parser.print_help()


if __name__ == "__main__":
    main()

# ADR: Extract Built-in Tools and Sandbox Execution from amsha-core

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
3. **Module-Owned External Tools (4 `AgentTool` beans)**:
   - `mcp`: `McpSelfRegisterTool` (`ToolCapability.REGISTER_MCP_SERVER` -> `"register_mcp_server"`)
   - `rag`: `IngestDocumentTool` (`ToolCapability.INGEST_DOCUMENT` -> `"ingest_document"`)
   - `self-evolve`: `SelfEvolveReflectTool` (`ToolCapability.SELF_EVOLVE_REFLECT` -> `"self_evolve_reflect"`) (indexed via `quarkus.index-dependency` in `application.yaml`)
   - `tool-playwright`: `PlaywrightCliTool` (`ToolCapability.BROWSER_NAVIGATE` -> `"browser_navigate"`)
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
- **Move to `sandbox` package**: `core/src/main/java/com/amsha/core/health/PodmanHealthCheck.java` and `PodmanImageHealthCheck.java` become `com.amsha.sandbox.PodmanHealthCheck` and `com.amsha.sandbox.PodmanImageHealthCheck`.
- **Retain in core**: `SandboxExecutionPort.java`, `SandboxResult.java`, and `SandboxFailureMessages.java`; no implementation may remain in `com.amsha.core.sandbox` after Iteration 3.
- **Tests**: the Issue 02 and Issue 03 test lists are the complete migration matrix. Every moved implementation test changes module and package together; registry/SPI/ArchUnit tests remain in core/app as listed in Issue 04.

### Native reflection inventory

- `CoreReflectionConfig.targets` retains `SandboxResult.class`, `CheckListEvent.class`, `CheckList.class`, `CheckListItem.class`, and `CheckListStatus.class`; migrated tool classes are removed from core imports and targets.
- `ToolsBuiltinReflectionConfig.targets` registers the 15 built-in tool classes and supporting components. The Lucene `classNames` inventory is exact: `org.apache.lucene.internal.tests.TestSecrets`, `org.apache.lucene.index.ConcurrentMergeScheduler`, `org.apache.lucene.index.SegmentReader`, `org.apache.lucene.index.IndexWriter`, and `org.apache.lucene.store.FilterIndexInput`, with declared constructors and public methods enabled as required by §6.9.
- `SandboxReflectionConfig.targets` registers sandbox DTOs, strategies, resolvers, and exceptions (`PodmanUnavailableException`, `SandboxImageMissingException`, `SandboxImageResolver`, `SandboxStrategy`, `SecretTransport`, `TmpfsSecretTransport`, `ToolExecutionException`); ordinary CDI beans are not registered speculatively.
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

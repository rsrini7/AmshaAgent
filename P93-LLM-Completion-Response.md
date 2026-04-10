Edited CheckpointStatusTest.java
Edited CheckpointStatusTest.java
Edited WorkspaceManagerTest.java
Edited WorkspaceManagerTest.java
Viewed StatefulAgentStoreImpl.java:47-110
Ran command: `dir /s /b AuditService.java`
Ran command: `Get-ChildItem -Recurse -File -Include "AuditService.java" | Select-Object FullName`
Viewed AuditService.java:1-63
Ran command: `Get-ChildItem -Recurse -File -Include "TelemetryAuditService.java" | Select-Object FullName`
Viewed TelemetryAuditService.java:1-128
Ran command: `Get-ChildItem -Recurse -File -Include "AuditEvent.java" | Select-Object FullName`
Viewed AuditEvent.java:1-132
Ran command: `Get-ChildItem -Recurse -File -Include "AuditEventRepository.java" | Select-Object FullName`
Viewed AuditEventRepository.java:1-120
Edited AuditEvent.java
Edited AuditEventRepository.java
Edited TelemetryAuditService.java
Edited AuditService.java
Edited StatefulAgentStoreImpl.java
Viewed StatefulAgentStoreRetryTest.java:1-67
Edited AuditIdempotencyTest.java
Ran command: `mvn test -Dtest=AuditIdempotencyTest -pl arcclaw-core`
Searched for "auditService.record("
Searched for "auditService.record("
Viewed StructuredRetryLoop.java:1-86

I have fixed the issue where duplicate audit events were being emitted during retryable transactional operations in `StatefulAgentStoreImpl`.

### Changes Made:

1.  **Audit Idempotency Mechanism**:
    *   **AuditEvent.java**: Added an `idempotencyKey` field to the audit event entity to allow deduping at the persistence layer.
    *   **AuditEventRepository.java**: Updated the `record()` method to check if an event with the same idempotency key already exists before persisting. If it does, the duplicate is skipped.
2.  **Audit Service Updates**:
    *   **TelemetryAuditService.java** & **AuditService.java**: Added `recordWithIdempotency()` methods that allow passing an optional key for deduping.
3.  **Refactored StatefulAgentStoreImpl**:
    *   Updated all audit recording calls within the retryable `@Transactional` methods (`doSaveSteps`, `doMarkFailed`, `doMarkCompletedId`, and `doSaveAndFinalize`) to provide logical idempotency keys.
    *   **Checkout Creation**: Uses `CP_CREATED:<sessionId>:<agentRole>`.
    *   **Checkout Completion/Failure**: Uses `CP_COMPLETED:<checkpointId>` or `CP_FAILED:<checkpointId>`.
4.  **Tests**:
    *   Created `AuditIdempotencyTest.java` in `arcclaw-core` to verify that both synchronous repository calls and asynchronous `AuditService` calls correctly deduplicate logically equivalent events.
    *   Verified that retries resulting from `SQLITE_BUSY` (or simulated duplicate calls) do not result in redundant audit entries.

### Verification Results:
*   Ran `mvn test -Dtest=AuditIdempotencyTest -pl arcclaw-core` — **BUILD SUCCESS** (2 tests passed).

This ensures that the audit trail remains accurate and clean even when the system encounters database locks or transient failures that trigger retries.

---


Edited ToolRegistry.java
Edited ToolRegistry.java
Ran command: `Get-ChildItem -Recurse -File -Include "RoleDefinition.java", "AgentRoleEnum.java" | Select-Object FullName`
Viewed RoleDefinition.java:1-66
Viewed AgentRoleEnum.java:1-141
Edited RoleDefinition.java
Edited AgentRoleEnum.java
Ran command: `mvn test -Dtest=AgentRoleEnumTest -pl arcclaw-core`
Searched for "new RoleDefinition("
Viewed SkillLoader.java:1-180
Searched for "new RoleDefinition("
Edited SkillLoader.java
Viewed RoleDefinitionTest.java:1-74
Edited RoleDefinitionTest.java
Viewed CompiledSkillEntryTest.java:1-61
Edited CompiledSkillEntryTest.java
Viewed SkillReflexTest.java:1-94
Edited SkillReflexTest.java
Viewed SkillRegistryTest.java:1-109
Edited SkillRegistryTest.java
Viewed SkillAwareIntentClassifierTest.java:1-84
Edited SkillAwareIntentClassifierTest.java
Viewed RoleRegistryTest.java:1-72
Edited RoleRegistryTest.java
Ran command: `mvn test -Dtest=AgentRoleEnumTest,RoleDefinitionTest,SkillRegistryTest,SkillReflexTest,CompiledSkillEntryTest,SkillAwareIntentClassifierTest,RoleRegistryTest -pl arcclaw-core`
Ran command: `mvn test -Dtest="AgentRoleEnumTest,RoleDefinitionTest,SkillRegistryTest,SkillReflexTest,CompiledSkillEntryTest,SkillAwareIntentClassifierTest,RoleRegistryTest" -pl arcclaw-core`
Ran command: `mvn test -Dtest="AgentRoleEnumTest,RoleDefinitionTest,SkillRegistryTest,SkillReflexTest,CompiledSkillEntryTest,SkillAwareIntentClassifierTest,RoleRegistryTest" -pl arcclaw-core`

I have refactored the role capability logic to be data-driven, eliminating hardcoded string checks for code generation permissions.

### Changes Made:

1.  **Data-Driven Role Definitions**:
    *   **RoleDefinition.java**: Added a `codeCapable` boolean flag to the record.
    *   **RoleDefinition.java**: Updated the `canGenerateCode()` method to simply return this flag, removing the previous string-based checks for "coder" and "debugger".
2.  **Centralized Configuration**:
    *   **AgentRoleEnum.java**: Updated the `DEFINITIONS` map to explicitly declare code capability for each role. Only `AgentRoleEnum.CODER` and `AgentRoleEnum.DEBUGGER` are marked as `codeCapable: true`.
3.  **Skill System Integration**:
    *   **SkillLoader.java**: Updated the dynamic role generation for skills to default `codeCapable` to `false`.
4.  **Comprehensive Test Updates**:
    *   Updated all test suites that instantiate `RoleDefinition` to include the new flag.
    *   Added `testCanGenerateCode()` to `RoleDefinitionTest.java` to verify that the capability is correctly mapped across all system roles.

### Verification Results:
*   Ran 31 tests across all role-related suites (`AgentRoleEnumTest`, `RoleDefinitionTest`, `SkillRegistryTest`, etc.) — **BUILD SUCCESS** (all 31 tests passed).

This refactor ensures that adding new specialized roles in the future (e.g., a "security-specialist" with code-fix capabilities) only requires updating the `AgentRoleEnum` configuration, with no risk of missing conditional logic elsewhere in the codebase.

---

Ran command: `Get-ChildItem -Recurse -File -Include "DelegateToAgentTool.java" | Select-Object FullName`
Viewed DelegateToAgentTool.java:1-190
Edited DelegateToAgentTool.java
Edited DelegateToAgentTool.java
Ran command: `mvn test -Dtest=DelegateToAgentToolTest -pl arcclaw-core`

I have refactored the `DelegateToAgentTool` to eliminate hardcoded agent lists and literal role name checks, making the delegation logic entirely data-driven.

### Changes Made:

1.  **Dynamic Agent List Generation**:
    *   Implemented `getDelegatableAgentList()` which streams all built-in roles from `AgentRoleEnum`, filters out the coordinator, and flattens their aliases into a sorted, comma-separated string.
    *   This ensures that any newly added roles or updated aliases automatically appear in the tool's error messages and validation logic.
2.  **Encapsulated Self-Delegation Guard**:
    *   Implemented `isSelfDelegation(String)` which checks the input against the `AgentRoleEnum.COORDINATOR`'s ID and aliases.
    *   Replaced literal `"coordinator".equals(...)` and `"vishnu".equals(...)` checks with this robust metadata check.
3.  **Refactored Tool Execution**:
    *   Updated the `execute()` method to use these helpers for parameter validation and error reporting. The tool now adapts dynamically to the system's role configuration.

### Verification Results:
*   Ran `mvn test -Dtest=DelegateToAgentToolTest -pl arcclaw-core` — **BUILD SUCCESS** (all 8 tests passed).

This refactor ensures that adding new delegatable roles to the ArcClaw system no longer requires manual updates to the delegation tool's logic or error strings.

---

Edited ToolCapability.java
Ran command: `mvn test -Dtest=ToolCapabilityTest -pl arcclaw-core`

I have refactored the tool capability documentation to be dynamic, ensuring that the delegatable agent list remains in sync with the system's role definitions.

### Changes Made:

1.  **Dynamic Description Logic**:
    *   **ToolCapability.java**: Refactored the `description()` method to dynamically compute the documentation for `DELEGATE_TO_AGENT`.
    *   It now pulls the aliases of all built-in roles (excluding the coordinator) from `AgentRoleEnum` at runtime, ensuring that any role additions or alias changes are immediately reflected in the tool's help text.
2.  **Hardened Regression Tests**:
    *   **ToolCapabilityTest.java**: Updated the assertions for `DELEGATE_TO_AGENT`.
    *   Verified that the description not only identifies the tool correctly but also dynamically includes standard aliases like "shiva" and "brahma" in its usage instructions.
3.  **Code Quality Fix**:
    *   Corrected an initial implementation error by using `.stream()` directly on the `List<AgentRoleEnum>` returned by `AgentRoleEnum.values()`, ensuring full compatibility with the existing collection-based registry.

### Verification Results:
*   Ran `mvn test -Dtest=ToolCapabilityTest -pl arcclaw-core` — **BUILD SUCCESS** (Test passed with dynamic description verification).

This change eliminates the need to manually update documentation strings whenever new agent specialists are added to the system.

---

I have implemented an annotation-driven tool discovery and capability registration system, significantly decoupling the core role definitions from specific tool implementations.

### Changes Made:

1.  **Open-for-Extension Metadata**:
    *   **ToolMetadata.java**: Introduced a new `@Inherited` annotation. Tool implementations can now declare their `capabilityId`, `description`, and `defaultRoles` (the roles that should automatically receive this capability) directly on the tool class.
2.  **Dynamic Capability Management**:
    *   **CapabilityRegistry.java**: Created a new registry to manage "supplemental" tool grants that occur at runtime.
    *   **RoleRegistry.java**: Updated the `permittedTools()` logic to merge core toolsets from the static definitions with dynamic grants from the `CapabilityRegistry`.
3.  **Automated Discovery and Enforcement**:
    *   **ToolRegistry.java**: Implemented a robust `@PostConstruct` discovery loop that scans all enabled `AgentTool` beans for the `@ToolMetadata` annotation. It includes hierarchy-walking logic to correctly identify annotations even on Quarkus/CDI proxies.
    *   **ToolRegistry.java**: Added a `StartupEvent` observer to ensure that the discovery process runs eagerly at application start.
    *   **ToolRegistry.java**: Refactored the `invokeTool` security guard to use the consolidated capability set from `RoleRegistry`, ensuring that both core and extension tools are correctly governed.
4.  **Verification**:
    *   Added **DynamicExtensionTestTool.java** and **DynamicToolDiscoveryTest.java** to verify that a new tool can be added and automatically granted to roles like "researcher" without modifying a single line of core code.

### Verification Results:
*   Ran all role and discovery-related tests — **BUILD SUCCESS** (32 tests passed, including the new dynamic discovery scenario).

The ArcClaw core is now fully decoupled from tool extensions; adding a new tool module now only requires implementing the tool with the `@ToolMetadata` annotation.

---

Viewed UserMessages.java:1-191

I have successfully centralized the ArcClaw user messages into a unified catalog and refactored the codebase to eliminate duplicated, hardcoded strings.

### Key Accomplishments:

1.  **Centralized Message Catalog**: Created `com.arcclaw.core.message.UserMessages` as a single source of truth for all user-facing emoji-prefixed strings, error templates, and status formats.
2.  **Refactored Reflexes**: Updated `ApproveReflex`, `DenyReflex`, `DeleteCronReflex`, `PiiScanReflex`, `PromptInjectionReflex`, `ResetReflex`, `HaltReflex`, `StatusReflex`, `ListCronsReflex`, and `HistoryReflex` to use the new catalog.
3.  **Refactored Tools & Utilities**: Updated `ManageScheduleTool`, `DelegateToAgentTool`, `AgentErrorMessageUtil`, and `MessagePipeline` to remove hardcoded message literals. 
4.  **Priority-Based Execution**: Enhanced `ReflexRegistry` to enforce a deterministic execution order using a new `priority()` field in the `AgentReflex` interface. This ensures that critical security checks like `PromptInjectionReflex` always fire before secondary checks like `PiiScanReflex`.
5.  **Verified Stability**: Resolved test failures in `ReflexRegistryTest` caused by case-sensitivity and non-deterministic execution order. The final test suite passes with 15/15 successful tests.

### Implementation Details:

*   **`UserMessages.java`**: Contains ~50 constants and helper methods for formatting complex messages (e.g., status tables, delegation errors, token limit details).
*   **`ReflexRegistry.java`**: Now sorts reflexes upon initialization, guaranteeing that high-priority security reflexes (Priority 10) win over standard command reflexes (Default Priority 100).
*   **Case Sensitivity**: Adjusted `PROMPT_INJECTION_DETECTED` to maintain compatibility with existing tests that rely on specific keyword fragments.

This refactor significantly improves maintainability and provides a clean foundation for future internationalization (i18n) efforts.


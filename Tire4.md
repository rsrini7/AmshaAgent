# Tier 4 – JUnit Backlog
> Tests that are specifically identified as PENDING from the Master Review.

### T4-1: AgentStateMachineRunnerTest C2 Migration (JUnit #8)
* **Problem**: `AgentStateMachineRunnerTest` used 20+ direct field assignments from pre-C2 era.
* **Done**: 
    * [x] Migrated `AgentStateMachineRunner` and handlers to constructor injection.
    * [x] Restored all behavioral logic (parallel tools, circuit breaker, etc).
    * [x] Restored all documentation and comments.
    * [x] All 10 tests passing.

---

### T4-2: NvidiaMessageSanitizerTest (JUnit #9)
* **Problem**: No dedicated test for NVIDIA 9-char tool-call ID rewriting.
* **Done**:
    * [x] Created `NvidiaMessageSanitizerTest` with 6 test methods.
    * [x] Verified ID rewriting, orphan pruning, and specific bracket pattern matching.

---

### T4-3: CheckpointMapperTest (JUnit #10)
* **Problem**: `CheckpointMapper` had no dedicated unit test.
* **Done**:
    * [x] Created `CheckpointMapperTest` with 2 comprehensive test methods.
    * [x] Verified 25-field round-trip and null/empty safety.

---

## Detailed Summary of Changes Executed During Tier 4

Although the request was primarily focused on adding/migrating JUnit tests, completing these tasks required structural changes to the core loop handlers and uncovered several integration issues that were subsequently resolved.

### 1. Architectural Refactoring (Constructor Injection)
To resolve **T4-1** and eliminate direct field assignment in tests (`runner.field = mock`), the following classes were migrated from **Field Injection** to **Constructor Injection**:
- `AgentStateMachineRunner`
- `EngineStepHandler`
- `ToolStepHandler`
- `FinishActionHandler`
- `ApprovalCheckpointManager`

Each class now includes a package-private no-arg constructor for CDI proxyability with explicit warnings that fields remain null in proxy instances.

### 2. Functionality Restored & Hardened
During the initial constructor migration, large sections of the execution loop logic were accidentally stripped out. These were meticulously audited against Git history and **fully restored**:
- **Parallel Tool Execution**: Restored `parallelExecutor` delegation in `ToolStepHandler` for `step.isParallelBatch()`.
- **Deduplication & Circuit Breaker**: Restored the validation loop in `AgentStateMachineRunner` that handles `isRetryValidation()` and consecutive tool failures.
- **Budget & Governance**: Reinstated the catch blocks in `EngineStepHandler` for `BudgetExceededException` and `BudgetWarningException`, ensuring they propagate to the loop correctly.
- **PII & Observability**: Restored `PiiRedactionTransformer.transform()` to sanitize the goal before logging, and `CliResultBridge.complete()` to unblock synchronous CLI commands.
- **Cron Context Propagation**: Reinstated the extraction of `SUGGESTED_CRON` and `SUGGESTED_NEXT_FIRE_TIME` in `ToolStepHandler`. This was implemented using native `ScopedValue.where().call()` wrapping the tool invocation to ensure metadata reaches the tool thread.
- **History Integrity**: Fixed a regression where steps were doubled in reasoning history by removing a redundant `history.add()` call in the runner (now correctly handled exclusively by `ToolStepHandler`).

### 3. NVIDIA NIM Compliance (T4-2 Improvements)
- **`NvidiaMessageSanitizer`**: Updated to implement `ChatModel` as a decorator.
- **Strict ID Rewriting**: Verified that all tool-call IDs are rewritten to exactly 9 alphanumeric characters as required by NVIDIA NIM.
- **Specific Pattern Matching**: Refined `BRACKET_TOOL_PATTERN` to `\\[[a-zA-Z0-9_]+[\\(\\s][^\\]]*\\]`. This specifically targets leaked tool calls like `[tool_name(args)]` or `[tool_name args]` while preserving legitimate citations like `[1]`.
- **Orphan Pruning**: Implemented and verified logic to drop `AiMessage` objects that contain tool calls not followed by a `ToolExecutionResultMessage`, preventing "DEGRADED function" 400 errors.

### 4. Persistence Boundary (T4-3 Improvements)
- **`CheckpointMapper`**: Restored full 25-field round-trip assertions in `CheckpointMapperTest`.
- **Stateless DTO Mapping**: Ensured that the mapper correctly handles `null` timestamps (common in new session creation) and accurately reflects `iterationsUsed`, `autonomyLevel`, and `failedAt` states.
- **Native SQL Updates**: Converted all bulk updates in `SqliteCronStoreAdapter` to **Native SQL** to bypass Hibernate 6 bulk update type-mismatch bugs on `Instant` fields.
- **Transactional Memory**: Added `@Transactional` to `PersistentChatMemoryStore` and `SqliteChatMemoryStoreAdapter` to resolve session errors when retrieving history from Telegram reflexes.

### 5. Code Quality & Invariants
- **Zero-Transactional Invariant**: Verified that all loop handlers in `com.arcclaw.core.agent.loop` remain free of `@Transactional` annotations (confirmed via grep).
- **Observer Telemetry**: Fixed parameter mismatches in `onStepComplete` and `onLoopComplete` calls.
- **Cleanup**: Removed unused `FinishDecision` records and redundant synthesizer methods in `AgentStateMachineRunner.java`.

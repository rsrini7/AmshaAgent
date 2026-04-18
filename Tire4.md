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
    * [x] Created `NvidiaMessageSanitizerTest` with 5 test methods.
    * [x] Verified ID rewriting, orphan pruning, and bracket pattern matching.

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

### 2. Functionality Restored (The "Missed Logic" Incident)
During the initial constructor migration, large sections of the execution loop logic were accidentally stripped out. These were meticulously audited against Git history and **fully restored**:
- **Parallel Tool Execution**: Restored `parallelExecutor` delegation in `ToolStepHandler` for `step.isParallelBatch()`.
- **Deduplication & Circuit Breaker**: Restored the validation loop in `AgentStateMachineRunner` that handles `isRetryValidation()` and consecutive tool failures.
- **Budget & Governance**: Reinstated the catch blocks in `EngineStepHandler` for `BudgetExceededException` and `BudgetWarningException`, ensuring they propagate to the loop correctly.
- **PII & Observability**: Restored `PiiRedactionTransformer.transform()` to sanitize the goal before logging, and `CliResultBridge.complete()` to unblock synchronous CLI commands.
- **Cron Context Propagation**: Reinstated the extraction of `SUGGESTED_CRON` and `SUGGESTED_NEXT_FIRE_TIME` in `ToolStepHandler`. Since `WorkspaceContext.run()` only maps the 6 core identity fields, this was correctly implemented using native `ScopedValue.where().call()` wrapping the tool invocation.

### 3. Bugs Fixed and Improvements Added
- **`NvidiaMessageSanitizer` CDI Conflict**: The sanitizer was incorrectly annotated with `@ApplicationScoped` while also implementing `ChatModel`. This caused an `AmbiguousResolutionException` during Quarkus bootstrap because it conflicted with the `LangChain4jLlmAdapter`. The annotation was removed, properly establishing it as a manual decorator pattern used inside `NvidiaProviderFactory`.
- **`StepDecision` Enhancements**: Added missing factory methods `retryEngine` and `retryValidation` (with a new `isRetryValidation` boolean flag) to accurately represent whether the loop should fetch a new thought from the LLM or instantly retry the tool validation phase (e.g., for Deduplication caching).
- **`ApprovalCheckpointManager` DRY**: Extracted the repetitive `try/catch` block for `setStateJson(objectMapper.writeValueAsString(history))` into a single `updateStateJson()` helper method.
- **`AgentErrorMessageUtil`**: Implemented a missing `hashGoal(String goal)` utility method to support the `onLoopComplete` observer telemetry.

### 4. Tests Implemented
- **`AgentStateMachineRunnerTest`**: Completely rewritten setup using constructor instantiation. Resolved all compilation errors and parameter mismatches.
- **`NvidiaMessageSanitizerTest`**: Verified that tool execution IDs like `call_123` are successfully rewritten to a compliant 9-character alphanumeric ID, and that bracketed pattern variants in thoughts are correctly stripped.
- **`CheckpointMapperTest`**: Verified the mapping of all 25 DTO fields back and forth to the `AgentCheckpoint` entity, alongside specific edge cases for `null` timestamp safety.
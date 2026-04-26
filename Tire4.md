## Tier 4 – JUnit Backlog
> Tests that are specifically identified as PENDING from the Master Review.

### T4-1: AgentStateMachineRunnerTest C2 Migration (JUnit #8)
* **Problem**: `AgentStateMachineRunnerTest` uses 20+ direct field assignments (`runner.field = mock`) from pre-C2 era. If any extracted class moves to constructor injection, all 10 tests break.
* **File**: `arcclaw-core/src/test/java/com/arcclaw/core/agent/loop/AgentStateMachineRunnerTest.java`
* **What to Do**: Migrate test setup to match C2-decomposed class constructors. Replace field assignments with constructor calls or CDI test injection.
* **Done When**:
    * [ ] Zero direct field assignments on decomposed classes in test setup
    * [ ] All 10 tests pass with constructor-based wiring

---

### T4-2: NvidiaMessageSanitizerTest (JUnit #9)
* **Problem**: No dedicated test exists for NVIDIA 9-char tool-call ID rewriting compliance. The sanitizer is critical for NVIDIA NIM integration.
* **What to Do**: Create `NvidiaMessageSanitizerTest` verifying:
    * Non-compliant IDs rewritten to 9-char alphanumeric
    * Orphaned tool calls (no following `ToolResult`) are pruned
    * `NVIDIA_TOOL_CALL_PATTERN` bracket variations matched
* **Done When**:
    * [ ] Test class exists with ≥5 test methods covering ID rewriting, orphan pruning, bracket patterns

---

### T4-3: CheckpointMapperTest (JUnit #10)
* **Problem**: `CheckpointMapper` has no dedicated unit test. Entity↔DTO round-trip correctness is untested in isolation.
* **File**: `arcclaw-core/src/main/java/com/arcclaw/core/persistence/CheckpointMapper.java`
* **Done When**:
    * [ ] `CheckpointMapperTest` exists with round-trip test for all fields
    * [ ] Edge cases tested: null optional fields, empty collections, max-length strings
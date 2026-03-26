# PROMPT — PHASE 68-A: Correctness & Safety Defects (Tier A)

**Role:** Senior Java Architect, Bug Fixer & Senior Java Developer
**Task:** Phase 68-A — Fix 6 confirmed correctness and safety defects in live production code. All bugs exist today. No architectural changes. Surgical fixes only.

***

## Architecture Context
Refer `@Architecture_Invariants.md`. Module dependency order: `arcclaw-channel-api` → `arcclaw-core` → `arcclaw-channel-telegram` → `arcclaw-app`.

***

## IMPLEMENTATION REQUIREMENTS

### Common Requirements
1. Every fix must be accompanied by a test that proves the bug existed and now does not.
2. No new dependencies. No structural package changes.
3. All existing 877+ tests must remain green after every component.
4. Use `static final Pattern` for any new regex — never compiled inline.
5. All fixes must be safe under JDK 25 — no preview features.
6. Test commands always use `clean` to prevent stale class false-negatives.

***

### Component A1 — Silent `argsJson` Corruption in `ChatHistoryAssembler`

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/agent/reasoning/ChatHistoryAssembler.java`

**Bug:** The tool args serialization block catches `Exception` and silently continues with `argsJson` as `null` or empty. `ToolExecutionRequest` is then built with blank arguments. The LLM retries with no params, producing ghost tool calls with no observable error.

**Fix:**
```java
// BEFORE:
catch (Exception e) {
    // Ignore serialization errors
}

// AFTER:
catch (Exception e) {
    argsJson = "{}";
    log.warnf("[ChatHistoryAssembler] Failed to serialize tool args at step %d" +
              " — using empty params. Cause: %s", i, e.getMessage());
}
```

**Test:** `ChatHistoryAssemblerTest` — add a test where `step.actionInput()` returns a map containing a non-serializable value (e.g., a circular reference mock). Assert the assembled `ToolExecutionRequest` has `arguments = "{}"` rather than `null`, and that no exception escapes.

***

### Component A2 — `@ActivateRequestContext` Missing on Short Overloads in `AgentLoop`

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/agent/loop/AgentLoop.java`

**Bug:** `run(String goal, ReasoningEngine engine)` and `run(String goal, ReasoningEngine engine, int maxIterations)` delegate to the full 6-arg `runWithRole()` but carry no `@ActivateRequestContext` themselves. Callers that invoke either short overload — including `AgentCronScheduler` and some CLI paths — never activate a CDI request context, causing `@RequestScoped` beans (`WorkspaceContext`, `WorkspaceScopeHolder`) to throw `ContextNotActiveException` at runtime under cron execution.

**Fix:** Add `@ActivateRequestContext` to both short overloads:
```java
@ActivateRequestContext
public AgentResult run(String goal, ReasoningEngine engine) { ... }

@ActivateRequestContext
public AgentResult run(String goal, ReasoningEngine engine, int maxIterations) { ... }
```

**Test:** `AgentLoopContextTest` — mock `WorkspaceContext.autonomy()` to throw `ContextNotActiveException` if request context is not active. Call the two-arg `run()` directly. Assert no `ContextNotActiveException` is thrown.

***

### Component A3 — OTel Double-Recording in `EngineStepHandler`

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/agent/loop/EngineStepHandler.java`

**Bug:** After each LLM step, `EngineStepHandler` calls both:
1. `observers.forEach(o -> o.recordLlmCall(...))` — iterates all `AgentObserver` CDI beans
2. `otelContext.recordLlmCall(...)` — calls `AgentOtelContext` directly

`AgentOtelContext` is `@ApplicationScoped` and implements `AgentObserver`, so it is already included in the `Instance<AgentObserver>` set. Every LLM step records two OTel token events: one from the observer loop and one from the direct call. This inflates cost metrics and OTel span data by 2×.

**Fix:** Remove the direct `otelContext.recordLlmCall(...)` call from `EngineStepHandler`. The `Instance<AgentObserver>` loop is sufficient. Keep the direct `otelContext` field only for span start/end lifecycle calls that are NOT part of the `AgentObserver` contract.

**Test:** `EngineStepHandlerOtelTest` — inject a spy `AgentObserver` that counts `recordLlmCall` invocations. After one engine step, assert the spy was called exactly once, not twice.

***

### Component A4 — Startup Race: `RagSchemaInitializer` fires before FTS schema is attached

**Files:**
- `arcclaw-core/src/main/java/com/arcclaw/core/persistence/SqliteWalConfigurer.java`
- `arcclaw-rag/src/main/java/com/arcclaw/rag/RagSchemaInitializer.java`

**Bug:** Both `SqliteWalConfigurer.onStartup()` and `RagSchemaInitializer.onStart()` observe `StartupEvent` with no `@Priority`. When Jandex scan order places `RagSchemaInitializer` first, it acquires a pooled connection and warms up `vec0` before `SqliteWalConfigurer` attaches the `fts` schema. FTS5 extension queries on that first connection fail until the next connection checkout. This is a non-deterministic ordering bug.

**Fix:**
```java
// SqliteWalConfigurer.java
void onStartup(@Observes @Priority(Interceptor.Priority.APPLICATION + 50) StartupEvent ev) { ... }

// RagSchemaInitializer.java
void onStart(@Observes @Priority(Interceptor.Priority.APPLICATION + 100) StartupEvent ev) { ... }
```
WAL+FTS attach (priority 50) always completes before vec0 warmup (priority 100).

**Test:** `StartupOrderTest` — use a `@QuarkusTest` that records the sequence of `onStartup()` / `onStart()` invocations via an ordered `List<String>` observer. Assert `SqliteWalConfigurer` fires before `RagSchemaInitializer`.

***

### Component A5 — Dead `catch (IllegalArgumentException)` in `ToolRegistry`

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/tools/ToolRegistry.java`

**Bug:** `invokeTool()` wraps `AgentRoleEnum.fromString(agentRole)` in a `catch (IllegalArgumentException)` block marked `/* Unknown role fail closed */`. `AgentRoleEnum.fromString()` never throws `IllegalArgumentException` — it returns a dynamic role for unrecognized strings. This catch block is dead code: it has never fired and never will. Its presence misleads developers into believing the fail-closed path is active when it is not.

**Fix:** Remove the `catch (IllegalArgumentException)` block entirely. If an explicit fail-closed guard is desired, add an `AgentRoleEnum.isKnownRole(agentRole)` check before the capability check with a proper log and early return.

**Test:** `ToolRegistryDeadCatchTest` — call `invokeTool()` with an unknown role string (e.g., `"phantom_role"`). Assert the call proceeds to the capability check phase and does not silently swallow any exception.

***

### Component A6 — `ReasoningEngine.chatForStructuredOutput()` LSP Trap

**File:** `arcclaw-core/src/main/java/com/arcclaw/core/agent/reasoning/ReasoningEngine.java`

**Bug:** The `default` implementation of `chatForStructuredOutput()` throws `UnsupportedOperationException`. `StructuredOutputEngine` calls this method via the `ReasoningEngine` interface. Any test mock or alternative implementation that forgets to override this method will throw silently at runtime with no indication that the interface contract was violated.

**Fix:** Remove the `default` body. Make `chatForStructuredOutput()` an abstract interface method (no default). All implementing classes (`LangChainReasoningEngine` and any test stubs) must explicitly implement it or fail at compile time.

```java
// BEFORE:
default String chatForStructuredOutput(String systemPrompt, String userPrompt) {
    throw new UnsupportedOperationException("Not implemented");
}

// AFTER:
String chatForStructuredOutput(String systemPrompt, String userPrompt);
```

**Test:** Compile-time enforcement replaces the runtime test. Add a comment in `ReasoningEngineContract` (or create one): a `@Test` that instantiates an anonymous `ReasoningEngine` implementing only `chat()` must fail to compile.

***

### Verification — Tier A

```powershell
# A1–A6 combined:
cd C:\Users\Srini\ws\ArcClawInternal
mvn clean test -pl arcclaw-core,arcclaw-rag,arcclaw-app -am
# Expected: 877+ tests, 0 failures, 0 errors
```

***
***

# PROMPT — PHASE 68-B: SOLID & Magic String Fixes (Tier B)

**Role:** Senior Java Architect & Senior Java Developer
**Task:** Phase 68-B — Eliminate 4 SOLID violations and 3 magic string duplication defects. These are structural improvements, not new features. No new external dependencies.

***

## Architecture Context
Refer `@Architecture_Invariants.md`.

***

## IMPLEMENTATION REQUIREMENTS

### Common Requirements
1. All existing 877+ tests green after every component.
2. No new Maven modules. No package moves.
3. Each component is independently buildable and committable.
4. `mvn clean test` after each component before proceeding to the next.

***

### Component B1 — `AgentRoleEnum.isSkillRole()` — Eliminate 6 Duplicate `SKILL_PREFIX` Constants

**Problem:** `private static final String SKILL_PREFIX = "skill:"` is independently declared in six classes: `AgentLoop`, `SystemPromptBuilder`, `AgentDispatchHandler`, `LangChainReasoningEngine`, `AgentRoleEnum`, `ToolInvocationGuard`. A rename of the prefix requires six edits and six tests.

**Files to modify:**
- `arcclaw-core/.../agent/role/AgentRoleEnum.java` — add two static methods
- `arcclaw-core/.../agent/loop/AgentLoop.java`
- `arcclaw-core/.../agent/reasoning/SystemPromptBuilder.java`
- `arcclaw-core/.../pipeline/handlers/AgentDispatchHandler.java`
- `arcclaw-core/.../agent/reasoning/LangChainReasoningEngine.java`
- `arcclaw-core/.../agent/loop/ToolInvocationGuard.java`

**Fix — add to `AgentRoleEnum`:**
```java
public static boolean isSkillRole(String roleStr) {
    return roleStr != null && roleStr.startsWith("skill:");
}

public static String skillName(String roleStr) {
    if (!isSkillRole(roleStr))
        throw new IllegalArgumentException("Not a skill role: " + roleStr);
    return roleStr.substring("skill:".length());
}
```

Replace every occurrence of:
- `agentRole.startsWith(SKILL_PREFIX)` → `AgentRoleEnum.isSkillRole(agentRole)`
- `agentRole.startsWith("skill:")` → `AgentRoleEnum.isSkillRole(agentRole)`
- `agentRole.substring(SKILL_PREFIX.length())` → `AgentRoleEnum.skillName(agentRole)`
- `agentRole.substring("skill:".length())` → `AgentRoleEnum.skillName(agentRole)`

Delete the `SKILL_PREFIX` constant from all six classes.

**Test:** `AgentRoleEnumSkillRoleTest` — assert `isSkillRole("skill:code-reviewer")` is `true`, `isSkillRole("coordinator")` is `false`, `isSkillRole(null)` is `false`, `skillName("skill:code-reviewer")` returns `"code-reviewer"`.

***

### Component B2 — `AgentSentinels.CRON_PREFIX` — Eliminate 3 Inline String Checks

**Problem:** The `"CRON "` prefix is written as:
- String literal `"CRON " + goal` in `ScheduleTaskTool`
- `goal.toUpperCase().startsWith("CRON")` in `LangChainReasoningEngine`
- `goal.toUpperCase().startsWith("CRON")` in `AgentCronScheduler`

It is absent from `AgentSentinels` where all other sentinel strings live.

**Files to modify:**
- `arcclaw-core/.../agent/AgentSentinels.java`
- `arcclaw-core/.../tools/ScheduleTaskTool.java`
- `arcclaw-core/.../agent/reasoning/LangChainReasoningEngine.java`
- `arcclaw-app/.../scheduler/AgentCronScheduler.java`

**Fix:**
```java
// AgentSentinels.java — add:
public static final String CRON_PREFIX = "CRON ";

public static boolean isCronGoal(String goal) {
    return goal != null && goal.startsWith(CRON_PREFIX);
}
```

Replace:
- `"CRON " + goal` → `AgentSentinels.CRON_PREFIX + goal`
- `goal.toUpperCase().startsWith("CRON")` → `AgentSentinels.isCronGoal(goal)`

Remove `toUpperCase()` — the prefix is always written uppercase by `ScheduleTaskTool` so case-insensitive check is unnecessary complexity.

**Test:** `AgentSentinelsTest` — assert `isCronGoal("CRON fetch prices")` is `true`, `isCronGoal("cron fetch prices")` is `false` (case-sensitive, by design), `isCronGoal(null)` is `false`.

***

### Component B3 — `RoleDefinition.canGenerateCode()` — Remove Hardcoded Role Strings from Finish Handler

**Problem:** `AgentStateMachineRunner.handleFinishAction()` hardcodes:
```java
if (AgentRoleEnum.RESEARCHER.toScopedName().equals(agentRole)
        || AgentRoleEnum.ARCHITECT.toScopedName().equals(agentRole)) {
```
Every new non-coder role must modify the runner. This is an OCP violation and a Feature Envy smell — the role knows its own constraints.

**Files to modify:**
- `arcclaw-core/.../agent/role/RoleDefinition.java`
- `arcclaw-core/.../agent/role/AgentRoleEnum.java` — update 2 role definitions
- `arcclaw-core/.../agent/loop/AgentStateMachineRunner.java`

**Fix — `RoleDefinition`:**
```java
// Add field (default true):
public record RoleDefinition(String id, String displayName, Set<ToolCapability> capabilities,
                              Set<String> aliases, boolean canGenerateCode) {
    // compact constructor — default canGenerateCode to true when not passed
}
```

Update `RESEARCHER` and `ARCHITECT` entries in `AgentRoleEnum`:
```java
RESEARCHER(new RoleDefinition("researcher", "Researcher", ..., false)),
ARCHITECT(new RoleDefinition("architect", "Architect",  ..., false)),
// all others: canGenerateCode = true (default)
```

Replace in `handleFinishAction()`:
```java
// BEFORE:
if (AgentRoleEnum.RESEARCHER.toScopedName().equals(agentRole)
        || AgentRoleEnum.ARCHITECT.toScopedName().equals(agentRole)) {

// AFTER:
if (!AgentRoleEnum.fromString(agentRole).definition().canGenerateCode()) {
```

**Test:** `RoleDefinitionCodeGenTest` — assert `RESEARCHER.definition().canGenerateCode()` is `false`, `ARCHITECT.definition().canGenerateCode()` is `false`, `COORDINATOR.definition().canGenerateCode()` is `true`. Add integration test to `AgentStateMachineRunnerTest` asserting researcher role blocks a code-block response.

***

### Component B4 — `LlmProviderFactory` SPI — Close the Hardcoded `ChatModelProducer` Switch

**Problem:** `ChatModelProducer.chatModel()` is a 3-case `switch` on provider string (`"groq"`, `"openai"`, `"ollama"`). Adding any new LLM provider (DeepSeek, Anthropic, Mistral) requires modifying this class — identical structural issue to the `EmbeddingProviderFactory` you already solved in P65.

**Files to create:**
- `arcclaw-core/.../agent/reasoning/LlmProviderFactory.java` (interface)
- `arcclaw-core/.../agent/reasoning/LlmModelConfig.java` (record — already likely exists, confirm)
- `arcclaw-app/.../routing/GroqLlmProviderFactory.java`
- `arcclaw-app/.../routing/OpenAiLlmProviderFactory.java`
- `arcclaw-app/.../routing/OllamaLlmProviderFactory.java`

**Files to modify:**
- `arcclaw-app/.../routing/ChatModelProducer.java` (was `ModelProducer.java` — confirm name)

**Fix:**
```java
// LlmProviderFactory.java
public interface LlmProviderFactory {
    String providerId();
    ChatLanguageModel createModel(LlmModelConfig config);
}

// GroqLlmProviderFactory.java
@ApplicationScoped
public class GroqLlmProviderFactory implements LlmProviderFactory {
    @Override public String providerId() { return "groq"; }
    @Override public ChatLanguageModel createModel(LlmModelConfig config) { ... }
}
// same pattern for OpenAi, Ollama
```

```java
// ChatModelProducer.java — replace switch with:
@Inject Instance<LlmProviderFactory> factories;

@Produces @ApplicationScoped
public ChatLanguageModel chatModel(LlmModelConfig config) {
    return factories.stream()
        .filter(f -> f.providerId().equalsIgnoreCase(config.provider()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "No LlmProviderFactory for provider: " + config.provider()))
        .createModel(config);
}
```

Delete the `switch` statement entirely.

**Test:** `LlmProviderFactoryTest` — mock three factories with different `providerId()`. Assert `ChatModelProducer` resolves `"groq"` to `GroqLlmProviderFactory`. Assert unknown provider throws `IllegalStateException` with provider name in message.

***

### Component B5 — `AutonomyPolicy` CDI Injection — Close DIP Violation in `ToolRegistry`

**Problem:** `ToolRegistry` does `private final AutonomyPolicy autonomyPolicy = new AutonomyPolicy.Default()` — constructed with `new`. You cannot swap, mock, or decorate the policy without modifying `ToolRegistry`.

**Files to modify:**
- `arcclaw-core/.../security/AutonomyPolicy.java`
- `arcclaw-core/.../tools/ToolRegistry.java`

**Fix:**
```java
// Mark Default as a CDI bean:
@ApplicationScoped
public static class Default implements AutonomyPolicy { ... }

// ToolRegistry — replace field init with injection:
@Inject AutonomyPolicy autonomyPolicy;
// Remove: private final AutonomyPolicy autonomyPolicy = new AutonomyPolicy.Default();
```

**Test:** `ToolRegistryAutonomyTest` — inject a mock `AutonomyPolicy` that always returns `RequiresApproval`. Assert `ToolRegistry.invokeTool()` throws `RequiresApprovalException` for a normally-FULL tool. This test is currently impossible without modifying `ToolRegistry` — its passing proves the DIP fix.

***

### Component B6 — `SynthesizingAgent` Interface — Fix LSP Violation on `Agent.synthesize()`

**Problem:** `Agent.synthesize()` has a `default` that throws `UnsupportedOperationException`. Five of six agents (`VishnuAgent`, `BrahmaAgent`, `ShivaAgent`, `SaraswatiAgent`, `BrihaspatiAgent`) cannot satisfy the `Agent` contract through `synthesize()`. `AgentStateMachineRunner` injects `@AgentRole("synthesizer") Agent` — if CDI wires the wrong agent, the failure is silent until runtime.

**Files to create:**
- `arcclaw-core/.../agent/persona/SynthesizingAgent.java`

**Files to modify:**
- `arcclaw-core/.../agent/persona/Agent.java` — remove `default synthesize()` throwing UOE
- `arcclaw-core/.../agent/persona/DattatreyaAgent.java` — implement `SynthesizingAgent`
- `arcclaw-core/.../agent/loop/AgentStateMachineRunner.java` — change injection type

**Fix:**
```java
// SynthesizingAgent.java
public interface SynthesizingAgent extends Agent {
    AgentResult synthesize(String sessionId, String combinedContext,
                           List<ReasoningStep> history);
}

// DattatreyaAgent.java
@ApplicationScoped @AgentRole("synthesizer")
public class DattatreyaAgent implements SynthesizingAgent { ... }

// AgentStateMachineRunner.java
@Inject SynthesizingAgent synthesizerAgent;
// Remove: @Inject @AgentRole("synthesizer") Agent synthesizerAgent;
```

Remove the `default synthesize()` UOE body from `Agent.java` entirely.

**Test:** `SynthesizingAgentContractTest` — assert that `DattatreyaAgent` is the only CDI bean implementing `SynthesizingAgent`. Assert the other 5 agent beans do NOT implement `SynthesizingAgent` (compile-time safety, but also a CDI instance count check).

***

### Verification — Tier B

```powershell
cd C:\Users\Srini\ws\ArcClawInternal
mvn clean test
# Expected: 877+ tests, 0 failures, 0 errors
```

***
***

# PROMPT — PHASE 68-C: Cleanup & Dead Code Purge (Tier C)

**Role:** Senior Java Developer
**Task:** Phase 68-C — Remove confirmed dead code and fix two smell-level issues. No behavioral changes. No new tests required beyond confirming existing suite stays green.

***

### Component C1 — Dead Code Purge (6 items, one PR)

Delete the following in a single commit. Each is confirmed to have zero callers in the P67 codebase:

| File | Reason |
|---|---|
| `arcclaw-core/.../scope/ScopedCounter.java` | Test bean in production module, no callers |
| `arcclaw-app/.../channel/ApprovalTimeoutNotifier.java` | `@Deprecated @ApplicationScoped` empty shell, replaced by `ChannelApprovalTimeoutDispatcher` |
| `arcclaw-core/.../ArcClawInfo.java` | Two constants (`NAME`, `VERSION`), zero callers |
| `arcclaw-core/.../agent/role/AgentRoleEnum.java` — `aliases` field init | No Telegram command, REST call, or test routes via deity alias (e.g., `"vishnu"`, `"brahaspati"`) |
| `arcclaw-app/.../scope/WorkspaceExecutors.java` — `wrap(ExecutorService)` method | No caller; only `newVirtualThreadPerTaskExecutor()` is used |

**Verification:** After deletion, run `rg "ScopedCounter\|ApprovalTimeoutNotifier\|ArcClawInfo\|\.wrap(" arcclaw-app/src arcclaw-core/src --type java`. Expected: 0 hits for deleted items.

***

### Component C2 — Compliance Filter Relocation (Inappropriate Intimacy Fix)

**Problem:** `PersistentChatMemoryStore.updateMessages()` filters `MessageTag`-prefixed `AiMessage` entries:
```java
.filter(msg -> !(msg instanceof AiMessage ai) || !MessageTag.isTagged(ai.text()))
```
The memory store should not know about the compliance tagging system.

**Files to modify:**
- `arcclaw-core/.../state/PersistentChatMemoryStore.java` — remove `MessageTag` filter
- `arcclaw-core/.../agent/loop/ChatMemoryWriter.java` — add filter before calling `updateMessages()`

**Fix:** Move the filter to `ChatMemoryWriter.saveAnswer()`:
```java
// ChatMemoryWriter.java — before calling updateMessages():
List<ChatMessage> filtered = current.stream()
    .filter(msg -> !(msg instanceof AiMessage ai) || !MessageTag.isTagged(ai.text()))
    .collect(Collectors.toList());
chatMemoryStore.updateMessages(memoryId, filtered);
```

Remove the `MessageTag` import from `PersistentChatMemoryStore` entirely.

***

### Component C3 — `CHANNEL_SPI.md` Stale Reference Path Fix

**File:** `CHANNEL_SPI.md` (root)

**Problem:** The reference implementation path still points to the pre-P67 location:
```
arcclaw-app/src/main/java/com/arcclaw/app/channel/telegram/
```

**Fix:** Update to:
```
arcclaw-channel-telegram/src/main/java/com/arcclaw/channel/telegram/
```
Update all 6 file references in the "Reference Telegram Implementation" section accordingly.

***

### Verification — Tier C

```powershell
cd C:\Users\Srini\ws\ArcClawInternal
mvn clean test
# Expected: 877+ tests, 0 failures, 0 errors
rg "com\.arcclaw\.app\.channel\.telegram|com\.arcclaw\.app\.security\.TelegramAllowlist" arcclaw-app/src/main/java --type java
# Expected: 0 results
```

***
***

# PROMPT — PHASE 68-D: Architectural Improvements (Tier D)

**Role:** Senior Java Architect & Senior Java Developer
**Task:** Phase 68-D — Four targeted architectural improvements. Each is independently committable. No new Maven modules. No external dependencies.

***

### Component D1 — `FastExitException` Elimination

**Problem:** `ToolStepHandler.invokeTool()` uses `FastExitException extends RuntimeException` purely for control flow. This is the textbook control-flow-via-exception anti-pattern — explicitly noted in the codebase comments as "control flow only, suppress stacktrace."

**Files to modify:**
- `arcclaw-core/.../agent/loop/ToolStepHandler.java`

**Fix — introduce a sealed result type:**
```java
sealed interface InvokeResult permits InvokeResult.Responded, InvokeResult.ShortCircuit {
    record Responded(ToolResponse response) implements InvokeResult {}
    record ShortCircuit(StepDecision decision) implements InvokeResult {}
}
```

Change `invokeTool()` return type from `ToolResponse` (throws `FastExitException`) to `InvokeResult`. In `invokeToolAndObserve()`, pattern-match:
```java
InvokeResult invokeResult = invokeTool(...);
if (invokeResult instanceof InvokeResult.ShortCircuit sc) return sc.decision();
ToolResponse response = ((InvokeResult.Responded) invokeResult).response();
```

Delete `FastExitException` class entirely.

**Test:** `ToolStepHandlerApprovalTest` — assert that when `ToolInvoker.invoke()` throws `RequiresApprovalException`, the method returns a `TERMINAL` `StepDecision` without any exception propagating to the caller.

***

### Component D2 — `AgentExecutionContext` Propagation

**Problem:** `AgentExecutionContext` was created to solve the `(String sessionId, String agentRole, String userId, String chatId, String goal, AutonomyLevel)` data clump, but it was never threaded beyond `AgentStateMachineRunner.run()`. All internal methods still carry 4–6 raw strings.

**Files to modify:**
- `arcclaw-core/.../agent/loop/EngineStepHandler.java`
- `arcclaw-core/.../agent/loop/ToolStepHandler.java`
- `arcclaw-core/.../agent/loop/CheckpointResumeHandler.java`
- `arcclaw-core/.../agent/loop/ApprovalCheckpointManager.java`
- `arcclaw-core/.../agent/loop/AgentStepProcessor.java`
- `arcclaw-core/.../agent/loop/AgentStateMachineRunner.java` — update call sites

**Fix:** Change the following method signatures to accept `AgentExecutionContext ctx` instead of `(String sessionId, String agentRole, ...)`:

```
EngineStepHandler.processEngineStep(ctx, engine, history, transientError, ...)
ToolStepHandler.validateTool(ctx, history, step, consecutiveFailures)
ToolStepHandler.invokeToolAndObserve(ctx, history, step, consecutiveFailures, skillSnapshot)
CheckpointResumeHandler.handle(ctx, resumedHistory, messageContext)
ApprovalCheckpointManager.persist(ctx, toolName, paramsJson, history)
```

Inside each method, replace `sessionId` with `ctx.sessionId()`, `agentRole` with `ctx.agentRole()`, `goal` with `ctx.goal()`, `autonomy` with `ctx.autonomy()`.

**Test:** Existing tests remain green. No behavioral change — this is purely a signature consolidation. Add `AgentExecutionContextPropagationTest` asserting that the autonomy level from context is visible inside `ToolStepHandler.evaluateGuard()` without a separate parameter (regression guard for the READONLY fix from P67).

***

### Component D3 — `ObservationTransformerChain` — Make `ToolObservationSanitizer` Extensible

**Problem:** `ToolObservationSanitizer` hardcodes three sanitization passes in sequence. Adding a new pass (UTF-8 normalization, language filter) requires modifying the class.

**Files to create:**
- `arcclaw-core/.../agent/loop/ObservationTransformer.java` (interface)
- `arcclaw-core/.../agent/loop/TruncationTransformer.java`
- `arcclaw-core/.../agent/loop/InjectionRedactionTransformer.java`
- `arcclaw-core/.../agent/loop/PiiRedactionTransformer.java`

**Files to modify:**
- `arcclaw-core/.../agent/loop/ToolObservationSanitizer.java`

**Fix:**
```java
// ObservationTransformer.java
public interface ObservationTransformer {
    int priority();
    String transform(String toolName, String input);
}

// Each transformer is @ApplicationScoped with a fixed priority()

// ToolObservationSanitizer.java
@Inject Instance<ObservationTransformer> transformers;

public String sanitize(String toolName, String observation) {
    List<ObservationTransformer> ordered = transformers.stream()
        .sorted(Comparator.comparingInt(ObservationTransformer::priority))
        .toList();
    String result = observation;
    for (ObservationTransformer t : ordered) result = t.transform(toolName, result);
    return result;
}
```

Priorities: `TruncationTransformer=10`, `InjectionRedactionTransformer=20`, `PiiRedactionTransformer=30`.

**Test:** `ToolObservationSanitizerChainTest` — inject a mock `ObservationTransformer` with priority 15 and assert it fires between truncation and injection redaction steps.

***

### Component D4 — `AgentReflex` ISP Split

**Problem:** `AgentReflex` combines three contracts: `matches()` (routing), `execute()` (handling), `sanitize()` (transformation). `PiiScanReflex` is injected **concretely** in 5 classes, bypassing the interface entirely.

**Files to create:**
- `arcclaw-core/.../reflex/InputSanitizer.java` (interface: `String sanitize(String input)`)

**Files to modify:**
- `arcclaw-core/.../reflex/PiiScanReflex.java` — implement `InputSanitizer`
- `arcclaw-core/.../reflex/PromptInjectionReflex.java` — implement `InputSanitizer`
- 5 classes with `@Inject PiiScanReflex` — change injection type to `@Inject InputSanitizer`
  - `AgentStateMachineRunner`, `ChatMemoryWriter`, `ToolObservationSanitizer`, `ReflexRegistry`, `ChatHistoryAssembler`

`AgentReflex` itself remains unchanged — the split is additive. `PiiScanReflex` implements both `AgentReflex` and `InputSanitizer`.

**Test:** `InputSanitizerInjectionTest` — assert that all 5 injection points resolve to `PiiScanReflex` via the `InputSanitizer` interface without any `@Inject PiiScanReflex` concrete reference remaining.

***

### Verification — Tier D (Full Suite)

```powershell
cd C:\Users\Srini\ws\ArcClawInternal
mvn clean test
# Expected: 877+ tests, 0 failures, 0 errors

# Confirm no SKILL_PREFIX duplicate remains after B1:
rg "SKILL_PREFIX|startsWith\(\"skill:" arcclaw-core/src arcclaw-app/src --type java
# Expected: only AgentRoleEnum.java contains the "skill:" literal

# Confirm no CRON inline check remains after B2:
rg "startsWith\(\"CRON\"\)\|\"CRON \"\s*\+" arcclaw-core/src arcclaw-app/src --type java
# Expected: 0 results

# Confirm no concrete PiiScanReflex injection after D4:
rg "@Inject\s+PiiScanReflex" arcclaw-core/src arcclaw-app/src --type java
# Expected: 0 results (only InputSanitizer injection remains)
```

***

## Execution Order

| Phase | Scope | Risk | Commit gate |
|---|---|---|---|
| **68-A** | 6 correctness/safety bugs | Low — surgical | Green before 68-B |
| **68-B** | SOLID + magic strings | Medium — signature changes | Green before 68-C |
| **68-C** | Dead code + doc | Very low | Green before 68-D |
| **68-D** | Architecture | Medium — refactors | Green, full suite |
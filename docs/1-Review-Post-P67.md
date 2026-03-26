This is a genuine, deep, honest review — no praise padding. Every finding is specific to actual code found in the P67 file. 

***
## SOLID Violations
### S — SRP Breaches (9 classes doing too many jobs)
**`LangChainReasoningEngine`** is the worst offender. It handles 6 unrelated concerns inside one `@ApplicationScoped` class: system prompt delegation, history assembly, tool spec filtering, LLM calling, prose extraction from vendor error payloads, rate-limit cause-chain walking, and governance recording. The `isProseGenerationFailure()`, `extractProseFromFailedGeneration()`, `isSchemaValidationError()`, `isRateLimitError()`, and `findRateLimitMessage()` methods are all distinct, independently-testable responsibilities crammed into one class. 

**`ToolRegistry.invokeTool()`** is a 100-line method doing six things: tool lookup → role-capability hard block → CDI proxy unwrapping for `@RequiresSecret` reflection → autonomy policy check → `RequiresApprovalException` throw → sandbox branch → audit recording. Any one of these should be a standalone class. 

**`AgentStateMachineRunner.handleFinishAction()`** makes three completely separate decisions: (1) block code generation for researcher/architect roles, (2) trigger synthesizer multi-agent synthesis path, (3) persist answer. A finish handler should not know about persona-specific code-generation policy. 

**`PersistentChatMemoryStore.getMessages()`** does deserialization + system-message partitioning + rolling-window pruning + returns all in one method. Three responsibilities, one method. 

**`CompactionSummaryRepository.searchMemory()`** embeds the FTS5 query sanitizer (`sanitizeForFts5()`) directly inside the repository. Sanitization is not a persistence concern. 

**`SystemPromptBuilder`** holds a 200-line `SECURITY_RULES_PROMPT` static constant. Security policy definitions are not a builder responsibility. 
### O — OCP Breaches (4 switch statements that must be modified to extend)
**`ChatModelProducer.chatModel()`** — a 3-case switch on `"groq"`, `"openai"`, `"ollama"`. Adding DeepSeek, Anthropic, or Mistral means modifying this class. The `EmbeddingProviderFactory` SPI you just built in P65 is exactly the fix needed here — apply it. 

**`PromptTemplateEngine.templateFor()`** — a 6-case switch on `role.id()`. Adding a new built-in role (`auditor`, `planner`) requires modifying this class. The fix: use `@Location("prompts/role-" + role.id())` with a programmatic template resolver instead of `@Inject`-per-template. 

**`AgentStateMachineRunner.handleFinishAction()`** — hardcodes `AgentRoleEnum.RESEARCHER.toScopedName().equals(agentRole)` and `AgentRoleEnum.ARCHITECT.toScopedName().equals(agentRole)` for code-block detection. Every new non-coder role requires modifying this method. Fix: add `boolean canGenerateCode()` to `RoleDefinition` — the runner asks the role, not the string. 

**`EngineStepHandler.handleEngineException()`** — ~80-line `if/else if` chain with Groq-specific strings (`"tool_use_failed"`, `"was not in request.tools"`, `"expected string, but got null"`) baked in. Every new LLM vendor's error vocabulary requires modifying this class. 
### L — LSP Breaches
**`ReasoningEngine.chatForStructuredOutput()`** has a `default` that throws `UnsupportedOperationException`. This makes the interface non-substitutable — any test mock that doesn't override this method will throw at runtime when `StructuredOutputEngine` calls it. Tests are not written yet, but when they are, this will silently mislead. 

**`Agent.synthesize()`** defaults to `UnsupportedOperationException`. Five of six agents (`VishnuAgent`, `BrahmaAgent`, `ShivaAgent`, `SaraswatiAgent`, `BrihaspatiAgent`) cannot be used as `Agent` through the `synthesize()` contract. The correct design is a separate `SynthesizingAgent extends Agent` interface that only `DattatreyaAgent` implements. 
### I — ISP Breaches
**`AgentReflex`** combines three contracts: `matches()` (routing), `execute()` (command handling), `sanitize()` (input transformation). These are not the same responsibility. `PiiScanReflex` exists primarily as a sanitizer and is injected **concretely** (`@Inject PiiScanReflex`) in at least 5 classes: `AgentStateMachineRunner`, `ChatMemoryWriter`, `ToolObservationSanitizer`, `ReflexRegistry`, `ChatHistoryAssembler`. This bypasses `AgentReflex` entirely and creates direct coupling to a concrete class. 

**`StatefulAgentStore`** interface exposes `loadSteps(Long checkpointId)` — a `Long` is a database internal row ID. This bleeds JDBC/JPA IDs through the domain interface, making the contract impossible to implement over a document store or Redis without inventing a fake numeric ID scheme. 
### D — DIP Breaches
**`ToolRegistry`** does `private final AutonomyPolicy autonomyPolicy = new AutonomyPolicy.Default()` — constructed with `new`, not injected. You cannot swap the autonomy policy without modifying `ToolRegistry`. 

**`AgentStateMachineRunner`** does `@Inject @AgentRole("synthesizer") Agent synthesizerAgent` — the orchestrator takes a hard CDI dependency on a specific persona bean. If Dattatreya is ever replaced or renamed, the runner breaks. The runner should call `agentPersonaRegistry.resolve(AgentRoleEnum.SYNTHESIZER).synthesize(...)` instead. 

***
## Code Smells
### Magic String Proliferation
The `"skill:"` prefix constant is defined independently as `private static final String SKILL_PREFIX = "skill:"` in **six separate classes**: `AgentLoop`, `SystemPromptBuilder`, `AgentDispatchHandler`, `LangChainReasoningEngine`, `AgentRoleEnum`, `ToolInvocationGuard`. A rename requires touching all six. One `AgentRoleEnum.isSkillRole(String r)` static method eliminates this. 

The `"CRON "` goal prefix is written as a string literal in `ScheduleTaskTool` (`entity.goal = "CRON " + goal`) and checked with `goal.toUpperCase().startsWith("CRON")` in `LangChainReasoningEngine` and `AgentCronScheduler`. It is not in `AgentSentinels`. One `AgentSentinels.CRON_PREFIX = "CRON "` constant fixes three sites. 

`resolveUserId()` in `ToolRegistry` returns `"internal"` as a fallback string. That string is never checked by any code downstream — `AuditService.record()` stores it verbatim, making audit log queries on `userId = 'internal'` silently include background cron runs. Should be a typed `UserId.INTERNAL` constant or `Optional`. 
### Primitive Obsession
`AgentExecutionContext` was created to solve the `(String sessionId, String agentRole, String userId, String chatId, String goal, AutonomyLevel)` data clump — but only `AgentStateMachineRunner.run()` uses it. All internal methods in `EngineStepHandler`, `ToolStepHandler`, `CheckpointResumeHandler`, `ApprovalCheckpointManager`, `ToolInvocationGuard` still carry 4–6 raw strings. The value object was extracted but never propagated. 

`int consecutiveFailures` travels in `StepDecision` as a raw int. This is a circuit-breaker state: it has semantics (reset to 0 on success, increment on failure, terminal at threshold=2). It should be a `CircuitBreakerState` value object with `increment()`, `isTripped()`, `reset()`. 
### Feature Envy
`AgentStateMachineRunner.handleFinishAction()` has to know that `researcher` and `architect` are non-code-generating roles. It's envious of `RoleDefinition`. The role knows its own constraints — the runner shouldn't. Fix: `role.definition().canGenerateCode()` boolean. 

`ChatHistoryAssembler.assemble()` checks `msg.toString().contains(AgentSentinels.GOAL_TOOL_APPROVED)`. This is approval-flow logic leaking into a chat-message serializer. The assembler should receive an already-filtered list from a `ChatHistoryFilter`. 
### Silent Exception Swallowing — Dangerous
In `ChatHistoryAssembler`, the tool args serialization block does:
```java
catch (Exception e) {
    // Ignore serialization errors
}
```
When `argsJson` stays `null` or empty, `ToolExecutionRequest` is built with empty arguments. The LLM then retries with no params, producing ghost tool calls. This is not "ignore" — it's a silent corruption of the reasoning trace. At minimum, `argsJson = "{}"` with a log. 

The `catch (IllegalArgumentException e) { /* Unknown role fail closed */ }` in `ToolRegistry.invokeTool()` after `AgentRoleEnum.fromString()` is **dead code** — `fromString()` never throws `IllegalArgumentException` (it creates a dynamic role). This catch block has never fired and never will. 
### Control-Flow Exception Anti-Pattern
`FastExitException extends RuntimeException` in `ToolStepHandler` is explicitly documented as "control flow only, suppress stacktrace." Using exceptions for control flow is the textbook anti-pattern. The fix is `ToolInvoker.invoke()` returning `Either<StepDecision, ToolResponse>` or simply `Optional<StepDecision>` (empty = proceed, present = exit). 
### God Object — `ToolRegistry`
Five distinct concerns: CDI tool discovery, role-capability hard block (should be `ToolInvocationGuard`), autonomy policy enforcement (should be `AutonomyPolicyEnforcer`), `@RequiresSecret` reflection + CDI proxy unwrapping (`ToolSecretExtractor`), sandbox routing, audit recording. The role-capability check alone (`agentRole != null ? AgentRoleEnum.fromString(agentRole).definition().capabilities().stream()...`) is a `ToolInvocationGuard` responsibility duplicated here. 
### Inappropriate Intimacy
**`PersistentChatMemoryStore.updateMessages()`** filters `MessageTag`-prefixed `AiMessage` entries:
```java
.filter(msg -> !(msg instanceof AiMessage ai) || !MessageTag.isTagged(ai.text()))
```
The memory store should not know about the compliance tagging system. The only caller that injects compliance messages is `ChatMemoryWriter` — the filter belongs there before calling `updateMessages()`. 
### Connascence of Timing — Startup Race
`RagSchemaInitializer.onStart()` and `SqliteWalConfigurer.onStartup()` both observe `StartupEvent` with no `@Priority` ordering. `RagSchemaInitializer` acquires a pooled connection to warm up `vec0`. `SqliteWalConfigurer` attaches the `fts` schema on the same pool. If Jandex scan order puts `RagSchemaInitializer` first, it fires before `fts` is attached — vec0 warm-up works but FTS5 extension queries on that same connection will fail until the next connection is acquired. This is a non-deterministic build-order bug. 
### Dead Code Inventory
| Dead Code | Reason |
|---|---|
| `ScopedCounter` | Test bean in production `arcclaw-core` module; no business use |
| `ApprovalTimeoutNotifier` | `@Deprecated @ApplicationScoped` empty class; `ChannelApprovalTimeoutDispatcher` replaced it |
| `@AgentRole` qualifier annotation | `AgentPersonaRegistry` loop-matches by string, never uses CDI qualifier; purely decorative |
| `WorkspaceExecutors.wrap()` | No caller in codebase wraps an existing executor; only `newVirtualThreadPerTaskExecutor()` is called |
| `ArcClawInfo` | Two constants (`NAME`, `VERSION`) with no callers; version is in `pom.xml` |
| `RoleDefinition.aliases` | Registered (`"vishnu"`, `"brahaspati"`) but no Telegram command, REST call, or test ever routes via alias |

 

***
## Gang of Four Pattern Gaps
### Strategy — Missing Where It Matters Most
**LLM error handling**: `handleEngineException()` is a flat if-chain with Groq-specific detection logic. This should be a `List<LlmErrorHandler>` where each implements `boolean canHandle(Exception)` and `StepDecision toDecision(Exception, context)`. Chain of Responsibility. New vendor errors are additive with zero changes to `EngineStepHandler`. 

**Autonomy policy**: `AutonomyPolicy` is instantiated with `new AutonomyPolicy.Default()` inside `ToolRegistry`. It cannot be swapped, decorated, or tested with a stricter policy. It should be `@ApplicationScoped` and injected — making it a proper Strategy bean. 
### Decorator — `ToolObservationSanitizer` Not Extensible
The sanitizer does three passes (truncate → injection-redact → PII-redact) hardcoded in sequence. Adding UTF-8 normalization or language-specific filters requires modifying the class. The fix is an `ObservationTransformer` interface with `String transform(String toolName, String input)` — three concrete CDI beans (`TruncationTransformer`, `InjectionRedactionTransformer`, `PiiRedactionTransformer`) discovered via `Instance<ObservationTransformer>`. 
### Observer — Double-Firing Risk
`Instance<AgentObserver>` is injected in `AgentLoop`, `AgentStateMachineRunner`, and `EngineStepHandler`. `AgentOtelContext` implements `AgentObserver` (OTel span recording). In `EngineStepHandler`, after every LLM step, the code calls both `observers.forEach(o -> o.recordLlmCall(...))` AND `otelContext.recordLlmCall(...)` directly. If `AgentOtelContext` is in the CDI observer set (which it is, since it's `@ApplicationScoped` implementing `AgentObserver`), OTel tokens are recorded twice per step. 
### Template Method — Missing `@ActivateRequestContext`
`AgentLoop.run(String goal, ReasoningEngine engine)` and `AgentLoop.run(String goal, ReasoningEngine engine, int maxIterations)` are convenience overloads that delegate to the full `@ActivateRequestContext`-annotated 6-arg `run()`. But these two-arg and three-arg overloads have no `@ActivateRequestContext` themselves. Callers that bypass the full path (e.g., some test scaffolding or the cron scheduler calling the minimal overload) will not activate a CDI request context, causing `@RequestScoped` beans to throw `ContextNotActiveException` at runtime. 
### Facade — `AgentStepProcessor` Leaks Internals
`AgentStepProcessor` is correctly identified as a Facade. But its internal delegates (`EngineStepHandler`, `ToolStepHandler`, `ApprovalCheckpointManager`) are all `@Inject`-exposed fields inside the facade — any class that imports `AgentStepProcessor` can still reach them via its public API since they're package-private, not private. `AgentStateMachineRunner` references `StepDecision` directly from the inner enum of `AgentStateMachineRunner` — the facade is in the same package so the intent of hiding is already defeated. 
### Chain of Responsibility — `ReflexRegistry` Pass Design Flaw
The two-pass design (pass 1: first match fires `execute()`; pass 2: ALL reflexes run `sanitize()`) means the sanitization pass always runs ALL reflexes even if the command was already short-circuited by an earlier `execute()`. For a RESET command, `PromptInjectionReflex.sanitize()` runs on the already-handled input — wasting CPU and potentially modifying a string that's already been acted on. 

***
## YAGNI Violations
**`RoleDefinition.aliases`**: The set (`"vishnu"`, `"brahaspati"`, `"saraswati"` etc.) is populated and iterated in `AgentRoleEnum.fromString()`. There is no caller in the entire codebase that routes using a deity name — Telegram uses `/agent researcher`, REST uses role strings, Qute templates use `role.id()`. This alias resolution code has never fired in production. 

**`AgentResult.FailureCategory` has 6 values** where behavior branches on 3: `BUDGET_EXCEEDED` (throw), `RATE_LIMIT` (surface clean message), everything else (log + return Failed). `COMPLIANCE_VIOLATION` and `SCHEMA_ERROR` and `USER_DENIED` all result in the same `Failed` pattern at the channel layer — the discriminator is unused downstream. 

**`WorkspaceExecutors.wrap(ExecutorService delegate)`**: The `wrap()` overload exists "for developers who might accidentally create a raw executor." There is not a single call to `wrap()` in the codebase — only `newVirtualThreadPerTaskExecutor()` is used. 

**`ScopedCounter`** is a `@WorkspaceScoped` bean in `arcclaw-core/scope` with no callers except presumably a deleted test. It allocates an `AtomicInteger` per workspace session. 

***
## Refactoring Prompts (Copy-Paste Ready)
These are precise, actionable prompts targeting the specific issues found above: 

***
### 🔴 P1 — `RoleDefinition.canGenerateCode()` — removes role-name hardcoding in finish handler
> **Add a `canGenerateCode` boolean field to `RoleDefinition`. Set it to `false` for `researcher` and `architect`, `true` for all others. In `AgentStateMachineRunner.handleFinishAction()`, replace the two `AgentRoleEnum.RESEARCHER.toScopedName().equals(agentRole)` and `AgentRoleEnum.ARCHITECT.toScopedName().equals(agentRole)` checks with a single `AgentRoleEnum.fromString(agentRole).definition().canGenerateCode()` check. Update the `RoleDefinition` compact constructor to default `canGenerateCode` to `true` when not specified.**

***
### 🔴 P1 — `AgentRoleEnum.isSkillRole(String)` — eliminates 6 duplicate `SKILL_PREFIX` constants
> **Add a `public static boolean isSkillRole(String roleStr)` method to `AgentRoleEnum` that returns `roleStr != null && roleStr.startsWith("skill:")`. Move the `"skill:"` literal inside this one method. Remove `private static final String SKILL_PREFIX = "skill:"` from `AgentLoop`, `SystemPromptBuilder`, `AgentDispatchHandler`, `LangChainReasoningEngine`, and `ToolInvocationGuard`. Replace all `agentRole.startsWith(SKILL_PREFIX)` / `agentRole.startsWith("skill:")` calls with `AgentRoleEnum.isSkillRole(agentRole)`. Do the same for the skill-name extraction: add `public static String skillName(String roleStr)` that returns `roleStr.substring("skill:".length())`.**

***
### 🔴 P1 — `AgentSentinels.CRON_PREFIX` — removes 3 fragile inline string checks
> **Add `public static final String CRON_PREFIX = "CRON "` to `AgentSentinels`. In `ScheduleTaskTool`, replace `"CRON " + goal` with `AgentSentinels.CRON_PREFIX + goal`. In `LangChainReasoningEngine` and `AgentCronScheduler`, replace `goal.toUpperCase().startsWith("CRON")` with `goal.startsWith(AgentSentinels.CRON_PREFIX)` (no `toUpperCase` needed since the prefix is always written uppercase).**

***
### 🔴 P1 — `LlmProviderFactory` SPI — closes the hardcoded `ChatModelProducer` switch
> **Create interface `LlmProviderFactory` in `arcclaw-core` with methods `String providerId()` and `ChatModel createModel(LlmModelConfig config)`. Create `LlmModelConfig` record with `provider`, `modelName`, `baseUrl`, `apiKey`, `timeout`. Create three `@ApplicationScoped` CDI beans: `GroqLlmProviderFactory`, `OpenAiLlmProviderFactory`, `OllamaLlmProviderFactory` each implementing `LlmProviderFactory`. Refactor `ChatModelProducer.chatModel()` to use `Instance<LlmProviderFactory>` — filter by `providerId()`, call `createModel()`, throw `IllegalStateException` if none found. Delete the `switch` statement entirely.**

***
### 🟠 P2 — `FastExitException` elimination — removes control-flow exception anti-pattern
> **In `ToolStepHandler.invokeTool()`, change the return type from `ToolResponse` to `Either<StepDecision, ToolResponse>` (or use `sealed interface InvokeResult permits InvokeResult.Approved, InvokeResult.ShortCircuit`). When `RequiresApprovalException` is caught, return `InvokeResult.ShortCircuit(checkpointManager.persist(...))` instead of throwing `FastExitException`. In `invokeToolAndObserve()`, pattern-match on the result. Delete `FastExitException` entirely.**

***
### 🟠 P2 — `AutonomyPolicy` CDI injection — closes DIP violation in `ToolRegistry`
> **Annotate `AutonomyPolicy.Default` with `@ApplicationScoped`. Add a constructor-injected `AutonomyPolicy autonomyPolicy` field to `ToolRegistry`. Remove `private final AutonomyPolicy autonomyPolicy = new AutonomyPolicy.Default()`. This makes the policy mockable in tests and swappable for a per-tenant policy via CDI `@Alternative`.**

***
### 🟠 P2 — `ObservationTransformerChain` — makes `ToolObservationSanitizer` extensible
> **Extract interface `ObservationTransformer` with single method `String transform(String toolName, String input)`. Create three `@ApplicationScoped` beans with `@Priority` ordering: `TruncationTransformer` (priority 10), `InjectionRedactionTransformer` (priority 20), `PiiRedactionTransformer` (priority 30). In `ToolObservationSanitizer`, inject `Instance<ObservationTransformer>` and sort by priority. Replace the three hardcoded steps in `sanitize()` with a loop over the sorted transformers. Adding a new sanitization step requires zero changes to this class.**

***
### 🟠 P2 — `SynthesizingAgent` interface — fixes LSP violation on `Agent.synthesize()`
> **Extract `public interface SynthesizingAgent extends Agent` with `AgentResult synthesize(String sessionId, String combinedContext, List<ReasoningStep> history)`. Remove the `default` `synthesize()` from `Agent` that throws `UnsupportedOperationException`. Make `DattatreyaAgent implements SynthesizingAgent`. In `AgentStateMachineRunner`, change `@Inject @AgentRole("synthesizer") Agent synthesizerAgent` to `@Inject SynthesizingAgent synthesizerAgent`. This makes the injection type-safe and removes the UnsupportedOperationException trap on all other agents.**

***
### 🟡 P3 — Compliance filter relocation — removes inappropriate intimacy in `PersistentChatMemoryStore`
> **Remove the `MessageTag.isTagged()` filter from `PersistentChatMemoryStore.updateMessages()`. Add it instead to `ChatMemoryWriter.saveAnswer()` before calling `chatMemoryStore.updateMessages()` — filter the current history before appending the new AI message. The memory store should persist exactly what it receives with no knowledge of compliance tagging semantics.**

***
### 🟡 P3 — Startup `@Priority` on RAG and FTS warmup — eliminates timing race
> **Add `@Priority(Interceptor.Priority.APPLICATION + 50)` to `SqliteWalConfigurer.onStartup()` and `@Priority(Interceptor.Priority.APPLICATION + 100)` to `RagSchemaInitializer.onStart()`. This guarantees WAL configuration and FTS schema attachment always complete before vec0 warm-up fires, regardless of Jandex scan order across builds.**

***
### 🟡 P3 — Fix silent `argsJson` swallow in `ChatHistoryAssembler`
> **In `ChatHistoryAssembler.assemble()`, replace the empty `catch (Exception e) { // Ignore serialization errors }` with `catch (Exception e) { argsJson = "{}"; log.warnf("[ChatHistoryAssembler] Failed to serialize tool args for step %d — using empty params. Cause: %s", i, e.getMessage()); }`. Empty args are better than null args — the LLM will retry with no params rather than producing a malformed `ToolExecutionRequest`.**

***
### 🟡 P3 — Remove dead `catch (IllegalArgumentException)` in `ToolRegistry`
> **`AgentRoleEnum.fromString()` never throws `IllegalArgumentException` — it returns a dynamic role. The `catch (IllegalArgumentException e) { /* Unknown role fail closed */ }` block in `ToolRegistry.invokeTool()` is dead code. Remove it. If you want fail-closed for truly unknown roles, use `AgentRoleEnum.isKnownRole(agentRole)` as an explicit guard before the capability check.**

***
### 🟡 P3 — Dead code purge list
> **Delete the following in one PR: `ScopedCounter.java` (test bean in prod), `ApprovalTimeoutNotifier.java` (`@Deprecated @ApplicationScoped` empty shell), `ArcClawInfo.java` (two unused constants), the `aliases` set initialization in all six `RoleDefinition` entries (no caller routes via deity name), and `WorkspaceExecutors.wrap(ExecutorService)` (no caller).**

***
### 🟢 P4 — `AgentLoopContext` propagation — finishes the primitive obsession fix
> **`AgentExecutionContext` already exists but is only used at the entry of `AgentStateMachineRunner.run()`. Thread it through: change `EngineStepHandler.processEngineStep()`, `ToolStepHandler.validateTool()`, `ToolStepHandler.invokeToolAndObserve()`, `CheckpointResumeHandler.handle()`, and `ApprovalCheckpointManager.persist()` to accept `AgentExecutionContext` instead of `(String sessionId, String agentRole, ...)`. Reduces every method signature by 2–4 parameters and makes the data flow explicit.**

***
### 🟢 P4 — `AgentReflex` ISP split
> **Split `AgentReflex` into two interfaces: `ReflexCommand` (`matches()` + `execute()`) and `InputSanitizer` (`sanitize()`). `PiiScanReflex` implements only `InputSanitizer`. `PromptInjectionReflex` implements both. `ApproveReflex`, `DenyReflex`, `StatusReflex`, `ResetReflex` implement only `ReflexCommand`. In all five classes that currently `@Inject PiiScanReflex` concretely, change the injection type to `@Inject InputSanitizer piiSanitizer`. In `ReflexRegistry`, maintain two typed lists — no more reflection-based instance check.**
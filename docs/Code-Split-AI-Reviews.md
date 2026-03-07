Gemini:

Looking at the codebase, **`AgentCronScheduler.java`** is indeed the heavy lifter. Currently, it acts as a "God Object" because it takes on multiple distinct responsibilities in the background task lifecycle. This violates the Single Responsibility Principle (SRP) and makes testing and debugging harder.

Here is a breakdown of its current responsibilities:

1. **Polling & Concurrency:** Reading from the database, claiming jobs, and managing semaphore limits.
2. **Context Bootstrapping:** Setting up the `WorkspaceContext`, isolated session IDs, and dev secrets.
3. **Agent Invocation:** Executing the AI logic via `agentLoop.run()`.
4. **State Management:** Updating the database based on results (Completed, Fired, Awaiting Approval, Failed).
5. **Observability & Events:** Emitting audit records and firing CDI events for Telegram delivery.

To make this maintainable, you can split this large class into three distinct, loosely coupled classes using the Facade or Delegate pattern:

### 1. `AgentCronScheduler.java` (The Dispatcher)

Keep this class strictly focused on timing and concurrency. It should only contain the `@Scheduled` method. Its job is to poll the database, acquire a semaphore permit, and hand the raw job off to the execution layer on a virtual thread.

```java
@ApplicationScoped
public class AgentCronScheduler {
    // Inject ONLY what is needed for polling and dispatching
    @Inject AgentCronRepository cronRepository;
    @Inject ManagedExecutor vThreads;
    @Inject CronJobRunner jobRunner; // <-- New injected component

    Semaphore concurrencyLimiter;

    @Scheduled(every = "${arcclaw.cron.poll-interval:30s}")
    @ActivateRequestContext
    void pollAndDispatch() {
        cronRepository.reclaimStaleCrons(...);
        List<AgentCronEntity> due = cronRepository.findDueCrons();

        for (AgentCronEntity job : due) {
            if (!cronRepository.claimForFire(job.id)) continue;
            if (!concurrencyLimiter.tryAcquire()) { ... continue; }

            vThreads.execute(() -> {
                try {
                    // Hand off to the runner
                    jobRunner.execute(job); 
                } finally {
                    concurrencyLimiter.release();
                }
            });
        }
    }
}

```

### 2. `CronJobRunner.java` (The Execution Context)

Extract the `executeCronJob` method into its own class. This class becomes responsible for setting up the CDI/Workspace scopes and invoking the Large Language Model via the `AgentLoop`. It isolates the "Business Logic" from the "Scheduling Logic".

```java
@ApplicationScoped
public class CronJobRunner {
    @Inject AgentLoop agentLoop;
    @Inject LangChainReasoningEngine engine;
    @Inject CronResultHandler resultHandler; // <-- New injected component

    public void execute(AgentCronEntity job) {
        String executionId = UUID.randomUUID().toString();
        String cronSessionId = "cron-" + job.id + "-" + executionId;

        try {
            // Setup isolated context
            AgentResult result = WorkspaceContext.run(cronSessionId, job.userId, job.chatId, AutonomyLevel.BACKGROUND, AgentRoleEnum.COORDINATOR.toScopedName(), () -> {
                String effectiveGoal = job.goal + "\n[SYSTEM CONSTRAINT...]";
                return agentLoop.run(cronSessionId, AgentRoleEnum.COORDINATOR.toScopedName(), effectiveGoal, engine, 2);
            });
            
            // Pass successful results to the handler
            resultHandler.handleSuccess(job, cronSessionId, executionId, result);

        } catch (Throwable t) {
            // Pass exceptions to the handler
            resultHandler.handleFailure(job, cronSessionId, executionId, t);
        }
    }
}

```

### 3. `CronResultHandler.java` (State & Telemetry Manager)

The database updates (markFired, markCompleted), Audit logging, and CDI event firing (for Telegram notifications) take up nearly 100 lines of complex `if/else` logic in your current setup. Moving this to a dedicated handler drastically cleans up the workflow.

```java
@ApplicationScoped
public class CronResultHandler {
    @Inject AgentCronRepository cronRepository;
    @Inject AuditService auditService;
    @Inject Event<AgentJobCompleted> jobCompletedEvent;
    @Inject WorkspaceManager workspaceManager;

    public void handleSuccess(AgentCronEntity job, String cronSessionId, String executionId, AgentResult result) {
        boolean wasApprovalRequested = result instanceof AgentResult.AwaitingApproval;
        
        if (!wasApprovalRequested) {
            if (job.runOnce) {
                cronRepository.markCompleted(job.id, Instant.now());
                // ... fire audit log
            } else {
                Instant next = CronScheduleUtil.computeNext(job.cronExpression, job.timezone);
                cronRepository.markFired(job.id, Instant.now(), next);
                // ... fire audit log
            }
        } else {
            cronRepository.markAwaitingApproval(job.id);
        }

        // Fire CDI Event for Telegram/CLI
        jobCompletedEvent.fire(new AgentJobCompleted(...));
        
        // Handle Session Termination
        if (!wasApprovalRequested) {
            workspaceManager.terminateSession(cronSessionId);
        }
    }

    public void handleFailure(AgentCronEntity job, String cronSessionId, String executionId, Throwable t) {
        cronRepository.markFailed(job.id, t.getMessage());
        // ... fire audit log
        // ... fire failure event
        workspaceManager.terminateSession(cronSessionId);
    }
}

```

### Why this structure works best:

* **Easier Testing:** You can mock `CronJobRunner` to test the concurrency limits of the Scheduler without initializing the LangChain API. You can test `CronResultHandler` logic without running any threads or LLM loops.
* **Transaction Boundaries:** It becomes much easier to apply `@Transactional` boundaries specifically around the DB update logic in `CronResultHandler` without keeping long-running database transactions open while the LLM is thinking in the `CronJobRunner`.

---

Perplexity Claude:

Based on a thorough analysis of the full codebase, here is a senior architect-level review of the biggest classes and concrete refactoring recommendations.

***

## Top Candidates for Splitting

### 1. `LangChainReasoningEngine` — Largest Class 🔴

This is the most complex class in the codebase. Its `nextStep()` method alone handles **5 distinct concerns**:

- Building the system prompt (base + role + security rules + strict tool binding)
- Injecting long-term chat history with compliance filtering
- Building the short-term ReAct loop (Thought-Action-Observation messages)
- Filtering compliance overrides (jailbreak detection, retroactive revert)
- Making the actual LLM API call and parsing the response

**Suggested split:**

| New Class | Responsibility |
|---|---|
| `ChatHistoryAssembler` | Assembles `List<ChatMessage>` from persistent store + ReAct loop history |
| `ComplianceMessageFilter` | All `skip` logic — `MessageTag`, `COMPLIANCE_OVERRIDE_PATTERN`, system noise filtering |
| `SystemPromptBuilder` | Combines base + persona + `SECURITY_RULES_PROMPT` + `strictBinding` into the final `SystemMessage` |
| `LangChainReasoningEngine` (slimmed) | Only: invoke LLM, parse response, return `ReasoningStep` |

***

### 2. `AgentStateMachineRunner` — God Method in `run()` 🔴

The `run()` loop dispatches to three private methods (`processEngineStep`, `validateTool`, `invokeToolAndObserve`) but the class still owns **all cross-cutting orchestration logic**:

- Deduplication guard (full JSON comparison logic in `invokeToolAndObserve`)
- GitHub Tool Intent Guard (regex + `GITHUB_TRIGGER_PATTERN`)
- HITL approval pause
- Fast-path scheduling exit
- Circuit breaker (consecutive failure counting)
- `saveGoalToChatMemory` (chat persistence inside the runner)

**Suggested split:**

| New Class | Responsibility |
|---|---|
| `ToolInvocationGuard` | Dedup guard, GitHub intent guard, HITL approval check — returns a `GuardDecision` |
| `ToolInvoker` | Actual `toolRegistry.invokeTool(...)` call + `RequiresApprovalException` handling |
| `ChatMemoryWriter` | `saveGoalToChatMemory` + post-FINISH answer persistence (currently scattered across `handleFinishAction` and `invokeToolAndObserve`) |
| `AgentStateMachineRunner` (slimmed) | Only the `while` loop + `StepDecision` dispatch |

***

### 3. `AgentLoop` — Mixed Lifecycle Concerns 🟡

Although it was already partially refactored (delegating to `AgentStateMachineRunner`), `AgentLoop.run(sessionId, agentRole, goal, engine, maxIterations)` still interleaves:

- Checkpoint resume logic (`handleResumeHook` is 60+ lines)
- Tool execution on resume path (`toolRegistry.invokeTool` directly inside a lifecycle class)
- `agentRole` string-to-enum resolution
- Three different `run(...)` overloads creating potential confusion

**Suggested split:**

| New Class | Responsibility |
|---|---|
| `CheckpointResumeHandler` | Extract `handleResumeHook()` entirely — encapsulates approval/denial state machine |
| `AgentLoop` (slimmed) | Only MDC setup, OTel span management, scope activation, and final checkpoint persistence |

***

### 4. `WorkspaceManager` — Mixed Scheduling + Lifecycle 🟡

This class mixes session heartbeat tracking, TTL-based GC sweeps, zombie recovery on startup, and termination logic all in one `@ApplicationScoped` bean. The `@Scheduled` sweep methods are a separate concern from the `terminateSession()` API.

**Suggested split:**

| New Class | Responsibility |
|---|---|
| `SessionHeartbeatTracker` | `recordHeartbeat`, `removeHeartbeat`, `activeSessionCount`, `clearAll` |
| `SessionReaper` | The `@Scheduled` sweep + zombie recovery + orphan cleanup on startup |
| `WorkspaceManager` (slimmed) | Only `terminateSession()`, workspace directory deletion, CDI scope destruction |

***

### 5. `AuditService` — Two Separate Tiers Mixed 🟡

`AuditService` has two clearly distinct tiers mixed in one class:
- **TELEMETRY** tier: fire-and-forget, swallows exceptions (`record()`, `recordLoopCompletion()`)
- **MANDATORY** tier: fail-closed, throws `ComplianceViolationException` (`recordMandatory()`)

**Suggested split:**

| New Class | Responsibility |
|---|---|
| `TelemetryAuditService` | All `TELEMETRY` tier writes — `record()`, `recordLoopCompletion()` |
| `ComplianceAuditService` | Only `recordMandatory()` — isolated, so a failure here is impossible to accidentally swallow |

***

## Package-Level Recommendation

The `com.arcclaw.core.agent` package currently holds agents, the engine, state machine, registry, role enums, persona registry, and result types — it is doing too much. Consider splitting into sub-packages:

```
com.arcclaw.core.agent.persona      → Agent impls, AgentPersonaRegistry, AgentRole
com.arcclaw.core.agent.loop         → AgentLoop, AgentStateMachineRunner, AgentExecutionContext
com.arcclaw.core.agent.reasoning    → LangChainReasoningEngine, ReasoningStep, ReasoningEngine
com.arcclaw.core.agent.role         → AgentRoleEnum, RoleDefinition, RoleRegistry, ToolCapability
com.arcclaw.core.agent.result       → AgentResult, AgentState
```

This organization mirrors the **Single Responsibility Principle** at the package level and makes onboarding much easier for new developers joining ArcClaw.

---

Claude.ai

Here are the split recommendations for the 6 large classes, ordered by priority:

---

**1. `LangChainReasoningEngine.java` (453 lines) — 1 God method**

The entire class is essentially one method `nextStep()` with ~300 lines doing: persona assembly, prompt building, history formatting, tool schema injection, LLM call, response parsing, and compliance checking.

Split into:
```
LangChainReasoningEngine.java     — orchestrator only, calls the below
  SystemPromptBuilder.java        — assembles persona + security rules + role block
  HistoryFormatter.java           — converts List<ReasoningStep> → List<ChatMessage>
  ToolSchemaInjector.java         — builds permitted tools list per role
  ReasoningStepParser.java        — parses LLM response → ReasoningStep
  ComplianceOverrideDetector.java — the COMPLIANCE_OVERRIDE_PATTERN check
```

---

**2. `WorkspaceManager.java` (481 lines) — 3 unrelated concerns**

Currently mixes: session heartbeat tracking, GC sweep scheduling, approval timeout sweep, workspace dir deletion, zombie recovery, and chat memory cleanup.

Split into:
```
WorkspaceManager.java             — terminateSession(), resetSessionState() only
  SessionHeartbeatTracker.java    — recordHeartbeat(), removeHeartbeat(), activeSessionCount()
  WorkspaceGcScheduler.java       — @Scheduled sweepTimedOutSessions(), sweepTimedOutApprovals()
  ZombieRecoveryService.java      — onStartup(), doRecoverZombies()
  WorkspaceDirCleaner.java        — wipeWorkspaceDir() + FileVisitor inner classes
```

---

**3. `AgentStateMachineRunner.java` (481 lines) — 4 step handlers**

The `run()` loop delegates to 4 private methods but they're all in one class. Each step handler is independently testable.

Split into:
```
AgentStateMachineRunner.java      — run() loop + StepDecision record only
  EngineStepProcessor.java        — processEngineStep() + schema error handling
  ToolValidator.java              — validateTool() + GITHUB_TRIGGER_PATTERN
  ToolInvoker.java                — invokeToolAndObserve() + dedup guard + isSuccessfulObservation()
  ChatMemoryWriter.java           — saveGoalToChatMemory() + handleFinishAction()
```

---

**4. `ProcessIsolationExecutor.java` (430 lines) — 3 strategies + circuit breaker**

Split into:
```
ProcessIsolationExecutor.java     — executeSandboxed() routing logic only
  SandboxCircuitBreaker.java      — CircuitBreakerState enum + state/failures/openedAt fields
                                    openCircuit(), checkPodmanAvailability()
  PodmanContainerExecutor.java    — executeInContainer() + activeContainerNames tracking
                                    stopAllContainersOnShutdown() @PreDestroy
  NamespaceExecutor.java          — executeInNamespace()
  DirectExecutor.java             — executeDirectly()
  SandboxSecretResolver.java      — resolveSecrets()
```

---

**5. `AgentCronScheduler.java` (320 lines) — polling + execution + lifecycle**

Split into:
```
AgentCronScheduler.java           — @Scheduled poll loop + semaphore management only
  CronJobExecutor.java            — executeCronJob() + session setup/teardown
  CronStatusTransition.java       — the switch(result) → markCompleted/markFired/markFailed logic
```

---

**6. `AgentResource.java` (325 lines) — REST + business logic mixed**

Split into:
```
AgentResource.java                — REST endpoints only (@POST/@GET/@DELETE)
  AgentSessionService.java        — createSession(), runAgent(), approve/deny business logic
  AgentResponseMapper.java        — mapFailedResponse() + all Response building
                                    record types: ApprovalRequest, AgentResponse, etc.
```

---

**Priority order to do these splits:**

| Priority | Class | Reason |
|---|---|---|
| 1 | `AgentStateMachineRunner` | Most test coverage already exists per method — easiest to split safely |
| 2 | `ProcessIsolationExecutor` | Circuit breaker + strategies are fully independent — no shared state |
| 3 | `WorkspaceManager` | GC scheduler and heartbeat have no reason to be in same class |
| 4 | `AgentCronScheduler` | `CronStatusTransition` is the same pattern as `AgentLoop` result handling |
| 5 | `LangChainReasoningEngine` | Highest risk — prompt building changes affect LLM behavior |
| 6 | `AgentResource` | Lowest priority — works fine, purely cosmetic separation |

Start with `AgentStateMachineRunner` — `ToolInvoker` already has tests for `invokeToolAndObserve()` via `AgentStateMachineRunnerTest` that will catch regressions immediately.


## Phase 53.3 (give this first, wait for completion)

```
## Phase 53.3 - LangChainReasoningEngine split

Split LangChainReasoningEngine.java into four classes. Current file attached.
This is the highest-risk split — LLM behavior depends on exact prompt assembly order.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
GROUND TRUTH — READ BEFORE IMPLEMENTING
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
1. The API is chatLanguageModel.chat(ChatRequest) — NOT model.generate().
   ChatRequest is built via ChatRequest.builder().messages(messages)
   .toolSpecifications(toolSpecifications).build(). Never use generate().

2. GovernanceLayer is NOT injected in LangChainReasoningEngine and is NOT called
   in nextStep(). recordLlmCall() is called in AgentStateMachineRunner.processEngineStep()
   AFTER nextStep() returns. Do NOT add GovernanceLayer to any class in this split.

3. permittedTools are computed inside nextStep() via:
   roleRegistry.permittedTools(AgentRoleEnum.fromString(agentRole))
   then used to filter toolRegistry.listTools() into List<ToolSpecification>.
   This tool spec building stays in the slimmed engine — NOT in SystemPromptBuilder.
   SystemPromptBuilder.build() takes NO permittedTools parameter.

4. The engine currently injects:
   ChatLanguageModel, ToolRegistry, ObjectMapper, ChatMemoryStore,
   CompactionSummaryRepository, RoleRegistry, PromptTemplateEngine, AgentPersonaRegistry
   Map every field explicitly when deciding what moves where.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
1. SystemPromptBuilder.java
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Package: com.arcclaw.core.agent.reasoning
@ApplicationScoped
Inject: AgentPersonaRegistry, PromptTemplateEngine, CompactionSummaryRepository, RoleRegistry

Method:
  public String build(String agentRole, String sessionId)

Builds and returns the complete finalSystemPrompt string in this EXACT order
(any reorder changes LLM behavior):
  1. "You are ArcClaw. Your active persona is described below.\n\n"
     + agentPersonaRegistry.getPersonaPrompt(roleEnum)
  2. Coordinator trimurti block — only appended when roleEnum == AgentRoleEnum.COORDINATOR
  3. SECURITY_RULES_PROMPT (the large multi-line static string — move verbatim)
  4. strictBinding block:
     "**STRICT TOOL BINDING CONTRACT**\n"
     + "ACTIVE PERSONA: " + agentRole + "\n"
     + promptTemplateEngine.buildSystemPrompt(AgentRoleEnum.fromString(agentRole),
         compactionSummary, Instant.now())
     + "\nThis is a HARD constraint..."

  compactionSummary is fetched inside build():
    String compactionSummary = (sessionId != null)
        ? compactionSummaryRepository.findLatestBySessionId(sessionId)
            .map(s -> s.summary).orElse(null)
        : null;

Returns: plain String — engine wraps in SystemMessage.from(result) before prepending.
NOTE: Use string concatenation (NOT String.format) — the prompt contains literal %
characters that would throw MissingFormatArgumentException at runtime.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
2. ChatHistoryAssembler.java
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Package: com.arcclaw.core.agent.reasoning
@ApplicationScoped
Inject: ChatMemoryStore, ObjectMapper

Method:
  public List<ChatMessage> assemble(String sessionId, String goal, List<ReasoningStep> history)

Returns: List<ChatMessage> WITHOUT the SystemMessage — engine prepends it separately.
NOTE on DoD: The main system prompt SystemMessage is prepended by the engine only.
ChatHistoryAssembler may emit a SystemMessage ONLY in STEP C for schema correction
hints — this is a mid-conversation correction signal, not the system prompt.

Assembles in this exact order:

  STEP A — persistent chat history (from ChatMemoryStore):
    if sessionId != null, chatMemoryStore.getMessages(sessionId), then filter:
    - Skip all SystemMessage instances
    - Skip AiMessage where MessageTag.isTagged(ai.text()) is true
    - Skip AiMessage where COMPLIANCE_OVERRIDE_PATTERN.matcher(ai.text()).find() is true
      (ai.text() may be null for tool-use messages — null-check before matching)
    - Skip UserMessage where toString() contains "The tool execution has been approved
      and completed"
    - Skip UserMessage where toString() contains "Resuming task after approval"

  IMPORTANT — before removing the second AiMessage else-if branch:
    Verify each string literal in that branch ("as per our agreement",
    "as per your request, i will provide", "i'll provide python code instead of rust",
    "i'll provide the equivalent python", "i cannot provide information on rust")
    is covered by COMPLIANCE_OVERRIDE_PATTERN. If any string is absent from the
    pattern, ADD it to the pattern before deleting the branch.
    Verify against the source file — do NOT rely on abbreviated spec.
    Only after confirming full coverage: remove the dead-code else-if branch.

  COMPLIANCE_OVERRIDE_PATTERN — copy the complete regex VERBATIM from
    LangChainReasoningEngine.java. Do NOT use any abbreviated version.

  STEP B — ReAct loop steps (short-term history):
    For each ReasoningStep in history:
      if step.action() != null && !FINISH:
        serialize step.actionInput() → argsJson:
          if actionInput has single key "parameters" with String value → use raw string
          else → objectMapper.writeValueAsString(actionInput)
        callId = "call_" + i (loop index)
        add AiMessage.from(thought, singletonList(ToolExecutionRequest))
        if step.observation() != null:
          truncate to 4000 chars with "... [TRUNCATED FOR LENGTH]" if needed
          add ToolExecutionResultMessage.from(callId, step.action(), safeObs)
      else (FINISH or null action):
        if step.thought() != null && !empty → add AiMessage.from(thought)

  STEP C — goal injection for special system goals only:
    if goal != null && goal.startsWith("The tool execution"):
      add UserMessage.from(goal)
    else if goal != null && goal.contains("System: Your previous tool call"):
      extract correctionHint = goal.substring(goal.indexOf("System: Your previous tool call"))
      add SystemMessage.from(correctionHint +
          "\nIMPORTANT: Do NOT attempt another tool call. Output your final answer as plain text only.")
    Regular user prompts are in ChatMemoryStore (STEP A) — do NOT re-add them here.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
3. ReasoningStepParser.java
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Package: com.arcclaw.core.agent.reasoning
@ApplicationScoped (NOT package-private — needs ObjectMapper injection for testability)
Inject: ObjectMapper

Method:
  public ReasoningStep parse(String sessionId, String agentRole, ChatResponse response)

Extracts tokens first:
  int inputTokens = response.tokenUsage() != null
      ? response.tokenUsage().inputTokenCount() : 0;
  int outputTokens = response.tokenUsage() != null
      ? response.tokenUsage().outputTokenCount() : 0;

  AiMessage aiMessage = response.aiMessage();

If aiMessage.hasToolExecutionRequests():
  ToolExecutionRequest req = aiMessage.toolExecutionRequests().get(0)
  log.infof("LLM is attempting to call tool: '%s' with args: %s", req.name(), req.arguments())
  Parse req.arguments() via objectMapper:
    if arguments null or blank → actionInput = Map.of()
    else → objectMapper.readValue(req.arguments(), TypeReference<Map<String,Object>>)
    on JsonParseException:
      log.warn(...)
      return ReasoningStep.pending(aiMessage.text(), ReasoningStep.FINISH,
          Map.of("error", "System Error: You provided invalid JSON formatting for the
          tool arguments. Please ensure strict JSON compliance without trailing commas."),
          inputTokens, outputTokens)
  return ReasoningStep.pending(aiMessage.text(), req.name(), actionInput,
      inputTokens, outputTokens)
Else:
  return ReasoningStep.pending(aiMessage.text(), ReasoningStep.FINISH,
      Map.of("answer", aiMessage.text() != null ? aiMessage.text() : ""),
      inputTokens, outputTokens)

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
4. LangChainReasoningEngine.java (slimmed)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Stays in package: com.arcclaw.core.agent for now — Prompt 5 moves it to
com.arcclaw.core.agent.reasoning. Do NOT move it in this prompt.

Inject — KEEP:
  ChatLanguageModel, ToolRegistry, RoleRegistry,
  SystemPromptBuilder, ChatHistoryAssembler, ReasoningStepParser

Inject — REMOVE:
  ObjectMapper (now in ChatHistoryAssembler + ReasoningStepParser)
  ChatMemoryStore (now in ChatHistoryAssembler)
  CompactionSummaryRepository (now in SystemPromptBuilder)
  AgentPersonaRegistry (now in SystemPromptBuilder)
  PromptTemplateEngine (now in SystemPromptBuilder)

Remove: SECURITY_RULES_PROMPT static field, COMPLIANCE_OVERRIDE_PATTERN static field

nextStep() body after split (~50 lines):

  // 1. Build system prompt
  String systemPrompt = promptBuilder.build(agentRole, sessionId);

  // 2. Assemble chat history (WITHOUT SystemMessage)
  List<ChatMessage> assembled = historyAssembler.assemble(sessionId, goal, history);

  // 3. Prepend SystemMessage + build full message list
  List<ChatMessage> messages = new ArrayList<>();
  messages.add(SystemMessage.from(systemPrompt));
  messages.addAll(assembled);

  // 4. Build tool specifications (stays in engine — needs RoleRegistry + ToolRegistry)
  Set<String> allowedTools = roleRegistry.permittedTools(AgentRoleEnum.fromString(agentRole));
  boolean isCronFired = sessionId != null && sessionId.startsWith("cron-");
  List<ToolSpecification> toolSpecifications = toolRegistry.listTools().stream()
      .filter(allowedTools::contains)
      .filter(name -> !isCronFired || (!name.equals("scheduletask") && !name.equals("manageschedule")))
      .map(name -> toolRegistry.findTool(name).orElseThrow())
      .map(tool -> ToolSpecification.builder()
          .name(tool.name()).description(tool.description())
          .parameters(tool.parametersSchema()).build())
      .collect(Collectors.toList());

  // 5. Build ChatRequest and call LLM
  ChatRequest request = toolSpecifications.isEmpty()
      ? ChatRequest.builder().messages(messages).build()
      : ChatRequest.builder().messages(messages).toolSpecifications(toolSpecifications).build();

  ChatResponse response;
  try {
      response = chatLanguageModel.chat(request);
  } catch (InvalidRequestException e) {
      String msg = e.getMessage() != null ? e.getMessage() : "";
      if (msg.contains("was not in request.tools") || msg.contains("tool_use_failed")) {
          log.warnf("LLM called unavailable tool — injecting correction: %s", msg);
          return ReasoningStep.pending(
              "Attempted to call a tool not available in this context",
              "OBSERVE", Map.of(), 0, 0)
              .withObservation(
                  "That tool is not available in the current execution context. " +
                  "Use only the tools listed in PERMITTED TOOLS. " +
                  "If no tool is needed, output your answer directly and call FINISH.");
      }
      throw e;
  } catch (Exception e) {
      String clean = AgentErrorMessageUtil.sanitizeErrorMessage(e.getMessage());
      throw new RuntimeException(clean, e);
  }

  // 6. Parse response
  return stepParser.parse(sessionId, agentRole, response);

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Test updates
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

LangChainReasoningEnginePersonaTest.java (MODIFY — currently uses reflection):
  This test injects all 8 original fields via reflection. After the split, update setUp():
  - REMOVE field injections for: objectMapper, chatMemoryStore, compactionSummaryRepository,
    promptTemplateEngine, agentPersonaRegistry
  - ADD mock injections via reflection:
      engine.promptBuilder = mockPromptBuilder
      engine.historyAssembler = mockHistoryAssembler
      engine.stepParser = mockStepParser
  - KEEP via reflection (these fields remain on the slimmed engine):
      mockChatLanguageModel, mockToolRegistry, mockRoleRegistry  ← roleRegistry STAYS
  - mockRoleRegistry MUST be stubbed to return a non-null Set:
      when(mockRoleRegistry.permittedTools(any())).thenReturn(Set.of());
    Without this stub, roleRegistry.permittedTools() returns null and the tool
    specification stream throws NullPointerException at test runtime.
  - promptBuilder stub:
      when(mockPromptBuilder.build(anyString(), anyString()))
          .thenReturn("SYSTEM_PROMPT_CONTAINS_PERSONA: " + expectedPersona)
  - historyAssembler stub:
      when(mockHistoryAssembler.assemble(any(), any(), any())).thenReturn(List.of())
  - stepParser stub:
      when(mockStepParser.parse(any(), any(), any())).thenReturn(expectedStep)
  - Persona assertion stays valid: capture ChatRequest, get first message (SystemMessage),
    verify it contains the persona string returned by the mocked promptBuilder

LangChainReasoningEngineTest.java (@QuarkusTest — MODIFY):
  Currently mocks: ChatLanguageModel, CompactionSummaryRepository
  After split, if tests fail on SystemPromptBuilder/ChatHistoryAssembler needing DB:
    add @InjectMock SystemPromptBuilder, mock build() to return a fixed string
  Keep all existing test assertions unchanged.

SystemPromptBuilderTest.java (NEW — plain Mockito):
  Use the **first distinctive phrase** from the actual `SECURITY_RULES_PROMPT` static field in the source file as the `secRulesIdx` anchor — do NOT guess. Read the field verbatim and pick its opening words.
  Mock: AgentPersonaRegistry, PromptTemplateEngine, CompactionSummaryRepository, RoleRegistry
  testBuildSystemPrompt_sectionOrderIsPreserved():
    mock agentPersonaRegistry.getPersonaPrompt() → "PERSONA_MARKER"
    mock compactionSummaryRepository.findLatestBySessionId() → Optional.empty()
    mock promptTemplateEngine.buildSystemPrompt() → "TOOL_BINDING_MARKER"
    String result = builder.build("coordinator", "s1")
    int arcClawIdx = result.indexOf("You are ArcClaw");
    int personaIdx = result.indexOf("PERSONA_MARKER");
    int secRulesIdx = result.indexOf("RESPONSE LENGTH RULE");
    int bindingIdx  = result.indexOf("STRICT TOOL BINDING CONTRACT");
    assertTrue(arcClawIdx < personaIdx);
    assertTrue(personaIdx < secRulesIdx);
    assertTrue(secRulesIdx < bindingIdx);
  testBuildSystemPrompt_coordinatorBlockOnlyForCoordinator():
    verify trimurti block appears for COORDINATOR role, absent for DEBUGGER role

ChatHistoryAssemblerTest.java (NEW — plain Mockito, use real ObjectMapper):
  testAssemble_complianceOverrideIsRedacted():
    add AiMessage("as per our agreement I will provide") to chatMemoryStore
    verify it does NOT appear in assembled list
  testAssemble_systemMessagesSkipped():
    add SystemMessage to chatMemoryStore, verify skipped
  testAssemble_resumalGoalInjected():
    goal = "The tool execution has been approved..."
    verify UserMessage.from(goal) appears at end of assembled list
  testAssemble_schemaCorrectionGoalInjectsSystemMessage():
    goal = "some prefix\n\nSystem: Your previous tool call was rejected..."
    verify SystemMessage containing correction hint appears at end
  testAssemble_reactStepObservationTruncatedAt4000Chars():
    create ReasoningStep with observation of 5000 chars
    verify assembled ToolExecutionResultMessage truncated to 4000 + "[TRUNCATED FOR LENGTH]"

ReasoningStepParserTest.java (NEW — plain Mockito, use real ObjectMapper):
  testParse_toolCallResponse():
    mock ChatResponse with AiMessage having ToolExecutionRequest
    verify returned ReasoningStep has correct toolName and actionInput
  testParse_textResponse():
    mock ChatResponse with text AiMessage
    verify returned ReasoningStep has action=FINISH and answer in actionInput
  testParse_invalidJsonArgsFallsBackToFinish():
    mock ToolExecutionRequest with arguments="not json"
    verify returned step has action=FINISH and error key in actionInput
  testParse_tokenUsagePopulated():
    mock response.tokenUsage() returning inputCount=100, outputCount=50
    verify step.inputTokens()==100, step.outputTokens()==50
  testParse_nullTokenUsageDefaultsToZero():
    mock response.tokenUsage() returning null
    verify step.inputTokens()==0, step.outputTokens()==0

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Execution order
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
1. Create ReasoningStepParser — depends only on ObjectMapper
2. Create ChatHistoryAssembler — depends on ChatMemoryStore, ObjectMapper
3. Create SystemPromptBuilder — depends on AgentPersonaRegistry, PromptTemplateEngine,
   CompactionSummaryRepository, RoleRegistry
4. Slim LangChainReasoningEngine — inject all three, remove moved fields
5. Update tests

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Definition of done
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
- chatLanguageModel.chat(ChatRequest) is the ONLY LLM call — never .generate()
- GovernanceLayer is NOT added to any class in this split
- SystemPromptBuilder.build() takes (String agentRole, String sessionId) only
- Tool specification building stays in slimmed LangChainReasoningEngine.nextStep()
- SystemMessage.from(systemPrompt) prepended by engine only — assembler returns
  messages without the main system prompt SystemMessage
- ChatHistoryAssembler may emit a SystemMessage only in STEP C for schema correction
- String concatenation throughout SystemPromptBuilder — NOT String.format
- SECURITY_RULES_PROMPT and COMPLIANCE_OVERRIDE_PATTERN removed from engine
- Dead-code second AiMessage else-if branch removed only after pattern coverage verified
- ReasoningStepParser is @ApplicationScoped
- LangChainReasoningEnginePersonaTest setUp() rewired with roleRegistry KEPT and stubbed
- InvalidRequestException "was not in request.tools" handler stays in slimmed engine
- All existing LangChainReasoningEngineTest and LangChainReasoningEnginePersonaTest
  assertions pass unchanged
- No circular CDI injection
```

---

## Comment 2 — Prompt 5 (give after 53.3 is complete and tests pass)

```
## Phase 53.5 Package reorganization

Reorganize com.arcclaw.core.agent package into sub-packages.
No logic changes — pure Move refactoring only.

Current package: com.arcclaw.core.agent (23 classes)

Target layout:
  com.arcclaw.core.agent.persona
    → Agent.java, AgentRole.java, AgentPersonaRegistry.java
    → BrahmaAgent, ShivaAgent, SaraswatiAgent, ArchitectAgent, VishnuAgent, DattatreyaAgent

  com.arcclaw.core.agent.role
    → AgentRoleEnum.java, RoleDefinition.java, RoleRegistry.java, ToolCapability.java

  com.arcclaw.core.agent.loop  (ToolInvocationGuard, ToolInvoker, ChatMemoryWriter
    already exist here from 53.2 — move these three in this step)
    → AgentLoop.java, AgentStateMachineRunner.java, AgentExecutionContext.java
    → ToolInvocationGuard.java, ToolInvoker.java, ChatMemoryWriter.java

  com.arcclaw.core.agent.reasoning  (SystemPromptBuilder, ChatHistoryAssembler,
    ReasoningStepParser already exist here from 53.3 — move these in this step)
    → LangChainReasoningEngine.java, ReasoningEngine.java, ReasoningStep.java
    → SystemPromptBuilder.java, ChatHistoryAssembler.java, ReasoningStepParser.java
    → PromptTemplateEngine.java, MessageTag.java

  com.arcclaw.core.agent.result
    → AgentResult.java, AgentState.java

IMPORTANT — "stays in com.arcclaw.core.agent" constraint reconciliation:
  Prompts 53.2 and 53.3 stated that AgentStateMachineRunner and LangChainReasoningEngine
  "stay in com.arcclaw.core.agent" — that constraint was temporary, valid only until
  this prompt. Prompt 5 is the authoritative final package layout:
    AgentStateMachineRunner → com.arcclaw.core.agent.loop
    LangChainReasoningEngine → com.arcclaw.core.agent.reasoning

Move order — CRITICAL: move least-depended-upon classes first to minimize cascading failures:

  Step 1: loop — move AgentLoop, AgentStateMachineRunner, AgentExecutionContext
    to com.arcclaw.core.agent.loop.
    (ToolInvocationGuard, ToolInvoker, ChatMemoryWriter already exist there from
    53.2 and need no move — only the three listed above require moving)

  Step 2: reasoning — move LangChainReasoningEngine, ReasoningEngine, ReasoningStep,
    PromptTemplateEngine, MessageTag to com.arcclaw.core.agent.reasoning.
    (SystemPromptBuilder, ChatHistoryAssembler, ReasoningStepParser already exist
    there from 53.3 and need no move)

  Step 3: persona — move Agent impls, AgentPersonaRegistry, AgentRole
    to com.arcclaw.core.agent.persona

  Step 4: role — move AgentRoleEnum, RoleDefinition, RoleRegistry, ToolCapability
    to com.arcclaw.core.agent.role
    (loop and reasoning consumers are already updated in Steps 1-2)

  Step 5: result — move AgentResult, AgentState to com.arcclaw.core.agent.result
    LAST — AgentResult is the most-imported class in the codebase.
    Moving it first would break every consumer simultaneously.

Do NOT pre-create CheckpointResumeHandler.java during this prompt.
It is created in Prompt 6 directly in com.arcclaw.core.agent.loop — no move needed.

Steps:
1. Update all import statements across arcclaw-core and arcclaw-app
2. Run full test suite after each sub-package move step, not after all moves at once
3. Move in the order above: loop → reasoning → persona → role → result

Definition of done:
- No class remains in com.arcclaw.core.agent root package
- All imports in arcclaw-core and arcclaw-app compile cleanly
- Full test suite passes after final move
- No logic changes — pure package move only
```

---

## Phase 54.6 - AgentLoop + AuditService split

Two smaller splits to finish the refactoring. Run only after Prompt 5 is complete.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
PART A — Extract CheckpointResumeHandler from AgentLoop
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. CheckpointResumeHandler.java
   Package: com.arcclaw.core.agent.loop
   @ApplicationScoped
   Inject: StatefulAgentStore, ToolRegistry

   public AgentResult handle(String sessionId, String agentRole,
       String goal, List<ReasoningStep> history)

   Full body of current handleResumeHook() moved here UNCHANGED.
   Return contract (preserve exactly):
     - Returns null      → caller (AgentLoop) falls through to main loop
     - Returns AgentResult → caller short-circuits and returns that result

2. AgentLoop.java (slimmed)
   - Inject CheckpointResumeHandler (add), remove handleResumeHook() private method
   - Call site in run() after extraction must be EXACTLY:

     AgentResult resumeResult = checkpointResumeHandler.handle(
         sessionId, canonicalRole, goal, resumedHistory);
     if (resumeResult != null) {
         result = resumeResult;
     } else {
         // fall through to stateMachineRunner.run(...)
     }

   Do NOT replace the null check with Optional, boolean, or any other pattern.
   The null contract is intentional and preserved from the original handleResumeHook().

3. CheckpointResumeHandlerTest.java (NEW — plain Mockito)
   - Move all handleResumeHook tests from AgentLoopTest here
   - Inject: mock StatefulAgentStore, mock ToolRegistry
   - Tests: denial path, approval path, tool execution failure path,
     null return on non-resumption goal

4. AgentLoopTest.java (MODIFY)
   - Remove handleResumeHook tests (moved to CheckpointResumeHandlerTest)
   - Retain: MDC setup/teardown, OTel span, checkpoint persistence,
     ComplianceViolation halt

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
PART B — Split AuditService into two tiers
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
NOTE: ComplianceViolationException already exists at
com.arcclaw.core.audit.ComplianceViolationException — do NOT create it again.

Current AuditService has exactly FOUR public methods. Assign each:

  TELEMETRY tier (fire-and-forget, swallows all exceptions):
    record(sessionId, userId, agentRole, SecurityEventType, detail)
    recordLoopCompletion(sessionId, userId, agentRole, goalHash, AgentResult,
                         inputTokens, outputTokens, BigDecimal usdCost)
    waitForIdle()  — test helper, delegates to persistenceEngine.waitForIdle()

  COMPLIANCE tier (fail-closed, throws ComplianceViolationException on failure):
    recordMandatory(sessionId, userId, agentRole, SecurityEventType, detail)

1. TelemetryAuditService.java
   Package: com.arcclaw.core.audit
   @ApplicationScoped
   Inject: AuditEventRepository, AsyncPersistenceEngine
   Owns: record(), recordLoopCompletion(), waitForIdle()
   Also owns private helpers: resolveOutcome(), resolveLoopEventType()
   All methods swallow exceptions exactly as in the current AuditService implementation.

2. ComplianceAuditService.java
   Package: com.arcclaw.core.audit
   @ApplicationScoped
   Inject: AuditEventRepository, AsyncPersistenceEngine
   Owns: recordMandatory() only
   Throws ComplianceViolationException (already exists) if write fails after retries.

3. AuditService.java — keep as facade (backward compatibility, no big-bang migration)
   @ApplicationScoped
   Inject: TelemetryAuditService, ComplianceAuditService
   REMOVE: @Inject AuditEventRepository and @Inject AsyncPersistenceEngine — these
     fields move to the two tier classes and must be deleted from the facade.
     No repository or persistence calls remain in the facade.
   REMOVE: resolveOutcome() and resolveLoopEventType() private helpers — these
     move to TelemetryAuditService.
   All four public methods delegate only:
     record()              → telemetryAuditService.record()
     recordLoopCompletion() → telemetryAuditService.recordLoopCompletion()
     waitForIdle()         → telemetryAuditService.waitForIdle()
     recordMandatory()     → complianceAuditService.recordMandatory()

4. Test updates

   TelemetryAuditServiceTest.java (NEW):
     - Tests for record() and recordLoopCompletion() move here
     - Verify exceptions are swallowed and logged, not rethrown
     - Verify waitForIdle() delegates to persistenceEngine.waitForIdle()

   ComplianceAuditServiceTest.java (NEW):
     - Tests for recordMandatory() move here
     - Verify ComplianceViolationException is thrown when persistenceEngine.submitCritical()
       throws (fail-closed guarantee)

   AuditServiceTest.java (MODIFY):
     - Replace direct field injection with mocks of TelemetryAuditService and
       ComplianceAuditService
     - Verify delegation only — no behavior assertions (those are in the tier tests)

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Definition of done
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
- CheckpointResumeHandler in com.arcclaw.core.agent.loop, no logic changes
- AgentLoop.run() null check pattern preserved exactly as shown above —
  no Optional, boolean, or other substitution
- ComplianceViolationException NOT re-created (already exists)
- AuditService facade has no @Inject AuditEventRepository or AsyncPersistenceEngine —
  only @Inject TelemetryAuditService and ComplianceAuditService remain
- All 4 AuditService methods delegate — existing callers unchanged
- resolveOutcome() and resolveLoopEventType() live in TelemetryAuditService only
- waitForIdle() delegated through facade — WorkspaceManagerTest usage unchanged
- AuditServiceTest rewired to delegation-only assertions
- Full test suite passes
- No circular CDI injection
```
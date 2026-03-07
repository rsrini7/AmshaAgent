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
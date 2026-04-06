# ArcClaw P84 — Complete End-to-End Workflow

---

### Phase 1: Startup Sequence
`ArcClawCli.main()` runs **before** CDI. The sequence is strictly ordered:
1. `CliUtils.validateRequiredEnvVars()` — hard exits if any required env var is missing; Quarkus never starts dirty
2. `ARCCLAW_DATA_DIR` pinned to an absolute path via `System.setProperty` before any YAML `${expr}` is evaluated
3. `Vec0NativeLibraryInstaller` (Priority 10) extracts the platform `.so/.dylib/.dll` from JAR before any JDBC connection opens
4. `Quarkus.run(...)` boots the CDI container, Hibernate, and Agroal
5. CDI `StartupEvent` observers fire: `SessionSchemaEnforcer` (90) → `RagSchemaInitializer` (100) warms pool and triggers `FtsConnectionInterceptor.onConnectionAcquire()` (loads `vec0` + ATTACHes the FTS db) → `SkillLoader` + `StartupSecurityValidator`

`StartupSecurityValidator` is a hard gate in prod: it throws `DeploymentException` if the vault pepper equals the dev default, if the HMAC audit key is absent, or if the Telegram allowlist contains only usernames instead of numeric IDs.

### Phase 2: Entry Points
Four picocli leaf commands converge on the same `AgentLoop.run()`:

| Command | Behaviour |
|---|---|
| `arcclaw serve` | Starts HTTP on 8080, blocks on `Quarkus.waitForExit()`. Telegram Camel route self-registers via `TelegramChannelRoute` `@StartupEvent` observer |
| `arcclaw ask "goal"` | One-shot: creates UUID session → `AgentLoop.run()` → terminates workspace in `finally` |
| `arcclaw chat` | Scanner on stdin → `ReflexRegistry.processIfReflex()` first (approve/deny/reset/status/history); non-reflex lines go to `AgentLoop.run()` |
| `arcclaw attach <channel>` | Discovers `ChannelDaemon` CDI bean by ID → `daemon.start()` (blocking) |

### Phase 3: Inbound Message Pipeline — Three Paths

**Path A — REST API:**
```
POST /api/v1/sessions/{id}/run
  → ApiKeyAuthFilter (HMAC-SHA256 vs stored digest; raw key never stored)
  → Bucket4j token-bucket (20 req/min per key)
  → AgentSessionService.runSync()
  → handleSlashCommand() (reflex short-circuit)
  → WorkspaceScopeHolder.call() → WorkspaceContext.run(sessionId, userId, chatId, autonomy, role, ...)
  → AgentLoop.run(sessionId, agentRole, goal, engine, maxIterations, context)
```

**Path B — Telegram:**
```
Telegram Bot API → Apache Camel (camel-quarkus-telegram)
  → TelegramAgentHandler.handleExchange(Exchange)
  → TelegramAllowlistGuard (numeric userId check)
  → ChannelServices.processAsync(ChannelMessage)
  → SessionRegistry.resolveOrCreate(channelType, channelId, userId)
  → SkillAwareIntentClassifier.classify(text) [compiled Pattern[] from SkillRegistry — LLM bypassed on match]
       ↓ (miss) → IntentClassifierService.classify(text) [LLM-backed classification]
  → AgentDispatchHandler → AgentLoop.run(...)
```

`SkillAwareIntentClassifier` iterates `static final Pattern[]` from compiled `CompiledSkillEntry` objects. If any matches, it returns `skill:{name}` as the role — zero LLM tokens spent on routing.

**Path C — Cron Scheduler:**
```
@Scheduled(every = "${arcclaw.cron.poll-interval:30s}", SKIP concurrent execution)
@Transactional(NOT_SUPPORTED)                          ← §5.12 compliant
  → cronRepository.reclaimStaleCrons(cutoff)           ← crash recovery RUNNING > 5 min; isolated try/catch
  → cronRepository.findDueCrons()
  → LookbackWindow filter (skip fires outside PT5M)
  → cronRepository.claimForFire(id)                   ← atomic CAS claim
  → Semaphore.tryAcquire() (max 10 concurrent)
  → ManagedExecutor virtual thread → CronJobRunner.execute(job)
    → cronRepository.setDeliveryId(id, executionId)   ← ORDERING CRITICAL: before WorkspaceContext.run()
    → WorkspaceContext.run(cronSessionId, userId, chatId, BACKGROUND, role, ...)
    → AgentLoop.run(cronSessionId, ..., effectiveGoal + CRON CONSTRAINT directive)
    → CronResultHandler.handleSuccess / handleFailure()
```

### Phase 4: AgentLoop Lifecycle (`AgentLoop.runWithRole`)

1. MDC set: `SESSION_ID`, `AGENT_ROLE`, `USER_ID` (captured early so `finally` block is immune to MDC lifecycle)
2. OTel session span started via `AgentOtelContext.startSessionSpan()`
3. Role resolution: `skill:X` roles preserve the full prefix so `SystemPromptBuilder` injects the skill's prompt; standard roles normalize via `AgentRoleEnum.fromString()`
4. **Checkpoint resume decision:**

| Checkpoint Status | Action |
|---|---|
| `IN_PROGRESS` | Deserialize `ReasoningStep[]` from `stateJson`, resume history |
| `AWAITING_APPROVAL` + resume signal | Deserialize steps, route to `CheckpointResumeHandler.handle()` |
| `AWAITING_APPROVAL` + new message | **Archive** stale checkpoint, notify user via channel, start fresh (null history) |
| Terminal (`COMPLETED`/`FAILED`/`ARCHIVED`/`COMPROMISED`) | Archive row, start fresh |

5. `CheckpointResumeHandler.handle()`: denial → `FAILED`; single-tool approval → `runWithFullAutonomy()` → `toolRegistry.invokeTool()`; parallel batch → `resumeParallelBatch()` reads `batchToolDecisions`, restores pre-executed results, re-invokes APPROVED, fills DENIED with "User denied"
6. **Fast-path for `delegate_to_agent` resume**: `tryDelegateFinishFastPath()` — if last history step is a clean `delegate_to_agent` result (not starting with ⏳/⚠️/❌), skip coordinator wrap-up LLM call (~9,800 tokens saved)
7. `AgentStateMachineRunner.run(agentExecutionContext, engine, resumedHistory)`

### Phase 5: Thought-Plan-Act-Observe Loop

Each iteration under its own OTel iteration span:

**Step A — EngineStepHandler (LLM call):**
`ReasoningEngine.nextStep(sessionId, role, goal, history)` → `ChatHistoryAssembler` reconstructs message list from in-memory `ReasoningStep[]` (SQLite not read mid-run) → `ChatModel.generate(systemPrompt, messages, toolSpecs)` → `ReasoningStepParser` normalizes response into `ReasoningStep` (single or parallel batch, list-first, `getFirst()` fallback permanently removed)

Exception classification tree:
- HTTP 400 `model_not_found` / `invalid_api_key` → terminal FAILED
- HTTP 400 `not in request.tools` → terminal FINISHED (tool not in role schema — LLM told so)
- HTTP 400 schema error → retry with correction prompt (max 2)
- HTTP 429 rate-limit ≤15s → sleep + retry; >15s → surface last observation as FINISH
- Network errors → terminal FAILED

**Step B — ToolStepHandler:**
```
ReasoningStep.isParallelBatch()
  → YES: ParallelToolStepExecutor.execute()
           Pass 1: guard.evaluate() ALL tools before ANY executes (§7.7)
             ANY HITL → handleHitlBatch() with pre-execution split (§7.30/7.31)
             DEDUP → cached result, peers continue (§7.15)
             BLOCKED → retryValidation
           Pass 2: execute ALLOWED in parallel virtual threads with OTel propagation (§7.13)
           Merge in original call order for LLM clarity
  → NO:  guard.evaluate() → Allow / Deduplicate / PauseForApproval / Blocked
           → ToolInvoker.invoke() → ToolRegistry.invokeTool() → AgentTool.execute()
           → ToolObservationSanitizer.sanitize() (truncation, PII redaction, injection guard)
```

**Step C — HITL Pause (`PauseForApproval`):**
```
ApprovalCheckpointManager.persistSingleTool() or persistBatch()
  → agentStore.save(sessionId, role, steps) [REQUIRES_NEW]
  → checkpoint.setPendingToolName() / setBatchToolDecisions() [all PENDING]
  → checkpoint.setStatus(AWAITING_APPROVAL)
  → AgentSseService.publish(HitlEvent(AWAITING_APPROVAL))
  → ChannelPlugin.sender.send([Text, ApprovalPrompt])
  → AgentResult.awaitingApproval(toolName, paramsJson)  ← loop exits
```

**Step D — Approval flow (Telegram callback button press):**
```
TelegramCallbackHandler.handleCallback(Exchange)
  → §7.35 expiry guard: agentStore.isCheckpointExpired(sessionId, timeoutHours)
  → BatchHitlKeyboardBuilder.parseCallback(data) → BATCH_APPROVE|sessionId|toolName
  → ApprovalStateMachine.decide(sessionId, toolName, APPROVE, reason)
       @Transactional(NOT_SUPPORTED)       ← §5.11 compliant
       → agentStore.findLatestAwaitingApproval(sessionId)   ORDER BY id DESC
       → isCheckpointExpired() → EXPIRED if stale
       → idempotency: !pendingTools.contains(toolName) → ALREADY_DECIDED
       → agentStore.updateBatchToolDecision(sessionId, role, tool, "APPROVED")
       → agentStore.isAllBatchToolsDecided()
       → ALL decided? → resumeHandler.resumeAfterBatchDecision(...)
            §7.29 double-resume guard: agentStore.markBatchResuming(sessionId)
            WorkspaceContext.runWithFullAutonomy(sessionId, ...) → AgentLoop.run()
            finally: agentStore.clearBatchResuming(sessionId)
       → remaining == 0? sender.editMessage(chatId, msgId, "completed", null)
```

### Phase 6: Persistence Layer
All writes are `@Transactional(REQUIRES_NEW)` via CDI self-inject, called from `NOT_SUPPORTED` outer frames. `SqliteRetryTemplate` wraps each with exponential backoff + jitter on `SQLITE_BUSY`. Three-layer P84 cascade prevention: no REQUIRED→REQUIRES_NEW nesting, Agroal `background-validation=true`, `reclaimStaleCrons` isolated `try/catch`.

### Phase 7: Multi-Agent Delegation
```
coordinator → DelegateToAgentTool.execute(agent="coder", goal="...")
  → capability subset check (§4.10) — rejects transitive escalation
  → scheduling tools EXEMPT from HITL (ToolInvocationGuard §4.10)
  → new WorkspaceContext.run(subSessionId, userId, chatId, agentRole, ...)
  → AgentLoop.run(subGoal, subRole)
  → sub-agent returns clean answer
  → tryDelegateFinishFastPath() → skip coordinator LLM wrap-up
```

Named agents: `brahma` (coder), `shiva` (debugger), `saraswati` (researcher), `brihaspati` (coordinator), `dattatreya` (synthesizer). `Dattatreya` uses structured `SynthesisFunction` returning `SynthesisResult` (contributions + conflict summary + finalResolution).

### Phase 8: Audit Chain
Every event: `AuditEventRepository.record()` → `HMAC-SHA256(id + sessionId + eventType + detail + timestamp, vaultKey)` → `em.persist(AuditEvent)`. `AsyncPersistenceEngine` drains two virtual threads (critical/telemetry); `WorkspaceScopeHolder.run(event.getSessionId(), ...)` bound **per event**, not per thread (§5.17 compliant).

### Phase 9: Session Termination
`WorkspaceManager.terminateSession()`:
1. Remove heartbeat from `SessionHeartbeatTracker`
2. Clear budget memory (`GovernanceLayer.clearSession()`)
3. `markCheckpointsFailed()` — excludes COMPLETED/FAILED/ARCHIVED/COMPROMISED (§5.18 fix)
4. Wipe workspace directory
5. Clear in-memory + persistent `ChatMemory`
6. Destroy CDI `@WorkspaceScoped` context
7. `statefulAgentStore.clearBatchResuming(sessionId)` (§7.29)

---

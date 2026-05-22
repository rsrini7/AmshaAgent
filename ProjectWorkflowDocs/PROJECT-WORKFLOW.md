# ArcClaw V1.0.0 - Complete End-to-End Workflow

ArcClaw is a **multi-modal, multi-channel, enterprise-grade Java 25 / Quarkus AI agent framework** built around a checkpoint-driven Thought-Plan-Act-Observe loop with Human-in-the-Loop (HITL) governance, pluggable channel adapters, hexagonal persistence (SQLite / PostgreSQL / filesystem), two-tier RAG memory, and MCP integration.

---

## Module Architecture

| Module | Role |
|---|---|
| `arcclaw-app` | Main deployable; CLI (Picocli), REST endpoints, scheduler wiring, security, channel routing |
| `arcclaw-core` | Agent loop, state machine, tools, audit, governance, SSE, skill system, routing, sandbox |
| `arcclaw-channel-api` | Channel SPI - sealed interfaces and plugin contracts shared by all channel adapters |
| `arcclaw-channel-telegram` | Apache Camel–backed Telegram bot adapter with HITL inline keyboards |
| `arcclaw-channel-web` | WebSocket channel adapter + Lit TypeScript frontend |
| `arcclaw-deployment` | Quarkus build-time processors - custom `@WorkspaceScoped` CDI scope, MCP reflection, REST enforcement |
| `arcclaw-persistence-api` | Hexagonal architecture port interfaces (CheckpointStorePort, ChatMemoryStorePort, CronStorePort, etc.) |
| `arcclaw-persistence-common` | Shared JPA entities used by all persistence adapters |
| `arcclaw-persistence-sqlite` | SQLite adapter (WAL + FTS5 + vec0) implementing all persistence ports |
| `arcclaw-persistence-postgres` | PostgreSQL adapter (partial: checkpoint, cron, audit only) |
| `arcclaw-persistence-fs` | Filesystem-based persistence adapter (alternative to SQLite) |
| `arcclaw-rag` | Two-tier vector (vec0) + FTS5 RAG memory module |
| `arcclaw-mcp` | Model Context Protocol server bridge + dynamic client tool loading |
| `arcclaw-onboarding` | 6-step setup wizard REST API |
| `arcclaw-tool-playwright` | Playwright browser automation tool |
| `arcclaw-common` | Shared utilities (LogUtil, constants) |
| `arcclaw-ui` | Lit TypeScript web frontend |

**Dependency flow:** `arcclaw-app` → `arcclaw-core` → `arcclaw-channel-api` ← `arcclaw-channel-telegram`, `arcclaw-channel-web`. `arcclaw-deployment` is build-time only (not in the runtime fat-jar). `arcclaw-rag`, `arcclaw-mcp`, and `arcclaw-tool-playwright` are optional runtime modules discovered via CDI. Persistence follows hexagonal architecture: `arcclaw-persistence-api` defines ports, `arcclaw-persistence-common` holds shared entities, and three adapters (sqlite, postgres, fs) implement the ports.

---

## Phase 1: Bootstrap & Startup

### 1.1 Pre-CDI Steps (in `ArcClawCli.main()`)

`ArcClawCli.main()` runs **before** the Quarkus CDI container boots. Steps are strictly ordered:

1. **Bootstrap** - `AppBootstrap.bootstrap(args)` delegates to `BootstrapOrchestrator` which: resolves binary dir, drains input buffer, extracts .env, handles LD_PRELOAD re-exec, loads .env, normalizes Playwright path, installs Playwright CLI, ensures fallback LLM defaults, validates security keys, validates LLM/embedding config, validates sandbox tools, validates mandatory config, runs interactive setup wizard, prompts Playwright setup, provisions data directory, provisions native libs (including Vec0).
2. **Env-var validation** - `CliUtils.validateRequiredEnvVars()` checks all required environment variables and hard-exits with a clear setup guide if any are absent. Quarkus never starts with a missing or dirty config.
3. **Args check** - If `args.length == 0`, prints usage guide and exits.
4. **Quarkus boot** - `Quarkus.run(ArcClawCli.class, args)` starts the CDI container (Quarkus Arc), Hibernate ORM, Agroal connection pool, and all Quarkus extensions.

### 1.2 CDI Startup Observers (firing in priority order)

| Priority | Observer | Action |
|---|---|---|
| 10 | `Vec0NativeLibraryInstaller` | Re-fires observer-path native lib install (belt-and-suspenders) |
| 90 | `SessionSchemaInitializer` | Ensures `session_registry` table exists (managed by Hibernate ORM + Flyway) |
| 100 | `RagSchemaInitializer` | Warms the Agroal pool; triggers `FtsConnectionInterceptor.onConnectionAcquire()` which loads `vec0` extension and ATTACHes the FTS database; creates `fts.vec_global` virtual table |
| Unordered | `SkillLoader.onStart()` | Loads skills from classpath (`ClasspathSkillProvider`, priority 100) and SQLite DB (`DatabaseSkillProvider`, priority 10); parses YAML frontmatter; compiles trigger `Pattern[]`; registers dynamic roles in `RoleRegistry`; snapshots `CompiledSkillEntry[]` into `SkillRegistry` |
| Unordered | `StartupSecurityValidator` | **Hard gate in prod:** throws `DeploymentException` if vault pepper equals dev default, HMAC audit key is absent, or the Telegram allowlist contains only usernames instead of numeric IDs |
| Unordered | `DevSecretSeederStartup` | Dev-profile only - seeds mock API keys so the app can run without a `.env` file |
| Unordered | `AuditSchemaEnforcer` (per-adapter) | Verifies audit event table DDL; implemented by `PostgresAuditSchemaEnforcer`, `FsAuditSchemaEnforcer` |
| Unordered | `AgentCronScheduler` (on `ApplicationReady`) | Restores previously persisted cron jobs so scheduled tasks survive restarts |

### 1.3 Build-Time Processors (`arcclaw-deployment`)

`WorkspaceScopeProcessor` registers `@WorkspaceScoped` as a custom CDI scope at Quarkus **build time**, instructing Arc to generate client proxies for all beans annotated with it. Three `@BuildStep` methods: `customScope()` declares the annotation as a scope; `registerContext()` binds it to `WorkspaceScopeContext`; `unremovableBeans()` prevents Arc dead-code elimination. `RestResourceBuildStep` enforces that any JAX-RS resource injecting `EntityManager` must carry `@RunOnVirtualThread` (Architecture Nuance #16 - prevents `BlockingOperationNotAllowedException`). `AgentFunctionProcessor` processes `@AgentFunction` interfaces and generates JSON schemas from SAM return types at build time via Jandex.

---

## Phase 2: Entry Points (Picocli Command Tree)

Four leaf subcommands all converge on `AgentLoop.run()`:

### `arcclaw serve` → Daemon REST Mode
Starts the Quarkus HTTP server (`quarkus.http.port=8080`) and blocks on `Quarkus.waitForExit()`. The Telegram channel route (`TelegramChannelRoute`) self-registers as a Camel route via its own `StartupEvent` observer - `ServeCommand` itself does nothing except keep the process alive.

### `arcclaw ask "<goal>"` → One-Shot CLI
Creates a UUID session, activates `WorkspaceContext`, calls `AgentLoop.run()` once, prints the final answer to stdout, and terminates the workspace session in the `finally` block.

### `arcclaw chat` → Interactive Terminal
Opens a `Scanner` on stdin. Each line first passes through `ReflexRegistry.processIfReflex()` (handles `/approve`, `/deny`, `/reset`, `/status`, `/history`, etc.). Non-reflex input dispatches to `AgentLoop.run()`. Session remains alive across turns; `ScopedValue.where(WorkspaceContext.CHANNEL_TYPE, ChannelType.CLI.id())` is bound for the lifetime of the loop.

### `arcclaw attach <channel>` → Channel Daemon
Discovers the matching `ChannelDaemon` CDI bean by `channelId()` and calls `daemon.start()` (blocking).

---

## Phase 3: Inbound Message Pipeline

Three distinct ingestion paths all converge on the same `MessagePipeline` and ultimately `AgentLoop.run()`.

### Path A: REST API

```
POST /api/v1/sessions          → create session (autonomy level stored in heartbeat tracker)
POST /api/v1/sessions/{id}/run
  → ApiKeyAuthFilter           (HMAC-SHA256 against stored digest; raw key never stored)
  → Bucket4j token-bucket      (20 req/min per key)
  → AgentSessionService.runSync()
  → handleSlashCommand()       (reflex short-circuit)
  → WorkspaceScopeHolder.call() → WorkspaceContext.run(sessionId, userId, chatId, autonomy, role, ...)
  → AgentLoop.run(sessionId, agentRole, goal, engine, maxIterations, context)
```

`ApiKeyAuthFilter` validates the `X-ArcClaw-API-Key` header by HMAC-SHA256 comparison against digests in `ApiKeyRegistry`; raw keys are never stored. Bucket4j token-bucket enforces 20 requests/minute per key. Successful auth stamps the key owner into MDC `USERID`.

### Path B: Telegram

```
Telegram Bot API
  → Apache Camel (camel-quarkus-telegram long-poll route)
  → TelegramChannelRoute (self-registered via @StartupEvent)
  → TelegramAgentHandler.handleExchange(Exchange)
  → TelegramAllowlistGuard  (numeric userId check against allowlist)
  → ChannelServices.processAsync(ChannelMessage)
  → SessionRegistry.resolveOrCreate(channelType, channelId, userId)
  → SkillAwareIntentDetector.classify(text)    ← compiled Pattern[] fast-path, zero LLM tokens
       ↓ (no skill match)
  → IntentDetectorService.classify(text)       ← LLM-backed classification
  → AgentDispatchHandler → AgentLoop.run(...)
```

`SkillAwareIntentDetector` iterates `static final Pattern[]` from pre-compiled `CompiledSkillEntry` objects in `SkillRegistry`. If any regex matches, it returns `skill:{name}` as the role - zero LLM tokens spent on routing. Only on a miss does it fall through to the LLM-backed `IntentDetectorService`.

### Path C: WebSocket (Web Channel)

```
Browser / client opens ws://host/ws/chat
  → WebChatEndpoint.onOpen()
  → API key validation (X-ArcClaw-API-Key header or Sec-WebSocket-Protocol)
  → UUID platformId assigned; session registered in WebSocketSessionStore
  → channelServices.resolveOrCreateSession() → arcclawSessionId sent in "connected" frame
  → @OnTextMessage: parse JSON {type:"message", text:"..."}
  → ChannelMessage.WebSocketMessage constructed
  → ChannelServices.processAsync(msg)     ← same pipeline as Telegram
```

`WebChannelDaemon` and `WebChannelPlugin` wire the `WebChannelSender`, `WebReplyRenderer`, `WebAllowlist`, and `WebJobNotifier` into the channel plugin registry.

### Path D: Cron Scheduler

```
@Scheduled(every="${arcclaw.cron.poll-interval:30s}", SKIP concurrent execution)
@Transactional(NOT_SUPPORTED)                          ← §5.12 compliant
  → AgentCronScheduler.pollAndDispatch()
  → cronRepository.reclaimStaleCrons(cutoff)           ← crash recovery: RUNNING > 5 min; isolated try/catch
  → cronRepository.findDueCrons()
  → LookbackWindow filter                              ← skip fires outside PT5M window
  → cronRepository.claimForFire(id)                   ← atomic CAS claim
  → Semaphore.tryAcquire()                             ← max 10 concurrent
  → ManagedExecutor virtual thread → CronJobRunner.execute(job)
    → cronRepository.setDeliveryId(id, executionId)   ← ORDERING CRITICAL: before WorkspaceContext.run()
    → WorkspaceContext.run(cronSessionId, userId, chatId, BACKGROUND, role, ...)
    → AgentLoop.run(cronSessionId, ..., effectiveGoal + CRON CONSTRAINT directive)
    → CronResultHandler.handleSuccess / handleFailure()
```

The cron goal is prefixed with a `SYSTEM CONSTRAINT: CRON EXECUTION MODE` directive, instructing the agent not to invoke delivery tools - ArcClaw delivers the output autonomously via the notifier chain.

---

## Phase 4: The Message Pipeline (Handler Chain)

`ChannelServicesImpl.processAsync()` submits the `ChannelMessage` to a virtual-thread executor and runs it through `MessagePipeline` - a sorted list of `MessageHandler` implementations executed in ascending priority order:

| Priority | Handler | Responsibility |
|---|---|---|
| 10 | `AllowlistHandler` | Fetch `ChannelPlugin` for channel type; check `ChannelAllowlist.isAllowed()` |
| 40 | `SessionResolutionHandler` | Resolve or create `SessionRegistry` entry; activate `WorkspaceContext` CDI scope |
| 45 | `CommandDispatchHandler` | Handle `/help` and other built-in commands before intent classification |
| 50 | `IntentClassificationHandler` | Run `ReflexRegistry`; skill routing; LLM intent classification |
| 60 | `CompactionHandler` | If token/message count over threshold, compact chat history via lightweight LLM |
| 70 | `AgentDispatchHandler` | Call `AgentLoop.run()`; render result; send via `ChannelPlugin.sender()` |

Any handler can return `HandleResult.STOP` to short-circuit the chain (e.g., `AllowlistHandler` on a blocked sender, or `IntentClassificationHandler` when a reflex fires and the reply is already sent).

---

## Phase 5: Reflex Layer (Zero-LLM Fast Commands)

`ReflexRegistry` holds `AgentReflex` implementations checked in `IntentClassificationHandler` before any LLM call. If a reflex matches, its `CommandResponse` is sent directly through the channel sender.

| Reflex | Priority | Trigger | Action |
|---|---|---|---|
| `PromptInjectionReflex` | 10 | Injection patterns | Detects "ignore previous instructions" style attacks; rejects message |
| `RetryReflex` | 10 | `/retry` | Retries the last agent action |
| `PiiScanReflex` | 20 | Any message | Runs PII detection pass; blocks if `PII_MODE=BLOCK_AND_NOTIFY` |
| `IdentityExtractionReflex` | 250 | User identity patterns | Extracts and stores user identity information |
| `ApproveReflex` | 100 | `/approve` | Calls `CheckpointApprovalService.approveTool()` |
| `DenyReflex` | 200 | `/deny` | Calls `CheckpointApprovalService.denyTool()` |
| `VaultStoreReflex` | 400 | `/vault-store` | Stores secrets in the encrypted vault |
| `StoreReflex` | 500 | `/store` | Stores key-value pairs |
| `HaltReflex` | 500 | `/halt` | Terminates the running session |
| `HistoryReflex` | 500 | `/history` | Returns recent chat turns |
| `StatusReflex` | 500 | `/status` | Shows current agent status |
| `ListCronsReflex` | 500 | `/crons` | Lists active scheduled jobs |
| `DeleteCronReflex` | 500 | `/delete-cron` | Removes a scheduled job |
| `ResetReflex` | 500 | `/reset` | Wipes session state via `WorkspaceManager.resetSessionState()` |
| `McpReflex` | 500 | `/mcp` | Shows MCP server status and connected tools |
| `SkillReflex` | 500 | `/skills` | Lists available skill personas |

---

## Phase 6: Agent Loop Lifecycle (`AgentLoop.run`)

This is the outer lifecycle shell - it sets up MDC, OTel, checkpoints, and delegates iteration logic to `AgentStateMachineRunner`.

### 6.1 Setup

- MDC keys `SESSION_ID`, `AGENT_ROLE`, `USER_ID` set immediately so the `finally` block remains immune to MDC lifecycle issues.
- OTel session span started via `AgentOtelContext.startSessionSpan(sessionId)`.
- Role resolution: standard roles resolve via `AgentRoleEnum.fromString()`; `skill:X` roles preserve the full prefix so `SystemPromptBuilder` injects the skill's custom system prompt and tool restrictions.

### 6.2 Checkpoint Resume Decision

`agentStore.load(sessionId, canonicalRole)` checks existing checkpoints:

| Checkpoint Status | Action |
|---|---|
| `IN_PROGRESS` | Deserialize `ReasoningStep[]` from `stateJson`; resume with existing history |
| `AWAITING_APPROVAL` + resume signal | Deserialize steps; route to `CheckpointResumeHandler.handle()` |
| `AWAITING_APPROVAL` + new message | **Archive** stale checkpoint; notify user via channel; start fresh (null history) |
| Terminal (`COMPLETED` / `FAILED` / `ARCHIVED` / `COMPROMISED`) | Archive row; start fresh |

### 6.3 CheckpointResumeHandler

If `context.isResumeSignal()` and checkpoint is `AWAITING_APPROVAL`:

- **Denial** → mark checkpoint `FAILED`, set fail reason, return `AgentResult.finished("Tool execution was denied")`.
- **Single-tool approval** → `WorkspaceContext.runWithFullAutonomy()` → `toolRegistry.invokeTool(toolName, params)`; append observation to history; reset checkpoint to `IN_PROGRESS`; return `null` (continue main loop).
- **Parallel batch approval** → `resumeParallelBatch()`: reads `batchToolDecisions` map; skips DENIED tools (fills "User denied" observation); re-invokes APPROVED tools or restores pre-executed results from checkpoint JSON; aggregates into `CompletedBatch` step; continues.
- **Auto-heal on resume failure** → `ResumeStrategyDispatcher` intercepts `sandbox_exec` failures for the coder role on the resume path. If `ExecutionFailureRecoveryService.shouldAttemptRecovery()` returns true, it delegates to Shiva (debugger) in an isolated `:heal:` sub-session, extracts fixed code from Shiva's response, and re-executes the tool. On success, the loop continues via `runFinishPass()`. If auto-heal also fails, falls through to persona escalation (Step C.2).
- **Persona escalation on resume** → `ResumeStrategyDispatcher.tryEscalationOnResume()` checks if the failure matches an escalation pattern (unknown API, architecture decision, obscure error). If `PersonaEscalationService.determineEscalationTarget()` returns a target, it spawns an isolated `:escalate:` sub-session with the target persona. On success, the result is merged back and the loop continues via `runFinishPass()`. On failure, returns `AgentResult.failed()`.

### 6.4 Fast-Path: `delegate_to_agent` Finish

`tryDelegateFinishFastPath()` - if the last history step's action is `delegate-to-agent` and the observation is a clean answer (does not start with ⏳ / ⚠️ / ❌), the coordinator skips its wrap-up LLM call and returns the sub-agent's result directly. Saves approximately 9,800 tokens per delegation.

### 6.5 AgentStateMachineRunner

Fires `AgentStateChangedEvent(active, goal)` for SSE broadcast. Saves goal to `ChatMemory`. Then iterates up to `maxIterations`, each iteration under its own OTel child span (`agent.iteration`).

---

## Phase 7: The Thought-Plan-Act-Observe Loop

### Step A: EngineStepHandler - LLM Call

**System prompt construction** (`SystemPromptBuilder`):
1. Selects the role-specific Qute template from `/templates/personas/` (e.g., `coordinator.txt`, `coder.txt`).
2. Injects the compaction summary from `CompactionSummaryCache` (`@WorkspaceScoped`) - blocks via `CompactionEventBridge.awaitIfInFlight(sessionId)` on first access to prevent stale-null race.
3. Runs all `ContextEnricher` CDI implementations. `RagContextEnricher` (in `arcclaw-rag`) searches both memory tiers, scores chunks by cosine similarity, and injects a `Retrieved Context` block.
4. Appends `SystemEnvironmentContextEnricher` metadata.

**LLM call** (`LangChainReasoningEngine.nextStep()`):
- Retrieves `ChatModel` from `ChatModelProducer` (selects active `AgentChatModelProvider` via `arcclaw.llm.provider` config: groq / openai / ollama / openrouter / claude-code).
- Assembles full message list via `ChatHistoryAssembler` from in-memory `ReasoningStep[]` (SQLite not read mid-run).
- Filters tool list via `RoleRegistry.permittedTools(AgentRoleEnum)` - tools are gated by `ToolCapability` enum.
- Cron sessions additionally suppress `schedule_task` and `manage_schedule` tools from the spec.
- Fires `ChatRequest` with tool specifications.

**Exception classification tree:**

| Exception | Handling |
|---|---|
| HTTP 400 `model_not_found` / `invalid_api_key` | Terminal `FAILED` |
| HTTP 400 `not in request.tools` | Terminal `FINISHED` (tool not in role schema - LLM told so) |
| HTTP 400 `tool_use_failed` with prose | `FINISHED` (extract prose as answer) |
| HTTP 400 schema validation error | Retry with correction prompt (max 2 retries) |
| HTTP 429 rate-limit ≤ 15s | Sleep + retry |
| HTTP 429 rate-limit > 15s | Surface last observation as `FINISH` |
| Network / connect errors | Terminal `FAILED` |
| Truncated code in `failed_generation` | Inject `OBSERVE` retry step with size-constraint instruction |

Budget tracking: `GovernanceStrategy.recordLlmCall()` checks per-session token budgets. `BudgetWarningException` appends a constraint directive to the next step; `BudgetExceededException` terminates the loop immediately.

### Step B: FINISH Action Handler

`handleFinishAction()`:
1. Extracts answer from `actionInput["answer"]` or falls back to `thought`.
2. Unescapes HTML entities; strips Unicode escapes.
3. Role guard: non-coder roles cannot generate bare code blocks → refusal response.
4. Raw tool-call leak guard: if answer contains `{"type":"function"...}`, retry with correction (max 2).
5. `guardFinalAnswer()` checks `CONTEXT_RAG_ECHO_PATTERN` / `CONTEXT_RETRIEVED_ECHO_PATTERN` to prevent LLM echoing injected RAG headers as the answer.
6. `ChatMemoryWriter.saveAnswer(sessionId, answer)` persists the AI message to `PersistentChatMemoryStore`.

### Step C: ToolStepHandler - Validation

`ToolInvocationGuard.evaluate()` runs this decision tree:

```
1. Guard interceptors (SPI: ToolGuardInterceptor, GithubToolGuardInterceptor)
         → Optional<GuardDecision>
2. Deduplication check
         → GuardDecision.Deduplicate(cachedObservation)
3. Role tool schema check
         → GuardDecision.Blocked("Tool not permitted for this role")
4. HITL pre-approval check:
   - roleRegistry.requiresApprovalForTools(role)
   - && tool is NOT a scheduling tool (exempt per §4.10)
   - && autonomy is NOT FULL
         → GuardDecision.PauseForApproval(toolName, paramsJson)
5. Default → GuardDecision.Allow
```

### Step C.1: ToolStepHandler - Auto-Heal Hook

After tool execution, `ToolStepHandler` intercepts `sandbox_exec` failures for the coder role before returning the error to the LLM:

```
sandbox_exec → ToolFailure (coder role only)
  → ExecutionFailureRecoveryService.shouldAttemptRecovery()
     - auto-heal enabled (arcclaw.agent.auto-heal.enabled=true)
     - agentRole == CODER
     - toolName == sandbox_exec
     - NOT an infra failure
  → attemptRecovery(sessionId, userId, chatId, originalCode, language, errorOutput, ...)
     - Spawns isolated Shiva (debugger) sub-session with :heal: sentinel
     - Max attempts configurable (default 2)
     - Accumulates errors across attempts
  → RecoveryResult.success(fixedCode)
     - Re-executes sandbox_exec with fixed code
     - On success: continues loop with new observation
     - On failure: falls through to escalation (Step C.2)
  → RecoveryResult.failure()
     - Falls through to escalation (Step C.2)
```

### Step C.2: ToolStepHandler - Persona Escalation Hook

After auto-heal (or directly for non-sandbox failures), `ToolStepHandler` checks if the failure matches a cross-persona escalation pattern:

```
ToolFailure (any role, any tool)
  → PersonaEscalationService.shouldAttemptEscalation()
     - escalation enabled (arcclaw.agent.escalation.enabled=true)
     - NOT an infra failure
  → determineEscalationTarget(sourceRole, errorOutput, originalGoal)
     - Pattern matching on error content and goal:
       Brahma → Saraswati (unknown API/dependency)
       Brahma → Brihaspati (architecture/trade-off decision)
       Shiva → Saraswati (obscure error needing research)
       Shiva → Brahma (fundamental flaw needing rewrite)
       Saraswati → Brahma (research complete, needs implementation)
       Brihaspati → Brahma (architecture decided, needs coding)
       Brihaspati → Saraswati (needs external research)
  → attemptEscalation(sessionId, userId, chatId, sourceRole, targetRole, ...)
     - Spawns isolated target persona sub-session with :escalate: sentinel
     - Max depth configurable (default 2, prevents infinite loops)
     - Supports chained escalation (target can escalate further)
  → EscalationResult.success(target, result, depth)
     - Returns result as ToolResponse.success() — loop continues
  → EscalationResult.failure()
     - Falls through to normal error path
```

### Step D: ToolStepHandler - Parallel Batch Execution

For parallel tool batches (`ReasoningStep.isParallelBatch() == true`):

```
Pass 1 - Guard ALL tools before ANY executes (§7.7):
  → ANY HITL-required → handleHitlBatch() with pre-execution split (§7.30/7.31)
     - Pre-execute FULL-autonomy tools immediately under runWithFullAutonomy()
     - Persist batch checkpoint with pre-executed results embedded
     - Pause for HITL-pending tools
  → DEDUP → return cached result; peers continue (§7.15)
  → BLOCKED → fill "blocked" observation; peers continue

Pass 2 - Execute ALLOWED tools in parallel virtual threads with OTel context propagation (§7.13)
  → Merge results in original call order for LLM clarity
  → Build CompletedBatch step → append to history
```

On resume after batch approval:
- `batchToolDecisions` map read from checkpoint JSON.
- Pre-executed results restored from checkpoint (not re-executed).
- APPROVED tools invoked; DENIED tools filled with "User denied" observation.
- Double-resume guard: `agentStore.markBatchResuming(sessionId)` is checked atomically before any re-entry (§7.29); cleared in `finally`.

### Step E: ToolInvoker

`toolRegistry.invokeTool(toolName, params)`:
1. Starts OTel child span (`tool.call`).
2. Looks up `AgentTool` by name in `ToolRegistry`.
3. Calls `AgentTool.execute(params)`.
4. Passes raw observation through `ToolObservationSanitizer.sanitize()`:
   - `PiiRedactionTransformer` - strips PII patterns.
   - `InjectionRedactionTransformer` - removes injected instructions from tool output.
   - `TruncationTransformer` - truncates observations exceeding configured limit.
5. Re-throws `RequiresApprovalException` so the state machine can pause cleanly.

### Available Agent Tools

| Tool | Class | Notes |
|---|---|---|
| `brave_web_search` | `BraveWebSearchTool` | Brave Search API; requires `BRAVE_API_KEY` |
| `serper_web_search` | `SerperWebSearchTool` | Serper Google Search API |
| `smart_web_fetch` | `SmartWebFetchTool` | Fetches and extracts content from URLs with SSRF protection |
| `sandbox_exec` | `SandboxExecTool` | Code execution; Podman container when `SANDBOX_STRATEGY=podman` |
| `github` | `GithubTool` | GitHub API; `GithubToolGuardInterceptor` enforces HITL |
| `memory_search` | `MemorySearchTool` | Searches RAG session KB + global KB |
| `ingest_document` | `IngestDocumentTool` | Embeds text into vec0 + `rag_chunk`; always requires approval |
| `schedule_task` | `ScheduleTaskTool` | Creates a new cron job |
| `manage_schedule` | `ManageScheduleTool` | Lists / updates / deletes cron jobs |
| `delegate_to_agent` | `DelegateToAgentTool` | Spawns sub-agent with capability subset check |
| `check_list` | `CheckListTool` | Fires `CheckListEvent` for structured checklists |
| `playwright_cli` | `PlaywrightCliTool` | Headless browser automation via Playwright CLI |
| `agent_task` | `AgentTaskTool` | Task management for agent workflows |
| MCP dynamic tools | `ExternalMcpToolAdapter` | Loaded at runtime from connected MCP servers |

---

## Phase 8: HITL Approval Flow

### Pause (Checkpoint Persistence)

`ApprovalCheckpointManager.persistSingleTool()` or `persistBatch()`:

```
→ agentStore.save(sessionId, role, steps)       [@Transactional(REQUIRES_NEW)]
→ checkpoint.setPendingToolName() / setBatchToolDecisions() [all PENDING]
→ checkpoint.setStatus(AWAITING_APPROVAL)
→ checkpoint.setChannelType() + setChannelPlatformId()   [for resume routing]
→ AgentSseService.publish(HitlEvent(AWAITING_APPROVAL))
→ ChannelPlugin.sender.send([TextNode("Approval needed"), ApprovalPromptNode])
→ AgentResult.awaitingApproval(toolName, paramsJson)   ← loop exits
```

### Approval via Telegram Inline Keyboard

```
User clicks inline keyboard button (BATCH_APPROVE|sessionId|toolName)
  → TelegramCallbackHandler.handleCallback(Exchange)
  → §7.35 expiry guard: agentStore.isCheckpointExpired(sessionId, timeoutHours)
  → BatchHitlKeyboardBuilder.parseCallback(data)
  → ApprovalStateMachine.decide(sessionId, toolName, APPROVE, reason)
       @Transactional(NOT_SUPPORTED)                   ← §5.11 compliant
       → agentStore.findLatestAwaitingApproval(sessionId)  ORDER BY id DESC
       → isCheckpointExpired() → returns EXPIRED if stale
       → idempotency: !pendingTools.contains(toolName) → ALREADY_DECIDED
       → agentStore.updateBatchToolDecision(sessionId, role, tool, "APPROVED")
       → agentStore.isAllBatchToolsDecided()
       → ALL decided → resumeHandler.resumeAfterBatchDecision(...)
            §7.29 double-resume guard: agentStore.markBatchResuming(sessionId)
            WorkspaceContext.runWithFullAutonomy(sessionId, ...) → AgentLoop.run()
            finally: agentStore.clearBatchResuming(sessionId)
       → remaining == 0 → sender.editMessage(chatId, msgId, "completed", null)
```

### Approval via REST

```
POST /api/v1/sessions/{id}/approve?agentRole=coordinator
  → AgentSessionService.approve()
  → WorkspaceScopeHolder.call()
  → agentStore.load() → verify AWAITING_APPROVAL
  → AgentLoop.run(resolvedResumeGoal, isResumeSignal=true)
```

### Approval via Web UI (HitlResource)

```
GET  /hitl            → lists all AWAITING_APPROVAL checkpoints from SQLite
POST /hitl/{id}/approve  → HitlService.resolve(taskId, toolName, true, reason)
                          → CheckpointApprovalService.approveTool() or .approveAll()
POST /hitl/{id}/reject   → HitlService.resolve(taskId, toolName, false, reason)
                          → CheckpointApprovalService.denyTool() or .denyAll()
GET  /hitl/events        → SSE stream via AgentSseService.subscribe(sessionId)
```

`BatchDecisionUtil.pendingToolNames()` determines whether a single-tool or batch approval call is appropriate.

### Approval Timeout

`ChannelApprovalTimeoutDispatcher` observes `ApprovalTimedOutEvent` and calls `ChannelPlugin.jobNotifier.notifyApprovalTimeout()` for the channel that originated the approval request. `TelegramStaleApprovalObserver` additionally monitors stale Telegram HITL messages and auto-denies them after the configured timeout, editing the Telegram message to reflect the expiry.

---

## Phase 9: Session & Workspace Lifecycle

### Session Identity

`SessionRegistry.resolveOrCreate(channelType, channelId, userId)` maps the triple `(channelType, channelId, userId)` to a stable `sessionId` stored in the `session_registry` SQLite table, with a Caffeine in-memory write-through cache for low-latency lookups.

### WorkspaceContext Scoping

```java
WorkspaceContext.run(sessionId, userId, chatId, autonomyLevel, agentRole, channelType, () -> {
    // ScopedValues bound: SESSION_ID, USER_ID, CHAT_ID, AUTONOMY, AGENT_ROLE, CHANNEL_TYPE
    // @WorkspaceScoped CDI beans (CompactionSummaryCache, SessionKnowledgeBase, etc.)
    // are instantiated and bound to this session's context
    return agentLoop.run(...);
});
```

`WorkspaceContext.runWithFullAutonomy()` overrides the autonomy level to `FULL` for tool execution on approved or cron paths, bypassing the HITL gate in `ToolInvocationGuard`.

### Session Heartbeat & Reaping

`SessionHeartbeatTracker` records the last-seen timestamp and cumulative token counts per session. `SessionReaper` (scheduled) terminates sessions that have exceeded the idle timeout via `WorkspaceManager.terminateSession()`.

### Session Termination (7-step teardown)

`WorkspaceManager.terminateSession(sessionId)`:

1. Remove heartbeat from `SessionHeartbeatTracker`.
2. Clear budget memory (`GovernanceLayer.clearSession()`).
3. `markCheckpointsFailed()` - marks only `IN_PROGRESS` / `AWAITING_APPROVAL` / `AWAITING_RETRY_APPROVAL` checkpoints as `FAILED`; excludes `COMPLETED` / `FAILED` / `ARCHIVED` / `COMPROMISED` to prevent race condition with `CronResultHandler` (§5.18 fix).
4. Wipe the session workspace directory (recursive `Files.walkFileTree` delete).
5. Clear in-memory + persistent `ChatMemory` (`MessageWindowChatMemory.clear()`).
6. Destroy the `@WorkspaceScoped` CDI context via `WorkspaceScopeContext.destroySession(sessionId)`.
7. `statefulAgentStore.clearBatchResuming(sessionId)` - clears any in-flight batch resume flag (§7.29).

All `markCheckpointsFailed` writes are `@Transactional(REQUIRES_NEW)` wrapped in `SqliteRetryTemplate` with exponential backoff + jitter on `SQLITE_BUSY`.

---

## Phase 10: Compaction (Chat History Summarization)

`CompactionHandler` (priority 60) runs before the agent dispatch on every message if the session's token or message count exceeds the configured threshold.

`CompactionService.compact()`:
1. Fetches old `ChatMemory` turns beyond the compaction window.
2. Calls the **compaction model** - a separate, lighter LLM produced by `CompactionModelProducer` (default `llama-3.1-8b-instant` via Groq, configured independently from the reasoning model).
3. Writes the summary to `CompactionSummaryEntity` in SQLite.
4. Clears the compacted turns from `PersistentChatMemoryStore`.
5. Releases `CompactionEventBridge` latch so the subsequent `CompactionSummaryCache.getSummary()` call in `SystemPromptBuilder` reads the fresh summary.

The `CompactionEventBridge` uses a `CountDownLatch` per session to prevent a race condition where `CompactionSummaryCache` populates before the compaction write commits, caching `null` for the rest of the session.

---

## Phase 11: RAG Memory System

### Two-Tier Architecture

| Tier | Store | Scope | Persistence |
|---|---|---|---|
| Session KB | `InMemoryEmbeddingStore` (`@WorkspaceScoped`) | Per session | Ephemeral - lost on session destroy |
| Global KB | SQLite `fts.vec_global` (vec0 virtual table) + `rag_chunk` table | Cross-session | Persistent across restarts |

### Ingest Flow

`IngestDocumentTool.execute()` (always requires manual HITL approval):
1. Sanitize text (strip consecutive dashes, markdown artifacts, control characters).
2. `EmbeddingModel.embed(text)` → float vector.
3. `INSERT OR REPLACE INTO fts.vec_global(chunkId, embedding)` - vec0 ANN index.
4. `em.persist(RagChunkEntity)` - stores original text for hydration.
5. `sessionKb.ingest()` - adds to in-memory `InMemoryEmbeddingStore`.
6. Returns `chunkId` as observation.

### Retrieval

`RagContextEnricher.enrich(sessionId, goal, basePrompt)` is called by `SystemPromptBuilder` on every LLM turn:
1. Embeds the current `goal` query.
2. Searches session KB (in-memory ANN) and global KB (`fts.vec_global` via vec0 KNN query).
3. Scores chunks by cosine similarity; deduplicates by `chunkId`.
4. Injects the top-N chunks as a `Retrieved Context` block into the system prompt.
5. Applies `CONTEXT_RAG_ECHO_PATTERN` guard in `guardFinalAnswer()` to prevent the LLM from echoing injected headers.

### Embedding Provider

`EmbeddingModelProducer` resolves the provider via `arcclaw.rag.embed-provider` (ollama / openai / jina) and delegates to the matching `EmbeddingProviderFactory` CDI bean.

---

## Phase 12: Skills System

Skills are YAML-fronted markdown files that define a custom agent persona with its own system prompt, tool capabilities, trigger patterns, and max-iteration limit.

### YAML Frontmatter Schema

```yaml
---
name: code-reviewer
description: Reviews code for bugs and style issues
trigger-patterns:
  - "review.*code"
  - "check.*pr"
tool-capabilities:
  - GITHUB
  - MEMORYSEARCH
max-iterations: 5
---
You are a meticulous code reviewer...
```

### Loading Pipeline

```
StartupEvent → SkillLoader.onStart()
  → ClasspathSkillProvider  (priority 100 - reads /skills/index.txt from classpath)
  → DatabaseSkillProvider   (priority 10  - reads agent_skill table from SQLite)
  → SkillDefinitionParser.parse(yaml)    [splits on --- delimiter; Jackson YAML for frontmatter]
  → Pattern.compile(triggerPatterns)     [pre-compiled for O(1) matching per pattern]
  → RoleRegistry.registerDynamic(skill:name, RoleDefinition)
  → SkillRegistry.updateSnapshot(CompiledSkillEntry[])
```

Database skills can override classpath skills by name (lower priority number = higher precedence). `SkillResource` (REST CRUD at `/skills`) provides hot-reload via `skillLoader.reloadAll(false)` after every save or delete.

### Routing

`SkillAwareIntentDetector` (CDI `@Alternative @Priority(1)`, overriding `IntentDetectorService`) iterates `CompiledSkillEntry[]` in O(n·p) where p = pattern count per skill. On match, returns `skill:{name}` immediately. `SystemPromptBuilder` detects the `skill:` prefix and injects the skill's custom system prompt and its `RoleDefinition`-derived tool restrictions.

---

## Phase 13: MCP (Model Context Protocol) Integration

### ArcClaw as MCP Server

`McpAdminResource` (`GET /mcp/tools`) exposes ArcClaw's internal tools as MCP-compatible descriptors via `McpToolBridge.getExposedTools()`. `AgentFunctionProcessor` (build-time) generates JSON schemas from `@AgentFunction`-annotated SAM interfaces, writing them to `arcclaw/schemas/{SchemaName}.json` as classpath resources.

### ArcClaw as MCP Client (Dynamic Tool Loading)

```
POST /api/v1/mcp/connect  {name, url}
  → McpClientService.connect(name, url)
  → SSE handshake with remote MCP server
  → Discover tool list from server
  → ExternalMcpToolAdapter wraps each as AgentTool
  → DynamicToolCapabilityRegistry.register(toolDef)
  → Available as tools in agent roles at next invocation
```

Self-connect smoke test: connecting to `http://localhost:8080/mcp` makes ArcClaw's own tools discoverable as dynamic tools - useful for multi-agent delegation patterns and tool composition. `McpBuildStep` in `arcclaw-deployment` registers all `com.arcclaw.mcp.*` classes for GraalVM reflection in native image builds.

---

## Phase 14: Multi-Agent Delegation

`DelegateToAgentTool` allows the coordinator to spawn sub-agents:

```
Coordinator: THINK → ACTION: delegate_to_agent(agent="brahma", goal="Write unit tests for...")
  → ToolInvocationGuard: scheduling tools are EXEMPT from HITL (§4.10)
  → Capability subset check: sub-agent role must have a subset of coordinator's tools
    (prevents transitive privilege escalation)
  → New WorkspaceContext.run(subSessionId, userId, chatId, agentRole, ...)
  → AgentLoop.run(subGoal, subRole)
  → Sub-agent executes its own Thought-Plan-Act-Observe loop
  → Returns clean answer as tool observation
  → tryDelegateFinishFastPath() → skip coordinator wrap-up LLM call (~9,800 tokens saved)
```

### Named Sub-Agents

| Agent Name | Role | Persona Template |
|---|---|---|
| `brahma` | Coder | `coder.txt` / `role-coder.txt` |
| `shiva` | Debugger | `debugger.txt` / `role-debugger.txt` |
| `saraswati` | Researcher | `researcher.txt` / `role-researcher.txt` |
| `brihaspati` | Architect | `architect.txt` / `role-architect.txt` |
| `dattatreya` | Synthesizer | `synthesizer.txt` / `role-synthesizer.txt` |
| `vishnu` | Coordinator | `coordinator.txt` / `role-coordinator.txt` |

`Dattatreya` (synthesizer) uses a structured output path via `SynthesisFunction` returning `SynthesisResult` (contributions from each sub-agent + `ConflictSummary` + `finalResolution`). Implemented via `StructuredOutputEngine` + `StructuredRetryLoop` with `PermissiveJsonExtractor`.

### Auto-Heal: Shiva as Code Fixer

`ExecutionFailureRecoveryService` automatically delegates sandbox execution failures to Shiva (debugger) for self-correction:

```
Brahma (coder) → sandbox_exec → FAIL
  → ExecutionFailureRecoveryService intercepts (ToolStepHandler or ResumeStrategyDispatcher)
  → Auto-delegate to Shiva with: original code + language + full error output
  → Shiva analyzes, fixes, returns corrected code in a single fenced code block
  → RecoveryService extracts fixed code via regex (```lang\n...\n```)
  → Re-executes sandbox_exec with fixed code
  → On success: continues agent loop
  → On failure after max attempts: returns error to user
```

**Configuration:**
| Property | Default | Description |
|---|---|---|
| `arcclaw.agent.auto-heal.enabled` | `true` | Enable/disable auto-heal |
| `arcclaw.agent.auto-heal.max-attempts` | `2` | Max Shiva delegation attempts per failure |

**Session sentinel:** `HEAL_SESSION_SENTINEL` (`:heal:`) in `AgentSentinels` marks auto-heal sub-sessions. Format: `{parent_sid}:heal:{agentName}:{currentTimeMillis}:a{attempt}`.

**Debugger persona:** `debugger.txt` includes `AUTO-HEAL MODE` instructions — Shiva returns only a single fenced code block with the corrected code, no explanations.

### Cross-Persona Escalation

`PersonaEscalationService` detects when a persona hits its capability boundary and auto-routes the task to a more suitable specialist:

```
Source persona → tool call → FAIL / knowledge gap
  → PersonaEscalationService.shouldAttemptEscalation()
  → determineEscalationTarget() via pattern matching:
     Brahma (coder)    → Saraswati (researcher)   : unknown API/dependency
     Brahma (coder)    → Brihaspati (architect)   : architecture/trade-off decision
     Shiva (debugger)  → Saraswati (researcher)   : obscure error needing research
     Shiva (debugger)  → Brahma (coder)           : fundamental flaw needing rewrite
     Saraswati (researcher) → Brahma (coder)      : research complete, needs implementation
     Brihaspati (architect) → Brahma (coder)      : architecture decided, needs coding
     Brihaspati (architect) → Saraswati (researcher): needs external research
     Dattatreya (synthesizer) → any               : conflicting outputs need re-specialization
  → Isolated sub-session with :escalate: sentinel
  → Target persona handles and returns result
  → Result merged back into source persona's context
  → Chained escalation: target can escalate further (up to max depth)
```

**Configuration:**
| Property | Default | Description |
|---|---|---|
| `arcclaw.agent.escalation.enabled` | `true` | Enable/disable cross-persona escalation |
| `arcclaw.agent.escalation.max-depth` | `2` | Max escalation chain depth (prevents infinite routing loops) |

**Session sentinel:** `ESCALATION_SESSION_SENTINEL` (`:escalate:`) in `AgentSentinels` marks escalation sub-sessions. Format: `{parent_sid}:escalate:{source}:{target}:{currentTimeMillis}`.

**Persona templates:** All 6 persona templates include `ESCALATION RULES` sections defining when and how to escalate to other personas.

**Integration points:**
- `ToolStepHandler` — escalation hook after tool failure (and after auto-heal failure)
- `ResumeStrategyDispatcher` — escalation hook on HITL resume path failure

---

## Phase 15: LLM Provider Architecture

`ChatModelProducer` produces the single `ChatModel` bean by selecting the active `AgentChatModelProvider` based on `arcclaw.llm.provider` config:

| Provider ID | Implementation | Notes |
|---|---|---|
| `groq` | `GroqProviderFactory` | OpenAI-compatible API at `api.groq.com`; default `meta-llama/llama-4-scout-17b-16e-instruct` |
| `openai` | `OpenAiProviderFactory` | Official OpenAI API |
| `ollama` | `OllamaProviderFactory` | Local inference; default `mistral-small:latest` |
| `openrouter` | `OpenRouterProviderFactory` | Unified multi-model API |
| `claude-code` | `ClaudeCodeProviderFactory` | `ClaudeCodeOAuthTokenExtractor` reads OAuth token from local Claude Code CLI installation |
| `cerebras` | `CerebrasLlmProviderFactory` | Cerebras inference API; daily quota detection disables slot permanently |
| `gemini` | `GeminiLlmProviderFactory` | Google Gemini API |
| `nvidia` | `NvidiaLlmProviderFactory` | NVIDIA inference API |
| `omlx` | `OmlxLlmProviderFactory` | OMLX inference API |
| `infinite-loop` | `InfiniteLoopAgentChatModelProvider` | Multi-slot provider with quality tracking and cooldown |

The **compaction model** is produced separately by `CompactionModelProducer` (in `arcclaw-app`) - always a lighter/cheaper model regardless of the main reasoning provider, configurable via `arcclaw.memory.compaction.*` properties.

`RoutingChatModelProvider` can route requests across providers based on intent or load. `LlmValidationService` implements `LlmValidationGateway` so the onboarding wizard can fire a test call to validate credentials before persisting them.

---

## Phase 16: Cron Result Handling & Notification

After `AgentLoop.run()` returns in the cron path, `CronResultHandler` processes the result:

| Result Type | Action |
|---|---|
| `Finished` (run-once job) | `cronRepository.markCompleted()` + audit `CRON_FIRED` event |
| `Finished` (recurring job) | `CronScheduleUtil.computeNext()` → `cronRepository.markFired(nextFireTime)` |
| `AwaitingApproval` | `cronRepository.markAwaitingApproval()` - session kept alive for `ApproveReflex` |
| `Failed` / `LoopExceeded` | `cronRepository.markFailed()` + audit `CRON_FAILED` event |

`CronResultHandler.fireCompletionEvent()` fires an `AgentJobCompleted` CDI event. `ChannelCronNotificationDispatcher` observes it and calls the matching `ChannelPlugin.jobNotifier()` based on `job.channelType`. `CliCronNotificationObserver` prints to stdout for CLI-scheduled tasks.

---

## Phase 17: Audit & Compliance

### WORM Audit Chain

Every significant event writes an `AuditEvent` with an HMAC-SHA256 chain linking records by sequence number:

```
AuditService.record(sessionId, userId, agentRole, SecurityEventType, detail)
  → AuditHashUtil.compute(id + sessionId + eventType + detail + timestamp, vaultKey)
  → AuditEventRepository.record() → em.persist(AuditEvent)
```

`AuditVerifier.verifyChain(sessionId)` re-computes HMAC for all events and confirms no tampering. `GET /audit/{sessionId}` exposes the full chain. `AsyncPersistenceEngine` drains two virtual threads (critical path / telemetry path). `ComplianceViolationException` bypasses normal error handling, marks the checkpoint as `COMPROMISED`, and surfaces `SECURITY ALERT` in structured logs.

### Security Events Audited

`SecurityEventType` includes: `SESSION_CREATED`, `SESSION_TERMINATED`, `TOOL_INVOKED`, `TOOL_APPROVED`, `TOOL_DENIED`, `CHECKPOINT_PERSISTED`, `CHECKPOINT_RESUMED`, `CRON_FIRED`, `CRON_FAILED`, `COMPLIANCE_VIOLATION`, `INJECTION_DETECTED`, `PII_DETECTED`.

---

## Phase 18: Observability

### OpenTelemetry

Spans at three levels, all parented under the session root span:
- **Session span** (`arcclaw.session`): `otelContext.startSessionSpan(sessionId)` - accumulates cumulative `llm.usage.prompt_tokens` and `llm.usage.completion_tokens`.
- **Iteration span** (`agent.iteration`): one per `AgentStateMachineRunner` loop iteration; carries `arcclaw.session.id`, `arcclaw.agent.role`, `arcclaw.iteration`.
- **Tool span** (`tool.call`): one per `ToolInvoker.invoke()` call; carries `arcclaw.tool.name`, `tool.operation`.
- **Reflex span** (`reflex.execute`): one per matched reflex.

Exported to Jaeger via OTLP at `http://localhost:4317`.

### Micrometer

`MetricsAgentObserver.onLoopComplete()` records per-loop Micrometer counters and histograms (token counts, iteration count, result type). `AgentStatusResource` exposes Micrometer metrics at the standard Quarkus `/q/metrics` endpoint.

### SSE Real-Time UI Events

`AgentSseService` publishes typed events consumed by the Lit web frontend:

| Event | Trigger |
|---|---|
| `AgentStateChangedEvent` | `idle` / `active` / `awaiting-approval` state transitions |
| `HitlEvent` | `AWAITING_APPROVAL` / `APPROVED` / `DENIED` / `RESUMED` |
| `AgentStatusEvent` | `RUNNING` / `COMPLETED` / `FAILED` |
| `HeartbeatEvent` | 20-second keep-alive from `ChatStreamResource` |

`GET /agents/events?sessionId=...&apiKey=...` and `GET /hitl/events?sessionId=...&apiKey=...` both stream SSE via `AgentSseService.subscribe(sessionId)`.

---

## Phase 19: Security Architecture

### API Key Authentication

Keys stored as `HMAC-SHA256(rawKey, hmacSecret)` - never plaintext. `ApiKeyRegistry` maintains a `hmacToKeyName` map initialized at startup. Per-key Bucket4j token buckets enforce rate limits; `isAllowed()` atomically consumes one token.

### Secret Vault

`SecretVault` (`SecretVaultImpl`) wraps AES-GCM-SIV encrypted secrets with `ARCCLAW_VAULT_PEPPER` as the Argon2id KDF salt. `VaultKey implements AutoCloseable` zeroes key bytes on `close()` and via `@PreDestroy`. `VaultKeyCache` caches derived keys to avoid repeated KDF on every vault access.

### Sandbox

`ARCCLAW_SANDBOX_STRATEGY=podman` (configured via env or `application.yaml`) routes `SandboxExecTool` to execute code inside an isolated Podman container, preventing user code from accessing the host process, filesystem, or network beyond the container boundary.

### PII Mode

`ARCCLAW_PII_MODE=BLOCK_AND_NOTIFY` activates `InputSanitizer.PII` to strip or block PII before it reaches LLM calls or logs. `PiiScanReflex` acts as an early gate in the reflex layer.

### Destructive Vocabulary Filter

`DestructiveVocabularyFilter` scans tool parameters for destructive keywords (e.g., `rm -rf`, `DROP TABLE`) and escalates to HITL approval even if the role would normally run with full autonomy.

---

## Phase 20: Frontend (Lit Web Components)

The `arcclaw-ui` module is a TypeScript / Lit Web Components application served statically and communicating with the backend over WebSocket and REST.

### Key Components

| Component | Role |
|---|---|
| `arc-chat-interface` | Main chat UI; opens `ws://host/ws/chat`; renders HITL approval cards inline via SSE |
| `arc-settings` | Tabbed UI for LLM provider switch, API key update, system prompt edit |
| `arc-onboarding` | 6-step wizard; calls `/onboarding/step*` REST endpoints |
| `arc-agent-status` | Polls `GET /agents` + subscribes `GET /agents/events` SSE for live agent status |
| `arc-audit-panel` | Displays WORM audit chain events for session inspection |
| `arc-scheduler-panel` | Manages cron jobs - list, create, delete scheduled tasks |
| `arc-hitl-panel` | Human-in-the-Loop approval management interface |
| `arc-skills-panel` | Skill management - view and configure agent skills |
| `arc-shell` | Main application shell with navigation |
| `arc-nav` | Navigation sidebar component |
| `arc-connection-badge` | WebSocket connection status indicator |
| `arc-theme-toggle` | Light/dark theme toggle |

### WebSocket Protocol

Browser sends: `{"type":"message","text":"user input"}`.
Backend sends: `{"type":"message","role":"assistant","text":"response"}`.
On open: `{"type":"connected","platformId":"<uuid>","sessionId":"<uuid>"}`.
On HITL: HITL events arrive via `GET /hitl/events` SSE and are rendered as inline approval cards.

---

## Phase 21: Onboarding Wizard

A 6-step REST wizard at `/onboarding`:

| Step | Endpoint | Payload | Action |
|---|---|---|---|
| 1 | `POST /onboarding/step/1` | - | Returns system info (version, current provider) |
| 2 | `POST /onboarding/step/2` | `{provider, model}` | Saves LLM provider config |
| 3 | `POST /onboarding/step/3` | `{apiKey}` | Validates credential via `LlmValidationGateway`; encrypts in vault |
| 4 | `POST /onboarding/step/4` | `{systemPrompt}` | Persists custom system prompt |
| 5 | `POST /onboarding/step/5` | `{mcpServers[]}` | Registers MCP server connections |
| 6 | `POST /onboarding/step/6` | `{telegramToken}` | Configures Telegram bot token |
| - | `GET /onboarding/status` | - | Returns current onboarding session status |
| - | `GET /onboarding/complete` | - | Finalizes setup, returns API key and redirect URL |
| - | `GET /settings` | - | Returns current config (`hasApiKey`, `provider`, `model`, `version`) |

`OnboardingStepProvider` SPI allows future steps to self-register (e.g., Brave Search key, additional channel tokens).

---

## Persistence Layer

### SQLite Tables

| Table | Entity | Purpose |
|---|---|---|
| `session_registry` | `SessionRegistryEntry` | `(channelType, channelId, userId)` → `sessionId` mapping |
| `agent_checkpoint` | `AgentCheckpointEntity` | Checkpoint state machine; `ReasoningStep[]` JSON; batch tool decisions |
| `agent_cron` | `AgentCronEntity` | Cron job definitions, fire times, delivery IDs, status |
| `agent_cron_run` | `CronRunEntity` | Cron execution history with delivery tracking |
| `chat_memory` | `ChatMemoryEntity` | Persistent `ChatMemory` for all sessions |
| `compaction_summary` | `CompactionSummaryEntity` | LLM-generated summaries of compacted chat history |
| `audit_event` | `AuditEventEntity` | HMAC-chained audit records |
| `agent_skill` | `AgentSkillEntity` | User-defined skill definitions (YAML) |
| `rag_chunk` | `RagChunkEntity` | RAG text chunks (hydration for vec0 results) |
| `encrypted_secrets` | `EncryptedSecretEntity` | AES-GCM-SIV encrypted vault entries |
| `advisory_lock` | `AdvisoryLockEntity` | Distributed advisory locking for coordination |
| `reasoning_step` | `ReasoningStepEntity` | Individual reasoning step persistence |
| `fts.vec_global` | - | vec0 virtual table for global KNN embedding search (ATTACHed FTS db) |

### Reliability Patterns

- All checkpoint writes: `@Transactional(REQUIRES_NEW)` called from `NOT_SUPPORTED` outer frames to prevent `REQUIRED→REQUIRES_NEW` nesting (§5.12 compliant).
- `SqliteRetryTemplate` wraps critical writes with exponential backoff + jitter on `SQLITE_BUSY`.
- `ArcClawSQLiteDialect` extends the Hibernate community SQLite dialect with an ORM 7 DDL identity column fix.
- `SqliteWalConfigurer` enables WAL mode for concurrent read/write access.
- Agroal `background-validation=true` keeps the connection pool healthy.

---

## Complete Data-Flow Summary

```
External Input
  (Telegram long-poll / REST POST / WebSocket ws: / CLI stdin / Cron @Scheduled)
         │
         ▼
  Security Gate
  (ApiKeyAuthFilter HMAC-SHA256 + Bucket4j / TelegramAllowlistGuard numeric userId)
         │
         ▼
  MessagePipeline
  ┌─────────────────────────────────────────┐
  │ AllowlistHandler (priority 10)          │
  │ SessionResolutionHandler (priority 40)  │  ← SessionRegistry → sessionId
  │ CommandDispatchHandler (priority 45)    │  ← /help and built-in commands
  │ IntentClassificationHandler (priority 50)  ← ReflexRegistry → short-circuit reply
  │   └─ SkillAwareIntentDetector           │     or skill:name / coordinator role
  │ CompactionHandler (priority 60)         │  ← summarize if over threshold
  │ AgentDispatchHandler (priority 70)      │
  └─────────────────────────────────────────┘
         │
         ▼
  WorkspaceContext.run(sessionId, userId, chatId, autonomy, role, channelType, ...)
  (activates @WorkspaceScoped CDI scope; binds ScopedValues)
         │
         ▼
  AgentLoop.run()
  ┌─── Checkpoint resume logic ────────────────────────────────────┐
  │  IN_PROGRESS → restore history                                 │
  │  AWAITING_APPROVAL → CheckpointResumeHandler                   │
  │  Terminal → archive, start fresh                               │
  └────────────────────────────────────────────────────────────────┘
         │
         ▼
  AgentStateMachineRunner.run()
  ┌─── LOOP up to maxIterations ───────────────────────────────────┐
  │                                                                 │
  │  EngineStepHandler                                              │
  │    → SystemPromptBuilder (role template + compaction + RAG)     │
  │    → LangChainReasoningEngine → LLM (Groq/OpenAI/Ollama/...)   │
  │    → ReasoningStepParser → ReasoningStep                        │
  │    → GovernanceLayer (token budget check)                       │
  │                                                                 │
  │  FINISH? → guardFinalAnswer() → ChatMemoryWriter → return       │
  │                                                                 │
  │  ToolStepHandler                                                │
  │    → ToolInvocationGuard                                        │
  │         ├─ Dedup → cached observation                           │
  │         ├─ Role schema blocked → error observation              │
  │         └─ HITL required → ApprovalCheckpointManager           │
  │              → persist(AWAITING_APPROVAL) → SSE HitlEvent       │
  │              → AwaitingApproval result ← loop exits             │
  │    → ToolInvoker → AgentTool.execute(params)                    │
  │         └─ ToolObservationSanitizer (PII / injection / truncate)│
  │    → Auto-Heal (sandbox_exec failure, coder role)               │
  │         └─ ExecutionFailureRecoveryService → Shiva :heal: sub   │
  │            → re-exec with fixed code → continue or fall through │
  │    → Persona Escalation (any failure, any role)                 │
  │         └─ PersonaEscalationService → target :escalate: sub     │
  │            → pattern match → delegate → merge → continue        │
  │    → ChatMemoryWriter.saveStep()                                 │
  └─────────────────────────────────────────────────────────────────┘
         │
         ▼
  AgentResult
  (Finished / StructuredFinished / Failed / LoopExceeded / AwaitingApproval)
         │
  ┌──────┴──────────────────────────────────────┐
  │ agentStore.saveAndFinalize()                │
  │ AgentSseService.publish(AgentStatusEvent)   │
  │ AuditService.record(HMAC-chained event)     │
  │ MetricsAgentObserver.onLoopComplete()       │
  │ AgentOtelContext.endSessionSpan()           │
  └──────┬──────────────────────────────────────┘
         │
         ▼
  ReplyRenderer → ChannelPlugin.sender.send(ReplyNode[])
  (TelegramMarkdownV2Renderer / WebReplyRenderer / CLI stdout)
         │
         ▼
  User receives response
```

Every actor - Telegram users, REST clients, WebSocket sessions, CLI operators, and scheduled cron jobs - converges through the same checkpoint-aware `AgentLoop`, ensuring consistent governance, HITL approval, WORM audit chaining, and distributed observability regardless of input channel.
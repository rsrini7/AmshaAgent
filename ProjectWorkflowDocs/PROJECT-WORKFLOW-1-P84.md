# ArcClaw — Complete End-to-End Workflow

ArcClaw is a **multi-modal, multi-channel, enterprise-grade Java/Quarkus AI agent framework** built around a checkpoint-driven Thought-Plan-Act-Observe loop with Human-in-the-Loop (HITL) governance, pluggable channel adapters, persistent state, RAG memory, and MCP integration.

***

## Module Architecture

| Module | Role |
|---|---|
| `arcclaw-app` | Main deployable; CLI, REST endpoints, scheduler, channel wiring |
| `arcclaw-core` | Agent loop, state machine, tools, audit, governance, SSE |
| `arcclaw-channel-api` | Channel SPI abstractions (sealed interfaces, plugin contracts) |
| `arcclaw-channel-telegram` | Apache Camel–backed Telegram adapter |
| `arcclaw-channel-web` | WebSocket channel adapter |
| `arcclaw-rag` | Two-tier vector/FTS5 RAG memory |
| `arcclaw-mcp` | Model Context Protocol server + client bridge |
| `arcclaw-onboarding` | 5-step setup wizard REST API |

***

## Phase 1: Bootstrap & Startup

`ArcClawCli.main()` runs **before** Quarkus CDI boots and performs these ordered steps: 

1. **Env-var validation** — `CliUtils.validateRequiredEnvVars()` prints a clear setup guide and exits if required keys are absent; Quarkus never starts with a missing config.
2. **Data directory provisioning** — resolves `ARCCLAW_DATA_DIR` to an absolute path, creates `workspaces/` sub-dir, and pins it back via `System.setProperty` so every YAML expression (`${ARCCLAW_DATA_DIR}`) resolves to the same location regardless of JVM launch directory.
3. **vec0 native library installation** — `Vec0NativeLibraryInstaller.performInstallation()` (Priority 10) extracts the correct SQLite-vec `.so/.dylib/.dll` from JAR resources, enabling vector search before any connection opens.
4. **Quarkus boot** — `Quarkus.run(ArcClawCli.class, args)` starts the CDI container, Hibernate, Agroal, and all extensions.

After CDI starts, **Quarkus `StartupEvent` observers** fire in priority order: 

- Priority 10 → `Vec0NativeLibraryInstaller` (already fired pre-CDI but re-fires via observer too)
- Priority 90 → `SessionSchemaEnforcer` creates `session_registry` DDL
- Priority 100 → `RagSchemaInitializer` warms the Agroal pool, triggers `FtsConnectionInterceptor.onConnectionAcquire()` which loads `vec0`, then creates the `fts.vec_global` virtual table
- Unordered → `SkillLoader.onStart()` loads skills from classpath + DB; `StartupSecurityValidator` validates pepper, HMAC vault key, and Telegram allowlist (throws `DeploymentException` on failure in non-dev profiles)

***

## Phase 2: Entry Points (Picocli Dual-Mode)

The picocli command tree has four leaf subcommands: 

### `arcclaw serve` → Daemon REST Mode
Starts the Quarkus HTTP server (`quarkus.http.port=8080`) and blocks. The Telegram channel route self-registers via its own `StartupEvent` observer — `ServeCommand` itself does nothing except call `Quarkus.waitForExit()`.

### `arcclaw ask "<goal>"` → One-Shot CLI
Creates a UUID session, calls `AgentLoop.run()` once, prints the final answer, and terminates the workspace session in the `finally` block.

### `arcclaw chat` → Interactive Terminal
Opens a `Scanner` on stdin. Each line first passes through `ReflexRegistry.processIfReflex()` (approve/deny/reset/status/history). Non-reflex input dispatches to `AgentLoop.run()`. The session remains alive across turns until the user types `exit`.

### `arcclaw attach <channel>` → Channel Daemon
Discovers the matching `ChannelDaemon` CDI bean by ID and calls `daemon.start()` (blocking).

***

## Phase 3: Inbound Message Pipeline

Three distinct ingestion paths all converge on `AgentLoop.run()`: 

### Path A: REST API

```
POST /api/v1/sessions          → create session (autonomy level stored in heartbeat tracker)
POST /api/v1/sessions/{id}/run → ApiKeyAuthFilter (HMAC-SHA256 + Bucket4j rate limit)
                                 → AgentSessionService.runSync()
                                 → handleSlashCommand() (reflex short-circuit)
                                 → WorkspaceScopeHolder.call() → WorkspaceContext.run()
                                 → AgentLoop.run()
```

`ApiKeyAuthFilter` validates the `X-ArcClaw-API-Key` header by HMAC-SHA256 comparison against stored digests; raw keys are never stored. Bucket4j token-bucket enforces 20 requests/minute per key. Successful auth stamps the key owner into MDC `USERID`. 

### Path B: Telegram

```
Telegram Bot API
  → Apache Camel (camel-quarkus-telegram route)
  → TelegramAgentHandler.handleExchange(Exchange)
  → ChannelServices.processAsync(ChannelMessage)
  → SessionRegistry.resolveOrCreate(channelType, channelId, userId)
  → SkillAwareIntentDetector.classify(text)    ← regex fast-path
       ↓ (no skill match)
  → IntentDetectorService.classify(text)       ← LLM classification
  → AgentDispatchHandler → AgentLoop.run()
```

`SkillAwareIntentDetector` iterates compiled `Pattern[]` from `SkillRegistry` — if any matches, it bypasses the LLM call entirely and returns `skill:{name}` as the role. Only on a miss does it fall through to the LLM-backed `IntentDetectorService`. 

### Path C: Cron Scheduler

```
Quarkus @Scheduled(every="${arcclaw.cron.poll-interval:30s}")
  → AgentCronScheduler.pollAndDispatch()
  → cronRepository.reclaimStaleCrons()           ← crash recovery (RUNNING > 5 min)
  → cronRepository.findDueCrons()
  → LookbackWindow filter (skip missed fires outside PT5M window)
  → cronRepository.claimForFire(id)              ← atomic claim
  → Semaphore.tryAcquire()                       ← throttle (max 10 concurrent)
  → ManagedExecutor virtual thread
  → CronJobRunner.execute(job)
  → cronRepository.setDeliveryId()               ← ORDERING CRITICAL before context setup
  → WorkspaceContext.run(cronSessionId, userId, chatId, BACKGROUND)
  → AgentLoop.run()
  → CronResultHandler.handleSuccess/handleFailure()
```

The cron goal is prefixed with a `SYSTEM CONSTRAINT CRON EXECUTION MODE` directive, instructing the agent not to call delivery tools — ArcClaw delivers the output autonomously. 

***

## Phase 4: Agent Loop (`AgentLoop.run`)

This is the outer lifecycle shell — it sets up context and delegates all iteration logic to `AgentStateMachineRunner`: 

### 4.1 Setup
- MDC keys (`SESSION_ID`, `AGENT_ROLE`, `USER_ID`) set for structured logging
- OTel session span started via `AgentOtelContext`
- Role resolution: standard roles resolve via `AgentRoleEnum.fromString()`; `skill:*` roles preserve the full key so `SystemPromptBuilder` injects the skill's custom system prompt

### 4.2 Checkpoint Resume Decision
`agentStore.load(sessionId, canonicalRole)` checks existing checkpoints: 

| Status | Action |
|---|---|
| `IN_PROGRESS` | Deserialize steps, resume history |
| `AWAITING_APPROVAL` | Deserialize steps, route to `CheckpointResumeHandler` |
| `COMPLETED` / `FAILED` / `ARCHIVED` | Archive row, start fresh |

### 4.3 CheckpointResumeHandler
If `context.isResumeSignal()` and checkpoint is `AWAITING_APPROVAL`: 
- **Denial** → mark `FAILED`, set fail reason, return `AgentResult.finished("Tool execution was denied")`
- **Single tool approval** → `WorkspaceContext.runWithFullAutonomy()` → `toolRegistry.invokeTool()`, append to history, reset checkpoint to `IN_PROGRESS`, return `null` (continue main loop)
- **Parallel batch approval** → `resumeParallelBatch()`: reads `batchToolDecisions` map, skips DENIED tools, re-invokes APPROVED tools (or restores pre-executed results), aggregates, continues

### 4.4 Fast-path: delegateToAgent
If the last history step's action is `delegate-to-agent` and the observation is a clean answer (not an error/empty), `tryDelegateFinishFastPath()` returns the sub-agent's result directly, skipping the coordinator wrap-up LLM call (saves ~9,800 tokens). 

### 4.5 AgentStateMachineRunner.run()
Fires initial `AgentStateChangedEvent(active, goal)` for SSE. Saves goal to ChatMemory. Then iterates up to `maxIterations`: 

***

## Phase 5: The Thought-Plan-Act-Observe Loop

Each iteration runs under its own OTel span: 

### Step A: EngineStepHandler (LLM Call)
`ReasoningEngine.nextStep(sessionId, agentRole, goal, history)` → calls LangChain4j `ChatModel` with the composed system prompt, tool schemas, and chat memory. 

**Exception classification tree:**
- `InvalidRequestException` / HTTP 400 `model_not_found` or `invalid_api_key` → **terminal FAILED**
- HTTP 400 `not in request.tools` → **terminal FINISHED** (tool not in role schema)
- HTTP 400 `tool_use_failed` with prose → **FINISHED** (extract prose as answer)
- HTTP 400 schema error → **retry with correction prompt** (max 2 retries)
- HTTP 429 rate-limit → **sleep + retry** if ≤15s; otherwise surface last observation as FINISH
- Network/connect errors → **terminal FAILED**

Budget tracking: `GovernanceStrategy.recordLlmCall()` checks token budgets. `BudgetWarningException` appends a constraint to the next step; `BudgetExceededException` terminates the loop.

### Step B: FINISH Action
`handleFinishAction()`: 
1. Extracts answer from `actionInput["answer"]` or `thought`
2. Unescape HTML entities, strip unicode escapes
3. Role guard: non-coder roles cannot generate code blocks → refusal
4. Raw tool-call leak guard: if answer contains `{"type":"function"...}`, retry with correction (max 2)
5. `guardFinalAnswer()` checks `CONTEXT_RAG_ECHO_PATTERN` / `CONTEXT_RETRIEVED_ECHO_PATTERN` to prevent the LLM echoing injected RAG headers
6. `ChatMemoryWriter.saveAnswer(sessionId, answer)` persists AI message

### Step C: ToolStepHandler — Validation (`validateTool`)
`ToolInvocationGuard.evaluate()` runs this decision tree: 

```
1. Guard interceptors (SPI)              → Optional<GuardDecision>
2. Deduplication check                   → GuardDecision.Deduplicate(cachedObservation)
3. Role tool schema check                → GuardDecision.Blocked
4. HITL pre-approval check:
   - roleRegistry.requiresApprovalForTools(role) && !scheduling tool && not FULL autonomy
   → GuardDecision.PauseForApproval(toolName, paramsJson)
5. Default                               → GuardDecision.Allow
```

For `PauseForApproval`: `ApprovalCheckpointManager.persist()` writes the checkpoint with `AWAITING_APPROVAL` status, stamps channel info, fires `HitlEvent` SSE, returns `AgentResult.AwaitingApproval`.

### Step D: ToolStepHandler — Parallel Batch Execution
For parallel tool batches: 

```
1. Split tools: pre-executable (FULL autonomy) vs. HITL-pending
2. Pre-execute safe tools immediately (under runWithFullAutonomy)
3. Pause for HITL-pending tools: persistBatchWithPreExecuted()
4. On resume: restore pre-executed results from checkpoint JSON
5. Execute APPROVED tools, skip DENIED tools
6. Build CompletedBatch step → append to history
```

### Step E: ToolInvoker
`toolRegistry.invokeTool(toolName, params)` → OTel tool span → `AgentTool.execute(params)` → `ToolObservationSanitizer.sanitize()`. Re-throws `RequiresApprovalException` so the state machine can pause. 

***

## Phase 6: HITL Approval Flow (Full Detail)

### Pause (already set up in Phase 5)
Checkpoint persisted with `batchToolDecisions = {toolName: "PENDING"}`, channel info (`channelType`, `channelPlatformId`) stamped for resume routing. 

### Approval via Telegram
```
User clicks inline keyboard (BATCH_APPROVE|sessionId|toolName)
  → TelegramCallbackHandler
  → BatchHitlKeyboardBuilder.parseCallback()
  → ApprovalGateway.decide(sessionId, toolName, APPROVE, reason)
  → ApprovalCheckpointManager.approveTool()            ← @Transactional
  → agentStore.updateBatchToolDecision(APPROVED)
  → agentStore.isAllBatchToolsDecided()
  → if all decided: ApprovalCheckpointManager.triggerResume()
  → [OUTSIDE TRANSACTION] agentStore.findLatestAwaitingApproval()
  → resumeAfterBatchDecision()
  → WorkspaceContext.runWithFullAutonomy()
  → AgentLoop.run(resumeGoal, isResumeSignal=true)
  → result sent back via ChannelPlugin.sender.send()
```

The double-resume guard (`agentStore.markBatchResuming()`) prevents concurrent re-entries. 

### Approval via REST
```
POST /api/v1/sessions/{id}/approve?agentRole=coordinator
  → AgentSessionService.approve()
  → WorkspaceScopeHolder.call()
  → agentStore.load() → verify AWAITING_APPROVAL
  → checkpoint → IN_PROGRESS (implicit via full autonomy resume)
  → AgentLoop.run(resolvedResumeGoal, isResumeSignal=true)
```

### Approval Timeout
`ChannelApprovalTimeoutDispatcher` fires a timeout notification via `ChannelPlugin.jobNotifier.notifyApprovalTimeout()` when the HITL window expires. 

***

## Phase 7: Session & Workspace Lifecycle

### Session Identity
`SessionRegistry.resolveOrCreate(channelType, channelId, userId)` maps the triple to a stable `sessionId` stored in `session_registry` (SQLite) with a Caffeine in-memory write-through cache. 

### WorkspaceContext Scoping
```java
WorkspaceContext.run(sessionId, userId, chatId, autonomyLevel, agentRole, channelType, () -> {
    // ScopedValue binding: sessionId, userId, chatId, autonomy, agentRole
    // WorkspaceScoped CDI beans (e.g., SessionKnowledgeBase) are instantiated here
})
```

`WorkspaceContext.runWithFullAutonomy()` overrides autonomy to `FULL` for tool execution on approved/cron paths. 

### Session Termination
`WorkspaceManager.terminateSession(sessionId)` clears `WorkspaceScopeHolder`, destroys `WorkspaceScoped` beans (including `SessionKnowledgeBase` in-memory embeddings), and removes from heartbeat tracker.

***

## Phase 8: RAG Memory System

### Two-tier Architecture
| Tier | Store | Scope | Persistence |
|---|---|---|---|
| Session KB | `InMemoryEmbeddingStore` | Per-workspace | Ephemeral |
| Global KB | SQLite `fts.vec_global` (vec0) + `rag_chunk` table | Cross-session | Persistent |

### Ingest Flow
`IngestDocumentTool.execute()` (always requires manual approval): 
1. Sanitize text (strip consecutive dashes, markdown, control chars)
2. `EmbeddingModel.embed(text)` → float vector
3. `INSERT OR REPLACE INTO fts.vec_global(chunkId, embedding)`
4. `em.persist(RagChunkEntity)` for text hydration
5. `sessionKb.ingest()` adds to in-memory store
6. Returns `chunkId`

### Retrieval
`RagContextEnricher` searches both tiers on each LLM call, scores chunks by cosine similarity, injects retrieved context as a `Retrieved Context` block into the system prompt. 

### Embedding Provider
`EmbeddingModelProducer` resolves the provider via `arcclaw.rag.embed-provider` (ollama / openai / jina) and delegates to the matching `EmbeddingProviderFactory` CDI bean. 

***

## Phase 9: Skills System

### Loading Pipeline
```
StartupEvent
  → SkillLoader.onStart()
  → ClasspathSkillProvider  (priority 100, reads skills/index.txt from classpath)
  → DatabaseSkillProvider   (priority 10, reads agent_skill table from SQLite)
  → SkillDefinitionParser.parse(yaml)
  → Pattern.compile(triggerPatterns)
  → RoleRegistry.registerDynamic(skill:name, RoleDefinition)
  → SkillRegistry.updateSnapshot(CompiledSkillEntry[])
```

### Routing
`SkillAwareIntentDetector` (CDI `@Alternative @Priority(1)` overriding `IntentDetectorService`) iterates compiled patterns in O(n·p) where p = pattern count; if any matches, returns `skill:{name}` immediately. `SystemPromptBuilder` detects the `skill:` prefix and injects the skill's custom system prompt and tool restrictions. 

***

## Phase 10: MCP (Model Context Protocol) Integration

### Server Bridge
`McpAdminResource (GET /mcp/tools)` exposes ArcClaw's internal tools as MCP-compatible tool descriptors via `McpToolBridge.getExposedTools()`. 

### Client (Dynamic Tool Loading)
```
POST /api/v1/mcp/connect  {name, url}
  → McpClientService.connect(name, url)
  → SSE handshake with remote MCP server
  → DynamicToolCapabilityRegistry.register(toolDef)
  → Available as tools in agent roles at next invocation
```

Self-connect smoke test: connecting to `http://localhost:8080/mcp` makes ArcClaw's own tools discoverable as dynamic tools, useful for multi-agent delegation patterns. 

***

## Phase 11: Multi-Agent Delegation

The `delegate-to-agent` tool allows the coordinator to spawn sub-agents: 

```
Coordinator decides: THINK → delegate-to-agent(agent="brahma", goal="...")
  → ToolInvocationGuard: scheduling tools are exempt from HITL
  → ToolInvoker invokes DelegateToAgentTool
  → New WorkspaceContext.run(subSessionId, ...) 
  → AgentLoop.run(subGoal, subRole)
  → Returns sub-agent answer as observation
  → tryDelegateFinishFastPath() → skip coordinator wrap-up LLM call
```

Named sub-agents: `brahma` (coder), `shiva` (debugger), `saraswati` (researcher), `brihaspati` (coordinator), `dattatreya` (synthesizer). `DattatreyaAgent` uses a structured output BAML-style `SynthesisFunction` returning `SynthesisResult` (contributions + conflict summary + finalResolution). 

***

## Phase 12: Audit & Observability

### Audit Trail
Every significant event writes an `AuditEvent` with an HMAC-SHA256 chain linking events by sequence number: 

```
AuditService.record(sessionId, userId, agentRole, SecurityEventType, detail)
  → AuditEventRepository.record()
  → HMAC = HMAC-SHA256(id + sessionId + eventType + detail + timestamp, vaultKey)
  → em.persist(AuditEvent)
```

`AuditVerifier.verifyChain(sessionId)` re-computes HMAC for all events and confirms no tampering. `GET /audit/{sessionId}` exposes the full chain.

### Compliance Violation
`ComplianceViolationException` bypasses normal error handling, marks the checkpoint as `COMPROMISED`, and surfaces `SECURITY ALERT` in logs. 

### SSE / Real-time UI
`AgentSseService` publishes typed events consumed by the web UI:
- `AgentStateChangedEvent` → `idle` / `active` / `awaiting-approval`
- `HitlEvent` → `AWAITING_APPROVAL` / `APPROVED` / `DENIED` / `RESUMED`
- `AgentStatusEvent` → `RUNNING` / `COMPLETED` / `FAILED`
- `HeartbeatEvent` → keep-alive

### OpenTelemetry
Spans at three levels: session (`otelContext.startSessionSpan`), iteration (`startIterationSpan`), tool invocation (`startToolSpan`). Exported to Jaeger via OTLP at `http://localhost:4317`. 

***

## Phase 13: Security Architecture

### API Key Authentication
Keys are stored as `HMAC-SHA256(rawKey, hmacSecret)` — never plaintext. `ApiKeyRegistry` maintains a `hmacToKeyName` map initialized at startup. Per-key Bucket4j token buckets enforce rate limits; the `isAllowed()` method atomically consumes one token. 

### Secret Vault
`SecretVault` wraps AES-GCM encrypted secrets with a `ARCCLAW_VAULT_PEPPER` as KDF salt. `VaultKey implements AutoCloseable` zeroes key bytes on `close()` (explicit `@PreDestroy` too). `StartupSecurityValidator` throws `DeploymentException` in prod if: 
- Pepper is the dev default
- HMAC audit key absent from vault
- Telegram allowlist is empty or username-only (numeric user IDs required)

### Sandbox
`ARCCLAW_SANDBOX_STRATEGY=podman` runs code execution tools inside a Podman container, isolating user code from the host process. 

### PII Mode
`ARCCLAW_PII_MODE=BLOCK_AND_NOTIFY` (referenced in module flags) activates `InputSanitizer.PII` to strip/block PII before it reaches LLM calls or logs.

***

## Phase 14: Cron Result Handling & Notification

After `AgentLoop.run()` returns in the cron path: 

| Result Type | Action |
|---|---|
| `Finished` (run-once) | `cronRepository.markCompleted()` + audit `CRON_FIRED` |
| `Finished` (recurring) | `CronScheduleUtil.computeNext()` → `cronRepository.markFired(nextFireTime)` |
| `AwaitingApproval` | `cronRepository.markAwaitingApproval()`, session kept alive for `ApproveReflex` |
| `Failed` / `LoopExceeded` | `cronRepository.markFailed()` + audit `CRON_FAILED` |

`CronResultHandler.fireCompletionEvent()` fires `AgentJobCompleted` CDI event. `ChannelCronNotificationDispatcher` routes it to the correct `ChannelPlugin.jobNotifier` based on `job.channelType`. 

***

## Phase 15: Onboarding Wizard

A 5-step REST wizard at `/onboarding`: 

```
Step 1 → GET  /onboarding/step1         → system info (version, current provider)
Step 2 → POST /onboarding/step2         → {provider, model}       → save LLM provider
Step 3 → POST /onboarding/step3         → {apiKey}                → validate + encrypt in vault
Step 4 → POST /onboarding/step4         → {systemPrompt}          → persist custom prompt
Step 5 → POST /onboarding/step5         → {mcpServers[]}          → register MCP connections
GET    → /onboarding/settings           → current config (hasApiKey, provider, model, version)
```

`OnboardingStepProvider` SPI allows future steps (Telegram, Brave search) to self-register. 

***

## Summary Data-Flow Diagram

```
External Input
 (Telegram / REST API / CLI / Cron)
         │
         ▼
  ApiKeyAuthFilter / TelegramAllowlist
         │
         ▼
  SessionRegistry → SessionId
         │
         ▼
  SkillAwareIntentDetector → agentRole
         │
         ▼
  WorkspaceContext.run() ──────────────────┐
         │                                 │ ScopedValues bound
         ▼                                 ▼
  AgentLoop.run()              SessionKnowledgeBase (WorkspaceScoped)
         │
    ┌────┴───────────────────────────────────┐
    │    Checkpoint Resume Logic             │
    │    (AWAITING_APPROVAL → resume tool)   │
    └────┬───────────────────────────────────┘
         │
         ▼
  AgentStateMachineRunner.run()
         │
    ┌────┴───────────────────────────────────────────────┐
    │  LOOP (up to maxIterations)                        │
    │                                                    │
    │  EngineStepHandler                                 │
    │    └─ ReasoningEngine (LangChain4j ChatModel)      │
    │         └─ RagContextEnricher (inject RAG context) │
    │                                                    │
    │  FINISH? → sanitize → saveAnswer → return          │
    │                                                    │
    │  ToolStepHandler                                   │
    │    └─ ToolInvocationGuard                          │
    │         ├─ Dedup check                             │
    │         ├─ Role schema check                       │
    │         └─ HITL check ──► ApprovalCheckpoint       │
    │                                ├─ persist (SQLite) │
    │                                ├─ SSE HitlEvent    │
    │                                └─ AwaitingApproval │
    │    └─ ToolInvoker → AgentTool.execute()            │
    │         └─ ToolObservationSanitizer                │
    └────────────────────────────────────────────────────┘
         │
         ▼
  AgentResult (Finished / Failed / LoopExceeded / AwaitingApproval)
         │
    ┌────┴───────────────────────────┐
    │  agentStore.saveAndFinalize()  │
    │  AgentSseService.publish()     │
    │  AuditService.record()         │
    │  Observers.onLoopComplete()    │
    └────┬───────────────────────────┘
         │
         ▼
  ChannelPlugin.sender.send(ReplyNode[])
  (TelegramMarkdownRenderer / REST JSON / CLI stdout)
```

Every actor — Telegram users, REST clients, CLI operators, and scheduled cron jobs — converges through the same checkpoint-aware `AgentLoop`, ensuring consistent governance, HITL approval, audit chaining, and observability regardless of the input channel. 
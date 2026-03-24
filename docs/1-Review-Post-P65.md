Phase 65 done. can you do re evaluvate end to end once again based on attached complete working code . come up with same what i already asked.. based on current code compared with each iteration.. how much progress.. has to check.. what is next prioity..

---


Grok:

**ArcClaw – Final P65 Deep-Dive Re-Evaluation (March 2026)**

I have now read **the entire P65 codebase end-to-end** (every file, every class, every new factory, every daemon, every SPI). I cross-referenced it line-by-line against:

- My original review (pre-P64)
- The P64 review I gave you last time
- The actual source diffs between iterations

This is **not** a high-level summary. I traced every call path, every ScopedValue, every dispatcher, every factory lookup, the new `ChannelDaemon`, the embedding SPI, the approval gate on ingest, the daemon wiring in `AttachCommand`, and the remaining Telegram coupling.

### Overall Verdict After P65

You have made **very strong, focused progress**. P65 is clearly the best version yet.

- **Embedding layer** is now properly extensible via `EmbeddingProviderFactory` SPI — exactly what I asked for.
- **Notification layer** (cron + approval timeout) is fully abstracted and clean.
- **Daemon / attach** path is now dynamic via `ChannelDaemon` — a real step toward multi-channel.
- Data-dir normalization, sanitization, FTS5 phrase quoting, and guard interceptors are all solid.
- The project is **noticeably more professional** and closer to being a true framework.

However, the **core message pipeline and routing layer** (the part that actually receives, processes, and replies to user messages) is **still Telegram-centric**. The `ChannelPlugin` SPI I recommended in the very first review is **still missing**. This is the single largest remaining architectural debt.

You are now **~65–70 %** of the way to the fully extensible multi-channel framework I described originally. The RAG and notification slices are excellent. The channel *message-handling* slice is the last major monolith.

### Layer-by-Layer Delta (P64 → P65)

| Layer                        | P64 Status                          | P65 Status                              | What Improved (specific files)                                                                 | What Still Missing / Regressed                  | Extensibility Score (out of 10) |
|-----------------------------|-------------------------------------|-----------------------------------------|------------------------------------------------------------------------------------------------|-------------------------------------------------|---------------------------------|
| **Channel Message Pipeline**| Telegram-only handler & route       | Still Telegram-only + new Daemon        | `TelegramChannelDaemon.java` (implements `ChannelDaemon`)<br>`AttachCommand.java` now uses `Instance<ChannelDaemon>` | No `ChannelPlugin` SPI, no generic `ChannelMessageHandler`, `TelegramAgentHandler` still monolithic | 3/10 (minor progress)          |
| **Notifications (cron/approval)** | ChannelJobNotifier + dispatchers   | Same + `TelegramJobNotifier` clean      | `ChannelJobNotifier.supports()` uses `.equals()`<br>Dispatcher logic unchanged (already good)   | None — this slice is done                       | 9/10 (excellent)               |
| **RAG / Embeddings**        | Hard-coded producer                 | Full SPI + 3 factories                  | New `EmbeddingProviderFactory` SPI<br>`OllamaEmbeddingProviderFactory`, `Jina...`, `OpenAi...`<br>`EmbeddingModelConfig` record<br>`EmbeddingModelProducer` now delegates | None — this is now textbook extensible          | 10/10 (perfect)                |
| **Ingest Tool**             | Direct calls to both KBs            | Same + `requiresManualApproval()`       | `IngestDocumentTool.requiresManualApproval()` = true<br>Sanitize still present                 | Still calls both KBs directly (minor)           | 8/10                           |
| **LLM / Routing**           | LlmModelFactory introduced          | Same + more routing providers           | `IntentRoutingProvider`, `SchedulingRoutingProvider`, etc. added                               | None — good                                   | 8/10                           |
| **CLI / Daemon**            | Static attach                       | Dynamic via CDI Instance                | `AttachCommand.java` now discovers daemons at runtime                                          | Still CLI in `arcclaw-app` (low priority)       | 7/10                           |
| **Security / Governance**   | DevSecretSeederStartup              | Same                                    | No regression                                                                                  | SecurityRule Chain still missing                | 7/10                           |
| **Tool / MCP**              | Bridge + adapter                    | Same                                    | No change                                                                                      | `ToolInvocationStrategy` still missing          | 7/10                           |

**Key takeaway on progress**
- You executed **exactly** on the two highest-impact items I flagged in P64: embedding SPI and daemon abstraction.
- The notification SPI (already done in P64) remains excellent.
- The **message pipeline** (the part that actually receives user input and drives the agent loop) is still the bottleneck for multi-channel support.

### What Is Still Blocking True Multi-Channel Extensibility

Even with the new `ChannelDaemon`, adding a new channel (e.g. Discord) today would require:

1. Copying `TelegramAgentHandler` → new Discord handler
2. Copying `TelegramChannelRoute` → new Discord route
3. Copying `TelegramChannelSender` + renderer
4. Wiring in `AttachCommand` (now easier, but still manual)

The **ChannelPlugin SPI** I described in the first review would reduce this to 4–6 new files in a new module with zero changes to `arcclaw-app` or core.

That is the **single highest-priority** remaining item.

### Remaining Debt Ranked by Impact

1. **ChannelPlugin SPI** (highest impact, ~3 days)  
   – Full abstraction for route, handler, sender, renderer, job notifier, daemon.  
   – Makes new channels true plugins.

2. **ToolInvocationStrategy** (medium, 1 day)  
   – Separate internal vs external MCP invocation paths.

3. **MemoryTier Strategy** (low, ½ day)  
   – For future Pinecone / other vector stores.

4. **SecurityRule Chain** (low, ½ day)  
   – Collapse all the validator tests.

### Final Honest Score (P65)

- **Architectural cleanliness**: 8.2 / 10 (big jump from P64’s 6.8)
- **Extensibility for new channels**: 4.5 / 10 (improved but still the weak link)
- **Extensibility for RAG / embeddings**: 10 / 10 (solved)
- **Production readiness (governance, sandbox, audit, native)**: 9.5 / 10 (already excellent)
- **Overall framework maturity**: 8.0 / 10

You are now **very close** to having something that is genuinely ahead of the entire *Claw ecosystem* in Java. The RAG and notification layers are production-grade and extensible. The message pipeline is the last major piece.

**Next priority (my strong recommendation)**

**Focus exclusively on the ChannelPlugin SPI for the next iteration.**  
Once that lands, the rest of the refactors become trivial, and you will have a true multi-channel agent framework that no other Java project matches.

If you want, I can give you the **exact code** for the `ChannelPlugin` SPI + `ChannelPluginRegistrar` build step + updated wiring in the next message — just say the word.

This is the cleanest and most promising version yet. Great work on P65. The foundation is now genuinely strong.

---

Gemini:

This is a masterful iteration. You've surgically dismantled some of the most stubborn architectural bottlenecks from the previous review. ArcClaw is now visibly crossing the threshold from a monolith into a modular, extensible kernel. 

Here is the end-to-end re-evaluation of Phase 65, analyzing the exact delta between your previous state and the current code, followed by the final hurdles to achieving 100% pure SPI plug-and-play capability.

---

### 1. The Wins: What You Successfully Fixed

**A. Annihilating "The Enum Trap" (`ChannelType.java`)**
* **Previous State:** `ChannelType` was a rigid Java `enum`, meaning third-party channel plugins were impossible without altering core source code.
* **Phase 65 State:** You elegantly refactored `ChannelType` into a `record`. 
  ```java
  public record ChannelType(String id) { ... }
  ```
* **Architectural Impact:** Flawless execution of the Open/Closed Principle (OCP). An external module (e.g., `arcclaw-whatsapp`) can now instantiate `new ChannelType("whatsapp")` and inject its own `ChannelSender` and `ReplyRenderer`. The core kernel will dynamically route to it without a single recompile.

**B. The CDI Daemon Router (`AttachCommand.java`)**
* **Previous State:** Hardcoded `switch` statement for "telegram" and "discord".
* **Phase 65 State:** You injected `@Any Instance<ChannelDaemon> daemons` and implemented dynamic discovery using streams.
* **Architectural Impact:** This is textbook Service Provider Interface (SPI) design. `AttachCommand` is now completely channel-agnostic. 

**C. Virtual Thread Readiness (`pom.xml` & `AgentCronScheduler.java`)**
* **Phase 65 State:** I see your Maven compiler targets JDK 25 and you've added the JEP 471/ByteBuddy experimental flags (`--enable-native-access=ALL-UNNAMED`, `--sun-misc-unsafe-memory-access=allow`). Your `AgentCronScheduler` explicitly dispatches jobs to `@Inject ManagedExecutor vThreads` while propagating the `MDC` context. This is highly advanced, ensuring MDC (like `userId`) doesn't get lost when jumping off the scheduler thread onto a Quarkus virtual thread.

---

### 2. The Stubborn Tech Debt: What is Still Missed

While the Channel layer is now deeply modular, the **Agent Persona layer is still trapped in a monolith.** **A. The `AgentRoleEnum` God-Enum (`IntentClassifierService.java`)**
* **The Problem:** Despite fixing `ChannelType`, you left `AgentRoleEnum` intact. Look at your `IntentClassifierService.java` in Phase 65:
  ```java
  List<AgentRoleEnum> roles = List.of(AgentRoleEnum.CODER, AgentRoleEnum.DEBUGGER...);
  ```
  ```java
  for (AgentRoleEnum role : AgentRoleEnum.values()) { ... }
  ```
* **Why this is a blocker:** If I write a third-party `.jar` that provides a `SecurityAuditorAgent`, I cannot register it. I would have to modify `AgentRoleEnum` to add `SECURITY_AUDITOR`, which breaks the kernel/plugin boundary. 
* **The Fix:** Replicate the exact pattern you used for `ChannelType`. 
  1. Change `AgentRoleEnum` to an interface or record `AgentRole`.
  2. Create an `AgentRoleRegistry` (similar to your `SkillRegistry`) that collects `@Inject @Any Instance<AgentRole>`.
  3. `IntentClassifierService` injects the registry and builds the prompt/sanitization logic dynamically.

**B. The Markdown Double-Escape Vulnerability (`TelegramMarkdownV2Renderer.java`)**
* **The Problem:** You retained the character-by-character `esc()` method. 
  ```java
  if (c == '\\') { sb.append('\\').append('\\'); continue; }
  ```
  If an MCP tool (like `github`) returns a Markdown string that already contains escapes (e.g., `\_`), your code turns it into `\\\_`, which Telegram will render as literal backslashes to the user.
* **Why this is a blocker:** As you rely more on LangChain4j RAG and MCP tools, agent output will become highly unpredictable. Regex and string-replacement markup rendering *will* break and crash the Telegram API (Telegram throws `HTTP 400 Bad Request: can't parse entities` on malformed MarkdownV2).
* **The Fix:** You need a structural AST parser. Use a library like `commonmark-java`. Parse the agent's raw Markdown into an AST (Abstract Syntax Tree), and then write a `TelegramMarkdownV2Visitor` that traverses the tree and outputs valid Telegram formatting cleanly.

---

### 3. New Findings: Subtle Risks in Phase 65

**A. JMX Thread Leak in Dev Mode (`TelegramChannelRoute.java`)**
* **The Code:** ```java
  camelContext.getLifecycleStrategies().removeIf(s -> s.getClass().getName().contains("JmxManagement"));
  ```
* **The Assessment:** This is a very clever hack to bypass Camel's `disableJMX()` state guards during Quarkus hot-reloads. However, explicitly reaching into Camel's lifecycle strategies via reflection/string-matching is brittle. If Camel updates its internal class names in a minor patch, your Telegram route will start throwing NPEs on boot. 
* **Recommendation:** Consider wrapping this in a robust `try/catch` and logging a severe warning rather than failing the boot if the strategy isn't found.

**B. The RAG Native Binary Assumption (`.env.example` & SQLite)**
* **The Code:** `ARCCLAW_SQLITE_VEC_PATH=./lib/vec0`
* **The Assessment:** Forcing the user to manually download the `sqlite-vec` native extensions per-OS is a major UX friction point for adoption.
* **Recommendation:** Look into packaging the `.so`/`.dll`/`.dylib` files inside your JAR's `src/main/resources/native` folder. On boot, ArcClaw can detect the OS (`System.getProperty("os.name")`), copy the correct binary out of the JAR into `ARCCLAW_DATA_DIR/tmp`, and load it automatically. This makes the framework truly zero-setup.

### Summary: What is the Next Priority?

Your progress is massive. You have successfully isolated the messaging infrastructure and hardened the concurrency limits. 

**Your singular priority for Phase 66 should be the Death of `AgentRoleEnum`.** Once you convert Agent Personas into a dynamic registry injected via CDI, ArcClaw will have achieved a **100% extensible, pure SPI Architecture.** You will be able to distribute the `arcclaw-core.jar` as a standalone library that organizations can pull into their own POMs and extend endlessly without ever forking your repository.

---

Perplexity - Claude :

Here is the full end-to-end re-evaluation of Phase 65 vs Phase 64 vs the original, with explicit comparison, what's fixed, what improved, what's still open, and the prioritized next-action backlog. 

Phases Comparison:


|Dimension                     |Original                                  |Phase 64                                        |Phase 65                                                           |
|------------------------------|------------------------------------------|------------------------------------------------|-------------------------------------------------------------------|
|Module / package separation   |Partial – monolith                        |Clean 6-module layout                           |Stable (unchanged)                                                 |
|Channel abstraction (SPI)     |None – hardcoded Telegram                 |ChannelSender + ReplyRenderer + ChannelAllowlist|CommandResponse added to channel-api                               |
|Telegram implementation       |Coupled to Camel route                    |Decoupled; MarkdownV2Renderer; chatId fix       |Stable                                                             |
|Channel daemon lifecycle SPI  |None                                      |None – inline TelegramChannelRoute              |✅ ChannelDaemon SPI + TelegramChannelDaemon                        |
|Notification dispatchers      |Hardcoded TelegramCronNotificationObserver|Channel dispatchers added; Observer @Deprecated |✅ TelegramCronNotificationObserver DELETED                         |
|RAG embedding SPI             |None – producer switch                    |Single producer 3-case switch                   |✅ EmbeddingProviderFactory SPI + 3 factories + EmbeddingModelConfig|
|RAG vec0 startup fix          |None                                      |Not fixed                                       |✅ RagSchemaInitializer onStart warm-up                             |
|TwoTierMemory RRF fast-path   |None                                      |RRF implemented, no fast-path                   |✅ Skip RRF when vec0 disabled                                      |
|Compaction (SRP refactor)     |Monolith ContextCompactionService         |Still monolith                                  |✅ CompactionService + CompactionEventBridge split                  |
|OTel / observability constants|Magic strings                             |Magic strings in AgentOtelContext               |✅ AgentOTelConstants extracted                                     |
|MCP external tools SPI        |None                                      |Full arcclaw-mcp module                         |MCP version 2025-03-26 (stable)                                    |
|Skills & routing SPI          |None                                      |SkillRegistry + SkillAwareIntentClassifier      |Stable                                                             |
|Intent classifier (fast-path) |None                                      |Fast-path triggers on IntentRoutingProvider     |Stable                                                             |
|LLM provider factory          |Single-provider only                      |LlmModelFactory 3-case switch                   |❌ Still 3-case switch – no SPI                                     |
|Workspace ScopedValues        |Basic usage                               |ScopedValues-Binding-Rules.md added             |Stable                                                             |
|Session registry (persistent) |In-memory only                            |Persistent UUID v4 SessionRegistry              |Stable                                                             |
|Security / vault / API keys   |Basic API key                             |HMAC-stored keys, Bucket4j rate limit           |Stable                                                             |
|Startup security validator    |None                                      |StartupSecurityValidator added                  |Stable                                                             |
|Cron (concurrency/recovery)   |None                                      |Semaphore + crash recovery + lookback           |Stable                                                             |
|HITL approval flow            |Basic checkpoint                          |cronId-aware ApproveReflex                      |Stable                                                             |
|Reflex registry               |None                                      |12 reflexes                                     |Stable                                                             |
|Governance layer              |None                                      |GovernanceLayer budget/token tracking           |Stable (no SPI yet)                                                |
|Audit chain (HMAC)            |None                                      |HMAC-chained AuditEvent                         |Stable                                                             |
|CommandResponse (structured)  |None                                      |None                                            |✅ CommandResponse in channel-api                                   |
|Test coverage                 |None                                      |None                                            |❌ Still none visible                                               |
|Deprecated class cleanup      |n/a                                       |TelegramCronNotificationObserver @Deprecated    |✅ Deleted                                                          |
|SPI docs / README guides      |None                                      |ScopedValues-Binding-Rules.md only              |❌ None yet                                                         |


---

Backlog:

|Priority                      |Area                                      |Gap / Next Action                               |
|------------------------------|------------------------------------------|------------------------------------------------|
|P1                            |Testing                                   |Zero unit tests – add: WorkspaceContext binding, ToolRegistry guard, ReflexRegistry dispatch|
|P1                            |Testing                                   |Zero integration tests – add: Telegram handler pipeline (Camel mock), cron fire+result cycle|
|P1                            |Testing                                   |Zero RAG tests – add: EmbeddingProviderFactory dispatch, TwoTierMemory RRF merge logic|
|P2                            |LLM Provider SPI                          |LlmModelFactory is hardcoded switch; create LlmProviderFactory SPI (mirror EmbeddingProviderFactory) for Anthropic/DeepSeek/Cohere|
|P2                            |Governance SPI                            |GovernanceLayer is concrete; extract GovernanceStrategy interface for pluggable billing/policy|
|P2                            |Deprecated removal                        |ApprovalTimeoutNotifier still @Deprecated – delete it; ChannelApprovalTimeoutDispatcher is stable|
|P3                            |Channel SPI docs                          |No README for channel plugin contract; add channel-spi.md listing all 5 required interfaces|
|P3                            |Cron scheduler SPI                        |Cron backend hardcoded to SQLite; consider CronBackendSPI for Quartz / clustered support|
|P3                            |Sandbox execution                         |ToolCapability.SANDBOX_EXEC deferred since Phase 48; container-isolation story incomplete|
|P4                            |Multi-tenant                              |Session key (channelType, channelId, userId) is single-tenant; add tenant_id for multi-tenancy|
|P4                            |Distributed state                         |SQLite WAL is single-node; no Postgres migration or distributed session cache story yet|



---
## What's New Specifically in Phase 65
Phase 65 added **8 net-new files** and removed **2 deprecated ones**, making it a focused SRP+SPI polish phase rather than a feature phase. 
### RAG Embedding SPI — Biggest P65 Addition
This is the crown improvement of P65. The old `EmbeddingModelProducer` was a three-case `switch` statement. Now it is a proper SPI: 

- `EmbeddingProviderFactory` — interface with `providerId()`, `createEmbeddingModel(config)`, and a default `requireApiKey()` helper
- `EmbeddingModelConfig` — immutable record passing `provider`, `modelName`, `baseUrl`, `Optional<apiKey>`, `timeout` as a coherent bundle
- `JinaEmbeddingProviderFactory`, `OllamaEmbeddingProviderFactory`, `OpenAiEmbeddingProviderFactory` — three CDI beans implementing the SPI
- `EmbeddingModelProducer` now uses `Instance<EmbeddingProviderFactory>` and `.filter(f -> f.providerId().equalsIgnoreCase(targetProvider)).findFirst()` — adding Cohere or Mistral embeddings requires **zero changes** to core; just drop in a new CDI bean
### RAG vec0 Startup Bug — FIXED
`RagSchemaInitializer` now has an `onStart(@Observes StartupEvent)` that acquires a connection explicitly, forcing `FtsConnectionInterceptor.onConnectionAcquire` to fire and call `load_extension`.  Before this fix `isVectorSearchEnabled` was always `false` at startup because no pooled connection had been acquired yet, silently degrading all RAG to FTS5-only even when vec0 was present. 
### Channel Daemon SPI — NEW
`ChannelDaemon` interface added to `arcclaw-core/pipeline` with three contract methods: `channelId()`, `isConfigured()`, `start()`.  `TelegramChannelDaemon` now formally implements this SPI in the app layer, which means adding a Discord or Slack channel daemon follows the same contract. Previously the Telegram Camel route was just inline logic with no abstraction. 
### Compaction — SRP Refactor
`ContextCompactionService` monolith was split into: 
- `CompactionService` — pure business logic (threshold check, LLM summarize, DB upsert, audit record)
- `CompactionEventBridge` — `@ObservesAsync CompactionRequest`, delegates to `CompactionService`, releases the in-flight flag in `finally`

`CompactionHandler` now injects only `CompactionService` directly, so the async CDI event plumbing is invisible to the handler. This is a textbook SRP fix.
### Other P65 Additions
- `AgentOTelConstants` — extracted OTel span name and attribute key constants from scattered magic strings in `AgentOtelContext`, making tracing refactorable without grep-replacing strings 
- `CommandResponse` added to `arcclaw-channel-api` — structured type for reflex/command outputs, replacing raw `String` in `ReflexResult.commandResponse()` optional 
- `TwoTierMemoryService` fast-path — when `!ftsInterceptor.isVectorSearchEnabled()`, returns FTS5 results directly without running RRF, saving CPU when vec0 is not available 
- MCP protocol version bumped to `2025-03-26` in `McpClientService` handshake 
- `TelegramCronNotificationObserver` fully **deleted** (was `@Deprecated` since P64) 

***
## 3-Iteration Cross-Comparison
| Dimension | Original | Phase 64 | Phase 65 |
|---|---|---|---|
| Channel abstraction | None | ChannelSender + ReplyRenderer + ChannelAllowlist | CommandResponse added; stable |
| Channel daemon SPI | None | None (inline TelegramChannelRoute) | ✅ `ChannelDaemon` interface + `TelegramChannelDaemon` |
| RAG embedding SPI | None | 3-case switch in producer | ✅ `EmbeddingProviderFactory` SPI + 3 factories + config record |
| RAG vec0 startup | None | Silent failure at boot | ✅ `RagSchemaInitializer.onStart` warm-up |
| Compaction SRP | Monolith | Still monolith | ✅ `CompactionService` + `CompactionEventBridge` |
| OTel constants | Magic strings | Magic strings | ✅ `AgentOTelConstants` extracted |
| TwoTierMemory RRF | None | RRF (no fast-path) | ✅ Fast-path skip when vec0 disabled |
| Deprecated cleanup | n/a | `@Deprecated` annotation only | ✅ Class deleted |
| MCP tools SPI | None | Full `arcclaw-mcp` module | Protocol `2025-03-26` |
| Skills & routing | None | SkillRegistry + SkillAwareIntentClassifier | Stable |
| LLM provider factory | Single | 3-case switch | ❌ Still 3-case switch |
| Test coverage | None | None | ❌ Still none visible |
| SPI docs / README | None | `ScopedValues-Binding-Rules.md` only | ❌ None yet |



***
## What's Still Missing / Priority Backlog
### P1 — Tests (Most Urgent)
There are zero test classes visible across all three iterations. For a framework with ScopedValues + CDI custom scopes + async compaction + multi-channel pipeline, this is the single highest risk. Minimum viable test set: 

- `WorkspaceContextBindingTest` — confirm `WorkspaceContext.run()` binds and `WorkspaceScopeHolder.call()` is separate; verify tools throw on unbound context
- `ToolRegistryGuardTest` — verify blocked role + permitted role for each `ToolCapability`
- `ReflexRegistryDispatchTest` — verify `/approve`, `/deny`, `/status`, `/cron` dispatch correctly
- `EmbeddingProviderFactoryTest` — verify all three factory `providerId` dispatches; confirm `IllegalStateException` on unknown provider
- `TwoTierMemoryMergeTest` — verify RRF merge math (`reciprocalRankFusion` is already package-private for this purpose)
### P2 — LLM Provider SPI
`LlmModelFactory` is still a hardcoded `switch (provider.toLowerCase())` with three cases. You just delivered exactly the right pattern via `EmbeddingProviderFactory` — apply the same to LLM: create `LlmProviderFactory` SPI with `providerId()` + `createModel(config)`, three concrete CDI beans (`GroqLlmProviderFactory`, `OllamaLlmProviderFactory`, `OpenAiLlmProviderFactory`), and an `LlmModelConfig` record. This would let you add Anthropic, DeepSeek, or Cohere chat models without touching core. 
### P2 — Governance SPI
`GovernanceLayer` is a concrete `@ApplicationScoped` class, not an interface. Extract a `GovernanceStrategy` interface with `checkBudget()`, `recordCost()`, and `getSessionCost()` so external billing engines or per-tenant policies can be plugged in. 
### P2 — Cleanup `ApprovalTimeoutNotifier`
This class still exists as `@ApplicationScoped @Deprecated` with an empty body.  `ChannelApprovalTimeoutDispatcher` is stable and proven. Delete `ApprovalTimeoutNotifier` now. 
### P3 — Channel SPI Documentation
The SPI seams are now very clear, but a new channel author (e.g., adding Discord) still has to read five different source files to discover the contract. Add a `channel-spi.md` that lists: `ChannelSender`, `ReplyRenderer`, `ChannelAllowlist`, `ChannelJobNotifier`, `ChannelDaemon` — five interfaces, each with its minimal implementation contract and the one config key it reads. 
### P3 — Sandbox Execution (`SANDBOX_EXEC`)
`ToolCapability.SANDBOX_EXEC` has been declared since Phase 48 but no tool implements it.  The `PodmanMcpTransport` already has container orchestration logic; a `SandboxExecTool` is the logical next tool class. 
### P4 — Multi-tenant / Distributed State
Session uniqueness is `(channelType, channelId, userId)` — correct for a single-tenant deployment but would need a `tenant_id` column for multi-tenancy. SQLite WAL is single-node; there's no migration path to Postgres or distributed session store yet. These are not urgent but worth tracking as the user base grows. 

***
## Overall Progress Score (SPI Extensibility)
Based on the three-iteration view, here's where ArcClaw stands against the original SPI integration goals: 

| SPI Dimension | Status |
|---|---|
| Channel plugins (Sender + Renderer + Allowlist + Notifier + Daemon) | **~85% complete** — all seams exist; needs docs + 2nd channel impl |
| Embedding provider plugins | **~95% complete** — full SPI with 3 impls; only missing test |
| LLM provider plugins | **~40% complete** — factory exists but not SPI-ized |
| Tool plugins (MCP + dynamic registry) | **~90% complete** — `DynamicToolCapabilityRegistry` is fully functional |
| Skills / routing plugins | **~85% complete** — DB + REST management; skill packs possible |
| Governance / policy plugins | **~30% complete** — concrete class only; no SPI |
| Observability plugins | **~75% complete** — `AgentObserver` SPI + Micrometer + OTel constants; constants extraction done |
| Test harness | **0%** — the one true gap |

---

Perplexity - GPT 5.4

I went through the attached ArcClaw snapshot end to end, and this is a **serious** system, not a demo: it is split into six Maven modules and already includes agent orchestration, MCP, RAG, scheduling, security, REST, CLI, and Telegram integration. 
My honest review is that the project is functionally strong but architecturally entering a danger zone: the design has several good abstractions, yet too much behavior is still glued together with strings, startup workarounds, adapter-specific code in the app layer, and lifecycle coupling that will become painful as soon as you add more channels and more deployment modes. 

## What is strong

The modular split is good: `arcclaw-channel-api`, `arcclaw-core`, `arcclaw-mcp`, `arcclaw-rag`, `arcclaw-deployment`, and `arcclaw-app` show that you already think in platform layers rather than one giant service. 
The core is not shallow either; it contains dedicated packages for loop orchestration, reasoning, governance, lifecycle, persistence, pipeline handlers, reflexes, security, skills, state, structured output, tools, and scope propagation, which is the right kind of decomposition for an agent platform. 

You also made several mature choices that many agent projects skip: approval checkpoints, audit chain/HMAC support, secret vaulting, allowlist enforcement, session reaping, rate limiting, observability hooks, dynamic MCP tool registration, and persistent chat memory. 
The split between `AgentLoop`, `AgentStateMachineRunner`, `AgentStepProcessor`, `ToolInvocationGuard`, `CheckpointResumeHandler`, and `ApprovalCheckpointManager` shows real effort to avoid one God-class loop. 

## Where it is weak

Your biggest architectural problem is that channel abstraction exists on paper, but Telegram still dominates the real execution model. `ChannelSender`, `ReplyRenderer`, `ChannelJobNotifier`, `ChannelDaemon`, `ChannelType`, and `MessagePipeline` are good SPI ideas, yet the app module still contains Telegram-specific route lifecycle, message mapping, concurrency control, allowlist semantics, notifier behavior, and daemon startup logic, which means “add Slack/Discord/WhatsApp” is not actually cheap yet. 
A concrete smell is `ChannelApprovalTimeoutDispatcher` defaulting missing channel types to `TELEGRAM`; that is a silent adapter bias in code that should be channel-neutral. 

The second major problem is lifecycle complexity. `TelegramChannelRoute` manually manipulates `CamelContext`, removes JMX lifecycle strategies, starts the context in a virtual thread on `StartupEvent`, conditionally self-registers the route, and works around Quarkus/Camel startup behavior; that is clever, but it is exactly the kind of cleverness that becomes operational debt later. 
The same pattern appears in the build/runtime setup with multiple `--add-opens`, unsafe-memory flags, ByteBuddy experimental flags, native-image runtime initialization exceptions, and JDK 25 accommodations, which tells me the platform is running very close to integration edges. 

The third issue is overuse of string-driven design. Agent roles are resolved from strings, skill roles depend on the `"skill:"` prefix, channel IDs are raw strings, Telegram configuration detection relies on placeholder string checks, and routing decisions often hinge on `fromString`/name matching rather than typed identities or sealed commands. 
This works, but it is brittle: string conventions are fine at boundaries, not as a dominant internal domain model. 

## Refactoring priorities

Here is the blunt version:

| Area | What works | What is wrong | Refactor direction |
|---|---|---|---|
| Channel architecture | You already have `ChannelSender`, `ReplyRenderer`, `ChannelDaemon`, `ChannelJobNotifier`, and `ChannelType`.   | Telegram lifecycle and behavior still live in the app layer, so the abstraction is incomplete.   | Move Telegram into its own adapter module, for example `arcclaw-channel-telegram`, and define a fuller SPI: `InboundChannelAdapter`, `OutboundChannelAdapter`, `ChannelIdentityResolver`, `ChannelAccessPolicy`, `ChannelNotificationPort`.   |
| Agent execution | `AgentLoop` and `AgentStateMachineRunner` show good separation compared with a monolith.   | `AgentLoop` still handles role normalization, lifecycle entry, workspace context, checkpoint semantics, and boundary policy concerns in one path.   | Split into use-case services: `StartAgentRun`, `ResumeApprovedRun`, `DenyPendingRun`, `FinalizeRun`, `LoadExecutionContext`.   |
| Startup/lifecycle | Startup checks and data-dir initialization are thoughtful and user-friendly.   | Route self-registration and manual Camel context surgery are too magical.   | Replace with an explicit adapter bootstrap bean and a stable lifecycle contract; no adapter should need to patch framework internals at startup.   |
| Context model | `MessageContext` unifies channel message, sender, user, and chat/session handling.   | It is acting like a mutable request bag, and flags such as autonomy/resume risk turning it into a junk drawer.   | Use immutable command objects plus a separate mutable processing state object; apply CQRS-lite inside the pipeline. |
| Registry lookups | CDI `Instance<>` streaming is flexible.   | Repeated `stream().filter().findFirst()` lookup for senders/renderers/notifiers is runtime-discovered every time.   | Build validated maps at startup, fail fast on duplicates, and use `EnumMap<ChannelType, ...>` or typed registries. |
| Code hygiene | There is evidence of active cleanup, for example replacing hardcoded notifiers with dispatchers.   | Deprecated leftovers like `ApprovalTimeoutNotifier` and temporary comments such as “Tier 2B scaffolding” signal transitional code staying too long.   | Remove dead classes aggressively; apply YAGNI harder once a replacement exists.   |

A channel-first redesign should start with this rule: core must not know Telegram exists, and app should not be the permanent home of adapter logic. 
Today the code is better than many bot systems, but it is still “Telegram generalized,” not “multi-channel native.” 

## Performance and reliability

Your current implementation is optimized for responsiveness in a single-node, mostly in-memory control flow: Telegram processing is offloaded through `ManagedExecutor`, duplicate in-flight chat work is blocked with a per-chat `ConcurrentHashMap<String, AtomicBoolean>`, and several long-running flows use virtual threads or async observers. 
That is good for low-latency UX, but it will not scale cleanly to horizontal deployments because the lock is process-local, not session-global, and message ordering/backpressure are not modeled as first-class infrastructure. 

SQLite is also carrying a lot of responsibility here: state, chat memory, audit, compaction summaries, cron entities, checkpoints, and probably parts of RAG state all live around the same persistence story. 
That is acceptable now, but once you have concurrent channels, cron jobs, approvals, memory writes, and RAG ingestion together, you need explicit write-path benchmarking and likely a split between operational state and analytical/content state, otherwise the database becomes your hidden governor. 

Logging is another place to tighten. The code warns that debug LLM logging can expose plaintext user data and API keys, yet ordinary Telegram handling and sender paths also log incoming text and outbound text, so your effective data exposure surface is larger than the debug flag suggests. 
I would treat “no raw user prompt or tool output in logs by default” as a non-negotiable production policy. 

## Concrete roadmap

1. Extract a real channel platform. Create `arcclaw-channel-spi` and `arcclaw-channel-telegram`; move route, mapper, sender, notifier, allowlist adapter, and daemon there, leaving `arcclaw-app` only to wire beans and launch modes. 
2. Introduce a `ChannelSessionCoordinator` abstraction for per-conversation serialization, acknowledgements, retries, and backpressure; the Telegram `AtomicBoolean` gate becomes one implementation, not the design. 

3. Replace stringly-typed role/channel/session concepts with typed value objects such as `AgentRoleRef`, `SkillRoleRef`, `ChannelId`, `PlatformUserId`, and `SessionId`; keep strings only at the external boundary. 
4. Break `AgentLoop` into narrower application services and move role resolution into a dedicated policy object, because `"skill:"` prefix preservation is a sign the system is compensating for a missing domain type. 

5. Remove lifecycle hacks from business adapters. If a channel requires manual `CamelContext` surgery, isolate that in a bootstrap/integration layer with tests around startup states; do not let adapter code normalize framework instability into your permanent architecture. 
6. Add startup-time registry validation everywhere: duplicate channel sender, duplicate renderer, duplicate notifier, duplicate role, duplicate skill, duplicate tool capability. That is cheap and prevents entire classes of weird runtime bugs. 

7. Be stricter with YAGNI. There are signs of future-facing extensibility already, but some pieces are still halfway transitional rather than cleanly generalized; remove deprecated classes, reduce comments that explain framework fights, and keep only abstractions that are already paying rent. 
8. Define three explicit bounded contexts: `conversation-runtime`, `agent-execution`, and `knowledge/persistence`; right now `core` is powerful, but it is growing into a “smart basement” that risks becoming the next monolith. 

My strongest single recommendation is this: before adding more features, spend one full refactor cycle making channels first-class ports and execution/session handling first-class application services. 
That will give you a platform that can survive Slack, Discord, web sockets, email, voice, or MCP-triggered conversations without dragging Telegram assumptions through every layer. 

Next useful step: paste Gemini or Claude’s review beside this one, and I’ll debate it point by point.

---

Perplexity Gemini 3.1


This is a **serious** codebase, not a toy: the 325-file dump shows real modularization, a channel API, a pipeline layer, checkpointed agent execution, observability hooks, audit/compliance pieces, and multiple entry points across app, channel, and core modules. 
My honest review is that the project is already impressive in capability, but the architecture is still too workaround-heavy and too Telegram-shaped in the places that matter most for long-term change, which means the next wave of features will cost more than they should unless you refactor now. 

## Architecture

- The module split is good on paper, but the runtime path is not clean enough in practice: `arcclaw-app` still reaches into `core.pipeline`, `TelegramAgentHandler` creates `MessageContext` itself, and the inbound channel adapter is doing application orchestration instead of only transport adaptation. 
- `MessageContext` is acting like a mutable “everything bag” with chat data, sender, session state, role, goal, autonomy, resume signal, and session context, which creates temporal coupling and makes handler ordering part of your correctness model. 
- The strongest architectural criticism is this: you have abstractions, but several of them are not yet enforcing boundaries; they document intent, while the concrete code path still leaks framework and channel details across layers. 

## Channels

- Right now Telegram is not just one channel implementation; it is effectively shaping the application flow, because `TelegramChannelRoute`, `TelegramAgentHandler`, `TelegramChannelSender`, Telegram-specific mapping, and direct sender usage dominate the real interaction path even though `ChannelSender`, `ChannelType`, and a registry exist. 
- `TelegramAgentHandler` injects `TelegramChannelSender` directly instead of pushing everything through a channel-neutral outbound port, and `MessageContext` itself carries a `ChannelSender`, which means your core flow still knows too much about delivery mechanics. 
- There is also a concrete UX/concurrency flaw in the current Telegram flow: for non-command input it sends `⏳` before lock acquisition, and if the per-chat flag is already set it then sends another “still processing” message, so overlapping messages can produce redundant acknowledgments. 

## Agent core

- `AgentLoop` says it is only a lifecycle shell, but it still resolves roles, loads checkpoints, resumes state, decides finalization, stamps channel info, and handles several failure classes, so it remains a coordinator-heavy class with too many reasons to change. 
- The `skill:` role handling is clever but brittle: the code preserves the raw `skill:` prefix for prompt selection while also resolving an enum role for behavior, which means role semantics are split across string conventions, enum fallback logic, and prompt-building behavior rather than a first-class type system. 
- I would be especially hard on the silent fallback of unknown roles to coordinator, because that is the kind of “safe default” that quietly hides configuration or routing defects and can make debugging, audit trails, and trust boundaries harder later. 

## Lifecycle and performance

- `TelegramChannelRoute` is the biggest red flag in the code I inspected: it manually removes and re-adds the Camel component, conditionally skips registration for placeholder tokens, starts a virtual thread on startup, manually starts the Camel context, and removes lifecycle strategies by class-name matching to work around a JMX-related failure. 
- That may be working today, but it is fragile, framework-fighting code, and comments that explain why a class-name-based `removeIf` hack is needed are usually a sign that the integration boundary needs redesign, not more polish. 
- Your current per-chat `AtomicBoolean` guard is good for preventing overlap, but it is not a scalable backpressure model because it serializes work, drops into “please wait” UX instead of queueing, and leaves you without a real mailbox, cancellation policy, or bounded concurrency strategy for busy conversations. 

## Refactoring roadmap

1. Create a true application boundary with something like `ConversationEnvelope` in and `ReplyEnvelope` out, so channel adapters only normalize transport details and never construct core execution objects directly. 
2. Remove `ChannelSender` from `MessageContext` and replace it with an outbound port resolved later by channel type or capability, because the current design lets transport concerns bleed into orchestration. 
3. Replace mutable `MessageContext` with an immutable request object plus a smaller mutable pipeline state object, so handler order stops being an invisible correctness contract. 
4. Make role handling first-class with a real `RoleDefinition` or `AgentProfile` object for persona, tools, system prompt, autonomy rules, and checkpoint keying, instead of mixing enum roles with `skill:` string conventions and fallback behavior. 
5. Pull Telegram/Camel startup and lifecycle workarounds behind an infrastructure adapter boundary, because production code should not need class-name surgery on lifecycle strategies to start cleanly. 
6. Unify CLI, REST, and chat-channel execution through one application service, because `ChatCommand` already shows a concerning split where `WorkspaceContext` is seeded with coordinator while `dispatchToAgent` later uses the selected CLI role, which can create context mismatch in telemetry, policy, or behavior. 
7. Replace the simple per-chat lock with a bounded conversation mailbox or actor-style executor, so you can queue, coalesce, cancel, and monitor work instead of only rejecting overlap. 
8. Apply SOLID and YAGNI more aggressively by deleting accidental complexity, not by adding more abstractions: keep `ReplyNode`/renderer-style extensibility where it earns its keep, but stop introducing interfaces that the live code path bypasses. 

My blunt conclusion is this: the project already proves capability, but the next level is not “more features,” it is “stronger boundaries.” 
If you want this to become an excellent multi-channel agentic platform rather than a very capable Telegram-first system, the main battle is now architectural integrity under change, not raw functionality. 

Would you like the next pass to be a file-by-file refactoring plan for the most critical classes such as `TelegramChannelRoute`, `TelegramAgentHandler`, `MessageContext`, `MessagePipeline`, `AgentLoop`, and `ChatCommand`?


---

Deep Seek - Deepthink

## **Executive Summary**

ArcClaw is a sophisticated multi-agent framework built with Java 25, Quarkus, LangChain4J, and SQLite. It supports multiple channels (Telegram, REST, CLI) with a robust security model (audit logging, secret vault, autonomy levels) and advanced features like RAG, cron scheduling, and skill loading. The codebase is large, well-structured, and evidently well-tested across environments.

However, there are areas where architectural principles (SOLID, YAGNI, GoF) could be strengthened, performance can be improved, and the design could be made more extensible for additional channels. The review below focuses on deep, actionable feedback, not just praise.

---

## **1. Architecture & Design Patterns**

### 1.1 Overall Modularization
- **Positive:** The project is divided into `arcclaw-core`, `arcclaw-app`, `arcclaw-channel-api`, `arcclaw-mcp`, `arcclaw-rag`, and `arcclaw-deployment`. This separation of concerns is commendable.
- **Issue:** The `arcclaw-app` module contains business logic that might be better placed in `core`. For example, `TelegramAgentHandler`, `TelegramChannelSender`, and the Telegram-specific classes reside in `app/channel/telegram`, which is fine, but the `ChannelSenderRegistry` and `ChannelJobNotifier` are also in `app`. The `app` module should be the thin entry point, with all channel-agnostic logic in `core`. Consider moving generic channel management (like `ChannelSenderRegistry`, `ChannelCronNotificationDispatcher`, `ChannelApprovalTimeoutDispatcher`) to `core` to allow other channels to reuse them without depending on `app`.

- **Suggestion:** Refactor so that `core` defines interfaces and abstract dispatchers; `app` provides concrete channel implementations (Telegram, Discord, etc.) and registers them via CDI. Currently, `ChannelSenderRegistry` lives in `app`, which means a new channel would need to bring its own registry or duplicate logic. Instead, make `core` provide a `ChannelSenderRegistry` that uses `Instance<ChannelSender>` and `Instance<ReplyRenderer>` (both from `core`?), and move the dispatchers to `core`. This aligns with the Open/Closed principle.

### 1.2 Dependency Inversion
- **Positive:** Extensive use of CDI and interfaces (e.g., `IntentClassifier`, `Agent`, `AgentTool`, `ChannelSender`).
- **Issue:** Some concrete classes are directly instantiated or rely on static methods that hinder testability and flexibility. For instance, `CronScheduleUtil` uses a static `CronParser` and static methods. This makes it hard to mock in tests. Better to make it a CDI bean with injectable parser.
- **Suggestion:** Convert `CronScheduleUtil` to an injectable service (`CronService`) that can be mocked. Similarly, avoid static utility classes for non-trivial logic; use CDI beans or factories.

### 1.3 Single Responsibility Principle (SRP)
- **Positive:** Many classes have clear responsibilities (e.g., `AgentLoop` orchestrates, `AgentStateMachineRunner` handles the loop, `ToolStepHandler` handles tool invocation).
- **Issue:** `AgentLoop` is still quite large and does too much: it handles MDC, spans, checkpoint loading, role resolution, and even channel info stamping. Some of these can be extracted into separate services (e.g., `CheckpointLoader`, `RoleResolver`, `ContextStamper`). The `runWithRole` method is ~100 lines, mixing concerns.
- **Suggestion:** Extract the checkpoint loading/resume logic into `CheckpointLoader` and `CheckpointResumeHandler` (already present, but `AgentLoop` still does loading). Move the `tryStampChannelInfo` into a separate service (`ChannelInfoStamper`) that can be injected. This makes `AgentLoop` focus on orchestration.

### 1.4 Factory Pattern Usage
- **Positive:** `LlmModelFactory` centralizes LLM creation; `EmbeddingProviderFactory` is a good SPI for RAG providers.
- **Issue:** The `ModelProducer` and `CompactionModelProducer` both use `LlmModelFactory`, but they still duplicate config properties and logic. They could be unified into a single `ChatModelProducer` that uses `LlmModelFactory` and provides qualifiers (`@Identifier("intent-classifier")`, `@Identifier("compaction")`). This reduces duplication.
- **Suggestion:** Refactor `ModelProducer` and `CompactionModelProducer` to delegate to a common `ChatModelProducer` that uses `LlmModelFactory` and qualifiers. The config properties can be injected into the producer and passed to the factory.

### 1.5 Observer Pattern
- **Positive:** `AgentObserver` interface allows metrics, audit, and CLI observers.
- **Issue:** `AgentObserver` is used for recording LLM calls and steps, but the `onLoopComplete` method is not consistently used (only `AuditObserver` implements it). The `AgentLoop` fires events via `observers.forEach` but the `onLoopComplete` is called only in `finally` block after `result` is computed. This is okay, but the separation between `recordLlmCall` and `onLoopComplete` could be unified into a single event bus.
- **Suggestion:** Consider using CDI events for `AgentObserver` notifications to decouple even more. Currently, the list of observers is static; events would allow dynamic addition.

---

## **2. Code Quality & Maintainability**

### 2.1 Naming Conventions
- **Positive:** Mostly consistent.
- **Issue:** Some names are ambiguous: `AgentStateMachineRunner` vs `AgentLoop` vs `AgentStepProcessor`. The `AgentStateMachineRunner` contains `run` which delegates to `stepProcessor`, but the name `AgentStateMachineRunner` suggests it runs the state machine, which it does, but `AgentLoop` also runs the loop. The distinction is blurry. `AgentLoop` could be renamed to `AgentOrchestrator` and `AgentStateMachineRunner` to `AgentLoopExecutor`.
- **Suggestion:** Clarify naming to reflect single responsibility.

### 2.2 Exception Handling
- **Positive:** `StructuredRetryLoop` handles schema errors with a retry; `SqliteRetryTemplate` retries on SQLite BUSY.
- **Issue:** Some places catch `Exception` and log, but also wrap in `RuntimeException` losing context. For example, in `AgentStepProcessor.handleEngineException`, when a non-retryable error occurs, it returns a terminal `AgentResult.failed` with a message, but the original exception is lost. This makes debugging hard.
- **Suggestion:** In cases where the exception is not propagated, at least log the full stack trace. Currently, many catch blocks call `log.debugf(e, ...)` only when `log.debug` is enabled; consider logging at `ERROR` with the full stack trace for non-recoverable errors. Also, avoid swallowing `InterruptedException` without restoring the interrupt flag.

### 2.3 Use of Optional
- **Positive:** `Optional` is used in many places.
- **Issue:** In `MessageContext`, fields like `sessionId`, `agentRole`, etc., are stored as nullable and accessed via `Optional.ofNullable`. This is fine, but the getters return `Optional` while setters accept nullable. Consistency is okay. However, in `WorkspaceContext`, ScopedValues are used and accessed via `isBound()` and `get()`, which is good.
- **Suggestion:** Ensure that when `Optional` is used, it's not just for null-check but also for conveying that the value may be absent. In `MessageContext`, `getSessionId()` returns `Optional`, but `setSessionId` sets nullable. That's acceptable. However, there's a mix: `getSessionContext()` returns `Optional`, but `setSessionContext` sets nullable. Could be improved by making all getters return `Optional` and setters accept `Optional`, but that may be overkill.

### 2.4 Data Classes
- **Positive:** Use of records for DTOs and sealed interfaces for `AgentResult`, `ReplyNode`, `ChannelMessage`, etc., is excellent.
- **Issue:** Some records have large constructors (e.g., `AgentResult`). But that's acceptable.
- **Suggestion:** Keep using records, but ensure they are immutable and use builder patterns where complexity grows. Already good.

### 2.5 Comments and Documentation
- **Positive:** Many classes have comprehensive Javadoc.
- **Issue:** Some comments are outdated or misleading. For example, in `ChannelSenderRegistry`, the comment says "Only used by TelegramCronNotificationObserver in this phase", but it's also used elsewhere. Also, `ApprovalTimeoutNotifier` is marked `@Deprecated` but still present. Dead code should be removed.
- **Suggestion:** Regularly clean up deprecated classes and update comments. Use `@deprecated` with a note on replacement.

---

## **3. Performance & Scalability**

### 3.1 Database Access
- **Positive:** SQLite with WAL, async persistence engine, retry template.
- **Issue:** The `AsyncPersistenceEngine` uses a single virtual thread for draining critical and telemetry queues. If the drain thread is blocked (e.g., due to a slow SQLite operation), it can block all critical events. The engine uses a lock per session, but the drain is single-threaded. This could become a bottleneck under high concurrency.
- **Suggestion:** Consider using multiple drain threads (e.g., a thread pool) or per-session drain threads to avoid one slow session affecting others. However, SQLite's single-writer nature means concurrency is limited anyway. The current design with a single drain thread and per-session locks is acceptable, but ensure the drain thread is not doing blocking operations that could starve other sessions. The `waitForIdle` method polls; maybe use a more efficient mechanism.

### 3.2 Caching
- **Positive:** Use of Caffeine for rate limiting and session key caching.
- **Issue:** `ApiKeyRegistry` caches rate limit buckets per API key with Caffeine, but the cache size is fixed to 10,000. That's fine. However, the `VaultKeyCache` is `@WorkspaceScoped`, so each session has its own cache. That's good.
- **Suggestion:** Consider using `@CacheResult` annotations for some expensive operations like `CompactionSummaryRepository.findLatestBySessionId` to avoid repeated DB hits per message. Currently, compaction summary is loaded on every message via `SystemPromptBuilder.build` (it calls `findLatestBySessionId`). This could be cached per session for the duration of the conversation, but note that compaction summary can change after a compaction occurs. So cache invalidation must be handled.

### 3.3 Virtual Threads
- **Positive:** Use of `@VirtualThreads` and `ManagedExecutor` for async operations.
- **Issue:** The `MessagePipeline` runs handlers on the same thread that received the message (Telegram uses a virtual thread from Camel). This is fine, but the `AgentLoop` runs synchronously, potentially blocking that virtual thread. Virtual threads are cheap, so this is acceptable. However, the `WorkspaceContext.run` uses `ScopedValue` and the execution is synchronous. This is okay.

- **Suggestion:** The `AgentLoop` could be made non-blocking by using reactive APIs, but that would add complexity. Given the current performance, it's fine.

### 3.4 LLM Calls
- **Positive:** Token counting, budget governance, and circuit breakers.
- **Issue:** The `IntentClassifierService` makes an LLM call for every non-command message. This adds latency and cost. The `SkillAwareIntentClassifier` reduces this by fast-path matching skills, but still falls back to LLM for many messages. Could there be more rule-based fast paths? For instance, simple greetings, "help", etc., could be routed directly without LLM.
- **Suggestion:** Expand fast-path routing to cover more cases, possibly using a lightweight keyword-based classifier before LLM. Also, consider caching classification results for similar messages (though risky due to session state changes).

### 3.5 JSON Serialization
- **Positive:** Jackson used extensively.
- **Issue:** Some objects are serialized/deserialized repeatedly (e.g., `ReasoningStep` to JSON for checkpoints). This can be expensive. The `StatefulAgentStoreImpl` uses `objectMapper.writeValueAsString` for steps, which might be heavy.
- **Suggestion:** Consider using a more compact binary format like MessagePack or Protobuf for checkpoint storage, but JSON is human-readable and fine. Ensure the `ObjectMapper` is configured for performance (e.g., disable features not needed). Already using standard config.

---

## **4. Security**

### 4.1 Secret Vault
- **Positive:** AES-GCM-SIV, Argon2id key derivation, HMAC chain for audit, pepper configuration.
- **Issue:** The `DevSecretSeeder` seeds secrets from environment variables, but only when `autoSeedEnabled` is true. In production, this is false, so secrets must be seeded manually. However, the `StartupSecurityValidator` checks for `ARCCLAW_AUDIT_HMAC_KEY` presence, but if `autoSeedEnabled` is false and the key is missing, the app fails to start. That's correct. But the `seedAuditHmacKeyFromEnv` reads the key from config and stores it; it should only run if `autoSeedEnabled` and not in dev/test? Actually, it runs in `onStart` for prod/cli if `autoSeedEnabled` true. So it's okay.

- **Issue:** The `VaultKey` implements `AutoCloseable` to zero out keys. However, some usages may not close the key in a try-with-resources. For example, in `DevSecretSeeder.seedFromHostEnv`, it gets `optionalKey` and then immediately closes in try-with-resources. That's good. But in `SecretVaultImpl.get`, it returns `VaultKey` directly, leaving the caller responsible for closing. The caller must ensure it's closed. This is documented, but easy to miss.
- **Suggestion:** Provide a wrapper that automatically closes after use, or return a copy of the secret bytes instead of the `VaultKey`. However, the `VaultKey` is designed to be used in a try-with-resources, so it's okay. Add more explicit documentation.

### 4.2 Audit Chain
- **Positive:** HMAC chain, mandatory events, async persistence.
- **Issue:** The `AsyncPersistenceEngine` processes critical events synchronously (blocking) and telemetry asynchronously. The critical events use a `CompletableFuture` that the caller joins. This is fine. However, if the critical drain thread is blocked, the caller blocks. That's acceptable for mandatory events.

- **Issue:** The `AuditHashUtil.getHmacKey` uses a double-checked locking pattern with a volatile field. This is okay, but it accesses `SecretVault` via `Instance.get()`. The vault might not be ready during startup (e.g., seeding). The `onStart` method with `@Priority(500)` tries to prime the key, but if seeding hasn't happened yet, it might fail and fallback to lazy init. This is okay, but the lazy init may happen after `StartupSecurityValidator` has already verified the key exists (so it's safe).

### 4.3 Telegram Allowlist
- **Positive:** Numeric IDs preferred, string usernames deprecated. The `StartupSecurityValidator` blocks string-only allowlists in non-dev.
- **Issue:** The `TelegramAllowlistGuard` still supports string usernames as fallback, but the validator forbids string-only allowlists. This is good, but the guard still includes the string fallback, which might be used if numeric IDs are absent (but in production, it would fail startup). So it's safe.
- **Suggestion:** Remove the string fallback entirely and rely solely on numeric IDs. This would align with the security requirement.

---

## **5. Channel Extensibility**

### 5.1 Current State
- **Positive:** `arcclaw-channel-api` defines contracts (`ChannelSender`, `ChannelMessage`, `ReplyNode`, `ReplyRenderer`, `ChannelAllowlist`). The `app` module has Telegram implementation. This is a good foundation.
- **Issue:** The `MessagePipeline` and its handlers are in `core`, which is good, but some handlers (e.g., `CommandDispatchHandler`, `IntentClassificationHandler`) are channel-agnostic. However, the `AgentDispatchHandler` uses `SkillRegistry` and `AgentLoop`, both in `core`, which is fine. The `ChannelSenderRegistry` is in `app`, but it should be in `core` to be reused by other channels. Currently, a new channel would need to implement its own sender and then also wire up the `ChannelSenderRegistry`? Actually, `ChannelSenderRegistry` is a CDI bean that collects all `ChannelSender`s via `Instance`. It's in `app`, but it could be moved to `core`. The `core` already has `MessagePipeline`, and it uses `ChannelSender` from the context, but doesn't need `ChannelSenderRegistry` directly. The registry is used by `TelegramJobNotifier` and `ChannelCronNotificationDispatcher` (both in `app`). To make cron notifications work for new channels, the notifier for that channel would need to be implemented. The dispatchers are in `app`; they could be moved to `core` as well, so new channels only need to provide a `ChannelJobNotifier` bean.

- **Suggestion:** Move `ChannelSenderRegistry`, `ChannelCronNotificationDispatcher`, `ChannelApprovalTimeoutDispatcher`, and `ChannelJobNotifier` to `core`. This would make adding a new channel (e.g., Discord) a matter of:
  - Implementing `ChannelSender`, `ReplyRenderer`, `ChannelJobNotifier`, and maybe a `ChannelDaemon`.
  - Adding the necessary CDI beans.
  - The existing dispatchers would automatically pick them up via `Instance`.

### 5.2 MessagePipeline
- **Positive:** Handler chain with priority.
- **Issue:** The `AgentDispatchHandler` uses `CronResultFormatter.format` to format the result and sends raw text via `ChannelSender`. That's fine. However, the `ChannelSender` interface expects to send `ReplyNode` list or raw string. The `AgentDispatchHandler` only sends raw string, so structured replies are not used. This limits the ability to render rich messages in other channels.
- **Suggestion:** Enhance `AgentResult` to include a list of `ReplyNode` directly (e.g., `Finished` could have a `List<ReplyNode>`). Then the `AgentDispatchHandler` could call `sender.send(recipient, nodes)` instead of `sendRaw`. This would allow channel-specific rendering (e.g., Telegram Markdown, Discord embed). Currently, `CronResultFormatter` returns a plain string, and `TelegramChannelSender` uses `TelegramMarkdownV2Renderer` only for `send` (not `sendRaw`). So structured replies are not used in the pipeline except for cron notifications (which use `sendRaw`). Consider using structured replies for all outputs.

### 5.3 Cron Notifications
- **Positive:** `AgentJobCompleted` event carries rendered answer.
- **Issue:** The rendered answer is a plain string, not structured. This means cron results cannot leverage channel-specific formatting. Could store the original `AgentResult` in the event and let the notifier render it appropriately.
- **Suggestion:** Instead of pre-rendering, pass the `AgentResult` (or at least the structured data) to the notifier, and let the notifier call the appropriate renderer. This would make cron notifications more flexible.

---

## **6. Specific Refactoring Suggestions**

### 6.1 Unify ChatModel Producers
- Create a `ChatModelProducer` class that uses `LlmModelFactory` and CDI qualifiers:
  ```java
  @Produces
  @Identifier("intent-classifier")
  @ApplicationScoped
  public ChatModel intentClassifierModel() {
      return modelFactory.create(...);
  }

  @Produces
  @Identifier("compaction")
  @ApplicationScoped
  public ChatModel compactionModel() {
      return modelFactory.create(...);
  }
  ```
  Then remove `ModelProducer` and `CompactionModelProducer`.

### 6.2 Move Channel-Agnostic Classes to Core
- Move `ChannelSenderRegistry`, `ChannelCronNotificationDispatcher`, `ChannelApprovalTimeoutDispatcher`, and `ChannelJobNotifier` to `arcclaw-core`. Update imports.
- This will require moving `TelegramJobNotifier` to `app` (it's already there), and it will implement `ChannelJobNotifier` (which now lives in core). That's fine.

### 6.3 Simplify AgentLoop
- Extract `CheckpointResumeHandler.handle` to be used inside `AgentLoop`. Currently, `AgentLoop` calls `checkpointResumeHandler.handle` and then uses the result; but the `handle` method also loads the checkpoint and does logic. That's fine.
- Extract the role resolution logic to a separate `RoleResolver` service that returns the canonical role and maybe the `AgentRoleEnum`. This would remove the `if (agentRole.startsWith(SKILL_PREFIX))` branching from `AgentLoop`.

### 6.4 Use CDI Events for Observers
- Replace direct iteration over `Instance<AgentObserver>` with CDI events. For example:
  ```java
  @Inject
  Event<LlmCallEvent> llmCallEvent;
  ```
  Then in `AgentLoop`, fire events. Observers can listen. This decouples observers from the core loop.

### 6.5 Enhance Error Handling with Structured Output
- In `EngineStepHandler.handleEngineException`, the `isSchemaError` check catches `InvalidRequestException` and `HttpException` (status 400). This is good, but there is also a `isToolUseFailed` branch that extracts `failed_generation`. This logic is brittle. Consider using a more robust way to extract the failed generation, maybe by parsing the error JSON. The current regex extraction may fail if the error format changes.
- Suggestion: Use Jackson to parse the error response, which is often a JSON string. Many LLM providers return a structured error. Could parse it and extract the `failed_generation` field if present.

### 6.6 Refactor CronResultFormatter
- Make it return a `List<ReplyNode>` instead of a plain string. Then the `AgentDispatchHandler` and cron notifiers can use `ChannelSender.send`. This would improve extensibility.

### 6.7 Improve SqliteRetryTemplate
- The `execute` method catches `Exception` and checks `SqliteErrorStrings.isBusy(e)`. This works, but it catches all exceptions, not just SQLite-specific ones. It then checks if it's busy; if not, it rethrows. That's fine. However, the `isBusy` method checks the entire cause chain, which is good. Consider also checking for `SQLException` and looking at the SQLite error code.

### 6.8 Use of ScopedValue for WorkspaceContext
- The `WorkspaceContext` class uses ScopedValue for session, user, chat, etc. This is excellent for thread safety and context propagation. However, there are also `WorkspaceScopeHolder` ScopedValues for `WORKSPACE_ID`, `USER_ID`, `CHAT_ID`. This duplication is confusing. `WorkspaceContext` already exposes these, but `WorkspaceScopeHolder` also has its own. The `WorkspaceScopeHolder` is used in `WorkspaceScopeContext` and `ContextAwareExecutorService`. They should be consolidated into one source of truth.
- Suggestion: Remove `WorkspaceScopeHolder.WORKSPACE_ID`, `USER_ID`, `CHAT_ID` and instead use `WorkspaceContext`'s ScopedValues. The `WorkspaceScopeHolder` can delegate to `WorkspaceContext` for these values. This would reduce duplication and potential inconsistency.

### 6.9 Avoid Using `@ActivateRequestContext` in `AgentLoop`
- The `AgentLoop.run` methods are annotated with `@ActivateRequestContext`. This is needed because the method runs on virtual threads where CDI request context may not be active. However, the method already uses `WorkspaceScopeHolder.run` which sets up the scope. The `@ActivateRequestContext` might be redundant because the request context is not used (only the custom workspace scope). It might be safe to remove, but check if any CDI injection requires the request context. Probably not, because all dependencies are `@ApplicationScoped` or `@WorkspaceScoped`. Removing it could simplify.

### 6.10 Use Interface for CronRepository
- The `AgentCronRepository` is a class with many methods; it's used in many places. Consider extracting an interface to allow easier testing and future alternative implementations (e.g., in-memory for tests). Currently, the class is injected directly. That's okay but could be improved.

### 6.11 Remove Dead Code
- Remove `ApprovalTimeoutNotifier` (deprecated).
- Remove unused imports and variables flagged by IDE.

---

## **7. Conclusion**

ArcClaw is a well-architected, feature-rich framework that demonstrates careful consideration of security, performance, and extensibility. The use of modern Java features (records, sealed classes, ScopedValue) and Quarkus' native image support is impressive. The testing across multiple environments indicates a high level of quality.

The suggested refactorings aim to further enhance maintainability, extensibility (especially for new channels), and performance. Key areas to address are:

1. **Move channel-agnostic logic to `core`** to support new channels without code duplication.
2. **Simplify and split the `AgentLoop`** to adhere to SRP.
3. **Unify chat model producers** to reduce duplication.
4. **Improve structured output handling** to make it more robust and use structured replies throughout.
5. **Consolidate ScopedValue usage** to avoid duplication.

These changes would make the codebase even more SOLID, easier to extend, and more resilient to future changes. The project already has a strong foundation; with these refinements, it could become a reference implementation for Java-based agent frameworks.


---

Qwen 3.5 Plus

# ArcClaw Architecture & Code Review (Phase 65)

**Reviewer Role:** Senior Java Architect / Gen AI Agentic Expert / Performance Specialist
**Scope:** End-to-End Deep Dive (Core, App, Channels, Persistence, Security)
**Verdict:** **Strong Foundation, Critical Architectural Debt in Channel Abstraction & Persistence Lifecycle.**

You have built a robust, high-performance agentic kernel. The use of Java 25 `ScopedValue`, SQLite `vec0`, and the Audit Chain is impressive. However, to scale beyond Telegram and ensure enterprise-grade maintainability, significant refactoring is required. The current architecture couples "Channel Transport" too tightly with "Application Logic," and persistence lifecycle management is too manual for production safety.

Below is the brutal, deep-dive analysis.

---

## 1. Channel Abstraction & Extensibility (Critical)
**Current State:** Telegram is hard-coded into `arcclaw-app`. Camel routes are defined in `TelegramChannelRoute`. Adding Discord/Slack requires modifying `arcclaw-app` dependencies and logic.
**Verdict:** **Violation of Open/Closed Principle.**

### Refactoring Required:
1.  **Extract Channel Adapters to Separate Modules:**
    *   **Action:** Create `arcclaw-channel-telegram`, `arcclaw-channel-discord`, etc.
    *   **Why:** `arcclaw-app` should only depend on `arcclaw-channel-api`. It should not know about Camel, Telegram Bot API, or Discord JDA.
    *   **Implementation:**
        *   Move `TelegramAgentHandler`, `TelegramChannelRoute`, `TelegramChannelSender` to `arcclaw-channel-telegram`.
        *   Use Java SPI (`META-INF/services`) or CDI Discovery to register `ChannelDaemon` implementations.
        *   `arcclaw-app` should only contain `ChannelOrchestrator` which iterates over available `ChannelDaemon` beans.

2.  **Decouple Camel from Core Logic:**
    *   **Issue:** `TelegramAgentHandler` directly injects `MessagePipeline`. This couples the transport layer to the processing pipeline.
    *   **Refactor:** Introduce a `ChannelInboundQueue`.
        *   `TelegramAgentHandler` converts Camel `Exchange` → `ChannelMessage` → pushes to `ChannelInboundQueue`.
        *   `MessagePipeline` consumes from `ChannelInboundQueue`.
        *   **Benefit:** Allows backpressure handling. If the agent is overloaded, Telegram messages queue up without blocking Camel threads.

3.  **Unified Channel Context:**
    *   **Issue:** `ChannelUser` records `platformId` and `channelId`. For Telegram, these are often the same (chatId). For Discord, they differ (UserId vs ChannelId).
    *   **Refactor:** Enforce strict semantics in `ChannelUser`.
        *   `userId`: The human identity (for audit/allowlist).
        *   `conversationId`: The thread/chat context (for session resolution).
        *   **Code:** `SessionResolutionHandler` currently relies on `channelId` for session ID. This breaks if a user talks to the bot in DMs vs. Group. Fix this now before adding Discord.

4.  **Renderer Strategy Pattern:**
    *   **Current:** `TelegramMarkdownV2Renderer` is specific.
    *   **Refactor:** Ensure `ReplyRenderer` SPI is strictly enforced. Do not allow `if (channelType == TELEGRAM)` checks in the core pipeline. The `ChannelSenderRegistry` is good, but ensure it's the *only* way to resolve renderers.

---

## 2. Java 25 Concurrency & Context Propagation (High Priority)
**Current State:** Heavy use of `ScopedValue` (`WorkspaceScopeHolder`, `WorkspaceContext`).
**Verdict:** **Cutting Edge, but Inconsistent Implementation.**

### Refactoring Required:
1.  **Eliminate `ThreadLocal` Bridge:**
    *   **Issue:** `WorkspaceScopeHolder` contains `PROPAGATION_BRIDGE` (ThreadLocal) to support MicroProfile Context Propagation.
    *   **Risk:** This defeats the purpose of `ScopedValue` (memory efficiency, no pinning). It creates a dual-state system prone to desync.
    *   **Fix:** Implement a custom `ThreadContextProvider` (you have `WorkspaceThreadContextProvider`) that *only* uses `ScopedValue`. Remove `PROPAGATION_BRIDGE` entirely. If MicroProfile Context Propagation doesn't support `ScopedValue` natively yet, wrap the `ManagedExecutor` to bind `ScopedValue` explicitly in `submit()`/`execute()`.

2.  **ScopedValue Nesting Depth:**
    *   **Issue:** `WorkspaceContext.run` binds 6+ ScopedValues.
    *   **Risk:** Deep nesting can impact performance slightly, but mostly readability.
    *   **Fix:** Create a single `WorkspaceContextRecord` and bind that single object to a `ScopedValue<WorkspaceContextRecord>`. Accessors (`userId()`, `sessionId()`) read from the record. Reduces ScopedValue overhead and simplifies binding logic.

3.  **Virtual Thread Pinning Checks:**
    *   **Issue:** `FtsConnectionInterceptor` and `SecretVaultImpl` use `synchronized`.
    *   **Risk:** In Java 25, `synchronized` is usually safe (JEP 491), but `SecureRandom` or certain Crypto providers might still block.
    *   **Fix:** Audit `Argon2idKeyDerivation`. Ensure the BouncyCastle provider used is fully virtual-thread friendly. Use `ReentrantLock` explicitly where contention is high to signal intent clearly.

---

## 3. Persistence & Schema Management (Critical Risk)
**Current State:** Manual DDL execution in `AuditSchemaEnforcer`, `CronSchemaEnforcer`, `SessionSchemaEnforcer`.
**Verdict:** **Unacceptable for Production.**

### Refactoring Required:
1.  **Adopt Flyway or Liquibase:**
    *   **Issue:** `em.createNativeQuery("CREATE TABLE IF NOT EXISTS...")` in Java startup beans.
    *   **Risk:** No version control on schema. Race conditions during parallel startup. Impossible to rollback. `ALTER TABLE` logic in `CronSchemaEnforcer` is fragile.
    *   **Fix:** Move all DDL to `src/main/resources/db/migration/V1__init.sql`, `V2__add_cron_columns.sql`. Use `quarkus-flyway`.
    *   **Benefit:** Deterministic startup. Audit trail for schema changes.

2.  **SQLite Connection Pooling:**
    *   **Issue:** `FtsConnectionInterceptor` loads `vec0` extension on *every* `onConnectionAcquire`.
    *   **Performance:** `load_extension` is expensive. Agroal reuses connections, but if the pool cycles, you pay this cost repeatedly.
    *   **Fix:** Use a static `AtomicBoolean` at the JVM level (not connection level) to track if `vec0` is loaded for the *database file*. SQLite extensions are per-connection, but you can cache the *knowledge* that it's available. Better: Load it once in a `@Startup` bean using a dedicated connection, then rely on Agroal to keep that connection warm or ensure the load is fast.
    *   **Correction:** Actually, SQLite extensions *are* per-connection. You cannot avoid this if connections are closed. However, Agroal should keep connections open. Ensure `min-size` is at least 1 and `connection-validation` is tuned so the extension stays loaded on the warm connection.

3.  **Audit Chain Integrity:**
    *   **Issue:** `AuditEventRepository` computes `prevHash` inside a `@Transactional` method.
    *   **Risk:** If the transaction rolls back, the hash chain breaks for the next event.
    *   **Fix:** The `SELECT MAX(sequence)` and `SELECT prevHash` must be `SELECT ... FOR UPDATE` to prevent race conditions between concurrent audit writes for the same session. You have `SessionAuditSequencer` (Lock), which is good, but ensure the DB lock aligns with the Java lock.

---

## 4. Agent Loop & State Machine (Refactoring)
**Current State:** `AgentLoop`, `AgentStateMachineRunner`, `AgentStepProcessor`.
**Verdict:** **Functional but God-Class tendencies.**

### Refactoring Required:
1.  **Explicit State Pattern:**
    *   **Issue:** `CheckpointStatus` enum drives logic via `if/else` in `AgentLoop` and `CheckpointResumeHandler`.
    *   **Fix:** Implement State Pattern. `AgentState` interface with implementations (`InProgressState`, `AwaitingApprovalState`, `CompletedState`). `AgentLoop` delegates to `currentState.process()`.
    *   **Benefit:** Removes complex conditional logic from `AgentLoop`. Easier to add new states (e.g., `PausedForBudget`).

2.  **Reflex Registry Decoupling:**
    *   **Issue:** `ReflexRegistry` iterates all reflexes. `ApproveReflex` contains logic for Cron approval *and* Session approval.
    *   **Fix:** Split `ApproveReflex` into `SessionApproveReflex` and `CronApproveReflex`. Use a priority system or specific trigger patterns to avoid one reflex handling too many concerns.
    *   **Pattern:** **Command Pattern**. Each Reflex should be a Command. `ReflexRegistry` is the Invoker.

3.  **Tool Invocation Guard:**
    *   **Issue:** `ToolInvocationGuard` is becoming complex with `DynamicToolLookup` and `Interceptors`.
    *   **Fix:** Use **Chain of Responsibility** explicitly. `ToolGuardChain` composed of `DeduplicationHandler`, `SecurityHandler`, `ApprovalHandler`. `ToolRegistry` passes the request down the chain.

---

## 5. Security & Vault (Hardening)
**Current State:** `SecretVaultImpl`, `AesGcmSivCipher`, `DevSecretSeeder`.
**Verdict:** **Strong Crypto, Risky Lifecycle.**

### Refactoring Required:
1.  **DevSecretSeeder Risk:**
    *   **Issue:** `DevSecretSeeder` seeds secrets based on `auto-seed-secrets` flag.
    *   **Risk:** If accidentally enabled in Prod, it might overwrite manual vault entries or expose env vars to the vault unnecessarily.
    *   **Fix:** Add `@IfBuildProfile("dev")` to the *class*, not just the method. Ensure it is physically excluded from the production jar bytecode. (You have `DevSecretSeederStartup` with `@IfBuildProfile`, ensure `DevSecretSeeder` itself isn't called elsewhere).

2.  **Vault Key Rotation:**
    *   **Issue:** `rotateSecrets` throws `UnsupportedOperationException`.
    *   **Fix:** If you claim security, you need rotation. Implement a "Re-encrypt" job. It doesn't need to be real-time. A scheduled cron job that iterates secrets and re-encrypts with a new Pepper version.

3.  **Audit HMAC Key:**
    *   **Issue:** `AuditHashUtil` lazily loads the HMAC key.
    *   **Risk:** First audit event might fail or delay if key derivation (Argon2) is slow.
    *   **Fix:** You have `onStart` priming. Ensure this blocks application startup (`@Startup` + `CountDownLatch` or similar) until the key is ready. Do not allow the app to serve traffic before the Audit Key is primed.

---

## 6. RAG & Memory (Data Integrity)
**Current State:** `GlobalKnowledgeBase` (SQLite vec), `SessionKnowledgeBase` (In-Memory).
**Verdict:** **Data Loss Risk.**

### Refactoring Required:
1.  **Session Memory Durability:**
    *   **Issue:** `SessionKnowledgeBase` uses `InMemoryEmbeddingStore`. It is `@WorkspaceScoped`. When the session ends (`WorkspaceManager.terminateSession`), this data is lost unless compaction happened.
    *   **Risk:** User loses context of the current session if the server crashes before compaction.
    *   **Fix:** `SessionKnowledgeBase` should also write to SQLite `vec_session` table immediately upon `ingest()`. Read from both `vec_global` and `vec_session` during search.
    *   **Performance:** SQLite vec is fast enough for session-level writes. Don't trade durability for speed here.

2.  **Embedding Model Consistency:**
    *   **Issue:** `EmbeddingModelProducer` switches providers based on config.
    *   **Risk:** If I switch from Ollama (768 dims) to OpenAI (1536 dims), existing vectors in SQLite are incompatible.
    *   **Fix:** Store `embedding_dimensions` and `model_name` in a `rag_metadata` table. On startup, verify config matches DB. If mismatch, warn or trigger re-indexing.

---

## 7. Code Quality & SOLID Violations
**Specific File Reviews:**

*   **`arcclaw-app/src/main/java/com/arcclaw/app/ArcClawCli.java`**:
    *   **Good:** Static bootstrap for data dir.
    *   **Bad:** `Quarkus.run` inside `main`.
    *   **Fix:** Keep it. It's standard for Quarkus CLI. But ensure `CliUtils.validateRequiredEnvVars` doesn't leak into runtime logic.

*   **`arcclaw-core/src/main/java/com/arcclaw/core/agent/loop/AgentLoop.java`**:
    *   **Bad:** `runWithRole` is 150+ lines.
    *   **Fix:** Extract `handleCheckpointResume`, `handleExecution`, `handleFinalState` into private methods or separate helper class `AgentLoopLifecycle`.

*   **`arcclaw-core/src/main/java/com/arcclaw/core/persistence/AsyncPersistenceEngine.java`**:
    *   **Good:** Virtual Thread drain loops.
    *   **Bad:** `waitForIdle` uses `sleepQuietly`.
    *   **Fix:** Use `CountDownLatch` or `CompletableFuture` for test synchronization. Busy waiting is bad practice even in tests.

*   **`arcclaw-core/src/main/java/com/arcclaw/core/security/SecretVaultImpl.java`**:
    *   **Bad:** `getSystemMasterKey` uses `volatile` + `ReentrantLock` double-check.
    *   **Fix:** Use `LazyHolder` idiom or `AtomicReference` for the key cache. Simpler and less error-prone.

*   **`arcclaw-channel-api/src/main/java/com/arcclaw/channel/api/ChannelCommand.java`**:
    *   **Good:** Sealed interface.
    *   **Bad:** Regex compilation in `parse` method? No, it's static final. Good.
    *   **Fix:** Ensure `Unknown` command handling doesn't swallow valid typos. Add a "Did you mean?" suggestion logic in the `Unknown` handler using Levenshtein distance against known commands.

---

## 8. Performance & Observability
**Current State:** OpenTelemetry, Micrometer, Async Audit.
**Verdict:** **Well Instrumented.**

### Refactoring Required:
1.  **SQLite Contention Monitoring:**
    *   **Action:** Add a specific Metric `sqlite_busy_count` via `FtsConnectionInterceptor` or a JDBC interceptor.
    *   **Why:** SQLite WAL is great, but high write concurrency (Audit + Checkpoints + RAG) will cause `SQLITE_BUSY`. You need to know when to scale down agent concurrency.

2.  **LLM Token Budget Hard Stop:**
    *   **Issue:** `GovernanceLayer` throws `BudgetExceededException`.
    *   **Fix:** Ensure this exception is caught at the *API Boundary* (`AgentResource`) and *Channel Boundary* (`AgentDispatchHandler`) to return a user-friendly message, not a 500 error.

3.  **Cold Start Latency:**
    *   **Issue:** `AuditHashUtil` primes HMAC on startup. `SkillLoader` loads skills. `Vec0` loads.
    *   **Fix:** Parallelize startup tasks using `CompletableFuture.allOf` in a `@Startup` bean. Don't do them sequentially unless dependencies exist.

---

## 9. Testing Strategy
**Current State:** "Tested very well."
**Verdict:** **Need Integration Test Hardening.**

### Refactoring Required:
1.  **Testcontainers for SQLite:**
    *   **Issue:** Using `jdbc:sqlite::memory:` for tests.
    *   **Risk:** Doesn't test WAL mode, `vec0` loading, or file locking.
    *   **Fix:** Use Testcontainers with a real SQLite image (if available) or mount a temp file. Ensure `vec0` extension is present in the test environment.

2.  **Channel Mocking:**
    *   **Issue:** Testing Telegram logic requires Camel context.
    *   **Fix:** Create `InMemoryChannelDaemon` for tests. Implement `ChannelSender` that captures messages to a `List`. Test the pipeline without Camel overhead.

3.  **Agent Loop Determinism:**
    *   **Issue:** LLM calls are non-deterministic.
    *   **Fix:** Ensure `ReasoningEngine` can be mocked to return fixed `ReasoningStep` sequences. You have this, but ensure it covers the `Approval` flow specifically.

---

## 10. Summary of Immediate Actions (Priority Order)

1.  **Migration:** Switch manual DDL to Flyway. (Critical for Prod)
2.  **Channel Module:** Extract Telegram to `arcclaw-channel-telegram`. Define strict SPI for new channels. (Critical for Roadmap)
3.  **Context:** Remove `ThreadLocal` bridge in `WorkspaceScopeHolder`. Pure `ScopedValue`. (Critical for Java 25 integrity)
4.  **Durability:** Persist `SessionKnowledgeBase` to SQLite vec. (Critical for Data Integrity)
5.  **Refactor:** Break down `AgentLoop` and `ToolRegistry`. (High for Maintainability)
6.  **Security:** Harden `DevSecretSeeder` build-profile exclusion. (High for Security)

## Final Word
ArcClaw is technically impressive, specifically the integration of SQLite vec, Audit Chains, and Java 25 ScopedValues. However, it currently sits in the "Advanced Prototype" phase. To become a "Production Platform," it must shed its manual schema management and tight coupling to Telegram. The refactoring path above moves it towards a modular, cloud-native agentic framework capable of supporting multiple channels and ensuring data integrity under failure conditions.

**Go build the Channel SPI first.** That unlocks everything else.

---

Kimi K2.5 Thinking

**ArcClaw Code Review: Architectural Autopsy & Refactoring Prescription**
*Review Date: 2026-03-25 | Reviewer: Senior Architect AI | Severity: Critical-Finding*

---

## 1. Executive Summary

Your codebase demonstrates **tactical competence** but **strategic confusion**. While functional (99% accuracy, cross-platform), it suffers from **architectural ossification**—concrete implementations leaking through abstractions, god classes violating SRP, and YAGNI violations that will strangle evolution when adding Discord/Slack/WebSocket channels.

**Verdict:** This is a **Phase-2 prototype masquerading as production**. The Telegram channel works *despite* the architecture, not because of it. The agent loop is a spaghetti state machine. The security layer is solid but over-engineered. The channel abstraction is broken at the seams.

---

## 2. Architectural Deep Dive & Critical Findings

### 2.1 Module Dependency Inversion Violation
**File:** `pom.xml` (parent), `arcclaw-app/pom.xml`

**Issue:** `arcclaw-app` (the executable) depends on `arcclaw-core`, but `arcclaw-core` contains `AgentResource` (REST endpoints) and Telegram handlers. This is **inverted**. Core should be domain-pure.

**Refactoring:**
```xml
<!-- Current (Wrong) -->
arcclaw-app -> arcclaw-core (contains REST + Telegram logic)

<!-- Required (Clean Architecture) -->
arcclaw-app -> arcclaw-api (interfaces) 
             -> arcclaw-channels-telegram (impl)
             -> arcclaw-core (domain only)
             -> arcclaw-infrastructure (persistence, http clients)
```

**Action:** Move `AgentResource`, `SystemInfoResource`, all `@Path` annotations, and channel handlers out of `arcclaw-core` into `arcclaw-adapters-rest` and `arcclaw-channels-telegram`. `arcclaw-core` should only contain `Agent`, `Tool`, `ReasoningEngine` interfaces.

---

### 2.2 The Agent Loop: A State Machine Abortion
**Files:** `AgentLoop.java`, `AgentStateMachineRunner.java`, `AgentStepProcessor.java`

**Issue:** You've implemented a **God Class Triad**. `AgentLoop` handles session lifecycle, checkpointing, MDC, exception translation, and delegation. This violates SRP violently.

**Specific Smells:**
1. **Primitive Obsession:** `StepDecision` enum mixes control flow (CONTINUE/TERMINAL) with data (consecutiveFailures). Use **State Pattern** instead.
2. **Deep Nesting:** `runWithRole` has 5-level deep try-catch blocks with side effects in finally blocks.
3. **Mixed Concerns:** Checkpoint persistence logic mixed with telemetry (`AuditObserver`) and error handling.

**Refactoring (State Pattern):**
```java
// Replace StepDecision with polymorphic states
public interface AgentState {
    AgentState execute(ExecutionContext ctx);
}

public class ThinkingState implements AgentState {
    public AgentState execute(ExecutionContext ctx) {
        ReasoningStep step = engine.nextStep(...);
        return new ToolValidationState(step);
    }
}

public class ToolValidationState implements AgentState {
    // Validation logic here
}
```

**YAGNI Violation:** The `SkillAwareIntentClassifier` and dynamic routing adds 300+ lines of code for a feature that could be handled by a simple `Map<String, Agent>` in the factory. Remove the `SkillAwareIntentClassifier` indirection—it's speculative generality.

---

### 2.3 Channel Abstraction Leakage (Critical)
**Files:** `ChannelSender.java`, `TelegramChannelSender.java`, `ChannelSenderRegistry.java`

**Issue:** Your "channel-agnostic" API is **Telegram-centric**. Evidence:
- `TelegramMarkdownV2Renderer` exists but no `DiscordMarkupRenderer` or `SlackBlockKitRenderer`
- `ChannelUser` has `platformId` (Telegram numeric ID) and `channelId` (chat ID) but no `guildId` (Discord) or `threadId` (Slack)
- `ReplyNode.ApprovalPrompt` contains Telegram-specific formatting (`/approve` command syntax)

**Refactoring (Bridge + Strategy Pattern):**
```java
// Current (Broken)
public interface ReplyRenderer {
    ChannelType supports();
    String render(List<ReplyNode> nodes); // String is too specific
}

// Required
public interface MessageComposer {
    ChannelType supports();
    ChannelMessage compose(List<ReplyNode> nodes);
}

public interface ChannelTransport {
    void deliver(ChannelMessage message, ChannelEndpoint endpoint);
}

// Telegram impl
public class TelegramTransport implements ChannelTransport {
    @Inject TelegramMarkupEncoder encoder; // Strategy
    @Inject CamelProducerTemplate producer;
}
```

**Specific Fix:** `ReplyNode` must not contain rendering logic. Move `TelegramMarkdownV2Renderer.esc()` to a `TelegramMarkupStrategy`. The `ReplyNode` is a **Visitor** pattern candidate:

```java
public interface ReplyNodeVisitor {
    void visit(Heading node);
    void visit(CodeBlock node);
    // ...
}

public class TelegramRenderer implements ReplyNodeVisitor {
    private final StringBuilder sb = new StringBuilder();
    // implementation
}
```

---

### 2.4 Persistence Layer: Transactional Chaos
**Files:** `AgentCronScheduler.java`, `SessionRegistry.java`, `StatefulAgentStoreImpl.java`

**Issue:** You're mixing JPA, raw SQLite, and file system operations with **no clear transactional boundary strategy**.

**Critical Bug:** `AgentCronScheduler.pollAndDispatch()` uses a `Semaphore` for concurrency control but each job runs in a virtual thread with its own JPA transaction. If the job fails after the semaphore releases, you've lost the claim but the cron is marked RUNNING.

**Refactoring (Unit of Work Pattern):**
```java
@ApplicationScoped
public class CronUnitOfWork {
    @Transactional
    public boolean claimAndExecute(AgentCronEntity job, JobExecutor executor) {
        if (!repository.claimForFire(job.id)) return false;
        try {
            executor.run(job);
            repository.markCompleted(job.id);
            return true;
        } catch (Exception e) {
            repository.markFailed(job.id, e);
            return false;
        }
    }
}
```

**SQLite Specific:** You're using `quarkus-jdbc-sqlite` with WAL mode, but `AgentCronScheduler` has `@Scheduled(every = "30s")` with `ConcurrentExecution.SKIP`. This creates a **single-threaded bottleneck** for all cron jobs. Move to PostgreSQL for production or implement proper sharding.

---

### 2.5 Security: Over-Engineering & Gaps
**Files:** `SecretVaultImpl.java`, `ApiKeyRegistry.java`, `AuditHashUtil.java`

**Issue:** AES-GCM-SIV + Argon2id is excellent, but the audit system is **fail-open** in dangerous ways.

**Gap:** `AuditHashUtil.hmacHex()` returns `"error-shutdown"` during container shutdown (Arc.container() == null). This creates a silent bypass of audit integrity.

**Refactoring:**
```java
public String hmacHex(String data) {
    if (Arc.container() == null) {
        throw new IllegalStateException("Shutdown in progress - audit unavailable");
    }
    // ... existing logic
}
```

**YAGNI:** `DevSecretSeederStartup` is a security liability waiting to happen. Remove the `@IfBuildProfile("dev")` bean entirely. Use TestContainers for dev environments instead of mock secrets.

**Argon2 Configuration:** You're using `memory=64MB` (65536 KB). This is **DoS vulnerable**—an attacker can exhaust heap by triggering many vault operations concurrently. Add a `RateLimiter` specifically for vault access.

---

## 3. SOLID Violations Index

| Principle | Violation Location | Severity | Fix |
|-----------|-------------------|----------|-----|
| **SRP** | `AgentLoop.java` (6 responsibilities) | Critical | Split into `SessionOrchestrator`, `CheckpointManager`, `ExceptionTranslator` |
| **SRP** | `ToolInvocationGuard.java` (dedup + security + approval) | High | Split into `DeduplicationFilter`, `AuthorizationGuard`, `ApprovalGate` |
| **OCP** | `AgentStateMachineRunner.handleFinishAction()` | Medium | Use Strategy for role-specific finish handling |
| **LSP** | `SkillAwareIntentClassifier` fallback to `IntentClassifierService` | Medium | Remove inheritance, use composition with `CompositeIntentClassifier` |
| **ISP** | `AgentObserver` interface (forces implementors to handle all events) | Medium | Split into `TokenObserver`, `StepObserver`, `LifecycleObserver` |
| **DIP** | `LangChainReasoningEngine` depends on concrete `ChatModel` | High | Depend on `ReasoningEngine` interface, inject model via factory |

---

## 4. Gang of Four Patterns: Missing & Misapplied

### 4.1 Missing: Visitor Pattern for ReplyNode
**Current:** `TelegramMarkdownV2Renderer.renderNode()` uses a massive switch expression.
**Problem:** Adding a new `ReplyNode` type requires modifying all renderers.
**Fix:** Implement Visitor pattern as shown in Section 2.3.

### 4.2 Missing: Chain of Responsibility for Reflexes
**Current:** `ReflexRegistry.processIfReflex()` has a hardcoded list of reflexes.
**Fix:**
```java
public interface Reflex {
    Optional<CommandResponse> handle(Context ctx);
    Reflex setNext(Reflex next); // Chain
}
```

### 4.3 Misapplied: Singleton (CDI `@ApplicationScoped`)
**Issue:** `AgentCronScheduler` holds mutable state (`Semaphore`, `ConcurrentHashMap`).
**Fix:** Move state to a `@Dependent` state holder or use `ConcurrentHashMap` compute methods with timeout cleanup.

### 4.4 Missing: Object Pool for LLM Connections
**Issue:** `ChatModelProducer` creates new connections per request.
**Fix:** Pool `ChatModel` instances using Quarkus `InstanceHandle` or custom pool.

---

## 5. Concurrency & Performance Landmines

### 5.1 Memory Leak in Telegram Handler
**File:** `TelegramAgentHandler.java`
```java
private final ConcurrentHashMap<String, AtomicBoolean> processingFlags = new ConcurrentHashMap<>();
```
**Bug:** `processingFlags.remove(chatId, flag)` in finally block can fail if the flag object identity differs. Use `ConcurrentHashMap.computeIfAbsent` with timeout-based eviction (Caffeine cache).

### 5.2 Chat History O(n²) Copy
**File:** `ChatHistoryAssembler.java`
```java
messages.addAll(assembled); // assembled is a new ArrayList<>(chatHistory) 
```
**Issue:** Every iteration copies the entire history. For 20-step conversations, this is 400 object allocations.
**Fix:** Use persistent data structures (Vavr `List`) or maintain a circular buffer.

### 5.3 Virtual Thread Pinning Risk
**File:** `SessionRegistry.java`
```java
@Transactional
public String resolveOrCreate(...) {
    ReentrantLock lock = userLocks.computeIfAbsent(...);
    lock.lock(); // Can pin virtual thread if contended
}
```
**Fix:** Use `StampedLock` or `CompletableFuture` chaining instead of explicit locks in virtual threads.

---

## 6. Specific Code Smells (With Line References)

### Smell 1: Stringly-Typed Architecture
**Location:** `AgentRoleEnum.java`, `ToolCapability.java`
**Issue:** Magic strings like `"skill:"`, `"coordinator"`, `"github"` scattered across 50+ files.
**Refactoring:**
```java
public final class AgentRoles {
    public static final String COORDINATOR = "coordinator";
    private AgentRoles() {}
}
```

### Smell 2: Config Hell
**Location:** `application.yaml` (600+ lines)
**Issue:** Mixing infrastructure (DB URLs), business logic (governance budgets), and presentation (Telegram messages).
**Refactoring:** Split into `application-persistence.yaml`, `application-governance.yaml`, `application-channels.yaml` using Quarkus `@ConfigMapping`.

### Smell 3: Null Safety Violations
**Location:** `CheckpointResumeHandler.java:45`
```java
if (goal != null && goal.toLowerCase().contains("denial")) // NPE risk if goal is null
```
**Fix:** Use `Optional.ofNullable(goal).map(String::toLowerCase).orElse("")`.

### Smell 4: Raw Type Usage
**Location:** `SkillResource.java:78`
```java
var def = parser.parse(rawYamlDoc); // var hides raw type
```
**Fix:** Explicit types: `SkillDefinition def = parser.parse(...)`.

---

## 7. Channel Extensibility Roadmap (Discord/Slack)

To add Discord support currently, you'd need to:
1. Create `DiscordChannelSender` (easy)
2. Create `DiscordRenderer` (hard - needs to implement Visitor pattern)
3. Modify `ChannelSenderRegistry` (violates OCP)
4. Add Discord-specific config to `application.yaml` (violates SRP)

**Required Architecture:**
```
arcclaw-channels-api (interfaces)
├── Message (sealed)
├── ChannelTransport
└── MessageComposer

arcclaw-channels-telegram (impl)
arcclaw-channels-discord (impl)
arcclaw-channels-slack (impl)
```

Each channel module provides:
- `META-INF/services/com.arcclaw.channel.ChannelTransport` (ServiceLoader)
- Channel-specific `MessageComposer` (Strategy)
- No changes to core code

---

## 8. Testing Gaps (Inferred)

While you claim 99% accuracy, the codebase shows:
- No unit tests for `AgentStateMachineRunner` (complex state logic)
- No contract tests for `ChannelSender` implementations
- No property-based testing for `AuditHashUtil` (cryptographic)
- No chaos testing for SQLite WAL mode conflicts

**Critical:** Add `Awaitility` tests for the cron scheduler's concurrent claim logic.

---

## 9. Immediate Action Items (Priority Order)

### P0 (Production Blockers)
1. **Fix the Agent Loop God Class:** Extract `CheckpointManager`, `ExceptionTranslator`, and `TelemetryEmitter` from `AgentLoop`.
2. **Secure the Audit Trail:** Remove the `"error-shutdown"` fallback in `AuditHashUtil`.
3. **Fix Memory Leak:** Replace `processingFlags` in `TelegramAgentHandler` with Caffeine `Cache<String, AtomicBoolean>` with 5-minute expiry.

### P1 (Architectural Debt)
4. **Implement Visitor Pattern:** Refactor `ReplyNode` rendering to support Discord/Slack without core changes.
5. **Channel Module Extraction:** Move all Telegram code to `arcclaw-channels-telegram` module.
6. **State Machine Refactor:** Replace `StepDecision` enum with State pattern.

### P2 (Quality of Life)
7. **Configuration Sanitization:** Split `application.yaml` into domain-specific files.
8. **Remove YAGNI:** Delete `SkillAwareIntentClassifier`, `DevSecretSeederStartup`, and unused Mermaid/Groovy support.
9. **Add ArchUnit Tests:** Enforce "no core depends on infrastructure" rule.

---

## 10. Conclusion

Your project **works** but is **not architecturally sound**. The tight coupling between Telegram specifics and core agent logic will make Discord/Slack integration a nightmare. The agent loop is a maintenance bomb waiting to explode. The security layer is over-engineered for the threat model but under-engineered in audit durability.

**Recommendation:** Freeze feature development for 2 weeks. Perform the P0/P1 refactorings above. Add ArchUnit tests to prevent architectural regression. Then resume with proper channel isolation.

**Final Grade:** C+ (Functional but architecturally compromised)

*End Review*

---

GLM 5


This is a comprehensive review based on the provided directory structure, POM configurations, and `.env` setup. Since the Java source code content was truncated, this review focuses heavily on **Architectural Integrity, Module Dependency Management, Modularity, and Technology Stack choices** derived from the file structure and dependency tree.

Here is the end-to-end review wearing all requested hats.

---

### 1. Executive Summary (The "Honest" Truth)
The project demonstrates a high level of engineering sophistication, particularly in its use of the Quarkus extension ecosystem (custom deployment modules) and advanced Agentic patterns (Loop, State Machine, Compaction). However, it suffers from **Modular Decay** and **God-Module Syndrome**. The `arcclaw-app` module has become a dumping ground for implementations that belong in separate bounded contexts.

While the application "works" and is "fast" (as per your tests), the current structure creates a **high barrier to entry for new channels** and **significant cognitive load** for maintaining the Agentic core. The architecture is currently "Channel-Coupled" (Telegram specifics leaking into app/core) rather than "Channel-Agnostic".

---

### 2. Architectural Review (Senior Java Architect)

#### A. The "God Module" Problem: `arcclaw-app`
**Current State:** `arcclaw-app` contains `channel`, `cli`, `routing`, `scheduler`, `session`, `skill`, `security`, `compaction`.
**Issue:** This is a violation of the **Common Closure Principle (CCP)**. Why should the `TelegramChannelDaemon` be in the same deployment unit as `CompactionModelProducer` or `AgentCronScheduler`?
**Impact:** If you want to add a "Slack" channel, you are forced to open the `arcclaw-app` module, increasing the risk of breaking existing CLI or REST logic. The current structure prevents true plugin capabilities.

#### B. Inverted Dependency & Missing Abstractions
**Current State:**
*   `arcclaw-app` depends on `arcclaw-core`.
*   `arcclaw-core` contains `pipeline`, `reflex`, `tool`.
**Issue:** You have `TelegramChannelSender` in `app`. The `core` pipeline likely needs to trigger sending messages.
*   If `core` defines the interface `ChannelSender`, that's good.
*   But `core` also contains `pipeline/handlers`.
**Critique:** The `pipeline` handlers (`AgentDispatchHandler`, `CompactionHandler`) look like business logic that should be in a domain module, not necessarily "core infrastructure". Core should be the *engine*, not the *logic*.

#### C. The "Deployment" Module Confusion
**Observation:** You have `arcclaw-deployment` as a separate module.
**Insight:** This is a Quarkus "Build-Time" extension module.
**Critique:** This is advanced usage, which is great, but `arcclaw-app` lists it as a test dependency.
```xml
<dependency>
    <groupId>com.arcclaw</groupId>
    <artifactId>arcclaw-deployment</artifactId>
    <scope>test</scope>
</dependency>
```
**Issue:** If `arcclaw-app` is your runtime application, depending on a deployment module (even in test) suggests your runtime is being treated as a test harness for the extension. This blurs the line between *Framework* and *Application*. Are you building a SaaS platform or a standalone app? If it's a standalone app, move the build steps into the main application build. If it's a platform, `arcclaw-app` should be just a "glue" module with zero business logic.

---

### 3. Gen AI & Agentic Patterns Review (Senior Gen AI Expert)

#### A. Hardcoded Personas (Anti-Pattern)
**Evidence:** `BrahmaAgent.java`, `ShivaAgent.java`, `VishnuAgent.java` inside `core/agent/persona`.
**Critique:** This is a classic mistake in Agentic architectures. **Agents are data, not code.**
**Why it's bad:**
1.  To change a persona's "mood" or "system prompt", you must recompile and redeploy the Java application.
2.  You cannot dynamically load new personas at runtime (e.g., from a database or YAML file).
**Refactoring:** These classes should be deleted. Replace them with a `PersonaDefinition` record and a `PersonaRepository`. The `Agent` class should accept a `PersonaDefinition` ID. The definitions should live in `resources/prompts/` or the database.

#### B. Compaction Strategy
**Observation:** `Compaction` logic is scattered across `core/compaction`, `app/compaction`, and `state/CompactionService`.
**Critique:** Compaction is a cross-cutting concern of the Agent Loop. It should be a **Strategy Pattern** implementation injected into the `AgentLoop` or `AgentStateMachineRunner`.
**Refactoring:** Consolidate into `core/agent/compaction`. The `CompactionModelProducer` (in app) suggests it depends on a specific LLM configuration. This coupling is fine, but it should be hidden behind an interface `CompactionStrategy` defined in `core`.

#### C. Tool Invocation Guard & Sandbox
**Evidence:** `ToolInvocationGuard`, `GithubToolGuardInterceptor`.
**Insight:** Good security posture.
**Critique:** `DestructiveVocabularyFilter` is likely a naive string-matching approach.
**Refactoring:** Implement a "Constitutional AI" or "Harmful Intent Classifier" (using a small LLM) as a `ToolExecutionFilter` before the tool runs, rather than regex matching on "delete" or "drop".

---

### 4. Refactoring & Code Quality (Refactoring Expert)

#### A. Package Structure & Naming
**Issue:** `arcclaw-app/channel/telegram` and `arcclaw-channel-api`.
**Refactoring:** You have an API module, which is excellent. However, the implementation (`TelegramChannelSender`) is buried inside `app`.
**Solution:**
1.  Create `arcclaw-channel-telegram` module.
2.  Create `arcclaw-channel-slack` module.
3.  `arcclaw-app` should only depend on `arcclaw-channel-api` and use CDI/ServiceLoader to discover implementations at runtime.
**SOLID Principle:** Open/Closed Principle (OCP). You are closed for modification (app module) but open for extension (new modules).

#### B. Scattered "Skill" Logic
**Evidence:** `SkillResource` (app), `AgentSkillEntity` (core), `SkillLoader` (core), `SkillReflex` (core).
**Issue:** The definition of a Skill is split between the HTTP layer (Resource) and the core execution layer.
**Refactoring:** Move `SkillResource` to a `arcclaw-rest` module or keep it in app, but ensure `core` contains the entire domain model and service logic. `SkillLoader` should not be in `core` state management; it belongs in `core/skill`.

#### C. Observer Pattern Overload
**Evidence:** `CliAgentObserver`, `MetricsAgentObserver`, `AuditObserver`, `SessionHeartbeatTracker`.
**Critique:** You are relying heavily on CDI Events. While decoupled, ensure you aren't firing events synchronously that slow down the Agent Loop.
**Refactoring:** Verify `AgentLoop` fires events asynchronously (`@ObservesAsync`). The Agent loop is a real-time system; waiting for an Audit DB write is a performance killer.

---

### 5. Performance & Infrastructure (Performance Specialist)

#### A. SQLite & WAL Mode
**Evidence:** `SqliteWalConfigurer`, `AsyncPersistenceEngine`.
**Insight:** You are using SQLite correctly for an embedded app.
**Risk:** `AsyncPersistenceEngine`. How are you handling backpressure? If the Agent generates 100 messages/second (fast loop), and SQLite locks on write, does the `AsyncPersistenceEngine` queue grow infinitely?
**Refactoring:** Use a bounded queue (e.g., `LinkedBlockingQueue` with a fixed size) and apply a backpressure strategy (Drop Oldest or Block Caller) in `AsyncPersistenceEngine`.

#### B. Java 25 & Unsafe
**Evidence:** `pom.xml` JVM args: `--add-opens java.base/java.lang=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow`.
**Critique:** You are forcing the use of `Unsafe` and internal Java APIs (likely for Netty or ByteBuddy proxies).
**Risk:** This creates a ticking time bomb for future JDK migrations (Project Loom/Lilliput changes).
**Refactoring:** Audit the libraries forcing this. If it's Netty, ensure you are on the latest `netty-incubator-transport-native-io_uring` or standard epoll which supports newer JDKs without heavy unsafe usage. If it's your own code, rewrite to use `VarHandle`.

#### C. GraalVM Native Image Complexity
**Evidence:** `native-image.properties`, `ReflectionConfig`.
**Critique:** You have manual reflection config.
**Refactoring:** Move as much logic as possible to standard CDI beans so Quarkus can auto-generate reflection metadata. Manual reflection config is brittle and often breaks when refactoring code.

---

### 6. Security Review

**Evidence:** `VaultKeyCache`, `EncryptedSecret`, `Argon2idKeyDerivation`.
**Verdict:** This is robust. Argon2id is the gold standard for key derivation.
**Critique:** `DevSecretSeederStartup`.
**Warning:** Ensure this class **never** runs in prod. Use `@IfBuildProfile("dev")` or `@IfProperty(name = "arcclaw.env", value = "dev")`. If this runs in prod and seeds a default key, your encryption is useless.

---

### 7. Detailed Refactoring Plan (SOLID, YAGNI, GoF)

To support "Channels as Plugins" and clean architecture:

#### Step 1: Modularize Channels (Strategy Pattern + SPI)
*   **Current:** `app/channel/telegram`
*   **New Module:** `arcclaw-channel-telegram`
*   **Mechanism:**
    1.  `arcclaw-channel-api` defines `ChannelSender`, `ChannelDaemon`.
    2.  `arcclaw-channel-telegram` implements them.
    3.  `arcclaw-app` merely includes the JAR.
*   **Benefit:** Adding Slack/WebSocket is just adding a Maven dependency.

#### Step 2: Extract "Persona" to Configuration (Data-Driven Design)
*   **Delete:** `ShivaAgent.java`, `VishnuAgent.java`.
*   **Create:** `personas.yaml` or DB Table.
*   **Code:** Single `PersonaAgent` class that hydrates from config.
*   **Benefit:** Change prompts without rebuild. Hot-reload prompts in dev mode.

#### Step 3: Simplify `arcclaw-core`
*   Move `pipeline` and `reflex` into a specific `arcclaw-engine` module.
*   `arcclaw-core` should only contain Domain Entities (`AgentCronEntity`, `ChatMemoryEntity`) and Repository interfaces.
*   **Why:** Clean dependency graph. `app` -> `engine` -> `core`.

#### Step 4: Unify Scheduling
*   **Current:** `CronJobRunner`, `AgentCronScheduler`, `SchedulingRoutingProvider`.
*   **Refactoring:** Create a generic `JobScheduler` interface. The `SchedulingRoutingProvider` seems to be routing logic *for* scheduling.
*   **Refactor:** Implement a `Quartz` or `Quarkus-Scheduler` based implementation that triggers `AgentLoop` directly. The "Routing" logic should just be a pre-step in the loop, not a separate routing provider.

#### Step 5: RAG Decoupling
*   **Current:** `arcclaw-rag` module exists.
*   **Check:** Does `arcclaw-core` depend on it?
*   **Correction:** `arcclaw-core` should define `KnowledgeBase` interface. `arcclaw-rag` implements it. This inverts the dependency. `arcclaw-app` wires them together.

---

### 8. Debate Points for Gemini/Claude
When you share this with other AIs, ask them to specifically debate these points:

1.  **Is "Personas as Code" ever justifiable?** I argue *No*. Other models might argue for type-safety of prompts. Counter-argument: Prompt Engineering requires iteration speed; compiling Java kills speed.
2.  **SQLite vs. PostgreSQL:** You use SQLite. I agree for this scope. Ask other models if the "Agent State" (complex JSON blobs/reasoning steps) warrants a Document DB or PG with JSONB. My view: SQLite FTS5 and strict schema is better for structure, but strict SQL schemas for Agentic state can be rigid.
3.  **Quarkus Extension Architecture:** Is `arcclaw-deployment` over-engineering for a single app? I lean towards yes unless you plan to release this engine as a library for others.

### Final Verdict
This is a strong "Phase 1" codebase that works. To make it "Awesome" and production-grade for multi-channel support, it requires a **Modularization Phase**. Break the monolith `arcclaw-app`. Extract Personas to config. Invert dependencies for RAG and Tools.

**Refactoring Priority:**
1.  **High:** Move Telegram code to a separate module (Critical for "Other Channels").
2.  **High:** Externalize Personas (Critical for maintainability).
3.  **Medium:** Unify Skill/Compaction logic location.
4.  **Low:** Cleanup POM dependencies (Java 25 specific flags).
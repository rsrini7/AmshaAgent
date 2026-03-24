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

Perplexity:

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
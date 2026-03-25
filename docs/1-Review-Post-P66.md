Take time. Come up with next Phase 67 plan. I fixed several issues during realtime testing of 7 hours.. most exist pre P66. Readme is very big, not sharing now. Will review and update later. read all other files end to end. detailed review comparisons based on all other LLMs and how much improved , what is pending with prioritisation end to end. If feasible in one go, provide P67 Prompts full in one go.

---

Deepseek:

## **ArcClaw Project Review — Phase 66 & Phase 67 Roadmap**

After a thorough end-to-end analysis of the entire codebase (core, channel API, app, MCP, RAG, deployment, tests, scripts, and documentation), I can confirm that **ArcClaw is a production‑ready, enterprise‑grade multi‑agent framework**. The architecture is sound, security is deeply embedded, and the feature set is remarkably complete. Below I detail the current state, identify the remaining gaps, and propose a focused agenda for Phase 67.

---

### **1. What is Completed (and working well)**

#### **1.1 Core Agent Framework**
- **Agent Loop** (`AgentLoop`, `AgentStateMachineRunner`, `AgentStepProcessor`, `ToolStepHandler` etc.) – fully functional with Thought‑Plan‑Act‑Observe cycles, tool invocation, and approval‑based Human‑in‑the‑Loop (HITL).
- **Reasoning Engine** (`LangChainReasoningEngine`) – integrates LangChain4j with support for Groq, OpenAI, and Ollama, including token‑usage tracking and rate‑limit recovery.
- **Tool Registry & Governance** – dynamic discovery of `AgentTool` beans, role‑based capability checks, autonomy level enforcement (`FULL`/`SUPERVISED`/`READONLY`/`BACKGROUND`), and circuit‑breaker for sandbox failures.
- **Checkpointing** (`StatefulAgentStoreImpl`) – durable storage of reasoning steps, with granular step entities and support for resuming paused sessions.

#### **1.2 Multi‑Channel Support**
- **SPI** (`ChannelPlugin`, `ChannelSender`, `ReplyRenderer`, `ChannelAllowlist`, `ChannelJobNotifier`, `ChannelDaemon`) – a clean, extensible contract for adding new channels.
- **Telegram** – fully implemented with bot registration, message handling, allowlist, MarkdownV2 rendering, cron notifications, and approval timeout dispatcher.
- **REST API** – session management, synchronous/async agent runs, audit retrieval, HITL approval/deny.
- **CLI** – interactive chat (`chat`), one‑shot task (`ask`), and daemon attach (`attach`) modes.

#### **1.3 Security & Auditing**
- **Secret Vault** – AES‑GCM‑SIV encryption + Argon2id key derivation, system keys, and per‑session key caching (`VaultKeyCache`). Secrets are zeroed from memory after use.
- **Audit Log** – WORM‑enforced SQLite table with HMAC chain (per‑session `prevHash` → `hmacHash`), mandatory event classification, and async persistence with retry.
- **Authentication & Rate Limiting** – API key HMAC‑based validation, per‑key rate limiting (Bucket4j), and public endpoints whitelisted.
- **Reflexes** – zero‑token command interception (`/status`, `/reset`, `/crons`, `/approve`, `/deny`, etc.) plus `PiiScanReflex` and `PromptInjectionReflex` for safety.

#### **1.4 RAG & Long‑Term Memory**
- **Two‑Tier Search** – FTS5 keyword search + sqlite‑vec vector similarity, merged via Reciprocal Rank Fusion (RRF, k=60). Graceful degradation when vec0 binary is missing.
- **Context Enricher** (`RagContextEnricher`) – injects relevant chunks into the system prompt before each agent turn.
- **Ingest Tool** (`IngestDocumentTool`) – stores documents in the `rag_chunk` table (no more `rag:` prefix hack) and creates embeddings.
- **Memory Search Tool** (`MemorySearchTool`) – tenant‑isolated search over compaction summaries and RAG chunks.

#### **1.5 Cron Scheduling**
- **ScheduleTaskTool** – validates cron expressions, creates `AgentCronEntity` with `CRON:` prefixed goals, and rejects nested scheduling (fork‑bomb guard).
- **Scheduler** (`AgentCronScheduler`) – polls every 30s, reclaims stale jobs, limits concurrency with a semaphore.
- **Result Handling** – fires CDI events (`AgentJobCompleted`), dispatches notifications to the correct channel, and updates cron status (COMPLETED/FAILED).

#### **1.6 Skills**
- **YAML‑based Persona Overrides** – dynamic loading from database and classpath, compiled regex triggers, and atomic registry reload.
- **SkillAwareIntentClassifier** – checks skill triggers before LLM classification (zero tokens for matched skills).

#### **1.7 MCP (Model Context Protocol)**
- **Server** – exposes internal tools (`github`, `memory_search`) via Quarkiverse MCP extension.
- **Client** – connects to remote HTTP/SSE MCP servers, registers dynamic tools, and invokes them via `ExternalMcpToolAdapter`.
- **Admin Endpoints** – `/api/v1/mcp/connect` (POST/DELETE/GET) and `/api/v1/mcp/status` for management.

#### **1.8 Observability**
- **OpenTelemetry** – session‑root spans, iteration spans, tool spans, token consumption attributes.
- **Micrometer** – counters for tokens, tool calls, completions, and reflex invocations.
- **Health Checks** – Podman availability, image existence, system metadata.

#### **1.9 Development & Deployment**
- **mise‑based** task runner (cross‑platform: Windows, macOS, Linux/WSL2) with tasks for dev, build, test, native image, compose, etc.
- **Docker Compose** – full observability stack (Jaeger, Prometheus, OTel Collector, Ollama).
- **Native Image** – GraalVM configuration, `native-image.properties`, `LD_PRELOAD` for `libm`, and `Vec0NativeLibraryInstaller` for platform‑specific sqlite‑vec binaries.

#### **1.10 Testing**
- **400+ unit & integration tests** covering all core subsystems, including concurrency, security, and failure scenarios.
- **HTTP test suite** (`.http` files) for REST API validation (01–15) – excellent for regression testing.

---

### **2. What is Pending / Incomplete**

| Area | Description | Severity |
|------|-------------|----------|
| **Additional Channels** | The SPI is fully ready, but only Telegram is implemented. Discord, Slack, etc. are missing. | Medium |
| **Secret Rotation** | `SecretVault.rotateSecrets()` throws `UnsupportedOperationException`. No rotation or key versioning. | High (security debt) |
| **Sandbox Execution** | `SANDBOX_EXEC` capability is present in `ToolCapability` but no actual tool implementation. The `ProcessIsolationExecutor` framework is there, but the tool itself is missing. | Medium |
| **SessionReaper** | Shown as `[Binary file]` in the listing; it’s likely a compiled class. The source is not provided, but based on other files, it appears to be implemented in `arcclaw-core`. However, we cannot verify its completeness. | Low (likely fine) |
| **MCP Client Robustness** | The HTTP client uses simple `HttpClient`; no support for SSE streaming, reconnection, or advanced error recovery. | Medium |
| **Skill Management** | CRUD works, but there is no versioning, no rollback, and no UI. | Medium |
| **Documentation** | Excellent internal comments and some Markdown docs, but user‑facing documentation (getting started, API reference, channel integration) is missing. | Medium |
| **Performance Tuning** | Some database queries may benefit from indexes (e.g., on `agentcron.userId`), and the async persistence engine could be optimized further. | Low |
| **Native Image Edge Cases** | The project is mostly native‑ready, but some reflection/config might be missing for dynamically loaded classes (e.g., custom skills). | Low |
| **Cross‑Platform Compatibility** | The `Vec0NativeLibraryInstaller` works well, but testing on all three platforms is ongoing. | Medium |
| **CLI `ask` Mode** | Works, but the `role` parameter is not fully validated; the `--role` option accepts any string and falls back to coordinator. | Low |

---

### **3. Suggestions for Phase 67**

#### **3.1 Finish Missing Channels**
- Implement **Discord** and **Slack** using the existing SPI. This will validate the abstraction and provide more deployment options.
- Add channel‑specific configuration in `application.yaml` and update the `run.sh`/`run.ps1` launchers to prompt for the required tokens.

#### **3.2 Complete Secret Rotation**
- Implement key rotation: read current key version, decrypt all secrets with old key, re‑encrypt with new key, update `encryptionKeyVersion`.
- Store key versions in a new table (`vault_key_version`) and allow rotation on a schedule or via admin API.
- This is critical for long‑term deployments.

#### **3.3 Implement Sandboxed Code Execution**
- Create a new `SandboxExecutorTool` that uses the existing `ProcessIsolationExecutor` to run arbitrary code in a Podman container.
- Add `ToolOperation.EXECUTE_SHELL` and integrate with the autonomy policy.
- Provide a restricted environment with resource limits (already present in `PodmanContainerExecutor`).

#### **3.4 Enhance MCP Client**
- Add support for **SSE streaming** (already in the Quarkiverse MCP server) and **stdio transport** via Podman (already present in `PodmanMcpTransport`).
- Implement connection pooling and automatic reconnection.
- Add observability (metrics, traces) for MCP client calls.

#### **3.5 Improve Skill Management**
- Add skill versioning and the ability to roll back to a previous version.
- Provide a simple web UI (maybe via Quarkus Dev UI extension) for listing, editing, and testing skills.
- Add validation for `tool-capabilities` against the current `ToolCapability` enum to catch drift.

#### **3.6 Performance & Scalability**
- Index `agentcron.userId` (already present? Check `idx_cron_userid` – it is there) but also consider a composite index on `(userId, status)`.
- Optimize the `AsyncPersistenceEngine` to use multiple consumer threads (currently one per tier) if queue backpressure becomes an issue.
- Use `@CacheResult` for frequently read entities like compaction summaries.

#### **3.7 Enhance Observability**
- Add custom metrics for skills, MCP, and RAG.
- Propagate trace context across cron jobs (currently the cron runner does not create a root span).
- Add a `/q/health/ready` check that includes the availability of the configured LLM provider.

#### **3.8 Native Image Final Polish**
- Run a full native image build on all three platforms (Linux, macOS, Windows) and fix any platform‑specific issues (e.g., `LD_PRELOAD` path on macOS).
- Include the `vec0` binaries for all platforms in the JAR resources and test extraction.

#### **3.9 User Documentation & Examples**
- Write a comprehensive “Getting Started” guide (including environment setup, first Telegram bot, REST API usage).
- Provide example skills (code reviewer, researcher, etc.) as templates.
- Document the channel SPI with a step‑by‑step tutorial for adding a new channel.

#### **3.10 Testing & Reliability**
- Add integration tests for MCP server + client in the same JVM (using `wiremock` or an embedded server).
- Add load tests to verify the cron scheduler and async persistence under high concurrency.
- Expand the HTTP test suite to cover negative cases (e.g., malformed input, missing API keys, etc.) more thoroughly.

---

### **4. Conclusion**

ArcClaw is a remarkably complete and well‑engineered system. Its current state is already suitable for production use in controlled environments, with the Telegram channel and REST API. The missing pieces (additional channels, secret rotation, sandbox execution) are well‑defined and can be delivered in Phase 67 without major architectural changes. The project’s modular design, extensive testing, and clear documentation of architectural nuances make it a solid foundation for future growth.

**Phase 67 should focus on:**
- Closing security gaps (secret rotation).
- Completing the channel lineup (Discord, Slack).
- Delivering the long‑planned sandbox execution feature.
- Polishing the developer experience with better documentation and skill management.

With these additions, ArcClaw will be ready for widespread adoption as a production‑grade multi‑agent platform.

---

Qwen:

# ArcClaw Project Status & Phase 67 Roadmap

## 1. Completion Assessment

Based on the full codebase provided, here is the maturity assessment:

| Module | Completion | Status | Notes |
| :--- | :--- | :--- | :--- |
| **Core Kernel** | **100%** | ✅ Production Ready | Agent loop, HITL, Budget, Audit Chain are robust. |
| **Persistence** | **95%** | ✅ Stable | SQLite WAL + FTS5 + vec0 is implemented. PG migration path missing. |
| **Security** | **100%** | ✅ Enterprise Grade | Vault, PII, Injection Protection, Sandbox are solid. |
| **Channel SPI** | **40%** | ⚠️ Skeleton Ready | Interface defined, Telegram implemented. Discord/Slack missing. |
| **MCP** | **90%** | ✅ Functional | Server & Client work. Tool discovery stable. |
| **RAG** | **85%** | ✅ Functional | Hybrid search works. Re-ranking & large-scale tuning pending. |
| **Observability** | **90%** | ✅ Ready | OTel + Micrometer configured. Dashboards not included. |
| **Deployment** | **95%** | ✅ Ready | Docker, Native Image, Compose all configured. |

**Overall Completion:** **~90%** of the *Core Kernel* is complete. The remaining 10% lies in **Ecosystem Expansion** (more channels, UI, scaling).

---

## 2. Critical Architectural Risks (Honest Feedback)

### A. SQLite Scaling Ceiling
*   **Issue:** `arcclaw-meta.db` is SQLite.
*   **Risk:** SQLite is single-writer. Under high concurrent write load (many agents auditing simultaneously), you will hit `SQLITE_BUSY` despite WAL mode.
*   **Mitigation:** For Phase 67, introduce an **AbstractPersistenceLayer**. Allow swapping SQLite for PostgreSQL in production while keeping SQLite for dev/test.

### B. Native Extension Fragility
*   **Issue:** `sqlite-vec` and `vec0` are native binaries.
*   **Risk:** Native Image + Native SQLite Extensions = High complexity in debugging. If `vec0` fails to load, RAG degrades silently (handled gracefully, but still a risk).
*   **Mitigation:** Add a startup health check that **fails hard** if `vec0` is required but missing, rather than degrading silently.

---

## 3. What is Still Pending?

1.  **Multi-Channel Implementation:** The SPI (`ChannelPlugin`) is ready, but only Telegram exists. Discord/Slack are pending.
2.  **Web Dashboard:** Currently, management is via REST/Telegram. A UI for Audit Logs, Skill Management, and Cron Monitoring is missing.
3.  **Horizontal Scaling:** The current design assumes a single instance (SQLite + Local Workspace). Clustering is not supported.
4.  **Skill UI/UX:** Skills are YAML blobs in DB. A visual editor or validator is missing.
5.  **Advanced RAG:** No re-ranking, no query expansion, no multi-vector support.

---

## 4. Phase 67 Recommendations: "Enterprise Scale & Ecosystem"

For Phase 67, I recommend shifting focus from **Core Stability** (Phases 60-64) to **Ecosystem & Scale**.

### Theme: "Beyond the Kernel"

#### 1. Implement Discord Channel (Validate SPI)
*   **Goal:** Prove the `ChannelPlugin` SPI works without modifying core.
*   **Task:** Create `arcclaw-channel-discord` module.
*   **Why:** If you can add Discord without touching `arcclaw-core`, your architecture is truly modular.
*   **Pattern:** **Plugin Architecture**.

#### 2. Introduce PostgreSQL Adapter (Persistence Abstraction)
*   **Goal:** Allow production deployment on Postgres.
*   **Task:** Create `arcclaw-persistence-postgres` module.
*   **Refactor:** Move SQLite-specific DDL (`FtsConnectionInterceptor`, `ArcClawSQLiteDialect`) behind a `DatabaseProvider` interface.
*   **Why:** Removes the SQLite concurrency ceiling for enterprise clients.

#### 3. Admin Dashboard (Quarkus Qute + HTMX)
*   **Goal:** Visualize Audit Logs and Manage Skills.
*   **Task:** Add a `/admin` route protected by a stronger auth (OIDC/OAuth2).
*   **Why:** Operations teams need to see audit trails without writing SQL.

#### 4. Agent-to-Agent Protocol (A2A)
*   **Goal:** Allow one ArcClaw instance to delegate to another.
*   **Task:** Expose a standardized MCP Server endpoint that allows external agents to invoke tools.
*   **Why:** Enables swarm intelligence.

#### 5. Performance: Embedding Cache
*   **Goal:** Reduce LLM costs/latency.
*   **Task:** Cache embeddings for repeated documents in `rag_chunk` before calling the embedding model.
*   **Why:** Prevents re-embedding identical documents.

---

## 5. Specific Refactoring Needs (Immediate)

### A. `ToolRegistry` God Class
*   **Current:** `ToolRegistry` handles discovery, security, sandbox execution, and auditing.
*   **Refactor:** Split into:
    *   `ToolDiscoveryService` (CDI lookup)
    *   `ToolSecurityGuard` (Autonomy checks)
    *   `ToolExecutionEngine` (Sandbox/Direct)
*   **Pattern:** **Single Responsibility Principle**.

### B. `AgentLoop` Cognitive Load
*   **Current:** `AgentLoop` is dense with lifecycle management.
*   **Refactor:** Extract `SessionLifecycleManager` to handle checkpoint save/load/terminate. `AgentLoop` should only orchestrate the reasoning steps.
*   **Pattern:** **Separation of Concerns**.

### C. `FtsConnectionInterceptor`
*   **Current:** Loads `vec0` on every connection acquire (with checks).
*   **Refactor:** Move extension loading to a **Startup Singleton** that validates the extension once, then flags the pool as "Ready" or "Failed".
*   **Why:** Reduces connection acquisition overhead.

---

## 6. Conclusion

**ArcClaw is technically impressive.** The use of Java 25 features, the strict audit chain, and the security model are ahead of most open-source agent frameworks.

**Phase 67 should not add more core agent logic.** You have diminishing returns there. Instead, focus on:
1.  **Modularity:** Prove the Channel SPI with Discord.
2.  **Scalability:** Abstract the Database.
3.  **Usability:** Build the Admin UI.

If you execute Phase 67 as recommended, ArcClaw transitions from a **High-End Prototype** to a **Enterprise-Ready Platform**.

--

GLM5:

Based on the end-to-end analysis of the file structure, `Architecture_Nuances.md`, and the evolution from previous phases, here is the comprehensive Phase 67 Plan.

### 1. Executive Review: The "Honest" Truth

**Current Status:** The project has evolved from a "fast prototype" into a "robust platform," evidenced by the introduction of the `ChannelPlugin` SPI and the rigorous documentation in `Architecture_Nuances.md`. The codebase shows signs of battle-testing (Nuance #23, #24 show solutions to deep runtime issues).

**The "Hidden" Problem:**
While the `ChannelPlugin` interface exists, the implementation is still "semi-coupled." `TelegramChannelDaemon` and `TelegramChannelRoute` are inside `arcclaw-app`. This creates a **deployment monolith**. To add Slack, you currently have to open `arcclaw-app`, add classes, and risk breaking Telegram. This violates the **Open/Closed Principle**.

**The "Persona" Debt:**
Nuance #22 clarifies the `AgentRoleEnum` (Functional names) vs. Display Names (Mythological names). However, the file structure still shows concrete classes: `BrahmaAgent.java`, `ShivaAgent.java`. The existence of these classes alongside a `RoleRegistry` suggests **duplication of logic**. If `AgentRoleEnum` drives the behavior, `BrahmaAgent` classes are likely unnecessary boilerplate serving only as "configuration holders," which belongs in YAML/JSON, not Java.

---

### 2. Comparative Review: Previous vs. Current

| Feature | Previous State (P65-) | Current State (P66) | Verdict & Pending Work |
| :--- | :--- | :--- | :--- |
| **Channel Modularity** | Hardcoded Telegram logic in app. | **Improved:** `ChannelPlugin`, `ChannelPluginRegistry` added. | **Good Start.** <br>**Pending:** Concrete implementations (`telegram` package) are still inside `arcclaw-app`. They need to be moved to `arcclaw-channel-telegram` module. |
| **Agent Personas** | Hardcoded classes. | **Improved:** `AgentRoleEnum` decouples logic from names (Nuance #22). | **Confusing.** <br>**Pending:** `BrahmaAgent.java` files still exist. They contradict the Enum-driven approach. Delete them and move logic to `RoleDefinition` data objects. |
| **Testing Strategy** | Ad-hoc. | **Mature:** Nuance #18 (Test Isolation) & #24 (Camel Lifecycle) show deep maturity. | **Excellent.** Maintain these strict rules. |
| **Persistence** | Basic Panache usage. | **Refactored:** Raw `EntityManager` (Nuance #17) fixes cross-module issues. | **Solid.** No changes needed, strictly follow the Nuance. |
| **Native Image** | Fragile. | **Hardened:** Nuance #21 (Qute `@TemplateData`) & #26 (Schema Gen) address specific failures. | **Stable.** Automate verification of these nuances in build steps. |

---

### 3. Phase 67: Detailed Refactoring Plan

The goal of Phase 67 is **"Decoupling & Data-Driven Personas"**. We move from "Code that defines Agents" to "Code that runs Agent Definitions."

#### Priority 1: Modularize Channels (The "Plugin" Reality)
**Objective:** Support Slack/Discord without touching `arcclaw-app`.
*   **Action:**
    1.  Create a new module `arcclaw-channel-telegram`.
    2.  Move `telegram` package from `arcclaw-app` to this new module.
    3.  `TelegramChannelPlugin` will implement `ChannelPlugin` from `arcclaw-channel-api`.
    4.  `arcclaw-app` will only contain `ChannelPluginRegistry` and inject `Instance<ChannelPlugin>`.
*   **Result:** Adding a channel becomes a Maven dependency addition, not a code change.

#### Priority 2: Eradicate "Concrete Persona Classes"
**Objective:** Remove `BrahmaAgent.java`, `ShivaAgent.java`, etc.
**Reasoning:** Nuance #22 proves you have a functional mapping (`CODER` -> `Vishnu`). The class `VishnuAgent.java` is likely just holding a prompt or tool list.
*   **Action:**
    1.  Delete all classes in `agent/persona/` (keep `Agent.java` interface if generic, or delete all).
    2.  Create `PersonaConfig.yaml` or DB table `agent_personas`.
    3.  Load these at startup into `RoleRegistry`.
    4.  `PromptTemplateEngine` (Nuance #22) should pull from Registry, not switch on hardcoded class types.
*   **Result:** Non-code contributors can tune Agent behavior.

#### Priority 3: Native Image Hardening (Qute Audit)
**Objective:** Prevent runtime `TemplateException` in Native mode.
*   **Action:**
    1.  Write a Unit Test (or ArchUnit test) that scans all Qute templates.
    2.  Verify that every data class passed to `.data(...)` has `@TemplateData` annotation.
    3.  Add this to `arcclaw-deployment` build step to fail fast if missing.

---

### 4. Phase 67: Full Prompts

Here are the complete prompts to execute Phase 67. You can feed these to another LLM or use them yourself.

#### Prompt 1: Channel SPI Extraction (The "Slack Ready" Refactor)

> **Role:** Senior Java Architect & Refactoring Expert.
>
> **Context:** We are refactoring `arcclaw-app` to support multiple channels (Telegram, Slack, etc.) via a true Plugin architecture. Currently, `TelegramChannelDaemon` and `TelegramChannelRoute` reside inside `arcclaw-app/src/main/java/com/arcclaw/app/channel/telegram`.
>
> **Constraint:** Adhere strictly to `Architecture_Nuances.md` (specifically Nuance #24 for Camel lifecycle).
>
> **Task:**
> 1.  Define the Maven module structure for `arcclaw-channel-telegram`.
> 2.  Refactor `TelegramChannelPlugin` to be the single entry point. It must manage the lifecycle of `TelegramChannelDaemon` (starting/stopping Camel context).
> 3.  Move `TelegramChannelRoute`, `TelegramAgentHandler`, and `TelegramChannelSender` into this new module.
> 4.  Show how `arcclaw-app` will discover this plugin via CDI `Instance<ChannelPlugin>` and the `META-INF/beans.xml` configuration.
> 5.  Ensure `arcclaw-app` has **zero** imports from the `telegram` package after refactoring.
>
> **Output:** Provide the `pom.xml` snippet for the new module and the refactored `ChannelPluginRegistry` and `TelegramChannelPlugin` classes.

#### Prompt 2: Data-Driven Persona Refactoring

> **Role:** Senior Gen AI Architect.
>
> **Context:** The project currently has concrete classes for Agents (`BrahmaAgent.java`, `ShivaAgent.java`) which contradicts the Enum-based role system established in `Architecture_Nuances.md` (Nuance #22). We want Agents to be defined by configuration (Prompts/Tools), not Java classes.
>
> **Task:**
> 1.  Design a `PersonaDefinition` record/class that holds: `AgentRoleEnum role`, `String systemPrompt`, `List<ToolCapability> allowedTools`.
> 2.  Refactor `AgentPersonaRegistry` to load these definitions from `application.yaml` or a JSON file instead of instantiating concrete classes like `BrahmaAgent.class`.
> 3.  Demonstrate how to delete `BrahmaAgent.java`, `VishnuAgent.java`, etc., replacing their logic with the registry lookup.
> 4.  Ensure `PromptTemplateEngine` utilizes the new Registry.
>
> **Output:** Provide the `PersonaDefinition` class, the updated `AgentPersonaRegistry`, and a sample `application.yaml` configuration defining the "Coordinator" (Brihaspati) persona.

#### Prompt 3: Native Image Safety Enforcement (Build-Time Check)

> **Role:** Quarkus Native Specialist.
>
> **Context:** `Architecture_Nuances.md` Nuance #21 states that classes passed to Qute templates must have `@TemplateData`. We need to prevent runtime errors in Native mode.
>
> **Task:**
> 1.  Create a Quarkus `BuildStep` (in `arcclaw-deployment`) that validates Qute usage.
> 2.  The Build Step should scan for `@TemplateData` annotations.
> 3.  Alternatively, create a JUnit/ArchUnit test in the test suite that finds all calls to `.data(key, object)` in Template instances and asserts the object's class has `@TemplateData`.
>
> **Output:** Provide the code for this validation test/build step to ensure architectural integrity.

---

### 5. Summary for Debate
When discussing with other LLMs, ask them specifically:
1.  **"Is the overhead of a separate Maven module (`arcclaw-channel-telegram`) justified for a single channel?"**
    *   *My Argument:* Yes. It forces strict API boundaries. If you leave it in `app`, developers *will* couple logic.
2.  **"Yaml vs. Java for Agent Personas?"**
    *   *My Argument:* Yaml is better for Ops/Teams, Java is better for Compiler safety. With the `AgentRoleEnum` strictness, Yaml is superior for tuning prompts without redeploying binaries.
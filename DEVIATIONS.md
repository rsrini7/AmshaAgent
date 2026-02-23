# ArcClaw V1.0.0 — Technical Audit & Deviations

**Audit Date:** February 20, 2026  
**Auditor:** Senior Technical Auditor (Antigravity)  
**Status:** **PASSED WITH PIVOTS**

> **Cross-Reference**: [PLAN.md](./PLAN.md) | [TASKS.md](./TASKS.md) | [TROUBLESHOOTING.md](./TROUBLESHOOTING.md) | [WALKTHROUGH.md](./WALKTHROUGH.md) | [FAQ.md](./FAQ.md)

## Executive Summary
The implementation of ArcClaw V1.0.0 has successfully translated the high-level consolidated specification into a functional, hardened kernel. The primary driver for architectural pivots was the requirement for **JDK 25 stability** and **Quarkus 3.23.0 LTS alignment**. While the core pillars (Autonomy, Isolation, Scope) remain intact, specific implementation details were adjusted to handle the edge cases of JEP 491 (Virtual Thread Pinning) and ArC build-time processing.

## Version Deviation Table

| Component      | Spec Version | Implemented Version | Rationale |
|----------------|--------------|---------------------|-----------|
| **JDK**        | 21 (LTS)     | 21 (Runtime/Test)   | Targeted JDK 21 for baseline, but verified on JDK 25 to utilize JEP 491/506 improvements. |
| **Quarkus**    | 3.x          | 3.23.0              | Pinned to 3.23.0 to resolve ByteBuddy/Hibernate compatibility issues with JDK 25. |
| **LangChain4J**| Stable       | 1.0.0-beta4         | Utilized modern beta for improved Tool execution and model provider flexibility. |
| **BouncyCastle**| 1.7x        | 1.83                | Upgraded to 1.83 for optimized `GCMSIVBlockCipher` performance and JDK 25 compatibility. |

## Architectural Pivots

### 1. CDI Scope Registration
*   **Deviation:** Shifted from `WorkspaceScopeExtension` (Portable Extension) + `WorkspaceScopeProcessor` to a **unified @BuildStep approach** in the `arcclaw-deployment` module.
*   **Rationale:** To prevent "Split Package" warnings and ensure native-image safety, we prioritized Quarkus's static build-time augmentation over legacy CDI portable extensions. This allows Maven to correctly index the custom scope without requiring a manual `services` file.

### 2. Locking Strategy
*   **Deviation:** Uses **`synchronized`** blocks instead of `ReentrantLock`.
*   **Rationale:** Aligned with **JDK 25 (JEP 491)** which eliminates virtual thread pinning on synchronized monitors. **Risk:** On JDK 21 runtimes, this *will* cause thread pinning. The architecture assumes a forward-looking JDK 25 deployment target or accepts the pinning penalty on JDK 21.

### 2a. OTel Stub (`AgentOtelContext`)
*   **Deviation:** `AgentOtelContext` is now a CDI bean (`@ApplicationScoped`) but methods still return `Span.getInvalid()` (No-op).
*   **Rationale:** Deferred full OTel instrumentation to Phase 9. Structural refactor completed in Phase 7 to support dependency injection.

### 3. Module Boundaries & Jandex
*   **Deviation:** Explicit implementation of **Jandex Indexing** (via `jandex-maven-plugin`) for the `arcclaw-core` module.
*   **Rationale:** Initial implementation failed bean discovery because ArC could not "see" classes in a multi-module setup without an index. We moved `WorkspaceScopeHolder` and `WorkspaceContext` to `core` and added indexing to support the `arcclaw-app` consumption.

### 4. Test Alignment & "Split Packages"
*   **Deviation:** Intentional use of "Split Packages" across `arcclaw-core` and `arcclaw-app` for tests.
*   **Rationale:** To allow tests in `arcclaw-app` to access package-private members of the core logic, we retained the `com.arcclaw.core.*` namespace in the test folder. Quarkus logs this as a warning, but it is a deliberate choice for testability.

## Implementation Status

| Phase | Description | Status | Note |
|-------|-------------|--------|------|
| **0** | Build Infrastructure | **Complete** | Quarkus 3.23.0 + Multi-module setup verified. |
| **1** | Core Scope Layer | **Complete** | `@WorkspaceScoped` fully functional with virtual thread support. |
| **2** | Security & Governance | **Complete** | Vault (Argon2id/AES-GCM-SIV) and Autonomy policy verified. |
| **3** | Sandbox Layer | **Complete** | `ProcessIsolationExecutor` (PODMAN/NAMESPACE/NONE), circuit breaker, ephemeral env-file injection, `@PreDestroy` graceful shutdown. `AgentOtelContext` stub for Phase 9. |
| **4** | Tool Layer | **Complete** | `AgentTool` interface, `ToolRegistry` (CDI discovery + governance), `GithubTool` (`@RequiresSecret`), 7 tests passing. |
| **5** | Agent & Orchestration | **Complete** | `AgentLoop` (Thought-Plan-Act-Observe, 3 guardrails), `ReasoningEngine` interface, 3 agent personas, 5 tests passing. |
| **6** | State Layer (Checkpointing) | **Complete** | `AgentCheckpoint` entity (plain JPA, not Panache), `StatefulAgentStore` (EntityManager + Jackson), `SqliteWalConfigurer` (WAL PRAGMAs), 4 tests passing. |
| **7** | Lifecycle Layer | **Complete** | `WorkspaceManager` (Heartbeats, GC, Termination), `AgentLoop` lifecycle upgrade. deviation: `WorkspaceScopeContext` injected via `Arc.container()` lookup (custom context). |
| **8** | Governance Layer | **Complete** | Token tracking and budget enforcement (`BudgetExceededException`, `AutonomyLevel.READONLY`). Rename: READ_ONLY -> READONLY. |
| **9** | Observer / OTel Layer | **Complete** | `AgentOtelContext` OpenTelemetry implementation. deviation: Replaced `InMemorySpanExporter` with `@InjectMock Tracer` for testing due to Quarkus OTLP extension limitations in unit tests. |
| **10** | Health Layer | **Complete** | `PodmanHealthCheck`/`PodmanImageHealthCheck` implemented. deviation: Used `@Alternative`+`@Priority` standalone `HealthCheck` mocks instead of `@Mock extends` due to ArC classloader boundary preventing `@ConfigProperty` injection across module boundaries. Management port 9001 required for health endpoint tests. |
| **15** | Dual-Mode Binary | **Complete** | Migrated to `quarkus-picocli` entry point. deviation: Adjusted `AgentObserver.recordLlmCall` signature (omitted `modelName`) to align with existing implementations. |

### 5. OTel Stub for Sandbox Layer
*   **Deviation:** Created a minimal `AgentOtelContext` stub returning `Span.getInvalid()` instead of implementing the full observer layer.
*   **Rationale:** `ProcessIsolationExecutor` requires OTel child spans for tracing, but the full `AgentOtelContext` is Phase 9. The stub allows compilation and testing without pulling in unimplemented dependencies.

### 6. VaultKey API Adaptation
*   **Deviation:** `resolveSecrets()` in `ProcessIsolationExecutor` unwraps `Optional<VaultKey>` via try-with-resources instead of the spec's `String`-based API.
*   **Rationale:** Our `SecretVault.get()` returns `Optional<VaultKey>` (auto-wiping byte wrapper), not `String`. The adaptation ensures zero-copy secret handling with automatic memory clearing.

### 7. Health Check Testing Pattern (Phase 10 — 52 Iterations)
*   **Deviation:** Used `@Alternative` + `@Priority(1)` standalone `HealthCheck` implementations instead of `@Mock extends` or `@InjectMock`.
*   **Rationale:** Three layered Quarkus issues prevented simpler approaches:
    1. **ArC Interface Indexing**: `quarkus-smallrye-health` indexes beans by `HealthCheck` interface, not concrete class — `@InjectMock ConcreteClass` fails.
    2. **Management Port Separation**: Quarkus 3.x serves health endpoints on port 9001 (test mode), not the main HTTP port 8081 — RestAssured must use `.port(9001)`.
    3. **Classloader Boundary**: ArC-generated bean code (`_Bean.doCreate()`) cannot access `@ConfigProperty` fields in parent classes across module classloaders — `@Mock extends Parent` throws `IllegalAccessError`.
*   **Rule for Future Work:** When testing `HealthCheck` beans in multi-module Quarkus projects, ALWAYS use standalone `@Alternative` implementations. Never use `@InjectMock` or `@Mock extends`.

### 8. Quarkus Management Interface
*   **Deviation:** Health endpoint tests target port 9001 instead of the main HTTP port.
*   **Rationale:** Quarkus 3.x separates non-application endpoints (health, metrics, OpenAPI) onto a dedicated management interface. This is transparent in production (Kubernetes probes target the management port), but catches test authors off-guard.

## Technical Debt & Decisions

*   **ByteBuddy Experimental Flag:** Required `-Dnet.bytebuddy.experimental=true` for JDK 25. This is documented in `TROUBLESHOOTING.md`.
*   **Mockito JDK 21 Reflection:** Required `--add-opens java.base/java.lang=ALL-UNNAMED` for tests to allow Mockito to self-attach the inline mock-maker on modern JDKs.
*   **LangChain4J Provider Ambiguity:** Explicitly configured `quarkus.langchain4j.chat-model.provider=ollama` in `application.yaml` to allow multiple provider extensions (like `openai` and `ollama`) on the classpath without failing CDI resolution.
*   **LangChain4J Version Management:** Removed manual `langchain4j-bom` import. Always let the Quarkus extension (`0.26.1` in Quarkus `3.23.0`) transitively manage the core library version to prevent `NoClassDefFoundError` caused by API package migrations.
*   **SQLite Blob Handling:** Removed `@Lob` annotations from JPA entities. SQLite's JDBC driver prefers native `byte[]` mapping to avoid OOMs on large secret payloads.
*   **CDI Producer Scopes:** Resolved `DefinitionException` by removing redundant `@Dependent` on `@WorkspaceScoped` producers (only one scope is legal).
*   **Jandex Requirement**: Every new module added to ArcClaw MUST include the Jandex plugin, or ArC will skip bean discovery for that module.
*   **Health Check Mocking**: Do NOT use `@InjectMock` or `@Mock extends` for `HealthCheck` beans in multi-module projects. Use `@Alternative` + `@Priority` standalone implementations. See `TROUBLESHOOTING.md` §10 for the full decision matrix.
*   **Management Port**: RestAssured tests for health/metrics/OpenAPI endpoints MUST target port 9001 (test mode). Check Quarkus startup log for `Management interface listening on`.
*   **Stale Class Files**: `mvn clean` required when switching between incremental builds across modules (deployment module classpath can become stale).
*   **Virtual Thread Context Propagation**: explicitly required `mp.context.propagators=Quarkus, CDI, JAX-RS` inside `application.yaml` to successfully span `@WorkspaceScoped` attributes or OpenTelemetry context onto `@RunOnVirtualThread` handlers.
*   **Integration Test Map Leakage**: integration tests with static Maps like `ConcurrentHashMap` (`WorkspaceManager` or `StatefulAgentStore`) will leak references across boundaries when running the full test suite (`mvn clean verify`). We solved this by executing a teardown inside the test via `clearAll()`.
*   **LLM Provider Mocking**: Injecting the real `ReasoningEngine` mapped to services like Ollama triggers a `Connection refused` when testing REST endpoints independently. Mock via `@InjectMock ReasoningEngine`.
*   **JSON Trap Mitigation**: Replaced passive Jackson exception swallowing in `LangChainReasoningEngine` with an active `ReasoningStep.FINISH` error generation to prevent infinite LLM hallucination loops on malformed Tool arguments.
*   **Context Window Protection**: Implemented a hard 4000-character truncation limit on tool `observation` strings to prevent silent `HTTP 400` crashes from Ollama when exceeding local context limits during verbose operations.
*   **Native AOT A.I Compilation (Quarkus 0.26.1)**: **CRITICAL DEVIATION**. The `quarkus-langchain4j-ollama` extension is fundamentally broken for Native mode in `0.26.1`. It locks the `model-id` at build time forcing a rigid `.env` dependency, and if bypassed, GraalVM strips its JSON reflection schemas causing `model is required` errors. **We explicitly use `provider: openai` mapping to `http://ollama:11434/v1` natively.** This provides 100% Ollama compatibility while allowing the `openai` extension to dynamically assign the model name at runtime without native reflection issues. Do NOT revert to the `ollama` provider natively.
*   **YAML OpenAI Model Property**: To stop `openai` falling back to `gpt-4o-mini`, the model injection MUST be prefixed exclusively at `quarkus.langchain4j.openai.chat-model.model-name`, NOT `quarkus.langchain4j.openai.model-name`.
*   **Cosmic Pantheon (Grok 4.20) Agents**: Renamed `GrokAgent` -> `VishnuAgent`, `HarperAgent` -> `SaraswatiAgent`, `BenjaminAgent` -> `BrahmaAgent`, `LucasAgent` -> `ShivaAgent` to establish a distinct mythological agentic framework for the kernel.
*   **Native Maven Compilation (`-am`)**: When building the native binary via `mvnw clean package -pl arcclaw-app -Pnative`, you MUST include the `-am` flag (Also Make) to ensure changes in `arcclaw-core` are recompiled before the `-app` binary is linked.
*   **Groq Cloud API Migration**: **CRITICAL CHANGE**. Replaced local Ollama with Groq Cloud (`llama-3.3-70b-versatile`) as the default LLM provider in the production profile. This utilizes the `openai` compatibility layer but repoints the `base-url` to Groq. 
*   **AutonomyLevel Renaming**: Renamed `READ_ONLY` -> `READONLY` and `PARTIAL` -> `SUPERVISED` across the entire codebase (Java core, handlers, and tests) to align with enterprise configuration standards and provide clearer operational semantics.
*   **Telegram Handler Default Level**: The `TelegramAgentHandler` now explicitly injects and applies the `arcclaw.autonomy.default-level` (defaulting to `SUPERVISED`) to the active workspace session at runtime.
*   **Autonomy Enforcement Location**: **ARCHITECTURE DECISION**. Autonomy policy enforcement was moved from `GovernanceLayer` (which focuses on financial token/budget tracking) to `ToolRegistry.java`. This preserves separation of concerns as `ToolRegistry` already acts as the gatekeeper for tool discovery and invocation security. `RequiresApprovalException` is thrown directly from the registry for `SUPERVISED` accounts.

### 9. Dual-Mode Binary Picocli Lifecycle (Phase 15)
*   **Deviation:** `Quarkus.waitForExit()` is omitted from the top-level `ArcClawCli.run()` dispatch method. 
*   **Rationale:** Calling `waitForExit()` at the parent level hangs the one-shot `ask` CLI mode indefinitely. Instead, the `serve` subcommand explicitly owns its own daemon hold via `Quarkus.waitForExit()`.
*   **Deviation:** The `AgentLoop` `forEach` observer loop captured an effectively non-final variable (`step`), which `AgentObserver.onStepComplete` rejected.
*   **Rationale:** Fixed explicitly assigning `final ReasoningStep completedStep = step` directly preceding the stream logic to satisfy Java's closure scopes.

---
*Internal Audit Trail sync complete.*


### 10. Telegram Session Continuity (REQ-V11-CLI-11)
*   **Deviation:** Explicitly omitted `workspaceManager.terminateSession()` in the Telegram handler loop.
*   **Rationale:** To preserve conversation memory across separate Telegram messages, the session must remain `IN_PROGRESS`. We only invoke `WorkspaceScopeHolder.clear()` in the `finally` block to release the thread-local correlation without destroying the persistent state.

### 11. Custom Dotenv Resolution SPI
*   **Deviation:** Implemented `DotenvConfigSourceProvider.java` as a MicroProfile SPI instead of relying on `quarkus.config.locations`.
*   **Rationale:** Standard Quarkus property location resolution failed to consistently load the root `.env` when executing submodules (e.g., `mvn -pl arcclaw-app quarkus:dev`) in Windows PowerShell. The SPI approach guarantees property injection early in the boot sequence with a high ordinal (295).

### 12. CDI Proxy Mock Injection
*   **Deviation:** Refactored `TelegramAgentHandlerTest` to bypass CDI proxy limitations.
*   **Rationale:** Standard `@InjectMock` cannot resolve into private fields of `@Dependent` beans within a `@QuarkusTest`. We manually unwrapped the `ClientProxy` and used reflection to force mocks into the target instance's private fields.
- [x] Verification: Resolve remaining exit-code-1 in `TelegramAgentHandlerTest`

### 13. State Machine "Resume Hook" (Sprint 2B)
- **Deviation**: Implemented a "resume hook" in `AgentLoop.run()` that detects `AWAITING_APPROVAL` checkpoints and executes the pending tool before entering the thought loop.
- **Rationale**: This allows for non-blocking human-in-the-loop approvals. The agent doesn't need to "re-decide" to run the tool; it completes the approved action and then continues reasoning.

### 14. Deterministic Session Continuity (Sprint 2C)
- **Deviation**: Use of `UUID.nameUUIDFromBytes((chatId + ":" + userId).getBytes())` for session IDs in Telegram.
- **Rationale**: Ensures that a single user in a specific chat always maps to the same workspace and conversational context, enabling true persistence even after bot restarts.

### 15. Session Reset Logic (Sprint 2C)
- **Deviation**: The Telegram handler now calls `workspaceManager.terminateSession()` specifically when the existing checkpoint is `COMPLETED` or `FAILED`.
- **Rationale**: This allows a user to "start over" after a task finishes, without losing the convenience of deterministic ID mapping.

### 16. Manual Model Producer (Sprint 3B)
- **Deviation**: Implemented a manual CDI `@Produces` method for the `intent-classifier` model instead of relying on `application.yaml` named-model configuration.
- **Rationale**: Resolved an `UnsatisfiedResolutionException` where Quarkus failed to wire the `@Identifier("intent-classifier")` qualified bean in a multi-module setup. The manual producer provides deterministic bean registration and avoids extension-specific configuration limitations.

### 17. Specialized Debugger Role ("shiva")
- **Deviation**: Introduced a dedicated `shiva` role for the Intent Router to handle "fix it" and "why is this failing" prompts, distinct from the `brahma` (creator) role.
- **Rationale**: Prevents specialized creation logic (Brahma) from being applied to debugging tasks, ensuring the appropriate system prompt and reasoning style (Shiva's contrarian/edge-case focus) are utilized.

### 18. Sandbox Enforcement Centralized in ToolRegistry
- **Deviation**: Moved `ProcessIsolationExecutor` invocation from individual 
  tool implementations into `ToolRegistry.invokeTool()`.
- **Rationale**: The original opt-in pattern allowed tool developers to silently 
  skip sandbox isolation. Centralizing enforcement in the registry makes 
  sandboxing the default for all tools, with explicit opt-out via 
  `requiresSandbox() = false` for trusted in-memory tools. The reflection 
  method `extractRequiredSecrets` was updated to accept a method name parameter, 
  fixing a silent credential injection failure when annotations moved from 
  `execute()` to `sandboxCommand()`.


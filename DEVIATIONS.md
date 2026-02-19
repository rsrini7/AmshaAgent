# ArcClaw v1.0 — Technical Audit & Deviations

**Audit Date:** February 19, 2026  
**Auditor:** Senior Technical Auditor (Antigravity)  
**Status:** **PASSED WITH PIVOTS**

## Executive Summary
The implementation of ArcClaw v1.0 has successfully translated the high-level consolidated specification into a functional, hardened kernel. The primary driver for architectural pivots was the requirement for **JDK 25 stability** and **Quarkus 3.23.0 LTS alignment**. While the core pillars (Autonomy, Isolation, Scope) remain intact, specific implementation details were adjusted to handle the edge cases of JEP 491 (Virtual Thread Pinning) and ArC build-time processing.

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
*   **Deviation:** Retained **`ReentrantLock`** in `WorkspaceScopeContext` despite JDK 25 offering a fix for `synchronized` pinning (JEP 491).
*   **Rationale:** To ensure "Hybrid Stability." While JEP 491 fixes pinning in JDK 25, many production environments still run JDK 21/23. Keeping `ReentrantLock` ensures the virtual threads do not pin the carrier across all LTS versions.

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

### 5. OTel Stub for Sandbox Layer
*   **Deviation:** Created a minimal `AgentOtelContext` stub returning `Span.getInvalid()` instead of implementing the full observer layer.
*   **Rationale:** `ProcessIsolationExecutor` requires OTel child spans for tracing, but the full `AgentOtelContext` is Phase 9. The stub allows compilation and testing without pulling in unimplemented dependencies.

### 6. VaultKey API Adaptation
*   **Deviation:** `resolveSecrets()` in `ProcessIsolationExecutor` unwraps `Optional<VaultKey>` via try-with-resources instead of the spec's `String`-based API.
*   **Rationale:** Our `SecretVault.get()` returns `Optional<VaultKey>` (auto-wiping byte wrapper), not `String`. The adaptation ensures zero-copy secret handling with automatic memory clearing.

## Technical Debt & Decisions

*   **ByteBuddy Experimental Flag:** Required `-Dnet.bytebuddy.experimental=true` for JDK 25. This is documented in `TROUBLESHOOTING.md`.
*   **SQLite Blob Handling:** Removed `@Lob` annotations from JPA entities. SQLite's JDBC driver prefers native `byte[]` mapping to avoid OOMs on large secret payloads.
*   **CDI Producer Scopes:** Resolved `DefinitionException` by removing redundant `@Dependent` on `@WorkspaceScoped` producers (only one scope is legal).
*   **Jandex Requirement**: Every new module added to ArcClaw MUST include the Jandex plugin, or ArC will skip bean discovery for that module.
*   **Stale Class Files**: `mvn clean` required when switching between incremental builds across modules (deployment module classpath can become stale).

---
*Internal Audit Trail sync complete.*

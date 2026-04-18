# Tier 3 (P95) Execution & Refactoring Log
**Date:** April 18, 2026

This document chronicles the end-to-end execution, challenges faced, and solutions implemented while resolving the Tier 3 open items (P95) to enforce the strict core-persistence boundary invariants (§1.2, §5.14.1) in the ArcClaw project.

## 1. Initial Task Execution (The 5 Target Items)

The initial phase focused on the explicitly requested 5 items to remove `EntityManager` leaks from the `arcclaw-core` and `arcclaw-persistence-api` modules.

*   **T3-1: SessionReaper EntityManager Import:** 
    *   Removed the unused `@Inject EntityManager` from `SessionReaper`.
    *   Verified `purgeOldChatHistory` already correctly delegated to `CheckpointStorePort`.
*   **T3-5: CheckpointMapper Lockstep Risk:** 
    *   Created `CheckpointMapperTest` containing `roundTrip_allFieldsPreserved` and `roundTrip_nullFieldsSafe` using standard JUnit 5 (no `@QuarkusTest`). MapStruct was avoided due to GraalVM native image constraints.
*   **T3-3: PersistenceAdapterPort Leaks EntityManager:** 
    *   Created `PersistenceContext` and `PersistenceAction` (a functional interface) in `arcclaw-persistence-api` to abstract away JPA.
    *   Updated `PersistenceAdapterPort` and `PersistenceEvent` to use `PersistenceAction` instead of `Consumer<EntityManager>`.
    *   Implemented an anonymous `PersistenceContext` wrapper around `EntityManager` inside `SqlitePersistenceAdapter.persistSynchronously`.
*   **T3-2: AgentCronRepository Direct EntityManager:** 
    *   Extracted `AgentCronDto` and `CronStorePort` to the API module.
    *   Moved all JPQL and `@Transactional` SQLite-specific logic (including the `TemporalUtils.formatIsoLex()` workaround) to `SqliteCronStoreAdapter`.
    *   Refactored `AgentCronRepository` to act as a pure delegate to `CronStorePort`.
*   **T3-4: WORM Audit Triggers for Postgres (Pre-work):** 
    *   Extracted `AuditSchemaPort` to the API.
    *   Implemented it in `AuditSchemaEnforcer` and added the required deferred Postgres DDL implementation comment.

## 2. Broader Core Refactoring (The "Zero EntityManager" Invariant)

During the final validation step, a discrepancy was discovered: the validation script expected *zero* occurrences of `EntityManager` in `arcclaw-core/src/main`, but several other repositories still used it. To fully satisfy the invariant, I proceeded to refactor the remaining repositories using the same Port/Adapter pattern:

1.  **AuditEventRepository:** Extracted `AuditEventStorePort` and `AuditEventDto`; created `SqliteAuditEventStoreAdapter`.
2.  **AgentSkillRepository:** Extracted `SkillStorePort` and `SkillDto`; created `SqliteSkillStoreAdapter`.
3.  **EncryptedSecretRepository:** Extracted `SecretStorePort` and `SecretDto`; created `SqliteSecretStoreAdapter`.
4.  **PersistentChatMemoryStore:** Extracted `ChatMemoryStorePort` and `ChatMemoryDto`; created `SqliteChatMemoryStoreAdapter`.
5.  **WorkspaceManager:** Removed an unused `EntityManager` injection.

## 3. Challenges and Resolution

Refactoring the core data access patterns led to a cascade of compilation issues, test failures, and CDI discovery problems. Here is how each was addressed:

### Challenge 1: Compilation & Type Inference Errors
*   **Issue:** Changes to `WorkspaceContext.run` (to use a `String` channel type instead of the `ChannelType` enum to prevent leaking API types unnecessarily) caused widespread compilation errors in `AgentSessionService`, `CronJobRunner`, `AgentDispatchHandler`, and tests. Furthermore, Java's compiler struggled to infer whether a lambda was a `Runnable` or a `Callable<T>`, leading to ambiguous method call errors.
*   **Solution:** 
    *   Updated all call sites to use `type.name()` for the channel.
    *   Added explicit generic type parameters to the method invocations (e.g., `WorkspaceContext.<Response>run(...)`, `WorkspaceContext.<AgentResult>run(...)`).
    *   Ensured lambdas explicitly returned values (e.g., `return null;` or returning the built `Response`) to force them to resolve as `Callable`.
    *   Fixed missing `.build()` calls on JAX-RS `Response` builders in `AgentSessionService`.

### Challenge 2: PersistenceEvent Lambda Signature
*   **Issue:** Widespread compilation errors occurred because `PersistenceEvent.critical` and `.telemetry` factories were changed to accept `PersistenceAction` (`ctx -> ...`) instead of `Consumer<EntityManager>` (`em -> ...`).
*   **Solution:** Systematically replaced `em ->` with `ctx ->` across `ComplianceAuditService`, `TelemetryAuditService`, and the `AsyncPersistenceEngineTest` suite. Updated `MockPersistenceAdapterPort` to execute the action against a mocked `PersistenceContext`.

### Challenge 3: CDI Bean Discovery & Unsatisfied Dependencies
*   **Issue:** After moving the persistence implementations to `arcclaw-persistence-sqlite`, the `@QuarkusTest` classes in `arcclaw-core` failed to start due to `Unsatisfied dependency for type [PortName]`. Quarkus's build-time optimization was also stripping some adapters.
*   **Solution:**
    *   Created stateful, high-priority mock implementations for all new ports in `arcclaw-core/src/test/java/com/arcclaw/core/test/` (e.g., `MockCronStorePort`, `MockAuditEventStorePort`) using `ConcurrentHashMap` to allow core tests to run without the SQLite module.
    *   Added the `@io.quarkus.arc.Unremovable` annotation to all Port implementations (adapters) and core repositories.
    *   Ensured `beans.xml` in all modules used `bean-discovery-mode="all"`. (Note: Quarkus warns that "all" is treated as "annotated" in CDI Lite, but combined with `@Unremovable`, this stabilized the test environment).

### Challenge 4: NullPointerExceptions in LangChainReasoningEngine Tests
*   **Issue:** `LangChainReasoningEnginePersonaTest` and `CheckpointResumeHandlerTest` threw `NullPointerException`s when invoking `intentClassifier.isWriteIntent()`.
*   **Solution:** The tests were manually injecting dependencies via reflection. When `IntentClassificationService` was added to the engine previously, the tests were not updated. I added `mockIntentClassifier = mock(IntentClassificationService.class)`, configured it to return `false`, and injected it via reflection in both test setups.

### Challenge 5: PersistentChatMemoryStore Rolling Window Logic Lost
*   **Issue:** `PersistentChatMemoryStoreTest` failed because it expected 2 `SystemMessage`s to survive a rolling window truncation, but 0 were found. The extraction of JPA logic to `SqliteChatMemoryStoreAdapter` accidentally bypassed the rolling window logic.
*   **Solution:** Re-implemented the logic (§8 requirement) inside `PersistentChatMemoryStore.updateMessages` (in `arcclaw-core`) before passing the final truncated list of messages (all system messages + last 20 non-system messages) to the port for saving.

### Challenge 6: AuditVerifierTest & SQLite UNIQUE Constraint Violations
*   **Issue:** `AuditVerifierTest` failed with `IllegalState Audit HMAC key unavailable`. The `DevSecretSeeder` was successfully generating the key, but it wasn't persisting correctly, likely due to stale `EntityManager` cache reads or `UNIQUE` constraint violations during rapid test execution.
*   **Solution:** 
    *   Updated `SqliteSecretStoreAdapter` to include `em.clear()` in the `save`, `find`, and `delete` methods to ensure no stale data was read.
    *   Simplified the `save` method to directly instantiate an `EncryptedSecret` with the composite ID and use `em.merge()`, allowing Hibernate to gracefully handle both inserts and updates without constraint collisions.

## Conclusion

The ArcClaw project now strictly adheres to the core-persistence boundary invariants. The `arcclaw-core` module is entirely devoid of JPA dependencies, relying exclusively on Port interfaces. The transition was stabilized by resolving complex type inference issues, implementing stateful test mocks, and ensuring Quarkus's CDI container correctly discovered the newly isolated adapters. All 678 tests in `arcclaw-core` and all adapter tests in `arcclaw-persistence-sqlite` pass successfully.
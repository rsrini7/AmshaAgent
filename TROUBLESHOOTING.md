# ArcClaw V1.0.0 — Master Architecture & Troubleshooting Guide

> **Consolidated Design Decisions, Known Failure Modes, and "Missing Think" Resolutions**
>
> This document serves as the single source of truth for architectural decisions, version alignments, and troubleshooting across ALL phases (0–10).
>
> **Cross-Reference**: [PLAN.md](./PLAN.md) | [TASKS.md](./TASKS.md) | [DEVIATIONS.md](./DEVIATIONS.md) | [WALKTHROUGH.md](./WALKTHROUGH.md)

---

## 1. Key Design Decisions & Architecture

### 1.1 Core CDI Scope Layer (`@WorkspaceScoped`)
| Decision | Rationale | Context |
| :--- | :--- | :--- |
| **`synchronized` over `ReentrantLock`** | **JEP 491 (JDK 25)** eliminates virtual thread pinning on `synchronized` blocks. This makes standard Java synchronization simpler and more idiomatic than explicit locks. | Core / `WorkspaceScopeContext` |
| **No `PortableExtension`** | Quarkus CDI Lite does not support the full `PortableExtension` SPI. Scope registration is handled purely via **`@BuildStep`** processors in the deployment module. | Deployment / `WorkspaceScopeProcessor` |
| **No `BytecodeRecorder`** | `ContextConfigurator.contextClass()` allows direct registration of the `InjectableContext` class definition, removing the need for a runtime recorder to construct the context manually. | Deployment / `WorkspaceScopeProcessor` |
| **Module Structure** | **6 Files Total**: <br>• **Core (3):** `WorkspaceScoped.java`, `WorkspaceScopeHolder.java`, `WorkspaceScopeContext.java`<br>• **Deployment (1):** `WorkspaceScopeProcessor.java`<br>• **Test (2):** `ScopedCounter.java`, `WorkspaceScopeTest.java` | Project Layout |

### 1.2 Initial Challenges & Version Alignments
- **JDK 25 vs JDK 21 Compatibility:**
  - *Challenge:* `maven-compiler-plugin` failed with `invalid source release 21 with --enable-preview` when running on JDK 25.
  - *Resolution:* Removed `--enable-preview` for source 21. JDK 25 requires `source=25` to enable preview features.
- **LangChain4J Versioning:**
  - *Challenge:* Quarkus extensions (`quarkus-langchain4j:0.26.1`) were incompatible with the newer `langchain4j-bom:1.0.0-beta4` (due to `ChatLanguageModel` class moves).
  - *Resolution:* Removed the BOM import. Relied on the Quarkiverse extension to transitively manage the compatible LangChain4J core version (0.26.1).
- **OpenTelemetry Test Dependencies:**
  - *Challenge:* `ClassNotFoundException: TracerEnabled` during tests.
  - *Resolution:* Explicitly removed redundant `quarkus-opentelemetry-deployment` test dependency from `arcclaw-app/pom.xml`. The core module already transitively provides the runtime requirement.
- **ByteBuddy & Java 25 Compatibility:**
  - *Challenge:* `java.lang.IllegalArgumentException: Java 25 (69) is not supported...`
  - *Resolution:* Set `net.bytebuddy.experimental=true` in `MAVEN_OPTS` and Surefire configuration.
- **SQLite JDBC Driver:**
  - *Challenge:* `Unable to find a JDBC driver corresponding to the database kind 'sqlite'`.
  - *Resolution:* Explicitly added `quarkus-jdbc-sqlite` to `arcclaw-app/pom.xml`.

---

## 2. Hardened Resolutions (The "Steel Thread" Fixes)

| Category | Symptom | Root Cause | Resolution |
| --- | --- | --- | --- |
| **CDI** | `ContextNotActiveException` | `WorkspaceScopeHolder` not activated before agent entry. | Ensure `activate()` is called in the `finally` block of the `AgentLoop`. |
| **Security** | `IllegalStateException` | Active session required to store secrets. | Verify `WorkspaceScopeHolder.set()` is called BEFORE test logic in `@BeforeEach`. |
| **Security** | `GenericJDBCException` | `getBlob` not implemented by SQLite. | Remove `@Lob` from entity `byte[]` fields to use `getBytes()`. |
| **Security** | `HibernateException` | ManagedEntity in two persistence contexts. | Use `entityManager.merge()` instead of `persist()` for existing entities in tests. |
| **Security** | Silent Credential Failure | Annotation moved from `execute()` to `sandboxCommand()`. | Refactored `extractRequiredSecrets` to accept a `methodName` parameter. |
| **Sandbox** | Zombie Containers | JVM `SIGTERM` didn't signal the Podman process. | Verify the `@PreDestroy` method in `ProcessIsolationExecutor` is calling `podman stop`. |
| **Native** | Predictable Nonces | BC initialized at build-time instead of run-time. | Verify fine-grained `native-image.properties` flags are correctly applied. |

---

## 3. Build & Infrastructure Troubleshooting

### 🔴 Failure: "Invalid source release 21 with --enable-preview"
**Symptom:** `mvn clean compile` fails with `invalid source release 21...`.
**Root Cause:** JDK 25 environment trying to compile source 21 with preview features (only supported for source 25).
**Resolution:** Remove `--enable-preview` or upgrade to `source=25`.

### 🔴 Failure: `NoClassDefFoundError: ChatLanguageModel`
**Symptom:** Runtime crash or test failure loading LangChain4J classes.
**Root Cause:** Using `langchain4j-bom:1.0.0-beta4` + `quarkus-langchain4j:0.26.1`. The BOM forces a newer, incompatible core library where `ChatLanguageModel` was moved to a new package.
**Resolution:** Remove `langchain4j-bom` import. Let Quarkus extension manage versions.

### 🔴 Failure: `ConfigurationException: A ChatLanguageModel ... bean was requested, but since there are multiple available providers ... needs to be set`
**Symptom:** Build fails during Quarkus augmentation when multiple LangChain4J extensions (e.g. `openai` and `ollama`) are on the classpath.
**Root Cause:** Quarkus cannot unambiguously wire `ChatLanguageModel` because multiple provider extensions offer it.
**Resolution:** Explicitly set `quarkus.langchain4j.chat-model.provider=ollama` (or `openai`) in `application.yaml`.

### 🔴 Failure: Mockito inline-mocker fails to attach on JDK 21+
**Symptom:** `Mockito is currently self-attaching to enable the inline-mock-maker. This will no longer work in future releases of the JDK` followed by test failures when mocking complex classes like `ChatLanguageModel`.
**Root Cause:** JDK 21+ heavily restricts dynamic agent loading and reflection which Mockito relies on.
**Resolution:** Add `--add-opens=java.base/java.lang=ALL-UNNAMED` to `MAVEN_OPTS` or Surefire configuration for test execution.

### 🔴 Failure: `ClassNotFoundException: TracerEnabled` (OTel)
**Symptom:** Quarkus test bootstrap fails.
**Root Cause:** Redundant or conflicting `quarkus-opentelemetry-deployment` artifact on the test augmentation classpath.
**Resolution:** Remove the explicit dependency if it conflicts with the core runtime extension.

### 🔴 Failure: `jakarta.enterprise.inject.spi.DefinitionException` (multiple scope annotations)
**Symptom:** Build fails during CDI augmentation.
**Root Cause:** A producer method (e.g., `WorkspaceContextProducer`) had multiple scope annotations (e.g., `@Dependent` and `@WorkspaceScoped`).
**Resolution:** Remove the redundant annotation. The producer determines the scope of the produced bean.

### 🔴 Failure: `java.lang.IllegalArgumentException: Java 25 (69) is not supported...`
**Symptom:** ByteBuddy fails during Hibernate proxy generation.
**Root Cause:** JDK 25 is too new for the current ByteBuddy version.
**Resolution:** Set `-Dnet.bytebuddy.experimental=true`.

### 🔴 Failure: `SQLFeatureNotSupportedException: not implemented by SQLite JDBC driver`
**Symptom:** `GenericJDBCException` when loading `byte[]` fields.
**Root Cause:** `@Lob` forces Hibernate to use `ResultSet.getBlob()`.
**Resolution:** Remove `@Lob` from the entity field.

### 🔴 Failure: `HibernateException: Illegal attempt to associate a ManagedEntity with two open persistence contexts`
**Symptom:** Test error during `saveEntity`.
**Root Cause:** Calling `persist()` on an object that Hibernate thinks is detached or already managed by another session.
**Resolution:** Use `entityManager.merge(entity)`.

### 🔴 Failure: `java.lang.IllegalAccessError: module java.base does not open java.lang...`
**Symptom:** NoClassDefFoundError for JBoss Threads.
**Root Cause:** JDK 25 requires explicit opens for reflection on some core classes.
**Resolution:** Add `--add-opens java.base/java.lang=ALL-UNNAMED` to `MAVEN_OPTS`.

### 🔴 Failure: BouncyCastle SecurityException
**Symptom:** `JCE cannot authenticate the provider BC`.
**Root Cause:** Signed jar conflict (`bc-fips` vs `bcprov`).
**Resolution:** Enforce explicit exclusions in `pom.xml`.

### 🔴 Failure: Discord Channel Registry (AD-014)
**Symptom:** Attempting to use `camel-quarkus-discord` crashes on boot with `NoClassDefFoundError`.
**Root Cause:** `quarkus-discord4j` is effectively abandonware (v0.0.1, no updates since 2024). It is incompatible with modern Quarkus 3.x and the latest Discord Gateway intents.
**Resolution:** De-prioritize Discord as a production-ready adapter. Stick with Telegram. See [ARCHITECTURAL_DECISIONS.md](./ARCHITECTURAL_DECISIONS.md).

### 🔴 Failure: Emojis appearing as `???` in Windows Terminal
**Symptom:** `ChatCommand` output looks like `? ArcClaw Interactive Terminal`.
**Root Cause:** Standard Windows Command Prompt and older PowerShell versions do not support the full UTF-8 Emoji plane by default.
**Resolution:** Swapped modern emojis for wider-compatible Unicode symbols like `✦` (U+2726), `⚡` (U+26A1), and `▶` (U+25B6).

### 🔴 Failure: `NoSuchElementException` in `ChatCommand` REPL
**Symptom:** Force-closing the stream or hitting `Ctrl+C` throws a massive stack trace from `scanner.nextLine()`.
**Root Cause:** The `Scanner` object throws this when the input stream is terminally closed while waiting for input.
**Resolution:** Added a defensive `try-catch` around `scanner.nextLine()` specifically for `NoSuchElementException` to break the loop silently.

---

## 4. CDI & Scope Troubleshooting

### 🔴 Failure: `ContextNotActiveException`
**Symptom:** Accessing `@WorkspaceScoped` bean fails.
**Root Cause:** Thread-local `WorkspaceScopeHolder` is empty.
**Resolution:** Call `WorkspaceScopeHolder.set(sessionId)` at entry; `clear()` at exit.

### 🔴 Failure: `UnsatisfiedResolutionException` (Custom Scope)
**Symptom:** Bean injection fails.
**Root Cause:**
1. Deployment module not found (missing `@BuildStep` execution).
2. `quarkus-extension.properties` missing in core module.
3. **Missing Jandex Index** in core module.
**Resolution:**
- Add `jandex-maven-plugin` or empty `beans.xml` to `arcclaw-core`.
- Ensure `quarkus-extension.properties` is in `src/main/resources/META-INF`.
- **CRITICAL**: Ensure `quarkus-extension-processor` is a provided dependency in the deployment module. Without it, `@BuildStep` methods are ignored, and the context is never registered.

### 🔴 Failure: `Unsatisfied dependency for type` in Cross-Module Tests
**Symptom:** Tests in `arcclaw-app` fail to start with `Unsatisfied dependency` referring to beans in `arcclaw-core`, despite those beans being correctly annotated.
**Root Cause:** Stale `.class` files or missing Jandex index updates in `arcclaw-core`. The `arcclaw-app` test runner may be relying on an older compilation output or local repository artifact of `arcclaw-core` that doesn't include newly added beans.
**Resolution:** Always run `mvn clean install` across the entire project (e.g., `mvn clean install -DskipTests`) when adding new beans across module boundaries to ensure the Jandex index is fully rebuilt and accessible to the app runner.

### 🔴 Failure: `Unsatisfied dependency for type InMemorySpanExporter` in Tests
**Symptom:** Injecting `InMemorySpanExporter` in `@QuarkusTest` fails with `UnsatisfiedResolutionException`.
**Root Cause:** The `quarkus-opentelemetry` extension does not automatically expose `InMemorySpanExporter` as a CDI bean unless explicitly configured or wired for testing. Adding `opentelemetry-sdk-testing` dependency manually can cause test discovery failures or classpath conflicts on some setups.
**Resolution:** Bypass the `InMemorySpanExporter` entirely. Use `quarkus-junit5-mockito` and `@InjectMock Tracer tracer` to mock the OpenTelemetry SDK. Verify `Tracer` and `Span` interactions natively (e.g. `verify(tracer).spanBuilder(...)`) instead of relying on the exporter sink.

### 🔴 Failure: `ContextNotActiveException` in Tests
**Symptom:** `ContextNotActiveException` when running tests, even if `WorkspaceScopeHolder.set()` seems to be called.
**Root Cause:** Thread isolation. Tests or frameworks may spawn child threads (or virtual threads acting as such) where standard `ThreadLocal` context is lost.
**Resolution:**
- Switch to `InheritableThreadLocal` in `WorkspaceScopeHolder`.
- Ensure `isActive()` explicitly checks `WorkspaceScopeHolder.get() != null`.
- Add `Thread.yield()` after `set()` in tests to ensure propagation before proxy access.

### 🟡 Warning: Thread Pinning
**Symptom:** Performance drop on high concurrency.
**Root Cause:** `synchronized` used on older JDKs.
**Resolution:** Upgrade to JDK 25 (JEP 491 compliant) or use `ReentrantLock`.

### 🔴 Failure: Null Contexts on Virtual Threads
**Symptom:** `@WorkspaceScoped` attributes or CDI contexts resolve to `null` silently during `@RunOnVirtualThread` execution.
**Root Cause:** Quarkus detaches `ThreadLocal` storage when swapping execution to asynchronous virtual threads.
**Resolution:** Add `mp.context.propagators=Quarkus, CDI, JAX-RS` to `application.yaml` to ensure transparent propagation across thread boundaries.

### 🔴 Failure: Integration Test Leakage across Suites
**Symptom:** An integration test checking `activeSessionCount()` passes in isolation but throws `expected: <1> but was: <3>` on a full suite build `mvn clean verify -pl arcclaw-app -am`.
**Root Cause:** Static/Application-scoped maps like `ConcurrentHashMap` in `WorkspaceManager` linger throughout the entire test classloader lifecycle unless strictly cleared explicitly.
**Resolution:** Inject a hermetic teardown method `clearAll()` manually inside the test (`@BeforeEach` or `@AfterEach`).

---

## 10. Health Layer Troubleshooting

> [!CAUTION]
> **Phase 10 Cost: 52 Iterations.** This section documents the hardest debugging cycle in the project. Read the entire section before attempting any health check work.

### 🔴 Failure: `@InjectMock` Cannot Resolve Cross-Module HealthCheck Beans
**Symptom:** `TestInstantiationException: could not resolve the bean of type: com.arcclaw.core.health.PodmanHealthCheck`.
**Root Cause:** When `quarkus-smallrye-health` runs during the build phase, it indexes health beans by their **interface type** (`HealthCheck`) rather than their **concrete class type**. When `@InjectMock` in the test asks for the concrete class, ArC cannot find it in its bean graph.
**Failed Approaches (40+ iterations wasted):**
1. `@InjectMock PodmanHealthCheck` — ArC doesn't index the concrete class
2. `@InjectMock @Liveness HealthCheck` — Ambiguous resolution with built-in `DataSourceHealthCheck`
3. `@Startup` + `@Unremovable` on production beans — Doesn't fix the indexing problem
4. `@Mock extends PodmanHealthCheck` (Quarkus `io.quarkus.test.Mock`) — Fails with `IllegalAccessError` on `@ConfigProperty` field injection across classloader boundaries
5. `@Mock extends PodmanImageHealthCheck` with `protected` field — Still fails because ArC-generated `_Bean.doCreate()` runs in a different classloader
**Resolution:** Use `@Alternative` + `@Priority(1)` standalone implementations of `HealthCheck` interface. Do NOT extend the production class.

```java
// ✅ CORRECT — standalone implementation
@Alternative @Priority(1) @Readiness @ApplicationScoped
public class MockPodmanImageHealthCheck implements HealthCheck {
    @Override public HealthCheckResponse call() {
        return HealthCheckResponse.named("Podman Image: test-image").up().build();
    }
}

// ❌ WRONG — extends production class
@Mock @Readiness @ApplicationScoped
public class MockPodmanImageHealthCheck extends PodmanImageHealthCheck { ... }
```

### 🔴 Failure: HTTP 404 on `/q/health/live` and `/q/health/ready`
**Symptom:** RestAssured receives 404, even though `smallrye-health` is in `Installed features`.
**Root Cause:** **Quarkus 3.x Management Interface.** Health, metrics, and OpenAPI endpoints are served on a **separate management port** (default: 9000 in prod, **9001 in test mode**), NOT the main HTTP port (8080/8081). RestAssured defaults to the main HTTP port.
**Failed Approaches:**
1. Setting `RestAssured.baseURI = "http://127.0.0.1"` — Hits the wrong port entirely
2. Checking `quarkus.http.non-application-root-path` — Not the issue in Quarkus 3.x
**Resolution:** Explicitly set `.port(9001)` in RestAssured calls:
```java
given().port(9001).when().get("/q/health/live").then().statusCode(200);
```
**Detection:** Look for `Management interface listening on http://localhost:9001` in Quarkus startup logs.

### 🔴 Failure: `IllegalAccessError` on `@ConfigProperty` Field Across Modules
**Symptom:** `MockPodmanImageHealthCheck_Bean tried to access protected field PodmanImageHealthCheck.containerImage`.
**Root Cause:** ArC generates bean proxy code (`_Bean.doCreate()`) in the **app module's classloader**, but the parent class lives in the **core module's classloader**. Even `protected` fields cannot be accessed across Quarkus classloader boundaries. This is NOT a Java visibility issue — it's a classloader isolation issue.
**Failed Approaches:**
1. Making field `protected` — Still fails across classloaders
2. Making field `public` — Would work but is poor API design
3. Null-safe access in `call()` — Irrelevant, the error occurs in bean **creation**, not in `call()`
**Resolution:** Do NOT extend production classes with `@ConfigProperty` fields in mock beans. Implement the interface directly.

### 🔴 Failure: `AmbiguousResolutionException` for `@Readiness HealthCheck`
**Symptom:** `Ambiguous dependencies for type HealthCheck and qualifiers [@Readiness]`.
**Root Cause:** Multiple beans implement `HealthCheck` with `@Readiness`: our `PodmanImageHealthCheck` AND the built-in `DataSourceHealthCheck` from Agroal.
**Resolution:** Do not use `@InjectMock @Readiness HealthCheck`. Use `@Alternative` with concrete mock classes instead.

### 🟡 Warning: Duplicate Dependencies in `pom.xml`
**Symptom:** Maven warning `'dependencies.dependency.(groupId:artifactId)' must be unique: io.quarkus:quarkus-smallrye-health`.
**Root Cause:** Copy-paste error during Phase 10 setup.
**Resolution:** Always check for duplicates after adding new dependencies.

### 📋 Phase 10 Decision Matrix (For Future LLMs)

| Approach | Works? | Why |
|----------|--------|-----|
| `@InjectMock ConcreteHealthCheck` | ❌ | ArC indexes by interface, not concrete class |
| `@InjectMock @Liveness HealthCheck` | ❌ | Ambiguous with built-in health checks |
| `@Mock extends ConcreteHealthCheck` | ❌ | ArC classloader boundary on `@ConfigProperty` fields |
| `@Alternative @Priority(1) implements HealthCheck` | ✅ | Clean CDI override, no classloader issues |
| `RestAssured.get("/q/health/live")` | ❌ | Hits main HTTP port, not management port |
| `given().port(9001).get("/q/health/live")` | ✅ | Correctly targets management interface |
---

## 5. Security & Sandbox Troubleshooting (Phases 2–3)

### 🔴 Failure: `NoSuchMethodError` (AES-GCM-SIV)
**Resolution:** Check for `bcprov-jdk18on` version 1.78.1+.

### 🔴 Failure: Zombie Containers / Rootless Errors
**Resolution:**
- Ensure `podman stop` in `@PreDestroy`.
- Enable lingering for rootless users: `loginctl enable-linger <user>`.

### 🔴 Failure: `ProcessIsolationExecutor` — Circuit Breaker Opens Unexpectedly
**Symptom:** All Podman executions fall back to NAMESPACE strategy.
**Root Cause:** Podman binary not on PATH or not installed. `@PostConstruct` check sets `podmanCircuitOpen = true`.
**Resolution:** Install Podman: `sudo dnf install podman` (RHEL/Fedora) or `brew install podman` (macOS). Verify with `podman --version`.

### 🔴 Failure: Ephemeral Env-File Not Deleted
**Symptom:** `.env` files accumulate in `java.io.tmpdir`.
**Root Cause:** JVM crash or force-kill before `finally` block executes.
**Resolution:** Monitor `/tmp/zc-*.env` files. They contain no secrets after process exit (overwritten by `clearSecretsFromMemory()`). Clean up manually if needed.

### 🔴 Failure: `TestEngine failed to discover tests` (Quarkus Augmentation)
**Symptom:** `WorkspaceScopeContext cannot be resolved to a type` during `@QuarkusTest` discovery.
**Root Cause:** Stale `.class` files in `arcclaw-deployment/target/` from a previous build.
**Resolution:** Always use `mvn clean test` instead of `mvn test` when switching between incremental builds.

## 6. Native Image Troubleshooting (Phase 0+)

### 🔴 Failure: OOM During Analysis
**Resolution:** Increase heap: `-J-Xmx10g`.

### 🔴 Failure: Predictable Randomness (DRBG)
**Resolution:** `native-image.properties`: `--initialize-at-run-time=org.bouncycastle...DRBG`.

---
## 7. Agent & Orchestration Troubleshooting (Phase 5)

### 🔴 Failure: `AgentLoop` Infinite Cycling
**Symptom:** Agent never terminates, consuming all LLM budget.
**Root Cause:** `ReasoningEngine.nextStep()` never returns a step with `action = "FINISH"`. The mock/LLM keeps generating tool calls.
**Resolution:** `AgentLoop` enforces `maxIterations` (default 5). After exceeding, it returns `AgentResult.loopExceeded()`. Always test with a `ReasoningEngine` mock that eventually returns `FINISH`.

### 🔴 Failure: Hallucinated Tool Names
**Symptom:** Agent tries to call a tool that doesn't exist in the `ToolRegistry`.
**Root Cause:** LLM generates an action name not matching any registered `AgentTool.name()`.
**Resolution:** `AgentLoop` catches unresolved tools and injects an error observation: `"Tool 'foo' not found. Available: [bar, baz]"`. The loop continues with corrected context.

### 🟡 Warning: `ArcClawSupervisor` CDI Qualifier Ambiguity
**Symptom:** `AmbiguousResolutionException` when injecting `Agent` without `@AgentRole`.
**Root Cause:** Multiple `Agent` implementations exist. CDI needs a qualifier.
**Resolution:** Always inject via `@AgentRole(COORDINATOR)`, `@AgentRole(CODER)`, etc. `ArcClawSupervisor` uses `Instance<Agent>` with programmatic lookup.

---

## 8. State & Lifecycle Layer Troubleshooting (Phases 6–7)

### "Missing Think" Resolution: API Surface Discovery
**Scenario:** Implementing custom CDI scopes (`InjectableContext`) without comprehensive official docs for internal SPIs.
**Technique:** Inspecting decompiled class definitions to understand the non-public API surface.
**Command:** `javap -p -cp <jar-path> io.quarkus.arc.InjectableContext` (or using IDE decompiler).
**Discovery:**
- Found `ContextState` inner interface in `InjectableContext`.
- Found `contextClass()` method in `ContextConfigurator` (avoiding the need for a `BytecodeRecorder`).
**Result:** Successfully implemented `WorkspaceScopeContext` matches the internal Arc expectations.

---

## 8. State & Lifecycle Layer Troubleshooting

### 🔴 Failure: `PanacheRepository` Bytecode Enhancement
**Symptom:** `java.lang.NoClassDefFoundError` or `Method not found` when using `PanacheRepository` in a library module (`arcclaw-core`).
**Root Cause:** Quarkus Panache bytecode enhancement only runs in the application module (`arcclaw-app`). It does not instrument classes in external library jars during the build of the app.
**Resolution:**
- Use plain `EntityManager` and JPQL in library modules (`arcclaw-core`).
- Avoid `extends PanacheEntity` or `implements PanacheRepository` in libraries.

### 🔴 Failure: `UnknownEntityException` (Hibernate)
**Symptom:** Entities in `arcclaw-core` are not discovered by Hibernate in `arcclaw-app`.
**Root Cause:** Hibernate ORM in Quarkus only scans the main app package by default.
**Resolution:**
- Config: `quarkus.hibernate-orm.packages=com.arcclaw.core.state` in `application.yaml`.
- Ensure `jandex` index exists in `arcclaw-core` (via `jandex-maven-plugin` or `beans.xml`).

### 🔴 Failure: `UnsatisfiedResolutionException` for `WorkspaceScopeContext`
**Symptom:** `@Inject WorkspaceScopeContext context` fails.
**Root Cause:** `WorkspaceScopeContext` implements `InjectableContext` (Arc SPI) but is **not** a CDI bean itself (no scope annotation).
**Resolution:**
- Do not inject it directly.
- Use `Arc.container().getActiveContext(WorkspaceScoped.class)` to retrieve the active context instance.

### 🔴 Failure: `IllegalAccessError` in Tests
**Symptom:** Test in `arcclaw-app` cannot access package-private `@Observes` method in `arcclaw-core`.
**Root Cause:** Classloader separation between the app module and the core library module prevents package-private access, even if packages match.
**Resolution:** Make the `@Observes` method `public`.
---

## 9. Governance & OTel Troubleshooting (Phases 8–9)

### 🔴 Failure: Budget Not Enforced
**Symptom:** Agent continues beyond budget USD limit without downgrading to `READ_ONLY`.
**Root Cause:** `GovernanceLayer.recordLlmCall()` not invoked by `AgentLoop`. The observer list injection is missing.
**Resolution:** `AgentLoop` must inject `Instance<AgentObserver>` and iterate through all observers after each `ReasoningEngine.nextStep()` call. Verify both `GovernanceLayer` and `AgentOtelContext` are in the observer list.

### 🔴 Failure: `SessionBudget` Memory Leak
**Symptom:** `ConcurrentHashMap<String, SessionBudget>` in `GovernanceLayer` grows unbounded.
**Root Cause:** Terminated sessions are never cleared from the budget map.
**Resolution:** `WorkspaceManager.terminateWorkspace()` must call `governanceLayer.clearSession(sessionId)` during cleanup.

### 🔴 Failure: `InMemorySpanExporter` Not Available in Tests
**Symptom:** `UnsatisfiedResolutionException` when trying to inject `InMemorySpanExporter` in `@QuarkusTest`.
**Root Cause:** Quarkus's OTel extension doesn't automatically expose test exporters as CDI beans.
**Resolution:** Use `@InjectMock Tracer` instead. Verify spans via `verify(tracer).spanBuilder(...)` rather than examining exported spans.

### 🟡 Warning: OTel Span Leaks
**Symptom:** Spans accumulate without being ended.
**Root Cause:** `AgentOtelContext.startSessionSpan()` creates a span and scope, but `endSessionSpan()` is never called.
**Resolution:** Ensure `WorkspaceManager.terminateWorkspace()` calls `agentOtelContext.endSessionSpan(sessionId)` as part of cleanup.

---


## 10. Health Layer Troubleshooting (Phase 10)

> [!CAUTION]
> **Phase 10 Cost: 52 Iterations.** Read the full section in the block above (lines 145–213) for the complete decision matrix. This is the most common failure area in the project.

---

## 11. Search & Reverse Engineering Techniques

### "Missing Think" Resolution: API Surface Discovery
**Scenario:** Implementing custom CDI scopes (`InjectableContext`) without comprehensive official docs for internal SPIs.
**Technique:** Inspecting decompiled class definitions to understand the non-public API surface.
**Command:** `javap -p -cp <jar-path> io.quarkus.arc.InjectableContext`
**Discovery:**
- Found `ContextState` inner interface in `InjectableContext`.
- Found `contextClass()` method in `ContextConfigurator` (avoiding `BytecodeRecorder`).

---

## 12. Architectural Context for Future LLMs

> **Prompt Context**: If you are an AI assistant working on this codebase, read this section first to avoid common pitfalls.

### 12.1 Critical Constraints
- **JDK Version**: Target **JDK 25** (JEP 491, JEP 506). Use `--add-opens` for tests.
- **Quarkus Version**: Pinned to **3.23.0**. Do not upgrade without verifying ByteBuddy compatibility.
- **Native Image**: All reflection must be registered. BouncyCastle needs `native-image.properties`.

### 12.2 Module Separation Rules
- **arcclaw-core**: No Panache, Jandex required, package-private is invisible to app tests. Health beans: `@Liveness`/`@Readiness` + `@ApplicationScoped` only.
- **arcclaw-app**: Runtime config, `@QuarkusTest` classes, `quarkus.hibernate-orm.packages` must be explicit.
- **arcclaw-deployment**: `@BuildStep` processors only. Must have `quarkus-extension-processor` as provided dependency.

### 12.3 Custom Scopes
- **Never** `@Inject WorkspaceScopeContext`. Use `Arc.container().getActiveContext(WorkspaceScoped.class)`.

### 12.4 Concurrency
- `synchronized` blocks are correct for JDK 25 (JEP 491). On JDK 21, these will pin carrier threads.

### 12.5 Testing Patterns Decision Matrix

| Bean Type | Mock Strategy | Port |
|-----------|---------------|------|
| Regular CDI beans | `@InjectMock` | 8081 |
| HealthCheck beans | `@Alternative` + `@Priority` standalone impl | 9001 |
| OTel Tracer | `@InjectMock Tracer` | N/A |
| Extension-managed beans | `@Alternative` + `@Priority` | 8081 |
| Custom context (`WorkspaceScopeContext`) | `Arc.container()` lookup | N/A |

### 12.6 Build Commands Cheat Sheet
```bash
# Full clean build (REQUIRED when adding new beans/modules)
mvn clean install -DskipTests

# Run specific test
mvn test -pl arcclaw-app -am -Dtest=TestClassName -Dsurefire.failIfNoSpecifiedTests=false

# Run all tests
mvn clean test -pl arcclaw-app -am -Dsurefire.failIfNoSpecifiedTests=false
```

---

## 13. API & Channel Troubleshooting (Phase 11)

### 🔴 Failure: HTTP 404 on RestAssured Setup for Application Endpoints
**Symptom:** Integration tests targeting REST endpoints unexpectedly return `404 Not Found`. Wait, didn't `quarkus-rest` load correctly? Yes it did.
**Root Cause:** Two distinct causes exist for this:
1. **API Prefix configuration:** `application.yaml` defined a base path (`quarkus.rest.path=/api/v1`). Hardcoded endpoints checking `/sessions` missed this redirect.
2. **Missing Transitive Serialization Extensions:** Defining extensions such as `quarkus-rest-jackson` or `quarkus-hibernate-validator` strictly inside library modules like `arcclaw-core` will *not* reliably enable them in Quarkus HTTP processing if the primary `arcclaw-app` module doesn't include them directly.
**Resolution:**
- Always ensure `quarkus.rest.path` reflects your expected request URL.
- Force inclusion of critical serialization and validation dependencies inside the leaf `arcclaw-app/pom.xml`.

### 🔴 Failure: `AgentResourceTest` fails with 500 (Missing Entity Packages)
**Symptom:** Running `mvn clean package` or `mvn test` fails on `AgentResourceTest.testFullAgentLifecycle` with an `Expected status code <200> but was <500>` error.
**Root Cause:** The test-specific `src/test/resources/application.properties` was missing the `com.arcclaw.core.state` package from the `quarkus.hibernate-orm.packages` configuration. Consequently, Hibernate could not discover the `AgentCheckpoint` entity during tests, causing JPA operations to crash.
**Resolution:** Updated `src/test/resources/application.properties` to explicitly include all core entity packages: `quarkus.hibernate-orm.packages=com.arcclaw.core.state,com.arcclaw.core.security`.

### 🔴 Failure: `ContextNotActiveException` on `/api/v1/sessions/{id}/run-async`
**Symptom:** When triggering the asynchronous agent execution endpoint, the server immediately logs a `ContextNotActiveException: Cannot use the EntityManager/Session because neither a transaction nor a CDI request context is active`.
**Root Cause:** The `/run-async` endpoint manually spawns a virtual thread using `Thread.ofVirtual().start()`. In Quarkus, threads spawned outside the managed executor pool do **not** automatically inherit or instantiate a CDI RequestContext. When `AgentLoop` attempts to invoke DB operations via Panache (e.g., `agentStore.load()`), it crashes because there is no context to bind the Hibernate session to.
**Resolution:** 
1. **JVM Mode**: Attaching `@ActivateRequestContext` to the `AgentLoop.run()` method typically ensures a request lifecycle.
2. **Native Mode (Failed Attempt 1 - Localized JTA)**: In AOT binaries, unmanaged virtual threads completely bypass library interceptors. We initially tried adding explicit **`@Transactional`** annotations to database read paths (like `StatefulAgentStoreImpl.load`), but Hibernate's `TransactionScopedSession` still strictly demanded a wider `RequestContext` from the caller.
3. **Native Mode (Failed Attempt 2 - ManagedExecutor)**: We tried migrating the manual `Thread.ofVirtual()` to Quarkus's injected `ManagedExecutor` expecting the `smallrye-context-propagation` extension to forward the context, but this still produced the exact same exception under Native AOT.
4. **Native Mode (Failed Attempt 3 - Mutiny Uni)**: We attempted to leverage Quarkus's native reactive engine by returning a `Uni<Response>` and emitting the block on the Mutiny worker infrastructure. However, context propagation was still lost in the native binary.
5. **Native Mode (Mitigation - AsyncResponse)**: Due to the aggressive interceptor stripping in GraalVM for unmanaged threads, Native isolated contexts remain elusive for this specific architecture. We reverted to the original `Thread.ofVirtual().start()` model, but replaced the immediate return with a JAX-RS `@Suspended AsyncResponse`. This ensures that when the native context inevitably fails, the API correctly surfaces a `500 Server Error` back to the client instead of a misleading `202 Accepted`.
6. **Native Mode (The Fix - `@VirtualThreads` ExecutorService)**: The [Quarkus Virtual Threads guide](https://quarkus.io/guides/virtual-threads#inject-the-virtual-thread-executor) documents the canonical solution. Quarkus stores CDI request scope inside a Vert.x *duplicated context*. Raw `Thread.ofVirtual()`, `ManagedExecutor`, and Mutiny workers all create threads that do NOT inherit this duplicated context. The solution is to inject `@VirtualThreads ExecutorService` and submit tasks via `vThreads.execute(...)`. This Quarkus-managed executor properly propagates the duplicated context (and therefore the CDI RequestScope) into spawned virtual threads. ✅ **Confirmed working in native mode.**

> [!CAUTION]
> **Critical Maven Build Flag**: All fixes for this issue appeared to fail because the native build command used `-pl arcclaw-app` without `-am` (also-make). This meant `arcclaw-core` (where the fixes lived) was never recompiled into the native binary. Always use:
> `mvnw clean package -pl arcclaw-app -am -Pnative -DskipTests`

---

## 14. Deployment & Container Troubleshooting

### 🔴 Failure: Docker Build Fails with `lstat /arcclaw-app/target/... no such file or directory`
**Symptom:** Running `docker compose up --build` instantly crashes during the `COPY arcclaw-app/target/*-runner` step.
**Root Cause:** The `Dockerfile.native` is intentionally lightweight and does *not* contain Java compilation instructions. It expects the GraalVM native binary to already exist on the host machine before Docker is invoked. The user skipped Step 2 of the `README.md` (Native compilation).
**Resolution:** Run the Maven package command locally *before* running `docker compose`:
`.\mvnw.cmd clean package -pl arcclaw-app -Pnative "-Dquarkus.native.container-build=true" "-Dquarkus.native.container-runtime=podman" -DskipTests`

### 🔴 Failure: Maven Native Build Fails with `Cannot get an executable name when no container runtime is available`
**Symptom:** When running `.\mvnw.cmd clean package -Pnative`, the Maven build immediately crashes with `java.lang.IllegalStateException: Cannot get an executable name when no container runtime is available`.
**Root Cause:** On Windows, simply opening the Podman Desktop GUI does not automatically boot the underlying Linux virtual machine or export the Docker daemon API endpoints to the host PowerShell session. Quarkus cannot find a running container engine to orchestrate the GraalVM compile step.
**Resolution:** You must manually start the Podman machine and bind the named pipe to the current PowerShell window before running Maven:
1. Start the machine: `podman machine start`
2. Export the Docker API pipe: `$env:DOCKER_HOST="npipe:////./pipe/podman-machine-default"`
3. Re-run your `mvnw.cmd` native build.

### 🔴 Failure: Docker Compose Fails with `bind: address already in use` for TCP port `11434`
**Symptom:** During `docker compose up`, the startup halts with `Error response from daemon: cannot listen on the TCP port: listen tcp4 :11434: bind: address already in use`.
**Root Cause:** You likely have the official Windows/Mac Ollama application installed naturally on your host machine. The background desktop daemon is already occupying port `11434`, preventing Docker from binding the internal Ollama container to the same host port.
**Resolution:** Quit the local Ollama desktop application via the system tray, OR remap the container's exposed port in `docker-compose.yaml` (e.g. `11435:11434`).

### 🔴 Failure: SQLite Permission denied in Docker.
**Symptom:** The `arcclaw` container crashes on startup complaining that SQLite cannot write to `/app/data` due to a `Permission denied` error.
**Root Cause:** Docker daemon creates named volumes as `root:root` by default, preventing the rootless `arcclaw` user (UID 1000) inside the container from writing the database.
**Resolution:** We implemented an `init-perms` service in `docker-compose.yaml` to automatically `chown` the volume to UID 1000 before startup.

### 🔴 Failure: Native Container Ollama Error `{"error":"model is required"}`
**Symptom:** When running the native container via `docker compose up`, triggering an agent loop immediately fails with `HttpException: {"error":"model is required"}`.
**Root Cause:** 
This is a deeply rooted bug in Quarkus LangChain4j version `0.26.1` regarding how it handles native AOT (Ahead-Of-Time) compilation.
If the `ollama` extension is enabled, the model ID is locked at build-time (`FixedRuntimeConfig`), strictly ignoring runtime environment variables.
However, if you attempt to *disable* the extension and write a custom CDI producer, the Native GraalVM processor strips the `OllamaChatRequest` payload classes out of the binary (since it assumes they are unused). At runtime, Jackson serializes an empty `{}` JSON object, which Ollama rejects.
**Resolution:** You must completely abandon the `provider: ollama` configuration block. Instead, configure the application to use the `provider: openai` extension.
Ollama hosts a perfectly compatible native OpenAI API at `http://ollama:11434/v1`. Unlike the Ollama extension, the `quarkus-langchain4j-openai` extension perfectly supports robust, dynamic `model-name` injection at runtime in native mode without any Reflection stripping bugs:
```yaml
langchain4j:
  chat-model:
    provider: openai
  openai:
    base-url: "http://ollama:11434/v1"
    api-key: "ollama-key-can-be-anything"
    chat-model:
      model-name: "${OLLAMA_MODEL:llama3.2:1b}"
```

### 🔴 Failure: Model defaults to `gpt-4o-mini` instead of Ollama model
**Symptom:** Setting `langchain4j.openai.model-name` doesn't work; the application still attempts (and fails) to download weights for `gpt-4o-mini`.
**Root Cause:** The property key for the OpenAI extension is nested one level deeper than you might expect.
**Resolution:** The property MUST be `langchain4j.openai.chat-model.model-name` (notice the extra `.chat-model.` nested block), otherwise the framework ignores it and uses the extension's default (`gpt-4o-mini`).

### 🔴 Failure: HTTP 500 `model 'llamaX' not found`
**Symptom:** The native container runs perfectly, but the first AI request fails with: `ReasoningEngine failed at iteration 1: dev.langchain4j.exception.ModelNotFoundException: {"error":{"message":"model 'llama3.2:1b' not found","type":"api_error","param":null,"code":null}}`.
**Root Cause:** The `model is required` compilation defect has been completely resolved, and the application is correctly sending the model name to the `ollama` Docker container. However, the Docker container itself is freshly spun up and has not yet downloaded the actual model weights (e.g. `llama3.2:1b`) from the internet.
**Resolution:** Our `docker-compose.yaml` includes an `ollama-init` sidecar that automates this download on startup. However, if you removed that service or changed the `arcclaw` dependency to simply wait for `service_started`, you must pull the model manually by executing into the running container:
```powershell
docker compose exec ollama ollama pull llama3.2:1b
```
*(If you want to skip the automated pull sidecar entirely, change `depends_on: ollama-init` back to `depends_on: ollama: condition: service_started` in your `docker-compose.yaml`)*

### 🔴 Failure: Dockerfile.native Build Fails with `mkdir: cannot create directory '/app/data': Permission denied`
**Symptom:** During `docker compose up --build`, the build halts at the `RUN mkdir -p /home/arcclaw/... /app/data` step.
**Root Cause:** The `WORKDIR /app` directive implicitly creates `/app` as `root:root`. In `Dockerfile.native`, the user was switched to `USER arcclaw` *before* creating the `/app/data` subdirectory, which blocked access.
**Resolution:** Moved the `RUN mkdir -p` block higher in `Dockerfile.native` (while still executing as root) and appended `&& chown -R arcclaw:arcclaw /app /home/arcclaw` before explicitly dropping privileges down to `USER arcclaw`.

### 🔴 Failure: Native Build Exception `Class initialization of io.quarkiverse.langchain4j.QuarkusJsonCodecFactory... failed`
**Symptom:** During the `Performing analysis` step of a native build, the process halts with a `NullPointerException` inside `QuarkusJsonCodecFactory$ObjectMapperHolder.<clinit>` or `QuarkusJsonCodecFactory$SnakeCaseObjectMapperHolder.<clinit>` because `Arc.container()` is null.
**Root Cause:** GraalVM's AOT analysis sometimes attempts to initialize LangChain4J's internal JSON codec factories at build-time. However, these classes require the CDI container, which only exists at run-time.
**Resolution:** Explicitly defer the initialization of these holders to run-time via native build arguments in your `pom.xml`:
```xml
<quarkus.native.additional-build-args>
  --initialize-at-run-time=io.quarkiverse.langchain4j.QuarkusJsonCodecFactory$ObjectMapperHolder,
  --initialize-at-run-time=io.quarkiverse.langchain4j.QuarkusJsonCodecFactory$SnakeCaseObjectMapperHolder
</quarkus.native.additional-build-args>
```

### 🔴 Failure: Docker Compose Build Fails at `microdnf update`
**Symptom:** Running `docker compose build --no-cache` fails during the `Dockerfile.native` build step at `RUN microdnf update -y` with an exit code of 1.
**Root Cause:** On Windows, the Podman virtual machine's network stack can sometimes lose internet connectivity or become de-synchronized from the host OS network (e.g. after waking from sleep, VPN changes, or long periods of idle time). This prevents the build container from reaching the RedHat UBI repositories.
**Resolution:** Force a hard reset of the Podman machine to re-initialize its network connection to the host:
```powershell
podman machine stop
podman machine start
```

### 🔴 Failure: Docker Compose `cannot listen on the TCP port... bind: address already in use`
**Symptom:** Running `docker compose up -d` fails with `Error response from daemon: cannot listen on the TCP port: listen tcp4 :11434: bind: address already in use`.
**Root Cause:** The native Windows Ollama application is running in the background (usually in the system tray) and is already bound to port 11434 on the host machine. This prevents the Docker container `ollama` service from binding its exposed port to the host.
**Resolution:** Right-click the Ollama icon in the Windows system tray and select "Quit Ollama". Then run `docker compose up -d` again.

### 🔴 Failure: Docker Compose `open //./pipe/dockerDesktopLinuxEngine: The system cannot find the file specified`
**Symptom:** Running `docker compose up` on Windows with Podman Desktop installed returns a connection error for `dockerDesktopLinuxEngine`.
**Root Cause:** Podman Desktop exposes its API socket at `//./pipe/podman-machine-default`. The standalone `docker compose` binary defaults to searching for Docker Desktop's named pipe unless explicitly instructed otherwise or unless Podman Desktop's API compatibility routing is enabled.
**Resolution:** Open Podman Desktop -> Go to Settings -> Resources -> Podman Provider -> click "Setup" next to "Docker API compatibility". Alternatively, set the environment variable directly in your terminal before running compose: `$env:DOCKER_HOST="npipe:////./pipe/podman-machine-default"`.

### 🔴 Failure: Unable to open JDBC Connection for DDL execution on Windows (`quarkus:dev`)
**Symptom:** Running `.\mvnw.cmd quarkus:dev` on Windows fails with `Unable to open JDBC Connection` because the `.db` parent path `./data` does not exist.
**Root Cause:** The SQLite JDBC driver does NOT automatically create missing parent directories. On Linux/Docker, `root:root` volume mapping hides this, but naked Windows filesystems block Hibernate ORM from initializing its DDL scripts.
**Resolution:** Replaced the empty `ArcClawApp` with a customized `@QuarkusMain` entry method that boots prior to Hibernate. It uses `ConfigProvider` to natively parse `ARCCLAW_DATA_DIR` and runs `Files.createDirectories()` securely.

### 🔴 Failure: SQLITE_ERROR (index already exists) during DDL Update
**Symptom:** When running `quarkus:dev` on a fresh database, Hibernate ORM throws `[SQLITE_ERROR] SQL error or missing database (index idx_checkpoint_session_role already exists)`.
**Root Cause:** `AgentCheckpoint` defined both a `@UniqueConstraint` and a redundant secondary `@Index` on `(sessionId, agentRole)`. SQLite automatically creates an index to enforce unique constraints. When Hibernate's schema generation (`update`) ran, it blindly issued `CREATE INDEX` for the duplicate index without the `IF NOT EXISTS` dialect syntax, crashing the boot sequence.
**Resolution:** Removed the explicit `@Index` definition from `AgentCheckpoint.java` since the `@UniqueConstraint` natively guarantees indexing.

### 🔴 Failure: Netty crashes with `sun.misc.Unsafe unavailable` on REST API request
**Symptom:** The Quarkus app boots correctly, but the first incoming HTTP request to `/api/v1/sessions` throws a massive `UnsupportedOperationException: sun.misc.Unsafe unavailable` from `io.netty.util.internal.CleanerJava9`.
**Root Cause:** In JDK 25, JEP 471 terminally deprecated and restrictively blocked the `sun.misc.Unsafe` memory-access methods by default. Netty heavily relies on these methods to allocate low-level byte buffers (especially on Windows where native epoll isn't available).
**Resolution:** Hardcoded `<jvmArgs>--sun-misc-unsafe-memory-access=allow</jvmArgs>` into both the `quarkus-maven-plugin` and `maven-surefire-plugin` within `arcclaw-app/pom.xml`. This explicitly re-enables `Unsafe` memory allocations for Netty until the library formally migrates to the Foreign Function & Memory API (FFM).

### 🔴 Failure: Test Teardown Crashes with `java.lang.IllegalAccessError` (JBoss Threads)
**Symptom:** When running Quarkus tests continuously (via `quarkus:dev` 'r' command), the testing thread abruptly faults with `module java.base does not open java.lang to unnamed module... to use the thread-local-reset capability on Java 24 or later`.
**Root Cause:** JBoss Threads dynamically handles thread pooling lifecycle resets by accessing private internals of `java.lang.Thread`. JDK 24+ explicitly encapsulates `java.lang`, rejecting these reflection calls natively.
**Resolution:** Explicitly added `--add-opens java.base/java.lang=ALL-UNNAMED` into the `<jvmArgs>` configuration tree for the `quarkus-maven-plugin` in both `arcclaw-parent/pom.xml` and `arcclaw-app/pom.xml`. 

### 🔴 Failure: `ClassNotFoundException for org.junit.internal.AssumptionViolatedException` in Quarkus Test Runner
**Symptom:** When initializing tests in Quarkus Dev Mode, an exception `Failed to load class org.junit.internal.AssumptionViolatedException: only supporting org.opentest4j.TestAbortedException for aborted execution` blocks the log stream.
**Root Cause:** The `quarkus-junit5` integration attempts to reflectively load legacy JUnit 4 violation interceptors natively because they occasionally bubble up through third-party dependencies. If `junit:junit` is missing from the test scope, the Jupiter classloader drops an ugly but harmless exception.
**Resolution:** Merged `<groupId>junit</groupId><artifactId>junit</artifactId>` back into the `<scope>test</scope>` block within `arcclaw-app/pom.xml` to immediately fulfill the classloader constraint.

---

## 15. LLM Operations Troubleshooting (Phase 14.1)

### 🔴 Failure: Silent Context Window Exhaustion (HTTP 400)
**Symptom:** The Agent loop crashes silently or throws an unhandled LangChain4J HTTP 400 exception after a tool executes successfully.
**Root Cause:** The agent executed a tool (e.g. `cat`) that returned >10k characters. Our `AgentLoop` blindly appended the entire raw String to the `ToolExecutionResultMessage`. On the next reasoning tick, this bloated history blew past the local `llama3.2:1b` context limit.
**Resolution:** Implemented explicit 4000-character defensive truncation on `step.observation()` directly inside `LangChainReasoningEngine.java`.

### 🔴 Failure: Infinite Hallucination Loop on Bad Arguments
**Symptom:** The Agent continuously attempts to call the exact same tool, failing instantly, burning through tokens until hitting the `maxIterations` circuit breaker. 
**Root Cause:** The local LLM hallucinated invalid JSON structure (e.g., trailing commas) for the Tool arguments payload. The `LangChainReasoningEngine` caught the `JsonParseException`, swallowed it, and mapped it to an empty `Map.of()`. The tool validated the empty map, failed for missing arguments, and the generic failure prompted the LLM to simply "try again", reproducing the identical malformed JSON structure.
**Resolution:** Stopped swallowing the exception. Now actively catching `JsonParseException` and returning a `FINISH` `ReasoningStep` injected with `"System Error: You provided invalid JSON formatting... Please ensure strict JSON compliance."` which halts the loop or allows explicit LLM self-correction.

### 🔴 Failure: HTTP 500 `Connection refused` in Dev Mode
**Symptom:** Running `quarkus:dev` locally and triggering the agent API returns `ReasoningEngine error: io.netty.channel.AbstractChannel$AnnotatedConnectException: Connection refused: getsockopt: localhost/127.0.0.1:11434`.
**Root Cause:** The application's `%dev` profile in `application.yaml` connects to `localhost:11434` for the Ollama inference engine. This error means there is no local process listening on that port. This typically happens if you haven't started your `ollama` Docker container via `docker compose up -d ollama` or if you quit the local Windows/Mac Ollama desktop app.
**Resolution:** Either start the local background Ollama application (from the system tray) or rely purely on the project's Docker stack by running `docker compose up -d` before booting `quarkus:dev`.

---

## 16. CLI Dual-Mode Operations Troubleshooting (Phase 15)

### 🔴 Failure: CLI Command `ask` Hangs Indefinitely after Completion
**Symptom:** Typing `arcclaw ask "goal"` correctly runs the agent, solves the task, prints the final result, but the terminal cursor hangs permanently instead of exiting back to bash. 
**Root Cause:** The top-level `ArcClawCli.java` (or main method dispatch block) contained `Quarkus.waitForExit()`. This function holds the Quarkus thread open waiting for a daemon stop signal, which directly conflicts with Picocli's desired application exit boundary.
**Resolution:** Remove `Quarkus.waitForExit()` from the root Picocli `CommandLine.execute()` scope. Re-assign `Quarkus.waitForExit()` explicitly *only* inside the background-intended `ServeCommand.run()` block.

### 🔴 Failure: `Variable used in lambda expression should be final or effectively final`
**Symptom:** Compilation fails inside `AgentLoop.java` during observer stream iterations. 
**Root Cause:** The `step` variable was reassigned via `withObservation` before attempting to inject it into `.forEach(o -> o.onStepComplete(..., step))`.
**Resolution:** Capture the mutated state inside a strictly defined block-local variable prior to the lambda: `final ReasoningStep completedStep = step; observers.forEach(o -> o.onStepComplete(..., completedStep));`.

---

## 17. Channel Adapter & Camel Integration Troubleshooting (Sprint 2A)

### 🔴 Failure: IDE Resolution Issue (`io.quarkus.test` cannot be resolved)
**Symptom:** After adding `quarkus-camel-bom` to the parent POM, the IDE (VS Code/Eclipse) shows red lines on test imports like `io.quarkus.test.junit.QuarkusTest`.
**Root Cause:** Adding multiple BOMs can sometimes confuse the IDE's dependency resolution cache, especially if the new BOM transitively affects test-scoped dependencies.
**Resolution:** Hard-pin the versions for `quarkus-junit5` and `quarkus-junit5-mockito` inside `arcclaw-app/pom.xml` using `<version>${quarkus.version}</version>` to force-repair the IDE's classpath mapping.

### 🔴 Failure: `ClassNotFoundException: dev.langchain4j.service.IllegalConfigurationException`
**Symptom:** Maven tests fail during startup with a `ClassNotFoundException` related to LangChain4j internal classes.
**Root Cause:** The `camel-quarkus-bom` contains its own internal LangChain4j component mappings which transitively override the versions used by the Quarkiverse LangChain4j extensions. This results in binary incompatibility between the core library and the Quarkus-specific layer.
**Resolution:** Explicitly import the `io.quarkiverse.langchain4j:quarkus-langchain4j-bom` into the parent `pom.xml` `<dependencyManagement>` section, placing it strictly *above* the Camel BOM to ensure the correct version is locked.

### 🔴 Failure: `SurefireBooterForkException` (Windows absolute paths)
**Symptom:** Maven tests crash on Windows with `Boot Manifest-JAR contains absolute paths in classpath`.
**Root Cause:** A known JDK 21+ Windows limitation where the default Surefire "Manifest JAR" approach fails to handle long absolute file paths correctly.
**Resolution:** Set `<useManifestOnlyJar>false</useManifestOnlyJar>` in the `maven-surefire-plugin` configuration in `arcclaw-app/pom.xml`.

### 🔴 Failure: `BeanDeploymentException` / CDI Interceptor on Private Method
**Symptom:** The Quarkus application fails to start during tests, complaining that a method with an interceptor (like `@ActivateRequestContext`) is `private`.
**Root Cause:** The Quarkus ArC container uses proxies for intercepted methods. Proxies require that intercepted methods be overridable (i.e., not `private` and not `final`).
**Resolution:** Change method visibility from `private` to package-private (default) or `protected`. (e.g., Change `private void runAgent(...)` to `void runAgent(...)`).

### 🔴 Failure: Dev mode crash (`StatusLine.setMessage` NPE)
**Symptom:** Quarkus dev mode fails to start with `NullPointerException: Cannot invoke "io.quarkus.dev.console.StatusLine.setMessage(String)" because "this.prompt" is null`.
**Root Cause:** The dev console prompt can fail to initialize in certain terminal environments, causing `AeshConsole` to receive a null prompt during console redraw.
**Resolution:** Disable the dev console prompt in the `%dev` profile:
```yaml
"%dev":
  quarkus:
    console:
      enabled: false
```
### 🔴 Failure: `NoSuchElementException` for `Optional<String>` Bot Token
**Symptom:** Quarkus crashes during Camel route registration with `java.util.NoSuchElementException` when using `@ConfigProperty Optional<String> botToken`.
**Root Cause:** Even if declared as `Optional`, Camel's endpoint URI construction (e.g., `from("telegram:bots?authorizationToken=" + botToken.get())`) will trigger `get()` before the container has verified the presence of the configuration.
**Resolution:** Always provide a `defaultValue = ""` in the `@ConfigProperty` annotation, or standardize to a required `String` and check for `isBlank()` inside the `configure()` method.

### 🔴 Failure: Camel Route Discovery Failure (`AssertionError: expected <true> but was <false>`)
**Symptom:** `TelegramChannelRouteTest` fails because the `CamelContext` contains zero routes, despite the `RouteBuilder` being annotated with `@ApplicationScoped`.
**Root Cause:**
1. **Missing Jandex Index:** In multi-module projects, Quarkus only scans modules with a `META-INF/jandex.idx` or a `META-INF/beans.xml`.
2. **Lazy Initialization:** The bean may not be instantiated if not explicitly injected or used.
3. **Missing Camel Bean Component:** `.bean(TelegramAgentHandler.class, "receiveMessage")` requires `camel-quarkus-bean`. Without it, route creation fails when the test tries to add routes.
4. **Context Not Started in Test:** The `CamelContext` can be present but not started, leaving the runtime routes list empty.
**Resolution:** 
- Add `jandex-maven-plugin` to the module's `pom.xml`.
- Use `mvn clean test` to ensure the index is not stale.
- Add `camel-quarkus-bean` to `arcclaw-app/pom.xml` when using `.bean(...)` in Camel routes.
- In `TelegramChannelRouteTest`, call `camelContext.addRoutes(route)` and `camelContext.start()` before asserting route existence.
**Notes:** When running with a mock Telegram token in tests, the Telegram consumer will log 404 polling warnings; these are expected and do not fail the test.

### 🔴 Failure: Telegram polling 404 noise during unrelated JUnit tests
**Symptom:** Running any `@QuarkusTest` (even non-Telegram tests) logs repeated `TelegramConsumer` 404 warnings because the Telegram route starts with a mock token.
**Root Cause:** The Telegram route is registered and its polling consumer starts in the test profile, which triggers real HTTP calls against Telegram.
**Resolution:**
- Add a config toggle (e.g., `arcclaw.channel.telegram.enabled`) in the route and set it to `false` in `%test`.
- Use a `QuarkusTestProfile` in `TelegramChannelRouteTest` to re-enable the route only for that test.
- Optionally reduce the Telegram component log level to `ERROR` in that profile to keep output clean.

### 🔴 Failure: `IllegalStateException: Invalid use of @InjectMock` on @Dependent Beans
**Symptom:** Test crashes with `Invalid use of io.quarkus.test.InjectMock - the injected bean does not declare a CDI normal scope but: jakarta.enterprise.context.Dependent`.
**Root Cause:** `@InjectMock` only works on beans with a normal scope (like `@ApplicationScoped`). Common framework beans like `ProducerTemplate` (Camel) and `ManagedExecutor` (MicroProfile) are often `@Dependent`.
**Resolution:** Use `QuarkusMock.installMockForType(mockInstance, BeanType.class)` in the test setup instead.

### 🔴 Failure: `QuarkusMock` fails because "bean is already in use"
**Symptom:** `java.lang.IllegalStateException: Actually, the bean is already in use` when calling `QuarkusMock.installMock(...)`.
**Root Cause:** The bean being mocked has already been injected into the System Under Test (SUT) or used by the container during the initial boot sequence before the test's `@BeforeEach` method had a chance to install the mock.
**Resolution:** Move `QuarkusMock` installation to a static **`@BeforeAll`** method. This ensures the mocks are registered with the CDI container *before* it starts wiring the beans for the test class.

---

## 18. Telegram Agent Test Sprint 2A

### ✅ Solved: `TelegramAgentHandlerTest` Stubborn Mocking
**Status:** Resolved.
**Solution:** The issue stemmed from `QuarkusMock` failing to consistently replace beans that were already proxied or instantiated, particularly with mixed scopes (`Dependent` vs `ApplicationScoped`).
**Approach:**
1.  Replaced `QuarkusMock` in `@BeforeAll` with `@InjectMock` for normal-scoped beans (`AgentLoop`, `WorkspaceManager`, `StatefulAgentStore`, `ReasoningEngine`).
2.  Manually mocked `Dependent` beans (`ProducerTemplate`, `ManagedExecutor`) in `@BeforeEach` to avoid scope conversion issues.
3.  **Critical Fix:** Unwrapped the `ClientProxy` of `TelegramAgentHandler` to inject these manual mocks directly into the underlying instance's private fields, bypassing the proxy's limitations.
4.  Ensured correct scope handling for `StatefulAgentStore` by using `@InjectMock` which handles the proxy delegation correctly.

### 🟡 YAML Profile Parsing Nuance
**Observation:** Ensure that the `%test` profile in `application.yaml` is correctly structured and doesn't conflict with legacy `.properties` files that might still exist in the target directory after a non-clean build.

---

## 19. Telegram Integration & Daemon Mode Troubleshooting Sprint 2A

### 🔴 Failure: Quarkus Dev Console NPE
**Symptom:** `java.lang.NullPointerException: Cannot invoke "java.io.Console.writer()" because the return value of "java.lang.System.console()" is null` during startup.
**Root Cause:** Running inside some IDE terminals or non-interactive shells where `System.console()` is not available, but Quarkus Dev UI tries to use it.
**Resolution:** Disable the Dev Console in `application.yaml` for the dev profile:
```yaml
"%dev":
  quarkus:
    dev:
      console:
        enabled: false
```

### 🔴 Failure: Telegram Bot Token Not Loaded (Windows/PowerShell/Multi-Module)
**Symptom:** `TelegramException: Could not GET ... 404 Not Found` and `authorizationToken=mock-token` in logs, despite `.env` file being present.
**Root Cause:** When running `quarkus:dev` against a submodule (e.g., `-pl arcclaw-app`), SmallRye Config evaluates paths relative to the submodule directory. The standard `config: locations: "../.env"` directive in `application.yaml` natively fails to propagate dynamically parsed properties early enough during boot, leaving the `.env` variables unloaded.
**Resolution:** Explicitly implemented a custom `ConfigSourceProvider` SPI (`DotenvConfigSourceProvider.java`) that scans both `./.env` and `../.env` natively, parsing key-value pairs and injecting them into the configuration tree with a standard ordinal (`295`), completely bypassing OS-level shell and classloader limitations.

### 🔴 Failure: Camel Routes Not Registering in Daemon Mode
**Symptom:** Application starts, but `Camel Context Status` shows 0 routes, or `TelegramChannelRoute` is not active.
**Root Cause:** In programmatic daemon mode (implementing `QuarkusApplication`), CDI beans for Camel routes might be instantiated but not automatically added to the `CamelContext` if the context auto-discovery didn't trigger before the main loop.
**Resolution:**
1. Inject `CamelContext` and the Route bean (`TelegramChannelRoute`) into the main command (`ServeCommand`).
2. Explicitly check and add the route if missing:
```java
if (camelContext.getRoute("telegram-bot") == null) {
    camelContext.addRoutes(telegramChannelRoute);
}
```
3. Ensure `camelContext.start()` is called if the context is stopped.

## Sprint 2A & 2B: Telegram Channel & Human-in-the-Loop State Machine

### 1. Telegram Polling `409 Conflict`
* **Symptom:** `org.apache.camel.component.telegram.TelegramException: 409 Conflict: terminated by other getUpdates request; make sure that only one bot instance is running`
* **Root Cause:** Quarkus Dev Mode hot-reloads occasionally fail to kill the old Camel networking thread before starting a new one. Telegram strictly limits polling to one active connection per bot token.
* **Resolution:** 1. Stop the Quarkus application (`Ctrl+C`).
  2. Kill lingering Java processes (Windows: `taskkill /F /IM java.exe`, Linux: `pkill -f java`).
  3. Verify no orphaned Docker containers are running with the same `.env` file (`docker ps`).
  4. Restart the Quarkus application cleanly.

### 2. Local LLM Emitting Raw JSON / Hallucinating
* **Symptom:** The agent prints raw JSON tool schemas to the Telegram chat (e.g., `{"name": "gh", "parameters": ...}`) instead of executing them, or hallucinates blatantly incorrect facts (e.g., "John Simpson invented C++").
* **Root Cause:** The local model (e.g., `llama3.2:1b`) lacked the parameter capacity (neural size) to hold the complex ArcClaw system prompt, adhere to the strict LangChain4j tool-calling JSON schema, and recall factual data simultaneously.
* **Resolution:** Migrated to the Groq Cloud API using the OpenAI compatibility layer in `application.yaml`. Pointed `base-url` to `https://api.groq.com/openai/v1` and upgraded the model to `llama-3.3-70b-versatile` which has native tool-calling training.

### 3. Agent Stuck in `READONLY` Mode
* **Symptom:** Agent refuses to execute tools, returning: `Security violation... The current autonomy level does not permit this operation.`
* **Root Cause:** Two issues: 
  1. Enum drift (`READ_ONLY` vs `READONLY`, `PARTIAL` vs `SUPERVISED`) caused config injection silently to fail. 
  2. `TelegramAgentHandler` injected the config property but failed to actively apply it to the `WorkspaceContext` via `workspaceContext.setAutonomyLevel()`.
* **Resolution:** Aligned all `AutonomyLevel` enums across the core and applied the setting dynamically within the Vert.x offloaded thread block before invoking `agentLoop.run()`.

### 4. SQLite `CHECK` Constraint Failure on Pause
* **Symptom:** `[SQLITE_CONSTRAINT_CHECK] A CHECK constraint failed (CHECK constraint failed: status in ('IN_PROGRESS','COMPLETED','FAILED'))`
* **Root Cause:** Added the `AWAITING_APPROVAL` state to the `CheckpointStatus` Java enum. However, SQLite does not support `ALTER TABLE` for modifying existing `CHECK` constraints generated previously by Hibernate.
* **Resolution:** In local dev mode, deleted the `arcclaw.db` file from the `data/` directory and allowed Hibernate to recreate the schema from scratch with the new enum values.

### 5. The "State Machine Overwrite" Bug (Phantom Approvals)
* **Symptom:** After the agent requests human approval, replying `/approve` results in `❌ No pending approval found for your session.` The database shows the session as `FAILED`.
* **Root Cause:** The `runLoop` internal method correctly caught `RequiresApprovalException` and set the state to `AWAITING_APPROVAL`. However, the public wrapper `AgentLoop.run()` saw that `success == false` and blindly overwrote the state to `FAILED` before returning.
* **Resolution:** Added an explicit bypass in the `AgentLoop.run()` wrapper: `if (result.awaitingApproval()) { // DO NOTHING } else if (result.success()) { ... }`.

### 6. The "Phantom Session" UUID Disconnect
* **Symptom:** Still receiving `❌ No pending approval found` despite fixing the overwrite bug.
* **Root Cause:** The Telegram handler was generating a new `UUID.randomUUID()` when a previous session failed, but the `/approve` command was strictly querying the deterministic `baseId` derived from the Telegram `chatId`. 
* **Resolution:** Enforced strict usage of the deterministic `baseId` in `TelegramAgentHandler`. Instead of generating a new UUID on failure, the system now calls `workspaceManager.terminateSession(baseId)` to wipe the old SQLite record and starts fresh under the exact same ID.

### 7. Groq `400 Bad Request` on FINISH Tool
* **Symptom:** `tool call validation failed: attempted to call tool 'FINISH' which was not in request.tools`
* **Root Cause:** The legacy ReAct-style system prompt instructed the LLM: `"When you have finished, use a tool named 'FINISH'"`. Modern native tool-calling LLMs obediently generated a `<function=FINISH>` payload, which Groq rejected because `FINISH` wasn't registered in the LangChain4j tool array.
* **Resolution:** Updated the System Prompt to native conversational standards: `"When you have gathered enough information to fulfill the request, simply output the final answer as plain text to the user. Do not call any further tools."`

### 8. `ProcessBuilder.start() debug` RuntimeException
* **Symptom:** Scary stack trace in console starting with `java.lang.RuntimeException: ProcessBuilder.start() debug` when executing a sandbox tool.
* **Root Cause:** This is **not a crash**. The Quarkus Dev Mode Java Agent intercepts the JDK's `ProcessBuilder` and throws a fake exception internally to capture the stack trace. This ensures Quarkus can kill orphaned child processes (like `gh` or `podman`) if you hit `Ctrl+C`.
* **Resolution:** Ignore. This is expected dev-mode behavior and disappears in production binaries.

---

### 11. Sprint 2B/2C: Human-in-the-Loop & State Machine Issues

#### 11.1 "No pending approval found" after /approve
* **Issue:** User clicks `/approve` in Telegram, but the bot responds with "No pending approval found".
* **Root Cause:** The `AgentLoop.run()` wrapper was catching the result and, seeing that the internal loop finished (even if it was a pause), it was blindly updating the checkpoint to `COMPLETED` or `FAILED`, overwriting the `AWAITING_APPROVAL` status.
* **Resolution:** Modified `AgentLoop.run()` to check `result.awaitingApproval()`. If true, bypass the final checkpoint status update.

#### 11.2 "Phantom Sessions" (Old state stuck)
* **Issue:** User tries to start a new task via Telegram, but the bot resumes an old completed or failed task.
* **Root Cause:** Deterministic ID mapping (`chatId:userId`) means the session ID never changes. If the old state is still in the database as `COMPLETED`, the agent loop loads it and thinks it has nothing to do.
* **Resolution:** In `TelegramAgentHandler`, check if the existing checkpoint status is `COMPLETED` or `FAILED`. If so, invoke `workspaceManager.terminateSession(id)` to wipe the records before starting the new loop.

#### 11.3 REQUIRES_APPROVAL_EXCEPTION on Shell Tools
* **Issue:** Simple bash tools fail in `SUPERVISED` mode.
* **Root Cause:** Sensitive operations like `EXECUTE_SHELL` are gated by the `ToolRegistry` to protect the host/container.
* **Resolution:** This is intended. The user must manually `/approve` these operations via Telegram.

### 12. Sprint 3A (Persistent ChatMemory)

#### 12.1 The "Illusion of Memory" / Telegram 400 Empty Message Crash
* **Symptom:** After successfully completing a task, the next message sent to the Telegram bot results in no response, and a `400 Bad Request` appears in the console from the Telegram API (`message text is empty`). The Groq API response logs show the LLM returning an empty string `""`.
* **Root Cause:** ArcClaw's `AgentLoop` is a task-based state machine, not a continuous chatbot. It bypasses LangChain4j's native `ChatMemory` and manually reconstructs the prompt by interleaving the user's `goal` with the `ReasoningSteps` from SQLite. If a `COMPLETED` session is kept alive for a new prompt, the loop appends the *previous* task's final answer to the very bottom of the prompt as an "Assistant" message. The LLM receives an out-of-order prompt where the last message is already from the Assistant, assumes its turn is over, and outputs nothing.
* **Resolution:** Enforced strict "Task Amnesia" in `TelegramAgentHandler.java`. The Session Continuity Check must explicitly call `workspaceManager.terminateSession(baseId)` to wipe the SQLite record if the previous session was `COMPLETED` or `FAILED`. Every new user message must trigger a fresh, isolated Agent Loop with a clean context window.
* **Validation Note:** The "Secret Code" retrieval failure (where the bot forgets a code word from the previous message) is a confirmed success of this architecture. It proves that the "Task Agent" pattern is correctly isolating execution and preventing context poisoning.


#### 12.2 `UnsatisfiedResolutionException` for Named Models
* **Symptom:** Build fails during CDI validation with `Unsatisfied dependency for type ChatLanguageModel ... and qualifiers [@Identifier("intent-classifier")]`.
* **Root Cause:** In LangChain4j-Quarkus `0.26.1`, automatic generation of `@Identifier`-qualified beans from `application.yaml` named models can be brittle in multi-module projects. If the injection point is in a different module than the configuration, or if Jandex indexing excludes the generated beans, resolution fails.
* **Resolution:** Implemented a manual CDI producer (`ModelProducer.java`). This guarantees the bean is correctly qualified and discovered by ArC across module boundaries.

#### 12.3 `ServiceConfigurationError` in Async LLM Calls
* **Symptom:** Asynchronous LLM tasks (like the Intent Classifier) fail silently and trigger fallback behavior. The console logs show a warning: `java.util.ServiceConfigurationError: io.smallrye.config.SmallRyeConfigFactory: io.quarkus.runtime.configuration.QuarkusConfigFactory not a subtype`.
* **Root Cause:** Executing Quarkus/LangChain4j logic using standard Java async methods like `CompletableFuture.supplyAsync()` runs the task on the unmanaged common `ForkJoinPool`. These raw threads do not inherit Quarkus CDI, OpenTelemetry, or Configuration contexts. When LangChain4j's underlying REST client attempts to lazily load configuration on this unmanaged thread, it causes a classloader/context conflict.
* **Resolution:** Replaced the default executor with the Quarkus/MicroProfile `ManagedExecutor`. By injecting `@Inject ManagedExecutor managedExecutor` and passing it to the async call (`CompletableFuture.supplyAsync(() -> { ... }, managedExecutor)`), Quarkus successfully propagates the CDI and config contexts to the worker thread.

#### 12.4 Metrics "Cold Start" (Lazy Counter Initialization)

* **Symptom:** Navigating to `/q/metrics` immediately after a restart does not show `arcclaw_agent_invocations_total` or `arcclaw_agent_tokens_total`, even if the Micrometer extension is active.
* **Root Cause:** Micrometer counters are lazily initialized. They do not appear in the Prometheus export until they have been incremented at least once.
* **Resolution:** This is expected behavior. The metrics will appear as soon as the first `AgentLoop` reasoning step completes or the first LLM call is recorded by the `MetricsAgentObserver`.

#### 12.5 Podman Availability & Circuit Breaker

* **Symptom:** Sandbox execution fails or falls back to `NAMESPACE` isolation even when Podman is installed.
* **Root Cause:** The `ProcessIsolationExecutor` performs a `podman --version` check at `@PostConstruct`. If Podman is not on the system PATH or fails to respond within 5 seconds, the "Podman Circuit" opens.
* **Resolution:** Ensure the Podman binary is in the environment PATH and the service is responsive. The executor automatically falls back to `NAMESPACE` strategy (if on Linux) or direct execution to maintain system availability.

---

### 13. Multi-Agent Routing & Metrics Verification (Sprint 3B/3C)

#### 13.1 Intent Router Semantic Validation

* **Symptom:** User prompts are consistently routed to the `coordinator` or the wrong specialized agent (e.g., a bug report routing to `brahma` instead of `shiva`).
* **Root Cause:** The 8B classifier model is failing to distinguish between "creating" and "fixing" code, or the `sanitize()` method in `IntentClassifierService` is missing a role mapping.
* **Verification Steps:** * **Test Case A (Creation):** Send *"Write a Java method to calculate factorials."* * **Expected Log:** `[TelegramHandler] Intent classified '...' -> role: brahma`.
* **Test Case B (Correction):** Send *"Why does this code throw a NullPointerException? String s = null; s.length();"* * **Expected Log:** `[TelegramHandler] Intent classified '...' -> role: shiva`.



#### 13.2 Real-time Metrics Verification

* **Symptom:** The metrics endpoint at `/q/metrics` loads, but the `arcclaw_` specific counters are missing.
* **Root Cause:** Micrometer counters are lazily initialized and only appear after the first event is recorded, or the `MetricsAgentObserver` is not being discovered by the `AgentLoop` due to indexing issues.
* **Verification Steps:**
1. Send any valid command to the Telegram bot to trigger an `AgentLoop` run.
2. Monitor the console for the explicit debug hook: `DEBUG: MetricsAgentObserver incrementing counter for role: <role>`.
3. Refresh `http://localhost:8080/q/metrics` and search for:
* `arcclaw_agent_invocations_total{role="brahma"}`
* `arcclaw_agent_invocations_total{role="shiva"}`
* `arcclaw_agent_tokens_total{type="input"}`


#### 13.3 Dynamic Approval Discovery

* **Symptom:** Typing `/approve` or `/deny` in Telegram results in a *"No pending approval found"* message, even though the bot is clearly waiting.
* **Root Cause:** The `handleApproval()` discovery loop in `TelegramAgentHandler` is missing the role string for the active agent (e.g., it checks `coordinator` but the task is stuck in `shiva`).
* **Verification Steps:**
1. Trigger a "dangerous" task (e.g., file deletion) that puts the agent into `AWAITING_APPROVAL`.
2. Confirm the bot asks for permission.
3. Type `/approve`.
4. Verify the console shows the discovery loop successfully locating the session's role (e.g., `shiva`) and resuming the loop.

#### 13.4 Detailed Intent Classifer Steps

##### 🏛️ 1. Coordinator Role

**Routing Intent:** General planning, coordination, and tasks that don't fit specialized buckets.

* **Prompt:** *"What are the next steps to complete the ArcClaw development roadmap?"*
* **Expected Log:** `Intent classified '...' -> role: coordinator`
* **Verification:** Confirm `arcclaw_agent_invocations_total{role="coordinator"}` increments in `/q/metrics`.

##### 🛠️ 2. Brahma Role (The Creator)

**Routing Intent:** Creating new code, writing scripts from scratch, or building features.

* **Prompt:** *"Write a Go script to listen for a web socket and log the incoming messages to a file."*
* **Expected Log:** `Intent classified '...' -> role: brahma`
* **Verification:** Confirm Brahma is invoked for **creation** tasks, not just generic coding keywords.

##### 🕉️ 3. Shiva Role (The Debugger/Transformer)

**Routing Intent:** Fixing existing code, analyzing errors, and interpreting stack traces.

* **Prompt:** *"I'm getting 'IndexOutOfBoundsException' on line 45 of my Python script when the array is empty. How do I fix this?"*
* **Expected Log:** `Intent classified '...' -> role: shiva`
* **Verification:** Ensure the `sanitize()` method correctly prioritized `shiva` over `brahma` for this fix-oriented prompt.

##### 📚 4. Saraswati Role (The Knowledge Base)

**Routing Intent:** Research, facts, explanations, and finding information.

* **Prompt:** *"Who was the lead architect behind the Java 8 Stream API, and what were the primary design goals?"*
* **Expected Log:** `Intent classified '...' -> role: saraswati`
* **Verification:** Confirms the classifier ignores the "Java" keyword (which might trigger Brahma) and correctly identifies the **research** intent.

##### 📊 Verification Dashboard

After running one of each, your metrics endpoint should look like this:

| Metric Name | Tag | Expected Value |
| --- | --- | --- |
| `arcclaw_agent_invocations_total` | `role="coordinator"` | `1.0` |
| `arcclaw_agent_invocations_total` | `role="brahma"` | `1.0` |
| `arcclaw_agent_invocations_total` | `role="shiva"` | `1.0` |
| `arcclaw_agent_invocations_total` | `role="saraswati"` | `1.0` |

---

### 14. 🛠️ Claude Code Reviews and Fixes

#### 🔴 Failure: REST API Sessions Cannot Use Tools

**Symptom:** When triggering agents via the REST API (`POST /api/v1/sessions/{id}/run`), tools like `GithubTool` immediately fail with a `SecurityException: Operation NETWORK_OUTBOUND not permitted`. The Human-in-the-loop (HITL) approval flow never triggers.
**Root Cause:** The `WorkspaceContext` initializes in `READONLY` mode by default. The REST API (`AgentResource`) was not elevating the autonomy level to `SUPERVISED` like the Telegram handler did, permanently trapping REST requests in a strict sandbox.
**Resolution:** Explicitly activate the CDI scope and elevate the autonomy level before calling the `AgentLoop`. (Ensure this is done inside the lambda thread for async calls as well).

```java
WorkspaceScopeHolder.set(sessionId);
workspaceContext.setAutonomyLevel(AutonomyLevel.SUPERVISED);

```

#### 🔴 Failure: Human-in-the-Loop Approval Fails to Resume

**Symptom:** An agent pauses for human approval, but when `/approve` is typed, the system cannot find the paused session or throws errors relating to a session ID named `"current"`.
**Root Cause:** `ToolRegistry.java` had a hardcoded string `"current"` being passed into the `RequiresApprovalException`, which wiped out the actual UUID of the session needing approval.
**Resolution:** Injected `WorkspaceContext` into the `ToolRegistry` and updated the exception to pass the real ID:

```java
throw new RequiresApprovalException(toolName, paramsJson, workspaceContext.getSessionId());

```

#### 🔴 Failure: Semantic Router Triggers Default Coordinator

**Symptom:** A prompt specifically asking to debug a stack trace is sent. The `IntentClassifierService` correctly identifies it as "shiva", but the generic "coordinator" (Vishnu) replies instead.
**Root Cause:** Domain Leakage. The LLM was outputting persona names ("shiva"), but the agents were registered under functional duty names (`@AgentRole("debugger")`). Because "shiva" didn't match "debugger", the `AgentLoop` fell back to the default coordinator.
**Resolution:** Updated `IntentClassifierService.sanitize()` to act as a strict Translation Layer mapping personas to system duties.

```java
if (lower.contains("shiva") || lower.contains("debugger")) return "debugger";

```

*Note: Approval loops and CLI help text must strictly use the functional duty strings (`coder`, `debugger`, `researcher`, `coordinator`).*

#### 🔴 Failure: Concurrent Users Overwriting Files

**Symptom:** When multiple users ask ArcClaw to clone different GitHub repositories simultaneously, the files get mixed up, overwritten, or throw file-lock exceptions.
**Root Cause:** `GithubTool.java` was hardcoding the working directory to `System.getProperty("user.dir")` (the JVM root). All Podman containers were mounting the exact same host directory.
**Resolution:** Updated `GithubTool` to dynamically generate and create a unique, isolated workspace directory for every request using the active session ID.

```java
Path workspaceRoot = Path.of(workspaceRootDir, workspaceContext.getSessionId());
Files.createDirectories(workspaceRoot);

```

#### 🟡 Warning: ArcClawSupervisor Compilation Failure

**Symptom:** Attempting to build a multi-agent orchestration flow using `ArcClawSupervisor` fails to compile, citing a missing 2-parameter `agentLoop.run()` method.
**Root Cause:** The Supervisor class was dead code that had drifted from the main `AgentLoop` signature updates, lacking `sessionId`, `role`, and `maxIterations` parameters.
**Resolution:** Updated the `executeWithAgent` method signature to pass the required 5 parameters, aligning it with the core state management system.

Here is the exact write-up for your `TROUBLESHOOTING.md` guide. You can paste this right below the other architectural fixes.

---

#### 🔴 Failure: The CDI Scope "Amnesia" Bug (ReAct Death Spiral)

**Symptom:** The LLM attempts to call a tool but gets rejected with `Security violation: Operation NETWORK_OUTBOUND not permitted at autonomy level READONLY`. Because the prompt tells the LLM it *does* have permission, the LLM stubbornly retries the tool call 5 times in a row until it crashes with a `MAX_ITERATIONS_EXCEEDED` error.
**The Mystery:** This happens even though your code explicitly calls `workspaceContext.setAutonomyLevel(SUPERVISED)` at the top of the method.
**Root Cause:** When `TelegramAgentHandler` detects a stale session (e.g., the previous task was `COMPLETED`), it calls `workspaceManager.terminateSession()` to wipe the history. However, `terminateSession` also **destroys the CDI Workspace Scope**. When the scope is destroyed, Quarkus throws away your elevated `WorkspaceContext`. Seconds later, when `agentLoop.run()` starts, Quarkus silently generates a brand-new context which defaults back to `READONLY`.
**Resolution:** You must ensure the CDI scope activation and autonomy elevation happen *after* any potential session termination logic, immediately before the AgentLoop runs.

```java
// 1. Session Continuity Check (may destroy the CDI scope!)
statefulAgentStore.load(...).ifPresent(cp -> {
    workspaceManager.terminateSession(baseId); 
});

// 2. RE-ASSERT the scope and elevate autonomy AFTER potential termination
WorkspaceScopeHolder.set(baseId);
workspaceContext.setAutonomyLevel(AutonomyLevel.SUPERVISED);

// 3. Now it is safe to run
agentLoop.run(...);

```

### 🔴 Post Claude Code Review Fixes & HITL & Memory Testing Challenges

#### 1. The "Goldfish Memory" Bug (Premature Context Wipes)

* **Symptom:** The agent successfully completes a tool action and answers a prompt, but if the user asks a follow-up question in the next message, the agent claims the conversation just started.
* **Root Cause:** The `TelegramAgentHandler` was treating `COMPLETED` checkpoints the exact same as `FAILED` checkpoints, calling `workspaceManager.terminateSession()` for both. This aggressively destroyed the LangChain in-memory `ChatMemory` after every successful interaction.
* **Resolution:** Split the continuity check logic. `FAILED` sessions receive a hard wipe (including `terminateSession()`), while `COMPLETED` sessions receive a soft wipe (resetting only the internal `stateJson` tool loop array to `[]` to prevent tool replays, while leaving the LangChain conversational memory completely intact).

#### 2. LLM "Trigger Happy" Behavior (Tool Over-Eagerness)

* **Symptom:** The user asks a conversational question like "Who are you?" or "What are your capabilities?", and the agent inexplicably attempts to trigger the `github` tool to answer it.
* **Root Cause:** The system prompt aggressively commanded the LLM: *"You MUST invoke these tools to fulfill the user's request."* Small, highly literal models (like Llama 3) interpreted this to mean they were forbidden from answering *any* prompt without using a tool first.
* **Resolution (Prompt Engineering):** Added an explicit "escape hatch" to the system prompt: *"DO NOT invoke tools for general greetings or questions about your own identity and capabilities."* This gives the LLM psychological permission to act as a conversational chatbot when external data isn't required.

#### 3. The "Amnesia on Error" Bug (Approval Flow Blindspot)

* **Symptom:** A tool is approved by the user, but the execution fails (e.g., a 404 Not Found from GitHub). Instead of reporting the error, the agent ignores it and replies with: *"Task resumed. What would you like to do next?"*
* **Root Cause:** When `/approve` was typed, the `handleApprovalDecision` method sent a hardcoded system string to the LLM saying: `"Resuming task after approval"`. The LLM saw this string, assumed everything was fine, and ignored the preceding tool error.
* **Resolution:** Updated the hardcoded continuation prompt to explicitly command the LLM to evaluate the result: *"The tool execution has been approved and completed. Read the tool's output. If it is an error, tell me. Otherwise, provide the final answer."*

#### 4. Few-Shot Overfitting (The "OpenClaw" Hallucination)

* **Symptom:** The user requests details for a specific repository (e.g., `rsrini7/mermaid2gif`), but the agent stubborn queries `openclaw/mermaid2gif` or `rsrini7/openclaw`, resulting in 404 errors.
* **Root Cause:** The system prompt used a highly specific few-shot example: *"infer it from the user's prompt (e.g., 'openclaw' -> 'openclaw/openclaw')"*. The small LLM overfitted to this example, treating the word "openclaw" as a strict formatting rule that had to be injected into every GitHub query.
* **Resolution:** Replaced the specific example with a generic one (`apache/camel`) and added a strict negative constraint: *"NEVER hallucinate or guess the repository owner. If the user only provides a repo name, ask them for the full owner/repo path."*

#### 5. Ephemeral Chat Memory (Amnesia on Reboot)

* **Symptom:** After stopping and recompiling the Quarkus native runner, the agent forgets the entire previous conversation, even though the SQLite database is still intact.
* **Root Cause (Architectural Constraint):** While ArcClaw's SQLite database permanently saves stateful checkpoints (like `AWAITING_APPROVAL`), LangChain4j stores the actual conversational chat history in the server's RAM via an in-memory `ChatMemoryStore`. Restarting the JVM/Native process wipes the RAM.
* **Resolution:** Documented as expected behavior. For enterprise persistence across server reboots, LangChain's `ChatMemoryStore` must eventually be wired directly to the SQLite/Hibernate persistence layer.

#### 6. SQLite Check Constraint Violations

* **Symptom:** The agent attempts to pause for human approval, but throws a fatal SQL error: `[SQLITE_CONSTRAINT_CHECK] A CHECK constraint failed`.
* **Root Cause:** The legacy SQLite database schema was generated with a strict `CHECK` constraint that only allowed `IN_PROGRESS`, `COMPLETED`, and `FAILED`. When the Java enum was updated to include `AWAITING_APPROVAL`, the database engine rejected the new string.
* **Resolution:** Deleted the local `arcclaw-meta.db` file. Upon the next startup, Hibernate recreated the schema with the updated constraint rules.

#### 7. Native ProcessBuilder Failures

* **Symptom:** The `GithubTool` throws a `RuntimeException: ProcessBuilder.start() debug` stating `cmd: "gh"` cannot be executed.
* **Root Cause:** The GraalVM native binary is stepping outside the application sandbox to the host OS (WSL2). The operating system did not have the GitHub CLI (`gh`) installed or available in the system `$PATH`.
* **Resolution:** Installed the official `gh` CLI via `apt` on the WSL2 instance and authenticated it via `gh auth login`.

### The transition to a stateful **ChatMemoryStore** in Sprint 3 introduced several complex edge cases involving how an LLM handles historical context versus real-time tool execution. Below is the comprehensive troubleshooting guide for the challenges faced and resolved after the memory integration began.

#### 1. The "Phantom Memory" Bug

* **Symptom:** The agent successfully answered a prompt involving a tool, but immediately claimed "the conversation just started" or "I didn't look up any project yet" when asked a follow-up question.
* **Root Cause:** The `LangChainReasoningEngine` was creating a brand-new message list for every request. Even though the history was being saved to SQLite, the engine was not fetching those messages before sending the next request to the LLM.
* **Resolution:** Injected the `ChatMemoryStore` into the reasoning engine. Added logic to fetch historical messages by `sessionId` and append them to the request list before the current user prompt.

#### 2. Internal Noise Contamination

* **Symptom:** The LLM became confused when asked about previous turns, sometimes trying to "re-debug" internal system strings or tool outputs.
* **Root Cause:** The `ChatMemoryStore` was saving every message, including internal loop mechanics like "Human Approved" or raw tool JSON outputs. This cluttered the context window with technical noise that the LLM tried to interpret as user conversation.
* **Resolution:** Implemented a message filter in the loading logic. Historical messages containing internal keywords (like "The tool execution has been approved") or specific AI thought processes are now skipped during the context-loading phase to keep the conversational history clean.

#### 3. The "Amnesia on Approval" Bug

* **Symptom:** After a user approved a tool execution (e.g., searching for a Linux repo), the agent provided the answer but completely lost track of the original request in the next turn.
* **Root Cause:** The logic to save the user’s goal to memory was originally placed at the *end* of the reasoning loop. When a tool triggered a `RequiresApprovalException`, the thread suspended and the "save" step was never reached. The resume loop only saw the tool's result, not the original question.
* **Resolution:** Relocated the persistence logic to the very top of `AgentLoop.runLoop`. The user's prompt is now committed to the database the millisecond the loop begins, ensuring it exists even if the thread pauses for human interaction.

#### 4. The "Double-Prompt Echo"

* **Symptom:** Logs showed the user's current question appearing twice in the JSON payload sent to the LLM.
* **Root Cause:** Redundant message handling. The `AgentLoop` started saving the prompt to the database immediately, which the engine then fetched. However, the engine was also manually appending the "current goal" at the end of the list.
* **Resolution:** Flipped the logic in `LangChainReasoningEngine`. It now only manually appends the `goal` if it matches an internal system "Resume" command. Standard user prompts are exclusively loaded from the memory fetch to prevent duplication.

#### 5. The "Ghost of the Denied Prompt"

* **Symptom:** After a user denied a tool execution via `/deny`, the bot would spontaneously try to execute that same denied tool later in the conversation.
* **Root Cause:** State misalignment. While Java marked the session as `FAILED`, the LLM’s conversational history still contained the original unfulfilled command. The LLM would see this "open task" in its history and try to "catch up" during the next interaction.
* **Resolution:** Updated the `/deny` handler in the Telegram gateway to inject a "Closure Message" into the chat memory: *"I requested permission to run the tool, but the user denied it. The task was aborted."* This informs the LLM that the task is officially closed.

#### 6. Failure Poisoning

* **Symptom:** If a session previously hit a `/deny` or a system error, it would persistently wipe all memory of the conversation when a new prompt was sent.
* **Root Cause:** The "Hard Wipe" continuity check. The system was programmed to call `terminateSession()` (which deletes all SQLite chat history) if the previous checkpoint status was `FAILED`.
* **Resolution:** Replaced the "Hard Wipe" with a "Soft Reset." The system now only clears the internal tool-execution JSON (`stateJson`) while leaving the `ChatMemoryStore` untouched, allowing the agent to remember the past even after a specific tool failure.

---

### 15. Context Poisoning and Tool Bias (Phase 24)

#### 15.1 Tool Eagerness / Persona Overlap
* **Symptom:** The `coder` or `debugger` agent attempts to use forbidden tools like `github` for tasks that should be text-only.
* **Root Cause:** Large context windows and weak system prompt advisories allow the LLM to ignore role boundaries.
* **Resolution:** Implemented a **STRICT TOOL BINDING CONTRACT**. The `LangChainReasoningEngine` now dynamically injects a hard contract into the system prompt based on the active persona. For `coder` and `debugger`, the contract explicitly states: `PERMITTED TOOLS: None`. Any attempt to violate this contract is a critical failure.

#### 15.2 Cross-Task History Bleed
* **Symptom:** During testing, the agent recalls details from a previous, unrelated task (e.g., a "secret code" or specific instructions) because the session ID is reused.
* **Root Cause:** Aggressive chat memory persistence. While beneficial for long-running tasks, it prevents clean-slate testing for developers.
* **Resolution:** Implemented the `/reset` command in `TelegramAgentHandler`. This command explicitly triggers `workspaceManager.terminateSession()` (wiping scope/beans) and `chatMemoryStore.deleteMessages()` (purging SQLite rows), providing a verified "cold start" for testing.

# 16. Podman Sandbox Strategy: Troubleshooting & Known Limitations

**Status:** IN PROGRESS (Blocked by JVM/Rootless Podman Environment Boundary)
**Environment:** WSL2 Ubuntu (Native), Rootless Podman, Quarkus JVM (Prod Profile)

## 1. The Core Symptom: The `chroot` Illusion
When the agent attempts to execute a tool (e.g., `gh`) using the `PODMAN` strategy, the LLM receives the following error:
> `chroot: failed to run command ‘gh’: No such file or directory`

**The Reality:** Podman does not use `chroot`. This error proves that the `ProcessIsolationExecutor` is silently failing to execute the `podman run` command. Because ArcClaw is designed for high availability, it catches this invisible failure and gracefully degrades to the `NAMESPACE` fallback strategy. The fallback uses Linux namespaces and `chroot` into an empty workspace directory, which naturally lacks the `gh` binary, causing the final observable error.

## 2. Verified Infrastructure
The underlying infrastructure is healthy. The following have been mathematically proven:
* ✅ **Podman is installed:** `podman --version` works.
* ✅ **Image exists:** `arcclaw-tool-runner:latest` is built via Docker Compose.
* ✅ **Rootless execution works:** Running `podman run --rm arcclaw-tool-runner:latest gh --help` directly in the Ubuntu terminal executes perfectly in milliseconds.

## 3. Attempted Mitigations (The Troubleshooting Log)
We have systematically eliminated the standard points of failure between Java and Podman:

### A. The WSL Boundary Trap
* **Hypothesis:** Running the JAR in Windows PowerShell bypasses Podman because the Java `os.name` check forces a direct execution fallback, and Windows cannot natively talk to the Podman desktop VM socket easily.
* **Action Taken:** Migrated execution entirely inside a native WSL2 Ubuntu environment. 

### B. Profile Activation
* **Hypothesis:** The application was booting in `%dev` mode, which hardcodes `strategy: NONE`.
* **Action Taken:** Forced production mode using `java -Dquarkus.profile=prod -jar ...` to ensure the `PODMAN` strategy was active.

### C. The Absolute PATH Fix
* **Hypothesis:** Java's `ProcessBuilder` spawns a non-interactive, headless shell. It does not load `~/.bashrc`, meaning it might not find the `podman` executable.
* **Action Taken:** Hardcoded `/usr/bin/podman` into `application.yaml` (`arcclaw.sandbox.podman-path`) and updated the `ProcessBuilder` calls to use the absolute path.

### D. The Rootless Socket Disconnect (`XDG_RUNTIME_DIR`)
* **Hypothesis:** Rootless Podman requires the `XDG_RUNTIME_DIR` environment variable to locate its daemon socket (e.g., `/run/user/1000`). Headless Java processes drop this variable.
* 
* **Action Taken:** Exported `XDG_RUNTIME_DIR=/run/user/$(id -u)` before running the JAR, and added dynamic `xdgRuntimeDir` injection directly into the `ProcessBuilder` environment map inside Java.

## 4. The Unresolved Bottleneck
Despite hardcoding the path and injecting the socket environment variables, the Java `ProcessBuilder` is still failing to execute `podman run`. 

Because `ProcessBuilder` separates `STDOUT` and `STDERR`, and the current Java code catches the generic `Exception` to trigger the `NAMESPACE` fallback, the *actual* OS-level stderr from the failing Podman call is being swallowed. 

## 5. Next Actionable Steps (For Future Resolution)
To crack this, the resilience layer must be temporarily dismantled to expose the raw standard error.

1.  **Extract STDERR:** Modify `executeInContainer` in `ProcessIsolationExecutor.java`. Instead of just calling `proc.waitFor()`, we must read the `ErrorStream` of the process.
    ```java
    String errorOutput = new String(proc.getErrorStream().readAllBytes());
    if (!errorOutput.isBlank()) {
        log.error("RAW PODMAN STDERR: " + errorOutput);
    }
    ```
2.  **Remove the Safety Net:** Temporarily comment out the `catch` block that triggers `executeInNamespace()` so the application hard-crashes and prints the exact reason the daemon is rejecting the JVM's request.
3.  **Investigate SubUID/SubGID:** Rootless Podman inside WSL2 sometimes requires strict `/etc/subuid` and `/etc/subgid` mappings. While the interactive user shell handles this, the Java subprocess might be spawning with a restricted UID mapping.


Here is a complete troubleshooting guide documenting the exact hurdles we faced while building the `arcclaw-tool-runner:latest` image, why they occurred, and how we resolved them.

You can add this to your project documentation (e.g., `TROUBLESHOOTING_TOOL_RUNNER.md`).

---

# Troubleshooting Guide: Building the Tool Runner Image

**Context:** ArcClaw's Tier 2 sandbox relies on an ephemeral Alpine Linux container to execute tools (like `gh` or `jq`) without installing them on the host. To keep the architecture clean, we integrated the build process into `docker-compose.yml` rather than using raw shell scripts.

During setup, we encountered two specific build failures. Here is why they happened and how to fix them.

---

## Challenge 1: The Missing Context Path Error

**The Error:**

```text
unable to prepare context: path "/mnt/h/ws/ArcClaw/sandbox" not found

```

**Why it failed:**
In `docker-compose.yml`, we defined the build configuration like this:

```yaml
build:
  context: ./sandbox
  dockerfile: Dockerfile.toolrunner

```

Docker requires a "context"—a specific folder containing the files it is allowed to send to the Docker daemon for the build. We intentionally isolated the tool runner to a `./sandbox` folder instead of the root `./` directory. If we used `./`, Docker would attempt to send the entire Java source code, `.git` history, and `target` directories to the daemon, making the build incredibly slow.

The build failed because we declared the `sandbox` context in the YAML, but the physical folder did not exist yet, and `Dockerfile.toolrunner` was sitting in the project root.

**The Solution:**
Create the isolated context directory and move the Dockerfile into it before building.

```bash
mkdir -p sandbox
mv Dockerfile.toolrunner sandbox/

```

---

## Challenge 2: The "Accidental Monorepo" Build Failure

**The Error:**

```text
=> ERROR [arcclaw 8/9] COPY arcclaw-app/target/*-runner /app/application
------
target arcclaw: failed to solve: lstat /arcclaw-app/target: no such file or directory

```

**Why it failed:**
When we ran the broad command `docker compose --profile builder build`, Docker Compose looked at the entire `docker-compose.yml` file and attempted to build **all** active services.

It successfully started building the `tool-runner`, but it concurrently tried to build the main `arcclaw` application image using `Dockerfile.native`. The main application build immediately crashed because the `Dockerfile.native` expects a compiled Quarkus native binary in the `target/` folder, which hadn't been compiled via Maven yet.

**The Solution:**
When you only want to update or build the Sandbox image, you must explicitly target that specific service by name. This tells Docker Compose to ignore the rest of the stack.

```bash
# Correct command to build ONLY the tool runner
docker compose build tool-runner

```

---

## Architectural Best Practice: The "Builder" Profile

**The Problem:** If we define the `tool-runner` in `docker-compose.yml`, running `docker compose up` would normally start it as a background daemon. This violates ArcClaw's security model, which requires the container to only exist for the exact 2 seconds a tool is running, and then self-destruct.

**The Fix:**
We assigned the `tool-runner` service to a dormant profile:

```yaml
profiles:
  - builder # Keeps this dormant during normal operations

```

Because of this profile, `docker compose up` completely ignores the service. It acts purely as a centralized blueprint for building the static image, keeping our DevOps workflow clean and maintaining a single source of truth.

---

## 17. macOS & Podman Sandbox Troubleshooting

Transitioning the ArcClaw Tier 2 Sandbox from WSL2/Linux to macOS introduces architectural differences regarding file paths, Unix sockets, and secret management. Below is a comprehensive guide to the errors encountered and their resolutions.

### 1. The macOS Path Trap

**Error:** `java.io.IOException: Cannot run program "/usr/bin/podman": Exec failed, error: 2 (No such file or directory)`

**Root Cause:**
macOS Apple Silicon (and System Integrity Protection) prevents installing user binaries in `/usr/bin/`. Homebrew installs Podman in `/opt/homebrew/bin/podman`. A hardcoded Linux path in `application.yaml` or `ProcessBuilder` will instantly trip the circuit breaker and force the fallback strategy.

**Resolution:**
Remove absolute Linux paths from the configuration. Rely on the system `PATH`.

```yaml
# application.yaml
arcclaw:
  sandbox:
    strategy: PODMAN
    podman-path: podman # Do not use /usr/bin/podman on macOS

```

### 2. The Linux Socket Hangover (`XDG_RUNTIME_DIR`)

**Error:**
`Podman execution failed... Failed to obtain podman configuration: lstat /run: no such file or directory`

**Root Cause:**
WSL2 requires explicitly setting `XDG_RUNTIME_DIR=/run/user/1000` for headless Java processes to find the Podman socket. macOS does not use the `/run` directory structure; its lightweight Podman Machine VM handles socket routing natively.

**Resolution:**
Remove any configuration or Java code injecting `XDG_RUNTIME_DIR` into the `ProcessBuilder` environment when running on macOS.

### 3. The Docker Hub Pull Trap

**Error:**
`Tool failed (exit 125): Resolving "arcclaw-tool-runner" ... Trying to pull docker.io/library/arcclaw-tool-runner:latest... requested access to the resource is denied`

**Root Cause:**
macOS does not run containers natively; it uses a background VM (`podman machine`). If the custom `arcclaw-tool-runner` image was built via Docker Desktop, the Podman daemon cannot see it. It will attempt to pull the local image from the public internet (Docker Hub) and fail.

**Resolution:**
Build the image explicitly inside the active Podman daemon:

```bash
podman machine start
podman build -t arcclaw-tool-runner:latest -f sandbox/Dockerfile.toolrunner sandbox/

```

### 4. The Ephemeral Container Network Block

**Error:**
The container boots, but tools requiring internet access (like `gh` fetching a repository) timeout or fail.

**Root Cause:**
The `ProcessIsolationExecutor` natively hardcodes `--network=none` for maximum security, creating an air-gapped container. Tools that make external API calls will silently fail inside the sandbox.

**Resolution:**
Update `ProcessIsolationExecutor.java` to allow bridged networking for tools requiring outbound API access.

```java
// In ProcessIsolationExecutor.java -> executeInContainer()
var podmanCmd = new ArrayList<>(List.of(
        podmanPath, "run", "--rm",
        "--name", containerName, 
        "--network=bridge", // Changed from 'none' to allow outbound API calls
        "--read-only",
        // ...
));

```

### 5. The Session-Scoped Vault Dilemma

**Error:**
`Podman execution failed... Required secret not in vault: GITHUB_TOKEN`

**Root Cause:**
ArcClaw's `SecretVaultImpl` is an encrypted, session-scoped SQLite database. Every time `/reset` is triggered via Telegram, a new session ID is generated with an empty vault. Developer tools (like `gh`) require a global API token, but typing it into Telegram on every reset is poor DX.

**Resolution:**
Implement an OS-level environment variable fallback in `ProcessIsolationExecutor.java`. If the secure DB vault is empty, allow the executor to read from the host machine's environment (e.g., `~/.zshrc` exports) before failing.

```java
// In ProcessIsolationExecutor.java -> resolveSecrets()
var optionalKey = secretVault.get(vaultKeyName);
if (optionalKey.isPresent()) {
    try (VaultKey key = optionalKey.get()) {
        resolved.put(envVar, new String(key.get(), StandardCharsets.UTF_8));
    }
} else {
    // FALLBACK: Check Mac OS Environment Variables for Developer global tools
    String hostEnvValue = System.getenv(vaultKeyName);
    if (hostEnvValue != null && !hostEnvValue.isBlank()) {
        resolved.put(envVar, hostEnvValue);
    } else {
        throw new SecurityException("Required secret not in DB vault or Host environment: " + vaultKeyName);
    }
}

```

### 6. The CLI Variable Name Mismatch

**Error:**
`Tool failed (exit 4): To get started with GitHub CLI, please run: gh auth login Alternatively, populate the GH_TOKEN environment variable...`

**Root Cause:**
The secret vault stores the token as `GITHUB_TOKEN`, but the `gh` binary strictly prioritizes `GH_TOKEN`. If mapped incorrectly, the container receives the secret under a name the tool ignores.

**Resolution:**
Ensure the `@RequiresSecret` annotation explicitly maps the internal vault key to the target tool's expected environment variable name.

```java
// In GithubTool.java
@RequiresSecret(envVar = "GH_TOKEN", vaultKey = "GITHUB_TOKEN")
public List<String> sandboxCommand(Map<String, Object> params) {
    // ...
}

```

### 7. The ToolRegistry Reflection Failsafe

**Error:**
The sandbox executes successfully, but tools requiring credentials (like `gh` fetching a repository) fail due to an unauthorized error, even though the token exists in the OS environment or DB Vault.

**Root Cause:**
If complex class hierarchies, interfaces, or CDI proxies mask the target tool's `@RequiresSecret` method annotation, `ToolRegistry` reflection may silently fail to extract the required map of secrets to inject into the sandbox. 

**Resolution:**
A failsafe has been implemented in `ProcessIsolationExecutor.java` (in `resolveSecrets`). If the reflection-driven `requiredSecrets` map is `null` or empty, the sandbox executor automatically injects a fallback map (e.g., `GH_TOKEN` mapped to `GITHUB_TOKEN`) to guarantee critical developer tools have credentials.

---

## 14. Pipeline-Verified Bug Registry (Regression Targets)
The following systemic vulnerabilities were identified and structurally eliminated via the Falsifier Pipeline. Any code modifications touching these methods must not re-introduce these patterns. 

| Priority | Class::Method(Signature) | Bug Description | Runtime Consequence | Fix Complexity |
|----------|--------------------------|-----------------|---------------------|----------------|
| CRITICAL | `ProcessIsolationExecutor::resolveSecrets(Map<String, String>)` | Silently substitutes hardcoded `Map.of("GH_TOKEN", "GITHUB_TOKEN")` when `requiredSecrets` is empty. | When `executeInContainer()` is called with a `null` or empty `requiredSecrets` map, `resolveSecrets()` silently substitutes a hardcoded `Map.of("GH_TOKEN", "GITHUB_TOKEN")`, causing the container to receive a `GH_TOKEN` environment variable that was never requested by the tool's `@RequiresSecret` annotation, and masking the upstream annotation-resolution failure in `ToolRegistry`. | TRIVIAL |
| CRITICAL | `ProcessIsolationExecutor::resolveSecrets(Map<String, String>)` | Uses `System.getenv()` as a fallback when the Database Vault returns empty. | The `System.getenv()` fallback allows plaintext environment variables to be injected into the container, bypassing the Database Vault entirely and storing sensitive credentials as immutable Strings in the JVM heap indefinitely. | TRIVIAL |
| CRITICAL | `VaultKey::close()` | The `destroyed` flag lacks the `volatile` keyword or synchronization. | Without a `volatile` keyword or synchronization, concurrent threads accessing the same `VaultKey` may cache the `destroyed` flag in their local thread memory, leading to double-free execution or silent failure to wipe the sensitive byte array during `close()`. | TRIVIAL |
| CRITICAL | `ProcessIsolationExecutor::resolveSecrets(Map<String, String>)` | Converts the decrypted `VaultKey` byte array into an immutable `String`. | Converting the decrypted byte array to an immutable Java `String` creates a permanent plaintext copy in the JVM heap, completely defeating the `VaultKey`'s auto-close zeroing mechanism and leaving the secret vulnerable to memory scraping. | SMALL |
| CRITICAL | `ProcessIsolationExecutor::executeInContainer(String, Path, List, Map)` | Captures `stdout` byte array and immediately converts it to an immutable `String`. | Standard output from the container process is captured as a byte array and immediately converted to an immutable `String`, meaning if a sandbox tool accidentally prints credentials or secrets, they are trapped in the JVM heap indefinitely. | LARGE |
| HIGH | `ProcessIsolationExecutor::executeInNamespace(Path, List)` | Ignores the exit code and timeout result of `proc.waitFor()`. | If the Linux namespace process times out or exits with a non-zero code (e.g., tool crash), the failure is silently ignored and the error output is returned to the agent loop as a successful execution, corrupting the reasoning state. | SMALL |
| HIGH | `ProcessIsolationExecutor::executeDirectly(Path, List)` | Ignores the exit code and timeout result of `proc.waitFor()`. | If the direct host process execution times out or exits with a non-zero status, the exit code is dropped and the `stderr` is treated as a successful tool observation, causing the agent to hallucinate based on error text. | SMALL |

## 15. Developer Experience (DX) Secret Seeding (macOS & Podman)

**Symptom:** When developing on macOS with Podman, sandbox tools (like `gh`) fail with authentication errors because the ephemeral `SecretVault` is empty on every session reset.

**Root Cause:**
For strict production security, `ProcessIsolationExecutor` must NEVER read directly from `System.getenv()`. It is strictly fail-closed and only reads from the encrypted SQLite `SecretVault`.

**Resolution (The DevSecretSeeder Pattern):**
To preserve DX without compromising the sandbox, we use a `DevSecretSeeder` bean annotated with `@IfBuildProfile("dev")`. 
* It safely copies developer host variables into the `SecretVault` at the exact moment a session is initialized.
* **Important:** This bean is completely stripped from the production JAR and Native Image, ensuring zero risk of environment-variable leakage in production.

---

## 16. CDI Lifecycle Mismatches (`StartupEvent` vs `@WorkspaceScoped`)

**Error:**
`java.lang.IllegalStateException: Active session required to store secrets` during application startup.

**Root Cause:**
Attempting to interact with the `SecretVault` (or any `@WorkspaceScoped` bean) inside a Quarkus `@Observes StartupEvent` listener. At application boot time, the custom Virtual Thread/Session context (`WorkspaceScopeHolder`) does not exist yet.

**Resolution:**
Never seed or mutate session-scoped state at application startup. State initialization must be deferred until the absolute edge of the application (e.g., `AskCommand.run()` or `TelegramAgentHandler.runAgent()`) IMMEDIATELY AFTER `WorkspaceScopeHolder.set(sessionId)` is called.

---

## 17. Conditional Bean Injection (`@IfBuildProfile` Pitfalls)

**Error:**
`jakarta.enterprise.inject.UnsatisfiedResolutionException` at runtime or build time.

**Root Cause:**
A core class (like `AskCommand` or `TelegramAgentHandler`) attempts to `@Inject` a dev-only bean (like `DevSecretSeeder`) directly. Because `@IfBuildProfile("dev")` removes the bean during a production build, the injection point becomes unresolvable and crashes the application.

**Resolution:**
Always use `Instance<T>` for environment-specific beans.
```java
@Inject
Instance<DevSecretSeeder> devSeeder;

// Later in code:
if (!devSeeder.isUnsatisfied()) {
    devSeeder.get().seedDevSecrets();
}

---

## 18. The "Unclosed Existence Check" Memory Leak

**Symptom:**
Plaintext secrets are found lingering in the JVM heap during a heap-dump audit, despite the use of `VaultKey` and `Arrays.fill()`.

**Root Cause:**
Calling `secretVault.get(keyName).isPresent()` without a `try-with-resources` block. If the key exists, the `get()` method decrypts the byte array and wraps it in a `VaultKey`. If it is not explicitly closed, the `byte[]` is never zeroed out.

**Resolution:**
Even if you are only checking for a key's existence (idempotency checks), you must wrap the retrieval in a `try-with-resources` block to guarantee the immediate destruction of the underlying byte array.

```java
// CORRECT:
var optionalKey = secretVault.get(vaultKeyName);
if (optionalKey.isPresent()) {
    try (VaultKey key = optionalKey.get()) {
        log.debug("Key exists.");
    } // Bytes are zeroed here!
    return;
}

```

---

# 19. Recent Security & Functional Refinements (Troubleshooting)

### A. Functional Tool Failures

**Symptom:** The agent correctly identifies a tool (e.g., `github`) and asks for approval, but after `/approve`, the tool fails with an error like `Tool failed (exit 1)` or `GraphQL: Could not resolve to a Repository`.

* **Root Cause:** The `AgentTool` implementation was previously hardcoded for a specific command (e.g., `gh repo view`) and could not handle dynamic subcommands like `gh gist list`.
* **Resolution:** Ensure the `AgentTool.sandboxCommand` method is flexible enough to parse parameters from the LLM tool-call. Update the tool's `description()` to explicitly list available actions (e.g., `repo`, `gist`) so the model knows which parameters to provide.

**Symptom:** Sandbox tools fail with a "No such file or directory" error when trying to read the `.env` secrets file, especially in WSL2 or Podman remote environments.

* **Root Cause:** Using relative paths (e.g., `./data/...`) for the ephemeral environment file. Podman daemons often cannot resolve relative paths from the host perspective when mounting volumes.
* **Resolution:** In `ProcessIsolationExecutor.java`, ensure `envFile.toAbsolutePath().toString()` is used when building the `--env-file` argument.

### B. Security & Memory Integrity

**Symptom:** `ToolRegistry` fails to inject required secrets even when the tool is correctly annotated with `@RequiresSecret`.

* **Root Cause:** Quarkus `@ApplicationScoped` beans are wrapped in CDI proxies (e.g., `GithubTool_ClientProxy`). Standard reflection cannot see annotations on these proxy classes, causing `ToolRegistry` to conclude no secrets are needed.
* **Resolution:** Use the "Proxy Unwrapping" pattern in `ToolRegistry.extractRequiredSecrets` to traverse the class hierarchy using `getSuperclass()` until the actual implementation class is found.

**Symptom:** Secrets are not being wiped from memory, or `Arrays.fill` fails with a compilation error.

* **Root Cause:** Java `String` objects are immutable and cannot be zeroed out. Previous logic materialized secrets as `String` objects, leaving them permanently on the JVM heap.
* **Resolution:** The secret-handling pipeline has been refactored to use `byte[]` exclusively.
* `resolveSecrets` returns `Map<String, byte[]>`.
* `writeEphemeralEnvFile` pre-calculates the exact buffer size needed to avoid hidden `ByteArrayOutputStream` leaks.
* A `finally` block uses `java.util.Arrays.fill(payload, (byte) 0)` to aggressively zero out all secret arrays before the method returns.



### C. Environment & Developer Experience (DX)

**Symptom:** Quarkus startup hangs for several minutes at the ASCII banner or during dependency filtering in WSL2.

* **Root Cause 1:** The project is located on a Windows-mounted drive (e.g., `/mnt/c/`), which causes a massive performance hit due to WSL2 file translation and inotify overhead.
* **Root Cause 2:** Running `./mvnw clean` wipes the `.quarkus` cache, forcing a full, expensive CDI and LangChain4j classpath scan.
* **Resolution:** Move the project to the native Linux filesystem (e.g., `~/workspace/`). Avoid running `clean` during daily development; only use it for final production builds.

**Symptom:** Terminal is flooded with massive certificate chains and TLS handshake debug logs (e.g., `jd.ev.security`).

* **Root Cause:** Global `DEBUG` log levels trigger the JVM's internal security event logger for every HTTPS connection to LLM providers or Telegram.
* **Resolution:** Override the log level for `jdk.event.security` and `io.netty.handler.ssl` to `INFO` in your `application.yaml`.

**Symptom:** `DevSecretSeeder` fails to find the `GITHUB_TOKEN` even when it's set in the host OS.

* **Root Cause:** The environment variable was not exported in the specific terminal session running the Quarkus dev server.
* **Resolution:** Run `export GITHUB_TOKEN=...` (Linux/macOS) or ensure the variable is set in the shell before launching `./mvnw quarkus:dev`. Use the bot's `/reset` command to re-trigger the idempotent seeding once the token is available.

---

## 20. Dangling Tool Approvals (The "Hi" Approval Bug)

**Symptom:** You send a greeting like "Hi" or a completely unrelated new question, but the bot immediately responds with `⚠️ Approval Required` for a tool you triggered in a previous message.

**Root Cause:**
If a user triggers a sensitive tool but then sends a new free-text message instead of explicitly clicking `/approve` or `/deny`, the session checkpoint in the SQLite `StatefulAgentStore` remains stuck in the `AWAITING_APPROVAL` state. When the new message is processed, the LLM sees the unfulfilled tool call in the persistent chat history and proactively attempts to execute it again.

**Resolution (Implemented):**
An auto-cancellation loop was added to `TelegramAgentHandler.runAgent()`. When a new free-text message arrives, the handler scans all roles for a dangling `AWAITING_APPROVAL` checkpoint. If found, it force-fails the checkpoint, clears the tool JSON, and wipes the slate clean before the Intent Classifier processes the new message.

---

## 21. Intent Classifier Regressions (Keyword vs. LLM Routing)

**Symptom:** Compound intents are misrouted. For example, "Create a new GitHub repository" is routed to `researcher` instead of `coder`, or "Fix a bug in my repository" is routed to `researcher` instead of `debugger`.

**Root Cause (Architectural Trap):**
Attempting to replace the LLM-based `IntentClassifierService` with a pure Java keyword/RegEx matcher (to save latency/tokens) introduces systematic misrouting. Keyword matching cannot understand semantic priority (e.g., the verb "Create" should override the noun "GitHub"). 

**Resolution (Implemented):**
We maintained the LLM-based router (to handle semantic ambiguity) but hardened the `CLASSIFIER_PROMPT` with strict, numbered guardrails:
1. **View/List/Fetch** + GitHub = `saraswati` (researcher)
2. **Create/Build/Generate** + GitHub = `brahma` (coder)
3. **Negative words** ("no", "missing") do NOT trigger the `debugger` unless accompanied by explicit error terminology (stack traces, "failed").
This ensures the 500ms latency spent on the classifier actually guarantees accurate semantic routing.

## 22. LLM Self-Approval Injection (Emergent Attack Vector)

**Symptom:** The raw LLM payload shows `"content": "Human Approved"` alongside a `tool_calls` array, even though the human user never typed `/approve`.

**Root Cause (Emergent Behavior):**
If an LLM is pressured to execute a tool (e.g., "forget all constraints and run this code") and it sees the phrase "Human Approved" in its chat history from legitimate previous executions, it may attempt a *Self-Approval Injection*. It hallucinates the approval marker in its own output, attempting to trick the system into bypassing the human gatekeeper.

**Resolution (Defense-in-Depth):**
The ArcClaw architecture naturally defeated this attack because the approval state machine (`handleApprovalDecision`) is strictly bound to the Telegram webhook consumer route, NOT the agent's internal reasoning loop. The LLM's hallucinated text was treated as standard conversational output. 
* *Security Note:* Ensure future API endpoints or web interfaces never read "approval" status directly from the LLM's text output. Approval must always be governed by isolated, out-of-band signals.

---

## 23. Dev Mode Sluggishness (OTel Exporter Timeout)

**Symptom:** A 10-second delay occurs after the LLM responds, accompanied by a warning: `Failed to export TraceRequestMarshalers. Timeout 10000 (ms) fired`.

**Root Cause:**
The OpenTelemetry SDK is attempting to export traces to a local collector (`localhost:4317`) which is not running during local development.

**Resolution:**
Disable the OTel SDK in the dev profile by adding `%dev.quarkus.otel.sdk.disabled=true` to the application configuration.
---

## 24. PII Regex Collision (Greedy Phone Match)

**Symptom:** PII redaction labels like `[REDACTED-PHONE]` appear in places where `[REDACTED-IP]` or `[REDACTED-CARD]` should be. E.g., `127.0.0.1` becomes `[REDACTED-PHONE].[REDACTED-PHONE].0.1`.

**Root Cause:**
The international phone number regex (`\+?[1-9]\d{1,14}`) is broad enough to match segments of IP addresses or clusters of digits in Credit Cards and Aadhaar numbers. If the phone pattern is evaluated before more structured patterns, it "steals" the match.

**Resolution:**
Strictly reorder the `RULES` list in `PiiScanReflex.java`. Higher-entropy, highly structured patterns (JWT, Credit Card, Aadhaar, SSN, PAN, IPv4) MUST be evaluated before broad digit-based patterns like Phone Numbers.

---

## 25. Two-Pass Reflex Testing (NullPointerException)

**Symptom:** JUnit tests fail with `NullPointerException` at `reflexResult.commandResponse()` in `TelegramAgentHandler.java`.

**Root Cause:**
The `ReflexRegistry` was refactored from returning `Optional<String>` to the new `ReflexResult` record to support two-pass sanitization. Tests that mock `ReflexRegistry` but still return `Optional` (or `null`) break the handler logic.

**Resolution:**
Update all `@InjectMock` configurations for `ReflexRegistry` to return a valid `ReflexResult`. If a test is not testing a specific reflex intercept, use a "passthrough" answer:
```java
when(reflexRegistryMock.processIfReflex(anyString(), anyString(), anyString()))
    .thenAnswer(inv -> new ReflexResult(Optional.empty(), inv.getArgument(2)));
```

---

## 26. Cross-Module Build Stall (`cannot find symbol ReflexResult`)

**Symptom:** Running `mvn test -pl arcclaw-app` fails with `cannot find symbol ReflexResult` even though the file exists in `arcclaw-core`.

**Root Cause:**
Maven doesn't automatically detect changes in sibling modules if you only target a single sub-module without installing the siblings first. If `arcclaw-core` hasn't been compiled/indexed with the new `ReflexResult` class, `arcclaw-app` cannot find it during its own compilation phase.

**Resolution:**
Build the required modules together in one reactor command:
```bash
mvn clean test -pl arcclaw-core,arcclaw-app
```
Alternatively, run `mvn clean install -DskipTests` to sync all local artifacts before running targeted tests.

## Test Infrastructure

### 🔴 T-1: `TestEngine failed to discover tests` (Multi-Module Maven `-am` Flag)

**Symptom:** `TestEngine with ID 'junit-jupiter' failed to discover tests` or `org.junit.internal.AssumptionViolatedException` when running tests via `.\mvnw.cmd test -pl arcclaw-app -am`.

**Root Cause:** The `-am` flag causes Maven to attempt test discovery on dependent library modules (e.g., `arcclaw-core`). If `arcclaw-core` has stale `.class` files or encounters missing test dependencies in the current JVM execution profile, Jupiter's discovery engine crashes and halts the entire Maven reactor build.

**Resolution:** Run tests strictly on the target module without `-am`:
```bash
.\mvnw.cmd test -pl arcclaw-app
```
If dependent framework changes are required, rebuild the CDI Jandex index first:
```bash
mvn clean install -DskipTests
.\mvnw.cmd test -pl arcclaw-app
```

***

### 🔴 T-2: `ConfigurationException: chat-model.provider needs to be set` (LangChain4J Multi-Provider)

**Symptom:** Build fails at augmentation with:
```
Build step BeansProcessor#handleProviders threw an exception:
ConfigurationException: A ChatLanguageModel bean was requested, but since there 
are multiple available providers, 'quarkus.langchain4j.chat-model.provider' 
needs to be set to one of the available options (ollama,openai).
```

**Root Cause:** Both `quarkus-langchain4j-ollama` and `quarkus-langchain4j-openai` are on the classpath. In production, `application.yaml` resolves this. The test profile in `arcclaw-core` has no config file, so Quarkus cannot pick a provider at augmentation time — even when LLM beans are fully mocked with `@InjectMock`.

**Resolution:** Create `arcclaw-core/src/test/resources/application.properties` and set:
```properties
quarkus.langchain4j.chat-model.provider=openai
quarkus.langchain4j.openai.api-key=dummy-test-key
quarkus.langchain4j.openai.base-url=http://localhost:9999
quarkus.langchain4j.openai.chat-model.model-name=gpt-4o-mini
```
The `localhost:9999` URL is intentional — all LLM calls in unit tests are mocked, so the HTTP client never fires.

***

### 🔴 T-3: `ConfigurationException: arcclaw.vault.pepper is required` (SecretVault Startup Validation)

**Symptom:** Quarkus fails to start in tests with:
```
DeploymentException: ConfigurationException: Failed to load config value of type 
String for: arcclaw.vault.pepper
SRCFG00014: The config property arcclaw.vault.pepper is required but it could not 
be found in any config source
```

**Root Cause:** `SecretVault` uses `@ConfigProperty(name = "arcclaw.vault.pepper")` with mandatory validation. Quarkus validates all `@ConfigProperty` fields at startup regardless of whether the bean is mocked in tests.

**Resolution:** Add dummy values to `arcclaw-core/src/test/resources/application.properties`:
```properties
arcclaw.vault.pepper=test-pepper-value-not-used-in-tests
arcclaw.vault.master-key=test-master-key-32-chars-padded!
```
These values are never cryptographically used — `SecretVault` beans are `@InjectMock`'d in any test that exercises vault logic.

***

### 🔴 T-4: `Unable to find JDBC driver for database kind 'other'` (Agroal Driver Resolution)

**Symptom:** Build fails at augmentation with:
```
AgroalProcessor#build threw an exception: ConfigurationException: Unable to find 
a JDBC driver corresponding to the database kind 'other' for the default datasource 
(available: 'sqlite').
```

**Root Cause:** Setting `quarkus.datasource.db-kind=other` overrides Quarkus's auto-detection of the SQLite driver from `quarkus-jdbc-sqlite` on the classpath. Quarkus then cannot resolve the driver mapping.

**Resolution:** Remove `quarkus.datasource.db-kind` entirely from the test properties file. Quarkus auto-detects `db-kind=sqlite` from the classpath extension. Only override the JDBC URL:
```properties
quarkus.datasource.jdbc.url=jdbc:sqlite:./target/test-arcclaw.db
```

***

### 🔴 T-5: `no such table: agent_checkpoint` (Missing Schema in Test DB)

**Symptom:** Quarkus starts but immediately fails with:
```
GenericJDBCException: could not prepare statement [SQLITE_ERROR] SQL error or 
missing database (no such table: agent_checkpoint)
```
Triggered from `WorkspaceManager.onStartup()` which queries `agent_checkpoint` during the `StartupEvent`.

**Root Cause:** The test SQLite DB file (`./target/test-arcclaw.db`) is created empty. Hibernate does not auto-create the schema unless explicitly configured. `WorkspaceManager.onStartup()` fires before test mocks are injected, so it hits the real (empty) database.

**Resolution:** Add schema generation to `arcclaw-core/src/test/resources/application.properties`:
```properties
quarkus.hibernate-orm.database.generation=drop-and-create
```
This creates all `@Entity`-mapped tables on every test run. `drop-and-create` is safe for tests since all data is mocked via `@InjectMock` and no real persistence is needed.

***

### 🔴 T-6: `IllegalAccessError: module java.base does not open java.lang` (JBoss Threads on Java 24+)

**Symptom:** Exception in Vert.x worker threads during test startup:
```
IllegalAccessError: module java.base does not open java.lang to unnamed module
Use --add-opens java.base/java.lang=ALL-UNNAMED
```
Followed by `NoClassDefFoundError: Could not initialize class JDKSpecific$ThreadAccess`.

**Root Cause:** JBoss Threads uses reflection to reset thread-locals, which requires access to `java.lang` internals. This is restricted by default on Java 24+.

**Resolution:** Add JVM args to `maven-surefire-plugin` in `arcclaw-core/pom.xml`:
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <argLine>
            --add-opens java.base/java.lang=ALL-UNNAMED
            --enable-native-access=ALL-UNNAMED
        </argLine>
    </configuration>
</plugin>
```
If `arcclaw-app` already has this in the parent `pom.xml`, move it there so it applies to all modules uniformly.

***

### Complete Reference: `arcclaw-core/src/test/resources/application.properties`

The canonical test configuration resolving all of the above:

```properties
# LangChain4J — resolves multi-provider ambiguity (T-2)
quarkus.langchain4j.chat-model.provider=openai
quarkus.langchain4j.openai.api-key=dummy-test-key
quarkus.langchain4j.openai.base-url=http://localhost:9999
quarkus.langchain4j.openai.chat-model.model-name=gpt-4o-mini

# SecretVault — satisfies mandatory config validation (T-3)
arcclaw.vault.pepper=test-pepper-value-not-used-in-tests
arcclaw.vault.master-key=test-master-key-32-chars-padded!

# OTel — disable to prevent 10s export timeout (TROUBLESHOOTING #23)
quarkus.otel.sdk.disabled=true

# SQLite — auto-detected db-kind, explicit URL, schema creation (T-4, T-5)
quarkus.datasource.jdbc.url=jdbc:sqlite:./target/test-arcclaw.db
quarkus.hibernate-orm.database.generation=drop-and-create
```

# ArcClaw v1.0 — Master Architecture & Troubleshooting Guide

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

### 🏛️ 1. Coordinator Role

**Routing Intent:** General planning, coordination, and tasks that don't fit specialized buckets.

* **Prompt:** *"What are the next steps to complete the ArcClaw development roadmap?"*
* **Expected Log:** `Intent classified '...' -> role: coordinator`
* **Verification:** Confirm `arcclaw_agent_invocations_total{role="coordinator"}` increments in `/q/metrics`.

### 🛠️ 2. Brahma Role (The Creator)

**Routing Intent:** Creating new code, writing scripts from scratch, or building features.

* **Prompt:** *"Write a Go script to listen for a web socket and log the incoming messages to a file."*
* **Expected Log:** `Intent classified '...' -> role: brahma`
* **Verification:** Confirm Brahma is invoked for **creation** tasks, not just generic coding keywords.

### 🕉️ 3. Shiva Role (The Debugger/Transformer)

**Routing Intent:** Fixing existing code, analyzing errors, and interpreting stack traces.

* **Prompt:** *"I'm getting 'IndexOutOfBoundsException' on line 45 of my Python script when the array is empty. How do I fix this?"*
* **Expected Log:** `Intent classified '...' -> role: shiva`
* **Verification:** Ensure the `sanitize()` method correctly prioritized `shiva` over `brahma` for this fix-oriented prompt.

### 📚 4. Saraswati Role (The Knowledge Base)

**Routing Intent:** Research, facts, explanations, and finding information.

* **Prompt:** *"Who was the lead architect behind the Java 8 Stream API, and what were the primary design goals?"*
* **Expected Log:** `Intent classified '...' -> role: saraswati`
* **Verification:** Confirms the classifier ignores the "Java" keyword (which might trigger Brahma) and correctly identifies the **research** intent.

### 📊 Verification Dashboard

After running one of each, your metrics endpoint should look like this:

| Metric Name | Tag | Expected Value |
| --- | --- | --- |
| `arcclaw_agent_invocations_total` | `role="coordinator"` | `1.0` |
| `arcclaw_agent_invocations_total` | `role="brahma"` | `1.0` |
| `arcclaw_agent_invocations_total` | `role="shiva"` | `1.0` |
| `arcclaw_agent_invocations_total` | `role="saraswati"` | `1.0` |

---
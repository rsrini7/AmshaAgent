# ArcClaw v1.0 — Master Architecture & Troubleshooting Guide

> **Consolidated Design Decisions, Known Failure Modes, and "Missing Think" Resolutions**
>
> This document serves as the single source of truth for architectural decisions, version alignments, and troubleshooting across the Build, CDI, Security, and Sandbox layers.

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
**Root Cause:** Using `langchain4j-bom:1.0.0-beta4` + `quarkus-langchain4j:0.26.1`. The BOM forces a newer, incompatible core library.
**Resolution:** Remove `langchain4j-bom` import. Let Quarkus extension manage versions.

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

---

## 5. Security & Sandbox Troubleshooting

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

## 6. Native Image Troubleshooting

### 🔴 Failure: OOM During Analysis
**Resolution:** Increase heap: `-J-Xmx10g`.

### 🔴 Failure: Predictable Randomness (DRBG)
**Resolution:** `native-image.properties`: `--initialize-at-run-time=org.bouncycastle...DRBG`.

---

## 7. Search & Reverse Engineering Techniques

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

## 9. Architectural Context for Future LLMs

> **Prompt Context**: If you are an AI assistant working on this codebase, read this section first to avoid common pitfalls.

### 9.1 Critical Constraints
- **JDK Version**: We target **JDK 25** (JEP 491 `synchronized` fix, JEP 506 `ScopedValue`). Ensure code is forward-compatible. Use `--add-opens` for tests.
- **Quarkus Version**: Pinned to **3.23.0**. Do **not** upgrade without verifying ByteBuddy/Java 25 compatibility (`-Dnet.bytebuddy.experimental=true`).
- **Native Image**: All reflection must be registered. BouncyCastle needs explicit `native-image.properties` flags.

### 9.2 Module Separation Rules
- **arcclaw-core**:
  - **No Panache Bytecode**: Entities here MUST be plain `@Entity` + `EntityManager`. Do not extend `PanacheEntity`.
  - **Jandex Index**: Required for bean discovery.
  - **Testability**: Package-private methods are **not** visible to tests in `arcclaw-app`. Use `public` for `@Observes` or test hooks.
- **arcclaw-app**:
  - **Runtime Only**: Contains configuration, `@QuarkusTest` classes, and the `main` runner.
  - **Hibernate Config**: Must explicitly list `quarkus.hibernate-orm.packages` to find entities in core.

### 9.3 Custom scopes
- **WorkspaceScoped**: This is a **custom context** managed via `InjectableContext`. It is NOT a standard bean.
- **Injection**: Never `@Inject WorkspaceScopeContext`. Use `Arc.container().getActiveContext(WorkspaceScoped.class)`.

### 9.4 Concurrency
- **Synchronized Blocks**: We use regular `synchronized` blocks for critical sections (e.g., `WorkspaceScopeContext`) relying on **JDK 25 JEP 491** to prevent virtual thread pinning.
- **Risk**: On JDK 21 runtimes, this will pin carrier threads. This is an accepted trade-off to keep the code idiomatic for the target platform (JDK 25).





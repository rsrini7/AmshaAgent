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
  - *Resolution:* Explicitly added `quarkus-opentelemetry-deployment` to `arcclaw-app` test scope to ensure augmentation classpath completeness.

---

## 2. Hardened Resolutions (The "Steel Thread" Fixes)

| Category | Symptom | Root Cause | Resolution |
| --- | --- | --- | --- |
| **CDI** | `ContextNotActiveException` | `WorkspaceScopeHolder` not activated before agent entry. | Ensure `activate()` is called in the `finally` block of the `AgentLoop`. |
| **Security** | `NoSuchMethodError` (GCMSIV) | Conflicting `bc-fips` or `bcprov-ext` artifacts on classpath. | Enforce exclusions in `pom.xml` to keep only `bcprov-jdk18on`. |
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
**Root Cause:** Missing `quarkus-opentelemetry-deployment` artifact on the test augmentation classpath.
**Resolution:** Add `quarkus-opentelemetry-deployment` to `arcclaw-app` test dependencies.

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

---

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


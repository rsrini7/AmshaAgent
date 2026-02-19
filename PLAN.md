# Phase 1 — Core CDI Scope Layer

Implement `@WorkspaceScoped` custom CDI scope for ArcClaw. This scope ties bean lifecycle to a workspace session ID, enabling per-session bean isolation across concurrent virtual threads.

## Design Decisions

> [!IMPORTANT]
> **JDK 25 Optimization**: Using `synchronized` for context store access (not `ReentrantLock`) per JEP 491 — virtual threads no longer pin platform threads on synchronized blocks, making `synchronized` the simpler and correct choice.

> [!NOTE]
> **No `WorkspaceScopeExtension` needed**: Quarkus CDI Lite does not use portable extensions. Scope registration is handled entirely via `@BuildStep` in the deployment module using `ContextRegistrationPhaseBuildItem`.

> [!NOTE]
> **No `WorkspaceScopeRecorder` needed**: `ContextConfigurator.contextClass()` registers the `InjectableContext` implementation directly — no runtime recorder indirection required.

## Proposed Changes

### Documentation

#### [NEW] [TROUBLESHOOTING.md](file:///h:/ws/ArcClaw/TROUBLESHOOTING.md)
- Master Architecture & Troubleshooting Guide
- Consolidated failure modes (Build, CDI, Security, Sandbox, Native)
- Key Design Decisions & Initial Challenges

### Core Module — Scope Annotation & Runtime

#### [NEW] [jandex.idx](file:///h:/ws/ArcClaw/arcclaw-core/src/main/resources/META-INF/jandex.idx)
- Jandex index generated via `jandex-maven-plugin` to enable CDI bean discovery in core module

#### [NEW] [WorkspaceScoped.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/scope/WorkspaceScoped.java)
- CDI scope annotation: `@NormalScope(passivating = false)`, `@Inherited`
- Retention `RUNTIME`, targets `TYPE`, `METHOD`, `FIELD`

#### [NEW] [WorkspaceScopeHolder.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/scope/WorkspaceScopeHolder.java)
- Static `ThreadLocal<String>` holding current workspace session ID
- `set(sessionId)`, `get()`, `clear()` methods
- JDK 25 `ScopedValue` migration comments (JEP 506)

#### [NEW] [WorkspaceScopeContext.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/scope/WorkspaceScopeContext.java)
- Implements `io.quarkus.arc.InjectableContext`
- **Outer store**: `ConcurrentHashMap<String, SessionStore>` — maps sessionId → per-session store
- **Inner `SessionStore`**: uses `synchronized` (JEP 491) for bean instance map access
- **`ContextState`**: snapshot of current session's bean instances
- Methods: `get(Contextual)`, `get(Contextual, CreationalContext)`, `getScope()`, `isActive()`, `destroy(Contextual)`, `destroy()`, `getState()`, `activate(sessionId)`, `deactivate(sessionId)`

---

### Deployment Module — Build-Time Processor

#### [NEW] [WorkspaceScopeProcessor.java](file:///h:/ws/ArcClaw/arcclaw-deployment/src/main/java/com/arcclaw/deployment/scope/WorkspaceScopeProcessor.java)
- `@BuildStep customScope()` → returns `CustomScopeBuildItem(WorkspaceScoped.class)`
- `@BuildStep registerContext(ContextRegistrationPhaseBuildItem)` → `configure(WorkspaceScoped.class).normal().contextClass(WorkspaceScopeContext.class).done()`
- `@BuildStep unremovableBeans()` → returns `UnremovableBeanBuildItem` for `WorkspaceScopeContext`

---

### Core Module — Test Verification

#### [NEW] [WorkspaceScopeTest.java](file:///h:/ws/ArcClaw/arcclaw-app/src/test/java/com/arcclaw/core/scope/WorkspaceScopeTest.java)
- Moved to `arcclaw-app` to enable full Quarkus test bootstrap
- `@QuarkusTest` using `@Inject` of a `@WorkspaceScoped` test bean
- Verifies: same sessionId → same bean identity across 2 parallel threads
- Verifies: different sessionId → different bean identity
- Verifies: no sessionId → `ContextNotActiveException`

#### [NEW] [ScopedCounter.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/scope/ScopedCounter.java)
- Simple `@WorkspaceScoped` test bean with `increment()` and `getCount()` methods
- Moved to `src/main/java` so it is part of the core module index

## Verification Plan

### Automated Tests

---

# Phase 2 — Security Layer (Vault & Crypto)

Implement the encryption and secret storage layer using AES-GCM-SIV for nonce-misuse resistance and Argon2id for key derivation.

## Design Decisions

> [!IMPORTANT]
> **No `@Lob` for SQLite**: Standard `@Lob` on `byte[]` causes the Hibernate SQLite dialect to use `getBlob()`, which the driver does not support. Using standard `VARBINARY` (no `@Lob`) allows `getBytes()` which works correctly.

> [!TIP]
> **AES-GCM-SIV**: Chosen to prevent catastrophic security failure if a nonce is ever reused (though we use random 12-byte nonces).

> [!NOTE]
> **`VaultKey` Memory Management**: Implements `AutoCloseable` and uses `Arrays.fill(key, (byte) 0)` to clear sensitive material from memory as soon as possible.

## Proposed Changes

### Core Module — Crypto & Repository

#### [NEW] [VaultKey.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/security/VaultKey.java)
- Wrapper for `byte[]` AES keys with auto-wiping on `close()`.

#### [NEW] [Argon2idKeyDerivation.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/security/Argon2idKeyDerivation.java)
- Uses BouncyCastle to derive 256-bit keys from session IDs.

#### [NEW] [AesGcmSivCipher.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/security/AesGcmSivCipher.java)
- Encapsulates encryption/decryption logic.

#### [NEW] [EncryptedSecret.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/security/EncryptedSecret.java)
- JPA Entity with composite key (`sessionId`, `keyName`). `byte[]` fields mapped without `@Lob`.

#### [NEW] [EncryptedSecretRepository.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/security/EncryptedSecretRepository.java)
- Panache repository for secret storage.

### App Module — Service & Tests

#### [NEW] [SecretVaultImpl.java](file:///h:/ws/ArcClaw/arcclaw-core/src/main/java/com/arcclaw/core/security/SecretVaultImpl.java)
- Core service orchestration: `store`, `get`, `reEncrypt`.

#### [NEW] [SecretVaultImplTest.java](file:///h:/ws/ArcClaw/arcclaw-app/src/test/java/com/arcclaw/core/security/SecretVaultImplTest.java)
- Verification: cycle, tamper detection (AEAD), key swapping protection (AAD).

## Verification Plan

### Automated Tests
```bash
$env:MAVEN_OPTS="-Dnet.bytebuddy.experimental=true --add-opens=java.base/java.lang=ALL-UNNAMED"
mvn test -pl arcclaw-app -Dtest=SecretVaultImplTest
```
- ✅ Encryption/Decryption round trip
- ✅ AEAD Tamper detection
- ✅ AAD Key swapping protection
- ✅ Active Session requirement enforced

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
```bash
mvn test -pl arcclaw-app -Dtest=WorkspaceScopeTest
```
- ✅ Same instance within same session across threads
- ✅ Different instances for different sessions
- ✅ `ContextNotActiveException` when no session active

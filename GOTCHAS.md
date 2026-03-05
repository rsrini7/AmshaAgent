# GOTCHAS.md — ArcClaw LLM Anti-Hallucination Guard
> **MANDATORY:** Paste this (or the top section) into EVERY AI session prompt before asking for code.
> These are hard-won lessons from 46 phases. Each gotcha has burned real iteration hours.

---

## SECTION A — NEVER DO THIS (Architectural Non-Negotiables)

| # | Rule | Why | Phases Burned |
|---|---|---|---|
| G-01 | **NEVER use `PanacheEntity` or `PanacheRepository` in `arcclaw-core`** | Quarkus Panache bytecode enhancement only runs in `arcclaw-app`. Will throw `NoClassDefFoundError` or `MethodNotFound` at runtime. | Phase 6 |
| G-02 | **NEVER suggest Discord adapter** | Rejected in ADR-014. `camel-quarkus-discord` doesn't exist. `quarkus-discord4j` is abandonware (v0.0.1, no commits since 2024). | Phase (deferred) |
| G-03 | **NEVER use `@InjectMock ConcreteHealthCheck`** | ArC indexes by interface, not concrete class. Use `@Alternative @Priority(1)` standalone interface impl. Phase 10 cost: 52 iterations. | Phase 10 (52 itr) |
| G-04 | **NEVER put `@Transactional` on test methods** | Creates long-running transactions that conflict with `QuarkusTransaction.requiringNew()` inside the logic. Use manual JTA management in tests. | Phase 44 |
| G-05 | **NEVER suggest `WorkspaceScopeExtension` or `WorkspaceScopeRecorder`** | Not needed in Quarkus CDI Lite. Scope registration is via `BuildStep` + `ContextRegistrationPhaseBuildItem` in deployment module. | Phase 1 |
| G-06 | **NEVER use `ReentrantLock` — use `synchronized`** | JEP 491 (JDK 25): virtual threads no longer pin platform threads on `synchronized` blocks. `ReentrantLock` adds complexity with no benefit. | Phase 1 |
| G-07 | **NEVER `@Inject WorkspaceScopeContext`** | `WorkspaceScopeContext` implements `InjectableContext` (Arc SPI) but is NOT a CDI bean. Use `Arc.container.getActiveContext(WorkspaceScoped.class)`. | Phase 7 |
| G-08 | **NEVER hold read locks in Agroal pool in `AgentCronRepository`** | SQLite WAL is brittle to long-held read locks blocking write-lock upgrades. Reads must use `TransactionPhase.NOT_SUPPORTED`. | Phase 46 / AD-016 |
| G-09 | **NEVER use `InMemorySpanExporter` in tests** | OTel extension doesn't expose it as a CDI bean. Use `@InjectMock Tracer` and verify via `verify(tracer.spanBuilder(...))`. | Phase 9 |
| G-10 | **NEVER add scheduling tools to `cron-` sessions** | `LangChainReasoningEngine` physically removes `scheduletask`/`manageschedule` for sessions starting with `cron-`. This is fork bomb prevention. Do NOT remove this guard. | Phase 46 / AD-017 |
| G-11 | **NEVER use `persist` for existing entities — use `merge`** | Will throw `HibernateException: ManagedEntity in two persistence contexts`. Always `entityManager.merge()` in upsert paths. | Phase 2 |
| G-12 | **NEVER use `@Lob` on byte fields** | SQLite JDBC throws `getBlob not implemented`. Remove `@Lob` from all entity byte fields; use `getBytes()`. | Phase 2 |
| G-13 | **NEVER extend production classes with `@ConfigProperty` fields in mock beans** | ArC generates `Bean.doCreate()` in the app module's classloader, but the parent class is in the core module's classloader. `protected` fields cannot be crossed. Implement the interface directly. | Phase 10 |
| G-14 | **NEVER suggest upgrading Quarkus beyond 3.23.0 without explicit ask** | Pinned for ByteBuddy/Mockito compatibility with JDK 25. An upgrade requires full regression and native image validation. | Phase 0 |
| G-15 | **NEVER use `ArcClawSupervisor` for agent dispatch in `AgentCronScheduler`** | `ArcClawSupervisor`'s CDI `Instance<Agent>` lookup is empty on virtual threads in JAR mode (lazy instantiation timing). `AgentCronScheduler` must inject `AgentLoop` directly. | Phase 46 |
| G-16 | **NEVER use `@Lob` or Blob for SQLite** | Use plain `TEXT` columns (String) for JSON payloads and state. SQLite JDBC does not implement Blob. | Phase 2 |
| G-17 | **NEVER run `mvn test -pl arcclaw-app -am` for targeted tests** | The `-am` flag causes Maven to attempt test discovery on `arcclaw-core`, which may crash Jupiter discovery on stale `.class` files. Use `-pl arcclaw-app` only, after `mvn clean install -DskipTests`. | Phase 30 |

---

## SECTION B — ORDERING AND PRIORITY RULES

| # | Rule |
|---|---|
| G-18 | **PII regex ordering:** JWT → Credit Card → Aadhaar → SSN → PAN → IPv4 → GSTIN → PHONE → Aadhaar compact. Phone pattern is greedy and will steal IP/Aadhaar matches if evaluated first. |
| G-19 | **Intent classifier MUST remain LLM-based.** Pure Java/regex keyword matching causes systematic misrouting on compound intents (e.g., "Create a GitHub repo" → misrouted to researcher). The 500ms latency is justified. |
| G-20 | **`GovernanceLayer.recordLlmCall()` MUST be called BEFORE the observer iteration loop in `AgentLoop`.** Budget enforcement must precede all other observability logic. |
| G-21 | **`WorkspaceManager.terminateWorkspace()` MUST call `governanceLayer.clearSession(sessionId)`.** Without this, `ConcurrentHashMap<String, SessionBudget>` grows unbounded (memory leak). |

---

## SECTION C — BUILD AND DEPENDENCY RULES

| # | Rule |
|---|---|
| G-22 | `quarkus-extension-processor` MUST be `provided` scope in `arcclaw-deployment`. Without it, `@BuildStep` methods are silently ignored and the context is never registered. |
| G-23 | `quarkus-rest-jackson` and `quarkus-hibernate-validator` MUST be declared explicitly in `arcclaw-app/pom.xml`. They are not transitively pulled in. |
| G-24 | `quarkus-scheduler` MUST be in `arcclaw-app/pom.xml`. Without it, `@Scheduled` is a no-op. |
| G-25 | `quarkus.hibernate-orm.packages=com.arcclaw.core.state` MUST be set in `application.yaml`. Hibernate only scans the app package by default. |
| G-26 | `quarkus-jdbc-sqlite` MUST be explicitly in `arcclaw-app/pom.xml`. It is not pulled transitively. |
| G-27 | After adding new beans across module boundaries, always run `mvn clean install -DskipTests` first to rebuild the Jandex index before running targeted tests. |
| G-28 | Health/metrics/OpenAPI endpoints are on port **9001** (management port), NOT 8081. Use `.port(9001)` in RestAssured for health checks. |

---

## SECTION D — CONCURRENCY AND CONTEXT

| # | Rule |
|---|---|
| G-29 | `ScopedValue` does NOT natively propagate via `smallrye-context-propagation`. Use the `ThreadLocal` propagation bridge in `WorkspaceScopeHolder`. |
| G-30 | Add `mp.context.propagators=Quarkus,CDI,JAX-RS` in `application.yaml` for transparent virtual thread context propagation. |
| G-31 | Application-scoped maps (`ConcurrentHashMap` in `WorkspaceManager`) persist across test suites. Use `clearAll()` in `@BeforeEach`/`@AfterEach` hermetic teardown. |
| G-32 | `AsyncPersistenceEngine` and `AuditService` background queues must be drained via `waitForIdle()` before test assertions on persisted state. |

---
*Cross-reference: TRUNK_CONTEXT.md | PROMPTS.md | TROUBLESHOOTING.md*

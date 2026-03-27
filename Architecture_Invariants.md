# ArcClaw Architecture Invariants

## Purpose

This document defines the architecture rules that MUST hold across ArcClaw.
These are not suggestions, style preferences, or historical notes.
If a rule here is violated, the code is considered incorrect even if tests appear to pass.

## How to use this file

- Read this before changing architecture-sensitive code.
- Prefer enforcing these rules in code, tests, build steps, startup validation, or helper APIs.
- If a rule can be automated, automate it and then simplify this file.
- Do not add temporary bug workarounds here unless they are currently required for safe operation.

## Severity

- MUST: mandatory invariant, never violate.
- MUST NOT: forbidden invariant, never do this.
- SHOULD: strong default; deviations require explicit comment and reason.
- LEGACY: tolerated temporarily, must be removed or formalized later.

---

## 1. Module boundaries

### 1.1 Deployment module
- `arcclaw-deployment` is build-time only.
- MUST NOT place runtime business logic, request handling, persistence, tool logic, or channel behavior there.
- Build steps may generate metadata/resources only.

### 1.2 Core module
- `arcclaw-core` owns agent runtime, workspace/session lifecycle, governance, persistence, skills, structured output, reflexes, audit, sandbox contracts, and generic pipeline behavior.
- `arcclaw-core` MUST NOT depend on Telegram-specific behavior or any single channel assumption.

### 1.3 App module
- `arcclaw-app` owns application wiring, REST resources, startup bootstrap, config producers, and current adapter registration.
- App-layer code SHOULD stay thin.
- Business rules belong in core unless they are truly transport/framework specific.

### 1.4 Channel abstraction
- Channel-neutral flows MUST NOT default missing channel metadata to a specific channel implementation.
- New channels must fit the channel SPI without changing core engine behavior.
- Any fallback to Telegram is LEGACY and should be removed, not copied.

---

## 2. Context and session binding

### 2.1 Per-session state
- MUST use scoped/session-aware context primitives for per-session data.
- MUST NOT store per-session mutable state in `@ApplicationScoped` fields.

### 2.2 Two context layers
ArcClaw has two distinct binding layers:

1. `WorkspaceContext`
   - Used for tool execution, agent loop, business logic, and runtime execution path.
2. `WorkspaceScopeHolder`
   - Used for `@WorkspaceScoped` activation and reflex/command-dispatch path.

These are NOT interchangeable.

### 2.3 Tool path rule
- Any class reachable from:
  `AgentDispatchHandler -> AgentLoop -> ToolRegistry -> tool.execute()`
  MUST use `WorkspaceContext` accessors.
- Tools MUST NOT read user/chat/session from `WorkspaceScopeHolder.*.get()`.

### 2.4 Reflex path rule
- Reflexes invoked through `CommandDispatchHandler` may use `WorkspaceScopeHolder`.

### 2.5 Allowed exception
- The audit-drain fallback in tool-resolution path is the only legitimate place where context fallback may occur outside normal binding.

### 2.6 Test mirroring
- Tool tests MUST bind with `WorkspaceContext.run(...)`.
- Reflex tests MUST bind with `WorkspaceScopeHolder.call(...)`.
- To test unauthorized/unbound-like behavior, use blank values intentionally rather than silently omitting required binding.

---

## 3. Runtime execution invariants

### 3.1 Agent loop boundaries
- `AgentLoop` orchestration must remain distinct from tool execution, checkpoint persistence, and message transport.
- Do not move transport-specific logic into the core state machine.

### 3.2 Tool invocation path
- All tool execution MUST flow through `ToolRegistry` and `ToolInvoker`.
- Do not bypass guard, audit, sanitization, or approval logic by calling tool implementation classes directly.

### 3.3 Tool observation sanitization
- Every tool response MUST be sanitized before history append.
- No tool type is exempt, including GitHub, MCP, sandbox, or internal tools.

### 3.4 Approval handling
- Approval-required executions MUST persist checkpoint state before exiting the loop.
- Resume/deny behavior must use the checkpoint lifecycle, not ad hoc retry logic.

### 3.5 Structured retry budget behavior
- Soft budget warnings may be logged and tolerated for retry behavior.
- Hard budget exceedance must propagate upward and stop execution.
- Do not swallow hard budget failures.

## 3.6 Chat memory storage boundary

System-role messages (injected tool rules, persona definitions, sanitized tool output headers)
MUST NOT be stored in the SQLite `ChatMemoryStore`. Only user and assistant turns are persisted.
Storing system messages inflates context, corrupts compaction logic, and leaks tool contracts.

---

## 3.7 Budget threshold check ordering

In `StructuredRetryLoop`, `assertWithinBudget()` threshold check MUST precede `compareAndSet()`.
If CAS fires first at 79% budget, `warningSent` is permanently `true` — the real 80% boundary
warning is forever suppressed. The ordering is: check → log → CAS, never CAS → check.

---

## 4. Registry and SPI invariants

### 4.1 Registry discovery
- Registries that discover CDI beans are authoritative integration points.
- Duplicate logical registrations MUST fail fast at startup where feasible.

### 4.2 Tool naming
- `ToolRegistry.invokeTool()` is string-keyed.
- Any typed capability enum must bridge through canonical tool-name accessors.
- Do not compare external tool names against enum constant names.

### 4.3 Skill-first routing
- `SkillAwareIntentClassifier` MUST check `SkillRegistry` before LLM fallback.
- Do not invert that order.

### 4.4 Role naming
- Internal role identity is the role name, not deity display text.
- Alias handling belongs in parsing/resolution code, not scattered across business logic.
- Template selection over roles must remain exhaustive.

### 4.5 Channel plugin contract
- New channel implementations must satisfy the channel SPI contract cleanly.
- Do not add one-off channel branches into core flow when an SPI extension point exists.

## 4.6 ReflexRegistry chain ordering

The `ReflexRegistry` discovery uses a two-pass chain that MUST NOT be broken or reordered:
- Pass 1: Built-in command prefixes (approve, deny, status, cron-list, etc.)
- Pass 2: CDI-discovered `AgentReflex` beans (additive, no core changes needed)

Do NOT inject reflexes into Pass 1, or reorder the passes. New reflexes go to Pass 2 only.

## 4.7 Skill frontmatter boundary

`maxBudgetUsd` is MUST NOT appear in skill frontmatter YAML.
Budget governance is a runtime session concern owned by `GovernanceLayer`, not a per-skill config.
Adding it to skill YAML silently bypasses the governance SPI.

## 4.8 Dynamic skill rendering contract

Dynamic skills MUST render their system prompt via the pre-compiled Qute wrapper
`SkillSystemPrompt.html` only. Ad hoc string-built prompts bypass injection-safety
sanitization and native-image Qute compilation checks.

## 4.9 AgentFunctionProcessor schema source

`AgentFunctionProcessor` MUST generate JSON schema from the SAM **return type** using
`SchemaFactoryWrapper.acceptJsonFormatVisitor(returnTypeClass, visitor)`.
MUST NOT use `ToolSpecifications.toolSpecificationFrom()` — that generates the INPUT
parameter schema, not the output schema, and produces silent wrong behavior in native tools.

---

## 5. Persistence and transaction rules

### 5.1 SQLite transaction shape
- SQLite WAL mode requires short, tight transaction boundaries.
- Do not hold long transactions across slow I/O, LLM calls, or channel operations.

### 5.2 Schema evolution
- Regular entity-table evolution is entity-driven.
- Raw DDL is reserved for virtual tables, triggers, and indexes.
- Do not introduce ad hoc migration scripts for normal table changes unless the architecture policy is explicitly changed repository-wide.

### 5.3 Repository style
- Core repositories use `EntityManager`.
- Do not introduce Panache repository/entity patterns into cross-module persistence paths.

### 5.4 ORM 7 transaction behavior
- Be careful with `QuarkusTransaction.requiringNew().run(...)` lambda boundaries.
- Prefer dedicated `@Transactional(REQUIRES_NEW)` methods when proxy binding matters.
- Do not assume `em.merge()` is the right fix for already-managed entities.

### 5.5 Read visibility in tests
- Test writes must be committed before REST assertions that depend on them.
- Snapshot isolation assumptions in SQLite tests are easy to get wrong; prefer explicit cleanup and committed setup.

### 5.6 Identity / dialect
- The custom SQLite dialect is mandatory for ORM-generated identity-column DDL correctness.
- Do not remove or bypass it for entity tables using generated identifiers.

## 5.7 EncryptedSecret version field ownership

`EncryptedSecret.ormVersion` is Hibernate-managed. Application code MUST NOT read or set it.
`EncryptedSecret.encryptionKeyVersion` is application-managed. Application code sets ONLY this field.
Mixing these two fields corrupts encryption version tracking and is undetectable at runtime.

---

## 6. Native image and reflection rules

### 6.1 Reflection safety
- New classes/records/enums used reflectively in native mode must be registered when Quarkus does not detect them automatically.
- JVM success does not prove native safety.

### 6.2 Qute native rule
- Any class used as `.data("key", object)` in Qute and accessed by property in native mode must be `@TemplateData`.
- `@TemplateData` and reflection registration solve different problems; do not treat them as interchangeable.

### 6.3 Generated resources
- Build-time schema/resource generation must emit Quarkus generated resources, not write arbitrary files to the filesystem.

### 6.4 Resource loading
- For generated/augmented resources in multi-module tests, prefer context-classloader resource loading when required by current Quarkus augmentation behavior.

## 6.5 @RunOnVirtualThread enforcement

The constraint that JAX-RS resources injecting `EntityManager` MUST have `@RunOnVirtualThread`
is enforced at **build time** by `RestResourceBuildStep`. Violations fail the build with a
`ValidationError` — they do NOT surface as runtime `BlockingOperationNotAllowedException`.
Do not remove `RestResourceBuildStep` thinking it is optional infrastructure.


---

## 7. Channel and transport rules

### 7.1 Channel neutrality
- Generic dispatchers, events, and registries MUST remain channel-neutral.
- Missing metadata must not silently imply a specific channel.

### 7.2 Notification dispatch
- Cron completion and approval timeout dispatch must route by declared channel type.
- Any legacy fallback behavior must be isolated and marked for deletion.

### 7.3 MCP framing
- MCP stdio transport is newline-delimited JSON-RPC.
- Every outbound message must be newline framed and flushed.
- Inbound reading must be line-based.

### 7.4 Channel daemons
- Channel daemons must be safe when unconfigured.
- `isConfigured()` exists to prevent startup crashes due to absent optional channel config.

---

## 8. Sandbox and security rules

### 8.1 Secret handling
- Secrets must not be stored as immutable Strings when a zeroable `char[]` or byte[] lifecycle is required.
- Clear sensitive material immediately after use where the implementation already expects this.

### 8.2 Vault session identity
- System-level vault operations must use the dedicated system session identifier constant.
- Do not inline magic literals for system-session behavior.

### 8.3 Encryption invariants
- Associated authenticated data must remain bound to secret identity/key name.
- Do not weaken AAD binding or replace misuse-resistant encryption choices casually.

### 8.4 Seeder ordering
- Startup observers depending on dev-seeded secrets must execute after the seeder priority window.

### 8.5 Tool execution safety
- Sandbox/protected tools must default to isolated execution unless explicitly documented otherwise.
- Opting out of isolation is exceptional and requires strong justification.

## 8.6 Sandbox tool exception contract

`SandboxExecTool.execute()` MUST follow this exception contract:
- `BudgetExceededException` — MUST propagate (not catch); `AgentStateMachineRunner` handles it.
- `ToolExecutionException`, `TimeoutException`, `SecurityException`, `IOException` — MUST catch
  and return `ToolResponse.failure(message)`. Never propagate to `ToolInvoker`.
- Output exceeding 8192 characters MUST be truncated at exactly 8192 with suffix
  `[output truncated at 8192 chars]`. Never include raw Java stack traces in tool output.


---

## 9. Performance and reliability rules

### 9.1 Regex/pattern compilation
- Reusable regex/pattern objects must be `static final`.
- Do not compile patterns per request, per message, or inside hot-path methods.

### 9.2 Time measurement
- Use `System.nanoTime()` for elapsed durations.
- Do not use wall-clock time for duration measurement.

### 9.3 Async persistence backpressure
- Mandatory persistence events must never be dropped.
- Telemetry may degrade under bounded overflow only where explicitly designed.
- Backpressure behavior must be observable via metrics/logging.

### 9.4 Logging
- Do not log secrets or unsafe raw payloads by default.
- Debug facilities that expose prompts, tokens, or raw content must remain explicitly gated.

---

## 10. Configuration rules

### 10.1 Profile coverage
- Config changes must be reviewed across all relevant profiles and test overrides.
- Do not update one profile block and assume the others are safe.

### 10.2 Test-profile stability
- Test-only configuration that disables hot-reload or optional runtime behaviors must remain explicit and intentional.

### 10.3 Path resolution
- OS-native binaries and dynamic extensions must be resolved using normalized absolute paths when runtime behavior depends on working directory.
- Do not rely on launch-directory-relative behavior for native libs.

## 10.4 Test profile skill reload

The test profile MUST explicitly set:
  arcclaw.skills.hot-reload: false

Omitting this causes `SkillRegistry` to watch the filesystem during tests, producing
flaky failures when skill YAML files are rewritten by concurrent Maven forks.


---

## 11. Test discipline

### 11.1 Protected endpoint tests
- Tests for protected endpoints must include valid auth headers when the purpose is to test business behavior.
- A 401 for missing auth is not a passing business test.

### 11.2 Health tests
- Health checks may need test alternatives when production dependencies are intentionally absent in CI/test environments.
- Use CDI alternatives intentionally, not hidden environment dependencies.

### 11.3 Architecture-sensitive tests
The following areas require regression tests whenever touched:
- channel plugin discovery
- LLM provider SPI resolution
- workspace/session binding
- checkpoint resume/deny lifecycle
- SQLite vec fallback and path resolution
- MCP framing behavior
- native-sensitive template/reflection behavior
- sandbox circuit-breaker behavior

---

## 12. Legacy traps to avoid

- Do not copy framework workaround comments into new code without proving the workaround is still necessary.
- Do not add new stringly-typed conventions when a canonical accessor/type already exists.
- Do not solve architecture drift by adding more markdown-only warnings.
- Do not treat “works in dev mode” as proof of correctness for native mode, test mode, or cross-module execution.
- Do not introduce adapter-specific behavior into core just because Telegram is currently the busiest channel.

---

## 13. When this file must change

Update this file when:
- a real invariant is added or removed,
- an old workaround becomes obsolete,
- an invariant is moved from documentation into code/test/build enforcement,
- module boundaries or lifecycle rules materially change.

Do not update this file for:
- minor code style,
- one-off incidents already covered by tests,
- temporary experiments.

---

## 14. Enforcement backlog

The long-term goal is to shrink this document.

Every time an invariant here can be enforced by:
- startup validator,
- build step,
- architecture test,
- CDI decorator/interceptor,
- dedicated helper API,
- typed wrapper/value object,
- failing integration test,

---

## 15. Module CDI discovery contract

Every non-app, non-deployment Maven module that contains CDI beans MUST:
1. Include `jandex-maven-plugin` in its `pom.xml` build section.
2. Include `src/main/resources/META-INF/beans.xml` with `bean-discovery-mode="annotated"`.

Without Jandex, `@ApplicationScoped` beans in library modules are **silently invisible**
to the Quarkus CDI container — no error, no warning, just missing beans.
This applies to every `arcclaw-channel-*` module added in P67 and beyond.

---

## §7. Parallel Tool Execution

7.1 MUST: A parallel LLM turn (N tool calls in one ChatResponse) MUST
    be represented as a single ReasoningStep in agent history using
    action = ReasoningStep.PARALLEL_SENTINEL ("__parallel__").
    The full batch payload is stored in actionInput under key "batch".

7.2 MUST NOT: Expand parallel tool calls into N separate ReasoningStep
    entries in history before execution. History entries MUST map
    1:1 with LLM turns to preserve chat protocol correctness.

7.3 MUST: ChatHistoryAssembler MUST reconstruct a parallel turn as:
    ONE AiMessage(thought, [req1, req2, ..., reqN]) followed by
    N ToolExecutionResultMessages in callId order.

7.4 MUST: If ANY tool in a parallel batch requires HITL, ALL tools in
    the batch pause atomically. Partial per-tool approval is forbidden
    until Phase 71 defines the partial approval protocol.

7.5 MUST NOT: Execute parallel tools on the calling thread.
    Use ManagedExecutor (already injected via WorkspaceExecutors)
    for all parallel tool invocations.

7.6 MUST: All parallel tool spans MUST be siblings under the same
    parent iteration span. Use startToolSpan() per tool call.

7.7 MUST: Deduplication guard (ToolInvocationGuard) MUST check ALL
    tools in a batch before ANY tool executes. If any call is a
    duplicate, the entire batch is evaluated before execution begins.

7.8 MUST: ParallelToolCall.callId MUST follow the pattern "call_N"
    where N is the 0-based index in the LLM's tool call list.
    Do NOT use UUID for callIds — LangChain4j requires stable,
    predictable callId matching between AiMessage and
    ToolExecutionResultMessage.

7.9 MUST NOT: Modify ReasoningStep record shape in Phase 70.
    The parallel batch is encoded in actionInput["batch"] as JSON.
    Schema migration for a native parallel column is Phase 71+.

7.10 SHOULD: Test parallel tool execution with Claude 3.5 Sonnet and
     GPT-4o before enabling in production, as parallel tool call
     behavior differs between providers. Groq (llama-3.x) rarely
     issues parallel tool calls — most parallel call traffic will
     come from Anthropic and OpenAI providers.

7.11 LEGACY: Single getFirst() path in ReasoningStepParser is removed
     in Phase 70. Do NOT add it back as a fallback without updating
     this invariant first.
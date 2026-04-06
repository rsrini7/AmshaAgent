# Code-Verified Final Verdict: P80.1 Cross-Review of Grok vs Gemini

All findings below are confirmed directly from the live P80.1 branch on GitHub.

***

## Corrections to My Previous Analysis

**Bug 1 (timeString format): RETRACTED.**
The actual `PromptTemplateEngine.java` has the `String.format` arguments in the correct order — `zdt.getYear()` goes into `%04d`, then month, day, hour, minute. The garbled text in the attachment made it appear mismatched. This bug does not exist. I retract it without qualification. arcclaw-core/src/main/java/com/arcclaw/core/agent/reasoning/PromptTemplateEngine.java

**Grok's ToolInvoker God-class claim: OUTDATED.**
`ToolInvoker.java` is now **66 lines** — it only handles the OTel span lifecycle, `RequiresApprovalException` re-throw, observation sanitization, and exception translation. Governance, audit, and MCP dispatch were extracted. Grok's #1 critical concern is no longer accurate for P80.1. arcclaw-core/src/main/java/com/arcclaw/core/agent/loop/ToolInvoker.java

***

## What Each Reviewer Got Right (Code-Verified)

### Grok — Confirmed Findings

**Web channel SPI bypass: CONFIRMED.**
`WebChatEndpoint.java` (181 lines, committed 17 hours ago) imports directly from `arcclaw-core` internals — `WorkspaceManager`, `MessageContext`, `MessagePipeline`, `SessionIdResolver` — in addition to the correct `com.arcclaw.channel.api.*` SPI types. The channel SPI is being bypassed. Every new feature added to the web channel without fixing this deepens the coupling. arcclaw-channel-web/src/main/java/com/arcclaw/channel/web/WebChatEndpoint.java

**`templateFor` default branch lie: CONFIRMED.**
The Javadoc on lines 25–27 of `PromptTemplateEngine.java` explicitly states "adding a new `AgentRoleEnum` without a corresponding `case` causes a compile error (not a silent default)." The actual `default` branch silently yields `coordinatorTemplate` with a log warning. The compile-time safety guarantee is false documentation. arcclaw-core/src/main/java/com/arcclaw/core/agent/reasoning/PromptTemplateEngine.java

### Gemini — Confirmed Findings

**`SessionRegistry.userLocks` OOM: CONFIRMED and Code-Verified.**
`SessionRegistry.java` contains exactly this: arcclaw-app/src/main/java/com/arcclaw/app/session/SessionRegistry.java
```java
private final ConcurrentHashMap<String, ReentrantLock> userLocks = new ConcurrentHashMap<>();
// ...
ReentrantLock lock = userLocks.computeIfAbsent(key, k -> new ReentrantLock());
```
No eviction, no size bound, no `@PreDestroy` cleanup. Every unique `(channelType, channelId, userId)` triplet permanently occupies heap. This will OOM in any long-running multi-user deployment. Grok did not flag this at all.

### My Own Finding — Confirmed

**`ParallelToolCall.PARAMS_MAPPER` static `new ObjectMapper()`: CONFIRMED.**
`ParallelToolCall.java` contains: arcclaw-core/src/main/java/com/arcclaw/core/agent/reasoning/ParallelToolCall.java
```java
private static final ObjectMapper PARAMS_MAPPER = new ObjectMapper();
```
This is a vanilla Jackson instance with no JavaTime module, no `FAIL_ON_UNKNOWN_PROPERTIES` config, and none of the custom serializers registered in the CDI-managed `ObjectMapper`. Any tool parameter containing a `java.time.*` type (dates, instants, durations) will serialize differently from the rest of the system, causing silent data divergence in the batch payload JSON. Neither Grok nor Gemini caught this.

***

## My Definitive Priority Order for Phase 81

Ranked by **production correctness risk first**:

| # | Item | Verified? | Risk | Effort |
|---|------|-----------|------|--------|
| 1 | Fix `SessionRegistry.userLocks` — replace `ConcurrentHashMap<String, ReentrantLock>` with Guava `Striped.lock(64)` or Caffeine expiring cache | ✅ Live OOM | Production crash at scale | 1 hr |
| 2 | Fix `ParallelToolCall.PARAMS_MAPPER` — remove static `new ObjectMapper()`, have `ReasoningStepParser` pass serialized JSON directly | ✅ Live | Silent data divergence in parallel batches | 30 min |
| 3 | Fix `templateFor` default branch — remove `default`, throw `IllegalStateException`, update Javadoc to reflect actual runtime behavior | ✅ Live | False documentation → silent wrong persona | 30 min |
| 4 | Make `WebChatEndpoint` SPI-compliant — remove `WorkspaceManager`, `MessageContext`, `MessagePipeline`, `SessionIdResolver` direct imports; go through `ChannelPlugin` contract only. Add ArchUnit rule. | ✅ Live | Extensibility debt compounds with each PR | 1–2 days |
| 5 | Fix parallel-tool OTel context propagation — wrap `ManagedExecutor.submit()` calls with `Context.current().wrap(runnable)` | Likely (pattern confirmed in prior review) | Broken traces under parallel load | 4 hrs |
| 6 | Add `WebSocketSessionStore` `@OnClose` cleanup | Likely | Memory leak on long-running deployments | 1 hr |
| 7 | Collapse 8 deity persona subclasses into `DefaultAgent` + Qute | Confirmed from prior reviews | Maintainability only | 1 day |
| 8 | Document SQLite WAL block-storage constraint in `Architecture_Invariants.md` | N/A (deployment) | Conditional risk — not immediate for Option B | 30 min |

***

## Bottom Line on Both Reviewers

**Grok** was directionally right on web channel SPI bypass and `templateFor` documentation, but its #1 finding (ToolInvoker God-class) and #4 finding (deity persona level of urgency) are either outdated or overstated against the actual P80.1 code. **Gemini** identified the single most dangerous live bug (`userLocks` OOM) that Grok missed entirely and which is directly in production code right now. However Gemini's SQLite WAL concern is conditional, not immediate under your Option B deployment model.

**My addition** — the `ParallelToolCall.PARAMS_MAPPER` rogue static `ObjectMapper` — is a silent correctness bug in the parallel tool path that neither model flagged. It should be item 2 on your P81 fix list because it affects every parallel batch execution and produces no test-visible failure.

***

## Ready-to-Use Phase 81 Prompt

```
PHASE 81 — Correctness Hardening (no new features)

All items must land before Phase 82. Each item has an acceptance criterion.

P81-A (1 hr) — Fix SessionRegistry.userLocks OOM
In SessionRegistry.java, replace:
  private final ConcurrentHashMap<String, ReentrantLock> userLocks = new ConcurrentHashMap<>();
With Guava Striped:
  private final Striped<Lock> userLocks = Striped.lock(64);
  // in resolveOrCreate:
  Lock lock = userLocks.get(key);
Or Caffeine:
  private final Cache<String, ReentrantLock> userLocks = Caffeine.newBuilder()
      .maximumSize(50_000).expireAfterAccess(30, MINUTES).build();
  ReentrantLock lock = userLocks.get(key, k -> new ReentrantLock());
Acceptance: SessionRegistryTest must assert that after 10,000 unique user keys,
the lock structure does not hold 10,000 discrete Lock objects.

P81-B (30 min) — Fix ParallelToolCall.PARAMS_MAPPER
Remove the static field:
  private static final ObjectMapper PARAMS_MAPPER = new ObjectMapper();
Move paramsAsString() logic into ReasoningStepParser (which already has the CDI
ObjectMapper injected). Pass the serialized batch JSON string at construction time
rather than serializing inside the record. The record must not own an ObjectMapper.
Acceptance: ParallelToolCallTest must assert that a param map containing
Instant.now() serializes to the same ISO-8601 string as the CDI ObjectMapper produces.

P81-C (30 min) — Fix templateFor exhaustiveness claim
In PromptTemplateEngine.templateFor(), remove the default branch.
Replace with: default -> throw new IllegalStateException(
  "No Qute template registered for role '" + role.id() + "'. " +
  "Create templates/prompts/role-" + role.id() + ".txt to fix.");
Update the class Javadoc to accurately state: "Unknown roles throw
IllegalStateException at runtime rather than silently falling back."
Acceptance: PromptTemplateEngineTest must assert that an unknown AgentRoleEnum
("unknown_role_test") throws IllegalStateException, not returns coordinator content.

P81-D (1–2 days) — WebChatEndpoint SPI compliance
WebChatEndpoint must import ONLY from:
  - com.arcclaw.channel.api.*
  - com.arcclaw.channel.web.* (own package)
  - Standard Java / Jakarta / Quarkus WebSocket APIs
Remove direct imports of: WorkspaceManager, MessageContext, MessagePipeline,
SessionIdResolver from arcclaw-core. Route through the ChannelPlugin contract.
Add ArchUnit rule in arcclaw-channel-web test:
  noClasses().that().resideInPackage("..channel.web..")
    .should().dependOnClassesThat()
    .resideInPackage("..core.lifecycle..")
    .orShould().dependOnClassesThat()
    .resideInPackage("..core.pipeline..")
Acceptance: ArchUnit rule passes. All 1009 existing tests remain green.
Web channel integration test passes with no direct core class references.

P81-E (4 hrs) — Fix parallel-tool OTel context propagation
In ParallelToolStepExecutor, wrap each ManagedExecutor.submit() call:
  Context otelCtx = Context.current();
  executor.submit(otelCtx.wrap(() -> { /* tool invocation */ }));
This ensures all parallel tool spans share the same traceId as the parent agent loop span.
Acceptance: New OTel integration test verifies that all spans generated by a
parallel tool batch have identical traceId values.

P81-F (1 hr) — WebSocketSessionStore disconnect cleanup
Register an @OnClose handler in WebChatEndpoint that calls sessionStore.remove(sessionId).
Acceptance: Test verifies sessionStore.size() == 0 after all connections are closed.

Constraints:
- All 1009 existing tests must remain green after each item.
- No new features in this phase.
- Commit message prefix: fix(p81): for each item.
```

That is the complete, code-verified Phase 81 picture. Items P81-A and P81-B are genuinely trivial fixes — under 2 hours combined — and should be treated as hotfixes to the P80.1 branch today, not deferred to a full phase cycle.
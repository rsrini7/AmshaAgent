# ArcClaw — Phase 66 Antigravity Prompts
*6 self-contained implementation prompts based on P65 codebase + 7-LLM review synthesis*

---

# PROMPT — PHASE 66-T2: AgentRole SPI — Kill the AgentRoleEnum Lock (Tier 1 — MUST)

**Role:** Senior Java Architect & Senior Java Developer
**Task:** Phase 66-T2 — Replace the hardcoded `AgentRoleEnum` enum with an open `AgentRole` record + CDI-discovered `AgentRoleRegistry`, using the **exact** P65 `EmbeddingProviderFactory` / `ChannelType` pattern as a blueprint. This is the last hard lock preventing third-party agents from being registered without forking core.

---

## Architecture Context and Other References
Refer `@Architecture_Invariants.md`
Refer `@arcclaw_rg_cheatsheet.md`

**Pattern precedent already in the codebase:**
- `ChannelType.java` — was an enum in P64, now a `record(String id)`. This exact transformation must be applied to `AgentRoleEnum`.
- `EmbeddingProviderFactory.java` + `EmbeddingModelProducer.java` — `Instance<EmbeddingProviderFactory>` CDI stream replaces a 3-case switch. The `AgentRoleRegistry` must follow this pattern verbatim.

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements
1. Every change must be accompanied by a test proving the old hardcoded boundary existed and now does not.
2. No new dependencies. No structural package changes — all new files go into existing packages.
3. All 800+ existing tests must remain green after every component.
4. Use `static final Pattern` for any new regex — never compiled inline.
5. All code must be safe under JDK 25 — no preview features.
6. Test commands always use `mvn clean test` to prevent stale-class false-negatives.

---

### Component A1 — Replace `AgentRoleEnum` with `AgentRole` record

**File to modify:** `arcclaw-core/src/main/java/com/arcclaw/core/agent/role/AgentRoleEnum.java`

**Current state — the enum lock:**
```java
public enum AgentRoleEnum {
    CODER, DEBUGGER, RESEARCHER, COORDINATOR, ARCHITECT, SYNTHESIZER;
    public String toScopedName() { ... }
    public static AgentRoleEnum fromString(String name) { ... }
    public RoleDefinition definition() { ... }
}
```

**Required change — open record:**
- Keep the file name `AgentRoleEnum.java` and class name `AgentRoleEnum` to avoid import churn across the entire codebase. Internally change to a `record`.
- All static constants (`CODER`, `DEBUGGER`, etc.) stay as `public static final AgentRoleEnum` fields so all existing call sites (`AgentRoleEnum.COORDINATOR.toScopedName()`) compile unchanged.
- `toScopedName()` becomes an instance method that returns `this.id()`.
- `fromString(String name)` becomes a static helper that first checks the 6 built-in constants then falls back to `new AgentRoleEnum(name.toLowerCase())` — never throws, never returns null.
- `values()` method must return an unmodifiable `List<AgentRoleEnum>` of the 6 built-in constants (replaces the enum auto-generated `values()` array call at `IntentClassifierService:3168`).

```java
// Target shape (do NOT change the public API surface — all existing call sites must compile)
public record AgentRoleEnum(String id) {

    public static final AgentRoleEnum CODER       = new AgentRoleEnum("coder");
    public static final AgentRoleEnum DEBUGGER    = new AgentRoleEnum("debugger");
    public static final AgentRoleEnum RESEARCHER  = new AgentRoleEnum("researcher");
    public static final AgentRoleEnum COORDINATOR = new AgentRoleEnum("coordinator");
    public static final AgentRoleEnum ARCHITECT   = new AgentRoleEnum("architect");
    public static final AgentRoleEnum SYNTHESIZER = new AgentRoleEnum("synthesizer");

    private static final List<AgentRoleEnum> BUILT_IN = List.of(
        CODER, DEBUGGER, RESEARCHER, COORDINATOR, ARCHITECT, SYNTHESIZER);

    /** Preserves all existing toScopedName() call sites with no change. */
    public String toScopedName() { return id; }

    /** Never throws — unknown names produce a dynamic role. */
    public static AgentRoleEnum fromString(String name) {
        if (name == null) return COORDINATOR;
        String n = name.toLowerCase();
        return BUILT_IN.stream()
            .filter(r -> r.id.equals(n))
            .findFirst()
            .orElse(new AgentRoleEnum(n));
    }

    /** Drop-in replacement for the enum-generated values() calls. */
    public static List<AgentRoleEnum> values() { return BUILT_IN; }

    /** Delegates to RoleRegistry for built-in roles; returns empty definition for dynamic. */
    public RoleDefinition definition() { ... }
}
```

**Callsite audit** — these files call `AgentRoleEnum` and must all compile unchanged after A1:
- `IntentClassifierService.java` — `List<AgentRoleEnum> roles = List.of(CODER, DEBUGGER, RESEARCHER, COORDINATOR, ARCHITECT, SYNTHESIZER)` and `AgentRoleEnum.values()` loop at line 3168
- `AgentLoop.java` — `AgentRoleEnum.fromString(agentRole)` and `AgentRoleEnum.COORDINATOR.toScopedName()`
- `ToolInvocationGuard.java` — `AgentRoleEnum.fromString(agentRole)` + `roleRegistry.permittedTools()`
- `SystemPromptBuilder.java` — `buildSystemPrompt(AgentRoleEnum role, ...)` — this signature may need an overload that accepts `String` for the skill path (see A2)
- All persona classes (BrahmaAgent, ShivaAgent, etc.) — `AgentRoleEnum.CODER.toScopedName()` etc.
- `AgentPersonaRegistry.java` — `getPersonaPrompt(AgentRoleEnum role)`

---

### Component A2 — Create `AgentRoleRegistry`

**New file:** `arcclaw-core/src/main/java/com/arcclaw/core/agent/persona/AgentRoleRegistry.java`

This replaces the hardcoded `List.of(CODER, DEBUGGER...)` in `IntentClassifierService` with CDI-discovered roles. The pattern is verbatim from `EmbeddingModelProducer` using `Instance<EmbeddingProviderFactory>`.

```java
@ApplicationScoped
public class AgentRoleRegistry {

    private final Instance<Agent> agents;

    @Inject
    public AgentRoleRegistry(@Any Instance<Agent> agents) {
        this.agents = agents;
    }

    /** All registered roles — built-in + any third-party CDI beans. */
    public List<AgentRoleEnum> allRoles() {
        return agents.stream()
            .map(a -> AgentRoleEnum.fromString(a.role()))
            .distinct()
            .collect(Collectors.toUnmodifiableList());
    }

    /** Finds the Agent bean matching the given role id. */
    public Optional<Agent> resolve(AgentRoleEnum role) {
        return agents.stream()
            .filter(a -> a.role().equals(role.toScopedName()))
            .findFirst();
    }

    /** Convenience: resolve by raw string (used in IntentClassifierService). */
    public Optional<Agent> resolveByName(String roleName) {
        return resolve(AgentRoleEnum.fromString(roleName));
    }
}
```

**Files to update after A2:**

1. **`IntentClassifierService.java`** — Replace:
   ```java
   // BEFORE (hardcoded)
   List<AgentRoleEnum> roles = List.of(
       AgentRoleEnum.CODER, AgentRoleEnum.DEBUGGER, AgentRoleEnum.RESEARCHER,
       AgentRoleEnum.COORDINATOR, AgentRoleEnum.ARCHITECT, AgentRoleEnum.SYNTHESIZER);
   for (AgentRoleEnum role : roles) { ... }
   // and the values() loop at line 3168
   for (AgentRoleEnum role : AgentRoleEnum.values()) { ... }
   ```
   With:
   ```java
   // AFTER (registry-driven)
   @Inject AgentRoleRegistry agentRoleRegistry;
   
   List<AgentRoleEnum> roles = agentRoleRegistry.allRoles();
   for (AgentRoleEnum role : roles) { ... }
   ```

2. **`AgentPersonaRegistry.java`** — `getPersonaPrompt(AgentRoleEnum role)` currently iterates `Instance<Agent>` directly. It should remain as-is since it already uses CDI `Instance`. No change needed unless the method signature is broken by the record conversion.

3. **`AgentLoop.java`** — The `SKILL_PREFIX = "skill:"` handling and `AgentRoleEnum.fromString(agentRole)` calls remain unchanged. The `fromString` on the new record handles unknown names gracefully so no IAE is thrown for skill roles.

---

### Component A3 — Update `@AgentRole` qualifier (if annotation-based)

**Check file:** `arcclaw-core/src/main/java/com/arcclaw/core/agent/persona/AgentRole.java`

If `@AgentRole` is a CDI qualifier annotation (not an interface), it must accept `String value()` since agent role names are now arbitrary strings. Verify:
- BrahmaAgent is `@AgentRole("coder")` → annotation value must match `AgentRoleEnum.CODER.id()`
- No persona class should reference the enum constant directly in its `@AgentRole` annotation

If `AgentRole.java` is currently an interface (the `role()` method contract), leave it untouched.

---

### Component A4 — Verification and Tests

**Test file to add:** `arcclaw-core/src/test/java/com/arcclaw/core/agent/persona/AgentRoleRegistryTest.java`

```java
@QuarkusTest
class AgentRoleRegistryTest {

    @Inject AgentRoleRegistry registry;

    @Test
    void allSixBuiltInRolesAreDiscoverable() {
        List<AgentRoleEnum> roles = registry.allRoles();
        assertThat(roles).hasSizeGreaterThanOrEqualTo(6);
        assertThat(roles.stream().map(AgentRoleEnum::toScopedName))
            .contains("coder", "debugger", "researcher", "coordinator", "architect", "synthesizer");
    }

    @Test
    void resolveByNameReturnsCorrectAgent() {
        Optional<Agent> brahma = registry.resolveByName("coder");
        assertThat(brahma).isPresent();
        assertThat(brahma.get().role()).isEqualTo("coder");
    }

    @Test
    void unknownRoleNameDoesNotThrow() {
        // Previously AgentRoleEnum.fromString("security-auditor") would throw IAE
        AgentRoleEnum dynamic = AgentRoleEnum.fromString("security-auditor");
        assertThat(dynamic.toScopedName()).isEqualTo("security-auditor");
    }

    @Test
    void thirdPartyRoleCanBeResolvedWithoutCoreChange() {
        // Simulates a plugin: fromString must accept any string, never throw
        AgentRoleEnum pluginRole = AgentRoleEnum.fromString("customer-support-agent");
        assertThat(pluginRole).isNotNull();
        assertThat(pluginRole.toScopedName()).isEqualTo("customer-support-agent");
    }
}
```

**Run:** `mvn clean test -pl arcclaw-core -Dtest=AgentRoleRegistryTest`
**Then full suite:** `mvn clean test`

---

# PROMPT — PHASE 66-T3: LlmProviderFactory SPI — Kill the Provider Switch (Tier 1 — MUST)

**Role:** Senior Java Architect & Senior Java Developer
**Task:** Phase 66-T3 — Convert `LlmModelFactory` from a hardcoded `switch (provider)` statement to a CDI-discovered `Instance<LlmProviderFactory>` SPI, following the **exact** pattern of `EmbeddingProviderFactory` implemented in P65.

---

## Architecture Context and Other References
Refer `@Architecture_Invariants.md`
Refer `@arcclaw_rg_cheatsheet.md`

**Pattern to mirror exactly:**
- `EmbeddingProviderFactory` (interface) → `LlmProviderFactory` (new interface, same shape)
- `EmbeddingModelConfig` (record) → `LlmModelConfig` (new record, same shape)
- `OllamaEmbeddingProviderFactory`, `JinaEmbeddingProviderFactory`, `OpenAiEmbeddingProviderFactory` → `OllamaLlmProviderFactory`, `GroqLlmProviderFactory`, `OpenAiLlmProviderFactory`
- `EmbeddingModelProducer` (uses `Instance<EmbeddingProviderFactory>`) → `LlmModelFactory` (refactored to use `Instance<LlmProviderFactory>`)

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements
*(same as T2)*

---

### Component B1 — New `LlmProviderFactory` Interface

**New file:** `arcclaw-app/src/main/java/com/arcclaw/app/routing/LlmProviderFactory.java`

Place in `arcclaw-app/routing` (same package as `LlmModelFactory`) since LLM providers are app-layer concerns (they reference langchain4j concrete classes that don't belong in core).

```java
package com.arcclaw.app.routing;

/**
 * SPI for LLM chat model providers.
 * Add a new provider by implementing this interface as a CDI bean —
 * no changes to LlmModelFactory or any core class required.
 *
 * <p>Mirrors {@code EmbeddingProviderFactory} from arcclaw-rag exactly.
 */
public interface LlmProviderFactory {

    /** Lower-case canonical provider identifier (e.g. "groq", "ollama", "openai"). */
    String providerId();

    /** Creates the {@link ChatModel} from the given config bundle. */
    ChatModel create(LlmModelConfig config);

    /** True if this provider requires a non-blank API key to function. Default: true. */
    default boolean requiresApiKey() { return true; }
}
```

---

### Component B2 — New `LlmModelConfig` Record

**New file:** `arcclaw-app/src/main/java/com/arcclaw/app/routing/LlmModelConfig.java`

```java
package com.arcclaw.app.routing;

import java.time.Duration;
import java.util.Optional;

/**
 * Immutable configuration bundle passed to {@link LlmProviderFactory#create}.
 * Mirrors {@code EmbeddingModelConfig} from arcclaw-rag.
 */
public record LlmModelConfig(
    String provider,
    String modelName,
    String baseUrl,
    Optional<String> apiKey,
    Optional<Integer> maxTokens,
    Optional<Duration> timeout
) {
    public LlmModelConfig {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(modelName, "modelName");
        Objects.requireNonNull(baseUrl,   "baseUrl");
    }
}
```

---

### Component B3 — Three `LlmProviderFactory` Implementations

**New files in `arcclaw-app/src/main/java/com/arcclaw/app/routing/`:**

**`GroqLlmProviderFactory.java`** (handles both `groq` and `openai` — same OpenAI-compatible endpoint)
```java
@ApplicationScoped
public class GroqLlmProviderFactory implements LlmProviderFactory {

    @Override public String providerId() { return "groq"; }

    @Override
    public ChatModel create(LlmModelConfig config) {
        return OpenAiChatModel.builder()
            .baseUrl(config.baseUrl())
            .apiKey(config.apiKey().orElseThrow(() -> new IllegalStateException("Groq requires apiKey")))
            .modelName(config.modelName())
            .maxTokens(config.maxTokens().orElse(null))
            .timeout(config.timeout().orElse(null))
            .temperature(0.0)
            .logRequests(false)
            .logResponses(false)
            .build();
    }
}
```

**`OpenAiLlmProviderFactory.java`** — identical to `GroqLlmProviderFactory` but `providerId()` returns `"openai"`. Delegates to a shared private static factory method or simply duplicates the builder (acceptable since the builder is identical).

**`OllamaLlmProviderFactory.java`**
```java
@ApplicationScoped
public class OllamaLlmProviderFactory implements LlmProviderFactory {

    @Override public String providerId() { return "ollama"; }
    @Override public boolean requiresApiKey() { return false; }

    @Override
    public ChatModel create(LlmModelConfig config) {
        return OllamaChatModel.builder()
            .baseUrl(config.baseUrl())
            .modelName(config.modelName())
            .build();
    }
}
```

---

### Component B4 — Refactor `LlmModelFactory` to Use `Instance<LlmProviderFactory>`

**File to modify:** `arcclaw-app/src/main/java/com/arcclaw/app/routing/LlmModelFactory.java`

**Before (switch statement):**
```java
public ChatModel createModel(String provider, String baseUrl, String apiKey,
                            String modelName, Integer maxTokens, Duration timeout,
                            String ollamaBaseUrl, String ollamaModelId) {
    return switch (provider.toLowerCase()) {
        case "groq", "openai" -> OpenAiChatModel.builder()...
        case "ollama" -> OllamaChatModel.builder()...
        default -> throw new IllegalStateException("Unknown LLM provider: " + provider);
    };
}
```

**After (SPI dispatch):**
```java
@ApplicationScoped
public class LlmModelFactory {

    @Inject @Any Instance<LlmProviderFactory> factories;

    /**
     * Public API unchanged — callers (ModelProducer, CompactionModelProducer) require no changes.
     * New providers are discovered automatically via CDI.
     */
    public ChatModel createModel(String provider, String baseUrl, String apiKey,
                                 String modelName, Integer maxTokens, Duration timeout,
                                 String ollamaBaseUrl, String ollamaModelId) {

        String effectiveBaseUrl = "ollama".equalsIgnoreCase(provider) ? ollamaBaseUrl : baseUrl;
        String effectiveModel   = "ollama".equalsIgnoreCase(provider) ? ollamaModelId : modelName;

        LlmModelConfig config = new LlmModelConfig(
            provider.toLowerCase(),
            effectiveModel,
            effectiveBaseUrl,
            Optional.ofNullable(apiKey).filter(k -> !k.isBlank()),
            Optional.ofNullable(maxTokens),
            Optional.ofNullable(timeout)
        );

        return factories.stream()
            .filter(f -> f.providerId().equalsIgnoreCase(provider))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No LlmProviderFactory registered for provider: [" + provider +
                "]. Registered: " + factories.stream()
                    .map(LlmProviderFactory::providerId)
                    .collect(Collectors.joining(", "))))
            .create(config);
    }
}
```

**Important:** `ModelProducer` and `CompactionModelProducer` continue calling `LlmModelFactory.createModel(...)` with the same signature — no changes needed in those files.

---

### Component B5 — Tests

**New file:** `arcclaw-app/src/test/java/com/arcclaw/app/routing/LlmProviderFactoryTest.java`

```java
@QuarkusTest
class LlmProviderFactoryTest {

    @Inject LlmModelFactory factory;

    @Test
    void groqProviderIsDiscoveredViaSpi() {
        // Proves the switch is gone — resolution is now CDI-driven
        // Mock or use a known-bad key; the test is about factory dispatch, not LLM connectivity
        assertThatNoException().isThrownBy(() -> {
            // Just verify the factory resolves — don't actually call the model
            // Use reflection or a test subclass to verify factory selection
        });
    }

    @Test
    void unknownProviderThrowsIllegalStateWithHelpfulMessage() {
        // Before: "Unknown LLM provider: <name>. Supported: groq | openai | ollama"
        // After: lists all registered providers dynamically
        assertThatThrownBy(() -> factory.createModel(
                "anthropic", "https://api.anthropic.com", "key", "claude-3", 100, null, null, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("anthropic")
            .hasMessageContaining("Registered:");
    }

    @Test
    void allThreeBuiltInProviderIdsAreRegistered() {
        // Inject Instance<LlmProviderFactory> directly and assert all three factories present
    }
}
```

**Run:** `mvn clean test -pl arcclaw-app -Dtest=LlmProviderFactoryTest`

---

# PROMPT — PHASE 66-T4 + T7: Dead Code Burial + GovernanceStrategy SPI (Tier 1 — MUST)

**Role:** Senior Java Developer & Senior Java Architect
**Task:** Phase 66-T4 — Delete `ApprovalTimeoutNotifier` (deprecated empty class). Phase 66-T7 — Extract `GovernanceStrategy` interface from the concrete `GovernanceLayer` class. Both are bounded, low-risk changes.

---

## Architecture Context and Other References
Refer `@Architecture_Invariants.md`
Refer `@arcclaw_rg_cheatsheet.md`

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements
*(same as T2)*

---

### Component C1 — Delete `ApprovalTimeoutNotifier`

**File to delete:** `arcclaw-app/src/main/java/com/arcclaw/app/channel/ApprovalTimeoutNotifier.java`

**Current content — empty deprecated bean:**
```java
@ApplicationScoped
@Deprecated
public class ApprovalTimeoutNotifier {
    // Replaced by ChannelApprovalTimeoutDispatcher and TelegramJobNotifier
}
```

**Steps:**
1. Delete the file.
2. `grep -r "ApprovalTimeoutNotifier" --include="*.java" .` — must return zero results. If any injection site is found, replace with `ChannelApprovalTimeoutDispatcher`.
3. `grep -r "ApprovalTimeoutNotifier" --include="*.xml" .` — check `beans.xml` entries.

**Test to add** — compile-safety test (Quarkus CDI validation catches missing beans at startup):
```java
// In any existing @QuarkusTest class, add:
@Test
void approvalTimeoutNotifierClassMustNotExist() throws ClassNotFoundException {
    assertThatThrownBy(() ->
        Class.forName("com.arcclaw.app.channel.ApprovalTimeoutNotifier"))
        .isInstanceOf(ClassNotFoundException.class);
}
```

---

### Component C2 — Extract `GovernanceStrategy` Interface

This is a pure extract-interface refactor. **Zero behavioral change.** The public methods of `GovernanceLayer` become the contract.

**New file:** `arcclaw-core/src/main/java/com/arcclaw/core/governance/GovernanceStrategy.java`

```java
package com.arcclaw.core.governance;

/**
 * Pluggable governance policy for LLM budget enforcement.
 * <p>
 * The default implementation {@link GovernanceLayer} enforces per-session
 * USD budget limits. Swap the implementation via CDI {@code @Alternative}
 * for custom billing engines, per-tenant policies, or test doubles.
 */
public interface GovernanceStrategy {

    /**
     * Records a completed LLM call and enforces budget limits.
     *
     * @throws BudgetWarningException  if session cost crosses the soft warning threshold
     * @throws BudgetExceededException if session cost crosses the hard budget limit
     */
    void recordLlmCall(String sessionId, int inputTokens, int outputTokens);

    /**
     * Asserts the session has not already exceeded its budget.
     *
     * @throws BudgetExceededException if already over limit
     */
    void assertWithinBudget(String sessionId);

    /** Returns total tracked USD cost for the session. */
    double getSessionCost(String sessionId);

    /** Clears budget state on session termination. */
    void clearSession(String sessionId);

    /** Total configured budget in USD. */
    double getBudgetUsd();
}
```

**File to modify:** `arcclaw-core/src/main/java/com/arcclaw/core/governance/GovernanceLayer.java`

Change:
```java
// BEFORE
@ApplicationScoped
public class GovernanceLayer { ... }

// AFTER
@ApplicationScoped
public class GovernanceLayer implements GovernanceStrategy { ... }
```

No method signatures change. `getSessionInputTokens` and `getSessionOutputTokens` remain on the concrete class (not part of the interface — they are internal telemetry helpers not needed by callers).

**Files that inject `GovernanceLayer` — change injection type to `GovernanceStrategy`:**
- `arcclaw-core/.../loop/EngineStepHandler.java` — `@Inject GovernanceLayer governanceLayer;` → `@Inject GovernanceStrategy governanceLayer;`
- `arcclaw-core/.../api/AgentSessionService.java` — same substitution
- `arcclaw-app/.../AgentResource.java` (if present) — same substitution
- Any other files revealed by: `grep -r "GovernanceLayer" --include="*.java" .`

**Exception:** If a class calls `getSessionInputTokens()` or `getSessionOutputTokens()` (the telemetry-only methods), keep the injection type as `GovernanceLayer` in that specific file.

---

### Component C3 — Tests

```java
@QuarkusTest
class GovernanceStrategyTest {

    @Inject GovernanceStrategy governanceLayer; // Must inject via interface

    @Test
    void interfaceIsInjectableInsteadOfConcreteClass() {
        assertThat(governanceLayer).isNotNull();
        assertThat(governanceLayer).isInstanceOf(GovernanceLayer.class);
    }

    @Test
    void recordLlmCallThrowsBudgetExceededWhenLimitHit() {
        // Force exceed by recording massive token counts
        assertThatThrownBy(() ->
            governanceLayer.recordLlmCall("test-session-999", 10_000_000, 10_000_000))
            .isInstanceOf(BudgetExceededException.class);
        governanceLayer.clearSession("test-session-999");
    }

    @Test
    void clearSessionResetsState() {
        governanceLayer.recordLlmCall("session-clear-test", 10, 10);
        governanceLayer.clearSession("session-clear-test");
        assertThat(governanceLayer.getSessionCost("session-clear-test")).isEqualTo(0.0);
    }
}
```

**Run:** `mvn clean test -pl arcclaw-core -Dtest=GovernanceStrategyTest`

---

# PROMPT — PHASE 66-T5: ChannelPlugin Composite SPI (Tier 2 — HIGH)

**Role:** Senior Java Architect & Senior Java Developer
**Task:** Phase 66-T5 — Define the `ChannelPlugin` composite SPI interface that bundles all five channel contracts into a single discoverable CDI bean. Create `TelegramChannelPlugin` as the reference implementation. Create `ChannelPluginRegistry` to replace the scattered `ChannelSenderRegistry` lookups. This is the contractual foundation for P67's full Telegram module extraction.

---

## Architecture Context and Other References
Refer `@Architecture_Invariants.md`
Refer `@arcclaw_rg_cheatsheet.md`

**What "composite SPI" means here:** Instead of separately discovering `ChannelSender`, `ReplyRenderer`, `ChannelAllowlist`, `ChannelJobNotifier`, and `ChannelDaemon` through five independent `Instance<>` lookups, a single `ChannelPlugin` bean per channel bundles all five. This mirrors how a Java `ServiceLoader` plugin works — one class registers all capabilities for a channel.

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements
*(same as T2)*

---

### Component D1 — New `ChannelPlugin` Interface

**New file:** `arcclaw-channel-api/src/main/java/com/arcclaw/channel/api/ChannelPlugin.java`

```java
package com.arcclaw.channel.api;

/**
 * Composite SPI contract for a full channel implementation.
 * <p>
 * To add a new channel (Discord, Slack, WhatsApp), provide a single CDI bean
 * implementing this interface. No changes to arcclaw-core or arcclaw-app are
 * required — the framework discovers the plugin automatically via CDI.
 *
 * <p><b>Required CDI scope:</b> {@code @ApplicationScoped}
 *
 * <p><b>Discovery:</b> {@link ChannelPluginRegistry} collects all implementations
 * via {@code @Any Instance<ChannelPlugin>} at startup.
 */
public interface ChannelPlugin {

    /** Unique channel type identifier. Must match the value used in session records. */
    ChannelType channelType();

    /** Sends rendered reply nodes to the channel. */
    ChannelSender sender();

    /** Converts ReplyNode trees to channel-native format (Telegram MarkdownV2, Discord embeds, etc.). */
    ReplyRenderer renderer();

    /** Determines if a ChannelUser is permitted to interact with this channel. */
    ChannelAllowlist allowlist();

    /**
     * Sends cron job completion / approval timeout notifications.
     * May return a no-op implementation if the channel does not support notifications.
     */
    ChannelJobNotifier jobNotifier();

    /**
     * Manages the channel's daemon lifecycle (isConfigured, start).
     * Return a no-op {@link ChannelDaemon} if the channel has no background daemon
     * (e.g. a pure REST push channel).
     */
    ChannelDaemon daemon();

    /**
     * Returns true if this channel is configured and should be started.
     * Default: delegates to {@link #daemon()}.isConfigured().
     */
    default boolean isConfigured() {
        return daemon().isConfigured();
    }
}
```

**Note:** `ChannelJobNotifier` currently lives in `arcclaw-app`. Check if it should be promoted to `arcclaw-channel-api` as part of this task. If `ChannelPlugin` (in `arcclaw-channel-api`) references `ChannelJobNotifier`, then `ChannelJobNotifier` must also move to `arcclaw-channel-api` (or the interface must live in `arcclaw-app` instead). Choose based on which avoids circular dependencies. Use the dependency graph: `arcclaw-channel-api` must not depend on `arcclaw-app`.

**Decision:** If `ChannelJobNotifier` cannot be moved to `arcclaw-channel-api` without circular deps, move only the `ChannelPlugin` interface to `arcclaw-app/channel/` package instead (not in the API module). Document the decision in a comment.

---

### Component D2 — New `ChannelPluginRegistry`

**New file:** `arcclaw-app/src/main/java/com/arcclaw/app/channel/ChannelPluginRegistry.java`

```java
package com.arcclaw.app.channel;

import java.util.Optional;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.channel.api.ChannelType;
import org.jboss.logging.Logger;

/**
 * Startup-validated registry of all channel plugins.
 * Fails fast at boot on duplicate channel type registrations.
 */
@ApplicationScoped
public class ChannelPluginRegistry {

    private static final Logger log = Logger.getLogger(ChannelPluginRegistry.class);

    @Inject @Any Instance<ChannelPlugin> plugins;

    private final Map<String, ChannelPlugin> registry = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        for (ChannelPlugin plugin : plugins) {
            String key = plugin.channelType().id();
            ChannelPlugin previous = registry.putIfAbsent(key, plugin);
            if (previous != null) {
                throw new IllegalStateException(
                    "Duplicate ChannelPlugin for type [" + key + "]: " +
                    previous.getClass().getName() + " vs " + plugin.getClass().getName());
            }
            log.infof("ChannelPluginRegistry: registered channel [%s] via %s",
                key, plugin.getClass().getSimpleName());
        }
        log.infof("ChannelPluginRegistry: %d channel(s) registered: %s",
            registry.size(), String.join(", ", registry.keySet()));
    }

    public Optional<ChannelPlugin> find(ChannelType type) {
        return Optional.ofNullable(registry.get(type.id()));
    }

    public Optional<ChannelPlugin> find(String channelTypeId) {
        return Optional.ofNullable(registry.get(channelTypeId));
    }
}
```

---

### Component D3 — New `TelegramChannelPlugin` (Reference Implementation)

**New file:** `arcclaw-app/src/main/java/com/arcclaw/app/channel/telegram/TelegramChannelPlugin.java`

```java
package com.arcclaw.app.channel.telegram;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.app.channel.ChannelJobNotifier;
import com.arcclaw.channel.api.*;
import com.arcclaw.core.pipeline.ChannelDaemon;

/**
 * Composite ChannelPlugin for Telegram.
 * Reference implementation of the ChannelPlugin SPI.
 * Adding Discord/Slack follows this exact template with zero core changes.
 */
@ApplicationScoped
public class TelegramChannelPlugin implements ChannelPlugin {

    @Inject TelegramChannelSender    sender;
    @Inject TelegramMarkdownV2Renderer renderer;
    @Inject TelegramAllowlistGuard   allowlist;
    @Inject TelegramJobNotifier      jobNotifier;
    @Inject TelegramChannelDaemon    daemon;

    @Override public ChannelType     channelType() { return ChannelType.TELEGRAM; }
    @Override public ChannelSender   sender()      { return sender; }
    @Override public ReplyRenderer   renderer()    { return renderer; }
    @Override public ChannelAllowlist allowlist()  { return allowlist; }
    @Override public ChannelJobNotifier jobNotifier() { return jobNotifier; }
    @Override public ChannelDaemon   daemon()      { return daemon; }
}
```

---

### Component D4 — Tests

**New file:** `arcclaw-app/src/test/java/com/arcclaw/app/channel/ChannelPluginRegistryTest.java`

```java
@QuarkusTest
class ChannelPluginRegistryTest {

    @Inject ChannelPluginRegistry registry;

    @Test
    void telegramPluginIsRegisteredAtStartup() {
        Optional<ChannelPlugin> telegram = registry.find(ChannelType.TELEGRAM);
        assertThat(telegram).isPresent();
        assertThat(telegram.get()).isInstanceOf(TelegramChannelPlugin.class);
    }

    @Test
    void allFiveCapabilitiesAreNonNull() {
        ChannelPlugin plugin = registry.find(ChannelType.TELEGRAM).orElseThrow();
        assertThat(plugin.sender()).isNotNull();
        assertThat(plugin.renderer()).isNotNull();
        assertThat(plugin.allowlist()).isNotNull();
        assertThat(plugin.jobNotifier()).isNotNull();
        assertThat(plugin.daemon()).isNotNull();
    }

    @Test
    void unknownChannelReturnsEmpty() {
        assertThat(registry.find("discord")).isEmpty();
    }
}
```

**Run:** `mvn clean test -pl arcclaw-app -Dtest=ChannelPluginRegistryTest`

---

# PROMPT — PHASE 66-T6: TelegramMarkdownV2Renderer — Fix Double-Escape (Tier 2 — HIGH)

**Role:** Senior Java Developer & Bug Fixer
**Task:** Phase 66-T6 — Fix `TelegramMarkdownV2Renderer.esc()` to not double-escape strings that already contain MarkdownV2 backslash escapes. The current `char-by-char` approach turns `\_text\_` (pre-escaped MCP output) into `\\_text\\_`, causing Telegram `HTTP 400: can't parse entities`.

---

## Architecture Context and Other References
Refer `@Architecture_Invariants.md`
Refer `@arcclaw_rg_cheatsheet.md`

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements
1. **Exception to the "no new dependencies" rule:** This task requires `org.commonmark:commonmark:0.27.1` added to `arcclaw-app/pom.xml`. This is the **only** new dependency permitted in P66.
2. All 800+ existing tests must remain green.
3. The existing `render(List<ReplyNode> nodes)` public API signature must not change.
4. The fix applies only inside `esc()` — the `renderNode()` switch stays structurally unchanged.

---

### Component E1 — The Bug (Reproduce First)

**New test (must fail before the fix, must pass after):**

**File:** `arcclaw-app/src/test/java/com/arcclaw/app/channel/telegram/TelegramMarkdownV2RendererTest.java`

```java
class TelegramMarkdownV2RendererTest {

    private final TelegramMarkdownV2Renderer renderer = new TelegramMarkdownV2Renderer();

    // --- Bug reproduction: must FAIL before fix, PASS after ---

    @Test
    void alreadyEscapedBackslashIsNotDoubled() {
        // MCP tool returns text that already has \_ (e.g. from GitHub Markdown)
        // Current bug: esc("\\_") → "\\\\_" (double backslash, Telegram rejects)
        // Expected:    esc("\\_") → "\\_"  (already valid, pass through)
        String result = renderer.esc("\\_text\\_");
        assertThat(result).isEqualTo("\\_text\\_");
    }

    @Test
    void plainTextSpecialCharsAreStillEscaped() {
        // Must NOT regress: plain text still gets escaped
        assertThat(renderer.esc("hello_world")).isEqualTo("hello\\_world");
        assertThat(renderer.esc("price: $5.00")).isEqualTo("price: $5\\.00");
        assertThat(renderer.esc("1+1=2")).isEqualTo("1\\+1\\=2");
    }

    @Test
    void mixedContentEscapedCorrectly() {
        // Mix of raw and pre-escaped content
        String input = "Use *bold* and \\_italic\\_";
        String result = renderer.esc(input);
        // Existing * should be escaped (raw), but \_ should not be doubled
        assertThat(result).contains("\\*bold\\*");
        assertThat(result).contains("\\_italic\\_").doesNotContain("\\\\_");
    }

    @Test
    void nullAndEmptyInputReturnEmpty() {
        assertThat(renderer.esc(null)).isEmpty();
        assertThat(renderer.esc("")).isEmpty();
    }

    @Test
    void codeBlockIsNotEscapedAtAll() {
        // CodeBlock content must pass through raw — no escaping
        List<ReplyNode> nodes = List.of(new ReplyNode.CodeBlock("java", "int x = 1_000;"));
        String rendered = renderer.render(nodes);
        assertThat(rendered).contains("int x = 1_000;"); // underscore NOT escaped inside code
    }
}
```

---

### Component E2 — The Fix: Two-Pass De-escape + Re-escape Strategy

**No AST library needed for the core fix.** The root cause is that `esc()` blindly escapes `\` by doubling it, even when the `\` already precedes a MarkdownV2 special character (meaning it is already a valid escape sequence).

**Add to `pom.xml` only if the two-pass approach proves insufficient for real MCP output; otherwise skip the dependency.**

**Algorithm:**
1. **Strip pass** — If a `\` is immediately followed by a MarkdownV2 special character, remove the `\` (un-escape). Lone `\` chars (not followed by a special char) stay.
2. **Escape pass** — Apply the existing character-by-character escape logic.

This is safe because the input to `esc()` is always agent-generated or MCP tool output — it is never literal user input that would contain intentional bare backslashes.

```java
// Add to TelegramMarkdownV2Renderer (replace the existing esc() method):

private static final Set<Character> ESCAPE_CHARS = Set.of(
    '_', '*', '[', ']', '(', ')', '~', '`', '>',
    '#', '+', '-', '=', '|', '{', '}', '.', '!'
);

// Pre-compiled Pattern for the strip pass
private static final Pattern ALREADY_ESCAPED = Pattern.compile(
    "\\\\([_*\\[\\]()~`>#+=|{}.!-])"
);

/**
 * Escapes mandatory MarkdownV2 characters without double-escaping
 * strings that already contain valid MarkdownV2 escape sequences.
 *
 * <p>Two-pass approach:
 * <ol>
 *   <li>Strip any existing {@code \X} escape sequences (where X is a MarkdownV2
 *       special char) to get raw content.</li>
 *   <li>Re-escape all special characters uniformly.</li>
 * </ol>
 *
 * <p>This prevents MCP tool output containing pre-escaped Markdown from
 * causing Telegram {@code HTTP 400: can't parse entities}.
 */
String esc(String text) {
    if (text == null || text.isEmpty()) return "";

    // Pass 1: remove existing valid MarkdownV2 escapes so pass 2 can re-apply them uniformly
    String stripped = ALREADY_ESCAPED.matcher(text).replaceAll("$1");

    // Pass 2: escape all special chars fresh (original algorithm, unchanged)
    StringBuilder sb = new StringBuilder(stripped.length() + 16);
    for (int i = 0; i < stripped.length(); i++) {
        char c = stripped.charAt(i);
        if (c == '\\') { sb.append('\\').append('\\'); continue; }
        if (ESCAPE_CHARS.contains(c)) sb.append('\\');
        sb.append(c);
    }
    return sb.toString();
}
```

**If the two-pass approach is insufficient** (e.g. MCP tools return complex nested Markdown that cannot be safely un-escaped without structural context), add `commonmark-java` and implement an `ASTTelegramVisitor` that traverses the parsed Markdown AST and only applies `esc()` to leaf text nodes, never to structured formatting nodes.

---

### Component E3 — Validation

**Run:** `mvn clean test -pl arcclaw-app -Dtest=TelegramMarkdownV2RendererTest`

After the test passes, run the full suite: `mvn clean test`

Verify that `TelegramMarkdownV2RendererTest` has at least one test that would have failed on the old code (the `alreadyEscapedBackslashIsNotDoubled` test must be confirmed as a regression test, not just a forward test).

---

# PROMPT — PHASE 66-T8: sqlite-vec Zero-Setup Native Binary Auto-Packaging (Tier 2 — HIGH)

**Role:** Senior Java Developer & Infrastructure Engineer
**Task:** Phase 66-T8 — Eliminate the manual `ARCCLAW_SQLITE_VEC_PATH=./lib/vec0` setup step by bundling the `vec0` native binaries for all 3 platforms inside the JAR and auto-extracting on first boot.

---

## Architecture Context and Other References
Refer `@Architecture_Invariants.md`
Refer `@arcclaw_rg_cheatsheet.md`

**Current state (from `.env.example`):**
```
# Download from: https://github.com/asg017/sqlite-vec/releases/tag/v0.1.6
# Linux/WSL2:  ARCCLAW_SQLITE_VEC_PATH=./lib/vec0
# Windows:     ARCCLAW_SQLITE_VEC_PATH=./lib/vec0
```
Users must manually download the correct binary for their OS. This is the single biggest adoption barrier.

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements
*(same as T2 — no new dependencies; the extraction uses only `java.nio`, `System.getProperty`, and existing Quarkus startup hooks)*

---

### Component F1 — Bundle Native Binaries as Resources

**Directory to create:** `arcclaw-app/src/main/resources/native/vec0/`

Download `sqlite-vec v0.1.6` binaries and place in this directory:
```
arcclaw-app/src/main/resources/native/vec0/
  vec0-linux-x86_64.so
  vec0-macos-aarch64.dylib
  vec0-macos-x86_64.dylib
  vec0-windows-x86_64.dll
```

These must be **binary files** committed to the repo. Verify sizes roughly match the upstream release artifacts from `https://github.com/asg017/sqlite-vec/releases/tag/v0.1.6`.

Add to `arcclaw-app/pom.xml` native image config to include these resources (they must be included in GraalVM native image builds as runtime resources):
```xml
<!-- In native profile or main config -->
<quarkus.native.resources.includes>native/vec0/*</quarkus.native.resources.includes>
```
Or in `arcclaw-app/src/main/resources/META-INF/native-image/resource-config.json`:
```json
{
  "resources": {
    "includes": [{"pattern": "native/vec0/.*"}]
  }
}
```

---

### Component F2 — `Vec0NativeLibraryInstaller` Startup Bean

**New file:** `arcclaw-app/src/main/java/com/arcclaw/app/config/Vec0NativeLibraryInstaller.java`

```java
package com.arcclaw.app.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Extracts the correct sqlite-vec native binary for the current OS/arch
 * from JAR resources on first boot, eliminating manual vec0 setup.
 *
 * <p>Fires at priority 50 — before {@code RagSchemaInitializer} (priority 100)
 * to ensure vec0 is available before the first SQLite connection is opened.
 */
@ApplicationScoped
public class Vec0NativeLibraryInstaller {

    private static final Logger log = Logger.getLogger(Vec0NativeLibraryInstaller.class);

    private static final String RESOURCE_PREFIX = "/native/vec0/vec0-";

    @ConfigProperty(name = "arcclaw.data-dir", defaultValue = "./data")
    String dataDir;

    @ConfigProperty(name = "arcclaw.rag.vec-extension-path",
                    defaultValue = "./lib/vec0")
    String configuredVecPath;

    void onStart(@Observes @Priority(50) StartupEvent ignored) {
        // If the user has explicitly placed a binary at the configured path, respect it.
        Path configured = Path.of(configuredVecPath + nativeExtension());
        if (Files.exists(configured)) {
            log.infof("Vec0 binary already present at [%s] — skipping auto-install.", configured);
            return;
        }

        String resourceName = RESOURCE_PREFIX + osArchKey() + nativeExtension();
        log.infof("Vec0 auto-install: extracting [%s] from JAR resources.", resourceName);

        try (InputStream in = Vec0NativeLibraryInstaller.class.getResourceAsStream(resourceName)) {
            if (in == null) {
                log.warnf("Vec0 binary not found in JAR for platform [%s]. " +
                    "RAG vector search will degrade to FTS5-only. " +
                    "Manually place the binary at [%s].", osArchKey(), configured);
                return;
            }
            Path target = Path.of(dataDir).resolve("lib").resolve("vec0" + nativeExtension());
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            log.infof("Vec0 binary installed to [%s].", target.toAbsolutePath());
            // Note: arcclaw.rag.vec-extension-path in application.yaml points to ./lib/vec0
            // This installer writes to {dataDir}/lib/vec0 which aligns with the default config.
        } catch (IOException e) {
            log.warnf("Vec0 auto-install failed: %s. RAG will degrade to FTS5-only.", e.getMessage());
        }
    }

    /** Returns the OS+arch key used in the resource filename. */
    static String osArchKey() {
        String os   = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();

        if (os.contains("linux"))   return arch.contains("aarch64") ? "linux-aarch64" : "linux-x86_64";
        if (os.contains("mac"))     return arch.contains("aarch64") ? "macos-aarch64" : "macos-x86_64";
        if (os.contains("windows")) return "windows-x86_64";
        return "unknown";
    }

    /** Returns the native library file extension for the current OS. */
    static String nativeExtension() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("windows")) return ".dll";
        if (os.contains("mac"))     return ".dylib";
        return ".so";
    }
}
```

---

### Component F3 — Update `.env.example` and Application YAML

**`.env.example`** — Change the vec0 section to reflect that manual setup is now optional:
```
# RAG — sqlite-vec Native Extension
# ArcClaw auto-installs the correct vec0 binary for your OS on first boot.
# Override this ONLY if you want to use a custom build or a different path.
# ARCCLAW_SQLITE_VEC_PATH=./lib/vec0
```

**`application.yaml`** — No change needed. The installer writes to `{dataDir}/lib/vec0` which the default config already points to.

---

### Component F4 — Tests

**New file:** `arcclaw-app/src/test/java/com/arcclaw/app/config/Vec0NativeLibraryInstallerTest.java`

```java
class Vec0NativeLibraryInstallerTest {

    @Test
    void osArchKeyReturnsKnownValueForLinux() {
        // Simulate Linux x86_64 — cannot rely on actual system properties in unit test
        // Use a test subclass or verify the logic directly
        assertThat(Vec0NativeLibraryInstaller.osArchKey()).isNotEqualTo("unknown");
    }

    @Test
    void nativeExtensionMatchesOs() {
        String ext = Vec0NativeLibraryInstaller.nativeExtension();
        assertThat(ext).isIn(".so", ".dll", ".dylib");
    }

    @Test
    void resourceExistsInJarForCurrentPlatform() {
        String resourceName = "/native/vec0/vec0-" +
            Vec0NativeLibraryInstaller.osArchKey() +
            Vec0NativeLibraryInstaller.nativeExtension();
        try (InputStream in = Vec0NativeLibraryInstallerTest.class.getResourceAsStream(resourceName)) {
            assertThat(in)
                .as("Vec0 binary resource [%s] must exist in the JAR", resourceName)
                .isNotNull();
        }
    }

    @Test
    void installerDoesNotOverwriteExistingBinary(@TempDir Path tempDir) throws IOException {
        // Create a sentinel file at the target path
        Path sentinelDir = tempDir.resolve("lib");
        Files.createDirectories(sentinelDir);
        Path sentinel = sentinelDir.resolve("vec0" + Vec0NativeLibraryInstaller.nativeExtension());
        Files.writeString(sentinel, "sentinel");

        Vec0NativeLibraryInstaller installer = new Vec0NativeLibraryInstaller();
        installer.dataDir = tempDir.toString();
        installer.configuredVecPath = sentinelDir.resolve("vec0").toString();
        installer.onStart(null); // must not overwrite

        assertThat(Files.readString(sentinel)).isEqualTo("sentinel");
    }
}
```

**Run:** `mvn clean test -pl arcclaw-app -Dtest=Vec0NativeLibraryInstallerTest`

---

# PROMPT — PHASE 66-T9: channel-spi.md — Plugin Contract Documentation (Tier 3 — MEDIUM)

**Role:** Senior Technical Writer & Java Architect
**Task:** Phase 66-T9 — Write `docs/channel-spi.md` documenting the complete contract for adding a new channel to ArcClaw, using the `TelegramChannelPlugin` (from T5) as the reference implementation.

---

## Architecture Context and Other References
Refer `@Architecture_Invariants.md`
Refer `@arcclaw_rg_cheatsheet.md`

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements
1. Documentation only — no Java changes.
2. All interface method signatures in the doc must match the actual compiled interfaces exactly.
3. The "Discord example" skeleton must be compilable if copy-pasted into the codebase.

---

### Component G1 — Create `docs/channel-spi.md`

**New file:** `docs/channel-spi.md`

The document must cover exactly these sections:

**1. Overview** — One paragraph explaining that a new channel requires zero core changes. Adding Discord/Slack/WhatsApp is a matter of creating a single Maven module with 6 CDI beans.

**2. The 6-Interface Contract** — Table with columns: Interface | Module | Responsibility | Required?

| Interface | Module | Responsibility | Required |
|---|---|---|---|
| `ChannelPlugin` | `arcclaw-channel-api` (or `arcclaw-app`) | Composite registration bean — wires all 5 below | Yes |
| `ChannelSender` | `arcclaw-channel-api` | Sends `List<ReplyNode>` to the channel | Yes |
| `ReplyRenderer` | `arcclaw-channel-api` | Converts `ReplyNode` tree to channel-native format | Yes |
| `ChannelAllowlist` | `arcclaw-channel-api` | Determines if a `ChannelUser` is authorized | Yes |
| `ChannelJobNotifier` | `arcclaw-app/channel` | Sends cron and approval timeout notifications | Yes |
| `ChannelDaemon` | `arcclaw-core/pipeline` | Daemon lifecycle: `isConfigured()`, `start()` | Yes |

**3. Step-by-Step: Adding Discord**
- Step 1: Create `arcclaw-channel-discord` module with `pom.xml` depending on `arcclaw-channel-api` and `arcclaw-core`
- Step 2: Implement `DiscordChannelSender implements ChannelSender`
- Step 3: Implement `DiscordRenderer implements ReplyRenderer` (renders `ReplyNode.Heading` as Discord `**bold**`, `ReplyNode.CodeBlock` as ` ```lang ` fences, etc.)
- Step 4: Implement `DiscordAllowlist implements ChannelAllowlist` (reads `ARCCLAW_DISCORD_ALLOWED_GUILDS` config)
- Step 5: Implement `DiscordJobNotifier implements ChannelJobNotifier`
- Step 6: Implement `DiscordDaemon implements ChannelDaemon` (JDA bot lifecycle)
- Step 7: Implement `DiscordChannelPlugin implements ChannelPlugin` — wires all 6

**4. Key Config Pattern** — How to add `arcclaw.channel.discord.token` and gate `isConfigured()` on it.

**5. Reference: Telegram Implementation** — File tree of all Telegram-related files showing exactly how the reference implementation is structured.

**6. Testing Your Plugin** — Minimum test checklist referencing `ChannelPluginRegistryTest` as a template.

---

### Component G2 — Verification

The documentation file must pass this check:
```bash
# Verify all 6 interface names exist in the codebase exactly as documented
grep -r "interface ChannelPlugin\|interface ChannelSender\|interface ReplyRenderer\|interface ChannelAllowlist\|interface ChannelJobNotifier\|interface ChannelDaemon" --include="*.java" . | wc -l
# Expected: 6
```

---

## P66 Execution Order Summary

```
T4 + T7  (1 day)   — Delete ApprovalTimeoutNotifier + GovernanceStrategy interface
T2       (1 day)   — AgentRoleEnum → AgentRole record + AgentRoleRegistry
T3       (0.5 day) — LlmProviderFactory SPI
T5       (1 day)   — ChannelPlugin + ChannelPluginRegistry + TelegramChannelPlugin
T6       (0.5 day) — TelegramMarkdownV2Renderer double-escape fix
T8       (1 day)   — sqlite-vec auto-packaging
T9       (2 hours) — channel-spi.md

Full suite verification: mvn clean test
```

After P66, the extensibility scores are:
- **Agent personas:** 10/10 (any AgentRole registerable via CDI)
- **LLM providers:** 10/10 (any provider via LlmProviderFactory SPI)
- **Channel plugins:** 9/10 (composite SPI defined; module extraction in P67)
- **Embeddings:** 10/10 (already solved in P65)
- **Notifications:** 10/10 (already solved in P65)
- **Governance:** 8/10 (interface extracted; pluggable strategies unlocked)
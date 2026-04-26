# ArcClaw Extension Guide

ArcClaw is designed as a modular kernel. Every major subsystem exposes a clean CDI interface that you can implement once and have it discovered automatically via Quarkus Arc - no wiring code, no registry modifications, no restart required beyond a standard redeploy.

> **Before extending ArcClaw**, read [`Architecture_Invariants.md`](./Architecture_Invariants.md). The invariants there are not suggestions - violations can cause silent misbehaviour, security regressions, or broken native image builds.

---

## Table of Contents

1. [Create a New Tool](#1-create-a-new-tool)
2. [Define a New Agent Persona](#2-define-a-new-agent-persona)
3. [Register a New Reflex](#3-register-a-new-reflex)
4. [Add a New Channel Adapter](#4-add-a-new-channel-adapter)
5. [Add a Custom Skill](#5-add-a-custom-skill)
6. [Add a New LLM Provider](#6-add-a-new-llm-provider)
7. [Add an ObservationTransformer](#7-add-an-observationtransformer)
8. [Add a ToolGuardInterceptor](#8-add-a-toolguardinterceptor)

---

## 1. Create a New Tool

Implement the `AgentTool` interface and mark it `@ApplicationScoped`. ArcClaw handles LangChain4J spec mapping, governance enforcement, deduplication, sandbox routing, and secret injection automatically.

### Simple in-process tool (no sandbox, no secrets)

```java
@ApplicationScoped
public class MyTool implements AgentTool {

    @Override public String name()        { return "mytool"; }
    @Override public String description() { return "Does something useful."; }
    @Override public ToolOperation requiredOperation() { return ToolOperation.READ_FS; }
    @Override public boolean requiresSandbox()         { return false; }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        String input = (String) params.get("input");
        return ToolResponse.success("Result: " + input);
    }
}
```

### Sandboxed tool with secret injection (Podman + tmpfs)

For tools that must make network calls or run untrusted binaries, enable sandbox isolation and declare the secrets your command needs. The `ToolRegistry` resolves them from `SecretVault` and injects them via a RAM-backed tmpfs env-file - they never appear in process arguments.

```java
@ApplicationScoped
public class MyNetworkTool implements AgentTool {

    @Override public String name()        { return "mynetworktool"; }
    @Override public String description() { return "Fetches data from an external API."; }
    @Override public ToolOperation requiredOperation() { return ToolOperation.NETWORK_OUTBOUND; }
    @Override public boolean requiresSandbox()         { return true; }

    @Override
    @RequiresSecret(envVar = "MY_API_KEY", vaultKey = "MY_SERVICE_TOKEN")
    public List<String> sandboxCommand(Map<String, Object> params) {
        return List.of("my-cli", "fetch", "--target", (String) params.get("url"));
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        // Only called in %dev strategy=NONE / direct-exec mode
        throw new UnsupportedOperationException("Sandbox required in production.");
    }
}
```

### Wiring tool permissions to a role

Tool schema is filtered per role via `RoleDefinition.capabilities()`. Add the corresponding `ToolCapability` entry to the role's definition in `AgentRoleEnum`:

```java
// In AgentRoleEnum - add to the role's RoleDefinition
COORDINATOR(new RoleDefinition(
    Set.of(
        ToolCapability.GITHUB,
        ToolCapability.SCHEDULE_TASK,
        ToolCapability.MANAGE_SCHEDULE,
        ToolCapability.MEMORY_SEARCH,
        ToolCapability.DELEGATE_TO_AGENT,
        ToolCapability.MY_TOOL   // ← add your new ToolCapability entry
    ),
    maxIterations,
    canUseTools
))
```

If your tool does not map to an existing `ToolCapability` value, add a new entry to the `ToolCapability` enum with `toolName()` returning the string that matches `AgentTool.name()`.

> [!NOTE]
> **Storing secrets at runtime**: Users can store secrets interactively via the Telegram `store <key>=<value>` command or via the vault REST API. They are encrypted with AES-256-GCM-SIV and persisted in `arcclaw-meta.db`. Your `@RequiresSecret(vaultKey="MY_SERVICE_TOKEN")` declaration resolves against this vault automatically.

> [!IMPORTANT]
> **Tool context binding**: Tools run in the `WorkspaceContext` execution path (`AgentDispatchHandler → AgentLoop → ToolRegistry → tool.execute()`). Tools **must** use `WorkspaceContext` accessors for session/user/chat data. They **must not** read from `WorkspaceScopeHolder`. Violating this causes context-not-active errors on cron and async paths. See Architecture Invariant §2.3.

---

## 2. Define a New Agent Persona

### Step 1 - Create the Agent class

Implement `Agent`, annotate with `@AgentRole("your_role")` and `@ApplicationScoped`. The persona is registered automatically by `AgentPersonaRegistry`.

```java
@AgentRole("analyst")
@ApplicationScoped
public class AnalystAgent implements Agent {

    @Override
    public String role() { return "analyst"; }

    // The system prompt is now driven by Qute templates (see Step 3 below)
    // This method provides a fallback only
    @Override
    public String systemPrompt() {
        return "You are a data analyst. Interpret structured data and produce clear summaries.";
    }
}
```

### Step 2 - Add the role to `AgentRoleEnum`

Define the role with its `RoleDefinition`, specifying `maxIterations`, `canUseTools`, and the `Set<ToolCapability>` the role is permitted to use:

```java
// In AgentRoleEnum
ANALYST("analyst", new RoleDefinition(
    Set.of(ToolCapability.MEMORY_SEARCH),  // tools this role may use
    5,                                       // maxIterations
    true                                     // canUseTools
))
```

Add a case to every exhaustive switch over `AgentRoleEnum` in the codebase - the compiler will flag missing cases.

### Step 3 - Add a Qute system prompt template

Create `arcclaw-core/src/main/resources/templates/prompts/role-analyst.txt`:

```
PERMITTED TOOLS: {#if sortedCapabilities.isEmpty()}None{#else}{#each sortedCapabilities}{it.toolName}{#if it_hasNext}, {/if}{/each}{/if}
You are a data analyst. Your only job is to interpret structured data
and produce clear summaries. You never write code or make network calls.

MAXIMUM REASONING ITERATIONS: {roleDef.maxIterations}

{#include prompts/safety-rules /}
{#include prompts/compaction-summary /}
```

Add the template injection to `PromptTemplateEngine`:

```java
// In PromptTemplateEngine
@Inject
@Location("prompts/role-analyst")
Template analystTemplate;

// Add a case to the exhaustive switch in buildSystemPrompt()
case ANALYST -> analystTemplate.data(...)...render();
```

### Step 4 - Register the role in `application.yaml`

```yaml
arcclaw:
  agents:
    roles:
      - name: analyst
        class: com.yourpackage.AnalystAgent
        description: "Data analysis persona"
```

### Step 5 - Register for native image reflection (if applicable)

If the new agent class uses any reflection-sensitive patterns, add it to `AppReflectionConfig` in `arcclaw-app`.

---

## 3. Register a New Reflex

Implement `AgentReflex`, mark it `@ApplicationScoped`, and return `true` from `matches()` for your trigger pattern. The `ReflexRegistry` discovers it automatically via CDI - no manual registration required. It is added to the CDI-discovered pass (Pass 2) automatically.

```java
@ApplicationScoped
public class PingReflex implements AgentReflex {

    @Override
    public boolean matches(String input) {
        return "/ping".equalsIgnoreCase(input.strip());
    }

    @Override
    public ReflexResult handle(MessageContext ctx) {
        return ReflexResult.reply("🏓 Pong! ArcClaw is alive.");
    }
}
```

### Sanitizer reflexes (run on every message)

If your reflex needs to inspect or transform every inbound message **before** the LLM sees it (like `PiiScanReflex`), override the `sanitize()` method:

```java
@ApplicationScoped
public class CustomSanitizerReflex implements AgentReflex {

    @Override
    public boolean matches(String input) {
        return false; // never short-circuits - sanitize() runs regardless
    }

    @Override
    public ReflexResult handle(MessageContext ctx) {
        return ReflexResult.noMatch(); // never reached
    }

    @Override
    public String sanitize(String input) {
        // Transform input before LLM sees it
        return input.replaceAll("sensitive-pattern", "[REDACTED]");
    }
}
```

> [!IMPORTANT]
> Keep `matches()` **cheap** - it is called on the hot path for every inbound message before any LLM call. Avoid I/O, database queries, or heavy computation here.
>
> The `ReflexRegistry` has a two-pass chain (Architecture Invariant §4.6): Pass 1 handles built-in command prefixes (`/approve`, `/deny`, `/halt`, etc.); Pass 2 discovers CDI `AgentReflex` beans. New reflexes always go to Pass 2.

---

## 4. Add a New Channel Adapter

Adding a new channel (e.g., Discord, Slack, SMS, WhatsApp) requires implementing the **6-interface Channel SPI contract** and registering it as `@ApplicationScoped` CDI beans. The entire reflex system, skill routing, intent classification, session routing, and agent execution are channel-agnostic - your new adapter gets all of these for free.

See [`CHANNEL_SPI.md`](./CHANNEL_SPI.md) for the complete step-by-step guide with a Discord example, including:

- All 6 required interface implementations (`ChannelPlugin`, `ChannelSender`, `ReplyRenderer`, `ChannelAllowlist`, `ChannelJobNotifier`, `ChannelDaemon`)
- How to bridge inbound messages into `MessagePipeline`
- How to gate the channel via configuration so a missing token doesn't crash startup
- The Telegram implementation as a reference (`arcclaw-channel-telegram/`)

> [!NOTE]
> `ChannelType` is a record - switched on `channelType.id()` (a string), not on enum ordinals. New channel types require a new `ChannelType` record instance (e.g., `ChannelType.DISCORD`). Do not add one-off channel branches into core flow when an SPI extension point exists (Architecture Invariant §4.5).

---

## 5. Add a Custom Skill

Skills require **no Java code**. Create a Markdown document with YAML frontmatter and post it via the REST API or place it on the classpath.

### Skill document format

```markdown
---
name: my-skill
description: What this skill does
trigger-patterns:
  - "my regex trigger"
  - "alternate.*pattern"
tool-capabilities:
  - GITHUB
  - MEMORYSEARCH
max-iterations: 7
---
You are a specialist persona. Describe the skill's behaviour in detail here.
This section becomes the system prompt injected into the agent for matching messages.
```

### POST via REST API (no restart required)

```bash
curl -X POST http://localhost:8080/api/v1/skills \
  -H "X-ArcClaw-API-Key: $ARCCLAW_API_KEYS_INTERNAL" \
  -H "Content-Type: text/plain" \
  --data-binary @my-skill.md
```

### Built-in classpath skill (compiled into binary)

1. Place the `.md` file in `arcclaw-core/src/main/resources/skills/`
2. Add its filename (without path) to `arcclaw-core/src/main/resources/skills/index.txt`
3. Rebuild

### Skill frontmatter constraints

| Field | Constraint |
|---|---|
| `name` | Required. Must be unique - database skills override classpath skills on name conflict. |
| `trigger-patterns` | Java regexes, matched case-insensitively. Empty list = skill never auto-routes (must be invoked explicitly). |
| `tool-capabilities` | Must be valid `ToolCapability` enum names. Invalid entries are silently ignored. |
| `max-iterations` | Positive integer. Default 5. |
| `maxBudgetUsd` | **Forbidden** - budget governance is a runtime session concern, not a per-skill config. See Architecture Invariant §4.7. |

> [!IMPORTANT]
> Dynamic skills are rendered via the pre-compiled Qute wrapper `SkillSystemPrompt.html`. Do not attempt to build skill prompts via ad hoc string concatenation - this bypasses injection-safety sanitization and native-image Qute compilation checks (Architecture Invariant §4.8).

---

## 6. Add a New LLM Provider

ArcClaw has **two** LLM provider SPIs - one for agent reasoning, one for intent classification and compaction. Both must be implemented if you want to use a new provider end-to-end.

### 6a. Agent reasoning provider (`AgentChatModelProvider`)

Located in `arcclaw-core`. Implement `AgentChatModelProvider` and mark it `@ApplicationScoped`:

```java
@ApplicationScoped
public class MyProviderFactory implements AgentChatModelProvider {

    @ConfigProperty(name = "arcclaw.myprovider.base-url",
                    defaultValue = "https://api.myprovider.com/v1")
    String baseUrl;

    @ConfigProperty(name = "arcclaw.myprovider.api-key",
                    defaultValue = "not-configured")
    String apiKey;

    @ConfigProperty(name = "arcclaw.myprovider.model",
                    defaultValue = "my-model-name")
    String modelName;

    @Override
    public String providerName() { return "myprovider"; }

    @Override
    public ChatModel create() {
        if ("not-configured".equals(apiKey)) {
            throw new IllegalStateException(
                "MyProvider requires arcclaw.myprovider.api-key to be set.");
        }
        return OpenAiChatModel.builder()   // OpenAI-compatible, or use a native client
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .logRequests(false)
                .logResponses(false)
                .build();
    }
}
```

The `LangChainReasoningEngine` discovers the active provider by matching `providerName()` against the `arcclaw.llm.provider` config property.

### 6b. Routing/intent-classification provider (`RoutingChatModelProvider`)

Located in `arcclaw-app`. Implement `RoutingChatModelProvider` and mark it `@ApplicationScoped`:

```java
@ApplicationScoped
public class MyRoutingProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() { return "myprovider"; }  // matches ARCCLAW_LLM_PROVIDER

    @Override
    public boolean requiresApiKey() { return true; }

    @Override
    public ChatModel create(LlmModelConfig config) {
        return OpenAiChatModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey().orElseThrow(() ->
                    new IllegalStateException("myprovider requires apiKey")))
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

### 6c. Wire the provider

Set in your `.env`:

```properties
ARCCLAW_LLM_PROVIDER=myprovider
ARCCLAW_LLM_API_KEY=your-key
ARCCLAW_LLM_BASE_URL=https://api.myprovider.com/v1
ARCCLAW_LLM_MODEL_NAME=my-model-name
```

> [!NOTE]
> The model used for **agent reasoning** is configured via `arcclaw.llm.provider` / `ARCCLAW_LLM_PROVIDER`. The model used for **intent classification** is configured via `arcclaw.routing.intent-classifier.model` (defaults to `llama-3.1-8b-instant`). The model for **context compaction** is produced by `CompactionModelProducer` in `arcclaw-app`. All three can be configured independently.

---

## 7. Add an ObservationTransformer

Every tool response passes through an ordered chain of `ObservationTransformer` implementations before being appended to the agent's reasoning history. This SPI lets you add cross-cutting post-processing without modifying tool code.

The three built-in transformers are:

| Transformer | Priority | Behaviour |
|---|---|---|
| `InjectionRedactionTransformer` | 10 | Strips prompt-injection artifacts from tool output |
| `PiiRedactionTransformer` | 20 | Redacts PII patterns (emails, phones, SSNs, etc.) from observations |
| `TruncationTransformer` | 30 | Truncates observations that exceed the configured max length |

### Implementing a custom transformer

```java
@ApplicationScoped
public class MyObservationTransformer implements ObservationTransformer {

    @Override
    public int priority() {
        return 25; // runs between PiiRedactionTransformer (20) and TruncationTransformer (30)
    }

    @Override
    public String transform(String toolName, String rawObservation) {
        if (rawObservation == null) return "";
        // Example: mask any API keys that leaked into tool output
        return rawObservation.replaceAll("(?i)(api[_-]?key\\s*[:=]\\s*)\\S+", "$1[MASKED]");
    }
}
```

`ToolObservationSanitizer` discovers all `ObservationTransformer` beans via CDI `Instance<ObservationTransformer>`, sorts by `priority()` ascending, and runs them in order. Lower priority number = runs earlier.

> [!IMPORTANT]
> `ObservationTransformer` implementations **must never throw**. The sanitizer chain does not wrap individual transformers - an unchecked exception will propagate out of `ToolInvoker` and terminate the agent loop iteration. Handle all exceptions internally and return a fallback value.

---

## 8. Add a ToolGuardInterceptor

`ToolInvocationGuard` runs before every tool invocation and decides whether to allow, block, deduplicate, or pause for approval. You can plug in custom governance rules via the `ToolGuardInterceptor` SPI - for example, to enforce domain-specific constraints, rate limits per tool, or custom block patterns.

### Implementing a custom interceptor

```java
@ApplicationScoped
public class MyToolGuardInterceptor implements ToolGuardInterceptor {

    /**
     * Return the tool names this interceptor handles.
     * Return an empty set to intercept ALL tools.
     */
    @Override
    public Set<String> handledToolNames() {
        return Set.of("github", "my_sensitive_tool");
    }

    /**
     * Evaluate the tool call. Return Optional.empty() to pass through to
     * the next interceptor or built-in guard logic.
     * Return a present Optional to short-circuit with that decision.
     */
    @Override
    public Optional<GuardDecision> evaluate(
            String sessionId,
            String agentRole,
            String toolName,
            Map<String, Object> params,
            List<ReasoningStep> history,
            String goal,
            List<CompiledSkillEntry> skillSnapshot) {

        // Example: block github if the target org is on a deny list
        if ("github".equals(toolName)) {
            String target = (String) params.getOrDefault("target", "");
            if (target.startsWith("forbidden-org/")) {
                return Optional.of(new GuardDecision.Blocked(
                    "Access to forbidden-org repositories is not permitted."));
            }
        }

        return Optional.empty(); // pass through
    }
}
```

### Guard decision types

| Decision | Effect |
|---|---|
| `GuardDecision.Allow` | Tool proceeds to execution immediately |
| `GuardDecision.Blocked(String observation)` | Tool is blocked; `observation` is returned to the LLM as the tool result |
| `GuardDecision.Deduplicate(String cachedObservation)` | Returns a cached result without re-executing; adds a `[NOTE: retrieved from session cache]` suffix |
| `GuardDecision.PauseForApproval(String toolName, String paramsJson)` | Saves an `AWAITING_APPROVAL` checkpoint and returns `202` to the user |

Interceptors registered via CDI are evaluated in the **first pass** of `ToolInvocationGuard.evaluate()`, before the built-in deduplication check, dynamic tool fallback, and HITL check. If any interceptor returns a present `Optional`, the guard short-circuits immediately without evaluating further interceptors or built-in logic.

> [!NOTE]
> The built-in deduplication check (which prevents the LLM from calling the same tool with identical parameters twice in the same session) runs **after** registered interceptors. If you want to override or extend deduplication logic, use a `ToolGuardInterceptor` that inspects `history` and returns `GuardDecision.Deduplicate` with your chosen cached observation.

---

## Cross-Cutting Notes

### Where business logic lives

Code location matters for module boundary correctness:

| Concern | Correct module |
|---|---|
| New `AgentTool`, `AgentReflex`, governance rule | `arcclaw-core` |
| New `RoutingChatModelProvider` | `arcclaw-app` |
| New channel implementation | New module or `arcclaw-channel-telegram` as reference |
| REST endpoint or startup bootstrap | `arcclaw-app` |
| Build-time CDI/MCP/RAG wiring | `arcclaw-deployment` |

### Reflection registration for native image

If your new class is used reflectively at runtime (e.g., Jackson deserialization, Qute template data), register it with `@RegisterForReflection` or add it to the relevant `*ReflectionConfig.java` in the appropriate module. JVM success does **not** prove native image safety (Architecture Invariant §6.1).

### Testing conventions

- Tool tests **must** bind with `WorkspaceContext.run(sessionId, userId, chatId, () -> { ... })`.
- Reflex tests **must** bind with `WorkspaceScopeHolder.call(sessionId, () -> { ... })`.
- Never mix the two binding layers in the same test (Architecture Invariant §2.6).

---

*Last updated: ArcClaw V1.0.0 · Quarkus 3.27.2 · JDK 25*

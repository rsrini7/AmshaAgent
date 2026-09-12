# Amsha Agent Extension Guide

Amsha Agent is designed as a modular kernel. Every major subsystem exposes a clean CDI interface that you can implement once and have it discovered automatically via Quarkus Arc - no wiring code, no registry modifications, no restart required beyond a standard redeploy.

For a visual representation of how components connect, refer to the [Module Architecture Diagram](assets/amsha_module_architecture.svg), [Message Pipeline Diagram](assets/amsha_message_pipeline.svg), and the [Agent Loop Diagram](assets/amsha_agent_loop.svg).

> **Before extending Amsha Agent**, read [`Architecture_Invariants.md`](./Architecture_Invariants.md). The invariants there are not suggestions - violations can cause silent misbehaviour, security regressions, or broken native image builds.

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
9. [Add a MessageHandler (Pipeline Interceptor)](#9-add-a-messagehandler-pipeline-interceptor)
10. [Add a ContextEnricher (System Prompt Injection)](#10-add-a-contextenricher-system-prompt-injection)
11. [Add an IntentRoutingProvider (Fast-Path Routing)](#11-add-an-intentroutingprovider-fast-path-intent-routing)
12. [Swap the GovernanceStrategy (Budget/Billing)](#12-swap-the-governancestrategy-budgetbilling)
13. [Add a SkillProvider (Custom Skill Sources)](#13-add-a-skillprovider-custom-skill-sources)
14. [Add an EmbeddingProviderFactory (Embedding Models)](#14-add-an-embeddingproviderfactory-embedding-models)
15. [Implement Persistence Ports (Storage Backend)](#15-implement-persistence-ports-storage-backend)

---

## 1. Create a New Tool

Implement the `AgentTool` interface and mark it `@ApplicationScoped`. Amsha Agent handles LangChain4J spec mapping, governance enforcement, deduplication, sandbox routing, and secret injection automatically.

### Simple in-process tool (no sandbox, no secrets)

```java
@ApplicationScoped
@ToolMetadata(
    capabilityId = "mytool",
    description = "Does something useful",
    defaultRoles = {"coordinator", "researcher"}
)
public class MyTool implements AgentTool {

    @Override public String name()        { return "mytool"; }
    @Override public String description() { return "Does something useful."; }
    @Override public ToolOperation requiredOperation() { return ToolOperation.READ_FS; }
    @Override public boolean requiresSandbox()         { return false; }

    @Override
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        String input = (String) params.get("input");
        return ToolResponse.success("Result: " + input);
    }
}
```

The `@ToolMetadata` annotation registers the tool's capability and grants it to specified roles automatically. The `ToolRegistry` discovers it via CDI and assembles role-specific tool schemas at startup.

### Sandboxed tool with secret injection (Podman + tmpfs)

For tools that must make network calls or run untrusted binaries, enable sandbox isolation and declare the secrets your command needs. The `ToolRegistry` resolves them from `SecretVault` and injects them via a RAM-backed tmpfs env-file - they never appear in process arguments.

```java
@ApplicationScoped
@ToolMetadata(
    capabilityId = "mynetworktool",
    description = "Fetches data from an external API",
    defaultRoles = {"researcher", "synthesizer"}
)
public class MyNetworkTool implements AgentTool {

    @Override public String name()        { return "mynetworktool"; }
    @Override public String description() { return "Fetches data from an external API."; }
    @Override public ToolOperation requiredOperation() { return ToolOperation.NETWORK_OUTBOUND; }
    @Override public boolean requiresSandbox()         { return true; }

    @Override
    @RequiresSecret(envVar = "MY_API_KEY", vaultKey = "MY_SERVICE_TOKEN")
    public List<String> sandboxCommand(ToolExecutionContext ctx, Map<String, Object> params) {
        return List.of("my-cli", "fetch", "--target", (String) params.get("url"));
    }

    @Override
    public ToolResponse execute(ToolExecutionContext ctx, Map<String, Object> params) {
        // Host-side fallback when sandbox is unavailable (allow-insecure-fallback=true)
        // Implement with ProcessBuilder for graceful degradation
        throw new UnsupportedOperationException("Sandbox required in production.");
    }
}
```

### Wiring tool permissions to a role

Tool-to-role assignment is handled by the `@ToolMetadata` annotation on your tool class. The `defaultRoles` array lists which roles automatically get this tool in their schema:

```java
@ToolMetadata(
    capabilityId = "mytool",           // must match AgentTool.name()
    description = "What this tool does",
    defaultRoles = {"coordinator", "researcher"}  // auto-granted to these roles
)
```

If your tool needs a new `ToolCapability` enum entry (for Qute template access or explicit schema filtering), add it to the enum:

```java
// In ToolCapability.java
MY_TOOL("mytool", "Does something useful"),
```

The `toolName` string must match `AgentTool.name()` exactly. The `ToolRegistry` resolves capabilities via `ToolCapability.fromToolName()` at startup.

> [!NOTE]
> **Storing secrets at runtime**: Users can store secrets interactively via the Telegram `store <key>=<value>` command or via the vault REST API. They are encrypted with AES-256-GCM-SIV and persisted in `amsha-meta.db`. Your `@RequiresSecret(vaultKey="MY_SERVICE_TOKEN")` declaration resolves against this vault automatically.

> [!IMPORTANT]
> **Tool context binding**: Tools run in the `WorkspaceContext` execution path (`AgentDispatchHandler → AgentLoop → ToolRegistry → tool.execute()`). Tools **must** use `WorkspaceContext` accessors for session/user/chat data. They **must not** read from `WorkspaceScopeHolder`. Violating this causes context-not-active errors on cron and async paths. See Architecture Invariant §2.3.

### AgentTool lifecycle methods

The `AgentTool` interface provides several optional lifecycle and configuration hooks (all have sensible defaults):

| Method | Default | Purpose |
|---|---|---|
| `onInit()` | no-op | Called when the tool is registered by `ToolRegistry`. Use for one-time setup (e.g., opening connections). |
| `onDestroy()` | no-op | Called on application shutdown. Use for cleanup (e.g., closing connections). |
| `healthCheck()` | returns success | Override to report tool health (e.g., `SandboxExecTool` checks Podman availability). Used by health check endpoints. |
| `prepareWorkspace(Path, Map)` | no-op | Called on the **host** before the sandbox container starts. Override to write files the sandbox command needs (e.g., `SandboxExecTool` compiles Java sources). |
| `shouldStripNullParams()` | `true` | Whether `ToolRegistry` strips explicit `null` values from LLM-provided parameters. Set to `false` if the target API differentiates between omitted and `null` fields. |
| `idempotencyMode()` | `NON_IDEMPOTENT` | Controls deduplication behavior in `ToolInvocationGuard`. Set to `IDEMPOTENT` for tools that produce the same result on repeated calls (e.g., read-only search tools). Per Architecture Invariant §7.38. |

---

## 2. Define a New Agent Persona

> **For the complete 14-step checklist with all required touchpoints, see [`AGENTS.md` Section 7](../AGENTS.md#7-adding-a-new-persona-agent-role).**

### Step 1 - Add enum entries

In `AgentPersona.java`, add the new deity enum entry:

```java
ANALYST("analyst", "Lakshmi (The Analyst)", Set.of("lakshmi")),
```

In `AgentRoleEnum.java`, add the static instance and `RoleDefinition`:

```java
public static final String ANALYST_ID = "analyst";
public static final AgentRoleEnum ANALYST = new AgentRoleEnum(ANALYST_ID);

// In DEFINITIONS map (use Map.ofEntries() if >10 entries)
Map.entry(AgentPersona.ANALYST.roleId(), new RoleDefinition(
    AgentPersona.ANALYST.roleId(),
    AgentPersona.ANALYST.displayName(),
    "Interprets structured data and produces clear summaries.",
    5,       // maxIterations
    true,    // requiresApproval — false = trusted role (bypasses non-sensitive op checks)
    false,   // codeCapable
    Set.of(), // capabilities - assembled dynamically via @ToolMetadata
    AgentPersona.ANALYST.aliases()
))
```

### Step 2 - Create Qute templates

Create **two** template files:

1. **Persona identity template** - `templates/personas/analyst.txt`:
```
You are Lakshmi, the Analyst of the Amsha Agent team.

YOUR RESPONSIBILITIES:
- Interpret structured data and produce clear summaries.
- ...

AVAILABLE TOOLS: {tools}
```

2. **Role prompt template** - `templates/prompts/role-analyst.txt`:
```
PERMITTED TOOLS: {#each sortedCapabilities}{it.toolName}{#if it_hasNext}, {/if}{/each}

ANALYST-SPECIFIC RULES:
- Interpret structured data and produce clear summaries.
- ...

MAXIMUM REASONING ITERATIONS: {roleDef.maxIterations}

{#include prompts/github-tool-rules /}
{#include prompts/delegate-to-agent-rules /}
{#include prompts/safety-rules /}
{#include prompts/compaction-summary /}
```

### Step 3 - Add CDI wiring

In `AgentProducer.java`, add template injection and producer method:

```java
@Inject
@Location("personas/analyst.txt")
Template analystTemplate;

@Produces
@ApplicationScoped
@AgentRole(AgentRoleEnum.ANALYST_ID)
public Agent analyst() {
    return new DefaultAgent(AgentPersona.ANALYST, () -> analystTemplate,
        defaultTimezone, structuredOutputEngine, objectMapper, toolRegistry);
}
```

In `PromptTemplateEngine.java`, add the role prompt template injection and switch case:

```java
@Inject
@Location("prompts/role-analyst")
Template analystTemplate;

// In templateFor() switch:
case "analyst" -> analystTemplate;
```

### Step 4 - Wire into the system

1. **`CoordinatorPromptSection.java`** - Add to Trimurti/Tridevi block
2. **`PersonaEscalationService.java`** - Add escalation paths in `determineNewPersonaEscalation()`
3. **`PromptInstructionLibrary.java`** - Add to system capabilities
4. **`DelegateToAgentTool.java`** - Add to `defaultRoles` in `@ToolMetadata`
5. **`delegate-to-agent-rules.txt`** - Add to "Available Specialists" list
6. **`application.yaml`** - Add role entry under `amsha.agents.roles`
7. **`AgentGroupRegistry.java`** - Add to appropriate group (Loka) if applicable
8. **Persona prompt** - Add `<GROUP_NAME> FAMILY AWARENESS` section with escalation rules

### Step 5 - Update documentation

1. **`AGENTS.md`** - Ensure Section 7 covers the new persona
2. **`telegram-test-amsha.txt`** - Add test cases
3. **`04_agent_roles.http`** - Add HTTP test cases

### Step 6 - Verify

```bash
python3 ./scripts/fix_fqns.py
./mvnw compile -DskipTests -q
./mvnw test -pl amsha-core -Dtest="AgentRoleTest,PersonaEscalationServiceTest"
```

### Step 7 - Register for native image reflection (if applicable)

If the new agent class uses any reflection-sensitive patterns, add it to `AppReflectionConfig` in `amsha-app`.

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
        return ReflexResult.reply("🏓 Pong! Amsha Agent is alive.");
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
- The Telegram implementation as a reference (`channel-telegram/`)

> [!NOTE]
> `ChannelType` uses the **Flyweight pattern** (GoF) — `ChannelType.of("telegram") == ChannelType.TELEGRAM` is guaranteed by reference equality. Built-in types are pre-registered as static constants. Third-party plugins register new types via `ChannelType.of(id)` on first use; the result is cached permanently.
>
> `ChannelMessage` and `ChannelCommand` are **non-sealed interfaces** — third-party modules can implement them without modifying `amsha-channel-api`. Attach channel-specific metadata to `ChannelMessage` using `ChannelMessageAttribute<T>` typed keys (extension object pattern). For compile-time safety of the built-in command set, `ChannelCommand.parse()` returns a `ChannelCommand.BuiltInChannelCommand` subtype that can be switched exhaustively.
>
> `ChannelJobNotifier.notifyApprovalRequired()` is now **abstract** — every channel adapter must implement it explicitly. Channels without inline button support should send text instructions (e.g., "To approve, run `/approve {sessionId}`").

### Reply rendering (ContentNode / InteractionNode)

The `ReplyNode` tree is split into two sealed sub-interfaces for channel rendering:

- **`ContentNode`** — pure content nodes (Text, Heading, CodeBlock, BulletList, NumberedList, KeyValue, Separator, ErrorMessage). Every channel must render these. `ContentNode` is `non-sealed` so third-party plugins can define custom content types.
- **`InteractionNode`** — interactive controls (ApprovalPrompt). Channels override `ReplyRenderer.renderInteraction()` for types they support; unsupported types fall back to `InteractionNode.fallbackText()` for graceful degradation.

All three built-in renderers (Telegram, Web, CLI) implement this split:

```java
@Override
public String renderContent(List<ContentNode> nodes) { /* exhaustive or default guard */ }

@Override
public String renderInteraction(InteractionNode node) {
    if (node instanceof ApprovalPrompt ap) {
        return "⏸ Approval required for: " + ap.toolName();
    }
    return node.fallbackText(); // graceful degradation
}
```

### Processing concurrency (ProcessingLockManager)

`AbstractInboundMessagePipeline` delegates chat-level processing serialization to a `ProcessingLockManager` SPI. The default runtime implementation is `CaffeineProcessingLockManager` in `amsha-core` with a 30-second TTL. For clustered deployments, provide an `@ApplicationScoped` bean backed by Redis or database advisory locks:

```java
@ApplicationScoped
public class RedisLockManager implements ProcessingLockManager {
    private static final long TTL_MILLIS = 30_000L;
    @Override public boolean tryLock(String chatId) { /* Redis SET NX PX TTL_MILLIS */ }
    @Override public void unlock(String chatId)     { /* Redis DEL */ }
    @Override public boolean isLocked(String chatId) { /* Redis EXISTS */ }
}
```

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
  - RECALL_MEMORY_SEARCH
  - ARCHIVAL_MEMORY_SEARCH
max-iterations: 7
---
You are a specialist persona. Describe the skill's behaviour in detail here.
This section becomes the system prompt injected into the agent for matching messages.
```

### POST via REST API (no restart required)

```bash
curl -X POST http://localhost:8080/api/v1/skills \
  -H "X-Amsha-API-Key: $AMSHA_API_KEYS_INTERNAL" \
  -H "Content-Type: text/plain" \
  --data-binary @my-skill.md
```

### Built-in classpath skill (compiled into binary)

1. Place the `.md` file in `core/src/main/resources/skills/`
2. Add its filename (without path) to `core/src/main/resources/skills/index.txt`
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

Amsha Agent has **two** LLM provider SPIs defined in `amsha-llm-provider-api` - one for agent reasoning (`AgentChatModelProvider`), and one for routing/intent classification/compaction (`RoutingChatModelProvider`). Concrete implementations are housed in `amsha-llm-providers`. Both must be implemented if you want to use a new provider end-to-end.

### 6a. Agent reasoning provider (`AgentChatModelProvider`)

Located in `amsha-llm-providers` under `com.amsha.llm.providers.reasoning`. Implement `AgentChatModelProvider` and mark it `@ApplicationScoped @Unremovable`:

```java
package com.amsha.llm.providers.reasoning;

import dev.langchain4j.model.chat.ChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import io.quarkus.arc.Unremovable;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import com.amsha.llm.api.AgentChatModelProvider;

@ApplicationScoped
@Unremovable
public class MyProviderFactory implements AgentChatModelProvider {

    @ConfigProperty(name = "amsha.llm.myprovider.base-url",
                    defaultValue = "https://api.myprovider.com/v1")
    String baseUrl;

    @ConfigProperty(name = "amsha.llm.myprovider.api-key",
                    defaultValue = "not-configured")
    String apiKey;

    @ConfigProperty(name = "amsha.llm.myprovider.model",
                    defaultValue = "my-model-name")
    String modelName;

    @Override
    public String providerName() { return "myprovider"; }

    @Override
    public ChatModel create() {
        if ("not-configured".equals(apiKey)) {
            throw new IllegalStateException(
                "MyProvider requires amsha.llm.myprovider.api-key to be set.");
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

The `LangChainReasoningEngine` and `ChatModelProducer` discover active providers dynamically through CDI multi-bean resolution (`Instance<AgentChatModelProvider>`).

### 6b. Routing/intent-classification provider (`RoutingChatModelProvider`)

Located in `amsha-llm-providers` under `com.amsha.llm.providers.routing`. Implement `RoutingChatModelProvider` and mark it `@ApplicationScoped @Unremovable`:

```java
package com.amsha.llm.providers.routing;

import dev.langchain4j.model.chat.ChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import io.quarkus.arc.Unremovable;
import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.RoutingChatModelProvider;

@ApplicationScoped
@Unremovable
public class MyRoutingProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() { return "myprovider"; }  // matches AMSHA_LLM_PROVIDER

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

### 6c. Wire and Register the provider

1. Register new classes in `LlmProvidersReflectionConfig` in `amsha-llm-providers` for GraalVM native reflection.
2. If adding new adapter branches, register in `ChatModelFactory.java`.
3. Set in your `.env`:

```properties
AMSHA_LLM_PROVIDER=myprovider
AMSHA_LLM_API_KEY=your-key
AMSHA_LLM_BASE_URL=https://api.myprovider.com/v1
AMSHA_LLM_MODEL_NAME=my-model-name
```

> [!NOTE]
> The model used for **agent reasoning** is configured via `amsha.llm.provider` / `AMSHA_LLM_PROVIDER`. The model used for **intent classification** is produced by `InfiniteLoopIntentChatModelProvider` and selected from `amsha.infinite-loop.classifier.providers`. The model for **context compaction** is produced by `InfiniteLoopCompactionChatModelProvider` and selected from `amsha.infinite-loop.compaction.providers`. All three can be configured independently per-slot with infinite-loop failover.

**Built-in LLM providers:**

| Provider | Provider ID | SPI | Module |
|---|---|---|---|
| `GroqProviderFactory` | `groq` | `AgentChatModelProvider` | `amsha-llm-providers` |
| `OpenAiProviderFactory` | `openai` | `AgentChatModelProvider` | `amsha-llm-providers` |
| `OllamaProviderFactory` | `ollama` | `AgentChatModelProvider` | `amsha-llm-providers` |
| `OpenRouterProviderFactory` | `openrouter` | `AgentChatModelProvider` | `amsha-llm-providers` |
| `ClaudeCodeProviderFactory` | `claude` | `AgentChatModelProvider` | `amsha-llm-providers` |
| `OmlxProviderFactory` | `omlx` | `AgentChatModelProvider` | `amsha-llm-providers` |
| `GeminiProviderFactory` | `gemini` | `AgentChatModelProvider` | `amsha-llm-providers` |
| `NvidiaProviderFactory` | `nvidia` | `AgentChatModelProvider` | `amsha-llm-providers` |
| `CerebrasProviderFactory` | `cerebras` | `AgentChatModelProvider` | `amsha-llm-providers` |
| `SarvamProviderFactory` | `sarvam` | `AgentChatModelProvider` | `amsha-llm-providers` |
| `OpenCodeProviderFactory` | `opencode` | `AgentChatModelProvider` | `amsha-llm-providers` |
| `HuggingFaceProviderFactory` | `huggingface` | `AgentChatModelProvider` | `amsha-llm-providers` |
| `MistralProviderFactory` | `mistral` | `AgentChatModelProvider` | `amsha-llm-providers` |
| `CloudflareProviderFactory` | `cloudflare` | `AgentChatModelProvider` | `amsha-llm-providers` |
| `CohereProviderFactory` | `cohere` | `AgentChatModelProvider` | `amsha-llm-providers` |
| `CodexProviderFactory` | `codex` | `AgentChatModelProvider` | `amsha-llm-providers` |
| `AgyProviderFactory` | `agy` | `AgentChatModelProvider` | `amsha-llm-providers` |
| `KimchiProviderFactory` | `kimchi` | `AgentChatModelProvider` | `amsha-llm-providers` |
| `FoundryProviderFactory` | `foundry` | `AgentChatModelProvider` | `amsha-llm-providers` |
| `LlamaCppProviderFactory` | `llamacpp` | `AgentChatModelProvider` | `amsha-llm-providers` |
| `GenericOpenAiProviderFactory` | `generic-openai` | `AgentChatModelProvider` | `amsha-llm-providers` |
| `InfiniteLoopAgentChatModelProvider` | `infiniteloop` | `AgentChatModelProvider` | `amsha-core` |

Each provider also has a corresponding `RoutingChatModelProvider` implementation in `amsha-llm-providers` for intent classification and compaction.

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

**Sensitive operations requiring HITL:** `NETWORK_OUTBOUND` and `EXECUTE_SHELL` are
classified as sensitive operations that always require HITL approval, even for trusted
roles (roles with `requiresApproval=false` in their `RoleDefinition`). See Architecture
Invariant §4.10.2 for the role-based governance bypass rules.

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
     * Evaluate the tool call. Optional.empty() and GuardDecision.Allow
     * continue through the next interceptor and every built-in guard.
     * A stronger present decision short-circuits the pre-policy chain.
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
| `GuardDecision.Allow` | Continues through later interceptors and all mandatory built-in checks; it never authorizes immediate execution |
| `GuardDecision.Blocked(String observation)` | Tool is blocked; `observation` is returned to the LLM as the tool result |
| `GuardDecision.Deduplicate(String cachedObservation)` | Returns a cached result without re-executing and marks it as deduplicated |
| `GuardDecision.PauseForApproval(ApprovalFrame frame)` | Uses the existing checkpoint/approval path with the tool parameters and risk level |

Interceptors registered via CDI are evaluated in the **first pass** of
`ToolInvocationGuard.evaluate()`, before parameter validation, built-in deduplication,
dynamic-tool recognition, and HITL checks. `Optional.empty()` and
`GuardDecision.Allow` continue through the chain. Only `Blocked`, `Deduplicate`, or
`PauseForApproval` short-circuit. Consequently, an interceptor cannot use `Allow` to
bypass mandatory validation or authorization.

> [!NOTE]
> The built-in deduplication check (which prevents the LLM from calling the same tool
> with identical parameters twice in the same session) runs **after** registered
> interceptors. To add a stricter pre-policy deduplication rule, use a
> `ToolGuardInterceptor` that inspects `history` and returns
> `GuardDecision.Deduplicate` with the chosen cached observation.

Probabilistic risk scoring must not be added to this pre-policy SPI. The
`PostPolicyToolRiskInterceptor` runs only after the deterministic path provisionally
allows a tool and can return only `REQUIRE_APPROVAL`, `BLOCK`, or `NoOpinion`. The
ordered `AnswerQualityInterceptor` runs only after deterministic answer acceptance;
its retry opinion is honored once at most and never after a tool boundary. Implementing
either active SPI for ONNX requires the corresponding model evidence and activation gates from the ONNX ADR; `local-ml-onnx` currently implements neither.

---

## 9. Add a MessageHandler (Pipeline Interceptor)

The `MessagePipeline` processes every inbound message through an ordered chain of `MessageHandler` implementations. Adding a handler lets you intercept messages at a specific priority band — for custom security checks, content transformation, analytics, or dispatch logic.

```java
@ApplicationScoped
public class AuditTrailHandler implements MessageHandler {

    @Override
    public int priority() { return 25; } // runs between AllowlistHandler (10) and SessionResolutionHandler (40)

    @Override
    public HandleResult handle(MessageContext context) {
        log.infof("Message from %s: %s", context.user().platformId(), context.message());
        return HandleResult.CONTINUE; // pass to next handler
    }
}
```

**Priority ranges** (from `MessageHandler` Javadoc):

| Range | Purpose | Existing handlers |
|---|---|---|
| 10–20 | Security, allowlist, global reflexes | `AllowlistHandler` (10) |
| 30–40 | Session/context resolution | `SessionResolutionHandler` (40) |
| 45–50 | Command dispatch, intent classification | `CommandDispatchHandler` (45), `IntentClassificationHandler` (50) |
| 60–70 | Compaction, agent dispatch | `CompactionHandler` (60), `AgentDispatchHandler` (70) |

Return `HandleResult.STOP` to halt the pipeline immediately (e.g., blocked message). Return `HandleResult.CONTINUE` to pass to the next handler.

---

## 10. Add a ContextEnricher (System Prompt Injection)

`ContextEnricher` implementations inject additional context into the system prompt before the LLM sees it. The `SystemPromptBuilder` discovers all implementations via CDI and calls them in sequence.

```java
@ApplicationScoped
public class TelemetryContextEnricher implements ContextEnricher {

    @Override
    public String enrich(String sessionId, String userId, String goal, String basePrompt) {
        return basePrompt + "\n\nCURRENT METRICS: uptime=" + ManagementFactory.getRuntimeMXBean().getUptime() + "ms";
    }
}
```

**Built-in implementations:**

| Enricher | Module | What it injects |
|---|---|---|
| `SystemEnvironmentContextEnricher` | `amsha-core` | Host environment metadata |
| `RagContextEnricher` | `amsha-rag` | RAG-retrieved memory context (FTS5 + vector search results) |

Return `basePrompt` unchanged if no context is available. Never return `null`.

---

## 11. Add an IntentRoutingProvider (Fast-Path Intent Routing)

`IntentRoutingProvider` implementations add regex-based fast-path triggers that skip LLM intent classification entirely. If a message matches a provider's `fastPathTriggers()`, the role is assigned immediately without consuming classification tokens.

```java
@ApplicationScoped
public class SecurityRoutingProvider implements IntentRoutingProvider {

    private static final List<Pattern> TRIGGERS = List.of(
        Pattern.compile("scan.*vulnerabilit", Pattern.CASE_INSENSITIVE),
        Pattern.compile("security.*audit", Pattern.CASE_INSENSITIVE)
    );

    @Override
    public List<Pattern> fastPathTriggers() { return TRIGGERS; }

    @Override
    public Optional<String> fastPathRole() { return Optional.of("debugger"); }

    @Override
    public List<String> promptDirectives() {
        return List.of("If the message mentions security scanning, route to debugger.");
    }
}
```

**Built-in providers:**

| Provider | Routes to | Triggers |
|---|---|---|
| `CoderRoutingProvider` | `coder` | Code-writing patterns |
| `DelegationRoutingProvider` | `coordinator` | "delegate to..." patterns |
| `IngestRoutingProvider` | `coordinator` | "ingest this..." patterns |
| `SchedulingRoutingProvider` | `coordinator` | "schedule..." patterns |
| `ResearcherRoutingProvider` | `researcher` | Research/query patterns |
| `DefaultRoutingProvider` | `coordinator` | Fallback (always matches) |

Lower `priority()` values are evaluated first. Return `Optional.empty()` from `fastPathRole()` if the provider does not own a fast path.

---

## 12. Swap the GovernanceStrategy (Budget/Billing)

`GovernanceStrategy` controls per-session USD budget enforcement, token tracking, and autonomy downgrade. The default `GovernanceLayer` implementation can be swapped via CDI `@Alternative` for custom billing engines, per-tenant policies, or test doubles.

```java
@Alternative
@Priority(1) // overrides the default GovernanceLayer
@ApplicationScoped
public class TenantBudgetStrategy implements GovernanceStrategy {

    @Override
    public void recordLlmCall(String sessionId, int inputTokens, int outputTokens) {
        // Custom per-tenant budget logic
    }

    @Override
    public void assertWithinBudget(String sessionId) { /* ... */ }

    @Override
    public double getSessionCost(String sessionId) { /* ... */ }

    @Override
    public void clearSession(String sessionId) { /* ... */ }

    @Override
    public double getBudgetUsd() { /* ... */ }

    @Override
    public int getSessionInputTokens(String sessionId) { /* ... */ }

    @Override
    public int getSessionOutputTokens(String sessionId) { /* ... */ }
}
```

> [!NOTE]
> `GovernanceStrategy` throws `BudgetWarningException` (soft, 80% threshold) and `BudgetExceededException` (hard limit). The `recordLlmCall` method is called after every LLM invocation — keep it fast.

---

## 13. Add a SkillProvider (Custom Skill Sources)

`SkillProvider` loads skill definitions from arbitrary sources. The `SkillLoader` discovers all implementations via CDI, merges them by priority, and registers them in the `SkillRegistry`.

```java
@ApplicationScoped
public class S3SkillProvider implements SkillProvider {

    @Override
    public String sourceName() { return "s3"; }

    @Override
    public int priority() { return 50; } // between database (10) and classpath (100)

    @Override
    public List<SkillDefinition> load() {
        // Fetch skill YAML documents from S3, parse, return
        return List.of();
    }
}
```

**Built-in providers:**

| Provider | Priority | Source |
|---|---|---|
| `DatabaseSkillProvider` | 10 | SQLite `agent_skill` table (user-created, wins on collision) |
| `SkillsJarSkillProvider` | 50 | `META-INF/skills/` in JAR dependencies (bundled SkillsJars) |
| `ClasspathSkillProvider` | 100 | `src/main/resources/skills/` (bundled baseline) |

Lower priority = processed first, wins on name collision. Use values between 10 and 100 for custom providers.

When adding or removing a SkillsJar dependency in `app/pom.xml`, run `python3 ./scripts/verify_skillsjar_index.py --fix` and commit the resulting `app/src/main/resources/META-INF/skills/skills-index.txt`. Native Image cannot enumerate `META-INF/skills/` at runtime, so its exact `SKILL.md` path must be indexed and `META-INF/skills/**` must remain included as a native resource. See [SkillsJar Admission and Quarantine](SKILLSJAR_ADMISSION.md) for compatibility requirements, rejection rules, package structure, and runtime inspection.

---

## 14. Add an EmbeddingProviderFactory (Embedding Models)

`EmbeddingProviderFactory` adds new embedding model providers for RAG vector search. `InfiniteLoopEmbeddingModelProvider` discovers all implementations via CDI and routes across the provider list in `amsha.infinite-loop.embedding.providers` with health tracking and cooldown.

```java
@ApplicationScoped
public class CohereEmbeddingProviderFactory implements EmbeddingProviderFactory {

    @Override
    public String providerId() { return "cohere"; }

    @Override
    public EmbeddingModel create(EmbeddingModelConfig config) {
        String apiKey = requireApiKey(config);
        return new CohereEmbeddingModel(apiKey, config.modelName().orElse("embed-english-v3.0"));
    }
}
```

**Built-in providers:**

| Provider | providerId | Model |
|---|---|---|
| `JinaEmbeddingProviderFactory` | `jina` | `jina-embeddings-v5-text-nano` |
| `OllamaEmbeddingProviderFactory` | `ollama` | `nomic-embed-text:v1.5` |
| `OpenAiEmbeddingProviderFactory` | `openai` | `text-embedding-3-small` |

Wire via `.env` (add the provider to the slot list, then add per-provider keys):

```properties
AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS=ollama,cohere
AMSHA_EMBEDDING_COHERE_API_KEY=your-key
AMSHA_EMBEDDING_COHERE_MODEL=embed-english-v3.0
AMSHA_EMBEDDING_COHERE_BASE_URL=https://api.cohere.com/v1
```

---

## 15. Implement Persistence Ports (Storage Backend)

Amsha Agent uses hexagonal architecture with port interfaces in `amsha-persistence-api`. Three backends exist (SQLite, PostgreSQL, filesystem). To add a new backend (e.g., DynamoDB, Redis), implement all port interfaces:

| Port | Responsibility |
|---|---|
| `CheckpointStorePort` | Agent checkpoint CRUD, claim, finalize |
| `ChatMemoryStorePort` | Rolling chat window persistence |
| `CronStorePort` | Cron job persistence |
| `SecretStorePort` | Encrypted secret storage |
| `AuditEventStorePort` | Audit event append + query |
| `AuditSchemaPort` | WORM trigger setup |
| `CompactionStorePort` | Compaction summary storage |
| `MemoryRetrievalPort` | RAG memory retrieval |
| `SkillStorePort` | Skill CRUD |
| `AdvisoryLockPort` | Distributed advisory locking |
| `PersistenceAdapterPort` | Composite adapter wiring all ports above |

See `amsha-persistence-sqlite` for the reference implementation.

> [!IMPORTANT]
> Persistence ports are infrastructure-level extensions. Most developers extending Amsha Agent should use the higher-level SPIs (tools, reflexes, handlers) rather than implementing a new storage backend.

---

## Cross-Cutting Notes

### Where business logic lives

Code location matters for module boundary correctness:

| Concern | Correct module |
|---|---|
| New `AgentTool` implementation | `amsha-tools-builtin`, `mcp`, or a module-specific tools package (contracts in `amsha-tool-api`) |
| New `AgentReflex`, `MessageHandler`, `ContextEnricher`, `IntentRoutingProvider`, `GovernanceStrategy` | `amsha-core` |
| New `AgentChatModelProvider` or `RoutingChatModelProvider` | `amsha-llm-providers` (contracts in `amsha-llm-provider-api`) |
| New `EmbeddingProviderFactory` | `amsha-rag` |
| New `SkillProvider` implementation | `amsha-skills` (registry + entry contracts stay in `core.skill`; skills → core only, never the reverse) |
| New tiered memory service | `amsha-memory` — strict zero-core; store ports come from `amsha-persistence-api` |
| Vault / crypto / governed HTTP | `amsha-security` (vocabulary contracts in `amsha-security-api`) |
| New persistence port implementation | New module (see `amsha-persistence-sqlite` as reference) |
| New channel implementation | New module or `amsha-channel-telegram` as reference |
| REST endpoint or startup bootstrap | `amsha-app` |
| Build-time CDI/MCP/RAG wiring | `amsha-deployment` |

### Reflection registration for native image

If your new class is used reflectively at runtime (e.g., Jackson deserialization, Qute template data), register it in the owning module's dedicated `*ReflectionConfig.java` — accessible application types via `targets`, private/nested/dependency types via `classNames` binary names. Do not place type-level `@RegisterForReflection` on individual application classes, and do not register a moved type in two modules. JVM success does **not** prove native image safety (Architecture Invariant §6.1).

### Testing conventions

- Tool tests **must** bind with `WorkspaceContext.run(sessionId, userId, chatId, () -> { ... })`.
- Reflex tests **must** bind with `WorkspaceScopeHolder.call(sessionId, () -> { ... })`.
- Never mix the two binding layers in the same test (Architecture Invariant §2.6).

---

*Last updated: Amsha Agent V0.1.0 · Quarkus 3.39.3 · JDK 25 · 18 LLM providers*

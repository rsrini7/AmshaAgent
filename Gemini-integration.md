## Quarkus LangChain4j — Google AI Gemini Integration

The extension `quarkus-langchain4j-ai-gemini` provides the `GoogleAiGeminiChatModel` integration using the **Google AI (Gemini Developer API)** — not Vertex AI. This is the simpler API-key-based path, distinct from the `quarkus-langchain4j-vertex-ai-gemini` extension which requires a GCP project. [docs.quarkiverse](https://docs.quarkiverse.io/quarkus-langchain4j/dev/gemini-chat-model.html)

***

## Dependency

### With BOM (recommended)

```xml

<dependency>
  <groupId>io.quarkiverse.langchain4j</groupId>
  <artifactId>quarkus-langchain4j-ai-gemini</artifactId>
  <!-- no version needed when BOM is in place -->
</dependency>
```

***

## `application.properties` — Full Config Reference (but we are using yaml and not properties)

```properties
# ── Required ──────────────────────────────────────────────────────────────────
quarkus.langchain4j.ai.gemini.api-key=YOUR_GEMINI_API_KEY

# ── Model selection (default: gemini-2.5-flash) ───────────────────────────────
quarkus.langchain4j.ai.gemini.chat-model.model-id=gemini-2.5-flash

# ── Sampling parameters ───────────────────────────────────────────────────────
quarkus.langchain4j.ai.gemini.chat-model.temperature=0.7
quarkus.langchain4j.ai.gemini.chat-model.max-output-tokens=8192
quarkus.langchain4j.ai.gemini.chat-model.top-p=0.95
# top-k not supported on gemini-2.5-flash

# ── Thinking / reasoning (Gemini 2.5 series only) ────────────────────────────
quarkus.langchain4j.ai.gemini.chat-model.thinking.include-thoughts=false
# -1 = dynamic budget, 0 = thinking off, N = fixed token budget
quarkus.langchain4j.ai.gemini.chat-model.thinking.thinking-budget=-1

# ── Timeouts ──────────────────────────────────────────────────────────────────
quarkus.langchain4j.ai.gemini.chat-model.timeout=30s
quarkus.langchain4j.ai.gemini.timeout=30s

# ── Logging ───────────────────────────────────────────────────────────────────
quarkus.langchain4j.ai.gemini.log-requests=true
quarkus.langchain4j.ai.gemini.log-responses=true
quarkus.langchain4j.ai.gemini.chat-model.log-requests=true
quarkus.langchain4j.ai.gemini.chat-model.log-responses=true

# ── Integration toggle ────────────────────────────────────────────────────────
quarkus.langchain4j.ai.gemini.enable-integration=true
```



***

## Named Model Config (multi-provider pattern)

If ArcClaw needs to register Gemini alongside another provider (e.g., OpenAI or Groq) under a named qualifier:

```properties
quarkus.langchain4j.ai.gemini."gemini-flash".api-key=YOUR_KEY
quarkus.langchain4j.ai.gemini."gemini-flash".chat-model.model-id=gemini-2.5-flash
quarkus.langchain4j.ai.gemini."gemini-flash".chat-model.temperature=0.5
quarkus.langchain4j.ai.gemini."gemini-flash".chat-model.timeout=60s
```

Then inject with `@ModelName("gemini-flash")` on the `ChatLanguageModel` injection point. [docs.quarkiverse](https://docs.quarkiverse.io/quarkus-langchain4j/dev/gemini-chat-model.html)

***

## Key Differences: `ai-gemini` vs `vertex-ai-gemini`

| | `quarkus-langchain4j-ai-gemini` | `quarkus-langchain4j-vertex-ai-gemini` |
|---|---|---|
| Auth | API key | GCP service account / OIDC token |
| Config prefix | `quarkus.langchain4j.ai.gemini` | `quarkus.langchain4j.vertexai.gemini` |
| Requires GCP project | No | Yes |
| Use case | Dev/SaaS | GCP-hosted production |

 [quarkus](https://quarkus.io/blog/gemini-personal-assistant/)

***

## ArcClaw-Specific Notes

**`NvidiaMessageSanitizer` compatibility:** Gemini 2.5 Flash enforces its own system-prompt constraints. If your `NvidiaMessageSanitizer` trims `SystemMessage` content to fit Nvidia-specific token windows, ensure it is not applied to the Gemini request path — Gemini's `max-output-tokens` default of 8192 and context window (1M tokens) are far more permissive. [docs.quarkiverse](https://docs.quarkiverse.io/quarkus-langchain4j/dev/gemini-chat-model.html)

**Thinking budget for agentic loops:** For ArcClaw's multi-step tool-calling chains, setting `thinking-budget=-1` (dynamic) on `gemini-2.5-flash` lets the model allocate reasoning tokens proportionally to task complexity — better for coordinator roles than a fixed budget. Set `thinking-budget=0` for RESEARCHER/CODER roles where lower latency matters more. [docs.quarkiverse](https://docs.quarkiverse.io/quarkus-langchain4j/dev/gemini-chat-model.html)

**`@Alternative @Priority(1)`:** If you register `GoogleAiGeminiChatModel` alongside LangChain4j's default in-memory mock, the same CDI ambiguity rule from `PersistentChatMemoryStore` applies — ensure only one `ChatLanguageModel` bean wins per qualifier scope.

---

This is a well-known breaking issue with Gemini's thinking models. Here is the complete diagnosis and fix path.

***

## Root Cause: `thought_signature` — Gemini 3.x / 2.5 Thinking Models

The error is 100% caused by which model you're using. When Gemini's thinking models (gemini-2.5-flash, gemini-2.5-pro, gemini-3-series) generate a `functionCall`, they attach an encrypted `thoughtSignature` to the first function call part in every response.  **This signature must be echoed back verbatim in the next request's conversation history, or the API returns a `400 INVALID_ARGUMENT`.** [ai.google](https://ai.google.dev/gemini-api/docs/thought-signatures)

Your log shows the session was checkpointed (`AWAITING_APPROVAL`), the checkpoint was reloaded, and then resumed — at which point the reconstructed conversation history no longer contained the original `thoughtSignature` that the model had emitted before the pause. Gemini rejects the continuation. [github](https://github.com/langchain4j/langchain4j/issues/4097)

***

## Three Fix Options

### Option 1 — Disable Thinking (Fastest Fix)

Force `thinking-budget=0` on the model. This turns off chain-of-thought, no `thoughtSignature` is emitted, and tool calling works with standard LangChain4j chat memory. [ai.google](https://ai.google.dev/gemini-api/docs/thought-signatures)

```properties
quarkus.langchain4j.ai.gemini.chat-model.thinking.thinking-budget=0
quarkus.langchain4j.ai.gemini.chat-model.thinking.include-thoughts=false
```

Or in the builder directly:
```java
GeminiThinkingConfig thinkingConfig = GeminiThinkingConfig.builder()
    .thinkingBudget(0)
    .includeThoughts(false)
    .build();
```

Trade-off: You lose reasoning quality on complex tasks. Best for CODER/RESEARCHER roles where latency matters more. [ai.google](https://ai.google.dev/gemini-api/docs/thought-signatures)

***

### Option 2 — Enable `sendThinking=true` in LangChain4j (Correct Fix)

LangChain4j's `GoogleAiGeminiChatModel` **already supports thought signatures** — but you must enable `sendThinking(true)` so the framework stores and re-sends signatures automatically across turns. [github](https://github.com/langchain4j/langchain4j/issues/4097)

```java
GoogleAiGeminiChatModel model = GoogleAiGeminiChatModel.builder()
    .apiKey(apiKey)
    .modelName("gemini-2.5-flash")
    .thinkingConfig(GeminiThinkingConfig.builder()
        .thinkingBudget(-1)       // dynamic budget
        .includeThoughts(true)
        .build())
    .returnThinking(true)         // extract thoughtSignature from response
    .sendThinking(true)           // re-inject thoughtSignature in next request
    .build();
```

In `application.properties` form:
```properties
quarkus.langchain4j.ai.gemini.chat-model.thinking.thinking-budget=-1
quarkus.langchain4j.ai.gemini.chat-model.thinking.include-thoughts=true
quarkus.langchain4j.ai.gemini.chat-model.return-thinking=true
quarkus.langchain4j.ai.gemini.chat-model.send-thinking=true
```

This is the right long-term fix for COORDINATOR roles that benefit from deep reasoning. [github](https://github.com/langchain4j/langchain4j/issues/4097)

***

### Option 3 — Persist `thoughtSignature` Through Checkpoint (ArcClaw-Specific)

The harder problem you're facing is not just multi-turn within a single session — it's **cross-checkpoint resume**. When the session pauses for `AWAITING_APPROVAL` and `CheckpointResumeHandler` reloads conversation history from `PersistentChatMemoryStore`, the `thoughtSignature` embedded in the `AiMessage` attributes must survive the serialize → deserialize cycle.

LangChain4j stores the signature in `AiMessage.attributes()` as `"thoughtSignature"` key. You need to confirm:

1. **`PersistentChatMemoryStore.updateMessages`** serializes `AiMessage.attributes()` into `messagesJson` — not just the text and tool requests.
2. **`PersistentChatMemoryStore.getMessages`** deserializes them back with attributes intact.
3. The `ChatMemoryDto` record includes a field for message attributes, not just content.

If `attributes()` is being dropped during serialization (which is likely, since standard `ObjectMapper` won't know about LangChain4j's internal message format), that is why the signature is lost on resume even when `sendThinking=true`.

***

## Recommended Approach for ArcClaw

| Role | Strategy | Reason |
|---|---|---|
| COORDINATOR | `sendThinking=true`, `thinkingBudget=-1` | Complex multi-step tasks benefit from reasoning |
| RESEARCHER | `thinkingBudget=0` or `sendThinking=true` | Either works; `0` reduces cost and latency |
| CODER | `thinkingBudget=0` | Fast tool invocation, no thinking overhead |

**Immediate fix:** Set `thinking-budget=0` to unblock yourself now. Then implement proper attribute serialization in `PersistentChatMemoryStore` before re-enabling thinking. [ai.google](https://ai.google.dev/gemini-api/docs/thought-signatures)

---

The root cause is clear: `dev.langchain4j.model.googleai.GoogleAiGeminiChatModel` lives in the **standalone** `langchain4j-google-ai-gemini` artifact, but you only added `quarkus-langchain4j-ai-gemini` to `arcclaw-app`. The `arcclaw-core` module has no transitive path to that class. [mvnrepository](https://mvnrepository.com/artifact/dev.langchain4j/langchain4j-google-ai-gemini)

You have two clean options:

***

## Option A — Use Quarkiverse's `GeminiChatLanguageModel` (Correct Quarkus way)

Replace the import in both factory classes. The Quarkiverse extension exposes its own builder-capable class: [javadoc](https://javadoc.io/static/io.quarkiverse.langchain4j/quarkus-langchain4j-gemini-common/1.1.1/io/quarkiverse/langchain4j/gemini/common/GeminiChatLanguageModel.html)

```java
// Replace this:
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;

// With this:
import io.quarkiverse.langchain4j.gemini.common.GeminiChatLanguageModel;
```

Then replace the builder call:
```java
return GeminiChatLanguageModel.builder()
    .apiKey(apiKey)
    .modelName(modelName)
    .maxRetries(maxRetries)
    .temperature(temperature)
    .logRequests(logRequests)
    .logResponses(logResponses)
    .build();
```

And add the dependency to **`arcclaw-core/pom.xml`** (not just `arcclaw-app`):
```xml
<dependency>
  <groupId>io.quarkiverse.langchain4j</groupId>
  <artifactId>quarkus-langchain4j-gemini-common</artifactId>
  <exclusions>
    <exclusion>
      <groupId>dev.langchain4j</groupId>
      <artifactId>langchain4j-http-client-jdk</artifactId>
    </exclusion>
  </exclusions>
</dependency>
```

`quarkus-langchain4j-gemini-common` is the shared module that contains `GeminiChatLanguageModel` — it's what both `quarkus-langchain4j-ai-gemini` and `quarkus-langchain4j-vertex-ai-gemini` depend on. [central.sonatype](https://central.sonatype.com/artifact/io.quarkiverse.langchain4j/quarkus-langchain4j-gemini-common)

***

## Option B — Add standalone `langchain4j-google-ai-gemini` to `arcclaw-core` directly

Keep the existing factory code unchanged, just add the missing artifact to `arcclaw-core/pom.xml`:

```xml
<dependency>
  <groupId>dev.langchain4j</groupId>
  <artifactId>langchain4j-google-ai-gemini</artifactId>
  <!-- version inherited from langchain4j BOM -->
</dependency>
```

⚠️ **Risk:** This works only if the langchain4j BOM version in your project already covers `langchain4j-google-ai-gemini`. If there's a version mismatch between this artifact and the `langchain4j-core` that Quarkiverse bundles, you will hit `NoSuchMethodError` or `ClassCastException` at runtime — especially on `ChatRequest`/`ChatResponse` API. Verify with:

```bash
mvn dependency:tree -pl arcclaw-core | grep langchain4j
```

***

## Recommendation

**Use Option A** — it's the idiomatic Quarkus path, version-safe, and consistent with how you handle OpenAI. The factory classes need only a 2-line change (import + class name). Option B is a classpath risk given Quarkiverse's tight coupling with its bundled langchain4j version.
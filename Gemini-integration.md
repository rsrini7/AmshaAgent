# Quarkus LangChain4j - Google AI Gemini Integration

The extension `quarkus-langchain4j-ai-gemini` provides the `AiGeminiChatLanguageModel` integration using the **Google AI (Gemini Developer API)**. This is the API-key-based path used in ArcClaw.

***

## Dependency

ArcClaw uses the Quarkiverse extension in `arcclaw-core/pom.xml`:

```xml
<dependency>
  <groupId>io.quarkiverse.langchain4j</groupId>
  <artifactId>quarkus-langchain4j-ai-gemini</artifactId>
  <exclusions>
    <exclusion>
      <groupId>dev.langchain4j</groupId>
      <artifactId>langchain4j-http-client-jdk</artifactId>
    </exclusion>
  </exclusions>
</dependency>
```

***

## Configuration (`application.yaml`)

ArcClaw maps global LLM settings to Gemini via `GeminiProviderFactory`.

```yaml
arcclaw:
  llm:
    provider: gemini
    gemini:
      api-key: "${ARCCLAW_LLM_API_KEY}"
      model-name: "${ARCCLAW_LLM_MODEL_NAME:gemini-flash-latest}"
      log-requests: false
      log-responses: false
```

The factory also respects global `arcclaw.llm` settings for `timeout`, `temperature`, and `max-output-tokens`.

***

## Content Filter & Safety Handling

Gemini employs strict safety filters. When a response is blocked (e.g., for PII or safety violations), the underlying API returns a response where the `parts` list is null or empty.

### The "parts is null" NPE
Previously, this manifested as a `NullPointerException` within the LangChain4j/Quarkus integration layer:
`Cannot invoke "java.util.List.iterator()" because "parts" is null`

### ArcClaw Fix
ArcClaw now intercepts this specific NPE in `LlmExceptionClassifier`:
1.  **Classification**: Caught as `LlmContentFilterException`.
2.  **Mapping**: Mapped to `AgentResult.FailureCategory.SECURITY_POLICY`.
3.  **User Feedback**: Surfaced as a graceful error message: *"Gemini returned an empty response or was blocked by safety filters. Try rephrasing your request or adjusting safety settings."*

This prevents generic "System Error" logs and avoids stack trace pollution for policy-driven blocks.

***

## Thinking Models & `thought_signature`

Gemini 2.5+ "Thinking" models (e.g., `gemini-2.5-flash`) generate reasoning tokens and attach an encrypted `thoughtSignature` to function calls.

### Cross-Checkpoint Resume Issue
If a session is paused for human approval (`AWAITING_APPROVAL`), the conversation history is persisted. To resume successfully with a thinking model:
1.  The `thoughtSignature` must be persisted in the database.
2.  It must be re-sent in the `AiMessage` attributes during the resume iteration.
3.  Failure to do so results in a `400 INVALID_ARGUMENT` from Gemini.

**Current Recommendation:**
For **CODER** or **RESEARCHER** roles, prefer using standard models or disabling thinking (`thinking-budget: 0`) to avoid resume complexity. For **COORDINATOR** roles requiring deep reasoning, ensure the persistence layer (SQLite) preserves `AiMessage` attributes.

***

## Implementation Reference

- **Factory**: `com.arcclaw.core.agent.reasoning.GeminiProviderFactory`
- **Exception Handling**: `com.arcclaw.core.agent.reasoning.error.LlmExceptionClassifier`
- **State Machine Integration**: `com.arcclaw.core.agent.loop.EngineStepHandler`

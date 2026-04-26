https://github.com/langchain4j/langchain4j/issues/3248
https://docs.quarkiverse.io/quarkus-langchain4j/dev/guardrails.html
https://mvnrepository.com/artifact/dev.langchain4j/langchain4j-guardrails
https://www.youtube.com/watch?v=jvRSSsP26Z8
https://github.dev/LizeRaes/langchain4j-examples/tree/main/agentic-tutorial
https://freedium-mirror.cfd/https://levelup.gitconnected.com/secure-your-java-rag-applications-by-implementing-guardrail-patterns-prompt-injection-and-secret-4373d1d6db75
https://freedium-mirror.cfd/https://medium.com/helidon/langchain4j-guardrails-and-metrics-in-helidon-6c26385623d3

Yes - and the layering works beautifully because they operate at **different points in the pipeline** and never conflict. Here's the exact architecture:

## How the Two Systems Fit Together

LangChain4j's guardrails run **inside the AI Service**, at the LLM call boundary - before the LLM is called (input) and after it responds (output) . ArcClaw's guardrails run **outside the AI Service** - at the orchestrator, session, and tool-invocation level. They are complementary layers, not redundant ones. 

```
User message
     │
     ▼
① ArcClaw InputGuard (PII strip, prompt injection pattern match) ← FAST, zero cost
     │
     ▼
② LC4J InputGuardrail: MessageModeratorInputGuardrail            ← Moderation API, costs tokens
     │
     ▼
   LLM call (inside AiService)
     │
     ▼
③ LC4J OutputGuardrail: JsonExtractorOutputGuardrail             ← Format + JSON schema check, may reprompt
     │
     ▼
④ ArcClaw OutputGuard (business rules, audit, budget recording)  ← AuditService + GovernanceLayer
     │
     ▼
⑤ ArcClaw ToolInvocationGuard (autonomy/approval, @RequiresSecret) ← Tool boundary, only ArcClaw owns this
```

## Recommended Ordering - ArcClaw First for Input

Put ArcClaw's input guard **before** LangChain4j's `MessageModeratorInputGuardrail` . Your PII stripping and pattern-based prompt injection checks are zero-cost and instant - they should discard bad input before you spend moderation API tokens. 

```java
// In your AiService declaration
@InputGuardrails({
    ArcClawPiiGuardrail.class,           // ① ArcClaw: fast, free
    ArcClawInjectionGuardrail.class,     // ① ArcClaw: regex/heuristic
    MessageModeratorInputGuardrail.class // ② LC4J: costs tokens, runs last
})
@OutputGuardrails({
    JsonExtractorOutputGuardrail.class,  // ③ LC4J: reprompts on bad JSON
    ArcClawBusinessRuleGuardrail.class   // ④ ArcClaw: audit + budget gate
})
String chat(String message);
```

## Key Integration Notes

- **LangChain4j guardrails must be CDI beans** - use `@ApplicationScoped` just like your existing ArcClaw beans, and they'll be picked up automatically in Quarkus .
- **The Quarkus-specific guardrail package is deprecated.** You must import from `dev.langchain4j.guardrail` (not `io.quarkiverse.langchain4j.guardrails`) going forward .
- **`MessageModeratorInputGuardrail`** is the only out-of-the-box input guardrail LC4J ships - it wraps a `ModerationModel` (OpenAI moderation endpoint) and fatally blocks flagged content .
- **`JsonExtractorOutputGuardrail`** is the built-in output guard - it uses Jackson to validate the response matches your expected type and fires a **reprompt** on failure . This replaces your current `JsonExtractionException` retry loop in `LangChainReasoningEngine`. 

## What ArcClaw Owns Exclusively

LangChain4j guardrails have **no equivalent** for these - keep them 100% in ArcClaw:

| Layer | ArcClaw-only | Why |
|---|---|---|
| Session | `GovernanceLayer` budget hard/soft limits | Per-session USD tracking LC4J doesn't do   |
| Tool boundary | `ToolInvocationGuard` + autonomy/approval | Tool-level HITL, `@RequiresSecret`, sandbox gate   |
| Observability | `AuditService` + OTel spans | Tenant-bound audit trail  |
| Lifecycle | `SessionReaper` / zombie recovery | Out of scope for LLM guardrails   |

## Practical Upgrade for `JsonExtractorOutputGuardrail`

Your current retry logic in `LangChainReasoningEngine` manually catches `JsonExtractionException` and retries once. You can replace that with LC4J's `JsonExtractorOutputGuardrail` and configure `maxRetries` via the annotation - it handles the reprompt loop natively and re-runs the full guardrail chain on success : 

```java
@OutputGuardrails(value = JsonExtractorOutputGuardrail.class, maxRetries = 2)
MyStructuredOutput reason(String prompt);
```

This removes boilerplate from `executeWithBudgetGate` while still letting your `ArcClawBusinessRuleGuardrail` run after for budget recording and audit.
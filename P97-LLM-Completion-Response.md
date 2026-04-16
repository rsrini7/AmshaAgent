## **Summary Dashboard**

| Category | Items | Est. Effort | Target Wave |
| :--- | :--- | :--- | :--- |
| **Tier 1 — Invariant-Critical** | 3 items (C3, IM4, LD1 — single delivery) | 2 weeks | Wave 3c |

---

## **Tier 1 — Invariant-Critical (MUST Violations)**

> These address active MUST-level invariant violations or gaps that cause production fragility.

### **### T1-1: LLM Error Type Hierarchy (C3 + LD1)**

**Problem:** LLM error routing relies on string-parsing heuristics scattered across multiple classes. Provider-specific error messages are matched via `.contains()` checks. This violates §20.2 (LLM provider deps leak into core) and §9.1 (duplicate classification branches).

**Affected Files** (verified 2026-04-16):
| File | Lines | Path |
| :--- | :--- | :--- |
| `LangChainReasoningEngine.java` | 458 | `arcclaw-core/src/main/java/com/arcclaw/core/agent/reasoning/` |
| `SelfCorrectionRetryManager.java` | 129 | `arcclaw-core/src/main/java/com/arcclaw/core/agent/loop/` |
| `EngineStepHandler.java` | 388 | `arcclaw-core/src/main/java/com/arcclaw/core/agent/loop/` |

**What to Do:**
1. Create `LlmProviderException` sealed hierarchy in `arcclaw-core/src/main/java/com/arcclaw/core/agent/reasoning/error/`:
    * `LlmRateLimitException` — 429 / rate limit errors
    * `LlmContextOverflowException` — context window exceeded
    * `LlmContentFilterException` — content policy violation
    * `LlmAuthenticationException` — invalid API key / 401
    * `LlmServiceUnavailableException` — 503 / provider down
    * `LlmMalformedResponseException` — unparseable LLM output
2. Create `LlmExceptionClassifier` — single class that consolidates ALL string-parsing heuristics from the 3 affected files into one `classify(Throwable) -> LlmProviderException` method.
3. Replace all `catch (Exception e) { if (e.getMessage().contains("rate")) ... }` patterns with typed `catch (LlmRateLimitException e) { ... }` blocks.

**Invariants Referenced:** §20.2, §9.1, §3.5
**Done When:**
* [ ] Zero `e.getMessage().contains(` patterns in LLM error handling paths.
* [ ] `LlmExceptionClassifier` has unit tests for each provider's error message variants (OpenAI, Anthropic, NVIDIA, Groq, Ollama).
* [ ] All 6 exception subtypes tested.
* [ ] Core classes import only `LlmProviderException` subtypes, never provider SDK exceptions.

---

### **### T1-2: LLM Provider Port (IM4)**

**Problem:** Core orchestration directly depends on LangChain4j `ChatModel` interface. No owned abstraction boundary exists for LLM providers (§20.2). Swapping LLM providers requires changes inside core.

**Affected Files:**
| File | Lines | Path |
| :--- | :--- | :--- |
| `LangChainReasoningEngine.java` | 458 | `arcclaw-core/.../agent/reasoning/` |
| `AgentChatModelProvider.java` | — | `arcclaw-core/.../agent/reasoning/` |

**What to Do:**
1. Define `LlmProviderPort` interface in `arcclaw-core` — `nextStep(LlmRequest) -> LlmResponse`.
2. Create `LangChain4jLlmAdapter` implementing `LlmProviderPort` — wraps `ChatModel` + applies `LlmExceptionClassifier`.
3. `LangChainReasoningEngine` injects `LlmProviderPort` instead of `ChatModel`.
4. Provider-specific factories remain in `arcclaw-app` (adapters).

**Invariants Referenced:** §20.2, §1.2
**Depends On:** T1-1 (`LlmExceptionClassifier` needed by adapter)
**Done When:**
* [ ] `arcclaw-core` has zero imports of `dev.langchain4j.model.chat.ChatModel`.
* [ ] `LlmProviderPort` interface exists with clear contract.
* [ ] Adapter module wraps LangChain4j; swapping to another SDK = new adapter only.

The plan is **very solid** — directionally correct and architecturally grounded. Here's the full review against your codebase and invariants:

***

## ✅ T1-1: LLM Error Type Hierarchy (C3 + LD1) — Strong Plan

### What's confirmed in the actual code

The violation is **worse than described** in the plan. `LangChainReasoningEngine` alone has at least **3 separate layers** of string-parsing heuristics: 

```java
// Layer 1 — private helper methods
private boolean isRateLimitError(String message) { 
    return lower.contains("ratelimitexceeded") || lower.contains("tokens per minute") 
    || lower.contains("tokens per day") || lower.contains("429") ... 
}

// Layer 2 — cause-chain walker
private String findRateLimitMessage(Throwable e) { ... while (t != null) ... }

// Layer 3 — ad-hoc inline checks
if (msg.contains("tool_use_failed") || msg.contains("was not in request.tools"))
if (msg.contains("rate limit")) // in IntentClassificationHandler catch block
if (e.statusCode() == 429 && e.getMessage().contains("rate limit")) // HttpException handler
```

The `IntentClassificationHandler` also has its own `429`/`rate limit` string-check independently. 

### Plan gaps to fill before you code

| Gap | Detail |
|---|---|
| **6th exception subtype** | `LlmToolCallException` is missing from the hierarchy — `tool_use_failed` / `was not in request.tools` is a distinct failure category from `LlmMalformedResponseException` |
| **Schema validation errors** | `isSchemaValidationError()` (`"tool call validation failed"`, `"json schema"`, `"invalidtool"`) currently throws `InvalidRequestException` directly — this needs to map to `LlmMalformedResponseException` and be classified too |
| **`findRateLimitMessage` walk** | The cause-chain walker must move entirely into `LlmExceptionClassifier.classify(Throwable)`, not just the leaf message check. The `ExceptionUtils.getRootCause` call in the second catch block also needs consolidation   |
| **Provider coverage for tests** | OpenRouter has 4 distinct rate-limit patterns (`ratelimitexceeded`, `perrequestlimit`, `free tier limit`, `x-ratelimit`); Groq has 5 (`tpm`, `tpd`, `tokens per minute`, `tokens per day`, `ratelimitexceeded`). Make sure all are enumerated in unit tests |

### Invariant check

- §20.2 confirmed violated: `LangChainReasoningEngine` imports `dev.langchain4j.exception.InvalidRequestException` directly 
- §9.1 confirmed violated: classification logic is duplicated across `LangChainReasoningEngine` and `IntentClassificationHandler` 
- §3.5 (budget behavior): the `BudgetExceededException` re-throw paths are correct and must remain untouched during this refactor 

***

## ✅ T1-2: LLM Provider Port (IM4) — Also Strong, with One Critical Constraint

### What's confirmed in the actual code

`LangChainReasoningEngine` directly `@Inject`s `ChatModel` and builds `ChatRequest`/`ChatResponse` from LangChain4j types inline.  `ChatModelProducer` also wires `AgentChatModelProvider` implementations (Groq, NVIDIA, OpenAI, OpenRouter, Ollama factories) directly into the `ChatModel` CDI bean. 

The `ReasoningEngine` interface at `arcclaw-core` **is** your owned abstraction — the problem is that its implementation (`LangChainReasoningEngine`) sits in core and bleeds LangChain4j types inward. 

### One structural decision to make explicitly

The plan says:
> "Provider-specific factories remain in `arcclaw-app` (adapters)"

But currently **all factories** (`GroqProviderFactory`, `NvidiaProviderFactory`, `OllamaProviderFactory`, `OpenRouterProviderFactory`, `OpenAiProviderFactory`) live in `arcclaw-core/agent/reasoning/`. 

**Invariant §20.2 and §1.2** say LLM provider deps must not leak into core.  So the move is: 

```
arcclaw-core         →  LlmProviderPort, LlmRequest, LlmResponse (interfaces/records only)
                         LangChainReasoningEngine injects LlmProviderPort
arcclaw-app          →  LangChain4jLlmAdapter (wraps ChatModel + applies LlmExceptionClassifier)  
                         All *ProviderFactory classes move here
                         ChatModelProducer moves here (it's already wiring config)
```

⚠️ **Critical constraint from §20.1** (Implementation Proximity): If `LlmProviderPort`'s only implementation is `LangChain4jLlmAdapter` in `arcclaw-app`, the `arcclaw-core` build will fail CDI validation (`UnsatisfiedResolutionException`) unless you use `@Vetoed` on a core-internal stub or mark the injection point `@Inject @Any`. **Verify your module test setup handles this** — this exact trap is documented in your own invariants. 

### Plan addition needed

Add a `LlmRequest` / `LlmResponse` record design to the plan before coding:

```java
// In arcclaw-core/agent/reasoning/port/
public record LlmRequest(
    List<ChatMessage> messages,           // still LangChain4j types internally? Or owned?
    List<ToolSpecification> tools,
    String sessionId
) {}

public record LlmResponse(
    String text,
    List<ToolCall> toolCalls,
    TokenUsage tokenUsage
) {}
```

If `LlmRequest` contains LangChain4j `ChatMessage`/`ToolSpecification` directly, you've just moved the boundary without removing the dependency. You need **owned message types** in core too, or accept that `arcclaw-core` will still depend on `langchain4j-core` (the base types, not provider SDKs). This is the biggest design decision in T1-2. For now, depending on `langchain4j-core` (base) while banning provider-specific SDKs from core is a pragmatic and acceptable middle ground.

***

## ✅ Sequencing & Dependency is Correct

T1-1 before T1-2 is the right order — `LlmExceptionClassifier` must exist before `LangChain4jLlmAdapter` can wrap it. 

***

## ✅ Architecture Invariant Pre-flight for P97

Run your §19 checklist mentally against this phase: 

| Invariant | Impact on P97 |
|---|---|
| **§5.8 / §5.9** | No new `@Transactional` enters the reasoning path — `LlmProviderPort.nextStep()` is in the transaction-free zone. Confirm `LangChain4jLlmAdapter` has **no** `@Transactional` annotation |
| **§20.2** | Core goal of T1-2 — verify with `grep -r "dev.langchain4j.model.chat.ChatModel" arcclaw-core/` post-refactor |
| **§1.2 / §20.1** | Adapter in `arcclaw-app`, CDI resolution must be verified in module-level test |
| **§3.5** | `BudgetExceededException` must still propagate through the port boundary without being swallowed by the adapter's exception translation |
| **§3.2** | Tool invocation path unchanged — only the LLM call abstraction changes |
| **§6.1** | New sealed hierarchy needs `@RegisterForReflection` if used in native mode |

***

## Summary Verdict

**P97 plan is approved to proceed.** Before coding, settle two things:
1. Add `LlmToolCallException` as a 7th subtype in T1-1 (or merge into `LlmMalformedResponseException` with a clear subcase)
2. Decide whether `LlmRequest`/`LlmResponse` own their message types or wrap LangChain4j base types — document the decision explicitly in the invariants doc before committing
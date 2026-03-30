# ArcClaw Java Conventions

## ReasoningStep — Factory Methods Only

**NEVER** construct `ReasoningStep` with `new ReasoningStep(...)` directly.
Always use the established static factory methods:

| Situation | Use |
|---|---|
| Creating a step with thought + action + actionInput | `ReasoningStep.pending(thought, action, actionInput, inputTokens, outputTokens)` |
| Creating a step that already has an observation | `step.withObservation(observationString)` |
| Overriding only the observation on an existing step | `existingStep.withObservation(...)` |

### Why
- The canonical constructor may gain fields (e.g. `parallelBatch`, `channelMeta`) in future
  phases — direct construction breaks silently at compile time if a new field is added.
- `ReasoningStep.pending(...)` centralises the `parallelBatch = false` default and any
  future field defaulting logic in one place.
- Passing `null` for `observation` in `new ReasoningStep(...)` is a latent NPE risk;
  `withObservation` enforces a non-null contract.

### Pattern to flag and correct
```java
// ❌ NEVER — direct constructor
new ReasoningStep(thought, action, actionInput, null, inputTokens, outputTokens)

// ✅ ALWAYS — factory method
ReasoningStep.pending(thought, action, actionInput, inputTokens, outputTokens)

// ✅ ALWAYS — mutation-style wither for observation
step.withObservation("Task completed.")
```

### Rescue / rewrite scenarios
When rewriting a step (e.g. rescuing a hallucinated `finish` tool call),
copy token counts from the original step:

```java
ReasoningStep rescued = ReasoningStep.pending(
    step.thought(),
    ReasoningStep.FINISH,
    Map.copyOf(step.actionInput()),  // defensive copy — never share the live map
    step.inputTokens(),              // preserve for guardFinalAnswer() token check
    step.outputTokens()
);
```

---

## AgentResult — Sealed Type Factories Only

**NEVER** use `new AgentResult.Finished(...)` etc. Always use:

```java
AgentResult.finished(answer, steps)
AgentResult.failed(reason, category, steps)
AgentResult.loopExceeded(maxIterations, steps)
```

---

## Map Mutation Safety

When copying `actionInput` from an existing `ReasoningStep` into a new one,
always use `Map.copyOf(step.actionInput())` — never pass the raw reference.
`handleFinishAction` reads but does not write; future refactors may not.

---

## General Java Guards

- Null-check `e.getMessage()` before use — several JVM exception types return `null`.
  Use: `e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()`
- Prefer `String.format(...)` / `log.infof(...)` over concatenation in log statements.
- `MDC` values removed in `finally` blocks only — never remove in `catch`.


## ReasoningStep — Key Constants

Never use raw string literals for action names or actionInput keys. Use constants from `ReasoningStep`:

| Constant | Value | Use |
|---|---|---|
| `ReasoningStep.FINISH` | `"FINISH"` | Action name for terminal step |
| `ReasoningStep.OBSERVE` | `"OBSERVE"` | Action name for schema-correction step |
| `ReasoningStep.ACTION_INPUT_ANSWER_KEY` | `"answer"` | Key in `actionInput` map for the final answer |

Case-insensitive comparison: use `ReasoningStep.FINISH.equalsIgnoreCase(toolName)`.
Never pre-call `.toLowerCase()` before `equalsIgnoreCase` — it is redundant and allocates
unnecessary String objects in the hot agent loop path.


## REST Status Code Conventions

| Operation | Endpoint pattern | Expected status |
|---|---|---|
| Create session | `POST /api/v1/sessions` | `201` |
| Run agent (sync) | `POST /api/v1/sessions/{id}/run` | `200` |
| Approve | `POST /api/v1/sessions/{id}/approve` | `200` |
| Session not found | any session endpoint | `404` |
| Cross-key access | any session endpoint | `401` + `code=SESSION_ACCESS_DENIED` |
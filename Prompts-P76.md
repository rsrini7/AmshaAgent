# PROMPT — PHASE 76: Agent Delegation — Full Implementation

Role: Senior Java Architect & Senior Java Developer
Task: Implement end-to-end agent delegation. Confirmed production bug from logs:
"ask Shiva to identify what my name now" routed to researcher (SLA=149ms, Groq)
instead of coordinator. Coordinator then hallucinated github twice for delegation
requests because no delegate_to_agent tool exists.

This prompt is the authoritative implementation spec. It supersedes all other
LLM analyses of this feature. Corrections to external reviews are called out
inline where they conflict with P75 source.

---

## Architecture Context

Refer @Architecture_Invariants.md §2 (context layers), §3.2 (tool invocation path).
Refer @ScopedValues-Binding-Rules.md.
Refer @arcclaw-core/src/main/resources/templates/prompts/role-coordinator.txt.

CRITICAL P75 STRUCTURAL NOTES — read before touching any file:
1. AgentRoleEnum is a RECORD (not enum) in P75: `public record AgentRoleEnum(String id)`.
   Capabilities live in a static DEFINITIONS Map<String, RoleDefinition>.
   To add DELEGATE_TO_AGENT to coordinator, modify the DEFINITIONS map entry only.
2. AgentRoleEnum.fromString() NEVER throws in P75.
   Unknown role strings produce `new AgentRoleEnum(normalized)`.
   Use AgentRoleEnum.isKnownRole(string) to validate agent names.
3. AutonomyLevel.READONLY exists in P75 — ToolStepHandler enforces it at line ~11801.

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements

1. Every fix must be accompanied by a test proving the bug existed and now does not.
2. No new Maven modules. No new dependencies.
3. All existing tests must remain green.
4. Use `static final Pattern` for any new regex — never compiled inline.
5. All code safe under JDK 25. No preview features.
6. Test commands always use `clean` to prevent stale class false-negatives.

---

### Component 1 — Add DELEGATE_TO_AGENT to ToolCapability and coordinator's DEFINITIONS

**File 1**: `arcclaw-core/src/main/java/com/arcclaw/core/agent/role/ToolCapability.java`

Add new constant. Locate the existing SANDBOX_EXEC entry and add after it:

```java
DELEGATE_TO_AGENT(
    "delegate_to_agent",
    "Delegates a task to a named specialist agent and returns their response. " +
    "Use ONLY when the user explicitly asks to involve a specific agent by name or " +
    "deity alias (e.g. 'ask Shiva', 'tell Brahma', 'have Saraswati'). " +
    "Parameters: {\"agent\": \"<role_name_or_deity_alias>\", " +
    "\"goal\": \"<fully self-contained task — no pronouns, no references to 'above'>\"}. " +
    "Valid role names: coordinator, researcher, coder, debugger, architect, synthesizer. " +
    "Deity aliases: vishnu, saraswati, brahma, shiva, brihaspati/brahaspati, dattatreya/datta."
)
```

**File 2**: `arcclaw-core/src/main/java/com/arcclaw/core/agent/role/AgentRoleEnum.java`

Locate the "coordinator" entry in the static DEFINITIONS map and add
`ToolCapability.DELEGATE_TO_AGENT` to its capability Set:

```java
"coordinator", new RoleDefinition(
    "coordinator",
    "Vishnu (Coordinator)",
    "Orchestrates sub-agents, manages session state, and schedules background tasks.",
    7, true,
    Set.of(
        ToolCapability.GITHUB,
        ToolCapability.SCHEDULE_TASK,
        ToolCapability.MANAGE_SCHEDULE,
        ToolCapability.MEMORY_SEARCH,
        ToolCapability.INGEST_DOCUMENT,
        ToolCapability.DELEGATE_TO_AGENT    // ← add this
    ),
    Set.of("vishnu")
),
```

**Test**: `ToolCapabilityTest.testDelegateToAgentExistsAndCoordinatorOwnsIt()`
```java
@Test
void testDelegateToAgentExistsAndCoordinatorOwnsIt() {
    assertNotNull(ToolCapability.DELEGATE_TO_AGENT);
    assertEquals("delegate_to_agent", ToolCapability.DELEGATE_TO_AGENT.toolName());
    assertTrue(
        AgentRoleEnum.COORDINATOR.definition().capabilities()
            .contains(ToolCapability.DELEGATE_TO_AGENT),
        "Coordinator must own DELEGATE_TO_AGENT capability"
    );
    // Other roles must NOT own it
    assertFalse(AgentRoleEnum.RESEARCHER.definition().capabilities()
        .contains(ToolCapability.DELEGATE_TO_AGENT));
    assertFalse(AgentRoleEnum.DEBUGGER.definition().capabilities()
        .contains(ToolCapability.DELEGATE_TO_AGENT));
}
```

---

### Component 2 — DelegateToAgentTool

**New file**: `arcclaw-core/src/main/java/com/arcclaw/core/tools/DelegateToAgentTool.java`

```java
package com.arcclaw.core.tools;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.reasoning.ReasoningEngine;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.ToolOperation;
import com.arcclaw.core.util.AgentResultFormatter;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.jboss.logging.Logger;

/**
 * Allows the coordinator (Vishnu) to explicitly delegate a task to a named
 * specialist agent and return that agent's response as a tool observation.
 *
 * <p>The inner AgentLoop runs in the same WorkspaceContext as the outer
 * coordinator turn. This is intentional and correct per Architecture_Invariants
 * §3.2 — all tool execution flows through ToolRegistry → ToolInvoker.
 *
 * <p>BudgetExceededException MUST propagate — do NOT catch it here. The outer
 * coordinator loop must see it and terminate correctly.
 *
 * <p>IMPORTANT: AgentRoleEnum.fromString() never throws in P75 — unknown names
 * produce a dynamic AgentRoleEnum. Validation uses isKnownRole() instead.
 */
@ApplicationScoped
public class DelegateToAgentTool implements AgentTool {

    private static final Logger log = Logger.getLogger(DelegateToAgentTool.class);

    @Inject AgentLoop agentLoop;
    @Inject ReasoningEngine reasoningEngine;

    @Override
    public String name() { return ToolCapability.DELEGATE_TO_AGENT.toolName(); }

    @Override
    public String description() { return ToolCapability.DELEGATE_TO_AGENT.description(); }

    @Override
    public ToolOperation requiredOperation() { return ToolOperation.READ_FS; }

    @Override
    public boolean requiresSandbox() { return false; }

    @Override
    public JsonObjectSchema parametersSchema() {
        return JsonObjectSchema.builder()
            .addStringProperty("agent",
                "The specialist agent to delegate to. " +
                "Role names: coordinator, researcher, coder, debugger, architect, synthesizer. " +
                "Deity aliases also accepted: vishnu, saraswati, brahma, shiva, " +
                "brihaspati, brahaspati, dattatreya, datta.")
            .addStringProperty("goal",
                "The specific task the specialist should perform. " +
                "MUST be fully self-contained — no pronouns, no references to 'the above', " +
                "no implicit context. The specialist executes in isolation with no access " +
                "to this conversation. Include file names, repo names, error text, or any " +
                "other context the specialist needs to complete the task cold.")
            .required("agent", "goal")
            .build();
    }

    @Override
    public ToolResponse execute(Map<String, Object> params) {
        String agentParam = (String) params.get("agent");
        String goal = (String) params.get("goal");

        if (agentParam == null || agentParam.isBlank()) {
            return ToolResponse.failure(400, "Parameter 'agent' is required.");
        }
        if (goal == null || goal.isBlank()) {
            return ToolResponse.failure(400, "Parameter 'goal' is required.");
        }

        // P75: fromString() never throws — use isKnownRole() to validate.
        if (!AgentRoleEnum.isKnownRole(agentParam)) {
            return ToolResponse.failure(400,
                "Unknown agent '" + agentParam + "'. " +
                "Valid role names: coordinator, researcher, coder, debugger, " +
                "architect, synthesizer. " +
                "Deity aliases: vishnu, saraswati, brahma, shiva, " +
                "brihaspati, brahaspati, dattatreya, datta.");
        }

        AgentRoleEnum targetRole = AgentRoleEnum.fromString(agentParam);
        String sessionId = WorkspaceContext.requireSessionId();

        log.infof("[DELEGATE] coordinator → agent=%s goal='%s' session=%s",
                  targetRole.toScopedName(), goal, sessionId);

        // BudgetExceededException MUST propagate — do NOT catch here.
        AgentResult result = agentLoop.run(
            sessionId,
            targetRole.toScopedName(),
            goal,
            reasoningEngine,
            targetRole.definition().maxIterations()
        );

        String formatted = AgentResultFormatter.format(result);
        log.infof("[DELEGATE] agent=%s completed for session=%s",
                  targetRole.toScopedName(), sessionId);
        return ToolResponse.success(formatted);
    }
}
```

**Test**: `DelegateToAgentToolTest`
```java
@Test
void testSuccessfulDelegationReturnsFormattedResult() {
    // Arrange
    when(agentLoop.run(anyString(), eq("debugger"), anyString(), any(), anyInt()))
        .thenReturn(AgentResult.finished("Your name is Sri.", List.of()));

    ToolResponse response = tool.execute(
        Map.of("agent", "shiva", "goal", "What is the user's preferred name?"));

    assertTrue(response.success());
    assertTrue(response.output().contains("Sri"));
}

@Test
void testUnknownAgentReturnsFailure() {
    ToolResponse response = tool.execute(
        Map.of("agent", "zeus", "goal", "do something"));
    assertFalse(response.success());
    assertTrue(response.output().contains("Unknown agent"));
}

@Test
void testDeityAliasResolvesCorrectly() {
    when(agentLoop.run(anyString(), eq("debugger"), anyString(), any(), anyInt()))
        .thenReturn(AgentResult.finished("ok", List.of()));

    ToolResponse response = tool.execute(
        Map.of("agent", "shiva", "goal", "analyze this stack trace"));
    assertTrue(response.success());
    verify(agentLoop).run(anyString(), eq("debugger"), anyString(), any(), anyInt());
}

@Test
void testBudgetExceededPropagatesUncaught() {
    when(agentLoop.run(anyString(), anyString(), anyString(), any(), anyInt()))
        .thenThrow(new BudgetExceededException("Budget exceeded"));

    assertThrows(BudgetExceededException.class, () ->
        tool.execute(Map.of("agent", "researcher", "goal", "research topic")));
}

@Test
void testBlankAgentReturnsFailure() {
    ToolResponse r = tool.execute(Map.of("agent", "", "goal", "something"));
    assertFalse(r.success());
}
```

---

### Component 3 — DelegationRoutingProvider (fast-path to coordinator)

**New file**: `arcclaw-core/src/main/java/com/arcclaw/core/routing/DelegationRoutingProvider.java`

Root cause from logs: "ask Shiva to identify what my name now" → classified as
`researcher` (149ms) because the LLM saw "identify" and ignored the delegation wrapper.
This fast-path pattern fires before the LLM is called and routes to coordinator at priority 5
— ahead of SchedulingRoutingProvider (10) and all others.

```java
package com.arcclaw.core.routing;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.agent.role.AgentRoleEnum;

/**
 * Fast-path routing provider that detects explicit agent delegation requests
 * and routes them to the coordinator BEFORE the LLM classifier is invoked.
 *
 * <p>Confirmed production bug: "ask Shiva to identify what my name now" was
 * classified as 'researcher' because the LLM focused on "identify" (research
 * intent) and ignored the "ask Shiva" delegation wrapper. This pattern fires
 * first and returns coordinator immediately — zero LLM cost.
 *
 * <p>Priority 5 — evaluated BEFORE SchedulingRoutingProvider (10) and all others.
 */
@ApplicationScoped
public class DelegationRoutingProvider implements IntentRoutingProvider {

    // All deity aliases and canonical role names. Must stay in sync with
    // AgentRoleEnum.DEFINITIONS aliases. Add new agents/aliases here when
    // new roles are registered.
    private static final String AGENT_NAMES =
        "shiva|brahma|saraswati|vishnu|brihaspati|brahaspati|dattatreya|datta|" +
        "coordinator|researcher|coder|debugger|architect|synthesizer";

    // Matches: "ask Shiva ...", "tell Brahma ...", "have Saraswati ...",
    //          "get Shiva to ...", "delegate to debugger", "ask that Shiva"
    private static final Pattern DELEGATION_PATTERN = Pattern.compile(
        "(?i)\\b(ask|tell|have|get|delegate\\s+to|instruct)\\s+(" +
        AGENT_NAMES + ")\\b"
    );

    @Override
    public List<Pattern> fastPathTriggers() {
        return List.of(DELEGATION_PATTERN);
    }

    @Override
    public List<String> promptDirectives() {
        return List.of(
            "DELEGATION IS HIGHEST PRIORITY: If the user message contains an explicit " +
            "agent name or deity alias (e.g. 'ask Shiva', 'tell Brahma', 'have Saraswati', " +
            "'delegate to debugger'), route to 'coordinator'. " +
            "The coordinator owns ALL delegation requests."
        );
    }

    @Override
    public Optional<String> fastPathRole() {
        return Optional.of(AgentRoleEnum.COORDINATOR.toScopedName());
    }

    @Override
    public int priority() {
        return 5; // Highest — fires before scheduling (10) and all others
    }
}
```

**Test**: `DelegationRoutingProviderTest`
```java
@Test
void testAllDelegationPatternsMatchCoordinator() {
    var provider = new DelegationRoutingProvider();
    List<Pattern> triggers = provider.fastPathTriggers();

    // Confirmed from production logs
    assertTrue(matches(triggers, "ask Shiva to identify what my name now"));
    assertTrue(matches(triggers, "ask Shiva deligate"));
    assertTrue(matches(triggers, "tell Brahma to write hello world in Python"));
    assertTrue(matches(triggers, "have Saraswati research quantum computing"));
    assertTrue(matches(triggers, "delegate to debugger"));
    assertTrue(matches(triggers, "get Brihaspati to review my architecture"));
    assertTrue(matches(triggers, "ask Dattatreya to synthesize the results"));

    // Must NOT match normal messages
    assertFalse(matches(triggers, "what is my name?"));
    assertFalse(matches(triggers, "remind me in 5 minutes"));
    assertFalse(matches(triggers, "show me my github repo"));
    assertFalse(matches(triggers, "i didnt ask you to get github"));

    assertThat(provider.fastPathRole()).contains("coordinator");
    assertThat(provider.priority()).isEqualTo(5);
}

private boolean matches(List<Pattern> patterns, String input) {
    return patterns.stream().anyMatch(p -> p.matcher(input).find());
}
```

---

### Component 4 — Coordinator system prompt: delegation rules

**File**: `arcclaw-core/src/main/resources/templates/prompts/role-coordinator.txt`

Add this `{#include}` directive after the existing includes at the top of the template
(after `{#include prompts/github-tool-rules /}`, before `{#include prompts/safety-rules /}`):

```
{#include prompts/delegate-to-agent-rules /}
```

**New file**: `arcclaw-core/src/main/resources/templates/prompts/delegate-to-agent-rules.txt`

This is the authoritative delegation rules document. The agent roster table below is
verified against P75 `AgentRoleEnum.DEFINITIONS` — do not change tool assignments
without also updating the DEFINITIONS map.

```
DELEGATE_TO_AGENT — COORDINATOR DISPATCH RULES

====
SECTION 1 — AGENT ROSTER & TOOL ACCESS (verified against P75 runtime)
====

You may delegate tasks to the following specialist agents.
Each agent has a fixed tool set enforced by the runtime — you cannot grant them
tools they do not own, and they cannot call tools outside their allowed set.

  ┌─────────────────┬────────────────────────────────────────────────────┬───────────────────────────────┐
  │ Agent           │ Scope                                              │ Tools (runtime-enforced)      │
  ├─────────────────┼────────────────────────────────────────────────────┼───────────────────────────────┤
  │ brahma          │ Write new code, build features from scratch        │ sandbox_exec                  │
  │ shiva           │ Debug stack traces, error logs, exception chains   │ github, sandbox_exec          │
  │ saraswati       │ Research, fact-finding, documentation lookups      │ github, memory_search         │
  │ brihaspati      │ System design, ADRs, architectural review          │ github, memory_search         │
  │ dattatreya      │ Cross-domain synthesis, conflict resolution        │ github, memory_search,        │
  │                 │ (multi-agent output integration)                   │ schedule_task, manage_schedule│
  │                 │                                                    │ ingest_document               │
  └─────────────────┴────────────────────────────────────────────────────┴───────────────────────────────┘

YOU (Vishnu) retain: github, schedule_task, manage_schedule, memory_search,
ingest_document, delegate_to_agent.

NOTE ON BRAHMA: Brahma generates code using the sandbox_exec tool, which runs
code in an isolated process. He does NOT have github or memory_search access.
Provide all necessary context in the goal — he cannot fetch it himself.

NOTE ON SHIVA: Shiva can both read GitHub (for code context) and execute code
in the sandbox (to reproduce bugs). He cannot search memory.


====
SECTION 2 — WHEN TO DELEGATE vs. HANDLE DIRECTLY
====

HANDLE DIRECTLY (do NOT delegate) when:
  - The task only requires a memory lookup → call memory_search yourself.
  - The task only requires a single GitHub fetch → call github yourself.
  - The task involves scheduling or cron → call schedule_task / manage_schedule
    yourself. These produce terminal fast-path exits — see Section 8.
  - The task only requires ingesting a document → call ingest_document yourself.
  - The user's request is a clarifying question about the team's capabilities.

DELEGATE when:
  - The task requires code GENERATION (Brahma — sandbox_exec).
  - The task requires root-cause analysis of a bug or stack trace (Shiva).
  - The task requires extensive factual research (Saraswati).
  - The task requires a formal architectural review or ADR (Brihaspati).
  - The task simultaneously spans code + debug + research (Dattatreya to synthesize).
  - The user EXPLICITLY names a specialist agent or deity alias.

NEVER delegate speculative tasks. If you are not sure which specialist to use,
think harder — do NOT default to delegation to avoid reasoning.


====
SECTION 3 — GOAL MATERIALIZATION (NON-NEGOTIABLE)
====

The goal you pass to delegate_to_agent MUST be fully self-contained.
The sub-agent executes in isolation with no access to this conversation,
no shared memory, and no visibility into prior tool results unless you
explicitly embed them in the goal string.

RULES:
  1. NEVER use pronouns (it, this, that, them). Replace with the explicit noun.
     WRONG: "Fix the bug in it."
     RIGHT:  "Fix the NullPointerException in AgentStateMachineRunner.run()
              at line 211 where iterSpan.setStatus() is called with a null
              message, and return the corrected Java method."

  2. NEVER embed time references. The goal describes the FINAL ACTION only.
     WRONG: "Check the database tomorrow and report."
     RIGHT:  "Query the database status via github and report results."

  3. NEVER reference 'the context above' without embedding the actual content.
     If the task depends on prior tool output, paste the relevant excerpt
     directly into the goal string.

  4. Include: repository owner/name, file paths, class names, method names,
     error text, expected output format — whatever the specialist needs cold.

  5. State the expected output format explicitly if it matters:
     "Return a complete Java class", "Return a markdown ADR".


====
SECTION 4 — PARALLEL DELEGATION (PHASE 70 BATCH)
====

When a task can be broken into INDEPENDENT sub-tasks with no data dependency
between them, you MAY dispatch them as a parallel batch. ParallelToolStepExecutor
will execute them concurrently.

RULES:
  - Only parallelize when sub-tasks are genuinely independent.
    If sub-task B needs output from sub-task A → sequential only.
  - Each parallel goal must still be fully self-contained (Section 3).
  - Maximum practical batch size: 4 concurrent agents.

EXAMPLE (correct parallel):
  Sub-task 1 → saraswati: "Summarize the README of rsrini7/ArcClaw in 3 bullets."
  Sub-task 2 → shiva:     "Analyze stack trace [paste here] and identify root cause."
  Independent — dispatch together.

EXAMPLE (wrong — dependency):
  Sub-task 1 → saraswati: "Fetch the README of rsrini7/ArcClaw."
  Sub-task 2 → brihaspati: "Write an ADR based on the README fetched above."
  Sub-task 2 DEPENDS on sub-task 1 → sequential only.


====
SECTION 5 — WHEN TO INVOKE DATTATREYA
====

Invoke Dattatreya ONLY when:
  - You have received results from 2+ specialists and they need reconciliation.
  - Specialist findings conflict and you need explicit conflict resolution.
  - The task simultaneously requires code, debug, AND research/architecture.

DO NOT invoke Dattatreya:
  - For single-specialist tasks. Route directly.
  - As a default catch-all.
  - When you have already synthesized results yourself.

When invoking Dattatreya, embed ALL findings in the goal:
  WRONG: "Synthesize the results from the agents above."
  RIGHT:  "Synthesize: Brahma found [paste]. Shiva found [paste].
           Saraswati found [paste]. Return a unified recommendation."

NOTE: AgentStateMachineRunner automatically routes FINISH through
Dattatreya's synthesize() method when role='synthesizer'. You only need
to explicitly call Dattatreya when merging multi-agent mid-session findings.

====
SECTION 6 — APPROVAL CHECKPOINTS
====

Certain tool calls require human approval before execution. This is enforced
by ApprovalCheckpointManager and is NOT negotiable — not by you, not by the
specialist agent.

When a sub-agent's execution is paused for approval:
  - The runtime returns AgentResult with status AWAITING_APPROVAL.
  - You will see this as a terminal result from delegate_to_agent.
  - You MUST NOT retry the delegation.
  - You MUST NOT attempt the tool call yourself as a workaround.
  - You MUST surface the approval request to the user exactly as returned.
  - Do NOT say "I'll try a different approach." The pause is intentional.

After the user approves and the session resumes:
  - The checkpoint replays the approved tool call automatically.
  - You will receive the actual tool result in the resumed session context.
  - Continue coordination from that point — do not re-delegate the same goal.


====
SECTION 7 — READONLY MODE
====

When AutonomyLevel is READONLY (enforced in ToolStepHandler.evaluateGuard()):
  - ALL tool calls are blocked at the runtime level — including delegate_to_agent.
  - Do NOT attempt to delegate tasks that require tool execution.
  - Do NOT tell the user "I am delegating this" and then fail — check the
    autonomy level first and report the constraint honestly.
  - Answer using only knowledge present in the conversation context or the
    <context> block.
  - If the task cannot be answered without tools, say so and explain which
    tool would be needed if autonomy were restored.


====
SECTION 8 — FAST-PATH EXIT AWARENESS
====

The following tools produce TERMINAL fast-path exits in the runtime.
When you call them — directly or via a delegated agent — the loop ends
immediately after the tool call. No further LLM reasoning occurs in that turn.

  schedule_task      → ALWAYS terminal. The cron is handed to the scheduler.
                       The session ends with a confirmation message.

  manage_schedule    → Terminal for all actions EXCEPT list when the user's
                       goal contains a mutating intent (delete, cancel, etc.).
                       Pure list requests fast-path to exit with the list output
                       directly — the LLM never sees it.

IMPLICATION FOR DELEGATION:
  - Do NOT delegate schedule_task or manage_schedule to another agent.
    Call them YOURSELF (see Section 2).
  - Do NOT embed reasoning or synthesis steps AFTER one of these calls.
    Any logic that depends on their result must be computed BEFORE the call.
  - If a delegate_to_agent goal would cause the inner agent to call
    schedule_task, the inner loop terminates and returns a FINISHED result
    to you. This is expected. Treat it as a normal delegation response.


====
SECTION 9 — LOOP BUDGET & SCHEMA ERRORS
====

The runtime enforces maxIterations per session. Each delegation call and each
tool call consume iterations from the same budget.

  - Every delegate_to_agent call costs at minimum 1 outer iteration
    (the tool call itself) plus however many iterations the inner agent uses.
    A 3-hop chain (coordinator → specialist → Dattatreya) can consume 10+
    iterations. Plan delegation depth accordingly.
  - Avoid chains longer than 3 hops in a single session.
  - If a sub-agent returns LOOP_EXCEEDED:
      → Simplify the goal — break it into smaller steps.
      → Do NOT retry the same goal unchanged.
  - If a sub-agent returns SCHEMA_ERROR (self-correction exhausted):
      → Rephrase the goal with more explicit parameter structure.
      → Retry ONCE only.
  - Consecutive tool failures (2 in a row) trip the circuit breaker in
    ToolStepHandler. If you see a failure observation, correct the goal
    before the next delegation attempt.


====
SECTION 10 — COMPACTION SAFETY
====

The runtime uses CompactionEventBridge to block message reads during in-flight
compaction. You do not interact with this directly. However:

  - If a sub-agent returns a suspiciously short answer ("Done.", "OK.", ✅ Done.)
    the ANSWER-GUARD in AgentStateMachineRunner already suppressed it and
    returned a retry prompt internally. You will see "⚠️ Context was lost
    mid-response — please resend your request." as the delegation result.
  - In this case, re-delegate the SAME goal ONCE. Compaction will have
    fully committed by the time the retry executes.
  - Do NOT re-delegate more than once for this reason.
    If it fails twice, surface the context issue to the user:
    "The session context was temporarily unavailable. Please try again."
  - This situation is expected after high-traffic compaction cycles. It is
    not a bug in the specialist — it is a transient infrastructure condition.


====
SECTION 11 — DELEGATION RESULT HANDLING
====

When delegate_to_agent returns an observation:

  FINISHED (non-empty answer):
    → Use the answer directly, or synthesize with other results.
    → If the answer is coherent and complete, surface it to the user.

  FINISHED ("⚠️ Context was lost mid-response"):
    → Compaction race — re-delegate once (see Section 10).

  LOOP_EXCEEDED:
    → Sub-agent ran out of iterations.
    → Simplify the goal, decompose into smaller tasks, and retry.

  FAILED — FailureCategory.SCHEMA_ERROR:
    → Sub-agent could not produce valid structured output.
    → Rephrase the goal with more explicit, tightly bounded instructions.
    → Retry once.

  FAILED — FailureCategory.SYSTEM_ERROR:
    → Infrastructure failure (sandbox offline, OOM, etc.).
    → Do NOT retry automatically.
    → Surface to user: "The execution environment is currently unavailable.
      Please try again in a moment."

  AWAITING_APPROVAL:
    → Human-in-the-loop checkpoint triggered.
    → Surface the approval request verbatim. Do NOT retry. See Section 6.

NEVER present a raw internal error observation to the user.
Always translate tool failure text into a clear, actionable message.


====
QUICK DECISION TREE
====

  User request arrives
       │
       ├─ Scheduling / cron intent?
       │    └─ YES → call schedule_task / manage_schedule YOURSELF. STOP.
       │
       ├─ Memory lookup only?
       │    └─ YES → call memory_search YOURSELF. STOP.
       │
       ├─ Single GitHub fetch only?
       │    └─ YES → call github YOURSELF. STOP.
       │
       ├─ Document ingest?
       │    └─ YES → call ingest_document YOURSELF. STOP.
       │
       ├─ User EXPLICITLY names a specialist or deity alias?
       │    └─ YES → call delegate_to_agent with that agent.
       │             Materialise the goal fully (Section 3). STOP.
       │
       ├─ Code GENERATION from scratch?
       │    └─ YES → delegate to brahma.
       │             NOTE: brahma has ONLY sandbox_exec.
       │             Embed ALL context — he cannot fetch from GitHub.
       │
       ├─ Bug / stack trace ANALYSIS?
       │    └─ YES → delegate to shiva.
       │             shiva has github + sandbox_exec.
       │             Paste the stack trace directly into the goal.
       │
       ├─ RESEARCH / documentation / factual lookup?
       │    └─ YES → delegate to saraswati.
       │
       ├─ ARCHITECTURAL review / ADR?
       │    └─ YES → delegate to brihaspati.
       │
       ├─ Requires ALL THREE (code + debug + research)?
       │    └─ YES → Parallelise to brahma + shiva + saraswati (Section 4),
       │             then delegate merged findings to dattatreya (Section 5).
       │
       └─ Cannot categorise?
            └─ Answer directly with available context.
               Do NOT delegate speculatively.
               Tool silence is always correct.
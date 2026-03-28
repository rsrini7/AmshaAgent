# PROMPT — PHASE 76: Agent Delegation (delegate_to_agent tool + routing fix)

Role: Senior Java Architect & Senior Java Developer  
Task: Implement end-to-end agent delegation so the coordinator can explicitly
invoke a named specialist agent with a delegated goal. The bug is confirmed in
live production logs: "ask Shiva to identify what my name now" routed to
researcher instead of coordinator, and coordinator called github twice when
asked to delegate because no delegation tool exists.

---

## Architecture Context

Refer @Architecture_Invariants.md §2 (context layers), §3.2 (tool invocation path).
Refer @ScopedValues-Binding-Rules.md for WorkspaceContext vs WorkspaceScopeHolder rules.
Refer @arcclaw-core/src/main/resources/templates/prompts/role-coordinator.txt

Tool path invariant: all tool execution MUST flow through ToolRegistry → ToolInvoker.
The delegate_to_agent tool calls AgentLoop.run() internally — this is intentional
and does NOT violate the invariant because the inner loop runs in the same
WorkspaceContext as the outer coordinator turn.

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

### Component 1 — ToolCapability.DELEGATE_TO_AGENT

**File**: `arcclaw-core/src/main/java/com/arcclaw/core/agent/role/ToolCapability.java`

Add a new enum constant:

```java
DELEGATE_TO_AGENT(
    "delegate_to_agent",
    "Delegates a task to a named specialist agent. " +
    "Use when the user explicitly asks to involve a specific agent (e.g. 'ask Shiva', " +
    "'have Brahma write this'). " +
    "Parameters: {\"agent\": \"<role_name>\", \"goal\": \"<clear task description>\"}. " +
    "Valid agent names: coordinator, researcher, coder, debugger, architect, synthesizer. " +
    "Deity aliases also accepted: vishnu, saraswati, brahma, shiva, brihaspati, dattatreya."
)
```

Add `DELEGATE_TO_AGENT` to `COORDINATOR`'s capability set in `AgentRoleEnum`:

```java
COORDINATOR("coordinator", new RoleDefinition(
    "coordinator",
    "Vishnu (Coordinator)",
    "Orchestrates sub-agents, manages session state, and schedules background tasks.",
    7,
    true,
    Set.of(
        ToolCapability.GITHUB,
        ToolCapability.SCHEDULE_TASK,
        ToolCapability.MANAGE_SCHEDULE,
        ToolCapability.MEMORY_SEARCH,
        ToolCapability.INGEST_DOCUMENT,
        ToolCapability.DELEGATE_TO_AGENT   // ← add this
    )
))
```

**Test**: `ToolCapabilityTest.testDelegateToAgentCapabilityExistsInCoordinator()` — verify
`AgentRoleEnum.COORDINATOR.definition().capabilities()` contains `DELEGATE_TO_AGENT`.

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
 * coordinator turn — this is intentional and correct. The inner role is
 * resolved from the agent parameter and validated against AgentRoleEnum.
 *
 * <p>BudgetExceededException is re-thrown so the outer coordinator loop
 * terminates correctly — do NOT catch it here.
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
                "The specialist agent role to delegate to. " +
                "Valid values: coordinator, researcher, coder, debugger, architect, synthesizer. " +
                "Deity aliases also accepted: vishnu, saraswati, brahma, shiva, brihaspati, dattatreya.")
            .addStringProperty("goal",
                "The specific task or question the specialist agent should address. " +
                "Be explicit and self-contained — the specialist does not see the original " +
                "conversation context, only this goal.")
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

        // Resolve the agent role — validates the name and handles deity aliases.
        AgentRoleEnum targetRole;
        try {
            targetRole = AgentRoleEnum.fromString(agentParam);
        } catch (IllegalArgumentException e) {
            return ToolResponse.failure(400,
                "Unknown agent '" + agentParam + "'. " +
                "Valid agents: coordinator, researcher, coder, debugger, architect, synthesizer. " +
                "Deity aliases: vishnu, saraswati, brahma, shiva, brihaspati, dattatreya.");
        }

        String sessionId = WorkspaceContext.requireSessionId();

        log.infof("[DELEGATE] Coordinator delegating to agent=%s goal='%s' session=%s",
                  targetRole.toScopedName(), goal, sessionId);

        // BudgetExceededException MUST propagate — do not catch it here.
        AgentResult result = agentLoop.run(
            sessionId,
            targetRole.toScopedName(),
            goal,
            reasoningEngine,
            targetRole.definition().maxIterations()
        );

        String formatted = AgentResultFormatter.format(result);
        log.infof("[DELEGATE] Agent=%s completed delegation for session=%s",
                  targetRole.toScopedName(), sessionId);
        return ToolResponse.success(formatted);
    }
}
```

**Test**: `DelegateToAgentToolTest` — mock `AgentLoop` to return `AgentResult.finished("Sri", List.of())`.
Invoke with `{agent: "debugger", goal: "What is the user's name?"}`.
Verify `ToolResponse.success()` is returned containing "Sri".
Verify that an unknown agent name returns `ToolResponse.failure(400, ...)`.
Verify that `BudgetExceededException` from the inner loop propagates uncaught.

---

### Component 3 — DelegationRoutingProvider (fast-path to coordinator)

**New file**: `arcclaw-core/src/main/java/com/arcclaw/core/routing/DelegationRoutingProvider.java`

**Problem from logs**: "ask Shiva to identify what my name now" was classified as
`researcher` (SLA=149ms) by the LLM because it saw "identify" as a research intent
and ignored the delegation wrapper. The fix: detect delegation phrasing early as a
fast-path trigger and route to `coordinator` so Vishnu handles the delegation.

```java
package com.arcclaw.core.routing;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.agent.role.AgentRoleEnum;

/**
 * Fast-path routing provider that detects explicit agent delegation requests.
 *
 * Matches patterns like:
 *   "ask Shiva to ..."
 *   "tell Brahma ..."
 *   "have Saraswati ..."
 *   "delegate to debugger"
 *   "get Brihaspati to ..."
 *
 * Routes ALL such requests to coordinator so Vishnu can use delegate_to_agent.
 * Without this, the LLM classifier sees the inner task intent (e.g. "identify")
 * and routes directly to the specialist, bypassing the coordinator entirely.
 *
 * Priority 5 — evaluated BEFORE SchedulingRoutingProvider (10) and all others.
 */
@ApplicationScoped
public class DelegationRoutingProvider implements IntentRoutingProvider {

    // Deity names and canonical role names, all lowercase
    private static final String AGENT_NAMES =
        "shiva|brahma|saraswati|vishnu|brihaspati|brahaspati|dattatreya|datta|" +
        "coordinator|researcher|coder|debugger|architect|synthesizer";

    private static final Pattern DELEGATION_PATTERN = Pattern.compile(
        "(?i)\\b(ask|tell|have|get|delegate\\s+to|ask\\s+that|instruct)\\s+" +
        "(" + AGENT_NAMES + ")\\b"
    );

    @Override
    public List<Pattern> fastPathTriggers() {
        return List.of(DELEGATION_PATTERN);
    }

    @Override
    public List<String> promptDirectives() {
        return List.of(
            "DELEGATION IS HIGHEST PRIORITY — evaluated before any other rule: " +
            "If the user message asks to involve a specific agent by name or deity alias " +
            "(e.g. 'ask Shiva', 'tell Brahma', 'have Saraswati', 'delegate to debugger'), " +
            "you MUST route to 'coordinator'. " +
            "The coordinator owns all agent delegation requests."
        );
    }

    @Override
    public Optional<String> fastPathRole() {
        return Optional.of(AgentRoleEnum.COORDINATOR.toScopedName());
    }

    @Override
    public int priority() {
        return 5; // Highest priority — before scheduling (10) and all others
    }
}
```

**Test**: `DelegationRoutingProviderTest`
```java
@Test
void testAskShivaMatchesDelegation() {
    var provider = new DelegationRoutingProvider();
    List<Pattern> triggers = provider.fastPathTriggers();
    
    assertThat(triggers).anyMatch(p ->
        p.matcher("ask Shiva to identify what my name now").find());
    assertThat(triggers).anyMatch(p ->
        p.matcher("tell Brahma to write this code").find());
    assertThat(triggers).anyMatch(p ->
        p.matcher("have Saraswati research this topic").find());
    assertThat(triggers).anyMatch(p ->
        p.matcher("delegate to debugger").find());
    assertThat(triggers).anyMatch(p ->
        p.matcher("ask Shiva delegate").find());
    
    // Must NOT match normal messages
    assertThat(triggers).noneMatch(p ->
        p.matcher("what is my name?").find());
    assertThat(triggers).noneMatch(p ->
        p.matcher("remind me in 5 minutes").find());
    
    assertThat(provider.fastPathRole()).contains("coordinator");
    assertThat(provider.priority()).isEqualTo(5);
}
```

---

### Component 4 — Coordinator system prompt — delegation rules

**File**: `arcclaw-core/src/main/resources/templates/prompts/role-coordinator.txt`

Find the existing `STRICT RULES:` section header and insert this block immediately BEFORE it:

```
DELEGATION RULES (MANDATORY — read before any other rules):

You have access to the delegate_to_agent tool. Use it when:
- The user explicitly names a specialist agent: "ask Shiva", "tell Brahma", "have Saraswati",
  "get Brihaspati to", "delegate to debugger", or any deity alias.
- CALL delegate_to_agent with:
    {"agent": "<exact role or deity name the user mentioned>",
     "goal": "<the specific task extracted from the user's message>"}
- Do NOT pre-fetch data on behalf of the specialist. Pass the goal as-is.
  Let the specialist decide which tools to call.
- Do NOT call github, memory_search, or any other tool as a proxy for delegation.
  If the user says "ask Shiva" and does NOT mention GitHub, do not call github.

When you CANNOT delegate (delegate_to_agent is not in your available tools list):
- Tell the user plainly: "I cannot directly invoke [agent] right now.
  Please send your message directly or rephrase."
- Do NOT attempt to simulate delegation by calling other tools.

CHAIN OF REASONING for delegation:
  User: "ask Shiva to identify what my name now"
  → Delegation keyword detected: "ask Shiva"
  → Call: delegate_to_agent({"agent": "shiva", "goal": "identify what the user's name is now"})
  → Return Shiva's answer to the user.
  
  WRONG: Call memory_search or github on behalf of Shiva.
  WRONG: Answer the question yourself without delegating.
```

**Test**: `DelegateToAgentIntegrationTest` — use a mock `AgentLoop` that records the role it was called with. Send goal `"ask Shiva to identify what my name now"` through the full `MessagePipeline` with a stubbed `IntentClassifierService` returning `coordinator`. Mock `ToolRegistry` to intercept `delegate_to_agent` invocation. Verify:
1. `DelegationRoutingProvider` fast-path fires and routes to coordinator.
2. Coordinator receives the message.
3. `delegate_to_agent` tool is available in `ToolRegistry.listTools()`.

---

### Component 5 — ToolStepHandler coordinator-github misuse guard

**File**: `arcclaw-core/src/main/java/com/arcclaw/core/agent/loop/ToolStepHandler.java`

In `validateTool()`, add this check AFTER the ReAct keyword check and BEFORE the hallucinated-tool check:

```java
// Guard: coordinator calling github when goal has no GitHub resource.
// From logs: coordinator called github for "ask Shiva delegate" and
// "delegate say my name from Shiva" — both pure delegation requests.
if (ToolCapability.GITHUB.toolName().equals(toolName)
        && AgentRoleEnum.COORDINATOR.toScopedName().equals(context.agentRole())
        && !goalMentionsGitHubResource(context.goal())) {
    log.warnf("[ToolStepHandler] Coordinator called github for non-github goal '%s' " +
              "session=%s — injecting delegation correction.",
              context.goal(), context.sessionId());
    return StepDecision.retryEngine(
        "System: You called the github tool, but the user's goal contains no GitHub " +
        "repository, gist, or issue reference. " +
        "If the user asked you to involve a specialist agent (e.g. 'ask Shiva', " +
        "'delegate'), use the delegate_to_agent tool instead. " +
        "If the answer is already in the conversation context, use FINISH directly " +
        "with the answer.",
        llmSchemaErrorCount + 1
    );
}
```

Add the helper method (private, package-accessible for tests):

```java
private static final Pattern GITHUB_RESOURCE_PATTERN = Pattern.compile(
    "(?i)\\b(repo|repository|github|gh\\s|gist|issue|pull.?request|pr\\b|" +
    "commit|branch|fork|clone|push|rsrini7/|arcclaw/)\\b"
);

private boolean goalMentionsGitHubResource(String goal) {
    if (goal == null || goal.isBlank()) return false;
    return GITHUB_RESOURCE_PATTERN.matcher(goal).find();
}
```

Note: the method signature must thread `llmSchemaErrorCount` through from the caller.
Check that `validateTool()` already receives `llmSchemaErrorCount` or add it as a parameter.
If not present as a parameter, use `0` as the initial value — the self-correction budget
will still apply correctly.

**Test**: `ToolStepHandlerDelegationGuardTest`
```java
@Test
void testCoordinatorGithubCallForDelegationGoalIsIntercepted() {
    // Arrange: coordinator role, goal = "ask Shiva delegate", tool = github
    AgentExecutionContext context = AgentExecutionContext.of(
        "session-test", "coordinator",
        "ask Shiva delegate", 5);
    ReasoningStep step = ReasoningStep.pending("delegating", "github", Map.of("action","repo"), 0, 0);
    
    // Act
    StepDecision decision = handler.validateTool(context, List.of(), step, 0);
    
    // Assert: should be a retryEngine correction, not terminal or proceed
    assertThat(decision.type()).isEqualTo(DecisionType.CONTINUE);
    assertThat(decision.transientError()).contains("delegate_to_agent");
}

@Test  
void testCoordinatorGithubCallForLegitimateGithubGoalProceeds() {
    AgentExecutionContext context = AgentExecutionContext.of(
        "session-test", "coordinator",
        "show me my github repo rsrini7/arcclaw", 5);
    ReasoningStep step = ReasoningStep.pending("fetching", "github", Map.of("action","repo"), 0, 0);
    
    StepDecision decision = handler.validateTool(context, List.of(), step, 0);
    
    // Should NOT be intercepted — goal mentions github resource
    assertThat(decision.type()).isNotEqualTo(DecisionType.CONTINUE);
}
```

---

## Verification

```bash
./mvnw clean test -pl arcclaw-core,arcclaw-app -am \
  -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -10
```

Expected: 800+ tests, 0 failures.

**Manual smoke test after deploy** — send to Telegram in order:
1. `ask Shiva what is 2+2` → Should see coordinator route, then `delegate_to_agent` tool call paused for approval, approve, Shiva answers.
2. `tell Brahma to write hello world in Python` → Coordinator delegates to coder, code appears.
3. `ask Shiva delegate` → No github tool call. Either delegate_to_agent fires or coordinator asks for clarification on what to delegate.

Check logs for:
- `DelegationRoutingProvider` fast-path firing on "ask Shiva" messages
- `[DELEGATE] Coordinator delegating to agent=debugger` log line
- NO `github` tool calls for delegation-only goals

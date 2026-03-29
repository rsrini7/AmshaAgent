package com.arcclaw.core.routing;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.agent.role.AgentRoleEnum;

/**
 * Ensures that natural language delegation requests (e.g., "ask shiva to fix this")
 * are routed to the Coordinator so it can use the delegate_to_agent tool.
 * Priority 5 ensures it runs after strict overrides but before fallback logic.
 */
@ApplicationScoped
public class DelegationRoutingProvider implements IntentRoutingProvider {

    // vishnu and coordinator are intentionally excluded:
    // (1) Routing "ask vishnu" to coordinator would create a self-loop
    //     (coordinator delegates to itself).
    // (2) DelegateToAgentTool already guards against self-delegation with a
    //     400 failure. Excluding here is the first line of defense.
    // Do NOT add vishnu or coordinator here.
    private static final String AGENT_NAMES =
        "shiva|brahma|saraswati|brihaspati|brahaspati|dattatreya|datta|" +
        "researcher|coder|debugger|architect|synthesizer";

    private static final Pattern DELEGATION_PATTERN = Pattern.compile(
        "(?i)\\b(ask|tell|have|get|delegate\\s+to|instruct)\\s+(the\\s+)?(" + 
        AGENT_NAMES + ")\\b"
    );

    @Override
    public int priority() {
        return 5;
    }

    @Override
    public List<Pattern> fastPathTriggers() {
        return List.of(DELEGATION_PATTERN);
    }

    @Override
    public Optional<String> fastPathRole() {
        return Optional.of(AgentRoleEnum.COORDINATOR.toScopedName());
    }

    @Override
    public List<String> promptDirectives() {
        return List.of(
            "If the user message asks a named specialist agent (shiva, brahma, saraswati, " +
            "brihaspati, dattatreya, or role equivalents: debugger, coder, researcher, " +
            "architect, synthesizer) to perform a task — route to 'coordinator'. " +
            "The coordinator will delegate to the specialist. Do NOT route directly.",
            "If the user says 'delegate to', 'ask X to', 'have X', 'tell X to' where X is " +
            "any agent name — route to 'coordinator' always."
        );
    }
}

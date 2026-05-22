package com.arcclaw.core.routing;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.agent.role.AgentPersona;
import com.arcclaw.core.agent.role.AgentRoleEnum;

/**
 * Ensures that natural language delegation requests (e.g., "ask shiva to fix this")
 * are routed to the Coordinator so it can use the garuda tool.
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
    private static final String AGENT_NAMES = Arrays.stream(AgentPersona.values())
            .filter(p -> p != AgentPersona.VISHNU)
            .flatMap(p -> Stream.concat(
                    Stream.of(p.name().toLowerCase(), p.roleId()),
                    p.aliases().stream()))
            .distinct()
            .collect(Collectors.joining("|"));

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
        String namesList = Arrays.stream(AgentPersona.values())
                .filter(p -> p != AgentPersona.VISHNU)
                .map(p -> String.format("%s (%s)", p.displayName(), p.roleId()))
                .collect(Collectors.joining(", "));

        return List.of(
            "If the user message asks a named specialist agent (" + namesList + ") " +
            "to perform a task - route to 'coordinator'. " +
            "The coordinator will delegate to the specialist. Do NOT route directly.",
            "If the user says 'delegate to', 'ask X to', 'have X', 'tell X to' where X is " +
            "any agent name - route to 'coordinator' always."
        );
    }
}

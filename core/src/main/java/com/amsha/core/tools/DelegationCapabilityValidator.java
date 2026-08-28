package com.amsha.core.tools;

import static com.amsha.core.message.UserMessages.delegationCapabilityUnavailable;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.tool.api.ToolCapability;

/**
 * Validates capability names explicitly requested inside a delegation goal.
 *
 * <p>The validator is deliberately separate from the delegation executor. The
 * outer {@code garuda} tool may be approved, but that approval never grants a
 * child role a tool that its own role definition does not permit. The
 * pre-approval scan is limited to nested tools that can escalate execution
 * authority or create another delegation boundary. The child's normal tool
 * guard remains authoritative for every other capability.
 */
final class DelegationCapabilityValidator {

    private static final String PARAM_AGENT = "agent";
    private static final String PARAM_GOAL = "goal";
    private static final Set<String> PRE_APPROVAL_TOOLS = Set.of(
            ToolCapability.HOST_EXEC.toolName(),
            ToolCapability.SANDBOX_EXEC.toolName(),
            ToolCapability.REGISTER_MCP_SERVER.toolName(),
            ToolCapability.DELEGATE_TO_AGENT.toolName());

    private DelegationCapabilityValidator() {}

    static String validate(Map<String, Object> params, RoleRegistry roleRegistry) {
        if (params == null || roleRegistry == null) {
            return null;
        }

        Object agentValue = params.get(PARAM_AGENT);
        Object goalValue = params.get(PARAM_GOAL);
        if (agentValue == null || goalValue == null) {
            return null;
        }

        String requestedAgent = DelegationProcessor.stripSurroundingQuotes(String.valueOf(agentValue));
        String goal = String.valueOf(goalValue);
        if (requestedAgent == null || requestedAgent.isBlank() || goal.isBlank()) {
            return null;
        }

        Set<String> permittedTools;
        try {
            permittedTools = roleRegistry.resolvePermittedTools(requestedAgent);
        } catch (IllegalArgumentException e) {
            // normalizeAgent() owns the user-safe unknown-role response.
            return null;
        }
        if (permittedTools == null) {
            permittedTools = Set.of();
        }

        for (String nestedTool : PRE_APPROVAL_TOOLS) {
            if (containsToolToken(goal, nestedTool) && !permittedTools.contains(nestedTool)) {
                return delegationCapabilityUnavailable(requestedAgent, nestedTool);
            }
        }
        return null;
    }

    private static boolean containsToolToken(String goal, String toolName) {
        String lowerGoal = goal.toLowerCase(Locale.ROOT);
        String lowerTool = toolName.toLowerCase(Locale.ROOT);
        int fromIndex = 0;
        while (fromIndex < lowerGoal.length()) {
            int matchIndex = lowerGoal.indexOf(lowerTool, fromIndex);
            if (matchIndex < 0) {
                return false;
            }
            int endIndex = matchIndex + toolName.length();
            boolean leftBoundary = matchIndex == 0 || !isToolNameCharacter(goal.charAt(matchIndex - 1));
            boolean rightBoundary = endIndex == goal.length() || !isToolNameCharacter(goal.charAt(endIndex));
            if (leftBoundary && rightBoundary) {
                return true;
            }
            fromIndex = endIndex;
        }
        return false;
    }

    private static boolean isToolNameCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }
}

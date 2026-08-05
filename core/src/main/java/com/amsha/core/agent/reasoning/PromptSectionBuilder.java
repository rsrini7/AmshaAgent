package com.amsha.core.agent.reasoning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import com.amsha.core.agent.loop.AgentExecutionContext;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.role.CapabilityRegistry;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.tool.DynamicToolInvoker;
import com.amsha.core.tools.AgentTool;
import com.amsha.core.tools.ToolRegistry;

/**
 * Shared logic for building common prompt sections (permitted tools, contamination guards, etc.).
 */
@ApplicationScoped
public class PromptSectionBuilder {

    private final ToolRegistry toolRegistry;
    private final CapabilityRegistry capabilityRegistry;

    @Inject
    Instance<DynamicToolInvoker> dynamicToolInvokers;

    @Inject
    public PromptSectionBuilder(ToolRegistry toolRegistry, CapabilityRegistry capabilityRegistry) {
        this.toolRegistry = toolRegistry;
        this.capabilityRegistry = capabilityRegistry;
    }

    protected PromptSectionBuilder() {
        this(null, null);
    }

    private static final String EXECUTION_BOUNDARY_SANDBOX = """
            EXECUTION BOUNDARY:
            You CAN execute code using the `sandbox_exec` tool, which runs code in a
            secure, isolated container. When a user asks you to "execute", "run",
            "compile and run", or "just do it" with a code snippet, use `sandbox_exec`
            with the appropriate `language` parameter.
            Supported languages: python, python3, bash, node, java, go.
            """;

    private static final String EXECUTION_BOUNDARY_NO_SANDBOX = """
            EXECUTION BOUNDARY:
            You cannot execute, run, or compile code directly in this role. If a user asks you to "execute", "run", "just do it", or
            "compile and run" any code, you MUST use `garuda(agent=brahma)` to handle the execution.
            """;

    private static final String EXECUTION_BOUNDARY_HOST = """
            EXECUTION BOUNDARY:
            You CAN execute code using the `host_exec` tool, which runs code directly on the
            host machine. When a user asks you to "execute", "run", "compile and run",
            or "just do it" with a code snippet, use `host_exec` with the appropriate
            `code` and `language` parameters.
            Supported languages: python, python3, bash, node, java, go.
            """;

    private static final String COMPACT_EXECUTION_BOUNDARY_HOST =
            "EXECUTION BOUNDARY: Use `host_exec` for run/execute/compile.\n";

    private static final String COMPACT_EXECUTION_BOUNDARY_SANDBOX =
            "EXECUTION BOUNDARY: Use `sandbox_exec` for run/execute/compile.\n";
    private static final String COMPACT_EXECUTION_BOUNDARY_NO_SANDBOX =
            "EXECUTION BOUNDARY: You cannot run code directly. If you are a skill role, use your permitted execution tool; otherwise hand off to the appropriate specialist execution role.\n";

    private static final String COMPACT_BROWSER_RETRY_RULE =
            "BROWSER EXTRACTION RETRY RULE: If `browser_navigate` extraction returns too little text, retry once with a broader selector.\n";

    public String buildExecutionBoundaryRule(AgentRoleEnum roleEnum, boolean isCompact) {
        boolean hasHost = capabilityRegistry.hasCapability(roleEnum.id(), ToolCapability.HOST_EXEC.toolName());
        if (hasHost) {
            if (isCompact) {
                return COMPACT_EXECUTION_BOUNDARY_HOST;
            }
            return EXECUTION_BOUNDARY_HOST;
        }
        boolean hasSandbox = capabilityRegistry.hasCapability(roleEnum.id(), ToolCapability.SANDBOX_EXEC.toolName());
        if (isCompact) {
            return hasSandbox ? COMPACT_EXECUTION_BOUNDARY_SANDBOX : COMPACT_EXECUTION_BOUNDARY_NO_SANDBOX;
        }
        return hasSandbox ? EXECUTION_BOUNDARY_SANDBOX : EXECUTION_BOUNDARY_NO_SANDBOX;
    }

    public String buildBrowserRetryRule(AgentExecutionContext context, AgentRoleEnum roleEnum, boolean isCompact) {
        boolean hasBrowser =
                getAvailableToolNames(context, roleEnum).contains(ToolCapability.BROWSER_NAVIGATE.toolName());
        if (!hasBrowser) return "";
        if (isCompact) return COMPACT_BROWSER_RETRY_RULE;

        return """
                BROWSER EXTRACTION RETRY RULE:
                - If a browser extraction (`browser_navigate` with action=extract_text) returns fewer
                  than 300 characters, the CSS selector likely failed or targeted a shell element.
                - Attempt a second call with a broader selector (e.g. "h3", "article", or "body").
                """;
    }

    public String buildToolContaminationGuard(
            AgentExecutionContext context, AgentRoleEnum roleEnum, boolean isCompact) {
        String toolList = getAvailableToolNames(context, roleEnum).stream()
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(", "));

        if (toolList.isBlank()) {
            return "HARD TOOL OVERRIDE: You have NO external tools available in this turn.\n";
        }

        if (isCompact) {
            return "Only these tools may be called in this turn: " + toolList + ".\n";
        }

        return "HARD TOOL OVERRIDE - CROSS-TURN CONTAMINATION PREVENTION:\n"
                + "Your ONLY available tools in this turn are: " + toolList + ".\n";
    }

    public List<String> getAvailableToolNames(AgentExecutionContext context, AgentRoleEnum roleEnum) {
        boolean isBackground = context != null && context.autonomyLevel() == AutonomyLevel.BACKGROUND;
        List<String> names = new ArrayList<>(capabilityRegistry.getSupplementalTools(roleEnum.id()));
        if (dynamicToolInvokers != null && !dynamicToolInvokers.isUnsatisfied()) {
            dynamicToolInvokers.stream()
                    .flatMap(catalog -> catalog.listTools().stream())
                    .map(tool -> tool.name())
                    .forEach(names::add);
        }
        return names.stream()
                .distinct()
                .filter(name -> !isBackground
                        || (!ToolCapability.SCHEDULE_TASK.toolName().equals(name)
                                && !ToolCapability.MANAGE_SCHEDULE.toolName().equals(name)))
                .filter(this::isVisibleDynamicOrStaticTool)
                .toList();
    }

    private boolean isVisibleDynamicOrStaticTool(String name) {
        return isDynamicTool(name)
                || toolRegistry.findTool(name).map(AgentTool::isEnabled).orElse(false);
    }

    private boolean isDynamicTool(String name) {
        return dynamicToolInvokers != null
                && !dynamicToolInvokers.isUnsatisfied()
                && dynamicToolInvokers.stream()
                        .anyMatch(catalog -> catalog.findTool(name).isPresent());
    }
}

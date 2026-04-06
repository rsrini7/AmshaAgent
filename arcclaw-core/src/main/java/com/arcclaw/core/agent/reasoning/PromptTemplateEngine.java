package com.arcclaw.core.agent.reasoning;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.loop.AgentExecutionContext;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.tools.ToolRegistry;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import org.jboss.logging.Logger;

/**
 * Builds role-specific system prompt section using Qute templates.
 * <p>
 * Called by {@link LangChainReasoningEngine} to replace the hardcoded
 * {@code getRoleBinding()} switch. Templates live at:
 * {@code arcclaw-core/src/main/resources/templates/prompts/role-*.txt}
 * <p>
 * <p>
 * Unknown roles throw IllegalStateException at runtime rather than silently
 * falling back to coordinator tool rules.
 */
@ApplicationScoped
public class PromptTemplateEngine {

    private static final Logger log = Logger.getLogger(PromptTemplateEngine.class);

    @Inject
    @Location("prompts/role-coordinator")
    Template coordinatorTemplate;

    @Inject
    @Location("prompts/role-researcher")
    Template researcherTemplate;

    @Inject
    @Location("prompts/role-coder")
    Template coderTemplate;

    @Inject
    @Location("prompts/role-debugger")
    Template debuggerTemplate;

    @Inject
    @Location("prompts/role-architect")
    Template architectTemplate;

    @Inject
    @Location("prompts/role-synthesizer")
    Template synthesizerTemplate;

    @Inject
    ToolRegistry toolRegistry;

    /**
     * Renders the system prompt section for the given role.
     *
     * @param role              the agent role
     * @param compactionSummary prior session summary, or {@code null} if none
     * @param systemTimeUtc     current UTC time, or {@code null} (coordinator falls back to empty string)
     * @return the rendered prompt string
     */
    public String buildSystemPrompt(AgentRoleEnum role, String compactionSummary, Instant systemTimeUtc) {
        return buildSystemPrompt(null, role, compactionSummary, systemTimeUtc);
    }

    /**
     * Renders the system prompt section with full context data.
     */
    public String buildSystemPrompt(AgentExecutionContext context, AgentRoleEnum role, String compactionSummary, Instant systemTimeUtc) {
        if (role == null) {
            log.warn("buildSystemPrompt called with null role — returning fallback");
            return "PERMITTED TOOLS: None";
        }

        // Sort capabilities for deterministic LLM prompt ordering
        // (RoleDefinition.capabilities() returns an immutable Set — order is undefined).
        // HARD FILTER: only include tools that are actually enabled in the registry.
        // This prevents hallucinations where the LLM tries to call a tool that lacks API keys.
        boolean isBackground = context != null && context.autonomyLevel() == com.arcclaw.core.security.AutonomyLevel.BACKGROUND;
        List<ToolCapability> sortedCapabilities = role.definition().capabilities()
                .stream()
                .filter(cap -> toolRegistry.findTool(cap.toolName()).isPresent())
                .filter(cap -> !isBackground || 
                        (!"schedule_task".equals(cap.toolName()) && 
                         !"manage_schedule".equals(cap.toolName())))
                .sorted(Comparator.comparing(ToolCapability::toolName))
                .toList();

            String timeString = "";
            if (systemTimeUtc != null) {
                ZonedDateTime zdt = systemTimeUtc.atZone(ZoneOffset.UTC);
                timeString = String.format(
                    "%04d-%02d-%02dT%02d:%02dZ (UTC hour=%d, minute=%d)",
                    zdt.getYear(), zdt.getMonthValue(), zdt.getDayOfMonth(),
                    zdt.getHour(), zdt.getMinute(),
                    zdt.getHour(), zdt.getMinute()
                );
            }

        boolean hasSandboxExec = sortedCapabilities.stream()
                .anyMatch(c -> "sandbox_exec".equals(c.toolName()));


        var request = templateFor(role)
                .data("roleDef", role.definition())
                .data("sortedCapabilities", sortedCapabilities)
                .data("hasSandboxExec", hasSandboxExec)
                .data("compactionSummary", compactionSummary)
                .data("systemTimeUtc", timeString);

        if (context != null && context.data() != null) {
            context.data().forEach(request::data);
        }

        return request.render();
    }

    private Template templateFor(AgentRoleEnum role) {
        return switch (role.id()) {
            case "coordinator" -> coordinatorTemplate;
            case "researcher"  -> researcherTemplate;
            case "coder"       -> coderTemplate;
            case "debugger"    -> debuggerTemplate;
            case "architect"   -> architectTemplate;
            case "synthesizer" -> synthesizerTemplate;
            default -> throw new IllegalStateException(
                "No Qute template registered for role '" + role.id() + "'. " +
                "Create templates/prompts/role-" + role.id() + ".txt in arcclaw-core/src/main/resources to fix.");
        };
    }
}

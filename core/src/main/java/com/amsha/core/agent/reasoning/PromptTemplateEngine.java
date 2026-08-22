package com.amsha.core.agent.reasoning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import com.amsha.core.agent.loop.AgentExecutionContext;
import com.amsha.core.agent.reasoning.prompt.PromptConstants;
import com.amsha.core.agent.reasoning.prompt.PromptRegistry;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.security.api.AutonomyLevel;
import com.amsha.tool.api.AgentTool;
import com.amsha.tool.api.ToolCapability;

import org.jboss.logging.Logger;

import io.quarkus.qute.Engine;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;

/**
 * Builds role-specific system prompt section using Qute templates.
 * <p>
 * Called by {@link LangChainReasoningEngine} to replace the hardcoded
 * {@code getRoleBinding()} switch. Templates live at:
 * {@code core/src/main/resources/templates/prompts/role-*.txt}
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
    @Location("prompts/role-healer")
    Template healerTemplate;

    @Inject
    @Location("prompts/role-optimizer")
    Template optimizerTemplate;

    @Inject
    @Location("prompts/role-sentinel")
    Template sentinelTemplate;

    @Inject
    @Location("prompts/role-protector")
    Template protectorTemplate;

    @Inject
    @Location("prompts/role-purifier")
    Template purifierTemplate;

    @Inject
    @Location("prompts/role-nurturer")
    Template nurturerTemplate;

    @Inject
    @Location("prompts/role-skill")
    Template skillTemplate;

    @Inject
    ToolRegistry toolRegistry;

    @Inject
    RoleRegistry roleRegistry;

    @Inject
    PromptRegistry promptRegistry;

    @Inject
    Engine engine;

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
    public String buildSystemPrompt(
            AgentExecutionContext context, AgentRoleEnum role, String compactionSummary, Instant systemTimeUtc) {
        if (role == null) {
            log.warn("buildSystemPrompt called with null role - returning fallback");
            return "PERMITTED TOOLS: None";
        }

        // Sort capabilities for deterministic LLM prompt ordering
        // (RoleDefinition.capabilities() returns an immutable Set - order is undefined).
        // HARD FILTER: only include tools that are actually enabled in the registry.
        // This prevents hallucinations where the LLM tries to call a tool that lacks API keys.
        boolean isBackground = context != null && context.autonomyLevel() == AutonomyLevel.BACKGROUND;
        var roleDef = roleRegistry.resolveRoleDefinition(role.id());
        List<ToolCapability> sortedCapabilities = roleDef.capabilities().stream()
                .filter(cap -> toolRegistry
                        .findTool(cap.toolName())
                        .map(AgentTool::isEnabled)
                        .orElse(false))
                .filter(cap -> !isBackground
                        || (!ToolCapability.SCHEDULE_TASK.toolName().equals(cap.toolName())
                                && !ToolCapability.MANAGE_SCHEDULE.toolName().equals(cap.toolName())))
                .sorted((c1, c2) -> {
                    if (ToolCapability.BROWSER_NAVIGATE.toolName().equals(c1.toolName())) return -1;
                    if (ToolCapability.BROWSER_NAVIGATE.toolName().equals(c2.toolName())) return 1;
                    return c1.toolName().compareTo(c2.toolName());
                })
                .toList();

        log.debugf(
                "Building system prompt for role %s. Enabled capabilities: %s",
                role.id(),
                sortedCapabilities.stream().map(ToolCapability::toolName).toList());

        String timeString = "";
        if (systemTimeUtc != null) {
            ZonedDateTime zdt = systemTimeUtc.atZone(ZoneOffset.UTC);
            timeString = String.format(
                    "%04d-%02d-%02dT%02d:%02dZ (UTC hour=%d, minute=%d)",
                    zdt.getYear(),
                    zdt.getMonthValue(),
                    zdt.getDayOfMonth(),
                    zdt.getHour(),
                    zdt.getMinute(),
                    zdt.getHour(),
                    zdt.getMinute());
        }

        boolean hasSandboxExec = sortedCapabilities.stream()
                .anyMatch(c -> ToolCapability.SANDBOX_EXEC.toolName().equals(c.toolName()));

        boolean hasBrowserNavigate = sortedCapabilities.stream()
                .anyMatch(c -> ToolCapability.BROWSER_NAVIGATE.toolName().equals(c.toolName()));

        var request = templateFor(role)
                .data("roleDef", roleDef)
                .data("sortedCapabilities", sortedCapabilities)
                .data("hasSandboxExec", hasSandboxExec)
                .data("hasBrowserNavigate", hasBrowserNavigate)
                .data("compactionSummary", compactionSummary)
                .data("systemTimeUtc", timeString)
                .data("dynamicSkills", roleRegistry.getDynamicRoles());

        if (context != null && context.data() != null) {
            context.data().forEach(request::data);
        }

        return request.render();
    }

    private Template templateFor(AgentRoleEnum role) {
        String templateText = promptRegistry.get(role.id(), PromptConstants.KEY_ROLE_TEMPLATE);
        if (templateText != null) {
            return engine.parse(templateText);
        }
        if (AgentRoleEnum.isSkillRole(role.id())) {
            return skillTemplate;
        }
        return switch (role.id()) {
            case "coordinator" -> coordinatorTemplate;
            case "researcher" -> researcherTemplate;
            case "coder" -> coderTemplate;
            case "debugger" -> debuggerTemplate;
            case "architect" -> architectTemplate;
            case "synthesizer" -> synthesizerTemplate;
            case "healer" -> healerTemplate;
            case "optimizer" -> optimizerTemplate;
            case "sentinel" -> sentinelTemplate;
            case "protector" -> protectorTemplate;
            case "purifier" -> purifierTemplate;
            case "nurturer" -> nurturerTemplate;
            default ->
                throw new IllegalStateException("No Qute template registered for role '" + role.id() + "'. "
                        + "Create templates/prompts/role-" + role.id() + ".txt in core/src/main/resources to fix.");
        };
    }
}

package com.arcclaw.core.agent.reasoning;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;

import org.jboss.logging.Logger;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.ToolCapability;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Builds role-specific system prompt section using Qute templates.
 * <p>
 * Called by {@link LangChainReasoningEngine} to replace the hardcoded
 * {@code getRoleBinding()} switch. Templates live at:
 * {@code arcclaw-core/src/main/resources/templates/prompts/role-*.txt}
 * <p>
 * The switch is exhaustive — adding a new {@link AgentRoleEnum} without
 * a corresponding {@code case} causes a compile error (not a silent default).
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

    /**
     * Renders the system prompt section for the given role.
     *
     * @param role              the agent role
     * @param compactionSummary prior session summary, or {@code null} if none
     * @param systemTimeUtc     current UTC time, or {@code null} (coordinator falls back to empty string)
     * @return the rendered prompt string
     */
    public String buildSystemPrompt(AgentRoleEnum role, String compactionSummary, Instant systemTimeUtc) {
        if (role == null) {
            log.warn("buildSystemPrompt called with null role — returning fallback");
            return "PERMITTED TOOLS: None";
        }

        // Sort capabilities for deterministic LLM prompt ordering
        // (RoleDefinition.capabilities() returns an immutable Set — order is undefined)
        List<ToolCapability> sortedCapabilities = role.definition().capabilities()
                .stream()
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

        return templateFor(role)
                .data("roleDef", role.definition())
                .data("sortedCapabilities", sortedCapabilities)
                .data("compactionSummary", compactionSummary)
                .data("systemTimeUtc", timeString)
                .render();
    }

    /**
     * Resolves the right template for the role.
     * No {@code default} — compile error if a new enum value is added without a case.
     */
    private Template templateFor(AgentRoleEnum role) {
        return switch (role) {
            case COORDINATOR -> coordinatorTemplate;
            case RESEARCHER  -> researcherTemplate;
            case CODER       -> coderTemplate;
            case DEBUGGER    -> debuggerTemplate;
            case ARCHITECT   -> architectTemplate;
            case SYNTHESIZER  -> synthesizerTemplate;
        };
    }
}

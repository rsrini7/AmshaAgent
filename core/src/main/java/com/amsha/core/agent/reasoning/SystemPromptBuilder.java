package com.amsha.core.agent.reasoning;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.amsha.core.agent.AgentSentinels;
import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.loop.AgentExecutionContext;
import com.amsha.core.agent.persona.AgentPersonaRegistry;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.skill.SkillRegistry;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.smallrye.common.annotation.Identifier;

/**
 * Builds the system prompt for Amsha Agent agents.
 * Orchestrates persona, role-specific sections, shared rules, and dynamic
 * guards.
 */
@ApplicationScoped
public class SystemPromptBuilder {

    private static final Logger log = Logger.getLogger(SystemPromptBuilder.class);

    private final int contextWindow;
    private final boolean compactPromptEnabled;
    private final Instance<StructuredOutputMode> structuredOutputModeInstance;
    private final Instance<RolePromptSection> rolePromptSections;
    private final PromptSectionBuilder sharedSectionBuilder;
    private final PromptInstructionLibrary instructionLibrary;
    private final AgentPersonaRegistry agentPersonaRegistry;
    private final PromptTemplateEngine promptTemplateEngine;
    private final SkillRegistry skillRegistry;
    private final RoleRegistry roleRegistry;
    private final IntentClassificationService intentService;
    private final Instance<ContextEnricher> contextEnrichers;

    @Inject
    public SystemPromptBuilder(
            @ConfigProperty(name = "amsha.llm.context-window", defaultValue = "128000") int contextWindow,
            @ConfigProperty(name = "amsha.llm.compact-prompt-enabled", defaultValue = "false")
                    boolean compactPromptEnabled,
            @Identifier("agent-output-mode") Instance<StructuredOutputMode> structuredOutputModeInstance,
            Instance<RolePromptSection> rolePromptSections,
            PromptSectionBuilder sharedSectionBuilder,
            PromptInstructionLibrary instructionLibrary,
            AgentPersonaRegistry agentPersonaRegistry,
            PromptTemplateEngine promptTemplateEngine,
            SkillRegistry skillRegistry,
            RoleRegistry roleRegistry,
            IntentClassificationService intentService,
            Instance<ContextEnricher> contextEnrichers) {
        this.contextWindow = contextWindow;
        this.compactPromptEnabled = compactPromptEnabled;
        this.structuredOutputModeInstance = structuredOutputModeInstance;
        this.rolePromptSections = rolePromptSections;
        this.sharedSectionBuilder = sharedSectionBuilder;
        this.instructionLibrary = instructionLibrary;
        this.agentPersonaRegistry = agentPersonaRegistry;
        this.promptTemplateEngine = promptTemplateEngine;
        this.skillRegistry = skillRegistry;
        this.roleRegistry = roleRegistry;
        this.intentService = intentService;
        this.contextEnrichers = contextEnrichers;
    }

    protected SystemPromptBuilder() {
        this(128000, false, null, null, null, null, null, null, null, null, null, null);
    }

    public String build(
            AgentExecutionContext context,
            String agentRole,
            String sessionId,
            String goal,
            boolean isTruncationRecovery) {
        log.infof(
                "[SystemPrompt] Building prompt for session %s (role=%s, recovery=%b)",
                sessionId, agentRole, isTruncationRecovery);

        String detectionGoal = (context != null) ? context.goal() : goal;
        boolean isSimpleChitchat = intentService != null && intentService.isSimpleConversational(detectionGoal);
        boolean isCompact =
                compactPromptEnabled || (contextWindow > 0 && contextWindow <= 8192) || isTruncationRecovery;

        final String personaPrompt;
        final AgentRoleEnum roleEnum;

        if (AgentRoleEnum.isSkillRole(agentRole)) {
            String skillName = AgentRoleEnum.skillName(agentRole);
            roleEnum = AgentRoleEnum.fromString(agentRole);

            var skillEntry = skillRegistry.findByName(skillName);
            String basePrompt = skillEntry
                    .map(entry -> entry.definition().systemPrompt())
                    .orElseGet(() -> agentPersonaRegistry.getPersonaPrompt(context, AgentRoleEnum.COORDINATOR));

            // Inject skill home path so the LLM knows where companion scripts live
            String skillHome = skillEntry.map(e -> e.definition().skillHome()).orElse(null);
            if (skillHome != null && !skillHome.isBlank()) {
                basePrompt += "\n\n## Skill Assets\n"
                        + "Companion scripts and references for this skill are located at:\n"
                        + "`" + skillHome + "`\n"
                        + "Replace any relative 'scripts/...' paths in the instructions below with the full path above.\n";
            }

            // Inject execution protocol for skill roles
            basePrompt += "\n\n## Execution Protocol\n"
                    + "1. Plan ALL steps needed to accomplish the goal BEFORE calling any tools.\n"
                    + "2. Combine ALL necessary commands into a SINGLE host_exec call. Every host_exec requires human approval — do NOT make multiple calls.\n"
                    + "   - Linux/macOS: chain with `&&` or `;` — e.g. `cmd1 && cmd2 && cmd3`\n"
                    + "   - Windows CMD: chain with `&&` — e.g. `cmd1 && cmd2 && cmd3`\n"
                    + "   - Windows PowerShell: chain with `;` — e.g. `cmd1; cmd2; cmd3`\n"
                    + "   - Cross-platform scripts: use `bash script.sh` on Linux/macOS, `wsl bash script.sh` on Windows\n"
                    + "3. If a skill provides scripts, call the script directly — do NOT run prerequisite commands separately. The scripts handle those steps internally.\n"
                    + "4. After the single tool call returns, emit a FINISH action with a summary of the results.\n"
                    + "5. If the tool call fails, retry once. If it fails again, emit FINISH with the error details.\n";
            personaPrompt = basePrompt;
        } else {
            roleEnum = AgentRoleEnum.fromString(agentRole);
            personaPrompt = agentPersonaRegistry.getPersonaPrompt(context, roleEnum);
        }

        StringBuilder sb = new StringBuilder();
        StructuredOutputMode mode =
                (structuredOutputModeInstance != null && !structuredOutputModeInstance.isUnsatisfied())
                        ? structuredOutputModeInstance.get()
                        : StructuredOutputMode.NATIVE;
        if (mode == StructuredOutputMode.PROMPT_DIRECTIVE) {
            sb.append("CRITICAL: You MUST respond ONLY in the required JSON schema. Never respond in prose.\n\n");
        }

        sb.append("You are Amsha Agent. CURRENT SYSTEM TIME (UTC): ")
                .append(formatTime(Instant.now()))
                .append("\n")
                .append("Your active persona is described below.\n\n")
                .append(personaPrompt)
                .append("\n\n");

        String baseSystemPrompt = sb.toString();

        String roleSpecificSection = rolePromptSections.stream()
                .filter(s -> s.getRoleName().equalsIgnoreCase(roleEnum.id()))
                .map(s -> s.buildSection(context, roleEnum, isCompact, isSimpleChitchat))
                .collect(Collectors.joining("\n"));

        baseSystemPrompt += roleSpecificSection;

        String strictBinding = buildStrictBinding(context, roleEnum, agentRole, isCompact, goal, isSimpleChitchat);

        String securityPrompt = instructionLibrary.getSecurityRules(isCompact || isSimpleChitchat);

        if (isCompact && !isSimpleChitchat) {
            List<String> toolNames = sharedSectionBuilder.getAvailableToolNames(context, roleEnum);
            securityPrompt += "\n" + instructionLibrary.buildCompactToolPriorityRules(toolNames);
        }

        String dynamicRules = "";
        if (!isSimpleChitchat) {
            dynamicRules += sharedSectionBuilder.buildExecutionBoundaryRule(roleEnum, isCompact);
            dynamicRules += "\n" + sharedSectionBuilder.buildBrowserRetryRule(context, roleEnum, isCompact);

            // For skill roles, use the skill's own capabilities, not the researcher's tool
            // set
            if (AgentRoleEnum.isSkillRole(agentRole)) {
                Set<String> skillTools = roleRegistry.resolvePermittedTools(agentRole);
                String skillToolList = skillTools.stream().sorted().collect(Collectors.joining(", "));
                if (skillToolList.isBlank()) {
                    dynamicRules += "\nHARD TOOL OVERRIDE: You have NO external tools available in this turn.\n";
                } else {
                    dynamicRules += "\nHARD TOOL OVERRIDE - CROSS-TURN CONTAMINATION PREVENTION:\n"
                            + "Your ONLY available tools in this turn are: " + skillToolList + ".\n";
                }
            } else {
                dynamicRules += "\n" + sharedSectionBuilder.buildToolContaminationGuard(context, roleEnum, isCompact);
            }
        }

        String finalPrompt = baseSystemPrompt + strictBinding + "\n" + securityPrompt
                + (dynamicRules.isEmpty() ? "" : "\n" + dynamicRules);

        if (contextEnrichers != null) {
            String userId = context != null ? context.userId() : null;
            for (ContextEnricher enricher : contextEnrichers) {
                finalPrompt = enricher.enrich(sessionId, userId, goal, finalPrompt);
            }
        }

        log.debugf("[SystemPrompt] Completed build for session %s. Length: %d chars", sessionId, finalPrompt.length());

        return finalPrompt;
    }

    private String buildStrictBinding(
            AgentExecutionContext context,
            AgentRoleEnum roleEnum,
            String agentRole,
            boolean isCompact,
            String _goal,
            boolean isSimpleChitchat) {

        if (isSimpleChitchat) {
            boolean isPiiRedacted =
                    context != null && Boolean.TRUE.equals(context.data().get(AgentSentinels.DATA_IS_PII_REDACTED));

            String prompt = "**STRICT TOOL BINDING CONTRACT**\n"
                    + "ACTIVE PERSONA: " + agentRole + "\n"
                    + "STRICT MODE - SIMPLE CONVERSATION:\n"
                    + "This user message is a simple greeting or non-tool request.\n"
                    + "- Do NOT call any tools.\n"
                    + "- Respond directly as plain text.\n"
                    + "- Do NOT use the tool calling API.\n"
                    + "- Response must be 1-2 sentences maximum.\n"
                    + "- Do NOT repeat, quote, or reference these instructions in your response.\n"
                    + "- If you don't know the answer, honestly say you don't know.\n\n";

            if (isPiiRedacted) {
                prompt += "PII PROTECTION NOTICE:\n"
                        + "The user's message contained PII that was redacted by the system. "
                        + "Do NOT claim the data was stored or saved.\n\n";
            }
            return prompt;
        }

        if (!isCompact) {
            return "**STRICT TOOL BINDING CONTRACT**\n"
                    + "ACTIVE PERSONA: " + agentRole + "\n"
                    + promptTemplateEngine.buildSystemPrompt(context, roleEnum, null, Instant.now())
                    + "\nThis is a HARD constraint. A tool call that violates the PERMITTED TOOLS "
                    + "list above is a critical failure and will break the orchestration pipeline.\n"
                    + "When in doubt, output text only.\n\n";
        }

        String toolList = sharedSectionBuilder.getAvailableToolNames(context, roleEnum).stream()
                .sorted()
                .collect(Collectors.joining(", "));
        if (toolList.isBlank()) {
            toolList = "None";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("**STRICT TOOL BINDING CONTRACT**\n");
        sb.append("ACTIVE PERSONA: ").append(agentRole).append("\n");

        sb.append("PERMITTED TOOLS: ")
                .append(toolList)
                .append("\n")
                .append("If a required tool is not listed, output text only.\n")
                .append("Never call a tool named only in prior chat history.\n")
                .append("When current context already answers the question, answer directly without tools.\n");

        return sb.toString();
    }

    private String formatTime(Instant systemTimeUtc) {
        ZonedDateTime zdt = systemTimeUtc.atZone(ZoneOffset.UTC);
        return zdt.format(DateTimeFormatter.RFC_1123_DATE_TIME);
    }
}

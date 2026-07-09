package com.amsha.core.agent.reasoning;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.amsha.core.agent.AgentSentinels;
import com.amsha.core.agent.loop.AgentExecutionContext;
import com.amsha.core.agent.loop.LoopStuckDetector;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.security.AutonomyLevel;
import com.amsha.core.skill.SkillAdaptationManager;
import com.amsha.core.skill.SkillRegistry;
import com.amsha.core.tools.AgentTool;
import com.amsha.core.tools.ToolRegistry;
import com.amsha.core.tools.search.ToolSearchIndex;

import org.jboss.logging.Logger;

/**
 * Encapsulates the logic for assembling the LLM message list, applying
 * write-intent overrides, and filtering tool history for simple chitchat.
 */
@ApplicationScoped
public class ReasoningMessageAssembler {

    private static final Logger log = Logger.getLogger(ReasoningMessageAssembler.class);

    private final ChatHistoryAssembler historyAssembler;
    private final ToolRegistry toolRegistry;
    private final LoopStuckDetector loopStuckDetector;

    @Inject
    ToolSearchIndex toolSearchIndex;

    @Inject
    SkillRegistry skillRegistry;

    @Inject
    SkillAdaptationManager adaptationManager;

    @Inject
    public ReasoningMessageAssembler(
            ChatHistoryAssembler historyAssembler, ToolRegistry toolRegistry, LoopStuckDetector loopStuckDetector) {
        this.historyAssembler = historyAssembler;
        this.toolRegistry = toolRegistry;
        this.loopStuckDetector = loopStuckDetector;
    }

    protected ReasoningMessageAssembler() {
        this(null, null, null);
    }

    public record AssembledMessages(List<ChatMessage> messages, List<ToolSpecification> tools) {}

    public AssembledMessages assemble(
            AgentExecutionContext context,
            String agentRole,
            String goal,
            List<ReasoningStep> history,
            String systemPrompt,
            Set<String> allowedTools,
            boolean isWriteIntent,
            boolean isSimpleChitchat,
            boolean isHistoryQuery) {

        log.infof(
                "[ReasoningAssembler] Assembling messages for session %s (role=%s, steps=%d, isChitchat=%b)",
                context.sessionId(), agentRole, history.size(), isSimpleChitchat);

        String sessionId = context.sessionId();
        List<ChatMessage> assembled = historyAssembler.assemble(sessionId, goal, history);

        List<ChatMessage> filteredAssembled = assembled.stream()
                .filter(m -> !(m instanceof SystemMessage sm
                        && sm.text().contains(AgentSentinels.COMPACTED_SUMMARY_SENTINEL)))
                .toList();

        Optional<String> stuckHint = loopStuckDetector.detectStuckHint(history, agentRole);
        String finalSystemPrompt =
                resolveSystemPrompt(assembled, systemPrompt, isWriteIntent, context.isForceFinishRetry());
        if (stuckHint.isPresent()) {
            log.infof("[ReasoningAssembler] Injecting loop-stuck hint for session %s (role=%s)", sessionId, agentRole);
            finalSystemPrompt = finalSystemPrompt + "\n\n" + stuckHint.get();
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.systemMessage(finalSystemPrompt));

        if (context.autonomyLevel() == AutonomyLevel.BACKGROUND) {
            messages.add(
                    SystemMessage.systemMessage("CAPABILITY CONSTRAINT: You do NOT have access to `schedule_task` or "
                            + "`manage_schedule` in this context. Do not attempt to call them. "
                            + "Use only the tools explicitly listed in your tool schema."));
        }

        List<ChatMessage> historyToInclude =
                filterHistory(filteredAssembled, isSimpleChitchat, context.isForceFinishRetry());

        if (isSimpleChitchat && !isHistoryQuery) {
            messages.add(UserMessage.userMessage(goal));
        } else {
            messages.addAll(historyToInclude);
            if (context.isForceFinishRetry()) {
                messages.add(UserMessage.userMessage("Your previous response contained raw JSON or tool-call syntax. "
                        + "Answer in plain prose only. All tools have already returned their results "
                        + "- do NOT call any more tools. Summarize the findings and call FINISH."));
            }
        }

        boolean isBackgroundSession = context.autonomyLevel() == AutonomyLevel.BACKGROUND
                || (context.sessionId() != null && context.sessionId().startsWith(AgentSentinels.CRON_SESSION_PREFIX));

        List<ToolSpecification> toolSpecifications = resolveTools(
                allowedTools,
                isSimpleChitchat,
                context.isForceFinishRetry(),
                isBackgroundSession,
                goal,
                agentRole,
                context.sessionId());

        log.debugf(
                "[ReasoningAssembler] Assembled %d messages and %d tools for session %s",
                messages.size(), toolSpecifications.size(), sessionId);

        return new AssembledMessages(messages, toolSpecifications);
    }

    private String resolveSystemPrompt(
            List<ChatMessage> assembled, String systemPrompt, boolean isWriteIntent, boolean isForceFinishRetry) {
        String summaryContent = assembled.stream()
                .filter(m ->
                        m instanceof SystemMessage sm && sm.text().contains(AgentSentinels.COMPACTED_SUMMARY_SENTINEL))
                .map(m -> ((SystemMessage) m).text())
                .findFirst()
                .orElse("");

        String finalSystemPrompt = summaryContent.isEmpty() ? systemPrompt : summaryContent + "\n\n" + systemPrompt;

        if (isWriteIntent && isForceFinishRetry) {
            finalSystemPrompt = "CRITICAL OVERRIDE: The user wants to STORE data. "
                    + "You MUST call ingest_document RIGHT NOW as your ONLY action. "
                    + "Use the user's original message as the 'text' parameter. "
                    + "Do NOT call FINISH. Do NOT explain.\n\n"
                    + finalSystemPrompt;
        }
        return finalSystemPrompt;
    }

    private List<ChatMessage> filterHistory(
            List<ChatMessage> filteredAssembled, boolean isSimpleChitchat, boolean isForceFinishRetry) {
        if (isSimpleChitchat || isForceFinishRetry) {
            return filteredAssembled.stream()
                    .filter(m -> !isSimpleChitchat || m.type() != ChatMessageType.TOOL_EXECUTION_RESULT)
                    .map(m -> {
                        if (m instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                            if (!isSimpleChitchat) return m;
                            return (ai.text() != null && !ai.text().isBlank()) ? AiMessage.aiMessage(ai.text()) : null;
                        }
                        return m;
                    })
                    .filter(Objects::nonNull)
                    .toList();
        }
        return filteredAssembled;
    }

    private List<ToolSpecification> resolveTools(
            Set<String> allowedTools,
            boolean isSimpleChitchat,
            boolean isForceFinishRetry,
            boolean isBackgroundSession,
            String goal,
            String agentRole,
            String sessionId) {
        if (isSimpleChitchat || isForceFinishRetry) {
            return List.of();
        }

        // Get skill home path for dynamic tool description injection
        final String skillHome;
        if (agentRole != null && AgentRoleEnum.isSkillRole(agentRole)) {
            String skillName = AgentRoleEnum.skillName(agentRole);
            var skillEntry = skillRegistry.currentSnapshot().stream()
                    .filter(e -> e.definition().name().equals(skillName))
                    .findFirst();
            skillHome = skillEntry.map(e -> e.definition().skillHome()).orElse(null);
            log.infof("[ToolDescInjection] agentRole=%s skillName=%s skillHome=%s", agentRole, skillName, skillHome);
        } else {
            skillHome = null;
        }

        List<String> ranked = new ArrayList<>();
        if (toolSearchIndex != null && goal != null && !goal.isBlank()) {
            List<String> searchResults = toolSearchIndex.search(goal, allowedTools.size());
            for (String name : searchResults) {
                if (allowedTools.contains(name)) {
                    ranked.add(name);
                }
            }
        }
        for (String name : allowedTools) {
            if (!ranked.contains(name)) {
                ranked.add(name);
            }
        }

        return ranked.stream()
                .filter(name -> !isBackgroundSession
                        || (!name.equals(ToolCapability.SCHEDULE_TASK.toolName())
                                && !name.equals(ToolCapability.MANAGE_SCHEDULE.toolName())))
                .map(name -> toolRegistry.findTool(name).orElseThrow())
                .filter(AgentTool::isEnabled)
                .map(tool -> {
                    String description = tool.description();
                    // Inject skill's basePath into host_exec description for skill roles
                    if (skillHome != null && ToolCapability.HOST_EXEC.toolName().equals(tool.name())) {
                        String scriptsDir = skillHome + "/scripts";
                        boolean hasScripts = Files.isDirectory(Path.of(scriptsDir));
                        log.infof(
                                "[ToolDescInjection] tool=%s scriptsDir=%s hasScripts=%s",
                                tool.name(), scriptsDir, hasScripts);
                        if (hasScripts) {
                            description += "\n\nSkill scripts are located at: " + scriptsDir
                                    + "\nIMPORTANT: Use host_exec (not sandbox_exec) to run skill scripts — they need host filesystem access."
                                    + "\nCall scripts directly with full path. Do NOT run prerequisite commands."
                                    + "\nFor non-interactive tasks, use scripts that accept input via flags or stdin."
                                    + "\nChain commands with && (Linux/macOS) or ; (PowerShell) if needed.";

                            // Add adaptation instructions if adaptive mode is enabled
                            String adaptInstructions = adaptationManager.getAdaptationInstructions(skillHome);
                            if (!adaptInstructions.isEmpty()) {
                                description += adaptInstructions;
                            }
                        }
                    }
                    return ToolSpecification.builder()
                            .name(tool.name())
                            .description(description)
                            .parameters(tool.parametersSchema())
                            .build();
                })
                .collect(Collectors.toList());
    }
}

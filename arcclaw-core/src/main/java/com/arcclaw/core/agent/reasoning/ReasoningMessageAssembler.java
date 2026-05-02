package com.arcclaw.core.agent.reasoning;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.loop.AgentExecutionContext;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.tools.AgentTool;
import com.arcclaw.core.tools.ToolRegistry;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
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

    @Inject
    public ReasoningMessageAssembler(ChatHistoryAssembler historyAssembler,
                                    ToolRegistry toolRegistry) {
        this.historyAssembler = historyAssembler;
        this.toolRegistry = toolRegistry;
    }

    protected ReasoningMessageAssembler() {
        this(null, null);
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

        log.infof("[ReasoningAssembler] Assembling messages for session %s (role=%s, steps=%d, isChitchat=%b)",
                context.sessionId(), agentRole, history.size(), isSimpleChitchat);

        String sessionId = context.sessionId();
        List<ChatMessage> assembled = historyAssembler.assemble(sessionId, goal, history);

        // § Resilience: Extract compacted summary from history to merge into main SystemPrompt
        String summaryContent = assembled.stream()
                .filter(m -> m instanceof SystemMessage sm && sm.text().contains(AgentSentinels.COMPACTED_SUMMARY_SENTINEL))
                .map(m -> ((SystemMessage) m).text())
                .findFirst()
                .orElse("");

        List<ChatMessage> filteredAssembled = assembled.stream()
                .filter(m -> !(m instanceof SystemMessage sm && sm.text().contains(AgentSentinels.COMPACTED_SUMMARY_SENTINEL)))
                .toList();

        String finalSystemPrompt = summaryContent.isEmpty() ? systemPrompt
                : summaryContent + "\n\n" + systemPrompt;

        if (isWriteIntent && context.isForceFinishRetry()) {
            finalSystemPrompt = "CRITICAL OVERRIDE: The user wants to STORE data. " +
                "You MUST call ingest_document RIGHT NOW as your ONLY action. " +
                "Use the user's original message as the 'text' parameter. " +
                "Do NOT call FINISH. Do NOT explain.\n\n" + finalSystemPrompt;
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.systemMessage(finalSystemPrompt));
 
        if (context.autonomyLevel() == AutonomyLevel.BACKGROUND) {
            messages.add(SystemMessage.systemMessage(
                "CAPABILITY CONSTRAINT: You do NOT have access to `schedule_task` or " +
                "`manage_schedule` in this context. Do not attempt to call them. " +
                "Use only the tools explicitly listed in your tool schema."
            ));
        }
 
        List<ChatMessage> historyToInclude = filteredAssembled;
        if (isSimpleChitchat || context.isForceFinishRetry()) {
            historyToInclude = filteredAssembled.stream()
                .filter(m -> !isSimpleChitchat || m.type() != ChatMessageType.TOOL_EXECUTION_RESULT)
                .map(m -> {
                    if (m instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                        if (!isSimpleChitchat) return m; 
                        return (ai.text() != null && !ai.text().isBlank()) 
                                ? AiMessage.aiMessage(ai.text()) : null;
                    }
                    return m;
                })
                .filter(Objects::nonNull)
                .toList();
        }

        if (isSimpleChitchat && !isHistoryQuery) {
            messages.add(UserMessage.userMessage(goal));
        } else {
            messages.addAll(historyToInclude);
            if (context.isForceFinishRetry()) {
                messages.add(UserMessage.userMessage(
                    "Your previous response contained raw JSON or tool-call syntax. " +
                    "Answer in plain prose only. All tools have already returned their results " +
                    "- do NOT call any more tools. Summarize the findings and call FINISH."
                ));
            }
        }

        boolean isBackgroundSession = 
            context.autonomyLevel() == AutonomyLevel.BACKGROUND
            || (context.sessionId() != null && context.sessionId().startsWith(AgentSentinels.CRON_SESSION_PREFIX));
 
        List<ToolSpecification> toolSpecifications;
        if (isSimpleChitchat || context.isForceFinishRetry()) {
            toolSpecifications = List.of();
        } else {
            toolSpecifications = toolRegistry.listTools().stream()
                    .filter(allowedTools::contains)
                    .filter(name -> !isBackgroundSession || (!name.equals(ToolCapability.SCHEDULE_TASK.toolName())
                            && !name.equals(ToolCapability.MANAGE_SCHEDULE.toolName())))
                    .map(name -> toolRegistry.findTool(name).orElseThrow())
                    .filter(AgentTool::isEnabled)
                    .map(tool -> ToolSpecification.builder()
                            .name(tool.name())
                            .description(tool.description())
                            .parameters(tool.parametersSchema())
                            .build())
                    .collect(Collectors.toList());
        }

        log.debugf("[ReasoningAssembler] Assembled %d messages and %d tools for session %s",
                messages.size(), toolSpecifications.size(), sessionId);

        return new AssembledMessages(messages, toolSpecifications);
    }
}

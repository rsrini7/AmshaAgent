package com.amsha.core.agent.reasoning;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.agent.loop.CodeGenerationPolicy;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.util.TextPatternUtils;
import com.amsha.llm.api.MultimodalRequests;
import com.amsha.tool.api.AgentSentinels;

/**
 * Pure (stateless) helpers for assembling the LLM message list. Extracted from
 * {@link ReasoningMessageAssembler} to keep that class under the PMD class-length cap; these
 * methods carry no Amsha instance state, so they are safe as {@code static} functions. Any caller
 * state they need ({@code intentClassifier}, {@code textPatternUtils}, {@code codePolicy}) is passed
 * in explicitly.
 */
final class ReasoningMessageHelpers {

    private static final int CHITCHAT_CONTEXT_MESSAGES = 2;

    private ReasoningMessageHelpers() {}

    static List<ChatMessage> recentConversationContext(
            List<ChatMessage> history,
            String goal,
            IntentClassificationService intentClassifier,
            TextPatternUtils textPatternUtils,
            CodeGenerationPolicy codePolicy) {
        if (intentClassifier != null && intentClassifier.isGreeting(goal)) return List.of();
        List<ChatMessage> conversation = history.stream()
                .filter(m -> m instanceof UserMessage || m instanceof AiMessage)
                .filter(m -> !(m instanceof UserMessage user && Objects.equals(extractUserText(user), goal)))
                .filter(m -> !containsCodeOrExecution(m, textPatternUtils, codePolicy))
                .toList();
        int fromIndex = Math.max(0, conversation.size() - CHITCHAT_CONTEXT_MESSAGES);
        return conversation.subList(fromIndex, conversation.size());
    }

    static boolean containsCodeOrExecution(
            ChatMessage m, TextPatternUtils textPatternUtils, CodeGenerationPolicy codePolicy) {
        if (m instanceof ToolExecutionResultMessage) return true;
        if (m instanceof AiMessage ai) {
            if (ai.hasToolExecutionRequests()) return true;
            String text = ai.text();
            if (text == null || text.isBlank()) return false;
            return text.contains("```")
                    || text.contains("[Executed tool:")
                    || text.contains("[Tool Output")
                    || (textPatternUtils != null && textPatternUtils.looksLikeJson(text))
                    || (codePolicy != null && codePolicy.isGeneratedCodeBlock(text, AgentRoleEnum.CODER.id()));
        }
        if (m instanceof UserMessage user) {
            String text = extractUserText(user);
            if (text == null || text.isBlank()) return false;
            return text.contains("```")
                    || text.contains("[Tool Output")
                    || text.contains("[Executed tool:")
                    || (textPatternUtils != null
                            && (textPatternUtils.goalRequestsExecution(text) || textPatternUtils.looksLikeJson(text)));
        }
        return false;
    }

    static String extractUserText(UserMessage user) {
        if (user == null) return null;
        return MultimodalRequests.textOf(user, "history");
    }

    static String resolveSystemPrompt(
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

    static List<ChatMessage> filterHistory(
            List<ChatMessage> filteredAssembled, boolean isSimpleChitchat, boolean isForceFinishRetry) {
        if (isSimpleChitchat || isForceFinishRetry) {
            return filteredAssembled.stream()
                    .map(m -> {
                        if (m instanceof ToolExecutionResultMessage tool) {
                            if (isSimpleChitchat) return null;
                            return (ChatMessage) UserMessage.userMessage(
                                    "[Tool Output from " + tool.toolName() + "]: " + tool.text());
                        }
                        if (m instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                            if (isSimpleChitchat) {
                                return (ai.text() != null && !ai.text().isBlank())
                                        ? AiMessage.aiMessage(ai.text())
                                        : null;
                            }
                            String text = (ai.text() != null && !ai.text().isBlank())
                                    ? ai.text()
                                    : "[Executed tool: "
                                            + ai.toolExecutionRequests().stream()
                                                    .map(ToolExecutionRequest::name)
                                                    .collect(Collectors.joining(", "))
                                            + "]";
                            return (ChatMessage) AiMessage.aiMessage(text);
                        }
                        return m;
                    })
                    .filter(Objects::nonNull)
                    .toList();
        }
        return filteredAssembled;
    }
}

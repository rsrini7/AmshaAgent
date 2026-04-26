package com.arcclaw.core.agent.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.security.InputSanitizer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

/**
 * Persists goals and answers to the ChatMemoryStore.
 * All content is PII-scrubbed before being saved to prevent
 * sensitive data from persisting in conversation history.
 */
@ApplicationScoped
public class ChatMemoryWriter {

    @Inject ChatMemoryStore chatMemoryStore;
    @Inject @InputSanitizer.PII InputSanitizer piiScanReflex;

    static final Set<String> SKIP_GOALS = Set.of(
        AgentSentinels.GOAL_TOOL_APPROVED,
        AgentSentinels.GOAL_RESUME_APPROVED,
        AgentSentinels.GOAL_RESUME_DENIED
    );

    /**
     * Saves the initial goal to chat memory with deduplication.
     * PII is redacted before storing.
     */
    public void saveGoal(String sessionId, String goal) {
        if (sessionId == null || goal == null || goal.isEmpty()) return;
        if (SKIP_GOALS.contains(goal)) return;

        String sanitizedGoal = piiScanReflex.sanitize(goal);

        List<ChatMessage> currentHistory = chatMemoryStore.getMessages(sessionId);
        if (currentHistory == null) currentHistory = new ArrayList<>();
        else currentHistory = new ArrayList<>(currentHistory);

        // Deduplication — skip if last message already contains this goal
        if (!currentHistory.isEmpty()) {
            ChatMessage lastMsg = currentHistory.getLast();
            if (lastMsg instanceof UserMessage
                    && lastMsg.toString().contains(sanitizedGoal)) {
                return;
            }
        }

        currentHistory.add(UserMessage.userMessage(sanitizedGoal));
        chatMemoryStore.updateMessages(sessionId, currentHistory);
    }

    /**
     * Appends an AI answer to the chat memory history.
     * PII is redacted before storing to prevent LLM-regenerated
     * sensitive data from persisting in conversation history.
     */
    public void saveAnswer(String sessionId, String answer) {
        if (sessionId == null || answer == null || answer.isBlank()) return;

        String sanitizedAnswer = piiScanReflex.sanitize(answer);

        List<ChatMessage> currentHistory = chatMemoryStore.getMessages(sessionId);
        if (currentHistory == null) {
            currentHistory = new ArrayList<>();
        } else {
            currentHistory = new ArrayList<>(currentHistory);
        }

        currentHistory.add(AiMessage.aiMessage(sanitizedAnswer));
        chatMemoryStore.updateMessages(sessionId, currentHistory);
    }

    /**
     * Clears all chat memory for a session.
     * Called by ResetReflex and session termination.
     */
    public void clearMemory(String sessionId) {
        if (sessionId != null) {
            chatMemoryStore.deleteMessages(sessionId);
        }
    }

    /**
     * Scrubs unfulfilled tool calls from chat memory.
     * Used when a pending approval is discarded in favor of a new goal.
     * This prevents the discarded tool calls from re-surfacing in the next turn's history.
     */
    public void scrubUnfulfilledToolCalls(String sessionId) {
        if (sessionId == null) return;

        List<ChatMessage> currentHistory = chatMemoryStore.getMessages(sessionId);
        if (currentHistory == null || currentHistory.isEmpty()) return;

        Set<String> resultIds = currentHistory.stream()
                .filter(m -> m instanceof ToolExecutionResultMessage)
                .map(m -> ((ToolExecutionResultMessage) m).id())
                .collect(Collectors.toSet());

        List<ChatMessage> filteredHistory = currentHistory.stream()
                .filter(m -> {
                    if (m instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                        // Keep only tool calls that have a matching result in history
                        return ai.toolExecutionRequests().stream()
                                .anyMatch(req -> resultIds.contains(req.id()));
                    }
                    return true;
                })
                .collect(Collectors.toList());

        if (filteredHistory.size() < currentHistory.size()) {
            chatMemoryStore.updateMessages(sessionId, filteredHistory);
        }
    }


}

package com.amsha.core.agent.loop;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.amsha.core.agent.AgentSentinels;
import com.amsha.core.events.ConversationMessageEvent;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.core.security.InputSanitizer;

/**
 * Persists goals and answers to the ChatMemoryStore.
 * All content is PII-scrubbed before being saved to prevent
 * sensitive data from persisting in conversation history.
 */
@ApplicationScoped
public class ChatMemoryWriter {

    @Inject
    ChatMemoryStore chatMemoryStore;

    @Inject
    @InputSanitizer.PII
    InputSanitizer piiScanReflex;

    @Inject
    Event<ConversationMessageEvent> conversationMessageEvents;

    static final Set<String> SKIP_GOALS = Set.of(
            AgentSentinels.GOAL_TOOL_APPROVED, AgentSentinels.GOAL_RESUME_APPROVED, AgentSentinels.GOAL_RESUME_DENIED);

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

        // Deduplication - skip if last message already contains this goal
        if (!currentHistory.isEmpty()) {
            ChatMessage lastMsg = currentHistory.getLast();
            if (lastMsg instanceof UserMessage && lastMsg.toString().contains(sanitizedGoal)) {
                return;
            }
        }

        currentHistory.add(UserMessage.userMessage(sanitizedGoal));
        chatMemoryStore.updateMessages(sessionId, currentHistory);
        emitUserMessageEvent(sessionId, sanitizedGoal, currentHistory.size());
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

        if (!currentHistory.isEmpty()) {
            ChatMessage lastMsg = currentHistory.getLast();
            if (lastMsg instanceof AiMessage ai && sanitizedAnswer.equals(ai.text())) {
                return;
            }
        }

        currentHistory.add(AiMessage.aiMessage(sanitizedAnswer));
        chatMemoryStore.updateMessages(sessionId, currentHistory);
        emitAgentMessageEvent(sessionId, sanitizedAnswer, currentHistory.size());
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
                        return ai.toolExecutionRequests().stream().anyMatch(req -> resultIds.contains(req.id()));
                    }
                    return true;
                })
                .collect(Collectors.toList());

        if (filteredHistory.size() < currentHistory.size()) {
            chatMemoryStore.updateMessages(sessionId, filteredHistory);
        }
    }

    private void emitUserMessageEvent(String sessionId, String content, int turnNumber) {
        if (conversationMessageEvents == null || sessionId == null || content == null || content.isBlank()) {
            return;
        }
        conversationMessageEvents.fireAsync(
                ConversationMessageEvent.user(sessionId, WorkspaceContext.getUserIdOrUnknown(), content, turnNumber));
    }

    private void emitAgentMessageEvent(String sessionId, String content, int turnNumber) {
        if (conversationMessageEvents == null || sessionId == null || content == null || content.isBlank()) {
            return;
        }
        conversationMessageEvents.fireAsync(ConversationMessageEvent.agent(
                sessionId,
                WorkspaceContext.getUserIdOrUnknown(),
                WorkspaceContext.AGENT_ROLE.isBound() ? WorkspaceContext.AGENT_ROLE.get() : "coordinator",
                content,
                turnNumber));
    }
}

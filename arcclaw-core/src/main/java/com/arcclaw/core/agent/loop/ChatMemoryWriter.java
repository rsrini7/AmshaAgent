package com.arcclaw.core.agent.loop;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.reflex.PiiScanReflex;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
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
    @Inject PiiScanReflex piiScanReflex;

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

        String sanitizedGoal = piiScanReflex.redactPii(goal);

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

        String sanitizedAnswer = piiScanReflex.redactPii(answer);

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
}

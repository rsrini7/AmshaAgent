package com.arcclaw.core.agent.loop;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.AgentSentinels;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Persists goals and answers to the ChatMemoryStore.
 */
@ApplicationScoped
public class ChatMemoryWriter {

    @Inject ChatMemoryStore chatMemoryStore;

    static final Set<String> SKIP_GOALS = Set.of(
        AgentSentinels.GOAL_TOOL_APPROVED,
        AgentSentinels.GOAL_RESUME_APPROVED,
        AgentSentinels.GOAL_RESUME_DENIED
    );

    /**
     * Saves the initial goal to chat memory with deduplication.
     */
    public void saveGoal(String sessionId, String goal) {
        if (sessionId != null && goal != null && !goal.isEmpty()) {
            if (!SKIP_GOALS.contains(goal)) {

                List<ChatMessage> currentHistory = chatMemoryStore.getMessages(sessionId);
                if (currentHistory == null) currentHistory = new ArrayList<>();
                else currentHistory = new ArrayList<>(currentHistory);

                boolean alreadySaved = false;
                if (!currentHistory.isEmpty()) {
                    ChatMessage lastMsg = currentHistory.getLast();
                    if (lastMsg instanceof UserMessage && lastMsg.toString().contains(goal)) {
                        alreadySaved = true;
                    }
                }
                if (!alreadySaved) {
                    // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
                    currentHistory.add(UserMessage.userMessage(goal));
                    chatMemoryStore.updateMessages(sessionId, currentHistory);
                }
            }
        }
    }

    /**
     * Appends an AI answer to the chat memory history.
     */
    public void saveAnswer(String sessionId, String answer) {
        if (sessionId != null && answer != null && !answer.isBlank()) {
            List<ChatMessage> currentHistory = chatMemoryStore.getMessages(sessionId);
            if (currentHistory == null) {
                currentHistory = new ArrayList<>();
            } else {
                currentHistory = new ArrayList<>(currentHistory);
            }
            // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
            currentHistory.add(AiMessage.aiMessage(answer));
            chatMemoryStore.updateMessages(sessionId, currentHistory);
        }
    }
}

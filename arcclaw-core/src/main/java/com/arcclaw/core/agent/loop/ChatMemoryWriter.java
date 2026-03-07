package com.arcclaw.core.agent.loop;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists goals and answers to the ChatMemoryStore.
 */
@ApplicationScoped
public class ChatMemoryWriter {

    @Inject ChatMemoryStore chatMemoryStore;

    /**
     * Saves the initial goal to chat memory with deduplication.
     */
    public void saveGoal(String sessionId, String goal) {
        if (sessionId != null && goal != null && !goal.isEmpty()) {
            if (!"The tool execution has been approved and completed. Read the tool's output. If it is an error, tell me. Otherwise, provide the final answer to my original request.".equals(goal)
                    && !"Resuming task after approval".equals(goal)
                    && !("Resuming task after denial").equals(goal)) {

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
                    currentHistory.add(UserMessage.from(goal));
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
            currentHistory.add(AiMessage.from(answer));
            chatMemoryStore.updateMessages(sessionId, currentHistory);
        }
    }
}

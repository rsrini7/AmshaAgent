package com.arcclaw.core.agent.loop;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.arcclaw.core.agent.AgentSentinels;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

@ExtendWith(MockitoExtension.class)
class ChatMemoryWriterTest {

    @Mock
    ChatMemoryStore chatMemoryStore;

    @InjectMocks
    ChatMemoryWriter chatMemoryWriter;

    @Test
    void testSaveGoalSkipsResumeGoals() {
        chatMemoryWriter.saveGoal("session-1", AgentSentinels.GOAL_TOOL_APPROVED);
        chatMemoryWriter.saveGoal("session-1", AgentSentinels.GOAL_RESUME_APPROVED);
        chatMemoryWriter.saveGoal("session-1", AgentSentinels.GOAL_RESUME_DENIED);

        verify(chatMemoryStore, never()).updateMessages(eq("session-1"), anyList());
    }

    @Test
    void testSaveGoalPersistsNormalGoal() {
        String sessionId = "session-1";
        String goal = "Normal user goal";
        
        chatMemoryWriter.saveGoal(sessionId, goal);
        
        verify(chatMemoryStore).getMessages(sessionId);
        verify(chatMemoryStore).updateMessages(eq(sessionId), anyList());
    }

    @Test
    void skipGoalsContainsAllThreeSentinelConstants() {
        // Pin exact string values to prevent accidental drift
        org.junit.jupiter.api.Assertions.assertTrue(ChatMemoryWriter.SKIP_GOALS.contains(
            "The tool execution has been approved and completed. Read the tool's output. If it is an error, tell me. Otherwise, provide the final answer to my original request."));
        org.junit.jupiter.api.Assertions.assertTrue(ChatMemoryWriter.SKIP_GOALS.contains("Resuming task after approval"));
        org.junit.jupiter.api.Assertions.assertTrue(ChatMemoryWriter.SKIP_GOALS.contains("Resuming task after denial"));
        org.junit.jupiter.api.Assertions.assertEquals(3, ChatMemoryWriter.SKIP_GOALS.size());
    }

    @Test
    void saveGoalDoesNotPersistAnySkipGoal() {
        String sessionId = "ses-1";
        
        for (String skipGoal : ChatMemoryWriter.SKIP_GOALS) {
            org.mockito.Mockito.clearInvocations(chatMemoryStore);
            chatMemoryWriter.saveGoal(sessionId, skipGoal);
            verify(chatMemoryStore, never()).updateMessages(eq(sessionId), anyList());
        }
    }
}
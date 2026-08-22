package com.amsha.core.agent.loop;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import com.amsha.core.security.InputSanitizer;
import com.amsha.tool.api.AgentSentinels;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatMemoryWriterTest {

    @Mock
    ChatMemoryStore chatMemoryStore;

    @Mock
    InputSanitizer piiScanReflex;

    @InjectMocks
    ChatMemoryWriter chatMemoryWriter;

    @BeforeEach
    void setUp() {
        // piiScanReflex.sanitize() should return input unchanged for unit tests.
        // Marked lenient to avoid UnnecessaryStubbingException in tests that don't reach the redaction call.
        lenient().when(piiScanReflex.sanitize(any())).thenAnswer(inv -> inv.getArgument(0));
    }

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
        Assertions.assertTrue(
                ChatMemoryWriter.SKIP_GOALS.contains(
                        "The tool execution has been approved and completed. Read the tool's output. If it is an error, tell me. Otherwise, provide the final answer to my original request."));
        Assertions.assertTrue(ChatMemoryWriter.SKIP_GOALS.contains("Resuming task after approval"));
        Assertions.assertTrue(ChatMemoryWriter.SKIP_GOALS.contains("Resuming task after denial"));
        Assertions.assertEquals(3, ChatMemoryWriter.SKIP_GOALS.size());
    }

    @Test
    void saveGoalDoesNotPersistAnySkipGoal() {
        String sessionId = "ses-1";

        for (String skipGoal : ChatMemoryWriter.SKIP_GOALS) {
            Mockito.clearInvocations(chatMemoryStore);
            chatMemoryWriter.saveGoal(sessionId, skipGoal);
            verify(chatMemoryStore, never()).updateMessages(eq(sessionId), anyList());
        }
    }
}

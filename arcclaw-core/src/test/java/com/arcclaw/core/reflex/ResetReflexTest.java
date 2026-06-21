package com.arcclaw.core.reflex;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.persistence.api.CompactionStorePort;
import com.arcclaw.persistence.api.CronStorePort;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResetReflexTest {

    @Mock
    WorkspaceManager workspaceManager;

    @Mock
    ChatMemoryStore chatMemoryStore;

    @Mock
    CompactionStorePort compactionStorePort;

    @Mock
    CronStorePort cronStore;

    @Mock
    StatefulAgentStore statefulAgentStore;

    @InjectMocks
    ResetReflex reflex;

    @BeforeEach
    void injectLogger() throws Exception {
        Field logField = ResetReflex.class.getDeclaredField("log");
        logField.setAccessible(true);
        logField.set(reflex, Logger.getLogger(ResetReflex.class));
    }

    private MessageContext context(String input) {
        ChannelUser user = new ChannelUser("user1", "chat1", "User 1", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, input, "sess-1", Instant.now());
        return new MessageContext("chat1", user, msg);
    }

    @Test
    void matchesRecognizesResetVariants() {
        assertTrue(reflex.matches("/reset"));
        assertTrue(reflex.matches("/resethard"));
        assertTrue(reflex.matches("clear all"));
        assertTrue(reflex.matches("clear history"));
        assertTrue(reflex.matches("clear my history"));
        assertTrue(reflex.matches("reset history"));
        assertTrue(reflex.matches("reset my history"));
    }

    @Test
    void rejectsUnrelatedInput() {
        assertFalse(reflex.matches("/halt"));
        assertFalse(reflex.matches("reset"));
        assertFalse(reflex.matches(null));
    }

    @Test
    void softResetRetainsChatMemory() {
        when(cronStore.deleteAllBySessionId("sess-1")).thenReturn(2L);

        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/reset", context("/reset"));

        verify(workspaceManager).resetSessionState("sess-1");
        verify(compactionStorePort).deleteBySessionId("sess-1");
        verify(chatMemoryStore, never()).deleteMessages(anyString());
        assertTrue(result.commandResponse().isPresent());
        assertTrue(result.commandResponse().get().contains("2"));
    }

    @Test
    void hardResetClearsChatMemory() {
        when(cronStore.deleteAllBySessionId("sess-1")).thenReturn(0L);

        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/resethard", context("/resethard"));

        verify(chatMemoryStore).deleteMessages("sess-1");
        assertTrue(result.commandResponse().get().contains("Hard Reset"));
    }

    @Test
    void clearAllIsTreatedAsHardReset() {
        when(cronStore.deleteAllBySessionId("sess-1")).thenReturn(0L);

        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "clear all", context("clear all"));

        verify(chatMemoryStore).deleteMessages("sess-1");
        assertTrue(result.commandResponse().get().contains("Hard Reset"));
    }

    @Test
    void marksStaleCheckpointsFailed() {
        AgentCheckpoint cp = mock(AgentCheckpoint.class);
        when(statefulAgentStore.load(eq("sess-1"), anyString())).thenReturn(Optional.of(cp));
        when(cronStore.deleteAllBySessionId("sess-1")).thenReturn(0L);

        reflex.execute("sess-1", "user1", "chat1", "/reset", context("/reset"));

        verify(cp, atLeastOnce()).setStatus(CheckpointStatus.FAILED);
        verify(cp, atLeastOnce()).setFailReason("Session reset by user.");
        verify(statefulAgentStore, atLeastOnce()).save(cp);
    }

    @Test
    void auditDetailDistinguishesHardAndSoft() {
        String soft = reflex.auditDetail("s1", "/reset");
        assertTrue(soft.contains("SOFT"));

        String hard = reflex.auditDetail("s1", "/resethard");
        assertTrue(hard.contains("HARD"));

        String clearAll = reflex.auditDetail("s1", "clear all");
        assertTrue(clearAll.contains("HARD"));
    }
}

package com.amsha.core.reflex;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.time.Instant;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.message.UserMessages;
import com.amsha.core.pipeline.MessageContext;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HaltReflexTest {

    @Mock
    WorkspaceManager workspaceManager;

    @InjectMocks
    HaltReflex reflex;

    @BeforeEach
    void injectLogger() throws Exception {
        Field logField = HaltReflex.class.getDeclaredField("log");
        logField.setAccessible(true);
        logField.set(reflex, Logger.getLogger(HaltReflex.class));
    }

    private MessageContext context() {
        ChannelUser user = new ChannelUser("user1", "chat1", "User 1", ChannelType.CLI);
        ChannelMessage msg = new ChannelMessage.CliMessage(user, "/halt", "sess-1", Instant.now());
        return new MessageContext("chat1", user, msg);
    }

    @Test
    void matchesRecognizesSlashHalt() {
        assertTrue(reflex.matches("/halt"));
        assertTrue(reflex.matches("  /HALT  "));
        assertTrue(reflex.matches("/Halt"));
        assertTrue(reflex.matches("/stop"));
        assertTrue(reflex.matches("  /STOP  "));
        assertTrue(reflex.matches("/Stop"));
    }

    @Test
    void rejectsNonHaltInput() {
        assertFalse(reflex.matches("/reset"));
        assertFalse(reflex.matches("halt"));
        assertFalse(reflex.matches(null));
    }

    @Test
    void executeTerminatesSessionAndReturnsHaltedMessage() {
        ReflexResult result = reflex.execute("sess-1", "user1", "chat1", "/halt", context());

        verify(workspaceManager).terminateSession("sess-1");
        assertTrue(result.commandResponse().isPresent());
        assertEquals(UserMessages.SESSION_HALTED, result.commandResponse().get());
    }

    @Test
    void auditDetailContainsSessionId() {
        String detail = reflex.auditDetail("sess-123", "/halt");
        assertTrue(detail.contains("HaltReflex"));
        assertTrue(detail.contains("sess-123"));
    }
}

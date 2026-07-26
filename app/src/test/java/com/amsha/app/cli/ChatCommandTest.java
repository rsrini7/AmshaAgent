package com.amsha.app.cli;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.List;

import com.amsha.core.agent.loop.CliResultBridge;
import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.governance.ApprovalFrame;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.session.SessionCatalogService;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.quarkus.runtime.ShutdownEvent;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatCommandTest {

    @Mock
    Logger log;

    @Mock
    WorkspaceManager workspaceManager;

    @Mock
    CliResultBridge cliResultBridge;

    @Mock
    SessionCatalogService sessionCatalogService;

    @InjectMocks
    ChatCommand command;

    @BeforeEach
    void setUp() {
        when(sessionCatalogService.registerOrResumeSession(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("session-123");
    }

    @Test
    void testCanonicalRole() {
        assertEquals("coordinator", ChatCommand.canonicalRole(null));
        assertEquals("coordinator", ChatCommand.canonicalRole(""));
        assertEquals("coder", ChatCommand.canonicalRole("coder"));
        assertEquals("custom-role", ChatCommand.canonicalRole("custom-role"));
    }

    @Test
    void testFormatTerminalResult() {
        assertEquals(
                "Answer 42", ChatCommand.formatTerminalResult(new AgentResult.Finished("Answer 42", List.of(), 0, 0)));
        assertEquals(
                "Structured Answer",
                ChatCommand.formatTerminalResult(
                        new AgentResult.StructuredFinished("Structured Answer", "{}", List.of(), 0, 0)));
        assertEquals(
                "❌ Error: Failed reason",
                ChatCommand.formatTerminalResult(new AgentResult.Failed(
                        "Failed reason", AgentResult.FailureCategory.SYSTEM_ERROR, List.of(), 0, 0)));
        assertEquals(
                "⏱ Timed out after 5 steps.",
                ChatCommand.formatTerminalResult(new AgentResult.LoopExceeded(5, List.of(), 0, 0)));
        assertEquals(
                "⏳ Rate limit hit. Retry in 2s...",
                ChatCommand.formatTerminalResult(new AgentResult.AwaitingRetry("rate limit", 2000, List.of(), 0, 0)));

        ApprovalFrame frame = mock(ApprovalFrame.class);
        assertNull(
                ChatCommand.formatTerminalResult(new AgentResult.AwaitingApproval(frame, "prompt", List.of(), 0, 0)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testExtractSlashCommands() throws Exception {
        Method method = ChatCommand.class.getDeclaredMethod("extractSlashCommands");
        method.setAccessible(true);
        List<String> commands = (List<String>) method.invoke(command);
        assertNotNull(commands);
        assertTrue(commands.contains("/exit"));
        assertTrue(commands.contains("/approve"));
    }

    @Test
    void testOnShutdown() throws Exception {
        command.requestedSessionId = "sess-xyz";

        var field = ChatCommand.class.getDeclaredField("currentCliSessionId");
        field.setAccessible(true);
        field.set(command, "sess-xyz");

        command.onShutdown(new ShutdownEvent());

        verify(cliResultBridge).cancel("sess-xyz");
        verify(workspaceManager).terminateSession("sess-xyz");
        assertNull(field.get(command));
    }
}

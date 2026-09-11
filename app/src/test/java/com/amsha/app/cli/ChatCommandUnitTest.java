package com.amsha.app.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import com.amsha.core.agent.result.AgentResult;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.domain.model.AgentCheckpointRecord;
import com.amsha.core.state.StatefulAgentStore;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ChatCommandUnitTest {

    private final ChatCommand chatCommand = new ChatCommand();

    @Test
    void testCanonicalRole() {
        assertEquals(AgentRoleEnum.COORDINATOR.toScopedName(), ChatCommand.canonicalRole(null));
        assertEquals(AgentRoleEnum.COORDINATOR.toScopedName(), ChatCommand.canonicalRole(""));
        assertEquals(AgentRoleEnum.CODER.toScopedName(), ChatCommand.canonicalRole("coder"));
        assertEquals("custom_role", ChatCommand.canonicalRole("custom_role"));
    }

    @Test
    void testFormatTerminalResult() {
        AgentResult.Finished finished = new AgentResult.Finished("Hello world", List.of(), 10, 10);
        assertEquals("Hello world", ChatCommand.formatTerminalResult(finished));

        AgentResult.StructuredFinished sf =
                new AgentResult.StructuredFinished("Structured answer", "{}", List.of(), 10, 10);
        assertEquals("Structured answer", ChatCommand.formatTerminalResult(sf));

        AgentResult.Failed failed =
                new AgentResult.Failed("Error occurred", AgentResult.FailureCategory.SYSTEM_ERROR, List.of(), 0, 0);
        assertTrue(ChatCommand.formatTerminalResult(failed).contains("Error occurred"));

        AgentResult.LoopExceeded exceeded = new AgentResult.LoopExceeded(5, List.of(), 0, 0);
        assertTrue(ChatCommand.formatTerminalResult(exceeded).contains("5 steps"));

        AgentResult.AwaitingRetry retry = new AgentResult.AwaitingRetry("session-1", 3000, List.of(), 0, 0);
        assertTrue(ChatCommand.formatTerminalResult(retry).contains("3s"));

        AgentResult approval = AgentResult.awaitingApproval("tool", "{}", "session-1", List.of());
        assertNull(ChatCommand.formatTerminalResult(approval));
    }

    @Test
    void testExtractSlashCommands() throws Exception {
        Method extract = ChatCommand.class.getDeclaredMethod("extractSlashCommands");
        extract.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> cmds = (List<String>) extract.invoke(chatCommand);
        assertTrue(cmds.contains("/exit"));
        assertTrue(cmds.contains("/approve"));
        assertTrue(cmds.contains("/deny"));
    }

    @Test
    void testDescribePhase() {
        assertEquals("", ChatCommand.describePhase(null));
        assertEquals("", ChatCommand.describePhase("  "));
        assertEquals(" · running read_file", ChatCommand.describePhase("TOOL:read_file"));
        assertEquals(" · running tools", ChatCommand.describePhase("PARALLEL_TOOLS"));
        assertEquals(" · reading tool results", ChatCommand.describePhase("OBSERVING"));
        assertEquals(" · writing the answer", ChatCommand.describePhase("FINISHING"));
        assertEquals(" · thinking", ChatCommand.describePhase("RUNNING"));
    }

    @Test
    void testOnShutdownCleanWhenNoSession() {
        assertDoesNotThrow(() -> chatCommand.onShutdown(null));
    }

    @Test
    void testIsSessionAwaitingApproval() {
        assertFalse(chatCommand.isSessionAwaitingApproval("sess-1"));

        StatefulAgentStore mockStore = Mockito.mock(StatefulAgentStore.class);
        chatCommand.agentStore = mockStore;

        Mockito.when(mockStore.hasPendingApproval("sess-1")).thenReturn(true);
        assertTrue(chatCommand.isSessionAwaitingApproval("sess-1"));

        Mockito.when(mockStore.hasPendingApproval("sess-1")).thenReturn(false);
        Mockito.when(mockStore.findLatestAwaitingApprovalBySessionContaining("sess-1"))
                .thenReturn(Optional.empty());
        assertFalse(chatCommand.isSessionAwaitingApproval("sess-1"));

        Mockito.when(mockStore.findLatestAwaitingApprovalBySessionContaining("sess-1"))
                .thenReturn(Optional.of(new AgentCheckpointRecord()));
        assertTrue(chatCommand.isSessionAwaitingApproval("sess-1"));
    }
}

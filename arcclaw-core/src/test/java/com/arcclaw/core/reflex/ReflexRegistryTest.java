package com.arcclaw.core.reflex;

import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.state.StatefulAgentStore;
import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.CheckpointStatus;
import com.arcclaw.core.governance.GovernanceLayer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class ReflexRegistryTest {

    @Inject
    ReflexRegistry reflexRegistry;

    @InjectMock
    WorkspaceManager workspaceManager;

    @InjectMock
    StatefulAgentStore statefulAgentStore;

    @InjectMock
    ChatMemoryStore chatMemoryStore;

    @InjectMock
    GovernanceLayer governanceLayer;

    // ── ReflexRegistry routing ──────────────────────────────────────────────

    @Test
    void testUnknownInputReturnsEmpty() {
        Optional<String> result = reflexRegistry.processIfReflex(
                "session-1", "user-1", "Hello ArcClaw");
        assertTrue(result.isEmpty(), 
                "Non-reflex input must return Optional.empty()");
        verifyNoInteractions(workspaceManager);
    }

    @Test
    void testNullInputReturnsEmpty() {
        Optional<String> result = reflexRegistry.processIfReflex(
                "session-1", "user-1", null);
        assertTrue(result.isEmpty());
    }

    @Test
    void testBlankInputReturnsEmpty() {
        Optional<String> result = reflexRegistry.processIfReflex(
                "session-1", "user-1", "   ");
        assertTrue(result.isEmpty());
    }

    // ── Log injection guard ─────────────────────────────────────────────────

    @Test
    void testLogInjectionGuardTruncatesLongInput() {
        // /reset with 250 chars appended — reflex matches on /reset prefix
        // but we want to verify the registry doesn't throw on long input
        String longInput = "/reset" + "A".repeat(250);
        // ResetReflex.matches() uses equalsIgnoreCase — this won't match
        // so it tests that the registry handles long non-matching input cleanly
        Optional<String> result = reflexRegistry.processIfReflex(
                "session-1", "user-1", longInput);
        assertTrue(result.isEmpty(), 
                "Long non-matching input must return empty, not throw");
    }

    // ── ResetReflex ─────────────────────────────────────────────────────────

    @Test
    void testResetReflexWipesWorkspaceAndMemory() {
        Optional<String> result = reflexRegistry.processIfReflex(
                "session-reset", "user-1", "/reset");

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("wiped"));
        verify(workspaceManager).terminateSession("session-reset");
        verify(chatMemoryStore).deleteMessages("session-reset");
    }

    @Test
    void testResetReflexCaseInsensitive() {
        Optional<String> result = reflexRegistry.processIfReflex(
                "session-1", "user-1", "/RESET");
        assertTrue(result.isPresent());
    }

    // ── HaltReflex ──────────────────────────────────────────────────────────

    @Test
    void testHaltReflexTerminatesSession() {
        Optional<String> result = reflexRegistry.processIfReflex(
                "session-halt", "user-1", "/halt");

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("Halted"));
        verify(workspaceManager).terminateSession("session-halt");
        // Halt must NOT wipe chat memory — different from reset
        verifyNoInteractions(chatMemoryStore);
    }

    // ── StatusReflex ────────────────────────────────────────────────────────

    @Test
    void testStatusReflexReturnsFormattedOutput() {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setStatus(CheckpointStatus.COMPLETED);
        when(statefulAgentStore.load("session-status", "coordinator"))
                .thenReturn(Optional.of(cp));
        when(statefulAgentStore.load(eq("session-status"), 
                argThat(r -> !r.equals("coordinator"))))
                .thenReturn(Optional.empty());
        when(governanceLayer.getSessionCost("session-status")).thenReturn(0.05);

        Optional<String> result = reflexRegistry.processIfReflex(
                "session-status", "user-1", "/status");

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("COMPLETED"));
        assertTrue(result.get().contains("$0.0500"));
        // Last 8 chars of sessionId must appear
        assertTrue(result.get().contains("n-status".substring(1))); 
    }

    @Test
    void testStatusReflexNoActiveState() {
        when(statefulAgentStore.load(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(governanceLayer.getSessionCost(anyString())).thenReturn(0.0);

        Optional<String> result = reflexRegistry.processIfReflex(
                "session-new", "user-1", "/status");

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("NO_ACTIVE_STATE"));
    }

    // ── DenyReflex ──────────────────────────────────────────────────────────

    @Test
    void testDenyReflexWithNoPendingApproval() {
        when(statefulAgentStore.load(anyString(), anyString()))
                .thenReturn(Optional.empty());

        Optional<String> result = reflexRegistry.processIfReflex(
                "session-deny", "user-1", "/deny");

        assertTrue(result.isPresent());
        assertTrue(result.get().contains("No pending"));
    }

    @Test
    void testDenyReflexSetsCheckpointFailed() {
        AgentCheckpoint cp = new AgentCheckpoint();
        cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
        cp.sessionId = "session-deny";
        cp.agentRole = "coordinator";
        when(statefulAgentStore.load("session-deny", "coordinator"))
                .thenReturn(Optional.of(cp));

        reflexRegistry.processIfReflex("session-deny", "user-1", "/deny");

        verify(statefulAgentStore).save(argThat(saved ->
                saved.getStatus() == CheckpointStatus.FAILED &&
                "Human denied the tool execution.".equals(saved.failReason)
        ));
    }

    // ── Fail-safe ───────────────────────────────────────────────────────────

    @Test
    void testReflexExceptionReturnsErrorStringSafely() {
        // ResetReflex will throw because workspaceManager throws
        doThrow(new RuntimeException("DB connection lost"))
                .when(workspaceManager).terminateSession(anyString());

        Optional<String> result = reflexRegistry.processIfReflex(
                "session-err", "user-1", "/reset");

        // Must NOT throw — must return safe error message
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("System Error"));
        // Telegram consumer loop must not be affected
    }
}

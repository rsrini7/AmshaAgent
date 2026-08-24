package com.amsha.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.amsha.channel.api.ChannelCommand;
import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.core.reflex.DelegateApprovalGuard;
import com.amsha.core.util.PreExecutionFilter;
import com.amsha.security.api.AutonomyLevel;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Integration-style tests for Pre-execution and Delegation.
 * Covers P75-A (Safe tool pre-execution) and P75-B (Approval delegation).
 */
@ExtendWith(MockitoExtension.class)
class CoreIntegrationTest {

    ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testPreExecutionFilter_MixedTools() {
        var calls = List.of(
                new ParallelToolCall("1", "safe_read", Map.of(), "{}", null, true),
                new ParallelToolCall("2", "unsafe_write", Map.of(), "{}", null, true));
        var result = PreExecutionFilter.split(calls, AutonomyLevel.SUPERVISED, Set.of("safe_read"));

        assertEquals(1, result.preExecutable().size());
        assertEquals("safe_read", result.preExecutable().get(0).toolName());
        assertEquals(1, result.hitlPending().size());
        assertEquals("unsafe_write", result.hitlPending().get(0).toolName());
    }

    @Test
    void testDelegateApprovalGuard_Configured() {
        var guard = new DelegateApprovalGuard();
        guard.setDelegateApproverIds(List.of("admin123"));

        assertTrue(guard.isDelegate("admin123"));
        assertFalse(guard.isDelegate("user456"));
    }

    @Test
    void testChannelCommand_ParseSessionId() {
        String input = "/approve b2ada049-88e1-474d-9589-783a82190d46";
        ChannelCommand cmd = ChannelCommand.parse(input);

        assertTrue(cmd instanceof ChannelCommand.Approve);
        ChannelCommand.Approve app = (ChannelCommand.Approve) cmd;
        assertEquals(Optional.of("b2ada049-88e1-474d-9589-783a82190d46"), app.targetId());
    }
}

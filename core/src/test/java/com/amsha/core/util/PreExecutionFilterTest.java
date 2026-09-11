package com.amsha.core.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amsha.core.agent.reasoning.ParallelToolCall;
import com.amsha.security.api.AutonomyLevel;

import org.junit.jupiter.api.Test;

class PreExecutionFilterTest {
    @Test
    void allFullAutonomyAllPreExecutable() {
        var calls = List.of(
                new ParallelToolCall("c1", "t1", Map.of(), "{}", null, true),
                new ParallelToolCall("c2", "t2", Map.of(), "{}", null, true));
        var result = PreExecutionFilter.split(calls, AutonomyLevel.FULL, Set.of());
        assertEquals(2, result.preExecutable().size());
        assertEquals(0, result.hitlPending().size());
    }

    @Test
    void allSupervisedNoSafeToolsAllHitl() {
        var calls = List.of(
                new ParallelToolCall("c1", "t1", Map.of(), "{}", null, true),
                new ParallelToolCall("c2", "t2", Map.of(), "{}", null, true));
        var result = PreExecutionFilter.split(calls, AutonomyLevel.SUPERVISED, Set.of());
        assertEquals(0, result.preExecutable().size());
        assertEquals(2, result.hitlPending().size());
    }

    @Test
    void mixedSafeToolsPreExecuted() {
        var calls = List.of(
                new ParallelToolCall("c1", "safe", Map.of(), "{}", null, true),
                new ParallelToolCall("c2", "unsafe", Map.of(), "{}", null, true));
        var result = PreExecutionFilter.split(calls, AutonomyLevel.SUPERVISED, Set.of("safe"));
        assertEquals(1, result.preExecutable().size());
        assertEquals("safe", result.preExecutable().getFirst().toolName());
        assertEquals(1, result.hitlPending().size());
        assertEquals("unsafe", result.hitlPending().getFirst().toolName());
    }

    @Test
    void emptyCallsList() {
        var result = PreExecutionFilter.split(List.of(), AutonomyLevel.FULL, Set.of());
        assertTrue(result.preExecutable().isEmpty());
        assertTrue(result.hitlPending().isEmpty());
    }

    @Test
    void fullAutonomyWithMandatoryHitl_RequiresApproval() {
        var calls = List.of(
                new ParallelToolCall("c1", "serper_web_search", Map.of(), "{}", null, true),
                new ParallelToolCall("c2", "safe_read", Map.of(), "{}", null, true));
        var result = PreExecutionFilter.split(
                calls, AutonomyLevel.FULL, Set.of("safe_read"), call -> "serper_web_search".equals(call.toolName()));
        assertEquals(1, result.preExecutable().size());
        assertEquals("safe_read", result.preExecutable().getFirst().toolName());
        assertEquals(1, result.hitlPending().size());
        assertEquals("serper_web_search", result.hitlPending().getFirst().toolName());
    }
}

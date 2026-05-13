package com.arcclaw.core.state;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Snapshot test for {@link CheckpointStatus} terminal states.
 * <p>
 * This test ensures that when new statuses are added to the enum, the developer
 * is forced to explicitly decide if they are terminal or not by updating
 * this snapshot. This prevents accidental changes to the agent lifecycle policy.
 */
public class CheckpointStatusTest {

    /**
     * Verifies that the internal logic of the enum is consistent.
     */
    @Test
    public void testTerminalStatusesConsistency() {
        Set<CheckpointStatus> terminalSet = CheckpointStatus.terminalStatuses();
        
        for (CheckpointStatus status : CheckpointStatus.values()) {
            assertEquals(status.isTerminal(), terminalSet.contains(status),
                "isTerminal() and terminalStatuses() mismatch for: " + status);
        }
    }

    /**
     * SNAPSHOT TEST: terminalStatuses()
     * <p>
     * If this set changes, you MUST explicitly update this test. This serves
     * as a governance check for lifecycle changes.
     */
    @Test
    public void testTerminalLifecycleSnapshot() {
        Set<CheckpointStatus> terminalStatuses = CheckpointStatus.terminalStatuses();
        
        Set<CheckpointStatus> expectedTerminal = Set.of(
            CheckpointStatus.COMPLETED,
            CheckpointStatus.FAILED,
            CheckpointStatus.LOOP_EXCEEDED,
            CheckpointStatus.DENIED,
            CheckpointStatus.COMPROMISED,
            CheckpointStatus.ARCHIVED,
            CheckpointStatus.ORPHANED,
            CheckpointStatus.RESUMING
        );

        assertEquals(expectedTerminal, terminalStatuses,
            "Terminal status set has changed. This snapshot test must be explicitly updated " +
            "to acknowledge the change in terminal lifecycle states. §7.36");
    }

    /**
     * SNAPSHOT TEST: Non-terminal statuses
     */
    @Test
    public void testNonTerminalStatusesSnapshot() {
        Set<CheckpointStatus> nonTerminal = Arrays.stream(CheckpointStatus.values())
            .filter(s -> !s.isTerminal())
            .collect(Collectors.toSet());

        Set<CheckpointStatus> expectedNonTerminal = Set.of(
            CheckpointStatus.NOT_STARTED,
            CheckpointStatus.IN_PROGRESS,
            CheckpointStatus.AWAITING_APPROVAL,
            CheckpointStatus.AWAITING_RETRY,
            CheckpointStatus.APPROVED
        );

        assertEquals(expectedNonTerminal, nonTerminal,
            "Non-terminal status set has changed. New statuses must be explicitly " +
            "classified as terminal or non-terminal in this snapshot test. §7.36");
    }
}

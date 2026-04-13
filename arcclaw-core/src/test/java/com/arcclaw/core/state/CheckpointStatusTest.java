package com.arcclaw.core.state;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Snapshot test for {@link CheckpointStatus} terminal states.
 * <p>
 * This test ensures that when new statuses are added to the enum, the developer
 * is forced to explicitly decide if they are terminal or not by updating
 * this snapshot and the corresponding methods in CheckpointStatus.
 */
public class CheckpointStatusTest {

    @Test
    public void testTerminalStatusesConsistency() {
        Set<CheckpointStatus> terminalSet = CheckpointStatus.terminalStatuses();
        
        for (CheckpointStatus status : CheckpointStatus.values()) {
            assertEquals(status.isTerminal(), terminalSet.contains(status),
                "isTerminal() and terminalStatuses() mismatch for: " + status);
        }
    }

    @Test
    public void testTerminalStatusesSnapshot() {
        Set<CheckpointStatus> terminalStatuses = CheckpointStatus.terminalStatuses();
        
        Set<CheckpointStatus> expectedTerminal = Set.of(
            CheckpointStatus.COMPLETED,
            CheckpointStatus.FAILED,
            CheckpointStatus.LOOP_EXCEEDED,
            CheckpointStatus.DENIED,
            CheckpointStatus.COMPROMISED,
            CheckpointStatus.ARCHIVED
        );

        assertEquals(expectedTerminal, terminalStatuses,
            "Terminal status set has changed. This snapshot test must be explicitly updated " +
            "to acknowledge the change in terminal lifecycle states.");
    }

    @Test
    public void testNonTerminalStatusesSnapshot() {
        Set<CheckpointStatus> nonTerminal = Arrays.stream(CheckpointStatus.values())
            .filter(s -> !s.isTerminal())
            .collect(Collectors.toSet());

        Set<CheckpointStatus> expectedNonTerminal = Set.of(
            CheckpointStatus.NOT_STARTED,
            CheckpointStatus.IN_PROGRESS,
            CheckpointStatus.AWAITING_APPROVAL,
            CheckpointStatus.AWAITING_RETRY
        );

        assertEquals(expectedNonTerminal, nonTerminal,
            "Non-terminal status set has changed. New statuses must be explicitly " +
            "classified as terminal or non-terminal in this snapshot test.");
    }

    @Test
    void testIsTerminalExactSet(){
        Set<CheckpointStatus> terminalStatuses = EnumSet.allOf(CheckpointStatus.class).stream()
            .filter(s -> s.isTerminal())
            .collect(Collectors.toSet());
        
        Set<CheckpointStatus> expectedTerminal = Set.of(
            CheckpointStatus.COMPLETED,
            CheckpointStatus.FAILED,
            CheckpointStatus.LOOP_EXCEEDED,
            CheckpointStatus.DENIED,
            CheckpointStatus.COMPROMISED,
            CheckpointStatus.ARCHIVED
        );

        assertEquals(expectedTerminal, terminalStatuses,
            "Terminal status set has changed. This snapshot test must be explicitly updated " +
            "to acknowledge the change in terminal lifecycle states.");
    }
}

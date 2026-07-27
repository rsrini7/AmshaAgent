package com.amsha.core.task;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TaskStateTest {

    @ParameterizedTest
    @CsvSource({
        "TODO, TODO",
        "IN_PROGRESS, IN_PROGRESS",
        "IN PROGRESS, IN_PROGRESS",
        "INPROGRESS, IN_PROGRESS",
        "COMPLETED, COMPLETED",
        "DONE, COMPLETED",
        "AWAITING_HUMAN_INPUT, AWAITING_HUMAN_INPUT",
        "AWAITING INPUT, AWAITING_HUMAN_INPUT",
        "BLOCKED, AWAITING_HUMAN_INPUT",
        "CANCELLED, CANCELLED",
        "CANCELED, CANCELLED",
        "'', TODO",
        "' ', TODO",
        "unknown, TODO",
    })
    void fromStringParsesCorrectly(String input, TaskState expected) {
        assertEquals(expected, TaskState.fromString(input));
    }

    @Test
    void fromStringNullReturnsTodo() {
        assertEquals(TaskState.TODO, TaskState.fromString(null));
    }

    @Test
    void isTerminalReturnsTrueForCompleted() {
        assertTrue(TaskState.COMPLETED.isTerminal());
    }

    @Test
    void isTerminalReturnsTrueForCancelled() {
        assertTrue(TaskState.CANCELLED.isTerminal());
    }

    @Test
    void isTerminalReturnsFalseForNonTerminal() {
        assertFalse(TaskState.TODO.isTerminal());
        assertFalse(TaskState.IN_PROGRESS.isTerminal());
        assertFalse(TaskState.AWAITING_HUMAN_INPUT.isTerminal());
    }

    @Test
    void isActiveReturnsTrueForInProgress() {
        assertTrue(TaskState.IN_PROGRESS.isActive());
    }

    @Test
    void isActiveReturnsTrueForAwaitingHumanInput() {
        assertTrue(TaskState.AWAITING_HUMAN_INPUT.isActive());
    }

    @Test
    void isActiveReturnsFalseForNonActive() {
        assertFalse(TaskState.TODO.isActive());
        assertFalse(TaskState.COMPLETED.isActive());
        assertFalse(TaskState.CANCELLED.isActive());
    }
}

package com.arcclaw.core.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextPatternUtilsTest {

    private final TextPatternUtils utils = new TextPatternUtils();

    @Test
    void goalRequestsExecutionRecognizesExplicitExecutionFollowUps() {
        assertTrue(utils.goalRequestsExecution("ok execute and share results"));
        assertTrue(utils.goalRequestsExecution("fix and run again"));
        assertTrue(utils.goalRequestsExecution("please execute again"));
    }

    @Test
    void goalRequestsExecutionIgnoresGenericRetryPhrases() {
        assertFalse(utils.goalRequestsExecution("explain it again"));
        assertFalse(utils.goalRequestsExecution("rewrite it and try again"));
        assertFalse(utils.goalRequestsExecution("do the same in O(n)"));
    }
}

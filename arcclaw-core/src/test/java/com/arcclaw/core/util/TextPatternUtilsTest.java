package com.arcclaw.core.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextPatternUtilsTest {

    @Test
    void goalRequestsExecutionRecognizesExplicitExecutionFollowUps() {
        assertTrue(TextPatternUtils.goalRequestsExecution("ok execute and share results"));
        assertTrue(TextPatternUtils.goalRequestsExecution("fix and run again"));
        assertTrue(TextPatternUtils.goalRequestsExecution("please execute again"));
    }

    @Test
    void goalRequestsExecutionIgnoresGenericRetryPhrases() {
        assertFalse(TextPatternUtils.goalRequestsExecution("explain it again"));
        assertFalse(TextPatternUtils.goalRequestsExecution("rewrite it and try again"));
        assertFalse(TextPatternUtils.goalRequestsExecution("do the same in O(n)"));
    }
}

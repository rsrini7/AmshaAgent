package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.persistence.api.CheckpointStatus;
import com.amsha.security.api.AutonomyLevel;

import org.junit.jupiter.api.Test;

class AgentExecutionContextTest {

    @Test
    void withGoalPreservesBudgetsAndClearsStaleRetryCache() {
        AgentExecutionContext original = new AgentExecutionContext(
                "s1",
                "u1",
                "c1",
                "researcher",
                "first goal",
                AutonomyLevel.SUPERVISED,
                StructuredOutputMode.NATIVE,
                5,
                0,
                null,
                null,
                null,
                null);
        assertTrue(original.tryConsumeAnswerQualityRetry());
        original.incrementTruncationRetry();
        original.setCheckpointRestoreOffset(3);
        original.setForceFinishRetry(true);
        original.setLastCheckpointStatus(CheckpointStatus.IN_PROGRESS);

        AgentExecutionContext rewritten = original.withGoal("fallback goal");
        rewritten = rewritten.withGoal("another rewrite");

        assertEquals("another rewrite", rewritten.goal());
        assertEquals(1, rewritten.getAnswerQualityRetryCount());
        assertEquals(1, rewritten.getTruncationRetryCount());
        assertEquals(3, rewritten.getCheckpointRestoreOffset());
        assertFalse(rewritten.isForceFinishRetry());
        assertNull(rewritten.lastCheckpointStatus());
        assertTrue(rewritten.isStatusDirty());
        assertFalse(rewritten.tryConsumeAnswerQualityRetry());
    }
}

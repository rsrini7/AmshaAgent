package com.arcclaw.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.arcclaw.core.agent.result.AgentResult;

import org.junit.jupiter.api.Test;

class AgentResultFormatterTest {

    @Test
    void testFormatFailed_withNormalReason_prefixesWithX() {
        AgentResult failed =
                AgentResult.failed("Something went wrong", AgentResult.FailureCategory.SYSTEM_ERROR, List.of());
        String formatted = AgentResultFormatter.format(failed);
        assertEquals("❌ Something went wrong", formatted);
    }

    @Test
    void testFormatFailed_withRateLimitCategory_doesNotPrefixWithX() {
        // Sanitize should add ⏳
        AgentResult failed = AgentResult.failed("rate limit", AgentResult.FailureCategory.RATE_LIMIT, List.of());
        String formatted = AgentResultFormatter.format(failed);
        // We check that it starts with ⏳ and DOES NOT start with ❌ ⏳
        assertTrue(formatted.startsWith("⏳"));
        assertTrue(!formatted.startsWith("❌"));
    }

    @Test
    void testFormatFailed_withWaitEmojiPrefix_doesNotPrefixWithX() {
        AgentResult failed = AgentResult.failed("⏳ Waiting", AgentResult.FailureCategory.SYSTEM_ERROR, List.of());
        String formatted = AgentResultFormatter.format(failed);
        assertEquals("⏳ Waiting", formatted);
    }
}

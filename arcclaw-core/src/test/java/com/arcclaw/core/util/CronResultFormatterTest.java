package com.arcclaw.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.arcclaw.core.agent.result.AgentResult;
import org.junit.jupiter.api.Test;

class CronResultFormatterTest {

    @Test
    void testFormatFailed_withNormalReason_prefixesWithX() {
        AgentResult.Failed failed = new AgentResult.Failed("Something went wrong", AgentResult.FailureCategory.SYSTEM_ERROR, List.of(), 0, 0);
        String formatted = CronResultFormatter.format(failed);
        assertEquals("❌ Something went wrong", formatted);
    }

    @Test
    void testFormatFailed_withRateLimitCategory_doesNotPrefixWithX() {
        // Sanitize should add ⏳
        AgentResult.Failed failed = new AgentResult.Failed("rate limit", AgentResult.FailureCategory.RATE_LIMIT, List.of(), 0, 0);
        String formatted = CronResultFormatter.format(failed);
        // We check that it starts with ⏳ and DOES NOT start with ❌ ⏳
        assertTrue(formatted.startsWith("⏳"));
        assertTrue(!formatted.startsWith("❌"));
    }

    @Test
    void testFormatFailed_withWaitEmojiPrefix_doesNotPrefixWithX() {
        AgentResult.Failed failed = new AgentResult.Failed("⏳ Waiting", AgentResult.FailureCategory.SYSTEM_ERROR, List.of(), 0, 0);
        String formatted = CronResultFormatter.format(failed);
        assertEquals("⏳ Waiting", formatted);
    }
}

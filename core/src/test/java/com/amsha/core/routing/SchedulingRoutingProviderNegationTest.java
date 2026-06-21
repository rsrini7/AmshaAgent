package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class SchedulingRoutingProviderNegationTest {

    @Test
    void is_negation_flagged_by_shouldSkip() {
        SchedulingRoutingProvider provider = new SchedulingRoutingProvider();
        String negatedInput = "please do NOT schedule this reminder";

        // This is the primary B4 defense §4.11
        assertTrue(provider.shouldSkip(negatedInput), "Explicit negation must be detected by shouldSkip()");

        // Fast-path triggers themselves should NOT match either
        List<Pattern> triggers = provider.fastPathTriggers();
        boolean anyMatch =
                triggers.stream().anyMatch(p -> p.matcher(negatedInput).find());
        assertFalse(anyMatch, "Negation 'do NOT schedule' must not trigger fast-path patterns");
    }

    @Test
    void positive_timeSignalWithLeadingWords_stillTriggersFastPath() {
        SchedulingRoutingProvider provider = new SchedulingRoutingProvider();
        List<Pattern> triggers = provider.fastPathTriggers();

        // These MUST still match even with leading words (non-anchored pattern)
        String[] positiveInputs = {
            "please remind me in 30 minutes",
            "can you remind me in 5 minutes",
            "I want a reminder for tomorrow",
            "in 2 hours check the build",
            "after 5 min run the test",
            "remind me every day at 9am"
        };

        for (String input : positiveInputs) {
            assertFalse(provider.shouldSkip(input), "Legitimate input should NOT be skipped: " + input);
            boolean anyMatch = triggers.stream().anyMatch(p -> p.matcher(input).find());
            assertTrue(anyMatch, "Legitimate scheduling input must trigger fast-path: " + input);
        }
    }

    @Test
    void positive_noTimeSignal_doesNotTriggerFastPath() {
        SchedulingRoutingProvider provider = new SchedulingRoutingProvider();
        List<Pattern> triggers = provider.fastPathTriggers();

        // "schedule" alone is removed from the guard pattern to prevent broad matches
        // Input: "please schedule this for tomorrow"
        // should match "tomorrow" but not "schedule"

        boolean matchesTomorrow = triggers.stream()
                .anyMatch(p -> p.matcher("please schedule this for tomorrow").find());
        assertTrue(matchesTomorrow, "'tomorrow' must still trigger fast-path");

        boolean matchesJustSchedule = triggers.stream()
                .anyMatch(p -> p.matcher("please schedule this").find());
        assertFalse(matchesJustSchedule, "'schedule' without time signal should NOT trigger fast-path");
    }
}

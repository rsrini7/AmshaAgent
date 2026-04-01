package com.arcclaw.core.routing;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class SchedulingRoutingProviderNegationTest {

    @Test
    void negation_doesNotTriggerFastPath() {
        SchedulingRoutingProvider provider = new SchedulingRoutingProvider();
        List<Pattern> triggers = provider.fastPathTriggers();
        String negatedInput = "please do NOT schedule this reminder";
        boolean anyMatch = triggers.stream()
            .anyMatch(p -> p.matcher(negatedInput).find());
        assertFalse(anyMatch,
            "Negation 'do NOT schedule' must not trigger scheduling fast-path");
    }
}

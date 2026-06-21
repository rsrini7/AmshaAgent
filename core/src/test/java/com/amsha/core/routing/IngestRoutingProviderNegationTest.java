package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class IngestRoutingProviderNegationTest {

    @Test
    void negation_doesNotTriggerFastPath() {
        IngestRoutingProvider provider = new IngestRoutingProvider();
        List<Pattern> triggers = provider.fastPathTriggers();
        String negatedInput = "please do NOT store this in memory";
        boolean anyMatch =
                triggers.stream().anyMatch(p -> p.matcher(negatedInput).find());
        assertFalse(anyMatch, "Negation 'do NOT store' must not trigger ingest fast-path");
    }
}

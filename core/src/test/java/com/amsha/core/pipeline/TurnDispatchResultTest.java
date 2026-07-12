package com.amsha.core.pipeline;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TurnDispatchResultTest {

    @Test
    void normalizesNullableMetadataToEmptyOptionals() {
        TurnDispatchResult result = new TurnDispatchResult(null, null, null, null);

        assertTrue(result.sessionId().isEmpty());
        assertTrue(result.resolvedRole().isEmpty());
        assertTrue(result.agentResult().isEmpty());
        assertTrue(result.commandResponse().isEmpty());
    }
}

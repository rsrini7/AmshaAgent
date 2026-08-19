package com.amsha.llm.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ProviderValidationResultTest {

    @Test
    void validatesSuccessAndFailureFactories() {
        ProviderValidationResult success = ProviderValidationResult.ok();
        assertAll(
                () -> assertTrue(success.eligible()),
                () -> assertTrue(success.errors().isEmpty()));

        ProviderValidationResult failure = ProviderValidationResult.rejected("Invalid API key", "Host unreachable");
        assertAll(
                () -> assertFalse(failure.eligible()),
                () -> assertEquals(2, failure.errors().size()),
                () -> assertEquals("Invalid API key", failure.errors().get(0)));

        ProviderValidationResult listFailure = ProviderValidationResult.rejected(List.of("Error 1"));
        assertAll(
                () -> assertFalse(listFailure.eligible()),
                () -> assertEquals(List.of("Error 1"), listFailure.errors()));

        ProviderValidationResult nullErrors = new ProviderValidationResult(true, null);
        assertTrue(nullErrors.errors().isEmpty());
    }
}

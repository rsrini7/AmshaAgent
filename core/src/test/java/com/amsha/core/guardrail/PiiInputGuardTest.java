package com.amsha.core.guardrail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.amsha.core.reflex.PiiScanReflex;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PiiInputGuardTest {

    @Mock
    PiiScanReflex reflex;

    PiiInputGuard guard;

    @BeforeEach
    void setUp() throws Exception {
        guard = new PiiInputGuard();
        var field = PiiInputGuard.class.getDeclaredField("reflex");
        field.setAccessible(true);
        field.set(guard, reflex);
    }

    @Test
    void sanitizesWhenPiiDetected() {
        when(reflex.sanitize(anyString(), anyString(), anyString())).thenReturn("[REDACTED-PII]");
        var result = guard.validate("sess", "user", "has pii", "prompt with pii");
        assertTrue(result.isSanitize());
        assertEquals("[REDACTED-PII]", ((GuardDecision.Sanitize) result).sanitizedInput());
    }

    @Test
    void allowsWhenNoPii() {
        when(reflex.sanitize(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        var result = guard.validate("sess", "user", "safe", "clean prompt");
        assertTrue(result.isAllowed());
    }
}

package com.amsha.core.guardrail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.amsha.core.reflex.PromptInjectionReflex;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InjectionInputGuardTest {

    @Mock
    PromptInjectionReflex reflex;

    InjectionInputGuard guard;

    @BeforeEach
    void setUp() throws Exception {
        guard = new InjectionInputGuard();
        var field = InjectionInputGuard.class.getDeclaredField("reflex");
        field.setAccessible(true);
        field.set(guard, reflex);
    }

    @Test
    void blocksWhenInjectionDetected() {
        when(reflex.matches(anyString())).thenReturn(true);
        var result = guard.validate("sess", "user", "inject", "prompt");
        assertTrue(result.isBlocked());
    }

    @Test
    void allowsWhenNoInjection() {
        when(reflex.matches(anyString())).thenReturn(false);
        var result = guard.validate("sess", "user", "safe", "prompt");
        assertTrue(result.isAllowed());
    }
}

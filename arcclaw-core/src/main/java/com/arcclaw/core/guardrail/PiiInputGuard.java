package com.arcclaw.core.guardrail;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.reflex.PiiScanReflex;

@ApplicationScoped
@Priority(200)
public class PiiInputGuard implements PreLlmGuard {

    @Inject
    PiiScanReflex reflex;

    @Override
    public GuardDecision validate(String sessionId, String userId, String userMessage, String assembledPrompt) {
        String sanitized = reflex.sanitize(sessionId, userId, assembledPrompt);
        if (!sanitized.equals(assembledPrompt)) {
            return new GuardDecision.Sanitize(sanitized);
        }
        return new GuardDecision.Allow();
    }
}

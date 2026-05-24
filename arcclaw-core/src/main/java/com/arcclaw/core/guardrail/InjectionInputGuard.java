package com.arcclaw.core.guardrail;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.reflex.PromptInjectionReflex;

@ApplicationScoped
@Priority(100)
public class InjectionInputGuard implements PreLlmGuard {

    @Inject
    PromptInjectionReflex reflex;

    @Override
    public GuardDecision validate(String sessionId, String userId, String userMessage, String assembledPrompt) {
        if (reflex.matches(userMessage)) {
            return new GuardDecision.Block("Prompt injection detected",
                    "Message triggered injection reflex at the LLM boundary");
        }
        return new GuardDecision.Allow();
    }
}

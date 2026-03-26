package com.arcclaw.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.security.InputSanitizer;

/**
 * Prompt injection pattern redaction via PromptInjectionReflex.sanitize().
 */
@ApplicationScoped
public class InjectionRedactionTransformer implements ObservationTransformer {

    @Inject
    @InputSanitizer.Injection
    InputSanitizer injectionReflex;

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public String transform(String toolName, String input) {
        if (input == null) return "";
        return injectionReflex.sanitize(input);
    }
}

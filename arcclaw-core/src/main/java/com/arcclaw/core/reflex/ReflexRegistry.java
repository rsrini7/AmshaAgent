package com.arcclaw.core.reflex;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;

@ApplicationScoped
public class ReflexRegistry {

    @Inject
    Logger log;

    @Inject
    Instance<AgentReflex> reflexes;

    public ReflexResult processIfReflex(String sessionId, String userId, String input) {
        if (input == null || input.isBlank()) {
            return new ReflexResult(Optional.empty(), input != null ? input : "");
        }

        // Pass 1: Command check (short-circuit)
        for (AgentReflex reflex : reflexes) {
            if (reflex.matches(input)) {
                try {
                    // Pass 1: Command short-circuit — sanitizedInput is unused since LLM is bypassed
                    return new ReflexResult(Optional.ofNullable(reflex.execute(sessionId, userId, input)), input);
                } catch (Exception e) {
                    String sanitizedInput = input.length() > 200 ? input.substring(0, 200) + "...[truncated]" : input;
                    log.errorf("[REFLEX-FAILURE] reflex=%s session=%s user=%s input=%s error=%s", 
                        reflex.getClass().getSimpleName(), sessionId, userId, sanitizedInput, e.getMessage(), e);
                    return new ReflexResult(Optional.of("⚠️ System Error during reflex execution: " + e.getMessage()), input);
                }
            }
        }

        // Pass 2: Sanitization (chain output through all reflexes)
        String sanitized = input;
        for (AgentReflex reflex : reflexes) {
            try {
                sanitized = reflex.sanitize(sessionId, userId, sanitized);
            } catch (Exception e) {
                log.errorf("[REFLEX-SANITIZE-FAILURE] reflex=%s error=%s", reflex.getClass().getSimpleName(), e.getMessage(), e);
            }
        }

        return new ReflexResult(Optional.empty(), sanitized);
    }
}

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

    public Optional<String> processIfReflex(String sessionId, String userId, String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }

        for (AgentReflex reflex : reflexes) {
            if (reflex.matches(input)) {
                try {
                    return Optional.ofNullable(reflex.execute(sessionId, userId));
                } catch (Exception e) {
                    String sanitizedInput = input.length() > 200 ? input.substring(0, 200) + "...[truncated]" : input;
                    log.errorf("[REFLEX-FAILURE] reflex=%s session=%s user=%s input=%s error=%s", 
                        reflex.getClass().getSimpleName(), sessionId, userId, sanitizedInput, e.getMessage(), e);
                    return Optional.of("⚠️ System Error during reflex execution: " + e.getMessage());
                }
            }
        }

        return Optional.empty();
    }
}

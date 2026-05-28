package com.arcclaw.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;

@ApplicationScoped
public class ToolObservationSanitizer {

    @Inject
    Instance<ObservationTransformer> transformers;

    /**
     * Sanitizes a raw tool observation before it is appended to agent history.
     * Delegates to an ordered chain of {@link ObservationTransformer} implementations.
     *
     * This method NEVER blocks the agent loop - it transforms and returns.
     *
     * @param toolName       the tool that produced the observation (for logging)
     * @param rawObservation the raw string returned by the tool
     * @return sanitized observation, never null
     */
    public String sanitize(String toolName, String rawObservation) {
        if (rawObservation == null) return "";

        List<ObservationTransformer> ordered = transformers.stream()
                .sorted(Comparator.comparingInt(ObservationTransformer::priority))
                .toList();

        String result = rawObservation;
        for (ObservationTransformer t : ordered) {
            result = t.transform(toolName, result);
        }
        return result;
    }
}

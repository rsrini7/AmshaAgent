package com.arcclaw.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.reflex.PiiScanReflex;
import com.arcclaw.core.reflex.PromptInjectionReflex;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ToolObservationSanitizer {

    private static final Logger LOG = Logger.getLogger(ToolObservationSanitizer.class);
    private static final int MAX_OBSERVATION_BYTES = 16 * 1024;
    private static final String TRUNCATION_MARKER = "\n[OBSERVATION TRUNCATED — exceeds 16 KiB limit]";

    @Inject
    PromptInjectionReflex injectionReflex;

    @Inject
    PiiScanReflex piiReflex;

    /**
     * Sanitizes a raw tool observation before it is appended to agent history.
     * Three operations in order:
     * 1. Hard length cap — prevents prompt-stuffing via tool output.
     * 2. Prompt injection pattern redaction via PromptInjectionReflex.sanitize().
     * 3. PII redaction via PiiScanReflex.redactPii().
     *
     * This method NEVER blocks the agent loop — it redacts and returns.
     *
     * @param toolName       the tool that produced the observation (for logging)
     * @param rawObservation the raw string returned by the tool
     * @return sanitized observation, never null
     */
    public String sanitize(String toolName, String rawObservation) {
        if (rawObservation == null) return "";

        // Step 1: hard length cap
        String truncated;
        if (rawObservation.length() > MAX_OBSERVATION_BYTES) {
            LOG.warnf("Tool '%s' observation truncated from %d to %d bytes",
                toolName, rawObservation.length(), MAX_OBSERVATION_BYTES);
            truncated = rawObservation.substring(0, MAX_OBSERVATION_BYTES) + TRUNCATION_MARKER;
        } else {
            truncated = rawObservation;
        }

        // Step 2: injection pattern redaction — call sanitize() directly, not via ReflexRegistry
        String deinjected = injectionReflex.sanitize(null, null, truncated);

        // Step 3: PII redaction — always-on, config-independent
        return piiReflex.redactPii(deinjected);
    }
}

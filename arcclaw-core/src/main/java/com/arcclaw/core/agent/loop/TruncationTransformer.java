package com.arcclaw.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

/**
 * Hard length cap — prevents prompt-stuffing via tool output.
 */
@ApplicationScoped
public class TruncationTransformer implements ObservationTransformer {

    private static final Logger LOG = Logger.getLogger(TruncationTransformer.class);
    private static final int MAX_OBSERVATION_CODEPOINTS = 16 * 1024;
    private static final String TRUNCATION_MARKER = "\n[OBSERVATION TRUNCATED — exceeds 16 KiB limit]";

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public String transform(String toolName, String input) {
        if (input == null) return "";
        
        if (input.codePointCount(0, input.length()) > MAX_OBSERVATION_CODEPOINTS) {
            int safeOffset = input.offsetByCodePoints(0, MAX_OBSERVATION_CODEPOINTS);
            LOG.warnf("Tool '%s' observation truncated from %d to %d codepoints",
                toolName, input.codePointCount(0, input.length()),
                MAX_OBSERVATION_CODEPOINTS);
            return input.substring(0, safeOffset) + TRUNCATION_MARKER;
        }
        return input;
    }
}

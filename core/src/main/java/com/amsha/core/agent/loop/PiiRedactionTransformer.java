package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.core.security.InputSanitizer;

/**
 * PII redaction via PiiScanReflex.redactPii().
 */
@ApplicationScoped
public class PiiRedactionTransformer implements ObservationTransformer {

    @Inject
    @InputSanitizer.PII
    InputSanitizer piiReflex;

    @Override
    public int priority() {
        return 30;
    }

    @Override
    public String transform(String toolName, String input) {
        if (input == null) return "";
        return piiReflex.sanitize(input);
    }
}

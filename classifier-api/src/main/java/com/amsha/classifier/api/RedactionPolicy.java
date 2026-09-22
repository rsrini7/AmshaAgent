package com.amsha.classifier.api;

import java.util.Objects;
import java.util.Set;

/**
 * Redaction rules applied to state projections prior to evaluation.
 *
 * @param redactPii whether personal identifying information is redacted
 * @param redactSecrets whether credentials and secret tokens are stripped
 * @param maskedFields specific projection field paths that must be masked
 */
public record RedactionPolicy(boolean redactPii, boolean redactSecrets, Set<String> maskedFields) {

    public static final RedactionPolicy DEFAULT = new RedactionPolicy(true, true, Set.of());

    public RedactionPolicy {
        if (maskedFields == null) {
            maskedFields = Set.of();
        } else {
            for (String field : maskedFields) {
                Objects.requireNonNull(field, "masked field");
                if (field.isBlank()) {
                    throw new IllegalArgumentException("masked field name must not be blank");
                }
            }
            maskedFields = Set.copyOf(maskedFields);
        }
    }
}

package com.amsha.classifier.api;

import java.util.Objects;
import java.util.Set;

/**
 * Policy governing allow-listed state projection and redaction before serialization
 * across external or separate-process provider boundaries.
 * Missing or invalid policy must fail closed.
 *
 * @param allowedFields explicit set of permitted top-level state projection field names
 * @param redactionPolicy redaction and masking rules applied to projected values
 */
public record StateProjectionPolicy(Set<String> allowedFields, RedactionPolicy redactionPolicy) {

    public StateProjectionPolicy {
        Objects.requireNonNull(allowedFields, "allowedFields");
        if (allowedFields.isEmpty()) {
            throw new IllegalArgumentException("allowedFields must not be empty; allow-list projection required");
        }
        for (String field : allowedFields) {
            Objects.requireNonNull(field, "allowed field");
            if (field.isBlank()) {
                throw new IllegalArgumentException("allowed field name must not be blank");
            }
        }
        allowedFields = Set.copyOf(allowedFields);
        Objects.requireNonNull(redactionPolicy, "redactionPolicy");
    }

    public static StateProjectionPolicy of(Set<String> allowedFields, RedactionPolicy redactionPolicy) {
        return new StateProjectionPolicy(allowedFields, redactionPolicy);
    }

    public static StateProjectionPolicy ofDefault(Set<String> allowedFields) {
        return new StateProjectionPolicy(allowedFields, RedactionPolicy.DEFAULT);
    }
}

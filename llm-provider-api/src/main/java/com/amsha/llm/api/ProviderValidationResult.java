package com.amsha.llm.api;

import java.util.List;

/**
 * Result of tiered provider validation. Immutable; an eligible result carries no errors. Errors are
 * safe field messages only (never secrets, endpoints, or credential material).
 */
public record ProviderValidationResult(boolean eligible, List<String> errors) {

    public ProviderValidationResult {
        if (errors == null) errors = List.of();
        else errors = List.copyOf(errors);
    }

    public static ProviderValidationResult ok() {
        return new ProviderValidationResult(true, List.of());
    }

    public static ProviderValidationResult rejected(String... errors) {
        return new ProviderValidationResult(false, List.of(errors));
    }

    public static ProviderValidationResult rejected(List<String> errors) {
        return new ProviderValidationResult(false, errors);
    }
}

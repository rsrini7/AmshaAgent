package com.amsha.core.routing;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of application-level model profile and connection draft validation.
 *
 * <p>Immutable; a valid result carries no error messages. Errors contain safe field
 * messages only, never credentials, raw tokens, or sensitive payload material.</p>
 */
public record ProfileValidationResult(boolean valid, List<String> errors) {

    public ProfileValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
        if (valid && !errors.isEmpty()) {
            throw new IllegalArgumentException("A valid ProfileValidationResult cannot carry errors");
        }
    }

    public static ProfileValidationResult ok() {
        return new ProfileValidationResult(true, List.of());
    }

    public static ProfileValidationResult fail(List<String> errors) {
        return new ProfileValidationResult(false, errors);
    }

    public static ProfileValidationResult fail(String firstError, String... moreErrors) {
        if (firstError == null) {
            return new ProfileValidationResult(false, List.of());
        }
        if (moreErrors == null || moreErrors.length == 0) {
            return new ProfileValidationResult(false, List.of(firstError));
        }
        List<String> list = new ArrayList<>();
        list.add(firstError);
        for (String err : moreErrors) {
            if (err != null) {
                list.add(err);
            }
        }
        return new ProfileValidationResult(false, list);
    }

    public String errorSummary() {
        return String.join("; ", errors);
    }
}

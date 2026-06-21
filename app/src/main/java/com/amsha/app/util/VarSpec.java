package com.amsha.app.util;

/**
 * Describes a single environment variable that Amsha Agent needs to run.
 *
 * <p>Each instance captures the variable name, a human-readable description,
 * and step-by-step instructions for obtaining / generating the value.
 *
 * @param key          the environment variable name (e.g. {@code AMSHA_VAULT_PEPPER})
 * @param description  plain-English explanation of what this variable does
 * @param howToGet     ordered list of instructions for obtaining or generating the value
 */
public record VarSpec(String key, String description, String... howToGet) {
    // Compact record — all accessors, equals, hashCode, toString are auto-generated.
}

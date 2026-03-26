package com.arcclaw.core.security;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.inject.Qualifier;

/**
 * Common interface for security components that sanitize strings before
 * they reach the LLM or enter persistent history.
 *
 * Implementations should perform context-independent redaction (e.g., regex-based PII masking).
 */
public interface InputSanitizer {
    /**
     * Sanitizes the input string.
     *
     * @param input the raw string to sanitize
     * @return the sanitized string
     */
    String sanitize(String input);

    @Qualifier
    @Retention(RUNTIME)
    @Target({METHOD, FIELD, PARAMETER, TYPE})
    @interface PII {}

    @Qualifier
    @Retention(RUNTIME)
    @Target({METHOD, FIELD, PARAMETER, TYPE})
    @interface Injection {}
}

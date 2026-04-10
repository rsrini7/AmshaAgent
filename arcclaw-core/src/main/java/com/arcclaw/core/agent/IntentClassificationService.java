package com.arcclaw.core.agent;

import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Shared service for detecting intent patterns within user goals.
 * Centralizes regex logic used by the state machine and reasoning engine.
 */
@ApplicationScoped
public class IntentClassificationService {

    // Matches goals that indicate a desire to persist or store information
    private static final Pattern WRITE_INTENT_PATTERN = Pattern.compile(
        "\\b(save|store|ingest|remember|add to memory|note that|call me)\\b",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * returns true if the text indicates a "write intent" — specifically 
     * for storing information in the long-term memory (ingest_document).
     */
    public boolean isWriteIntent(String text) {
        if (text == null || text.isBlank()) return false;
        return WRITE_INTENT_PATTERN.matcher(text).find();
    }
}

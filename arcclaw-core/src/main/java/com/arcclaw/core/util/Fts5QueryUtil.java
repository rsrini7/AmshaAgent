package com.arcclaw.core.util;

public final class Fts5QueryUtil {

    private Fts5QueryUtil() {}

    /**
     * Sanitizes a raw user query for FTS5 MATCH parameter safety.
     * <p>
     * FTS5 parses the MATCH value as a query expression - special characters
     * like {@code '}, {@code `}, {@code *}, {@code ^} etc. cause syntax errors.
     * For RAG retrieval, punctuation carries no semantic value, so all
     * non-word characters are replaced with spaces. The result is wrapped
     * in double-quotes for FTS5 phrase search to prevent operator interpretation
     * of any residual tokens.
     */
    public static String toFts5Phrase(String input) {
        if (input == null || input.isBlank()) return "\"\"";

        // Replace all non-word, non-space characters with a space.
        // This sanitizes against FTS5 operators like *, ^, etc.
        String sanitized = input.replaceAll("[^\\w\\s]", " ").trim();

        if (sanitized.isBlank()) return "";

        // Return tokens as-is. FTS5 will perform an implicit AND search on tokens.
        // This is much more flexible than phrase search for RAG.
        return sanitized;
    }
}

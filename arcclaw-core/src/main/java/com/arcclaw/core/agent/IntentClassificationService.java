package com.arcclaw.core.agent;

import java.util.Set;
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

    private static final Set<String> FOLLOWUP_SIGNALS = Set.of(
        "same", "again", "retry", "execute", "run", "use sandbox", 
        "in java", "in go", "in python", "in typescript", "in kotlin", "in rust"
    );

    private static final Pattern CONTINUITY_PATTERN = Pattern.compile(
        "(?i)^\\s*(try\\s+again|retry|do\\s+it\\s+again|once\\s+more|redo|go\\s+ahead|proceed|continue)(\\s+.*)?$"
    );

    private static final Pattern TOOL_VERB_PATTERN = Pattern.compile(
        "\\b(search|find|fetch|list|get|github|gh|repo|read|browse|open|go to|url|site|web|run|exec|code|script|program|schedule|at|every|after|in|save|store|ingest|remember|note|weather|news|price|latest|stock|status|info|history|check|bullet|recap|summarize)\\b",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern HISTORY_QUERY_PATTERN = Pattern.compile(
        "\\b(summarize|recap|reiterate|what did we|discussed|so far|bullet our points|bullets?|bullet points|bulletized|bulletized summary|history|above|earlier|previous|before|summary)\\b",
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

    /**
     * Returns true if the goal inherently requires conversation history to answer.
     */
    public boolean isHistoryQuery(String text) {
        if (text == null || text.isBlank()) return false;
        return HISTORY_QUERY_PATTERN.matcher(text).find();
    }

    /**
     * Returns true for short messages that lack any verbs suggesting tool usage.
     * Used to strip tool definitions from system prompt to save tokens (Fix B).
     */
    public boolean isSimpleConversational(String text) {
        if (text == null || text.isBlank()) return true;
        if (text.length() > 60) return false;
        return !TOOL_VERB_PATTERN.matcher(text).find();
    }

    /**
     * Returns true for short messages containing follow-up keywords,
     * indicating a desire to continue with the current agent/context.
     */
    public boolean isFollowUpHeuristic(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase();
        return lower.length() < 150 && 
               FOLLOWUP_SIGNALS.stream().anyMatch(lower::contains);
    }

    /**
     * Returns true if the user is explicitly asking to retry or continue 
     * a previous operation using standard phrases.
     */
    public boolean isContinuityCommand(String text) {
        if (text == null || text.isBlank()) return false;
        return CONTINUITY_PATTERN.matcher(text).matches();
    }
}

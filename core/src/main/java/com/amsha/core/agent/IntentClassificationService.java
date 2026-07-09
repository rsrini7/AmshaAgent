package com.amsha.core.agent;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.regex.Pattern;

/**
 * Shared service for detecting intent patterns within user goals.
 * Centralizes regex logic used by the state machine and reasoning engine.
 */
@ApplicationScoped
public class IntentClassificationService {

    private static final Pattern WRITE_INTENT_PATTERN = Pattern.compile(
            "\\b(save|store|ingest|remember|add to memory|note that|call me|my name is|refer to me as|address me as)\\b",
            Pattern.CASE_INSENSITIVE);

    // Retry commands mean "do the same task again".
    // Continuation commands mean "keep going with the current task".
    // Execution follow-ups ("execute", "run it", "do it") are NOT retry/continuation — they use the NEW goal.
    private static final Pattern RETRY_PATTERN = Pattern.compile(
            "(?i)^\\s*(?:try\\s+again|retry|do\\s+it\\s+again|once\\s+more|redo|same|again|repeat)(?:[.!?]+\\s*)?(?:\\s+.*)?$");

    private static final Pattern CONTINUATION_PATTERN = Pattern.compile(
            "(?i)^\\s*(?:go\\s+ahead|proceed|continue|carry\\s+on|keep\\s+going|move\\s+ahead|resume|yes|yep|yeah|sure|ok|okay)"
                    + "(?:[.!?]+\\s*)?(?:\\s+.*)?$");

    private static final Pattern TOOL_VERB_PATTERN = Pattern.compile(
            "\\b(search|find|fetch|list|get|github|gh|repo|read|browse|open|go to|url|site|web|run|exec|code|script|program|schedule|at|every|after|in|save|store|ingest|remember|note|weather|news|price|latest|stock|status|info|history|check|bullet|recap|summarize|summary)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern HISTORY_QUERY_PATTERN = Pattern.compile(
            "\\b(summarize|recap|reiterate|what did we|discussed|so far|bullet our points|bullets?|bullet points|bulletized|bulletized summary|history|above|earlier|previous|before|summary)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern IDENTITY_QUERY_PATTERN = Pattern.compile(
            "\\b(who am i|my name|do you know me|user name|user identity|what i love|my hobby|my hobbies|my preference|my interests|about me|my contact|my phone|my number|my email|my address|contact num|phone num|email addr)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SCHEDULE_INTENT_PATTERN = Pattern.compile(
            "\\b(schedule|cron|run at|run every|run daily|run hourly|every\\s+(hour|minute|day|week|month|\\d+)|daily|hourly|weekly|monthly)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SELF_EVOLVE_INTENT_PATTERN = Pattern.compile(
            "\\b(self[\\s_-]*evolve(?:[\\s_-]*reflect)?|self_evolve_reflect|behavioral directives?|directive status|current directive status|synthesize behavioral directives?)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * returns true if the text indicates a "write intent" - specifically
     * for storing information in the long-term memory (ingest_document).
     */
    public boolean isWriteIntent(String text) {
        if (text == null || text.isBlank()) return false;
        // §Hardening: Interrogative phrases (ending with '?') are recall intents, not write intents.
        // e.g. "you still remember?" vs "remember that I am Srini"
        // Check the FIRST LINE only — system corrections may be appended after newlines.
        String firstLine =
                text.contains("\n") ? text.substring(0, text.indexOf('\n')).trim() : text.trim();
        if (firstLine.endsWith("?")) {
            return false;
        }
        return WRITE_INTENT_PATTERN.matcher(firstLine).find();
    }

    /**
     * Returns true if the goal inherently requires conversation history to answer.
     */
    public boolean isHistoryQuery(String text) {
        if (text == null || text.isBlank()) return false;
        return HISTORY_QUERY_PATTERN.matcher(text).find();
    }

    /**
     * Returns true if the goal is an identity-related query requiring memory lookup.
     */
    public boolean isIdentityQuery(String text) {
        if (text == null || text.isBlank()) return false;
        return IDENTITY_QUERY_PATTERN.matcher(text).find();
    }

    /**
     * Returns true if the goal inherently requires task scheduling.
     */
    public boolean isScheduleIntent(String text) {
        if (text == null || text.isBlank()) return false;
        return SCHEDULE_INTENT_PATTERN.matcher(text).find();
    }

    /**
     * Returns true if the request is about self-evolve reflection, directive synthesis,
     * or reading the current behavioral directive status.
     */
    public boolean isSelfEvolveIntent(String text) {
        if (text == null || text.isBlank()) return false;
        return SELF_EVOLVE_INTENT_PATTERN.matcher(text).find();
    }

    /**
     * Returns true for short messages that lack any verbs suggesting tool usage.
     * Used to strip tool definitions from system prompt to save tokens (Fix B).
     */
    public boolean isSimpleConversational(String text) {
        if (text == null || text.isBlank()) return true;
        // §Issue 2: PII-redacted goals should be treated as simple chitchat
        if (text.contains(AgentSentinels.REDACTED_HYPHEN)) return true;

        // § Bug 2: Identity queries should not be classified as chitchat because they require memory lookup
        if (isIdentityQuery(text)) return false;

        if (text.length() > 60) return false;
        return !TOOL_VERB_PATTERN.matcher(text).find();
    }

    private static final Pattern FOLLOWUP_PATTERN = Pattern.compile(
            "(?i)^\\s*(do\\s+)?(same|again|retry|execute|run|use\\s+sandbox|in\\s+(java|go|python|typescript|kotlin|rust|bash))\\b.*");

    private static final Pattern CHITCHAT_EXCLUSION_PATTERN =
            Pattern.compile("(?i)^\\s*(thanks|thank\\s+you|ok|okay|cancel|stop|bye|hello|hi|hey)[.!?]?\\s*$");

    /**
     * Returns true for short messages containing specific follow-up signals,
     * indicating a desire to continue with the current agent/context.
     * §Refinement: Uses a stricter pattern to avoid false positives on general chitchat.
     */
    public boolean isFollowUpHeuristic(String text) {
        if (text == null || text.isBlank()) return false;
        if (CHITCHAT_EXCLUSION_PATTERN.matcher(text).matches()) return false;

        return text.length() < 150 && FOLLOWUP_PATTERN.matcher(text).matches();
    }

    /**
     * Returns true if the user is explicitly asking to retry a previous operation.
     */
    public boolean isRetryCommand(String text) {
        if (text == null || text.isBlank()) return false;
        return RETRY_PATTERN.matcher(text).matches();
    }

    /**
     * Returns true if the user is explicitly asking to continue the current task.
     */
    public boolean isContinuationCommand(String text) {
        if (text == null || text.isBlank()) return false;
        return CONTINUATION_PATTERN.matcher(text).matches();
    }

    /**
     * Backward-compatible alias for the combined resume semantics.
     * Prefer {@link #isRetryCommand(String)} or {@link #isContinuationCommand(String)}.
     */
    @Deprecated
    public boolean isContinuityCommand(String text) {
        return isRetryCommand(text) || isContinuationCommand(text);
    }

    /**
     * Combined resume semantics used when either retry or continue should restore
     * the prior checkpoint goal and context.
     */
    public boolean isResumeCommand(String text) {
        return isContinuityCommand(text);
    }
}

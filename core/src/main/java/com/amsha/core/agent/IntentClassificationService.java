package com.amsha.core.agent;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.regex.Pattern;

import com.amsha.tool.api.AgentSentinels;

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
            "\\b(search|find|fetch|list|get|github|gh|repo|read|browse|open|go to|url|site|web|run|exec|execute|code|script|program|schedule|at|every|after|in|save|store|ingest|remember|note|weather|news|price|latest|stock|status|info|history|check|bullet|recap|summarize|summary|sandbox|compile|write|create|generate|build|implement|develop|solve|compute|calculate|debug|fix|test|translate|convert|transform|parse|analyze|diagram|draw|plot|java|python|rust|golang|sql|bash|html|css|json|yaml|xml)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern HISTORY_QUERY_PATTERN = Pattern.compile(
            "\\b(summarize|recap|reiterate|what did we|discussed|so far|bullet our points|bullets?|bullet points|bulletized|bulletized summary|history|above|earlier|previous|before|summary|"
                    + "first\\s+(?:message|conversation|question|topic|thing|exchange)|conversation\\s+start|"
                    + "start\\s+of\\s+(?:the\\s+)?conversation|beginning\\s+of\\s+(?:the\\s+)?conversation)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern IDENTITY_QUERY_PATTERN = Pattern.compile(
            "\\b(who\\s+am\\s+i|my\\s+name|do\\s+you\\s+know\\s+me|user\\s+name|user\\s+identity|what\\s+i\\s+love|my\\s+hobby|my\\s+hobbies|my\\s+preference|my\\s+interests|about\\s+me|my\\s+contact|my\\s+phone|my\\s+number|my\\s+email|my\\s+address|contact\\s+num|phone\\s+num|email\\s+addr|"
                    + "who\\s+(?:are|r)\\s+(?:you|u)|who\\s+(?:is|s)\\s+this|what\\s+(?:are|r)\\s+(?:you|u)|what\\s+is\\s+your\\s+name|what\\s+model|which\\s+model|tell\\s+me\\s+about\\s+yourself|what\\s+can\\s+you\\s+do|what\\s+is\\s+amsha|who\\s+is\\s+amsha|your\\s+identity|your\\s+name|your\\s+role|your\\s+purpose)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CONVERSATIONAL_PHRASE_PATTERN = Pattern.compile(
            "^\\s*(?:how\\s+(?:are\\s+you(?:\\s+doing)?|r\\s+u|is\\s+it\\s+going|do\\s+you\\s+do)|"
                    + "thanks(?:\\s+(?:a\\s+lot|very\\s+much|for\\s+the\\s+help))?|thank\\s+you(?:\\s+(?:so\\s+much|very\\s+much|for\\s+(?:the\\s+help|your\\s+help)))?|thx|ty|"
                    + "ok|okay|cool|nice(?:\\s+job)?|great|awesome|got\\s+it|sounds\\s+good|perfect|sure|understood|"
                    + "bye|goodbye|cya|see\\s+you|farewell|good\\s+night|have\\s+a\\s+(?:good|nice|great)\\s+day|"
                    + "tell\\s+me\\s+a\\s+joke|make\\s+me\\s+laugh|show\\s+me\\s+empathy|what\\s+do\\s+you\\s+think\\??)\\s*[.!?]*$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SCHEDULE_INTENT_PATTERN = Pattern.compile(
            "\\b(schedule|cron|run at|run every|run daily|run hourly|every\\s+(hour|minute|day|week|month|\\d+)|daily|hourly|weekly|monthly|today|tonight|tomorrow|next\\s+(hour|day|week|month)|(?:in|after)\\s+\\d+\\s+(seconds?|minutes?|hours?|days?|weeks?|months?))\\b",
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
     * Detects a short execution follow-up such as "run it" or "execute and share
     * the result". Unlike {@link #isFollowUpHeuristic(String)} this excludes
     * retry/continuation phrases so callers can route execution to the coder.
     */
    public boolean isExecutionFollowUp(String text) {
        if (text == null || text.isBlank()) return false;
        return text.length() < 150 && EXECUTION_FOLLOWUP_PATTERN.matcher(text).matches();
    }

    /**
     * Returns true if the goal is an identity-related query requiring memory lookup
     * or agent self-identity / capabilities.
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

    private static final Pattern GREETING_PATTERN = Pattern.compile(
            "^\\s*(?:hi|hello|hey|howdy|hola|namaste|greetings|good\\s+(?:morning|afternoon|evening|day))(?::?[.!?]*|(?:\\s+(?:there|all|everyone|team|friend|amsha|assistant|bot|folks))?[.!?]*)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Returns true if the text is a pure greeting (e.g. "Hi", "Hello", "Hey", "Good morning").
     */
    public boolean isGreeting(String text) {
        if (text == null || text.isBlank()) return false;
        String trimmed = text.trim();
        return GREETING_PATTERN.matcher(trimmed).matches()
                && !TOOL_VERB_PATTERN.matcher(trimmed).find();
    }

    /**
     * Returns true if the text is a simple conversational message (e.g. greeting or polite remark)
     * that does NOT require external tools, code generation, or task delegation.
     * Used to strip tool definitions from system prompt to save tokens (Fix B).
     */
    public boolean isSimpleConversational(String text) {
        if (text == null || text.isBlank()) return false;
        if (isGreeting(text)) return true;
        // §Issue 2: PII-redacted goals should be treated as simple chitchat
        if (text.contains(AgentSentinels.REDACTED_HYPHEN)) return true;

        // Identity queries should not be classified as chitchat because they require memory lookup or persona identity
        if (isIdentityQuery(text)) return false;

        // Execution follow-ups must retain context and tool availability.
        if (isFollowUpHeuristic(text)) return false;

        return CONVERSATIONAL_PHRASE_PATTERN.matcher(text.trim()).matches();
    }

    private static final Pattern FOLLOWUP_PATTERN = Pattern.compile(
            "(?i)^\\s*(?:do\\s+it|(?:do\\s+)?(?:same|again|retry|execute|run|use\\s+sandbox|in\\s+(java|go|python|typescript|kotlin|rust|bash)))\\b.*");

    private static final Pattern EXECUTION_FOLLOWUP_PATTERN = Pattern.compile("(?i)^\\s*(?:"
            + "do\\s+it"
            + "|(?:execute|compile|use\\s+sandbox)\\b.*"
            + "|run\\b.*\\b(?:it|this|code|program|script|snippet|function|class|method|"
            + "test|tests|build|result|output|app|application|binary|command|sandbox|shell|"
            + "python|java|node|javascript|typescript|go|rust|bash|sql)\\b.*"
            + ")");

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

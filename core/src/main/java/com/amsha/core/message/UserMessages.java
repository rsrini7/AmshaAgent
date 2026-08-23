package com.amsha.core.message;

/**
 * Centralized catalog for all user-facing emoji-prefixed messages.
 * <p>
 * This ensures consistent wording across the platform and simplifies
 * future internationalization (i18n) support.
 */
public final class UserMessages {

    // Authorization & Security
    public static final String NOT_AUTHORIZED_DELEGATION = "❌ You are not authorized to use approval delegation.";
    public static final String NOT_AUTHORIZED_ACTION = "❌ You are not authorized to perform this action.";
    public static final String PII_DETECTED = "🔒 PII detected in your request. Please remove sensitive data.";
    public static final String PROMPT_INJECTION_DETECTED =
            "⚠️ adversarial instruction (prompt injection) detected. Request blocked.";
    public static final String TARGET_SESSION_NOT_AWAITING = "❌ Target session not found or not awaiting approval.";
    public static final String DESTRUCTIVE_GUARD_REPLY = "⛔ I can only perform read operations (view, list). "
            + "Destructive operations such as deleting repositories are not supported.";
    public static final String CLASSIFICATION_RATE_LIMITED =
            "⏳ Rate limited on classification - defaulting to coordinator.";

    // Approval & Session Control
    public static final String RESUMING_TASK = "✅ Resuming task...";
    public static final String APPROVAL_NEEDED = "⏸ Approval needed: %s";
    public static final String TASK_DENIED = "❌ Task denied by user.";
    public static final String ALREADY_PROCESSING = "⏳ Already processing your request. Please wait.";
    public static final String ALREADY_PROCESSING_APPROVAL = "⏳ Already processing your approval. Please wait.";
    public static final String ALREADY_PROCESSING_DENIAL = "⏳ Already processing your denial. Please wait.";
    public static final String SESSION_HALTED = "🛑 System Halted. Workspace terminated.";
    public static final String DELEGATION_AGENT_REQUIRED_FORMAT = "Parameter 'agent' is required. Valid agents: %s.";
    public static final String DELEGATION_GOAL_REQUIRED =
            "Parameter 'goal' is required. Please provide a clear, specific goal for the sub-agent.";
    public static final String DELEGATION_SELF_GUARD_FORMAT =
            "Cannot delegate to yourself (%s). You ARE the specialist for this task. STOP delegating and proceed with your own tools directly. Use the context already in the goal — do NOT re-delegate. Available agents: %s";
    public static final String DELEGATION_UNKNOWN_AGENT_FORMAT = "Unknown agent '%s'. Valid agents: %s.";
    public static final String DELEGATION_REJECTED_CAPS_FORMAT =
            "Delegation to %s rejected: target role has capabilities [%s] not permitted for delegating role [%s]. "
                    + "Only roles with sufficient capabilities (e.g. 'coder') can delegate to this agent. "
                    + "Please request the coordinator to delegate via coder.";
    public static final String DELEGATION_FAILED_FORMAT = "Delegation to '%s' failed: %s";
    /**
     * Separator that splits a model-facing observation into its user-safe head and its
     * LLM-directive tail. ToolResultProcessor strips everything from this marker onward
     * before persisting or emitting an answer to the chat channel.
     */
    public static final String CRITICAL_DIRECTIVE_SEPARATOR = "\n\nCRITICAL DIRECTIVE:";

    /**
     * Stable marker embedded in {@link #DELEGATION_PAUSED_FORMAT} so deterministic
     * terminal guards (ToolResultProcessor.handleDelegateOutcome) can detect a paused
     * delegation regardless of whether the delegate tool reports it as success or failure.
     */
    public static final String DELEGATION_PAUSED_MARKER = "PAUSED awaiting human approval";

    /**
     * Model-facing observation for a paused delegation. The user-visible answer is the
     * head before {@link #CRITICAL_DIRECTIVE_SEPARATOR}; the directive tail is consumed
     * by the coordinator LLM only and must never reach the chat channel.
     */
    public static final String DELEGATION_PAUSED_FORMAT =
            "Delegation to '%s' is " + DELEGATION_PAUSED_MARKER + ": tool '%s' needs human approval. "
                    + "The delegated agent's result will be delivered automatically once it is granted."
                    + CRITICAL_DIRECTIVE_SEPARATOR
                    + " This is a system status, not an error. Do NOT retry the delegation. "
                    + "Do NOT add commentary; you must FINISH this turn now.";

    public static final String DELEGATION_EXCEEDED_FORMAT =
            "Agent '%s' exceeded its iteration budget (%d iterations). Try simplifying the goal.";
    public static final String SESSION_CLEARED = "🧹 Session state and temporary workspace cleared.";
    public static final String RESET_COMPLETE_FORMAT =
            "🧹 Agent state, checkpoints, and %d scheduled task(s) have been cleared. "
                    + "Your conversation history has been preserved - we can pick up where we left off.";
    public static final String COMPACTED_SUMMARY_FORMAT = "🗜️ *Compacted Summary* (%d messages):\n%s";
    public static final String ACTIVE_WINDOW_FORMAT = "💬 *Active Window* (%d messages):";
    public static final String NO_HISTORY_FOUND = "📭 No conversation history found for this session.";
    public static final String HISTORY_YOU_PREFIX = ". 👤 *You:* ";
    public static final String HISTORY_AI_PREFIX = ". 🤖 *Amsha Agent:* ";
    public static final String STATUS_FORMAT = """
            📊 *Status:* %s
            🤖 *Model:* %s
            💰 *Cost:* $%.4f / $%.2f (%.1f%%)
            🔢 *Tokens:* %d in + %d out = %d total
            🧠 *Memory:* %s
            ⏱️ *Session:* %s""";
    public static final String NO_PENDING_APPROVAL = "No pending approval found for this session.";
    public static final String NO_PENDING_DENIAL = "No pending denial found for this session.";
    public static final String NO_PENDING_ANYWHERE = "No pending tool executions require approval.";
    public static final String NO_PENDING_ANYWHERE_SESSION =
            "No pending tool executions require approval for this session.";
    public static final String NO_BATCH_AWAITING = "No parallel batch awaiting per-tool approval for this session.";

    // Task / Cron Management
    public static final String CRON_NOT_FOUND = "❌ Cron task not found or not yours.";
    public static final String CRON_NOT_AWAITING = "❌ This task is not awaiting approval (status=%s).";
    public static final String CRON_NO_CHECKPOINT =
            "❌ No pending checkpoint found for this task. It may have already been processed.";
    public static final String CRON_MULTIPLE_AWAITING = "⚠️ Multiple background tasks awaiting approval:\n\n%s";
    public static final String CRON_SINGLE_AWAITING_GUIDE = """
            ⚠️ No active approval in this chat session.

            A background task is waiting for your approval:
            • %s

            Use: `/approve %s` to approve, or `/deny %s` to cancel.""";

    // Generic Errors
    public static final String INTERNAL_ERROR = "⚠️ An internal error occurred. Please try again.";
    public static final String DELEGATION_PAUSED_USER_FALLBACK =
            "⏳ Approval is pending — the delegated task's result will be delivered automatically once granted.";
    /** Stable prefix identifying the escalation handover notice; guards against double-prepend. */
    public static final String ESCALATION_NOTICE_MARKER = "ℹ️ Note:";

    public static final String PERSONA_ESCALATION_NOTICE_FORMAT =
            ESCALATION_NOTICE_MARKER + " '%s' could not run '%s', so this request was handled by '%s' instead.\n\n";
    public static final String PERMISSION_DENIED_NO_WORK_FORMAT =
            "⚠️ I couldn't complete this task: tool '%s' was denied for role '%s' (permission boundary). "
                    + "Nothing was executed, so there are no results to report. "
                    + "Grant the required capability or rephrase the request.";
    public static final String UNEXPECTED_FAULT = "⚠️ An unexpected fault occurred while processing your request.";
    public static final String COULD_NOT_RESUME = "⚠️ Could not resume - session may already be processing.";
    public static final String APPROVAL_ERROR =
            "❌ **Approval Error**: An internal fault occurred while resuming the task.";
    public static final String DENIAL_ERROR =
            "❌ **Denial Error**: An internal fault occurred while terminating the task.";
    public static final String TASK_FAILED = "❌ Task failed. Please try again.";
    public static final String AI_UNEXPECTED_RESPONSE =
            "⚠️ The AI service returned an unexpected response. Please try again.";
    public static final String MODEL_NOT_FOUND_FORMAT =
            "⚠️ **Model Not Found**: The AI model `%s` is either not available or you don't have access to it. Please check your configuration.";
    public static final String RATE_LIMITED_FORMAT =
            "⏳ I'm a little busy right now - please give me a moment and try again in %s.";
    public static final String OVER_CAPACITY =
            "⚠️ The AI service is currently over capacity and cannot process requests. Please try again in a few minutes.";
    public static final String TOOL_VALIDATION_ERROR =
            "❌ Tool execution error: The agent generated an invalid request.";
    public static final String BUDGET_EXCEEDED = "❌ Your session budget limit has been reached.";
    public static final String API_ERROR_FORMAT = "⚠️ API Error: %s";
    public static final String AI_SERVICE_ERROR = "⚠️ The AI service returned an error. Please try again shortly.";
    public static final String INTERNAL_LLM_ERROR =
            "⚠️ I encountered an internal error and could not complete your request. Please try again.";
    public static final String TOKEN_LIMIT_REACHED_FORMAT = """
            ⚠️ **Daily Token Limit Reached**
            • Limit: %s
            • Used: %s
            • Requested: %s

            ⏳ Please try again in %s.""";
    public static final String TOKEN_LIMIT_SIMPLE_FORMAT = "⚠️ Daily token limit reached. Please try again in %s.";

    // Success / Informational
    public static final String TOOL_DENIED_FORMAT = "🚫 Tool '%s' denied.";
    public static final String CRON_DELETED_FORMAT = "✅ Cron job %s deleted.";
    public static final String CRON_NOT_FOUND_ID_FORMAT = "❌ Cron job not found: %s. Use /crons to see valid IDs.";
    public static final String CRON_INVALID_FORMAT = "❌ Invalid cron ID format: %s. Use /crons to see valid IDs.";
    public static final String NO_SCHEDULE_FOUND = "No scheduled tasks found.";
    public static final String USAGE_DELETE_CRON = "Usage: deletecron <id>  |  Use /crons to see valid IDs.";
    public static final String DELETE_CONTEXT_NOT_AVAILABLE = "Cannot delete schedule - user context not available.";
    public static final String LIST_CONTEXT_NOT_AVAILABLE = "Cannot list schedules - user context not available.";

    private UserMessages() {}

    /**
     * Formats an approval needed message with the tool name.
     */
    public static String approvalNeeded(String toolName) {
        return String.format(APPROVAL_NEEDED, toolName);
    }

    /**
     * Formats a tool denied message.
     */
    public static String toolDenied(String toolName) {
        return String.format(TOOL_DENIED_FORMAT, toolName);
    }

    public static String cronSingleAwaitingGuide(String goal, String id) {
        return String.format(CRON_SINGLE_AWAITING_GUIDE, goal, id, id);
    }

    public static String cronNotAwaiting(Object status) {
        return String.format(CRON_NOT_AWAITING, status);
    }

    public static String cronMultipleAwaiting(String list) {
        return String.format(CRON_MULTIPLE_AWAITING, list);
    }

    public static String cronDeleted(String id) {
        return String.format(CRON_DELETED_FORMAT, id);
    }

    public static String cronNotFound(String id) {
        return String.format(CRON_NOT_FOUND_ID_FORMAT, id);
    }

    public static String cronInvalid(String id) {
        return String.format(CRON_INVALID_FORMAT, id);
    }

    public static String delegationAgentRequired(String list) {
        return String.format(DELEGATION_AGENT_REQUIRED_FORMAT, list);
    }

    public static String delegationSelfGuard(String agent, String list) {
        return String.format(DELEGATION_SELF_GUARD_FORMAT, agent, list);
    }

    public static String delegationUnknownAgent(String agent, String list) {
        return String.format(DELEGATION_UNKNOWN_AGENT_FORMAT, agent, list);
    }

    public static String delegationRejectedCaps(String target, String unauthorized, String current) {
        return String.format(DELEGATION_REJECTED_CAPS_FORMAT, target, unauthorized, current);
    }

    public static String delegationFailed(String target, String error) {
        return String.format(DELEGATION_FAILED_FORMAT, target, error);
    }

    public static String delegationPaused(String target, String tool) {
        return String.format(DELEGATION_PAUSED_FORMAT, target, tool);
    }

    /**
     * Strips the LLM-directive tail from a model-facing observation, returning the
     * user-safe head. Falls back to the generic paused notice when the observation
     * carries no directive separator.
     */
    public static String userSafePortion(String observation) {
        if (observation == null || observation.isBlank()) {
            return DELEGATION_PAUSED_USER_FALLBACK;
        }
        int idx = observation.indexOf(CRITICAL_DIRECTIVE_SEPARATOR);
        String head = idx >= 0 ? observation.substring(0, idx).trim() : "";
        return head.isEmpty() ? DELEGATION_PAUSED_USER_FALLBACK : head;
    }

    public static String personaEscalationNotice(String fromRole, String toolName, String toRole) {
        return String.format(PERSONA_ESCALATION_NOTICE_FORMAT, fromRole, toolName, toRole);
    }

    public static String delegationExceeded(String target, int iterations) {
        return String.format(DELEGATION_EXCEEDED_FORMAT, target, iterations);
    }

    public static String statusMessage(
            String status,
            String model,
            double cost,
            double budget,
            double pct,
            int input,
            int output,
            int total,
            String memory,
            String sessionId) {
        return String.format(STATUS_FORMAT, status, model, cost, budget, pct, input, output, total, memory, sessionId);
    }

    public static String statusMessage(
            String status,
            double cost,
            double budget,
            double pct,
            int input,
            int output,
            int total,
            String memory,
            String sessionId) {
        return statusMessage(status, "default", cost, budget, pct, input, output, total, memory, sessionId);
    }

    public static String sessionReset(long deletedCrons) {
        return String.format(RESET_COMPLETE_FORMAT, deletedCrons);
    }

    public static String modelNotFound(String model) {
        return String.format(MODEL_NOT_FOUND_FORMAT, model);
    }

    public static String rateLimited(String waitTime) {
        return String.format(RATE_LIMITED_FORMAT, waitTime);
    }

    public static String apiError(String message) {
        return String.format(API_ERROR_FORMAT, message);
    }

    public static String tokenLimitDetailed(String limit, String used, String requested, String waitTime) {
        return String.format(TOKEN_LIMIT_REACHED_FORMAT, limit, used, requested, waitTime);
    }

    public static String tokenLimitSimple(String waitTime) {
        return String.format(TOKEN_LIMIT_SIMPLE_FORMAT, waitTime);
    }
}

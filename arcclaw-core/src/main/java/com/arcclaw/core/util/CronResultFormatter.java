package com.arcclaw.core.util;

import com.arcclaw.core.agent.result.AgentResult;

/**
 * Shared logic for rendering {@link AgentResult} to user-friendly text.
 * Unified across interactive sessions and background cron tasks.
 */
public class CronResultFormatter {

    public static String format(AgentResult result) {
        String reply = switch (result) {
            case AgentResult.Finished f       -> f.answer();
            case AgentResult.AwaitingApproval a -> "⏸ Approval needed for tool: " + a.toolName() 
                                                + "\nReply /approve or /deny";
            case AgentResult.LoopExceeded _   -> "⏱ I ran out of steps. Try rephrasing your request.";
            case AgentResult.Failed f         -> "❌ " + AgentErrorMessageUtil.sanitizeErrorMessage(f.reason());
        };

        if (reply == null || reply.isBlank()) {
            reply = "✅ Done.";
        }
        return reply;
    }

    /**
     * Specialized format for first-time Cron HITL prompts.
     */
    public static String formatCronHitl(String cronId, String goal, String toolName) {
        return "⚠️ Approval Required for scheduled task.\n\n" +
               "Cron ID: " + cronId + "\n" +
               "Tool: " + toolName + "\n" +
               "Goal: " + goal + "\n\n" +
               "Reply `/approve " + cronId + "` to run it once now, or `/deny " + cronId + "` to cancel.";
    }
}

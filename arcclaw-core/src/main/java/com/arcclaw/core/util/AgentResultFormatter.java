package com.arcclaw.core.util;

import com.arcclaw.core.agent.result.AgentResult;

/**
 * Shared logic for rendering {@link AgentResult} to user-friendly text.
 * Unified across interactive sessions and background cron tasks.
 */
public class AgentResultFormatter {

    public static String format(AgentResult result) {
        String reply = switch (result) {
            case AgentResult.Finished f       -> f.answer();
            case AgentResult.AwaitingApproval a -> {
                StringBuilder sb = new StringBuilder("⏸ Approval needed for tool: ").append(a.toolName());
                if (a.paramsJson() != null && !a.paramsJson().isBlank()) {
                    String display = a.paramsJson().length() > 500
                            ? a.paramsJson().substring(0, 500) + "\n… [truncated]"
                            : a.paramsJson();
                    sb.append("\n\n📋 Params:\n```\n").append(display).append("\n```");
                }
                sb.append("\n\nReply /approve or /deny");
                yield sb.toString();
            }
            case AgentResult.LoopExceeded _   -> "⏱ I ran out of steps. Try rephrasing your request.";
            case AgentResult.Failed f         -> {
                String msg = AgentErrorMessageUtil.sanitizeErrorMessage(f.reason());
                yield (f.category() == AgentResult.FailureCategory.RATE_LIMIT || msg.startsWith("⏳"))
                        ? msg : "❌ " + msg;
            }
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

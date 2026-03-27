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
            case AgentResult.AwaitingApproval a -> formatHitlMessage(
                    a.toolName(), a.paramsJson(), null, false);
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
        return "⚠️ Approval Required for background task.\n\n" +
               "Goal: " + goal + "\n\n" +
               formatHitlMessage(toolName, null, cronId, true);
    }

    private static String formatHitlMessage(String compositeName, String paramsJson, String cronId, boolean isCron) {
        StringBuilder sb = new StringBuilder();

        // 1. Detect parallel batch
        boolean isBatch = compositeName != null && compositeName.startsWith("__parallel__:");
        if (isBatch) {
            String[] tools = compositeName.substring("__parallel__:".length()).split(",");
            sb.append("⏸ Parallel batch execution requires approval.\n\n");
            sb.append("Tools pending:\n");
            for (String ts : tools) {
                sb.append("• `").append(ts).append("`\n");
            }
        } else {
            sb.append("⏸ Approval needed for tool: ").append(compositeName);
            if (paramsJson != null && !paramsJson.isBlank()) {
                String display = paramsJson.length() > 500
                        ? paramsJson.substring(0, 500) + "\n… [truncated]"
                        : paramsJson;
                sb.append("\n\n📋 Params:\n```\n").append(display).append("\n```");
            }
        }

        sb.append("\n\n");

        String cronPrefix = isCron && cronId != null ? " " + cronId : "";
        if (isBatch) {
            sb.append("To approve/deny specific tools:\n")
              .append(" `/approve").append(cronPrefix).append(" <toolName>`\n")
              .append(" `/deny").append(cronPrefix).append(" <toolName>`\n\n")
              .append("Or decide all remaining at once:\n")
              .append(" `/approve").append(cronPrefix).append("` or `/deny").append(cronPrefix).append("`");
        } else {
            sb.append("Reply `/approve").append(cronPrefix).append("` or `/deny").append(cronPrefix).append("`");
        }

        return sb.toString();
    }
}

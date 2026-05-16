package com.arcclaw.core.cron;

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.core.agent.result.AgentResult;

/**
 * CDI event fired when a scheduled cron job completes execution.
 * <p>
 * Carries only the pre-rendered answer text - not the full {@link AgentResult}
 * which includes {@code List<ReasoningStep>}. Observers need delivery data,
 * not the entire reasoning trace.
 *
 * @param cronId             The cron job identifier.
 * @param userId             The owning user.
 * @param channelType        Target channel. {@code null} → legacy row, treated as TELEGRAM.
 * @param channelPlatformId  Channel-specific recipient (e.g. Telegram chat ID). May be null → fall back to chatId.
 * @param correlationId      Idempotency/correlation key (executionId UUID).
 * @param renderedAnswer     Pre-formatted text from the scheduler.
 * @param failureCategory    {@code null} if the job succeeded; otherwise the failure category.
 * @param cronSessionId      The full cron session ID for approval notifications, e.g. "cron-{id}-{uuid}". {@code null} for non-approval events.
 * @param toolName           The tool requiring approval. {@code null} for non-approval events.
 */
public record AgentJobCompleted(
    String cronId,
    String userId,
    ChannelType channelType,
    String channelPlatformId,
    String correlationId,
    String renderedAnswer,
    AgentResult.FailureCategory failureCategory,
    String cronSessionId,
    String toolName
) {}

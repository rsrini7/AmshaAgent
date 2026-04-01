package com.arcclaw.channel.telegram;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.lifecycle.StaleApprovalExpired;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class TelegramStaleApprovalObserver {

    @Inject
    TelegramChannelSender sender;

    @Inject
    TelegramMarkdownV2Renderer renderer;

    @ConfigProperty(name = "arcclaw.hitl.timeout-hours", defaultValue = "24")
    long hitlTimeoutHours;

    void onStaleApproval(@ObservesAsync StaleApprovalExpired event) {

        if (!ChannelType.TELEGRAM.name().equalsIgnoreCase(event.channelType())) {
            return; // only handle Telegram channel
        }

        String toolDisplay = event.pendingToolName() != null
            && event.pendingToolName().startsWith(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX)
            ? "batch: " + event.pendingToolName().substring(ReasoningStep.PARALLEL_SENTINEL_PIPE_PREFIX.length())
            : event.pendingToolName();

        // §7.28: escape at call site for MarkdownV2
        String msg = String.format(
            "⏱ Your approval window for `%s` has expired (>%dh).\n" +
            "The pending tool execution was automatically cancelled.\n" +
            "Start a new request to try again.",
            renderer.esc(toolDisplay != null ? toolDisplay : "unknown"),
            hitlTimeoutHours);

        ChannelUser user = new ChannelUser(event.channelPlatformId(), event.channelPlatformId(), "unknown", ChannelType.TELEGRAM);
        // Using sendWithKeyboard(..., null) to leverage its MarkdownV2 parse mode support
        sender.sendWithKeyboard(user, msg, null);
    }
}

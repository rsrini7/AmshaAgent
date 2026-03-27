package com.arcclaw.channel.telegram;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.core.lifecycle.StaleApprovalExpired;

@ApplicationScoped
public class TelegramStaleApprovalObserver {

    @Inject
    TelegramChannelSender sender;

    void onStaleApproval(@Observes StaleApprovalExpired event) {

        if (!ChannelType.TELEGRAM.name().equalsIgnoreCase(event.channelType())) {
            return; // only handle Telegram channel
        }

        String toolDisplay = event.pendingToolName() != null
            && event.pendingToolName().startsWith("parallel:")
            ? "batch: " + event.pendingToolName().substring("parallel:".length())
            : event.pendingToolName();

        String msg = String.format(
            "⏱ Your approval window for `%s` has expired (>%dh).\n" +
            "The pending tool execution was automatically cancelled.\n" +
            "Start a new request to try again.",
            toolDisplay,
            24);

        sender.sendRawToChatId(event.channelPlatformId(), msg);
    }
}

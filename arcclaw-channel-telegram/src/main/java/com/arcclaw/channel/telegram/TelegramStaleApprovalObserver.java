package com.arcclaw.channel.telegram;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.lifecycle.StaleApprovalExpired;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class TelegramStaleApprovalObserver {

    @Inject
    TelegramChannelSender sender;

    @ConfigProperty(name = "arcclaw.hitl.timeout-hours", defaultValue = "24")
    long hitlTimeoutHours;

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
            hitlTimeoutHours);

        sender.sendRaw(new ChannelUser(event.channelPlatformId(), event.channelPlatformId(), "unknown", ChannelType.TELEGRAM), msg);
    }
}

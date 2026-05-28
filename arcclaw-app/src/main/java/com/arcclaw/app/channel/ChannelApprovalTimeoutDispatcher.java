package com.arcclaw.app.channel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import java.util.Optional;

import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.lifecycle.ApprovalTimedOutEvent;

import org.jboss.logging.Logger;

/**
 * Dispatcher that routes approval timeout events to the correct ChannelJobNotifier.
 * <p>
 * Replaces the hardcoded ApprovalTimeoutNotifier.
 */
@ApplicationScoped
public class ChannelApprovalTimeoutDispatcher {

    private static final Logger log = Logger.getLogger(ChannelApprovalTimeoutDispatcher.class);

    @Inject
    ChannelPluginRegistry pluginRegistry;

    void onApprovalTimedOut(@ObservesAsync ApprovalTimedOutEvent event) {
        if (event.channelPlatformId() == null || event.channelPlatformId().isBlank()) {
            log.debugf("No channelPlatformId for session=%s - skipping timeout notification", event.sessionId());
            return;
        }

        Optional<ChannelType> typeOpt = ChannelType.fromString(event.channelType());
        if (typeOpt.isEmpty()) {
            log.warnf(
                    "ChannelApprovalTimeoutDispatcher: unknown channelType '%s' for session %s - "
                            + "dropping timeout notification. Fix: ensure channelType is set on AgentCronEntity.",
                    event.channelType(), event.sessionId());
            return;
        }
        ChannelType type = typeOpt.get();

        pluginRegistry
                .find(type)
                .map(ChannelPlugin::jobNotifier)
                .ifPresentOrElse(
                        n -> n.notifyApprovalTimeout(
                                event.channelPlatformId(),
                                event.sessionId(),
                                event.pendingToolName(),
                                event.timeoutMinutes()),
                        () -> log.warnf("No ChannelPlugin for channelType [%s] - dropping notification", type));
    }
}

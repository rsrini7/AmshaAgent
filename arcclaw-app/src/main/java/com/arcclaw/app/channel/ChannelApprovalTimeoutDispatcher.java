package com.arcclaw.app.channel;

import com.arcclaw.channel.api.ChannelJobNotifier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelType;
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
    @Any
    Instance<ChannelJobNotifier> notifiers;

    void onApprovalTimedOut(@ObservesAsync ApprovalTimedOutEvent event) {
        if (event.channelPlatformId() == null || event.channelPlatformId().isBlank()) {
            log.debugf("No channelPlatformId for session=%s — skipping timeout notification",
                    event.sessionId());
            return;
        }

        ChannelType type = ChannelType.fromString(event.channelType()).orElse(ChannelType.TELEGRAM);

        notifiers.stream()
            .filter(n -> n.supports(type))
            .findFirst()
            .ifPresentOrElse(
                n -> n.notifyApprovalTimeout(event.channelPlatformId(), event.sessionId(), event.pendingToolName(), event.timeoutMinutes()),
                () -> log.warnf("No ChannelJobNotifier found for type [%s]", type)
            );
    }
}

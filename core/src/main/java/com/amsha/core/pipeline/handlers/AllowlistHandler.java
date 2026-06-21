package com.amsha.core.pipeline.handlers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

import com.amsha.channel.api.ChannelAllowlist;
import com.amsha.channel.api.ChannelPlugin;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.channel.ChannelPluginRegistry;
import com.amsha.core.pipeline.HandleResult;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.pipeline.MessageHandler;

import org.jboss.logging.Logger;

/**
 * Validates the user against the allowlist.
 */
@ApplicationScoped
public class AllowlistHandler implements MessageHandler {
    private static final Logger log = Logger.getLogger(AllowlistHandler.class);

    @Inject
    ChannelPluginRegistry channelPluginRegistry;

    @Inject
    AuditService auditService;

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public HandleResult handle(MessageContext context) {
        String userId = context.getUser().platformId();
        String username = context.getUser().displayName();

        Optional<ChannelAllowlist> allowlistOpt =
                channelPluginRegistry.find(context.getChannelType()).map(ChannelPlugin::allowlist);

        if (allowlistOpt.isEmpty()) {
            log.warnf("No allowlist found for channel %s - allowing access by default.", context.getChannelType());
            return HandleResult.CONTINUE;
        }

        ChannelAllowlist allowlist = allowlistOpt.get();
        if (!allowlist.isAllowed(userId, username)) {
            log.warnf(
                    "[SECURITY] Blocked unauthorized user: %s (id: %s) on channel %s",
                    username, userId, context.getChannelType());
            auditService.record(
                    "allowlist",
                    userId,
                    "system",
                    SecurityEventType.UNAUTHORIZED_ACCESS,
                    "channel=" + context.getChannelType() + " username=" + username);

            if (!allowlist.isSilentBlock()) {
                channelPluginRegistry
                        .find(context.getChannelType())
                        .map(ChannelPlugin::sender)
                        .ifPresent(s -> s.sendRaw(context.getUser(), allowlist.getBlockResponse()));
            }
            return HandleResult.STOP;
        }
        return HandleResult.CONTINUE;
    }
}

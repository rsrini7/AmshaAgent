package com.arcclaw.core.pipeline.handlers;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelAllowlist;
import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessageHandler;
import org.jboss.logging.Logger;

/**
 * Validates the user against the allowlist.
 */
@ApplicationScoped
public class AllowlistHandler implements MessageHandler {
    private static final Logger log = Logger.getLogger(AllowlistHandler.class);

    @Inject
    ChannelPluginRegistry channelPluginRegistry;

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public HandleResult handle(MessageContext context) {
        String userId = context.getUser().platformId();
        String username = context.getUser().displayName();

        Optional<ChannelAllowlist> allowlistOpt = channelPluginRegistry.find(context.getChannelType())
                .map(ChannelPlugin::allowlist);

        if (allowlistOpt.isEmpty()) {
            log.warnf("No allowlist found for channel %s - allowing access by default.", context.getChannelType());
            return HandleResult.CONTINUE;
        }

        ChannelAllowlist allowlist = allowlistOpt.get();
        if (!allowlist.isAllowed(userId, username)) {
            log.warnf("[SECURITY] Blocked unauthorized user: %s (id: %s) on channel %s", 
                    username, userId, context.getChannelType());
            
            if (!allowlist.isSilentBlock()) {
                channelPluginRegistry.find(context.getChannelType())
                    .map(ChannelPlugin::sender)
                    .ifPresent(s -> s.sendRaw(context.getUser(), allowlist.getBlockResponse()));
            }
            return HandleResult.STOP;
        }
        return HandleResult.CONTINUE;
    }
}

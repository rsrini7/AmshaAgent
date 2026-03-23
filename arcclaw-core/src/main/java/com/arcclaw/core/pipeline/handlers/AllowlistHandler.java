package com.arcclaw.core.pipeline.handlers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelAllowlist;
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
    ChannelAllowlist allowlist;

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public HandleResult handle(MessageContext context) {
        String userId = context.getUser().platformId();
        String username = context.getUser().displayName();

        if (!allowlist.isAllowed(userId, username)) {
            log.warnf("[SECURITY] Blocked unauthorized user: %s (id: %s)", username, userId);
            if (!allowlist.isSilentBlock()) {
                context.getSender().sendRaw(context.getUser(), allowlist.getBlockResponse());
            }
            return HandleResult.STOP;
        }
        return HandleResult.CONTINUE;
    }
}

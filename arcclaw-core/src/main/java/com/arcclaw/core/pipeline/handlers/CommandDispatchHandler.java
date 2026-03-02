package com.arcclaw.core.pipeline.handlers;

import com.arcclaw.channel.api.ChannelCommand;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessageHandler;
import com.arcclaw.core.reflex.ApproveReflex;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Dispatches /approve to the appropriate reflex logic (cron vs session).
 */
@ApplicationScoped
public class CommandDispatchHandler implements MessageHandler {
    private static final Logger log = Logger.getLogger(CommandDispatchHandler.class);

    @Inject
    ApproveReflex approveReflex;

    @Override
    public int priority() {
        return 30;
    }

    @Override
    public HandleResult handle(MessageContext context) {
        ChannelCommand cmd = ChannelCommand.parse(context.getMessage().text());

        if (cmd instanceof ChannelCommand.Approve approve) {
            log.infof("Handling /approve command for chat %s", context.getChatId());
            String input = approve.cronId().map(id -> "/approve " + id).orElse("/approve");
            String response = approveReflex.execute(context.getChatId(), context.getUser().platformId(), input);
            context.getSender().sendRaw(context.getUser(), response);
            return HandleResult.STOP;
        }

        return HandleResult.CONTINUE;
    }
}

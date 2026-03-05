package com.arcclaw.core.pipeline.handlers;

import com.arcclaw.channel.api.ChannelCommand;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessageHandler;
import com.arcclaw.core.reflex.DenyReflex;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Handles global reflexes like /deny.
 */
@ApplicationScoped
public class ReflexHandler implements MessageHandler {
    private static final Logger log = Logger.getLogger(ReflexHandler.class);

    @Inject
    DenyReflex denyReflex;

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public HandleResult handle(MessageContext context) {
        ChannelCommand cmd = ChannelCommand.parse(context.getMessage().text());

        if (cmd instanceof ChannelCommand.Deny deny) {
            log.infof("Handling /deny reflex for chat %s", context.getChatId());
            String input = deny.cronId().map(id -> "/deny " + id).orElse("/deny");
            String response = denyReflex.execute(context.getSessionId().orElse(""), context.getUser().platformId(), context.getChatId(), input);
            context.getSender().sendRaw(context.getUser(), response);
            return HandleResult.STOP;
        }

        return HandleResult.CONTINUE;
    }
}

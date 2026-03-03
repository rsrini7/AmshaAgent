package com.arcclaw.core.pipeline.handlers;

import com.arcclaw.channel.api.ChannelCommand;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessageHandler;
import com.arcclaw.core.reflex.ReflexRegistry;
import com.arcclaw.core.reflex.ReflexResult;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CommandDispatchHandler implements MessageHandler {
    private static final Logger log = Logger.getLogger(CommandDispatchHandler.class);

    @Inject
    ReflexRegistry reflexRegistry;

    @Override
    public int priority() {
        return 30;
    }

    @Override
    public HandleResult handle(MessageContext context) {
        ChannelCommand cmd = ChannelCommand.parse(context.getMessage().text());
        
        return switch (cmd) {
            case ChannelCommand.Approve a -> { handleReflex(a.cronId().map(id -> "/approve " + id).orElse("/approve"), context); yield HandleResult.STOP; }
            case ChannelCommand.Deny d    -> { handleReflex(d.cronId().map(id -> "/deny " + id).orElse("/deny"), context); yield HandleResult.STOP; }
            case ChannelCommand.Status s  -> { handleReflex("/status", context); yield HandleResult.STOP; }
            case ChannelCommand.Halt h    -> { handleReflex("/halt", context); yield HandleResult.STOP; }
            case ChannelCommand.Reset r   -> { handleReflex("/reset", context); yield HandleResult.STOP; }
            case ChannelCommand.ListTasks l->{ handleReflex("/list", context); yield HandleResult.STOP; }
            case ChannelCommand.Unknown u -> HandleResult.CONTINUE;
        };
    }

    private void handleReflex(String rawCommand, MessageContext context) {
        log.infof("Dispatching command %s for chat %s", rawCommand, context.getChatId());
        ReflexResult result = reflexRegistry.processIfReflex(context.getSessionId().orElse(""), context.getUser().platformId(), rawCommand);
        
        if (result.commandResponse().isPresent()) {
            context.getSender().sendRaw(context.getUser(), result.commandResponse().get());
        }
    }
}
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
            case ChannelCommand.Approve a -> {
                String input = a.cronId()
                        .map(id -> "/approve " + id)
                        .orElse("/approve");
                dispatch(context, input);
                yield HandleResult.STOP;
            }
            case ChannelCommand.Deny d -> {
                String input = d.cronId()
                        .map(id -> "/deny " + id)
                        .orElse("/deny");
                dispatch(context, input);
                yield HandleResult.STOP;
            }
            case ChannelCommand.Status _ -> {
                dispatch(context, "/status");
                yield HandleResult.STOP;
            }
            case ChannelCommand.Halt _ -> {
                dispatch(context, "/halt");
                yield HandleResult.STOP;
            }
            case ChannelCommand.Reset _ -> {
                dispatch(context, "/reset");
                yield HandleResult.STOP;
            }
            case ChannelCommand.ListTasks _ -> {
                dispatch(context, "/list");
                yield HandleResult.STOP;
            }
            case ChannelCommand.Unknown _ -> {
                log.debugf("[CommandDispatch] Unrecognized command '%s', passing to pipeline",
                        context.getMessage().text());
                yield HandleResult.CONTINUE;
            }
        };
    }

    private void dispatch(MessageContext context, String input) {
        String sessionId = context.getSessionId().orElse("");
        String userId    = context.getUser().platformId();
        String chatId    = context.getChatId();

        log.infof("[CommandDispatch] command='%s' session=%s", input, sessionId);

        ReflexResult result = reflexRegistry.processIfReflex(sessionId, userId, chatId, input);

        result.commandResponse().ifPresent(response ->
            context.getSender().sendRaw(context.getUser(), response)
        );
    }
}
package com.arcclaw.core.pipeline.handlers;

import com.arcclaw.channel.api.ChannelCommand;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessageHandler;
import com.arcclaw.core.reflex.ReflexRegistry;
import com.arcclaw.core.reflex.ReflexResult;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;

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
            case ChannelCommand.History _ -> { 
                dispatch(context, "/history"); 
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
            case ChannelCommand.Unknown u -> {
                log.debugf("[CommandDispatch] Unrecognized command '%s', passing to pipeline",
                        context.getMessage().text());
                
                if (u.raw().startsWith("/")) {
                    String sessionId = context.getSessionId().orElse("");
                    String userId = context.getUser().platformId();
                    String chatId = context.getChatId();

                    // FIX: Wrap the fallback reflex execution in WorkspaceContext
                    ReflexResult result = null;
                    try {
                        result = WorkspaceContext.run(
                            sessionId, userId, chatId, 
                            AutonomyLevel.FULL, AgentRoleEnum.COORDINATOR.toScopedName(),
                            () -> reflexRegistry.processIfReflex(sessionId, userId, chatId, u.raw(), context)
                        );
                    } catch (Exception e) {
                        log.errorf("[CommandDispatch] Error processing command '%s': %s", u.raw(), e.getMessage(), e);
                        yield HandleResult.STOP;
                    }
                    
                    if (result != null && result.commandResponse().isPresent()) {
                        context.getSender().sendRaw(context.getUser(), result.commandResponse().get());
                        yield HandleResult.STOP;
                    }
    
                    if (u.raw().startsWith("/cron")) {
                        context.getSender().sendRaw(context.getUser(), "❓ Did you mean `/crons`? Use that to list tasks.");
                        yield HandleResult.STOP;
                    }
                }
                yield HandleResult.CONTINUE;
            }
        };
    }

    private void dispatch(MessageContext context, String input) {
        String sessionId = context.getSessionId().orElse("");
        String userId    = context.getUser().platformId();
        String chatId    = context.getChatId();

        log.infof("[CommandDispatch] command='%s' session=%s", input, sessionId);

        // FIX: Wrap the execution in WorkspaceContext so the Reflexes can access user/chat IDs
        ReflexResult result = null;
        try {
            result = WorkspaceContext.run(
                sessionId,
                userId,
                chatId,
                AutonomyLevel.FULL, 
                AgentRoleEnum.COORDINATOR.toScopedName(),
                () -> reflexRegistry.processIfReflex(sessionId, userId, chatId, input, context)
            );
        } catch (Exception e) {
            log.errorf("[CommandDispatch] Error processing command '%s': %s", input, e.getMessage(), e);
            return;
        }

        if(result != null){
            result.commandResponse().ifPresent(response ->
                context.getSender().sendRaw(context.getUser(), response)
            );
        }
    }
}
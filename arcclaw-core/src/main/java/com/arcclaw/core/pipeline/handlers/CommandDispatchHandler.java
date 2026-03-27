package com.arcclaw.core.pipeline.handlers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelCommand;
import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessageHandler;
import com.arcclaw.core.reflex.ReflexRegistry;
import com.arcclaw.core.reflex.ReflexResult;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CommandDispatchHandler implements MessageHandler {

    private static final Logger log = Logger.getLogger(CommandDispatchHandler.class);

    @Inject
    ReflexRegistry reflexRegistry;

    @Inject
    ChannelPluginRegistry channelPluginRegistry;

    @Override
    public int priority() {
        return 45;
    }

    @Override
    public HandleResult handle(MessageContext context) {
        ChannelCommand cmd = ChannelCommand.parse(context.getMessage().text());

        return switch (cmd) {
            case ChannelCommand.Approve a -> {
                String input = "/approve";
                if (a.cronId().isPresent()) {
                    input += " " + a.cronId().get();
                } else if (a.toolName().isPresent()) {
                    input += " " + a.toolName().get();
                }
                dispatch(context, input);
                yield HandleResult.STOP;
            }
            case ChannelCommand.Deny d -> {
                String input = "/deny";
                if (d.cronId().isPresent()) {
                    input += " " + d.cronId().get();
                } else if (d.toolName().isPresent()) {
                    input += " " + d.toolName().get();
                }
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
            case ChannelCommand.Start s -> {
                // Telegram sends /start on first open and after the user presses the Start button.
                // Deep-link payload (e.g. /start approve_xyz) is forwarded to the reflex chain
                // so future inline-button flows work without any further changes.
                if (s.payload().isPresent()) {
                    String payloadInput = s.payload().get();
                    log.debugf("[CommandDispatch] /start with deep-link payload '%s' — routing to reflexes",
                            payloadInput);
                    dispatch(context, payloadInput);  // reuse existing dispatch → reflex chain
                } else {
                    channelPluginRegistry.find(context.getChannelType())
                        .map(ChannelPlugin::sender)
                        .ifPresent(sender -> sender.sendRaw(context.getUser(),
                            "👋 Hello! I'm *ArcClaw*, your AI agent.\n\n"
                            + "Just type your question or task in plain text.\n\n"
                            + "*Commands:*\n"
                            + "/cron — list scheduled tasks\n"
                            + "/status — session state\n"
                            + "/history — conversation history\n"
                            + "/reset — clear this session\n"
                            + "/halt — stop current task\n"
                            + "/approve · /deny — HITL approvals"));
                }
                yield HandleResult.STOP;
            }
            case ChannelCommand.CronTasks _ -> {
                dispatch(context, "/cron");
                yield HandleResult.STOP;
            }
            case ChannelCommand.DeleteCron dc -> {
                String input = dc.cronId()
                        .map(id -> "deletecron " + id)
                        .orElse("deletecron");
                dispatch(context, input);
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
                        log.errorf("[CommandDispatch] Error processing command '%s': %s", u.raw(), e.getMessage());
                        log.debug("Full command dispatch failure stack trace:", e);
                        
                        channelPluginRegistry.find(context.getChannelType())
                            .map(ChannelPlugin::sender)
                            .ifPresentOrElse(
                                s -> s.sendRaw(context.getUser(), "⚠️ Command failed due to an internal error. Please try again."),
                                () -> log.warnf("CommandDispatchHandler: no sender for type %s — error reply dropped", context.getChannelType())
                            );
                        yield HandleResult.STOP;
                    }
                    
                    if (result != null && result.commandResponse().isPresent()) {
                        String response = result.commandResponse().get();
                        channelPluginRegistry.find(context.getChannelType())
                            .map(ChannelPlugin::sender)
                            .ifPresentOrElse(
                                s -> s.sendRaw(context.getUser(), response),
                                () -> log.warnf("CommandDispatchHandler: no sender for type %s", context.getChannelType())
                            );
                        yield HandleResult.STOP;
                    }
    
                    if (u.raw().startsWith("/cron")) {
                        channelPluginRegistry.find(context.getChannelType())
                            .map(ChannelPlugin::sender)
                            .ifPresentOrElse(
                                s -> s.sendRaw(context.getUser(), "❓ Did you mean `/crons`? Use that to list tasks."),
                                () -> log.warnf("CommandDispatchHandler: no sender for type %s", context.getChannelType())
                            );
                        yield HandleResult.STOP;
                    }

                    if ("/start".equalsIgnoreCase(u.raw().trim())) {
                        channelPluginRegistry.find(context.getChannelType())
                            .map(ChannelPlugin::sender)
                            .ifPresent(s -> s.sendRaw(context.getUser(),
                                "👋 Hello! I'm *ArcClaw*, your AI agent.\n\n"
                                + "Just type your question or task in plain text.\n\n"
                                + "*Commands:*\n"
                                + "/cron — list scheduled tasks\n"
                                + "/status — session state\n"
                                + "/history — conversation history\n"
                                + "/reset — clear this session\n"
                                + "/halt — stop current task\n"
                                + "/approve · /deny — HITL approvals"));
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
            log.errorf("[CommandDispatch] Error processing command '%s': %s", input, e.getMessage());
            log.debug("Full command dispatch failure stack trace:", e);
            
            channelPluginRegistry.find(context.getChannelType())
                .map(ChannelPlugin::sender)
                .ifPresentOrElse(
                    s -> s.sendRaw(context.getUser(), "⚠️ Command failed due to an internal error. Please try again."),
                    () -> log.warnf("CommandDispatchHandler: no sender for type %s", context.getChannelType())
                );
            return;
        }

        if(result != null){
            result.commandResponse().ifPresent(response ->
                channelPluginRegistry.find(context.getChannelType())
                    .map(ChannelPlugin::sender)
                    .ifPresentOrElse(
                        s -> s.sendRaw(context.getUser(), response),
                        () -> log.warnf("CommandDispatchHandler: no sender for type %s", context.getChannelType())
                    )
            );
        }
    }
}

package com.arcclaw.core.pipeline.handlers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelCommand;
import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessageHandler;
import com.arcclaw.core.pipeline.parsers.CommandParserRegistry;
import com.arcclaw.core.reflex.ReflexRegistry;
import com.arcclaw.core.reflex.ReflexResult;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.util.TimeContextInjector;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CommandDispatchHandler implements MessageHandler {

    private static final Logger log = Logger.getLogger(CommandDispatchHandler.class);

    private static final String WARN_NO_SENDER = "CommandDispatchHandler: no sender for type %s";

    @ConfigProperty(name = "arcclaw.agent.default-timezone", defaultValue = "Asia/Kolkata")
    String defaultTimezone;

    @Inject
    ReflexRegistry reflexRegistry;

    @Inject
    ChannelPluginRegistry channelPluginRegistry;

    @Inject
    CommandParserRegistry commandParserRegistry;

    @Override
    public int priority() {
        return 45;
    }

    @Override
    public HandleResult handle(MessageContext context) {
        ChannelCommand cmd = commandParserRegistry.parse(context.getMessage().text(), context.getChannelType());

        if (cmd instanceof ChannelCommand.BuiltInChannelCommand bic) {
            return switch (bic) {
                case ChannelCommand.Approve a -> {
                    String input = "/approve";
                    if (a.targetId().isPresent()) {
                        input += " " + a.targetId().get();
                    } else if (a.toolName().isPresent()) {
                        input += " " + a.toolName().get();
                    }
                    dispatch(context, input);
                    yield HandleResult.STOP;
                }
                case ChannelCommand.Deny d -> {
                    String input = "/deny";
                    if (d.targetId().isPresent()) {
                        input += " " + d.targetId().get();
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
                    if (s.payload().isPresent()) {
                        dispatch(context, s.payload().get());
                    } else {
                        sendWelcomeMessage(context);
                    }
                    yield HandleResult.STOP;
                }
                case ChannelCommand.CronTasks _ -> {
                    dispatch(context, "/cron");
                    yield HandleResult.STOP;
                }
                case ChannelCommand.DeleteCron dc -> {
                    String input = dc.cronId().map(id -> "deletecron " + id).orElse("deletecron");
                    dispatch(context, input);
                    yield HandleResult.STOP;
                }
                case ChannelCommand.Unknown u -> handleUnknownCommand(context, u);
            };
        }

        return HandleResult.CONTINUE;
    }

    private void sendWelcomeMessage(MessageContext context) {
        channelPluginRegistry
                .find(context.getChannelType())
                .map(ChannelPlugin::sender)
                .ifPresent(sender -> sender.sendRaw(context.getUser(), """
                        👋 Hello! I'm *ArcClaw*, your AI agent.

                        Just type your question or task in plain text.

                        *Commands:*
                        /cron - list scheduled tasks
                        /status - session state
                        /history - conversation history
                        /reset - clear this session
                        /halt - stop current task
                        /approve · /deny - HITL approvals"""));
    }

    private HandleResult handleUnknownCommand(MessageContext context, ChannelCommand.Unknown u) {
        TimeContextInjector.inject(context, context.getMessage().text(), defaultTimezone);

        String sessionId = context.getSessionId().orElse("");
        String userId = context.getUser().platformId();
        String chatId = context.getChatId();

        ReflexResult result = null;
        try {
            result = WorkspaceContext.run(
                    sessionId,
                    userId,
                    chatId,
                    AutonomyLevel.FULL,
                    AgentRoleEnum.COORDINATOR.toScopedName(),
                    () -> reflexRegistry.processIfReflex(sessionId, userId, chatId, u.raw(), context));

            if (result != null && result.sanitizedInput() != null) {
                context.setGoal(result.sanitizedInput());
            }
        } catch (Exception e) {
            log.errorf("[CommandDispatch] Error processing command '%s': %s", u.raw(), LogUtil.rootMessage(e));
            log.debug("Full command dispatch failure stack trace:", e);

            channelPluginRegistry
                    .find(context.getChannelType())
                    .map(ChannelPlugin::sender)
                    .ifPresentOrElse(
                            s -> s.sendRaw(
                                    context.getUser(), "⚠️ Command failed due to an internal error. Please try again."),
                            () -> log.warnf(
                                    "CommandDispatchHandler: no sender for type %s - error reply dropped",
                                    context.getChannelType()));
            return HandleResult.STOP;
        }

        if (result != null && result.commandResponse().isPresent()) {
            String response = result.commandResponse().get();
            channelPluginRegistry
                    .find(context.getChannelType())
                    .map(ChannelPlugin::sender)
                    .ifPresentOrElse(
                            s -> s.sendRaw(context.getUser(), response),
                            () -> log.warnf(WARN_NO_SENDER, context.getChannelType()));
            return HandleResult.STOP;
        }

        if (u.raw().startsWith("/cron")) {
            channelPluginRegistry
                    .find(context.getChannelType())
                    .map(ChannelPlugin::sender)
                    .ifPresentOrElse(
                            s -> s.sendRaw(context.getUser(), "❓ Did you mean `/crons`? Use that to list tasks."),
                            () -> log.warnf(WARN_NO_SENDER, context.getChannelType()));
            return HandleResult.STOP;
        }

        if ("/start".equalsIgnoreCase(u.raw().trim())) {
            sendWelcomeMessage(context);
            return HandleResult.STOP;
        }

        return HandleResult.CONTINUE;
    }

    private void dispatch(MessageContext context, String input) {
        String sessionId = context.getSessionId().orElse("");
        String userId = context.getUser().platformId();
        String chatId = context.getChatId();

        log.infof("[CommandDispatch] command='%s' session=%s", input, sessionId);

        // Wrap the execution in WorkspaceContext so the Reflexes can access user/chat IDs
        ReflexResult result = null;
        try {
            result = WorkspaceContext.run(
                    sessionId,
                    userId,
                    chatId,
                    AutonomyLevel.FULL,
                    AgentRoleEnum.COORDINATOR.toScopedName(),
                    () -> reflexRegistry.processIfReflex(sessionId, userId, chatId, input, context));
        } catch (Exception e) {
            log.errorf("[CommandDispatch] Error processing command '%s': %s", input, LogUtil.rootMessage(e));
            log.debug("Full command dispatch failure stack trace:", e);

            channelPluginRegistry
                    .find(context.getChannelType())
                    .map(ChannelPlugin::sender)
                    .ifPresentOrElse(
                            s -> s.sendRaw(
                                    context.getUser(), "⚠️ Command failed due to an internal error. Please try again."),
                            () -> log.warnf(WARN_NO_SENDER, context.getChannelType()));
            return;
        }

        if (result != null) {
            result.commandResponse().ifPresent(response -> {
                if (response.isBlank()) return;
                channelPluginRegistry
                        .find(context.getChannelType())
                        .map(ChannelPlugin::sender)
                        .ifPresentOrElse(
                                s -> s.sendRaw(context.getUser(), response),
                                () -> log.warnf(WARN_NO_SENDER, context.getChannelType()));
            });
        }
    }
}

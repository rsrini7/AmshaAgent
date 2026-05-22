package com.arcclaw.core.pipeline;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import com.arcclaw.core.message.UserMessages;
import com.arcclaw.core.util.ClassUtils;
import io.quarkus.runtime.Startup;
import org.jboss.logging.Logger;

/**
 * Orchestrates the execution of {@link MessageHandler}s.
 */
@Startup
@ApplicationScoped
public class MessagePipeline {
    private static final Logger log = Logger.getLogger(MessagePipeline.class);

    private final List<MessageHandler> handlers;
    private final ChannelPluginRegistry channelPluginRegistry;

    @Inject
    public MessagePipeline(Instance<MessageHandler> handlerInstances, ChannelPluginRegistry channelPluginRegistry) {
        this.channelPluginRegistry = channelPluginRegistry;
        this.handlers = handlerInstances.stream()
                .sorted(Comparator.comparingInt(MessageHandler::priority))
                .collect(Collectors.toList());
        log.infof("Initialized MessagePipeline with %d handlers: %s",
                handlers.size(),
                handlers.stream().map(ClassUtils::getCleanName).collect(Collectors.joining(" -> ")));
        log.infof("Pipeline handler order: %s",
            handlers.stream()
                .map(h -> ClassUtils.getCleanName(h) + "(" + h.priority() + ")")
                .collect(Collectors.joining(" → ")));
    }

    /**
     * Executes the pipeline for a message.
     */
    @ActivateRequestContext
    public void process(MessageContext context) {
        log.debugf("Executing message pipeline for chat %s", context.getChatId());
        for (MessageHandler handler : handlers) {
            try {
                HandleResult result = handler.handle(context);
                if (result == HandleResult.STOP) {
                    log.debugf("Pipeline execution stopped by %s", ClassUtils.getCleanName(handler));
                    return;
                }
            } catch (Exception e) {
                log.errorf("Pipeline handler %s failed for chat %s: %s",
                        ClassUtils.getCleanName(handler), context.getChatId(), LogUtil.rootMessage(e));
                log.debugf("Full stacktrace for pipeline failure in handler %s:\n%s", ClassUtils.getCleanName(handler), LogUtil.trimmedStack(e));                try {
                    channelPluginRegistry.find(context.getChannelType())
                        .map(ChannelPlugin::sender)
                        .ifPresentOrElse(
                            s -> s.sendRaw(context.getUser(), UserMessages.INTERNAL_ERROR),
                            () -> log.warnf("MessagePipeline: no sender for type %s - error notification dropped", context.getChannelType())
                        );
                } catch (Exception notifyEx) {
                    log.warnf("Failed to send pipeline error notification to chat %s: %s",
                            context.getChatId(), notifyEx.getMessage());
                }
                return;
            }
        }
    }
}

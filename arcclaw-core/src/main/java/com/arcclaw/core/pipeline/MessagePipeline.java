package com.arcclaw.core.pipeline;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.core.channel.ChannelPluginRegistry;
import org.jboss.logging.Logger;

/**
 * Orchestrates the execution of {@link MessageHandler}s.
 */
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
                handlers.stream().map(h -> h.getClass().getSimpleName()).collect(Collectors.joining(" -> ")));
        log.infof("Pipeline handler order: %s",
            handlers.stream()
                .map(h -> h.getClass().getSimpleName() + "(" + h.priority() + ")")
                .collect(Collectors.joining(" → ")));
    }

    /**
     * Executes the pipeline for a message.
     */
    public void process(MessageContext context) {
        for (MessageHandler handler : handlers) {
            log.debugf("Running handler: %s", handler.getClass().getSimpleName());
            try {
                HandleResult result = handler.handle(context);
                if (result == HandleResult.STOP) {
                    log.debugf("Pipeline stopped by handler: %s", handler.getClass().getSimpleName());
                    return;
                }
            } catch (Exception e) {
                log.errorf("Pipeline handler %s failed for chat %s: %s",
                        handler.getClass().getSimpleName(), context.getChatId(), e.getMessage());
                log.debugf(e, "Full stacktrace for pipeline failure in handler %s", handler.getClass().getSimpleName());
                try {
                    channelPluginRegistry.find(context.getChannelType())
                        .map(ChannelPlugin::sender)
                        .ifPresentOrElse(
                            s -> s.sendRaw(context.getUser(), "⚠️ An internal error occurred. Please try again."),
                            () -> log.warnf("MessagePipeline: no sender for type %s — error notification dropped", context.getChannelType())
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

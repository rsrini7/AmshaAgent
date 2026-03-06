package com.arcclaw.core.pipeline;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Orchestrates the execution of {@link MessageHandler}s.
 */
@ApplicationScoped
public class MessagePipeline {
    private static final Logger log = Logger.getLogger(MessagePipeline.class);

    private final List<MessageHandler> handlers;

    @Inject
    public MessagePipeline(Instance<MessageHandler> handlerInstances) {
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
                log.errorf(e, "Pipeline handler %s failed for chat %s",
                        handler.getClass().getSimpleName(), context.getChatId());
                try {
                    context.getSender().sendRaw(context.getUser(),
                        "⚠️ An internal error occurred. Please try again.");
                } catch (Exception notifyEx) {
                    log.warnf(notifyEx, "Failed to send pipeline error notification to chat %s",
                            context.getChatId());
                }
                return;
            }
        }
    }
}

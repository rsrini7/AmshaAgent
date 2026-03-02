package com.arcclaw.app.channel.telegram;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessagePipeline;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.component.telegram.model.IncomingMessage;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

@ApplicationScoped
public class TelegramAgentHandler {

    private static final Logger log = Logger.getLogger(TelegramAgentHandler.class);

    @Inject
    MessagePipeline pipeline;

    @Inject
    ManagedExecutor executor;

    @Inject
    TelegramMessageMapper messageMapper;

    @Inject
    TelegramChannelSender sender;

    public void receiveMessage(Exchange exchange) {
        IncomingMessage msg = exchange.getIn().getBody(IncomingMessage.class);

        if (msg == null || msg.getText() == null) {
            log.warn("Received message without text");
            return;
        }

        String chatId = exchange.getIn().getHeader("CamelTelegramChatId", String.class);
        String userId = String.valueOf(msg.getFrom().getId());
        String username = msg.getFrom().getUsername();
        String text = msg.getText();

        ChannelUser user = new ChannelUser(userId, username, com.arcclaw.channel.api.ChannelType.TELEGRAM);
        ChannelMessage channelMessage = messageMapper.map(text, user, chatId);

        if (channelMessage == null) {
            return;
        }

        MessageContext context = new MessageContext(chatId, user, channelMessage, sender);
        executor.submit(() -> processPipeline(context));
    }

    @ActivateRequestContext
    void processPipeline(MessageContext context) {
        try {
            pipeline.process(context);
        } catch (Exception e) {
            log.errorf(e, "Uncaught exception in message pipeline for chat %s", context.getChatId());
        }
    }
}

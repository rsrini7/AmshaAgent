package com.arcclaw.channel.telegram;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.AbstractInboundMessagePipeline;
import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelServices;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.common.util.LogUtil;
import io.quarkus.arc.Unremovable;
import org.apache.camel.Exchange;
import org.apache.camel.component.telegram.model.IncomingMessage;
import org.jboss.logging.Logger;

/**
 * Primary inbound message handler for the Telegram channel.
 * Bridges Camel/Telegram events into the ChannelServices SPI using the reusable Inbound Message Pipeline.
 */
@ApplicationScoped
@Unremovable
public class TelegramAgentHandler extends AbstractInboundMessagePipeline {

    private static final Logger log = Logger.getLogger(TelegramAgentHandler.class);

    @Inject
    TelegramMessageMapper messageMapper;

    @Inject
    TelegramChannelSender sender;

    @Inject
    public TelegramAgentHandler(ChannelServices channelServices) {
        super(channelServices, ChannelType.TELEGRAM);
    }

    protected TelegramAgentHandler() {
        super(null, null);
    }

    public void receiveMessage(Exchange exchange) {
        IncomingMessage msg = exchange.getIn().getBody(IncomingMessage.class);

        if (msg == null) {
            log.warn("Received null message body from Telegram");
            return;
        }

        String chatId = msg.getChat() != null 
            ? String.valueOf(msg.getChat().getId())
            : exchange.getIn().getHeader("CamelTelegramChatId", String.class);

        if (chatId == null || chatId.isBlank()) {
            log.warnf("No chatId for exchange, dropping message");
            return;
        }

        if (msg.getText() == null) {
            log.warnf("Received non-text message from chat %s - notifying user", chatId);
            String userId = msg.getFrom() != null ? String.valueOf(msg.getFrom().getId()) : "unknown";
            ChannelUser fallbackUser = new ChannelUser(userId, chatId, null, channelType);
            sender.sendRaw(fallbackUser, "⚠️ I can only process text messages.");
            return;
        }

        if (msg.getFrom() == null) {
            log.warnf("Dropped message with null 'from' in chat %s", chatId);
            return;
        }

        String userId = String.valueOf(msg.getFrom().getId());
        String username = msg.getFrom().getUsername();
        String text = msg.getText();

        ChannelUser user = new ChannelUser(userId, chatId, username, channelType);
        ChannelMessage channelMessage = messageMapper.map(text, user, chatId);

        processInboundMessage(chatId, text, user, channelMessage);
    }

    @Override
    protected void sendRawReply(ChannelUser user, String text) {
        sender.sendRaw(user, text);
    }

    @Override
    protected void logWarn(String format, Object... args) {
        log.warnf(format, args);
    }

    @Override
    protected void logInfo(String format, Object... args) {
        log.infof(format, args);
    }

    @Override
    protected void logError(String format, Throwable t) {
        log.errorf("%s: %s", format, LogUtil.rootMessage(t));
        log.debugf("Full trace: %s", LogUtil.trimmedStack(t));
    }
}

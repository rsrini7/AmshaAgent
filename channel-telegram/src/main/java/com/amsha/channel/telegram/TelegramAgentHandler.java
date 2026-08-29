package com.amsha.channel.telegram;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.channel.api.AbstractInboundMessagePipeline;
import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelServices;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.channel.api.ProcessingLockManager;
import com.amsha.common.util.LogUtil;

import org.apache.camel.Exchange;
import org.apache.camel.component.telegram.model.IncomingMessage;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

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
    public TelegramAgentHandler(ChannelServices channelServices, ProcessingLockManager lockManager) {
        super(channelServices, ChannelType.TELEGRAM, lockManager);
    }

    /**
     * No-arg constructor required by Quarkus CDI proxy generation.
     * Do not call directly — use the {@link Inject} constructor.
     */
    @Deprecated
    protected TelegramAgentHandler() {
        super();
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

        if (msg.getFrom() == null) {
            log.warnf("Dropped message with null 'from' in chat %s", chatId);
            return;
        }

        String userId = String.valueOf(msg.getFrom().getId());
        String username = msg.getFrom().getUsername();
        ChannelUser user = new ChannelUser(userId, chatId, username, channelType);

        ChannelMessage channelMessage = messageMapper.map(msg, user, chatId);

        if (channelMessage == null) {
            log.warnf("Received unsupported non-text / non-media message from chat %s - notifying user", chatId);
            sender.sendRaw(user, "⚠️ I can only process text messages or supported media attachments.");
            return;
        }

        processInboundMessage(chatId, channelMessage.text(), user, channelMessage);
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

package com.arcclaw.channel.telegram;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelServices;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.quarkus.arc.Unremovable;
import org.apache.camel.Exchange;
import org.apache.camel.component.telegram.model.IncomingMessage;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jboss.logging.Logger;

/**
 * Primary inbound message handler for the Telegram channel.
 * Bridges Camel/Telegram events into the ChannelServices SPI.
 */
@ApplicationScoped
@Unremovable
public class TelegramAgentHandler {

    private static final Logger log = Logger.getLogger(TelegramAgentHandler.class);

    @Inject
    ChannelServices channelServices;

    @Inject
    TelegramMessageMapper messageMapper;

    @Inject
    TelegramChannelSender sender;

    private static final Duration FLAG_TTL = Duration.ofMinutes(10);
    private final Cache<String, AtomicBoolean> processingFlags = Caffeine.newBuilder()
        .expireAfterAccess(FLAG_TTL)
        .build();

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
            ChannelUser fallbackUser = new ChannelUser(userId, chatId, null, ChannelType.TELEGRAM);
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

        ChannelUser user = new ChannelUser(userId, chatId, username, ChannelType.TELEGRAM);
        ChannelMessage channelMessage = messageMapper.map(text, user, chatId);

        if (channelMessage == null) {
            return;
        }

        AtomicBoolean flag = processingFlags.get(chatId, id -> new AtomicBoolean(false));
        if (!flag.compareAndSet(false, true)) {
            sender.sendRaw(user, "⏳ Still processing your previous message. Please wait.");
            return;
        }

        // Action acknowledgment
        if (text != null && !text.trim().startsWith("/")) {
            sender.sendRaw(user, "⏳");
        }

        log.infof("TELEGRAM-RECV Message received for chat %s, delegating to SPI", chatId);
        channelServices.processAsync(channelMessage)
            .handle((res, ex) -> {
                if (ex != null) {
                    log.errorf("Uncaught exception in message processing for chat %s: %s", chatId, ExceptionUtils.getRootCauseMessage(ex));
                    log.debugf("Full exception trace during async processing: %s", 
                               ExceptionUtils.getStackTrace(ex).lines().limit(10).reduce("", (a, b) -> a + "\n" + b));
                    sender.sendRaw(user, "Sorry, an internal error occurred while processing your message.");
                }
                flag.set(false);
                return null;
            });
    }

    /** For tests only - checks if a chat is currently being processed. */
    boolean isProcessing(String chatId) {
        AtomicBoolean flag = processingFlags.getIfPresent(chatId);
        return flag != null && flag.get();
    }
}

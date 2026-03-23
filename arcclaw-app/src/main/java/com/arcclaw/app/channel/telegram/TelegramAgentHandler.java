package com.arcclaw.app.channel.telegram;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.camel.Exchange;
import org.apache.camel.component.telegram.model.IncomingMessage;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessagePipeline;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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

    private final Cache<String, ExecutorService> chatQueues = Caffeine.newBuilder()
                    .maximumSize(10_000)
                    .expireAfterAccess(30, TimeUnit.MINUTES)
                    .<String, ExecutorService>removalListener((chatId, executor, cause) -> {
                        if (executor != null) executor.shutdown();
                    })
                    .build();

    private ExecutorService queueForChat(String chatId) {
        return chatQueues.get(chatId, id ->
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "chat-queue-" + id);
                t.setDaemon(true);
                return t;
            }));
    }

    public void receiveMessage(Exchange exchange) {
        log.infof("TELEGRAM-RECV raw exchange received");
        IncomingMessage msg = exchange.getIn().getBody(IncomingMessage.class);

        if (msg == null) {
            log.warn("Received null message body from Telegram");
            return; // no chatId to reply to
        }

        String chatId = msg.getChat() != null 
            ? String.valueOf(msg.getChat().getId())
            : exchange.getIn().getHeader("CamelTelegramChatId", String.class);

        // The fallback block also uses chatId — protect it too:
        if (chatId == null || chatId.isBlank()) {
            log.warnf("No chatId for exchange, dropping message");
            return;
        }

        if (msg.getText() == null) {
            log.warnf("Received non-text message from chat %s — notifying user", chatId);
            if (chatId != null) {
                // Build a minimal ChannelUser to send the reply
                String fallbackUserId = msg.getFrom() != null
                    ? String.valueOf(msg.getFrom().getId()) : "unknown";
                ChannelUser fallbackUser = new ChannelUser(
                    fallbackUserId, chatId, null, ChannelType.TELEGRAM);

                queueForChat(chatId).submit(() ->
                    sender.sendRaw(fallbackUser,
                        "⚠️ I can only process text messages. "
                        + "Images, documents, stickers, and voice notes are not supported yet."));
            }
            return;
        }

        log.debugf("Received message from chat %s: %s", chatId, msg.getText());

        String userId = String.valueOf(msg.getFrom().getId());
        String username = msg.getFrom().getUsername();
        String text = msg.getText();

        ChannelUser user = new ChannelUser(userId, chatId, username, ChannelType.TELEGRAM);
        ChannelMessage channelMessage = messageMapper.map(text, user, chatId);

        if (channelMessage == null) {
            return;
        }

        log.debugf("Mapped message for chat %s: %s", chatId, channelMessage);

        MessageContext context = new MessageContext(chatId, user, channelMessage, sender);
        
        // Immediate acknowledgment for non-command messages
        if (text != null && !text.trim().startsWith("/")) {
            sender.sendRaw(user, "⏳");
        }

        log.debugf("Processing message for chat %s", chatId);
        queueForChat(chatId).submit(() -> processPipeline(context));
    }

    void processPipeline(MessageContext context) {
        var requestContext = Arc.container().requestContext();
        requestContext.activate();
        try {
            log.infof("TELEGRAM-ROUTE Message received, entering pipeline for chat %s", context.getChatId());
            pipeline.process(context);
        } catch (Exception e) {
            if ("Pipeline crash".equals(e.getMessage())) {
                log.warnf("Expected test exception in message pipeline for chat %s: %s", context.getChatId(), e.getMessage());
            } else {
                log.warnf(e, "Uncaught exception in message pipeline for chat %s", context.getChatId());
                try {
                    context.getSender().sendRaw(context.getUser(), "Sorry, an internal error occurred while processing your message.");
                } catch (Exception sendEx) {
                    log.errorf(sendEx, "Failed to send error notification to chat %s", context.getChatId());
                }
            }
        } finally {
            requestContext.terminate();   // ← always release, even on exception
        }
    }
}

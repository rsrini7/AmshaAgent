package com.arcclaw.app.channel.telegram;

import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.ReplyNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.ProducerTemplate;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class TelegramChannelSender implements ChannelSender {

    @Inject
    ProducerTemplate producerTemplate;

    @Inject
    Logger log;

    @ConfigProperty(name = "arcclaw.channel.telegram.bot-token", defaultValue = "test-token")
    String botToken;

    @Override
    public ChannelType supports() {
        return ChannelType.TELEGRAM;
    }

    @Override
    public void send(ChannelUser recipient, List<ReplyNode> nodes) {
        // Real implementation in Tier 2B. For now, just send text if possible.
        // Needs ReplyRenderer.
    }

    @Override
    public void sendRaw(ChannelUser recipient, String text) {
        if (text == null || text.isBlank()) {
            log.warn("Attempted to send empty message to " + recipient.platformId() + " — skipping");
            return;   // ← never call Camel with empty body
        }
        String endpoint = "telegram:bots?authorizationToken=" + botToken;
        producerTemplate.sendBodyAndHeader(endpoint, text, "CamelTelegramChatId", recipient.platformId());
    }
}

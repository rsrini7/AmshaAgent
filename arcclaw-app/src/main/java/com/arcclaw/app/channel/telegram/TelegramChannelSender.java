package com.arcclaw.app.channel.telegram;

import java.util.List;

import org.apache.camel.ProducerTemplate;
import org.jboss.logging.Logger;

import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.ReplyNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class TelegramChannelSender implements ChannelSender {

    private final ProducerTemplate producerTemplate;
    private final Logger log;

    @Inject
    public TelegramChannelSender(ProducerTemplate producerTemplate, Logger log) {
        this.producerTemplate = producerTemplate;
        this.log = log;
    }

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
        String endpoint = "telegram:bots";
        producerTemplate.sendBodyAndHeader(endpoint, text, "CamelTelegramChatId", recipient.platformId());
    }
}

package com.arcclaw.app.channel;

import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.ReplyNode;
import com.arcclaw.channel.api.ReplyRenderer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Registry that resolves the correct {@link ChannelSender} and {@link ReplyRenderer}
 * for a given {@link ChannelType} at runtime.
 * <p>
 * Lookup: {@code senders.stream().filter(s -> s.supports() == type).findFirst().orElseThrow(...)}.
 * <p>
 * <strong>Tier 2B scaffolding</strong> — only used by {@code TelegramCronNotificationObserver} in
 * this phase.
  */
@ApplicationScoped
public class ChannelSenderRegistry {

    private static final Logger log = Logger.getLogger(ChannelSenderRegistry.class);

    @Inject
    Instance<ChannelSender> senders;

    @Inject
    Instance<ReplyRenderer> renderers;

    /**
     * Renders {@code nodes} via the matching renderer and delivers via the matching sender.
     */
    public void send(ChannelUser user, String correlationId, List<ReplyNode> nodes) {
        String rendered = rendererFor(user.channel()).render(nodes);
        senderFor(user.channel()).sendRaw(user, rendered);
    }

    /**
     * Delivers a pre-rendered plain text string without rendering.
     */
    public void sendRaw(ChannelUser user, String correlationId, String plainText) {
        log.debugf("Sending correlationId=%s to platformId=%s", correlationId, user.platformId());
        senderFor(user.channel()).sendRaw(user, plainText);
    }

    private ChannelSender senderFor(ChannelType type) {
        return senders.stream()
                .filter(s -> s.supports() == type)
                .findFirst()
                .orElseThrow(() -> new UnsupportedOperationException(
                        "No ChannelSender for: " + type));
    }

    private ReplyRenderer rendererFor(ChannelType type) {
        return renderers.stream()
                .filter(r -> r.supports() == type)
                .findFirst()
                .orElseThrow(() -> new UnsupportedOperationException(
                        "No ReplyRenderer for: " + type));
    }
}

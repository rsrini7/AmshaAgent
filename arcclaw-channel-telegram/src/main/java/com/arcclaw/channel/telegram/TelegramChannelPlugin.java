package com.arcclaw.channel.telegram;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelAllowlist;
import com.arcclaw.channel.api.ChannelDaemon;
import com.arcclaw.channel.api.ChannelJobNotifier;
import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ReplyRenderer;

/**
 * Composite ChannelPlugin for Telegram.
 * Reference implementation of the ChannelPlugin SPI.
 */
@ApplicationScoped
public class TelegramChannelPlugin implements ChannelPlugin {

    @Inject
    TelegramChannelSender sender;

    @Inject
    TelegramMarkdownV2Renderer renderer;

    @Inject
    TelegramAllowlistGuard allowlist;

    @Inject
    TelegramJobNotifier jobNotifier;

    @Inject
    TelegramChannelDaemon daemon;

    @Override
    public ChannelType channelType() {
        return ChannelType.TELEGRAM;
    }

    @Override
    public ChannelSender sender() {
        return sender;
    }

    @Override
    public ReplyRenderer renderer() {
        return renderer;
    }

    @Override
    public ChannelAllowlist allowlist() {
        return allowlist;
    }

    @Override
    public ChannelJobNotifier jobNotifier() {
        return jobNotifier;
    }

    @Override
    public ChannelDaemon daemon() {
        return daemon;
    }
}

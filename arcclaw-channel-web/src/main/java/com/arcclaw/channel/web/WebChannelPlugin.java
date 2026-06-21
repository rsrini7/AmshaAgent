package com.arcclaw.channel.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelAllowlist;
import com.arcclaw.channel.api.ChannelDaemon;
import com.arcclaw.channel.api.ChannelJobNotifier;
import com.arcclaw.channel.api.ChannelPlugin;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ReplyRenderer;

@ApplicationScoped
public class WebChannelPlugin implements ChannelPlugin {

    @Inject
    WebChannelSender sender;

    @Inject
    WebReplyRenderer renderer;

    @Inject
    WebAllowlist allowlist;

    @Inject
    WebJobNotifier jobNotifier;

    @Inject
    WebChannelDaemon daemon;

    @Override
    public ChannelType channelType() {
        return ChannelType.WEBSOCKET;
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

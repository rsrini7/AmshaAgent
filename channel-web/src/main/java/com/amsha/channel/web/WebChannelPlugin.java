package com.amsha.channel.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.channel.api.ChannelAllowlist;
import com.amsha.channel.api.ChannelDaemon;
import com.amsha.channel.api.ChannelJobNotifier;
import com.amsha.channel.api.ChannelPlugin;
import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ReplyRenderer;

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

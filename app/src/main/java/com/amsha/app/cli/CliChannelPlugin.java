package com.amsha.app.cli;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.channel.api.ChannelAllowlist;
import com.amsha.channel.api.ChannelDaemon;
import com.amsha.channel.api.ChannelJobNotifier;
import com.amsha.channel.api.ChannelPlugin;
import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ReplyRenderer;

/**
 * ChannelPlugin for the CLI interactive channel.
 * Enables cron result delivery and HITL approval notifications in CLI mode.
 */
@ApplicationScoped
public class CliChannelPlugin implements ChannelPlugin {

    @Inject
    CliChannelSender sender;

    @Inject
    CliReplyRenderer renderer;

    @Inject
    CliAllowlist allowlist;

    @Inject
    CliJobNotifier jobNotifier;

    @Inject
    CliChannelDaemon daemon;

    @Override
    public ChannelType channelType() {
        return ChannelType.CLI;
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

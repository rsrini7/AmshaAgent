package com.arcclaw.app.cli;

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
 * ChannelPlugin for the CLI interactive channel.
 * Enables cron result delivery and HITL approval notifications in CLI mode.
 */
@ApplicationScoped
public class CliChannelPlugin implements ChannelPlugin {

    @Inject CliChannelSender    sender;
    @Inject CliReplyRenderer    renderer;
    @Inject CliAllowlist        allowlist;
    @Inject CliJobNotifier      jobNotifier;
    @Inject CliChannelDaemon    daemon;

    @Override public ChannelType       channelType()  { return ChannelType.CLI; }
    @Override public ChannelSender     sender()       { return sender; }
    @Override public ReplyRenderer     renderer()     { return renderer; }
    @Override public ChannelAllowlist  allowlist()    { return allowlist; }
    @Override public ChannelJobNotifier jobNotifier() { return jobNotifier; }
    @Override public ChannelDaemon     daemon()       { return daemon; }
}

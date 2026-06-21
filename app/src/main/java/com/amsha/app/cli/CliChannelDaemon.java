package com.amsha.app.cli;

import jakarta.enterprise.context.ApplicationScoped;

import com.amsha.channel.api.ChannelDaemon;

/**
 * CLI implementation of {@link ChannelDaemon}.
 * CLI has no background daemon — the REPL is the entry point.
 */
@ApplicationScoped
public class CliChannelDaemon implements ChannelDaemon {

    @Override
    public String channelId() {
        return "cli";
    }

    @Override
    public void start() {
        // CLI has no background daemon; the REPL is the entry point.
    }

    @Override
    public boolean isConfigured() {
        return true;
    }
}

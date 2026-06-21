package com.amsha.channel.api;

/**
 * Interface for channel-specific background daemons that keep Amsha Agent running.
 */
public interface ChannelDaemon {
    /** Canonical lowercase channel ID, e.g. "telegram", "discord". */
    String channelId();
    /** Starts the channel daemon (blocking until stopped). */
    void start() throws Exception;
    /** Returns true if this channel is properly configured and can start. */
    boolean isConfigured();
}

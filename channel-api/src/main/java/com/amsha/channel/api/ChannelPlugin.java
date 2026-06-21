package com.amsha.channel.api;

/**
 * Composite SPI contract for a full channel implementation.
 * <p>
 * To add a new channel (Discord, Slack, WhatsApp), provide a single CDI bean
 * implementing this interface. No changes to amsha-core or amsha-app are
 * required - the framework discovers the plugin automatically via CDI.
 *
 * <p><b>Required CDI scope:</b> {@code @ApplicationScoped}
 *
 * <p><b>Discovery:</b> {@link com.amsha.app.channel.ChannelPluginRegistry} collects all implementations
 * via {@code @Any Instance<ChannelPlugin>} at startup.
 */
public interface ChannelPlugin {

    /** Unique channel type identifier. Must match the value used in session records. */
    ChannelType channelType();

    /** Sends rendered reply nodes to the channel. */
    ChannelSender sender();

    /** Converts ReplyNode trees to channel-native format. */
    ReplyRenderer renderer();

    /** Determines if a ChannelUser is permitted to interact with this channel. */
    ChannelAllowlist allowlist();

    /**
     * Sends cron job completion / approval timeout notifications.
     */
    ChannelJobNotifier jobNotifier();

    /**
     * Manages the channel's daemon lifecycle.
     */
    ChannelDaemon daemon();

    /**
     * Returns true if this channel is configured and should be started.
     */
    default boolean isConfigured() {
        return daemon().isConfigured();
    }
}

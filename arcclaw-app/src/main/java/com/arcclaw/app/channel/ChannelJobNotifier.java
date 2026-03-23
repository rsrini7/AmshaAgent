package com.arcclaw.app.channel;

import com.arcclaw.channel.api.ChannelType;

/**
 * Interface for channel-specific job notification dispatchers.
 * <p>
 * Allows adding new channels (like Discord, Slack) by providing a new
 * implementation of this interface, without modifying the core notification dispatchers.
 */
public interface ChannelJobNotifier {
    
    /**
     * Determines if this notifier supports the given channel type.
     */
    boolean supports(ChannelType channelType);
    
    /**
     * Notifies the user on this channel that their scheduled/cron job has completed.
     */
    void notifyJobCompleted(String recipient, String renderedMessage, String sessionId);
    
    /**
     * Notifies the user on this channel that an approval has timed out.
     */
    void notifyApprovalTimeout(String recipient, String sessionId, String pendingToolName, long timeoutMinutes);
}

package com.arcclaw.channel.api;

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
     * Notifies the user on this channel that a scheduled/cron job requires approval.
     * <p>
     * Channels with inline button support (e.g. Telegram) should override this to
     * present Approve/Deny buttons alongside the message. The default implementation
     * sends the rendered message as plain text without buttons.
     *
     * @param recipient      Channel-specific recipient identifier.
     * @param cronSessionId  Full cron session ID for constructing callback data.
     * @param toolName       The tool that requires approval.
     * @param renderedMessage The pre-formatted notification text.
     */
    default void notifyApprovalRequired(String recipient, String cronSessionId, String toolName, String renderedMessage) {
        notifyJobCompleted(recipient, renderedMessage, cronSessionId);
    }
    
    /**
     * Notifies the user on this channel that an approval has timed out.
     */
    void notifyApprovalTimeout(String recipient, String sessionId, String pendingToolName, long timeoutMinutes);
}

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
     * Every channel adapter must implement this method to make a deliberate choice
     * about how to present approval actions (inline buttons, text instructions, etc.).
     * Channels without inline button support should send text instructions (e.g.
     * "To approve, run /approve {@code cronSessionId}").
     *
     * @param recipient      Channel-specific recipient identifier.
     * @param cronSessionId  Full cron session ID for constructing callback data.
     * @param toolName       The tool that requires approval.
     * @param renderedMessage The pre-formatted notification text.
     */
    void notifyApprovalRequired(String recipient, String cronSessionId, String toolName, String renderedMessage);
    
    /**
     * Notifies the user on this channel that an approval has timed out.
     */
    void notifyApprovalTimeout(String recipient, String sessionId, String pendingToolName, long timeoutMinutes);
}

package com.amsha.channel.api.session;

/**
 * CDI event fired when a session rotation completes so channel adapters
 * can update their local identity.
 */
public record SessionRotatedEvent(String oldSessionId, String newSessionId, String ownerKey, String channelType) {}

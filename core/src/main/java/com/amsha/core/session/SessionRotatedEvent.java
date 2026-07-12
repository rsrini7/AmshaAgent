package com.amsha.core.session;

public record SessionRotatedEvent(String oldSessionId, String newSessionId, String ownerKey, String channelType) {}

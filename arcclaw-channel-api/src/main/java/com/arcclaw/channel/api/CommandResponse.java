package com.arcclaw.channel.api;

import java.util.Collections;
import java.util.Map;

/**
 * Structured response from a triggered CommandReflex or ChannelCommand handler.
 * Replaces simple strings to allow future-proof metadata propagation (e.g. execution duration,
 * specific record IDs) back to the channel adapter.
 */
public record CommandResponse(String content, Map<String, Object> metadata) {
    public CommandResponse(String content) {
        this(content, Collections.emptyMap());
    }
}

package com.amsha.core.config;

import java.time.Instant;
import java.util.Map;

/**
 * Fired when runtime configuration is updated via the Settings API.
 * <p>
 * Observers can use this event to hot-reload governance policies, tool configs,
 * or other settings without requiring a full application restart.
 *
 * @param changedKeys map of configuration keys that were modified, with their new values
 * @param source      the API endpoint or component that triggered the change
 * @param timestamp   when the change occurred
 */
public record ConfigurationChangedEvent(Map<String, String> changedKeys, String source, Instant timestamp) {
    public ConfigurationChangedEvent {
        if (changedKeys == null || changedKeys.isEmpty()) {
            throw new IllegalArgumentException("changedKeys must not be null or empty");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be null or blank");
        }
    }

    public static ConfigurationChangedEvent of(String source, Map<String, String> changedKeys) {
        return new ConfigurationChangedEvent(changedKeys, source, Instant.now());
    }

    /**
     * Returns true if the given key was changed.
     */
    public boolean hasKey(String key) {
        return changedKeys.containsKey(key);
    }

    /**
     * Returns the new value for the given key, or null if not changed.
     */
    public String getValue(String key) {
        return changedKeys.get(key);
    }
}

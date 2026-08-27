package com.amsha.core.observability;

import java.util.Locale;
import java.util.Optional;

import org.jboss.logging.Logger;

/**
 * Operating mode for the LangChain4j {@code ChatModelListener} observability pilot.
 */
public enum ChatModelListenerMode {
    OFF("off"),
    SHADOW("shadow"),
    ACTIVE("active");

    private final String id;

    ChatModelListenerMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    private static final Logger LOG = Logger.getLogger(ChatModelListenerMode.class);

    public static ChatModelListenerMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return OFF;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ChatModelListenerMode mode : values()) {
            if (mode.id.equals(normalized)) {
                return mode;
            }
        }
        LOG.warnf("Unrecognized ChatModelListenerMode '%s', defaulting to OFF", value);
        return OFF;
    }

    public static Optional<ChatModelListenerMode> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ChatModelListenerMode mode : values()) {
            if (mode.id.equals(normalized)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}

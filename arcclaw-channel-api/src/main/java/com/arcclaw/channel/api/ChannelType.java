package com.arcclaw.channel.api;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Identifies the originating channel for a message or command.
 * Uses the Flyweight pattern (GoF) to guarantee identity equality —
 * {@code ChannelType.of("telegram") == ChannelType.TELEGRAM} is always true.
 *
 * <p>Built-in types are pre-registered. Dynamic types (e.g. third-party channel plugins)
 * are created on first use and cached permanently.
 */
public final class ChannelType {

    private static final ConcurrentMap<String, ChannelType> REGISTRY = new ConcurrentHashMap<>();

    // ── Built-in types ──────────────────────────────────────────────────────
    public static final ChannelType TELEGRAM = register("telegram");
    public static final ChannelType DISCORD = register("discord");
    public static final ChannelType SLACK = register("slack");
    public static final ChannelType WEBSOCKET = register("websocket");
    public static final ChannelType REST_API = register("rest_api");
    public static final ChannelType CLI = register("cli");

    // Backward-compat alias — maps the legacy "restapi" id to the REST_API constant
    // so old session rows (channel_type = 'restapi') still resolve via fromString().
    static {
        REGISTRY.put("restapi", REST_API);
    }

    private final String id;

    private ChannelType(String id) {
        this.id = id;
    }

    private static ChannelType register(String id) {
        ChannelType ct = new ChannelType(id);
        REGISTRY.put(id, ct);
        return ct;
    }

    /**
     * Returns the canonical {@code ChannelType} for the given id.
     * Guarantees reference equality for the same id string.
     *
     * @param id case-insensitive channel identifier (e.g. "Telegram", "TELEGRAM")
     * @return the cached ChannelType instance
     * @throws IllegalArgumentException if id is null or blank
     */
    public static ChannelType of(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ChannelType id must not be null or blank");
        }
        String normalized = id.toLowerCase().trim();
        return REGISTRY.computeIfAbsent(normalized, ChannelType::new);
    }

    /**
     * Lookup-only resolution. Returns the cached {@code ChannelType} for the
     * given id, or empty if the id is null/blank or not a registered type.
     * <p>
     * Unlike {@link #of(String)}, this does <strong>not</strong> create new
     * entries — typos return empty instead of polluting the registry.
     */
    public static Optional<ChannelType> fromString(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        String normalized = value.toLowerCase().trim();
        return Optional.ofNullable(REGISTRY.get(normalized));
    }

    /**
     * Forces Jackson deserialization through the Flyweight registry.
     * Without this, Jackson would create a new instance (bypassing the
     * private constructor and breaking identity equality).
     * <p>
     * Uses {@link #fromString} (lookup-only) rather than {@link #of} to
     * avoid polluting the registry with unknown types from serialised data.
     *
     * @throws IllegalArgumentException if the id is not a registered type
     */
    @JsonCreator
    public static ChannelType fromJson(String id) {
        return fromString(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown ChannelType in JSON payload: " + id));
    }

    /** The lowercase identifier (e.g. "telegram"). Serialised as-is via Jackson. */
    @JsonValue
    public String id() {
        return id;
    }

    /**
     * Uppercased id for backward compatibility with callers that used
     * the former enum's {@code name()} method.
     */
    public String name() {
        return id.toUpperCase();
    }

    @Override
    public String toString() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        return this == o; // Flyweight: identity equality
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}

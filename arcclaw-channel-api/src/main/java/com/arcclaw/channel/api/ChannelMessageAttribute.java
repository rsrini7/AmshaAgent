package com.arcclaw.channel.api;

import java.util.Objects;

/**
 * Type-safe key for channel-specific message attributes.
 *
 * <p>Channel plugins define their own keys as static constants:
 * <pre>{@code
 * public static final ChannelMessageAttribute<String> CALLBACK_QUERY_ID =
 *     new ChannelMessageAttribute<>(String.class, "telegram.callbackQueryId");
 * }</pre>
 *
 * @param <T> the attribute value type
 */
public record ChannelMessageAttribute<T>(Class<T> type, String name) {

    /**
     * Two keys are equal only if both name <em>and</em> type match.
     * This prevents type confusion when two plugins define attributes with the
     * same semantic name but different value types — they are stored as distinct
     * entries in the attributes map rather than silently overwriting each other.
     * <strong>Note:</strong> type equality uses {@link Class#equals}, which is
     * safe in single-classloader deployments (standard Quarkus). If classloader
     * isolation is ever introduced, switch to name-only equality with type
     * checking at retrieval.
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof ChannelMessageAttribute<?> a
            && name.equals(a.name)
            && type.equals(a.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type);
    }
}

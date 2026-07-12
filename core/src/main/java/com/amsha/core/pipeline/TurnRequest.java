package com.amsha.core.pipeline;

import java.util.Objects;
import java.util.Optional;

import com.amsha.channel.api.ChannelMessage;

/**
 * A channel-neutral request to process one user turn.
 *
 * <p>Adapters provide transport identity and may request an already selected
 * session or an explicit role. All workflow decisions still happen in the
 * shared {@link MessagePipeline}.
 */
public record TurnRequest(
        ChannelMessage message,
        String chatId,
        Optional<String> requestedSessionId,
        Optional<String> explicitRole,
        Optional<Integer> maxIterations) {

    public TurnRequest {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(chatId, "chatId must not be null");
        requestedSessionId =
                requestedSessionId == null ? Optional.empty() : requestedSessionId.filter(s -> !s.isBlank());
        explicitRole = explicitRole == null ? Optional.empty() : explicitRole.filter(s -> !s.isBlank());
        maxIterations = maxIterations == null ? Optional.empty() : maxIterations.filter(value -> value > 0);
    }

    public static TurnRequest mapped(ChannelMessage message, String chatId) {
        return new TurnRequest(message, chatId, Optional.empty(), Optional.empty(), Optional.empty());
    }
}

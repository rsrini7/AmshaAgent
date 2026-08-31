package com.amsha.core.speech;

/**
 * Channel-neutral inputs used to resolve the speech output mode for one turn.
 *
 * <p>Deliberately free of channel types: callers report whether the inbound turn carried audio
 * rather than exposing Telegram-specific attachment structures, so the same policy drives Telegram,
 * REST, and WebSocket later without change.
 *
 * @param requestedText the user's request for this turn; for an audio turn this is the transcript
 * @param inboundAudioPresent true when this turn originated from or carried inbound audio
 */
public record OutputPolicyContext(String requestedText, boolean inboundAudioPresent) {

    public OutputPolicyContext {
        requestedText = requestedText == null ? "" : requestedText;
    }

    public static OutputPolicyContext of(String requestedText, boolean inboundAudioPresent) {
        return new OutputPolicyContext(requestedText, inboundAudioPresent);
    }
}

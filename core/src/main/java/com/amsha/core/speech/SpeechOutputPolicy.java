package com.amsha.core.speech;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Locale;
import java.util.regex.Pattern;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Deterministic resolution of the speech output mode for one agent turn.
 *
 * <p>Precedence, highest first:
 * <ol>
 *   <li>an explicit positive or negative directive in the current turn;</li>
 *   <li>{@code amsha.tts.default-output-mode}, applied to audio-input turns;</li>
 *   <li>a safe {@link OutputMode#TEXT_ONLY} fallback.</li>
 * </ol>
 *
 * <p>V1 has no persisted user preference, so there is no user-preference level between 1 and 2.
 *
 * <p>Audio is never triggered by an incidental mention of the word "audio": every positive pattern
 * requires either an action verb ("reply/answer/send ... with/in/as ... audio") or an explicit
 * audio/voice noun phrase ("audio reply", "voice note"). A question such as "how do audio codecs
 * work?" therefore stays text-only.
 */
@ApplicationScoped
public class SpeechOutputPolicy {

    private static final Pattern NEGATIVE_AUDIO = Pattern.compile(
            "\\b(?:no|not|don'?t|do\\s+not|without|never|skip)\\b[^.?!\\n]{0,25}\\b(?:audio|voice|speech|spoken|tts)\\b"
                    + "|\\btext\\s*[- ]?only\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern AUDIO_ONLY = Pattern.compile(
            "\\b(?:audio|voice)\\s*[- ]?only\\b"
                    + "|\\bonly\\s+(?:the\\s+)?(?:audio|voice)\\b"
                    + "|\\bjust\\s+(?:the\\s+)?(?:audio|voice)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern POSITIVE_AUDIO = Pattern.compile(
            "\\b(?:reply|respond|answer|send|give|say)\\b[^.?!\\n]{0,30}"
                    + "\\b(?:with|in|as|by|via)\\b[^.?!\\n]{0,15}\\b(?:audio|voice)\\b"
                    + "|\\b(?:reply|respond)\\s+(?:with\\s+)?(?:audio|voice)\\b"
                    + "|\\b(?:audio|voice)\\s+(?:reply|response|answer|version|message|note)\\b"
                    + "|\\bread\\s+(?:\\w+\\s+){0,2}(?:aloud|out)\\b"
                    + "|\\bspeak\\s+(?:\\w+\\s+){0,2}(?:aloud|out)\\b"
                    + "|\\bmake\\s+(?:it\\s+)?(?:an?\\s+)?(?:audio|voice)\\b",
            Pattern.CASE_INSENSITIVE);

    private final String defaultOutputMode;

    @Inject
    public SpeechOutputPolicy(
            @ConfigProperty(name = "amsha.tts.default-output-mode", defaultValue = "TEXT_ONLY")
                    String defaultOutputMode) {
        this.defaultOutputMode = defaultOutputMode;
    }

    /** Resolves the output mode for one turn. Never returns null. */
    public OutputMode resolve(OutputPolicyContext context) {
        if (context == null) {
            return OutputMode.TEXT_ONLY;
        }
        String text = context.requestedText();
        if (text != null && !text.isBlank()) {
            if (NEGATIVE_AUDIO.matcher(text).find()) {
                return OutputMode.TEXT_ONLY;
            }
            if (AUDIO_ONLY.matcher(text).find()) {
                return OutputMode.AUDIO_ONLY;
            }
            if (POSITIVE_AUDIO.matcher(text).find()) {
                return OutputMode.TEXT_AND_AUDIO;
            }
        }
        return context.inboundAudioPresent() ? defaultMode() : OutputMode.TEXT_ONLY;
    }

    private OutputMode defaultMode() {
        if (defaultOutputMode == null || defaultOutputMode.isBlank()) {
            return OutputMode.TEXT_ONLY;
        }
        try {
            return OutputMode.valueOf(defaultOutputMode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException _) {
            return OutputMode.TEXT_ONLY;
        }
    }
}

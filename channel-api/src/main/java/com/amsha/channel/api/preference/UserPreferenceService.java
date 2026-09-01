package com.amsha.channel.api.preference;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Service port for managing user-scoped preferences such as speech voice and output mode across all channels.
 */
public interface UserPreferenceService {

    /** Standard predefined voices validated and supported by TTS providers. */
    List<String> PREDEFINED_VOICES = List.of("default", "vivian", "serena", "eric", "ryan", "alloy", "onyx");

    /** Returns the curated list of selectable voice identifiers. */
    default List<String> getAvailableVoices() {
        return PREDEFINED_VOICES;
    }

    /** Returns whether the given voice name is a recognized selectable voice. */
    default boolean isValidVoice(String voice) {
        if (voice == null || voice.isBlank()) {
            return false;
        }
        String normalized = voice.trim().toLowerCase(Locale.ROOT);
        return PREDEFINED_VOICES.stream().anyMatch(v -> v.equalsIgnoreCase(normalized));
    }

    /** Returns whether the given output mode name is a valid mode. */
    default boolean isValidOutputMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return false;
        }
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        return "DEFAULT".equals(normalized)
                || "TEXT_ONLY".equals(normalized)
                || "TEXT_AND_AUDIO".equals(normalized)
                || "AUDIO_ONLY".equals(normalized);
    }

    /** Returns the explicitly configured voice for the given user, if any. */
    Optional<String> getVoice(String userId);

    /**
     * Resolves the effective voice for the given user, falling back to system configuration
     * ({@code amsha.tts.voice}) when no user-level preference is set.
     */
    Optional<String> getEffectiveVoice(String userId);

    /**
     * Sets the user's voice preference. Setting blank or "default" clears the preference.
     *
     * @throws IllegalArgumentException if the voice name is not in {@link #getAvailableVoices()}
     */
    void setVoice(String userId, String voice);

    /** Clears the user's voice preference. */
    void clearVoice(String userId);

    /** Returns the explicitly configured speech output mode for the given user, if any. */
    Optional<String> getOutputMode(String userId);

    /**
     * Resolves the effective output mode for the user, falling back to system configuration
     * ({@code amsha.tts.default-output-mode}) when no user preference is set.
     */
    Optional<String> getEffectiveOutputMode(String userId);

    /**
     * Sets the user's speech output mode preference. Setting blank or "default" clears the preference.
     *
     * @throws IllegalArgumentException if the mode name is invalid
     */
    void setOutputMode(String userId, String mode);

    /** Clears the user's speech output mode preference. */
    void clearOutputMode(String userId);

    /**
     * Atomically validates and updates both voice and speech output mode preferences for a user.
     * Validates both inputs BEFORE applying any write, ensuring no partial mutation on failure.
     *
     * @param userId user identifier
     * @param voice voice timbre identifier or null/blank/"default" to clear
     * @param outputMode output mode name or null/blank/"default" to clear
     * @throws IllegalArgumentException if either parameter fails validation
     */
    void updatePreferences(String userId, String voice, String outputMode);
}

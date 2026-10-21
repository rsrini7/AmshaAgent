package com.amsha.llm.providers.audio;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.amsha.channel.api.MediaTypes;
import com.amsha.common.http.HttpProtocol;

/** Account-linked Gnani speech API contract constants and allowlists. */
final class GnaniApiContract {
    static final String API_KEY_HEADER = HttpProtocol.HEADER_X_API_KEY_ID;
    static final String STT_ENDPOINT = "https://api.vachana.ai/stt/v3";
    static final String TTS_ENDPOINT = "https://api.vachana.ai/api/v1/tts/inference";
    static final int STT_PRIORITY = 40;
    static final String DEFAULT_TTS_MODEL = "timbre-v3.0";
    static final String DEFAULT_TTS_VOICE = "Shivani";
    static final String DEFAULT_STT_LANGUAGE = "en-IN";
    static final Set<String> STT_LANGUAGES =
            Set.of("bn-IN", "en-IN", "gu-IN", "hi-IN", "kn-IN", "ml-IN", "mr-IN", "pa-IN", "ta-IN", "te-IN");
    static final Set<String> TTS_LANGUAGES = Set.of(
            "auto", "bn-IN", "en-IN", "gu-IN", "hi-IN", "hi-en", "kn-IN", "ml-IN", "mr-IN", "pa-IN", "ta-IN", "te-IN");
    static final Set<String> TTS_V3_VOICES = Set.of(
            "Avinash", "Chetana", "Devansh", "Harshad", "Kalpana", "Lakshmi", "Prakash", "Riyaa", "Shivani", "Shubham",
            "Tarika", "Vasudha", "Yashraj");
    static final Set<String> STT_AUDIO_MIME_TYPES = Set.of(
            MediaTypes.WAV,
            MediaTypes.X_WAV,
            MediaTypes.WAVE,
            MediaTypes.MPEG,
            MediaTypes.MP3,
            MediaTypes.OGG,
            MediaTypes.APPLICATION_OGG,
            MediaTypes.OPUS,
            MediaTypes.FLAC,
            MediaTypes.X_FLAC,
            MediaTypes.AAC,
            MediaTypes.M4A,
            MediaTypes.X_M4A,
            MediaTypes.MP4);
    static final Map<String, String> STT_SHORT_LANGUAGE_CODES = Map.copyOf(Map.of(
            "bn", "bn-IN",
            "en", "en-IN",
            "gu", "gu-IN",
            "hi", "hi-IN",
            "kn", "kn-IN",
            "ml", "ml-IN",
            "mr", "mr-IN",
            "pa", "pa-IN",
            "ta", "ta-IN",
            "te", "te-IN"));
    static final Map<String, String> GENERIC_VOICE_MAP = Map.copyOf(Map.of(
            "default", DEFAULT_TTS_VOICE,
            "vivian", "Lakshmi",
            "serena", "Shivani",
            "eric", "Avinash",
            "ryan", "Shubham",
            "alloy", "Chetana",
            "onyx", "Prakash"));
    static final Map<Character.UnicodeScript, String> SCRIPT_LANGUAGES = Map.copyOf(Map.of(
            Character.UnicodeScript.BENGALI, "bn-IN",
            Character.UnicodeScript.DEVANAGARI, "hi-IN",
            Character.UnicodeScript.GUJARATI, "gu-IN",
            Character.UnicodeScript.GURMUKHI, "pa-IN",
            Character.UnicodeScript.TAMIL, "ta-IN",
            Character.UnicodeScript.TELUGU, "te-IN",
            Character.UnicodeScript.KANNADA, "kn-IN",
            Character.UnicodeScript.MALAYALAM, "ml-IN"));

    private GnaniApiContract() {}

    static boolean isValidApiKeyHeaderValue(String value) {
        return value != null
                && !value.isBlank()
                && value.chars().allMatch(character -> character >= 0x21 && character <= 0x7E);
    }

    static String normalizeSttLanguage(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String candidate = language.trim();
        if (candidate.length() == 2) {
            candidate = STT_SHORT_LANGUAGE_CODES.getOrDefault(candidate.toLowerCase(Locale.ROOT), candidate);
        }
        String normalizedCandidate = candidate;
        return STT_LANGUAGES.stream()
                .filter(value -> value.equalsIgnoreCase(normalizedCandidate))
                .findFirst()
                .orElse(null);
    }

    static boolean isSupportedAudioMime(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return false;
        }
        String normalized = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return STT_AUDIO_MIME_TYPES.contains(normalized);
    }
}

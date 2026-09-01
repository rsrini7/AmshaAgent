package com.amsha.core.reflex;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.amsha.channel.api.preference.UserPreferenceService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.pipeline.MessageContext;

@ApplicationScoped
@ReflexPriority(450)
public class VoiceReflex implements AgentReflex {

    private static final Pattern VOICE_COMMAND_PATTERN =
            Pattern.compile("(?i)^/(voice|voices|tts|tts-voice)(\\s+(.+))?$");

    @Inject
    UserPreferenceService userPreferenceService;

    @Override
    public boolean matches(String input) {
        if (input == null) {
            return false;
        }
        return VOICE_COMMAND_PATTERN.matcher(input.trim()).matches();
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        if (input == null) {
            return ReflexResult.respond("Invalid command", context);
        }
        Matcher matcher = VOICE_COMMAND_PATTERN.matcher(input.trim());
        if (!matcher.matches()) {
            return ReflexResult.respond("Invalid voice command", context);
        }
        String targetArg = matcher.group(3);
        String effectiveUserId = userId != null && !userId.isBlank() ? userId : "unknown";

        if (targetArg != null && !targetArg.isBlank()) {
            String arg = targetArg.trim();
            String lower = arg.toLowerCase(Locale.ROOT);

            // Handle mode subcommands, e.g. "/voice mode audio_only" or direct mode "/voice audio_only"
            String modeCandidate = extractModeCandidate(lower);
            if (modeCandidate != null) {
                return handleModeSelection(effectiveUserId, modeCandidate, context);
            }

            // Handle reset/clear
            if ("default".equals(lower) || "clear".equals(lower) || "reset".equals(lower)) {
                userPreferenceService.clearVoice(effectiveUserId);
                String systemVoice =
                        userPreferenceService.getEffectiveVoice(effectiveUserId).orElse("Random / Provider Default");
                return ReflexResult.respond(
                        "🗣 Voice preference cleared. System default will be used: **" + systemVoice + "**", context);
            }

            // Handle voice timbre selection
            if (userPreferenceService.isValidVoice(lower)) {
                userPreferenceService.setVoice(effectiveUserId, lower);
                return ReflexResult.respond(
                        "🗣 Voice preference set to **" + lower + "**. All future speech replies will use this voice.",
                        context);
            }

            return ReflexResult.respond(
                    "❌ Unsupported argument '" + arg + "'.\n\n"
                            + "• To set voice: `/voice <name>` (voices: "
                            + String.join(", ", userPreferenceService.getAvailableVoices()) + ")\n"
                            + "• To set output mode: `/voice mode <text_only | text_and_audio | audio_only | default>`\n"
                            + "• To reset: `/voice default`",
                    context);
        }

        // List current settings and available options
        String currentVoice = userPreferenceService.getVoice(effectiveUserId).orElse(null);
        String effectiveVoice =
                userPreferenceService.getEffectiveVoice(effectiveUserId).orElse("Random / Provider Default");
        String currentMode =
                userPreferenceService.getOutputMode(effectiveUserId).orElse(null);
        String effectiveMode =
                userPreferenceService.getEffectiveOutputMode(effectiveUserId).orElse("TEXT_ONLY");
        List<String> available = userPreferenceService.getAvailableVoices();

        StringBuilder sb = new StringBuilder();
        sb.append("🗣 **Voice & Speech Settings**\n");
        if (currentVoice != null) {
            sb.append("• Active Voice: **").append(currentVoice).append("** (Custom Preference)\n");
        } else {
            sb.append("• Active Voice: **").append(effectiveVoice).append("** (System Default)\n");
        }
        if (currentMode != null) {
            sb.append("• Output Mode: **").append(currentMode).append("** (Custom Preference)\n\n");
        } else {
            sb.append("• Output Mode: **").append(effectiveMode).append("** (System Default)\n\n");
        }

        sb.append("Select voice with `/voice <name>`:\n");
        for (String v : available) {
            if ("default".equalsIgnoreCase(v)) {
                sb.append("• `/voice default` — System Default / Model Neutral\n");
            } else {
                sb.append("• `/voice ").append(v).append("`\n");
            }
        }
        sb.append("\nSet speech output mode with `/voice mode <mode>`:\n");
        sb.append("• `/voice mode text_only` — Text only\n");
        sb.append("• `/voice mode text_and_audio` — Text and spoken voice\n");
        sb.append("• `/voice mode audio_only` — Spoken voice only\n");
        sb.append("• `/voice mode default` — Follow system default\n");
        return ReflexResult.respond(sb.toString(), context);
    }

    private String extractModeCandidate(String lower) {
        if (lower.startsWith("mode ")) {
            return lower.substring(5).trim();
        }
        if (lower.startsWith("mode=")) {
            return lower.substring(5).trim();
        }
        if ("text_only".equals(lower)
                || "text-only".equals(lower)
                || "text_and_audio".equals(lower)
                || "text-and-audio".equals(lower)
                || "text+audio".equals(lower)
                || "audio_only".equals(lower)
                || "audio-only".equals(lower)) {
            return lower;
        }
        return null;
    }

    private ReflexResult handleModeSelection(String userId, String modeArg, MessageContext context) {
        String normalized = modeArg.replace("-", "_").replace("+", "_and_").toUpperCase(Locale.ROOT);
        if ("DEFAULT".equals(normalized) || "CLEAR".equals(normalized) || "RESET".equals(normalized)) {
            userPreferenceService.clearOutputMode(userId);
            String systemMode =
                    userPreferenceService.getEffectiveOutputMode(userId).orElse("TEXT_ONLY");
            return ReflexResult.respond(
                    "🔊 Speech output mode cleared. System default will be used: **" + systemMode + "**", context);
        }
        if (userPreferenceService.isValidOutputMode(normalized)) {
            userPreferenceService.setOutputMode(userId, normalized);
            return ReflexResult.respond("🔊 Speech output mode set to **" + normalized + "**.", context);
        }
        return ReflexResult.respond(
                "❌ Unsupported output mode '" + modeArg
                        + "'. Allowed modes: text_only, text_and_audio, audio_only, default",
                context);
    }

    @Override
    public SecurityEventType auditEventType() {
        return SecurityEventType.REFLEX_COMMAND;
    }
}

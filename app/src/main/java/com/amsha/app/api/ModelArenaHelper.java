package com.amsha.app.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.amsha.llm.api.ModelDefaults;

/** Helper for model arena validation, error categorization, token math, and defaults. */
final class ModelArenaHelper {

    static final int MAX_INPUT_TOKENS = 2048;
    static final int MAX_OUTPUT_TOKENS = 1024;
    static final int DEFAULT_OUTPUT_TOKENS = 512;
    static final int MAX_MODELS = 3;
    static final long TIMEOUT_SECONDS = 30L;
    static final int MAX_SLOT_LENGTH = 128;

    static final Pattern SLOT_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+(:[a-zA-Z0-9_./:-]+)?$");

    static final Set<String> ALLOWED_PROVIDERS = Set.of(
            ModelDefaults.PROVIDER_GROQ,
            ModelDefaults.PROVIDER_OPENAI,
            ModelDefaults.PROVIDER_OPENROUTER,
            ModelDefaults.PROVIDER_CEREBRAS,
            ModelDefaults.PROVIDER_SARVAM,
            ModelDefaults.PROVIDER_OPENCODE,
            ModelDefaults.PROVIDER_HUGGINGFACE,
            ModelDefaults.PROVIDER_MISTRAL,
            ModelDefaults.PROVIDER_CLOUDFLARE,
            ModelDefaults.PROVIDER_COHERE,
            ModelDefaults.PROVIDER_CODEX,
            ModelDefaults.PROVIDER_NVIDIA,
            ModelDefaults.PROVIDER_OLLAMA,
            ModelDefaults.PROVIDER_OMLX,
            ModelDefaults.PROVIDER_LLAMACPP,
            ModelDefaults.PROVIDER_ANTHROPIC,
            ModelDefaults.PROVIDER_CLAUDE_CODE,
            ModelDefaults.PROVIDER_AGY,
            ModelDefaults.PROVIDER_KIMCHI,
            ModelDefaults.PROVIDER_FOUNDRY,
            ModelDefaults.PROVIDER_GEMINI,
            ModelDefaults.PROVIDER_GENERIC_OPENAI,
            "default");

    static final Map<String, String> PROVIDER_BASE_URLS = Map.ofEntries(
            Map.entry(ModelDefaults.PROVIDER_GROQ, ModelDefaults.DEFAULT_GROQ_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_OPENAI, ModelDefaults.DEFAULT_OPENAI_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_OPENROUTER, ModelDefaults.DEFAULT_OPENROUTER_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_CEREBRAS, ModelDefaults.DEFAULT_CEREBRAS_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_SARVAM, ModelDefaults.DEFAULT_SARVAM_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_OPENCODE, ModelDefaults.DEFAULT_OPENCODE_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_HUGGINGFACE, ModelDefaults.DEFAULT_HUGGINGFACE_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_MISTRAL, ModelDefaults.DEFAULT_MISTRAL_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_CLOUDFLARE, ModelDefaults.DEFAULT_CLOUDFLARE_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_COHERE, ModelDefaults.DEFAULT_COHERE_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_CODEX, ModelDefaults.DEFAULT_CODEX_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_NVIDIA, ModelDefaults.DEFAULT_NVIDIA_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_OLLAMA, ModelDefaults.DEFAULT_OLLAMA_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_OMLX, ModelDefaults.DEFAULT_OMLX_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_LLAMACPP, ModelDefaults.DEFAULT_LLAMACPP_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_ANTHROPIC, ModelDefaults.DEFAULT_ANTHROPIC_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_CLAUDE_CODE, ModelDefaults.DEFAULT_CLAUDE_CODE_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_AGY, ModelDefaults.DEFAULT_AGY_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_KIMCHI, ModelDefaults.DEFAULT_KIMCHI_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_FOUNDRY, ModelDefaults.DEFAULT_FOUNDRY_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_GEMINI, ModelDefaults.DEFAULT_GEMINI_BASE_URL),
            Map.entry(ModelDefaults.PROVIDER_GENERIC_OPENAI, ""));

    private ModelArenaHelper() {}

    static String validateAndExtractProvider(String slot) {
        if (slot == null || slot.isBlank()) {
            throw new IllegalArgumentException("Model slot cannot be blank");
        }
        if (slot.length() > MAX_SLOT_LENGTH) {
            throw new IllegalArgumentException("Model slot exceeds the maximum identifier length");
        }
        if (!SLOT_PATTERN.matcher(slot).matches()) {
            throw new IllegalArgumentException("Invalid model slot identifier syntax: " + slot);
        }
        String provider = slot.contains(":") ? slot.substring(0, slot.indexOf(':')) : slot;
        String cleanProv = provider.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_PROVIDERS.contains(cleanProv)) {
            throw new IllegalArgumentException("Unallowlisted model slot provider: " + cleanProv);
        }
        return cleanProv;
    }

    static String categoricalModelSlots(List<String> slots) {
        return slots.stream()
                .map(ModelArenaHelper::validateAndExtractProvider)
                .distinct()
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("none");
    }

    static String categorizeError(Throwable t) {
        if (t instanceof UnsupportedOperationException) {
            return "PROVIDER_UNSUPPORTED_CAPABILITY";
        }
        String msg = (t.getMessage() != null ? t.getMessage() : "").toLowerCase(Locale.ROOT);
        if (msg.contains("unsupported")) return "PROVIDER_UNSUPPORTED_CAPABILITY";
        if (msg.contains("timeout") || msg.contains("timed out")) return "PROVIDER_TIMEOUT";
        if (msg.contains("401")
                || msg.contains("unauthorized")
                || msg.contains("api_key")
                || msg.contains("api key")
                || msg.contains("invalid key")
                || msg.contains("auth token")
                || msg.contains("authentication")
                || msg.contains("access token")
                || msg.contains("bearer token")) {
            return "PROVIDER_AUTHENTICATION_ERROR";
        }
        if (msg.contains("429") || msg.contains("rate limit") || msg.contains("quota")) return "PROVIDER_RATE_LIMIT";
        if (msg.contains("not found")
                || msg.contains("unknown model")
                || msg.contains("model not found")
                || msg.contains("does not exist")
                || msg.contains("model_not_found")) {
            return "PROVIDER_UNKNOWN_MODEL";
        }
        return "PROVIDER_EXECUTION_ERROR";
    }

    static int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return (int) Math.ceil(text.length() / 4.0);
    }

    static String truncateToTokenBudget(String text, int maxTokens) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int maxChars = Math.max(1, maxTokens) * 4;
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    static double estimateCostUsd(int inTokens, int outTokens) {
        if (inTokens <= 0 && outTokens <= 0) {
            return 0.0;
        }
        return (inTokens * 0.0000015) + (outTokens * 0.000002);
    }

    static String hashText(String text) {
        if (text == null) return "null";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            return "sha256-error";
        }
    }

    static class ArenaConfigurationException extends RuntimeException {
        ArenaConfigurationException(String msg) {
            super(msg);
        }

        ArenaConfigurationException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    static class ArenaAuditException extends RuntimeException {
        ArenaAuditException(String msg) {
            super(msg);
        }

        ArenaAuditException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}

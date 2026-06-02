package com.arcclaw.app.util;

import java.util.Locale;

/**
 * Provider-specific API-key specifications for ArcClaw.
 *
 * <p>Each public constant describes the env-var name, a human-readable
 * description, and setup steps for a specific LLM provider's primary
 * API key. The static resolution methods ({@link #llmKeySpecFor},
 * {@link #compactionKeySpecFor}, {@link #classifierKeySpecFor},
 * {@link #embeddingKeySpecFor}) map a normalized provider name to the
 * correct {@link VarSpec}, returning {@code null} for providers that
 * do not require an API key (e.g. ollama).
 */
public final class ProviderKeySpecs {

    private ProviderKeySpecs() {
        // utility class
    }

    // -----------------------------------------------------------------------
    // Primary LLM provider key specs
    // -----------------------------------------------------------------------

    public static final VarSpec GROQ_KEY_SPEC = new VarSpec(
            "ARCCLAW_GROQ_API_KEY",
            "Groq Cloud API key. Starts with 'gsk_'.",
            "1. Sign up at https://console.groq.com",
            "2. Create an API key - it starts with 'gsk_'",
            "3. Add:  ARCCLAW_GROQ_API_KEY=gsk_your_key_here");

    public static final VarSpec OPENROUTER_KEY_SPEC = new VarSpec(
            "ARCCLAW_OPENROUTER_API_KEY",
            "OpenRouter API key. Starts with 'sk-or-v1-'.",
            "1. Sign up at https://openrouter.ai (free tier available)",
            "2. Create an API key - it starts with 'sk-or-v1-'",
            "3. Add:  ARCCLAW_OPENROUTER_API_KEY=sk-or-v1-your_key_here",
            "Free models with tool support: https://openrouter.ai/models?q=free");

    public static final VarSpec OPENAI_KEY_SPEC = new VarSpec(
            "ARCCLAW_OPENAI_API_KEY",
            "OpenAI API key. Starts with 'sk-'.",
            "1. Sign up at https://platform.openai.com",
            "2. Create a project API key",
            "3. Add:  ARCCLAW_OPENAI_API_KEY=sk-your_key_here");

    public static final VarSpec GEMINI_KEY_SPEC = new VarSpec(
            "ARCCLAW_GEMINI_API_KEY",
            "Google Gemini API key.",
            "1. Get a free key at https://aistudio.google.com",
            "2. Add:  ARCCLAW_GEMINI_API_KEY=AIza...");

    public static final VarSpec CEREBRAS_KEY_SPEC = new VarSpec(
            "ARCCLAW_CEREBRAS_API_KEY",
            "Cerebras AI API key.",
            "1. Sign up at https://cloud.cerebras.ai",
            "2. Create an API key - it starts with 'csk-'",
            "3. Add:  ARCCLAW_CEREBRAS_API_KEY=csk-your_key_here");

    public static final VarSpec NVIDIA_KEY_SPEC = new VarSpec(
            "ARCCLAW_NVIDIA_API_KEY",
            "NVIDIA NIM API key. Starts with 'nvapi-'.",
            "1. Sign up at https://build.nvidia.com",
            "2. Create an API key - it starts with 'nvapi-'",
            "3. Add:  ARCCLAW_NVIDIA_API_KEY=nvapi-your_key_here");

    /** OMLX uses local MLX server; no API key needed (defaults to 'not-needed'). */
    public static final VarSpec OMLX_KEY_SPEC = new VarSpec(
            "ARCCLAW_OMLX_API_KEY",
            "OMLX API key (optional for local server, defaults to 'not-needed').",
            "1. Run:  mlx_lm.server --model mlx-community/<model> --port 8000",
            "2. Add:  ARCCLAW_OMLX_API_KEY=not-needed");

    // -----------------------------------------------------------------------
    // Key spec resolution — primary LLM
    // -----------------------------------------------------------------------

    /**
     * Resolves the primary LLM API-key {@link VarSpec} for a provider name.
     * Returns {@code null} for {@code ollama} (no key required).
     */
    public static VarSpec llmKeySpecFor(String provider) {
        return switch (provider == null ? "groq" : provider.toLowerCase(Locale.ROOT)) {
            case "openrouter" -> OPENROUTER_KEY_SPEC;
            case "openai" -> OPENAI_KEY_SPEC;
            case "gemini" -> GEMINI_KEY_SPEC;
            case "nvidia" -> NVIDIA_KEY_SPEC;
            case "cerebras" -> CEREBRAS_KEY_SPEC;
            case "omlx" -> OMLX_KEY_SPEC;
            case "ollama" -> null;
            default -> GROQ_KEY_SPEC;
        };
    }

    // -----------------------------------------------------------------------
    // Key spec resolution — compaction slot
    // -----------------------------------------------------------------------

    /**
     * Per-provider API-key spec for the compaction slot. Returns {@code null}
     * for providers that don't need a key (ollama, omlx).
     */
    public static VarSpec compactionKeySpecFor(String provider) {
        return switch (provider) {
            case "ollama", "omlx" -> null;
            case "openrouter" ->
                providerKeySpec(
                        "openrouter", "ARCCLAW_COMPACTION_OPENROUTER_API_KEY", "sk-or-v1-", "https://openrouter.ai");
            case "openai" ->
                providerKeySpec("openai", "ARCCLAW_COMPACTION_OPENAI_API_KEY", "sk-", "https://platform.openai.com");
            case "gemini" ->
                providerKeySpec("gemini", "ARCCLAW_COMPACTION_GEMINI_API_KEY", "AIza", "https://aistudio.google.com");
            case "cerebras" ->
                providerKeySpec("cerebras", "ARCCLAW_COMPACTION_CEREBRAS_API_KEY", "csk-", "https://cloud.cerebras.ai");
            case "nvidia" ->
                providerKeySpec("nvidia", "ARCCLAW_COMPACTION_NVIDIA_API_KEY", "nvapi-", "https://build.nvidia.com");
            default -> providerKeySpec("groq", "ARCCLAW_COMPACTION_GROQ_API_KEY", "gsk_", "https://console.groq.com");
        };
    }

    // -----------------------------------------------------------------------
    // Key spec resolution — intent-classifier slot
    // -----------------------------------------------------------------------

    /**
     * Per-provider API-key spec for the intent-classifier slot. Returns {@code null}
     * for providers that don't need a key (ollama, omlx).
     */
    public static VarSpec classifierKeySpecFor(String provider) {
        return switch (provider) {
            case "ollama", "omlx" -> null;
            case "openrouter" ->
                providerKeySpec(
                        "openrouter", "ARCCLAW_CLASSIFIER_OPENROUTER_API_KEY", "sk-or-v1-", "https://openrouter.ai");
            case "openai" ->
                providerKeySpec("openai", "ARCCLAW_CLASSIFIER_OPENAI_API_KEY", "sk-", "https://platform.openai.com");
            case "gemini" ->
                providerKeySpec("gemini", "ARCCLAW_CLASSIFIER_GEMINI_API_KEY", "AIza", "https://aistudio.google.com");
            case "cerebras" ->
                providerKeySpec("cerebras", "ARCCLAW_CLASSIFIER_CEREBRAS_API_KEY", "csk-", "https://cloud.cerebras.ai");
            case "nvidia" ->
                providerKeySpec("nvidia", "ARCCLAW_CLASSIFIER_NVIDIA_API_KEY", "nvapi-", "https://build.nvidia.com");
            default -> providerKeySpec("groq", "ARCCLAW_CLASSIFIER_GROQ_API_KEY", "gsk_", "https://console.groq.com");
        };
    }

    // -----------------------------------------------------------------------
    // Key spec resolution — embedding slot
    // -----------------------------------------------------------------------

    /**
     * Per-provider API-key spec for the embedding slot. Returns {@code null}
     * for providers that don't need a key (ollama).
     */
    public static VarSpec embeddingKeySpecFor(String provider) {
        return switch (provider) {
            case "ollama" -> null;
            case "openai" ->
                providerKeySpec("openai", "ARCCLAW_EMBEDDING_OPENAI_API_KEY", "sk-", "https://platform.openai.com");
            case "jina" ->
                providerKeySpec(
                        "jina",
                        "ARCCLAW_EMBEDDING_JINA_API_KEY",
                        "jina_",
                        "https://jina.ai (free tier: 1M tokens/month, no credit card)");
            default -> null;
        };
    }

    // -----------------------------------------------------------------------
    // Shared builder
    // -----------------------------------------------------------------------

    private static VarSpec providerKeySpec(String provider, String envVar, String keyPrefix, String signupUrl) {
        return new VarSpec(
                envVar,
                "API key for the " + provider + " provider (starts with '" + keyPrefix + "').",
                "1. Sign up at " + signupUrl,
                "2. Create an API key - it starts with '" + keyPrefix + "'",
                "3. Add:  " + envVar + "=" + keyPrefix + "your_key_here");
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    /**
     * Resolves the first provider in a comma-separated provider list (e.g. "groq,ollama").
     */
    public static String firstProvider(String csv) {
        if (csv == null || csv.isBlank()) return "groq";
        int comma = csv.indexOf(',');
        return (comma < 0 ? csv : csv.substring(0, comma)).trim().toLowerCase(Locale.ROOT);
    }
}

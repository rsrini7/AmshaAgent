package com.amsha.app.util;

import java.util.Locale;

/**
 * Provider-specific API-key specifications for Amsha Agent.
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
            "AMSHA_GROQ_API_KEY",
            "Groq Cloud API key. Starts with 'gsk_'.",
            "1. Sign up at https://console.groq.com",
            "2. Create an API key - it starts with 'gsk_'",
            "3. Add:  AMSHA_GROQ_API_KEY=gsk_your_key_here");

    public static final VarSpec OPENROUTER_KEY_SPEC = new VarSpec(
            "AMSHA_OPENROUTER_API_KEY",
            "OpenRouter API key. Starts with 'sk-or-v1-'.",
            "1. Sign up at https://openrouter.ai (free tier available)",
            "2. Create an API key - it starts with 'sk-or-v1-'",
            "3. Add:  AMSHA_OPENROUTER_API_KEY=sk-or-v1-your_key_here",
            "Free models with tool support: https://openrouter.ai/models?q=free");

    public static final VarSpec OPENAI_KEY_SPEC = new VarSpec(
            "AMSHA_OPENAI_API_KEY",
            "OpenAI API key. Starts with 'sk-'.",
            "1. Sign up at https://platform.openai.com",
            "2. Create a project API key",
            "3. Add:  AMSHA_OPENAI_API_KEY=sk-your_key_here");

    public static final VarSpec GEMINI_KEY_SPEC = new VarSpec(
            "AMSHA_GEMINI_API_KEY",
            "Google Gemini API key.",
            "1. Get a free key at https://aistudio.google.com",
            "2. Add:  AMSHA_GEMINI_API_KEY=AIza...");

    public static final VarSpec CEREBRAS_KEY_SPEC = new VarSpec(
            "AMSHA_CEREBRAS_API_KEY",
            "Cerebras AI API key.",
            "1. Sign up at https://cloud.cerebras.ai",
            "2. Create an API key - it starts with 'csk-'",
            "3. Add:  AMSHA_CEREBRAS_API_KEY=csk-your_key_here");

    public static final VarSpec NVIDIA_KEY_SPEC = new VarSpec(
            "AMSHA_NVIDIA_API_KEY",
            "NVIDIA NIM API key. Starts with 'nvapi-'.",
            "1. Sign up at https://build.nvidia.com",
            "2. Create an API key - it starts with 'nvapi-'",
            "3. Add:  AMSHA_NVIDIA_API_KEY=nvapi-your_key_here");

    /** OMLX uses local MLX server; no API key needed (defaults to 'not-needed'). */
    public static final VarSpec OMLX_KEY_SPEC = new VarSpec(
            "AMSHA_OMLX_API_KEY",
            "OMLX API key (optional for local server, defaults to 'not-needed').",
            "1. Run:  mlx_lm.server --model mlx-community/<model> --port 8000",
            "2. Add:  AMSHA_OMLX_API_KEY=not-needed");

    public static final VarSpec SARVAM_KEY_SPEC = new VarSpec(
            "AMSHA_SARVAM_API_KEY",
            "Sarvam AI API key.",
            "1. Sign up at https://dashboard.sarvam.ai",
            "2. Create an API key",
            "3. Add:  AMSHA_SARVAM_API_KEY=your_key_here");

    public static final VarSpec OPENCODE_KEY_SPEC = new VarSpec(
            "AMSHA_OPENCODE_API_KEY",
            "OpenCode AI API key.",
            "1. Sign up at https://opencode.ai/zen",
            "2. Create an API key",
            "3. Add:  AMSHA_OPENCODE_API_KEY=your_key_here");

    public static final VarSpec HUGGINGFACE_KEY_SPEC = new VarSpec(
            "AMSHA_HUGGINGFACE_API_KEY",
            "Hugging Face User Access Token. Starts with 'hf_'.",
            "1. Sign up at https://huggingface.co",
            "2. Create an access token at https://huggingface.co/settings/tokens",
            "3. Add:  AMSHA_HUGGINGFACE_API_KEY=hf_your_key_here");

    public static final VarSpec MISTRAL_KEY_SPEC = new VarSpec(
            "AMSHA_MISTRAL_API_KEY",
            "Mistral AI API key.",
            "1. Sign up at https://console.mistral.ai",
            "2. Create an API key",
            "3. Add:  AMSHA_MISTRAL_API_KEY=your_key_here");

    public static final VarSpec GENERIC_OPENAI_KEY_SPEC = new VarSpec(
            "AMSHA_GENERIC_OPENAI_API_KEY",
            "Generic OpenAI-compatible API key.",
            "1. Sign up with your OpenAI-compatible provider",
            "2. Create an API key",
            "3. Add:  AMSHA_GENERIC_OPENAI_API_KEY=your_key_here");

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
            case "sarvam" -> SARVAM_KEY_SPEC;
            case "opencode" -> OPENCODE_KEY_SPEC;
            case "huggingface" -> HUGGINGFACE_KEY_SPEC;
            case "mistral" -> MISTRAL_KEY_SPEC;
            case "omlx" -> OMLX_KEY_SPEC;
            case "generic-openai" -> GENERIC_OPENAI_KEY_SPEC;
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
                        "openrouter", "AMSHA_COMPACTION_OPENROUTER_API_KEY", "sk-or-v1-", "https://openrouter.ai");
            case "openai" ->
                providerKeySpec("openai", "AMSHA_COMPACTION_OPENAI_API_KEY", "sk-", "https://platform.openai.com");
            case "gemini" ->
                providerKeySpec("gemini", "AMSHA_COMPACTION_GEMINI_API_KEY", "AIza", "https://aistudio.google.com");
            case "cerebras" ->
                providerKeySpec("cerebras", "AMSHA_COMPACTION_CEREBRAS_API_KEY", "csk-", "https://cloud.cerebras.ai");
            case "nvidia" ->
                providerKeySpec("nvidia", "AMSHA_COMPACTION_NVIDIA_API_KEY", "nvapi-", "https://build.nvidia.com");
            case "sarvam" ->
                providerKeySpec("sarvam", "AMSHA_COMPACTION_SARVAM_API_KEY", "", "https://dashboard.sarvam.ai");
            case "opencode" ->
                providerKeySpec("opencode", "AMSHA_COMPACTION_OPENCODE_API_KEY", "", "https://opencode.ai/zen");
            case "huggingface" ->
                providerKeySpec(
                        "huggingface",
                        "AMSHA_COMPACTION_HUGGINGFACE_API_KEY",
                        "hf_",
                        "https://huggingface.co/settings/tokens");
            case "mistral" ->
                providerKeySpec("mistral", "AMSHA_COMPACTION_MISTRAL_API_KEY", "", "https://console.mistral.ai");
            case "generic-openai" ->
                providerKeySpec(
                        "generic-openai", "AMSHA_COMPACTION_GENERIC_OPENAI_API_KEY", "", "your provider dashboard");
            default -> providerKeySpec("groq", "AMSHA_COMPACTION_GROQ_API_KEY", "gsk_", "https://console.groq.com");
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
                        "openrouter", "AMSHA_CLASSIFIER_OPENROUTER_API_KEY", "sk-or-v1-", "https://openrouter.ai");
            case "openai" ->
                providerKeySpec("openai", "AMSHA_CLASSIFIER_OPENAI_API_KEY", "sk-", "https://platform.openai.com");
            case "gemini" ->
                providerKeySpec("gemini", "AMSHA_CLASSIFIER_GEMINI_API_KEY", "AIza", "https://aistudio.google.com");
            case "cerebras" ->
                providerKeySpec("cerebras", "AMSHA_CLASSIFIER_CEREBRAS_API_KEY", "csk-", "https://cloud.cerebras.ai");
            case "nvidia" ->
                providerKeySpec("nvidia", "AMSHA_CLASSIFIER_NVIDIA_API_KEY", "nvapi-", "https://build.nvidia.com");
            case "sarvam" ->
                providerKeySpec("sarvam", "AMSHA_CLASSIFIER_SARVAM_API_KEY", "", "https://dashboard.sarvam.ai");
            case "opencode" ->
                providerKeySpec("opencode", "AMSHA_CLASSIFIER_OPENCODE_API_KEY", "", "https://opencode.ai/zen");
            case "huggingface" ->
                providerKeySpec(
                        "huggingface",
                        "AMSHA_CLASSIFIER_HUGGINGFACE_API_KEY",
                        "hf_",
                        "https://huggingface.co/settings/tokens");
            case "mistral" ->
                providerKeySpec("mistral", "AMSHA_CLASSIFIER_MISTRAL_API_KEY", "", "https://console.mistral.ai");
            case "generic-openai" ->
                providerKeySpec(
                        "generic-openai", "AMSHA_CLASSIFIER_GENERIC_OPENAI_API_KEY", "", "your provider dashboard");
            default -> providerKeySpec("groq", "AMSHA_CLASSIFIER_GROQ_API_KEY", "gsk_", "https://console.groq.com");
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
                providerKeySpec("openai", "AMSHA_EMBEDDING_OPENAI_API_KEY", "sk-", "https://platform.openai.com");
            case "jina" ->
                providerKeySpec(
                        "jina",
                        "AMSHA_EMBEDDING_JINA_API_KEY",
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

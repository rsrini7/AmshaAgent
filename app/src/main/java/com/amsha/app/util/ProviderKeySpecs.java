package com.amsha.app.util;

import java.util.Locale;
import java.util.Set;

import com.amsha.common.config.ConfigKeys;
import com.amsha.llm.api.ModelDefaults;

/**
 * Provider-specific API-key specifications for Amsha Agent.
 *
 * <p>Each public constant describes the env-var name, a human-readable
 * description, and setup steps for a specific LLM provider's primary
 * API key. The static resolution methods ({@link #llmKeySpecFor},
 * {@link #compactionKeySpecFor}, {@link #classifierKeySpecFor},
 * {@link #embeddingKeySpecFor}) map a normalized provider name to the
 * correct {@link VarSpec}, returning {@code null} for providers that
 * do not require an API key (e.g. ollama, codex).
 */
public final class ProviderKeySpecs {

    public static final Set<String> LLM_KEYLESS_PROVIDERS = Set.of(
            ModelDefaults.PROVIDER_OLLAMA,
            ModelDefaults.PROVIDER_OMLX,
            ModelDefaults.PROVIDER_LLAMACPP,
            ModelDefaults.PROVIDER_CODEX,
            ModelDefaults.PROVIDER_CLAUDE_CODE,
            ModelDefaults.PROVIDER_AGY,
            ModelDefaults.PROVIDER_KIMCHI,
            ModelDefaults.PROVIDER_FOUNDRY);
    public static final Set<String> COMPACTION_CLASSIFIER_KEYLESS_PROVIDERS = Set.of(
            ModelDefaults.PROVIDER_OLLAMA,
            ModelDefaults.PROVIDER_OMLX,
            ModelDefaults.PROVIDER_LLAMACPP,
            ModelDefaults.PROVIDER_CODEX,
            ModelDefaults.PROVIDER_CLAUDE_CODE,
            ModelDefaults.PROVIDER_AGY,
            ModelDefaults.PROVIDER_KIMCHI,
            ModelDefaults.PROVIDER_FOUNDRY);

    private ProviderKeySpecs() {
        // utility class
    }

    public static boolean isLlmKeyless(String provider) {
        return provider != null && LLM_KEYLESS_PROVIDERS.contains(provider.toLowerCase(Locale.ROOT));
    }

    public static boolean isCompactionClassifierKeyless(String provider) {
        return provider != null && COMPACTION_CLASSIFIER_KEYLESS_PROVIDERS.contains(provider.toLowerCase(Locale.ROOT));
    }

    // -----------------------------------------------------------------------
    // Primary LLM provider key specs
    // -----------------------------------------------------------------------

    public static final VarSpec GROQ_KEY_SPEC = new VarSpec(
            ConfigKeys.GROQ_API_KEY,
            "Groq Cloud API key. Starts with 'gsk_'.",
            "1. Sign up at https://console.groq.com",
            "2. Create an API key - it starts with 'gsk_'",
            "3. Add:  AMSHA_GROQ_API_KEY=gsk_your_key_here");

    public static final VarSpec OPENROUTER_KEY_SPEC = new VarSpec(
            ConfigKeys.OPENROUTER_API_KEY,
            "OpenRouter API key. Starts with 'sk-or-v1-'.",
            "1. Sign up at https://openrouter.ai (free tier available)",
            "2. Create an API key - it starts with 'sk-or-v1-'",
            "3. Add:  AMSHA_OPENROUTER_API_KEY=sk-or-v1-your_key_here",
            "Free models with tool support: https://openrouter.ai/models?q=free");

    public static final VarSpec OPENAI_KEY_SPEC = new VarSpec(
            ConfigKeys.OPENAI_API_KEY,
            "OpenAI API key. Starts with 'sk-'.",
            "1. Sign up at https://platform.openai.com",
            "2. Create a project API key",
            "3. Add:  AMSHA_OPENAI_API_KEY=sk-your_key_here");

    public static final VarSpec GEMINI_KEY_SPEC = new VarSpec(
            ConfigKeys.GEMINI_API_KEY,
            "Google Gemini API key.",
            "1. Get a free key at https://aistudio.google.com",
            "2. Add:  AMSHA_GEMINI_API_KEY=AIza...");

    public static final VarSpec CEREBRAS_KEY_SPEC = new VarSpec(
            ConfigKeys.CEREBRAS_API_KEY,
            "Cerebras AI API key.",
            "1. Sign up at https://cloud.cerebras.ai",
            "2. Create an API key - it starts with 'csk-'",
            "3. Add:  AMSHA_CEREBRAS_API_KEY=csk-your_key_here");

    public static final VarSpec NVIDIA_KEY_SPEC = new VarSpec(
            ConfigKeys.NVIDIA_API_KEY,
            "NVIDIA NIM API key. Starts with 'nvapi-'.",
            "1. Sign up at https://build.nvidia.com",
            "2. Create an API key - it starts with 'nvapi-'",
            "3. Add:  AMSHA_NVIDIA_API_KEY=nvapi-your_key_here");

    /** OMLX uses local MLX server; no API key needed (defaults to 'not-needed'). */
    public static final VarSpec OMLX_KEY_SPEC = new VarSpec(
            ConfigKeys.OMLX_API_KEY,
            "OMLX API key (optional for local server, defaults to 'not-needed').",
            "1. Run:  mlx_lm.server --model mlx-community/<model> --port 8000",
            "2. Add:  AMSHA_OMLX_API_KEY=not-needed");

    public static final VarSpec SARVAM_KEY_SPEC = new VarSpec(
            ConfigKeys.SARVAM_API_KEY,
            "Sarvam AI API key.",
            "1. Sign up at https://dashboard.sarvam.ai",
            "2. Create an API key",
            "3. Add:  AMSHA_SARVAM_API_KEY=your_key_here");

    public static final VarSpec OPENCODE_KEY_SPEC = new VarSpec(
            ConfigKeys.OPENCODE_API_KEY,
            "OpenCode AI API key.",
            "1. Sign up at https://opencode.ai/zen",
            "2. Create an API key",
            "3. Add:  AMSHA_OPENCODE_API_KEY=your_key_here");

    public static final VarSpec HUGGINGFACE_KEY_SPEC = new VarSpec(
            ConfigKeys.HUGGINGFACE_API_KEY,
            "Hugging Face User Access Token. Starts with 'hf_'.",
            "1. Sign up at https://huggingface.co",
            "2. Create an access token at https://huggingface.co/settings/tokens",
            "3. Add:  AMSHA_HUGGINGFACE_API_KEY=hf_your_key_here");

    public static final VarSpec MISTRAL_KEY_SPEC = new VarSpec(
            ConfigKeys.MISTRAL_API_KEY,
            "Mistral AI API key.",
            "1. Sign up at https://console.mistral.ai",
            "2. Create an API key",
            "3. Add:  AMSHA_MISTRAL_API_KEY=your_key_here");

    public static final VarSpec CLOUDFLARE_KEY_SPEC = new VarSpec(
            ConfigKeys.CLOUDFLARE_API_KEY,
            "Cloudflare API token for Workers AI.",
            "1. Sign up at https://dash.cloudflare.com",
            "2. Create an API token with Workers AI Read/Edit permissions",
            "3. Add:  AMSHA_CLOUDFLARE_API_KEY=your_api_token_here",
            "4. Add:  AMSHA_CLOUDFLARE_ACCOUNT_ID=your_account_id_here");

    public static final VarSpec COHERE_KEY_SPEC = new VarSpec(
            ConfigKeys.COHERE_API_KEY,
            "Cohere API key for Command R+ & enterprise models.",
            "1. Sign up at https://dashboard.cohere.com",
            "2. Create an API key",
            "3. Add:  AMSHA_COHERE_API_KEY=your_key_here");

    public static final VarSpec GENERIC_OPENAI_KEY_SPEC = new VarSpec(
            ConfigKeys.GENERIC_OPENAI_API_KEY,
            "Generic OpenAI-compatible API key.",
            "1. Sign up with your OpenAI-compatible provider",
            "2. Create an API key",
            "3. Add:  AMSHA_GENERIC_OPENAI_API_KEY=your_key_here");

    /** Shared API credential for the optional Gnani speech adapters. */
    public static final VarSpec GNANI_SPEECH_KEY_SPEC = new VarSpec(
            ConfigKeys.GNANI_API_KEY,
            "Gnani API key (required when Gnani STT or TTS is enabled).",
            "1. Obtain an API key for your Gnani account",
            "2. Add: AMSHA_GNANI_API_KEY=your_key_here");

    // -----------------------------------------------------------------------
    // Key spec resolution — primary LLM
    // -----------------------------------------------------------------------

    /**
     * Resolves the primary LLM API-key {@link VarSpec} for a provider name.
     * Returns {@code null} for keyless providers (e.g. ollama, codex).
     */
    public static VarSpec llmKeySpecFor(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        if (isLlmKeyless(provider)) {
            return null;
        }
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case ModelDefaults.PROVIDER_OPENROUTER -> OPENROUTER_KEY_SPEC;
            case ModelDefaults.PROVIDER_OPENAI -> OPENAI_KEY_SPEC;
            case ModelDefaults.PROVIDER_GEMINI -> GEMINI_KEY_SPEC;
            case ModelDefaults.PROVIDER_NVIDIA -> NVIDIA_KEY_SPEC;
            case ModelDefaults.PROVIDER_CEREBRAS -> CEREBRAS_KEY_SPEC;
            case ModelDefaults.PROVIDER_SARVAM -> SARVAM_KEY_SPEC;
            case ModelDefaults.PROVIDER_OPENCODE -> OPENCODE_KEY_SPEC;
            case ModelDefaults.PROVIDER_HUGGINGFACE -> HUGGINGFACE_KEY_SPEC;
            case ModelDefaults.PROVIDER_MISTRAL -> MISTRAL_KEY_SPEC;
            case ModelDefaults.PROVIDER_CLOUDFLARE -> CLOUDFLARE_KEY_SPEC;
            case ModelDefaults.PROVIDER_COHERE -> COHERE_KEY_SPEC;
            case ModelDefaults.PROVIDER_OMLX -> OMLX_KEY_SPEC;
            case ModelDefaults.PROVIDER_GENERIC_OPENAI -> GENERIC_OPENAI_KEY_SPEC;
            case ModelDefaults.PROVIDER_GROQ -> GROQ_KEY_SPEC;
            default -> null;
        };
    }

    // -----------------------------------------------------------------------
    // Key spec resolution — compaction slot
    // -----------------------------------------------------------------------

    /**
     * Per-provider API-key spec for the compaction slot. Returns {@code null}
     * for providers that don't need a key (ollama, omlx, codex).
     */
    public static VarSpec compactionKeySpecFor(String provider) {
        if (isCompactionClassifierKeyless(provider)) {
            return null;
        }
        return switch (provider) {
            case ModelDefaults.PROVIDER_OPENROUTER ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_OPENROUTER,
                        ConfigKeys.COMPACTION_OPENROUTER_API_KEY,
                        "sk-or-v1-",
                        "https://openrouter.ai");
            case ModelDefaults.PROVIDER_OPENAI ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_OPENAI,
                        ConfigKeys.COMPACTION_OPENAI_API_KEY,
                        "sk-",
                        "https://platform.openai.com");
            case ModelDefaults.PROVIDER_GEMINI ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_GEMINI,
                        ConfigKeys.COMPACTION_GEMINI_API_KEY,
                        "AIza",
                        "https://aistudio.google.com");
            case ModelDefaults.PROVIDER_CEREBRAS ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_CEREBRAS,
                        ConfigKeys.COMPACTION_CEREBRAS_API_KEY,
                        "csk-",
                        "https://cloud.cerebras.ai");
            case ModelDefaults.PROVIDER_NVIDIA ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_NVIDIA,
                        ConfigKeys.COMPACTION_NVIDIA_API_KEY,
                        "nvapi-",
                        "https://build.nvidia.com");
            case ModelDefaults.PROVIDER_SARVAM ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_SARVAM,
                        ConfigKeys.COMPACTION_SARVAM_API_KEY,
                        "",
                        "https://dashboard.sarvam.ai");
            case ModelDefaults.PROVIDER_OPENCODE ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_OPENCODE,
                        ConfigKeys.COMPACTION_OPENCODE_API_KEY,
                        "",
                        "https://opencode.ai/zen");
            case ModelDefaults.PROVIDER_HUGGINGFACE ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_HUGGINGFACE,
                        ConfigKeys.COMPACTION_HUGGINGFACE_API_KEY,
                        "hf_",
                        "https://huggingface.co/settings/tokens");
            case ModelDefaults.PROVIDER_MISTRAL ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_MISTRAL,
                        ConfigKeys.COMPACTION_MISTRAL_API_KEY,
                        "",
                        "https://console.mistral.ai");
            case ModelDefaults.PROVIDER_CLOUDFLARE ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_CLOUDFLARE,
                        ConfigKeys.COMPACTION_CLOUDFLARE_API_KEY,
                        "",
                        "https://dash.cloudflare.com");
            case ModelDefaults.PROVIDER_COHERE ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_COHERE,
                        ConfigKeys.COMPACTION_COHERE_API_KEY,
                        "",
                        "https://dashboard.cohere.com");
            case ModelDefaults.PROVIDER_GENERIC_OPENAI ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_GENERIC_OPENAI,
                        ConfigKeys.COMPACTION_GENERIC_OPENAI_API_KEY,
                        "",
                        "your provider dashboard");
            case ModelDefaults.PROVIDER_GROQ ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_GROQ,
                        ConfigKeys.COMPACTION_GROQ_API_KEY,
                        "gsk_",
                        "https://console.groq.com");
            default -> null;
        };
    }

    // -----------------------------------------------------------------------
    // Key spec resolution — intent-classifier slot
    // -----------------------------------------------------------------------

    /**
     * Per-provider API-key spec for the intent-classifier slot. Returns {@code null}
     * for providers that don't need a key (ollama, omlx, codex).
     */
    public static VarSpec classifierKeySpecFor(String provider) {
        if (isCompactionClassifierKeyless(provider)) {
            return null;
        }
        return switch (provider) {
            case ModelDefaults.PROVIDER_OPENROUTER ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_OPENROUTER,
                        ConfigKeys.CLASSIFIER_OPENROUTER_API_KEY,
                        "sk-or-v1-",
                        "https://openrouter.ai");
            case ModelDefaults.PROVIDER_OPENAI ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_OPENAI,
                        ConfigKeys.CLASSIFIER_OPENAI_API_KEY,
                        "sk-",
                        "https://platform.openai.com");
            case ModelDefaults.PROVIDER_GEMINI ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_GEMINI,
                        ConfigKeys.CLASSIFIER_GEMINI_API_KEY,
                        "AIza",
                        "https://aistudio.google.com");
            case ModelDefaults.PROVIDER_CEREBRAS ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_CEREBRAS,
                        ConfigKeys.CLASSIFIER_CEREBRAS_API_KEY,
                        "csk-",
                        "https://cloud.cerebras.ai");
            case ModelDefaults.PROVIDER_NVIDIA ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_NVIDIA,
                        ConfigKeys.CLASSIFIER_NVIDIA_API_KEY,
                        "nvapi-",
                        "https://build.nvidia.com");
            case ModelDefaults.PROVIDER_SARVAM ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_SARVAM,
                        ConfigKeys.CLASSIFIER_SARVAM_API_KEY,
                        "",
                        "https://dashboard.sarvam.ai");
            case ModelDefaults.PROVIDER_OPENCODE ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_OPENCODE,
                        ConfigKeys.CLASSIFIER_OPENCODE_API_KEY,
                        "",
                        "https://opencode.ai/zen");
            case ModelDefaults.PROVIDER_HUGGINGFACE ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_HUGGINGFACE,
                        ConfigKeys.CLASSIFIER_HUGGINGFACE_API_KEY,
                        "hf_",
                        "https://huggingface.co/settings/tokens");
            case ModelDefaults.PROVIDER_MISTRAL ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_MISTRAL,
                        ConfigKeys.CLASSIFIER_MISTRAL_API_KEY,
                        "",
                        "https://console.mistral.ai");
            case ModelDefaults.PROVIDER_CLOUDFLARE ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_CLOUDFLARE,
                        ConfigKeys.CLASSIFIER_CLOUDFLARE_API_KEY,
                        "",
                        "https://dash.cloudflare.com");
            case ModelDefaults.PROVIDER_COHERE ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_COHERE,
                        ConfigKeys.CLASSIFIER_COHERE_API_KEY,
                        "",
                        "https://dashboard.cohere.com");
            case ModelDefaults.PROVIDER_GENERIC_OPENAI ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_GENERIC_OPENAI,
                        ConfigKeys.CLASSIFIER_GENERIC_OPENAI_API_KEY,
                        "",
                        "your provider dashboard");
            case ModelDefaults.PROVIDER_GROQ ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_GROQ,
                        ConfigKeys.CLASSIFIER_GROQ_API_KEY,
                        "gsk_",
                        "https://console.groq.com");
            default -> null;
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
            case ModelDefaults.PROVIDER_OLLAMA -> null;
            case ModelDefaults.PROVIDER_OPENAI ->
                providerKeySpec(
                        ModelDefaults.PROVIDER_OPENAI,
                        ConfigKeys.EMBEDDING_OPENAI_API_KEY,
                        "sk-",
                        "https://platform.openai.com");
            case "jina" ->
                providerKeySpec(
                        "jina",
                        ConfigKeys.EMBEDDING_JINA_API_KEY,
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
        if (csv == null || csv.isBlank()) return "";
        int comma = csv.indexOf(',');
        return (comma < 0 ? csv : csv.substring(0, comma)).trim().toLowerCase(Locale.ROOT);
    }
}

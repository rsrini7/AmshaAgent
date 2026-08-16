package com.amsha.core.routing;

import java.util.Locale;
import java.util.Map;

/**
 * Single source of truth for all default model names, base URLs, and provider constants.
 * Every provider factory, catalog, validation service, and UI endpoint must reference
 * these values instead of hardcoding strings. This eliminates the need to update
 * multiple files when a default model changes.
 */
public final class ModelDefaults {

    private ModelDefaults() {}

    // ── Provider IDs ─────────────────────────────────────────────

    public static final String PROVIDER_GROQ = "groq";
    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_OLLAMA = "ollama";
    public static final String PROVIDER_ANTHROPIC = "anthropic";
    public static final String PROVIDER_CLAUDE_CODE = "claude-code";
    public static final String PROVIDER_OPENROUTER = "openrouter";
    public static final String PROVIDER_NVIDIA = "nvidia";
    public static final String PROVIDER_CEREBRAS = "cerebras";
    public static final String PROVIDER_SARVAM = "sarvam";
    public static final String PROVIDER_OPENCODE = "opencode";
    public static final String PROVIDER_HUGGINGFACE = "huggingface";
    public static final String PROVIDER_GEMINI = "gemini";
    public static final String PROVIDER_OMLX = "omlx";
    public static final String PROVIDER_LLAMACPP = "llamacpp";
    public static final String PROVIDER_GENERIC_OPENAI = "generic-openai";
    public static final String PROVIDER_INFINITE_LOOP = "infinite-loop";

    // ── Default Model Names ──────────────────────────────────────

    public static final String DEFAULT_GROQ_MODEL = "meta-llama/llama-4-scout-17b-16e-instruct";
    public static final String DEFAULT_OPENAI_MODEL = "gpt-4o-mini";
    public static final String DEFAULT_OLLAMA_MODEL = "mistral-small:latest";
    public static final String DEFAULT_ANTHROPIC_MODEL = "claude-haiku-4-5";
    public static final String DEFAULT_CLAUDE_CODE_MODEL = "claude-sonnet-4-5";
    public static final String DEFAULT_OPENROUTER_MODEL = "openai/gpt-oss-20b:free";
    public static final String DEFAULT_NVIDIA_MODEL = "bytedance/seed-oss-36b-instruct";
    public static final String DEFAULT_CEREBRAS_MODEL = "qwen-3-235b-a22b-instruct-2507";
    public static final String DEFAULT_SARVAM_MODEL = "sarvam-105b";
    public static final String DEFAULT_OPENCODE_MODEL = "deepseek-v4-flash-free";
    public static final String DEFAULT_HUGGINGFACE_MODEL = "meta-llama/Llama-3.3-70B-Instruct";
    public static final String DEFAULT_GEMINI_MODEL = "gemini-2.5-flash-lite";
    public static final String DEFAULT_OMLX_MODEL = "mlx-community/Qwen3.6-35B-A3B-4bit";
    public static final String DEFAULT_LLAMACPP_MODEL = "qwen3";
    public static final String DEFAULT_GENERIC_OPENAI_MODEL = "gpt-4o-mini";
    public static final String DEFAULT_INFINITE_LOOP_MODEL = "infinite-loop-mock";

    // ── Default Base URLs ────────────────────────────────────────

    public static final String DEFAULT_GROQ_BASE_URL = "https://api.groq.com/openai/v1";
    public static final String DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";
    public static final String DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com";
    public static final String DEFAULT_OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1";
    public static final String DEFAULT_NVIDIA_BASE_URL = "https://integrate.api.nvidia.com/v1";
    public static final String DEFAULT_CEREBRAS_BASE_URL = "https://api.cerebras.ai/v1";
    public static final String DEFAULT_SARVAM_BASE_URL = "https://api.sarvam.ai/v1";
    public static final String DEFAULT_OPENCODE_BASE_URL = "https://opencode.ai/zen/v1";
    public static final String DEFAULT_HUGGINGFACE_BASE_URL = "https://router.huggingface.co/v1";
    public static final String DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    public static final String DEFAULT_OMLX_BASE_URL = "http://127.0.0.1:8000/v1";
    public static final String DEFAULT_LLAMACPP_BASE_URL = "http://127.0.0.1:9000/v1";

    // ── Embedding Defaults ───────────────────────────────────────

    public static final String DEFAULT_OLLAMA_EMBED_MODEL = "nomic-embed-text:v1.5";
    public static final String DEFAULT_JINA_EMBED_MODEL = "jina-embeddings-v5-text-nano";

    // ── Fallback Provider ────────────────────────────────────────

    public static final String DEFAULT_PROVIDER = PROVIDER_GROQ;

    // ── URL-to-Provider Mapping ──────────────────────────────────

    private static final Map<String, String> URL_PROVIDER_MAP = Map.ofEntries(
            Map.entry("openai.com", PROVIDER_OPENAI),
            Map.entry("groq.com", PROVIDER_GROQ),
            Map.entry("nvidia.com", PROVIDER_NVIDIA),
            Map.entry("cerebras.ai", PROVIDER_CEREBRAS),
            Map.entry("sarvam.ai", PROVIDER_SARVAM),
            Map.entry("opencode.ai", PROVIDER_OPENCODE),
            Map.entry("huggingface.co", PROVIDER_HUGGINGFACE),
            Map.entry("googleapis.com", PROVIDER_GEMINI),
            Map.entry("anthropic.com", PROVIDER_ANTHROPIC),
            Map.entry("localhost:11434", PROVIDER_OLLAMA),
            Map.entry("127.0.0.1:11434", PROVIDER_OLLAMA),
            Map.entry("localhost:8000", PROVIDER_OMLX),
            Map.entry("127.0.0.1:8000", PROVIDER_OMLX),
            Map.entry("localhost:9000", PROVIDER_LLAMACPP),
            Map.entry("127.0.0.1:9000", PROVIDER_LLAMACPP));

    /**
     * Returns the default model name for the given provider ID.
     * Falls back to {@link #DEFAULT_GROQ_MODEL} for unknown providers.
     */
    public static String defaultModelForProvider(String provider) {
        if (provider == null) return DEFAULT_GROQ_MODEL;
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case PROVIDER_GROQ -> DEFAULT_GROQ_MODEL;
            case PROVIDER_OPENAI -> DEFAULT_OPENAI_MODEL;
            case PROVIDER_OLLAMA -> DEFAULT_OLLAMA_MODEL;
            case PROVIDER_ANTHROPIC -> DEFAULT_ANTHROPIC_MODEL;
            case PROVIDER_CLAUDE_CODE -> DEFAULT_CLAUDE_CODE_MODEL;
            case PROVIDER_OPENROUTER -> DEFAULT_OPENROUTER_MODEL;
            case PROVIDER_NVIDIA -> DEFAULT_NVIDIA_MODEL;
            case PROVIDER_CEREBRAS -> DEFAULT_CEREBRAS_MODEL;
            case PROVIDER_SARVAM -> DEFAULT_SARVAM_MODEL;
            case PROVIDER_OPENCODE -> DEFAULT_OPENCODE_MODEL;
            case PROVIDER_HUGGINGFACE -> DEFAULT_HUGGINGFACE_MODEL;
            case PROVIDER_GEMINI -> DEFAULT_GEMINI_MODEL;
            case PROVIDER_OMLX -> DEFAULT_OMLX_MODEL;
            case PROVIDER_LLAMACPP -> DEFAULT_LLAMACPP_MODEL;
            case PROVIDER_GENERIC_OPENAI -> DEFAULT_GENERIC_OPENAI_MODEL;
            case PROVIDER_INFINITE_LOOP -> DEFAULT_INFINITE_LOOP_MODEL;
            default -> DEFAULT_GROQ_MODEL;
        };
    }

    /**
     * Returns the default base URL for the given provider ID.
     * Returns an empty string for unknown providers.
     */
    public static String defaultBaseUrlForProvider(String provider) {
        if (provider == null) return "";
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case PROVIDER_GROQ -> DEFAULT_GROQ_BASE_URL;
            case PROVIDER_OPENAI -> DEFAULT_OPENAI_BASE_URL;
            case PROVIDER_OLLAMA -> DEFAULT_OLLAMA_BASE_URL;
            case PROVIDER_ANTHROPIC -> DEFAULT_ANTHROPIC_BASE_URL;
            case PROVIDER_OPENROUTER -> DEFAULT_OPENROUTER_BASE_URL;
            case PROVIDER_NVIDIA -> DEFAULT_NVIDIA_BASE_URL;
            case PROVIDER_CEREBRAS -> DEFAULT_CEREBRAS_BASE_URL;
            case PROVIDER_SARVAM -> DEFAULT_SARVAM_BASE_URL;
            case PROVIDER_OPENCODE -> DEFAULT_OPENCODE_BASE_URL;
            case PROVIDER_HUGGINGFACE -> DEFAULT_HUGGINGFACE_BASE_URL;
            case PROVIDER_GEMINI -> DEFAULT_GEMINI_BASE_URL;
            case PROVIDER_OMLX -> DEFAULT_OMLX_BASE_URL;
            case PROVIDER_LLAMACPP -> DEFAULT_LLAMACPP_BASE_URL;
            default -> "";
        };
    }

    /**
     * Infers the provider ID from a base URL by matching known URL fragments.
     * Falls back to {@link #PROVIDER_OPENROUTER} for unrecognized URLs.
     */
    public static String inferProviderFromUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return PROVIDER_OPENROUTER;
        return URL_PROVIDER_MAP.entrySet().stream()
                .filter(entry -> baseUrl.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(PROVIDER_OPENROUTER);
    }
}

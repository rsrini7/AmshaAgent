package com.amsha.llm.api;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.amsha.common.http.HttpProtocol;

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
    public static final String PROVIDER_MISTRAL = "mistral";
    public static final String PROVIDER_CLOUDFLARE = "cloudflare";
    public static final String PROVIDER_COHERE = "cohere";
    public static final String PROVIDER_CODEX = "codex";
    public static final String PROVIDER_AGY = "agy";
    public static final String PROVIDER_KIMCHI = "kimchi";
    public static final String PROVIDER_FOUNDRY = "foundry";
    public static final String PROVIDER_GEMINI = "gemini";
    public static final String PROVIDER_OMLX = "omlx";
    public static final String PROVIDER_LLAMACPP = "llamacpp";
    public static final String PROVIDER_GENERIC_OPENAI = "generic-openai";
    public static final String PROVIDER_INFINITE_LOOP = "infinite-loop";

    public static final Set<String> ALL_PROVIDERS = Set.of(
            PROVIDER_GROQ,
            PROVIDER_OPENAI,
            PROVIDER_OLLAMA,
            PROVIDER_ANTHROPIC,
            PROVIDER_CLAUDE_CODE,
            PROVIDER_OPENROUTER,
            PROVIDER_NVIDIA,
            PROVIDER_CEREBRAS,
            PROVIDER_SARVAM,
            PROVIDER_OPENCODE,
            PROVIDER_HUGGINGFACE,
            PROVIDER_MISTRAL,
            PROVIDER_CLOUDFLARE,
            PROVIDER_COHERE,
            PROVIDER_CODEX,
            PROVIDER_AGY,
            PROVIDER_KIMCHI,
            PROVIDER_FOUNDRY,
            PROVIDER_GEMINI,
            PROVIDER_OMLX,
            PROVIDER_LLAMACPP,
            PROVIDER_GENERIC_OPENAI,
            PROVIDER_INFINITE_LOOP);

    public static boolean isKnownProvider(String provider) {
        return provider != null && ALL_PROVIDERS.contains(provider.toLowerCase(Locale.ROOT));
    }

    // ── Default Model Names ──────────────────────────────────────

    public static final String DEFAULT_GROQ_MODEL = "openai/gpt-oss-120b";
    public static final String DEFAULT_OPENAI_MODEL = "gpt-4o-mini";
    public static final String DEFAULT_OLLAMA_MODEL = "mistral-small:latest";
    public static final String DEFAULT_ANTHROPIC_MODEL = "claude-haiku-4-5";
    public static final String DEFAULT_CLAUDE_CODE_MODEL = "claude-sonnet-4-5";
    public static final String DEFAULT_OPENROUTER_MODEL = "minimax/minimax-m3:free";
    public static final String DEFAULT_NVIDIA_MODEL = "meta/llama-3.2-11b-vision-instruct";
    public static final String DEFAULT_CEREBRAS_MODEL = "qwen-3.8-27b";
    public static final String DEFAULT_SARVAM_MODEL = "sarvam-105b-conversations";
    public static final String DEFAULT_OPENCODE_MODEL = "deepseek-v4-flash-free";
    public static final String DEFAULT_HUGGINGFACE_MODEL = "meta-llama/Llama-3.3-70B-Instruct";
    public static final String DEFAULT_MISTRAL_MODEL = "codestral-latest";
    public static final String DEFAULT_CLOUDFLARE_MODEL = "@cf/meta/llama-3.3-70b-instruct-fp8-fast";
    public static final String DEFAULT_COHERE_MODEL = "command-r-08-2024";
    public static final String DEFAULT_CODEX_MODEL = "gpt-5.6-luna";
    public static final String DEFAULT_AGY_MODEL = "gemini-3.1-flash-lite";
    public static final String DEFAULT_KIMCHI_MODEL = "minimax-m3";
    public static final String DEFAULT_FOUNDRY_MODEL = "qwen3-14b";
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
    public static final String DEFAULT_CLAUDE_CODE_BASE_URL = "https://api.anthropic.com";
    public static final String DEFAULT_OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1";
    public static final String DEFAULT_NVIDIA_BASE_URL = "https://integrate.api.nvidia.com/v1";
    public static final String DEFAULT_CEREBRAS_BASE_URL = "https://api.cerebras.ai/v1";
    public static final String DEFAULT_SARVAM_BASE_URL = "https://api.sarvam.ai/v1";
    public static final String DEFAULT_OPENCODE_BASE_URL = "https://opencode.ai/zen/v1";
    public static final String DEFAULT_HUGGINGFACE_BASE_URL = "https://router.huggingface.co/v1";
    public static final String DEFAULT_MISTRAL_BASE_URL = "https://api.mistral.ai/v1";
    public static final String DEFAULT_CLOUDFLARE_BASE_URL =
            "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1";
    public static final String DEFAULT_COHERE_BASE_URL = "https://api.cohere.ai/compatibility/v1";
    public static final String DEFAULT_CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex";
    public static final String DEFAULT_CODEX_OPENAI_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_CODEX_HROOM_BASE_URL = "http://127.0.0.1:8787/v1";
    public static final String DEFAULT_AGY_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai";
    public static final String DEFAULT_KIMCHI_BASE_URL = "https://llm.kimchi.dev/openai/v1";
    public static final String DEFAULT_FOUNDRY_BASE_URL = "http://127.0.0.1:8000/v1";
    public static final String DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    public static final String DEFAULT_OMLX_BASE_URL = "http://127.0.0.1:8000/v1";
    public static final String DEFAULT_LLAMACPP_BASE_URL = "http://127.0.0.1:9000/v1";

    // ── Embedding Defaults ───────────────────────────────────────

    public static final String DEFAULT_OLLAMA_EMBED_MODEL = "nomic-embed-text:v1.5";
    public static final String DEFAULT_JINA_EMBED_MODEL = "jina-embeddings-v5-text-nano";

    // ── Provider Selection ───────────────────────────────────────

    /** Empty until a user explicitly selects a provider. */
    public static final String DEFAULT_PROVIDER = "";

    // ── Unset-Model Sentinel ─────────────────────────────────────

    /**
     * Legacy placeholder treated as "no explicit model selected". Every provider factory falls back to its
     * configured default when the requested model is {@code null}, blank, or equal (case-insensitively) to
     * this value. Reference this constant instead of retyping the literal.
     */
    public static final String PLACEHOLDER_MODEL = "mistral-small";

    // ── URL-to-Provider Mapping ──────────────────────────────────

    private static final Map<String, String> URL_PROVIDER_MAP = Map.ofEntries(
            Map.entry("openai.com", PROVIDER_OPENAI),
            Map.entry("groq.com", PROVIDER_GROQ),
            Map.entry("nvidia.com", PROVIDER_NVIDIA),
            Map.entry("cerebras.ai", PROVIDER_CEREBRAS),
            Map.entry("sarvam.ai", PROVIDER_SARVAM),
            Map.entry("opencode.ai", PROVIDER_OPENCODE),
            Map.entry("huggingface.co", PROVIDER_HUGGINGFACE),
            Map.entry("mistral.ai", PROVIDER_MISTRAL),
            Map.entry("cloudflare.com", PROVIDER_CLOUDFLARE),
            Map.entry("cohere.ai", PROVIDER_COHERE),
            Map.entry("googleapis.com", PROVIDER_GEMINI),
            Map.entry("anthropic.com", PROVIDER_ANTHROPIC),
            Map.entry("kimchi.dev", PROVIDER_KIMCHI),
            Map.entry("localhost:11434", PROVIDER_OLLAMA),
            Map.entry("127.0.0.1:11434", PROVIDER_OLLAMA),
            Map.entry("localhost:8000", PROVIDER_OMLX),
            Map.entry("127.0.0.1:8000", PROVIDER_OMLX),
            Map.entry("localhost:9000", PROVIDER_LLAMACPP),
            Map.entry("127.0.0.1:9000", PROVIDER_LLAMACPP));

    /**
     * Returns the default model name for a known provider ID.
     * Returns an empty string when no provider is selected or the ID is unknown.
     */
    public static String defaultModelForProvider(String provider) {
        if (provider == null || provider.isBlank()) return "";
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
            case PROVIDER_MISTRAL -> DEFAULT_MISTRAL_MODEL;
            case PROVIDER_CLOUDFLARE -> DEFAULT_CLOUDFLARE_MODEL;
            case PROVIDER_COHERE -> DEFAULT_COHERE_MODEL;
            case PROVIDER_CODEX -> DEFAULT_CODEX_MODEL;
            case PROVIDER_AGY -> DEFAULT_AGY_MODEL;
            case PROVIDER_KIMCHI -> DEFAULT_KIMCHI_MODEL;
            case PROVIDER_FOUNDRY -> DEFAULT_FOUNDRY_MODEL;
            case PROVIDER_GEMINI -> DEFAULT_GEMINI_MODEL;
            case PROVIDER_OMLX -> DEFAULT_OMLX_MODEL;
            case PROVIDER_LLAMACPP -> DEFAULT_LLAMACPP_MODEL;
            case PROVIDER_GENERIC_OPENAI -> DEFAULT_GENERIC_OPENAI_MODEL;
            case PROVIDER_INFINITE_LOOP -> DEFAULT_INFINITE_LOOP_MODEL;
            default -> "";
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
            case PROVIDER_CLAUDE_CODE -> DEFAULT_CLAUDE_CODE_BASE_URL;
            case PROVIDER_OPENROUTER -> DEFAULT_OPENROUTER_BASE_URL;
            case PROVIDER_NVIDIA -> DEFAULT_NVIDIA_BASE_URL;
            case PROVIDER_CEREBRAS -> DEFAULT_CEREBRAS_BASE_URL;
            case PROVIDER_SARVAM -> DEFAULT_SARVAM_BASE_URL;
            case PROVIDER_OPENCODE -> DEFAULT_OPENCODE_BASE_URL;
            case PROVIDER_HUGGINGFACE -> DEFAULT_HUGGINGFACE_BASE_URL;
            case PROVIDER_MISTRAL -> DEFAULT_MISTRAL_BASE_URL;
            case PROVIDER_CLOUDFLARE -> DEFAULT_CLOUDFLARE_BASE_URL;
            case PROVIDER_COHERE -> DEFAULT_COHERE_BASE_URL;
            case PROVIDER_CODEX -> DEFAULT_CODEX_BASE_URL;
            case PROVIDER_AGY -> DEFAULT_AGY_BASE_URL;
            case PROVIDER_KIMCHI -> DEFAULT_KIMCHI_BASE_URL;
            case PROVIDER_FOUNDRY -> DEFAULT_FOUNDRY_BASE_URL;
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

    /**
     * True when the provider requires an explicit target or base URL (e.g. generic-openai).
     */
    public static boolean requiresCustomBaseUrl(String provider) {
        return provider != null && PROVIDER_GENERIC_OPENAI.equalsIgnoreCase(provider.trim());
    }

    /**
     * Resolves the catalog or health probe URL for the given provider and base URL.
     */
    public static String probeUrlForProvider(String provider, String baseUrl) {
        String effectiveBase =
                (baseUrl != null && !baseUrl.isBlank()) ? baseUrl.trim() : defaultBaseUrlForProvider(provider);
        if (effectiveBase.isBlank()) {
            return "";
        }
        String normalized =
                effectiveBase.endsWith("/") ? effectiveBase.substring(0, effectiveBase.length() - 1) : effectiveBase;
        if (provider != null) {
            String p = provider.toLowerCase(Locale.ROOT);
            if (PROVIDER_OLLAMA.equals(p)) {
                return normalized.endsWith("/v1") ? normalized + "/models" : normalized + "/api/tags";
            }
            if (PROVIDER_ANTHROPIC.equals(p) || PROVIDER_CLAUDE_CODE.equals(p)) {
                return normalized.endsWith("/v1") ? normalized + "/models" : normalized + "/v1/models";
            }
        }
        if (normalized.endsWith("/models")) {
            return normalized;
        }
        return normalized + "/models";
    }

    /**
     * Returns standard authentication and version headers for a provider given its API key.
     */
    public static Map<String, String> authHeadersForProvider(String provider, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Map.of();
        }
        if (provider != null) {
            String p = provider.toLowerCase(Locale.ROOT);
            if (PROVIDER_ANTHROPIC.equals(p) || PROVIDER_CLAUDE_CODE.equals(p)) {
                return Map.of("x-api-key", apiKey, "anthropic-version", "2023-06-01");
            }
        }
        return Map.of(HttpProtocol.HEADER_AUTHORIZATION, HttpProtocol.AUTH_SCHEME_BEARER + apiKey);
    }
}

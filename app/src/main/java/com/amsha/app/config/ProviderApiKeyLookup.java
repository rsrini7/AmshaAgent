package com.amsha.app.config;

import java.util.Locale;

import com.amsha.app.util.ProviderKeySpecs;
import com.amsha.common.config.ConfigKeys;
import com.amsha.llm.api.ModelDefaults;

/** Resolves provider IDs to their primary and auxiliary API key settings. */
final class ProviderApiKeyLookup {

    private ProviderApiKeyLookup() {}

    static String llmApiKeyForProvider(String provider) {
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case ModelDefaults.PROVIDER_OPENROUTER -> ConfigKeys.OPENROUTER_API_KEY;
            case ModelDefaults.PROVIDER_OPENAI -> ConfigKeys.OPENAI_API_KEY;
            case ModelDefaults.PROVIDER_GEMINI -> ConfigKeys.GEMINI_API_KEY;
            case ModelDefaults.PROVIDER_CEREBRAS -> ConfigKeys.CEREBRAS_API_KEY;
            case ModelDefaults.PROVIDER_NVIDIA -> ConfigKeys.NVIDIA_API_KEY;
            case ModelDefaults.PROVIDER_SARVAM -> ConfigKeys.SARVAM_API_KEY;
            case ModelDefaults.PROVIDER_OPENCODE -> ConfigKeys.OPENCODE_API_KEY;
            case ModelDefaults.PROVIDER_HUGGINGFACE -> ConfigKeys.HUGGINGFACE_API_KEY;
            case ModelDefaults.PROVIDER_MISTRAL -> ConfigKeys.MISTRAL_API_KEY;
            case ModelDefaults.PROVIDER_CLOUDFLARE -> ConfigKeys.CLOUDFLARE_API_KEY;
            case ModelDefaults.PROVIDER_COHERE -> ConfigKeys.COHERE_API_KEY;
            case ModelDefaults.PROVIDER_CODEX -> ConfigKeys.CODEX_API_KEY;
            case ModelDefaults.PROVIDER_OMLX -> ConfigKeys.OMLX_API_KEY;
            case ModelDefaults.PROVIDER_GENERIC_OPENAI -> ConfigKeys.GENERIC_OPENAI_API_KEY;
            case ModelDefaults.PROVIDER_GROQ -> ConfigKeys.GROQ_API_KEY;
            default -> null;
        };
    }

    static String compactionApiKeyForProvider(String provider) {
        if (ProviderKeySpecs.isCompactionClassifierKeyless(provider)) return null;
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case ModelDefaults.PROVIDER_OPENROUTER -> ConfigKeys.COMPACTION_OPENROUTER_API_KEY;
            case ModelDefaults.PROVIDER_OPENAI -> ConfigKeys.COMPACTION_OPENAI_API_KEY;
            case ModelDefaults.PROVIDER_GEMINI -> ConfigKeys.COMPACTION_GEMINI_API_KEY;
            case ModelDefaults.PROVIDER_CEREBRAS -> ConfigKeys.COMPACTION_CEREBRAS_API_KEY;
            case ModelDefaults.PROVIDER_NVIDIA -> ConfigKeys.COMPACTION_NVIDIA_API_KEY;
            case ModelDefaults.PROVIDER_SARVAM -> ConfigKeys.COMPACTION_SARVAM_API_KEY;
            case ModelDefaults.PROVIDER_OPENCODE -> ConfigKeys.COMPACTION_OPENCODE_API_KEY;
            case ModelDefaults.PROVIDER_HUGGINGFACE -> ConfigKeys.COMPACTION_HUGGINGFACE_API_KEY;
            case ModelDefaults.PROVIDER_MISTRAL -> ConfigKeys.COMPACTION_MISTRAL_API_KEY;
            case ModelDefaults.PROVIDER_CLOUDFLARE -> ConfigKeys.COMPACTION_CLOUDFLARE_API_KEY;
            case ModelDefaults.PROVIDER_COHERE -> ConfigKeys.COMPACTION_COHERE_API_KEY;
            case ModelDefaults.PROVIDER_GENERIC_OPENAI -> ConfigKeys.COMPACTION_GENERIC_OPENAI_API_KEY;
            case ModelDefaults.PROVIDER_GROQ -> ConfigKeys.COMPACTION_GROQ_API_KEY;
            default -> null;
        };
    }

    static String classifierApiKeyForProvider(String provider) {
        if (ProviderKeySpecs.isCompactionClassifierKeyless(provider)) return null;
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case ModelDefaults.PROVIDER_OPENROUTER -> ConfigKeys.CLASSIFIER_OPENROUTER_API_KEY;
            case ModelDefaults.PROVIDER_OPENAI -> ConfigKeys.CLASSIFIER_OPENAI_API_KEY;
            case ModelDefaults.PROVIDER_GEMINI -> ConfigKeys.CLASSIFIER_GEMINI_API_KEY;
            case ModelDefaults.PROVIDER_CEREBRAS -> ConfigKeys.CLASSIFIER_CEREBRAS_API_KEY;
            case ModelDefaults.PROVIDER_NVIDIA -> ConfigKeys.CLASSIFIER_NVIDIA_API_KEY;
            case ModelDefaults.PROVIDER_SARVAM -> ConfigKeys.CLASSIFIER_SARVAM_API_KEY;
            case ModelDefaults.PROVIDER_OPENCODE -> ConfigKeys.CLASSIFIER_OPENCODE_API_KEY;
            case ModelDefaults.PROVIDER_HUGGINGFACE -> ConfigKeys.CLASSIFIER_HUGGINGFACE_API_KEY;
            case ModelDefaults.PROVIDER_MISTRAL -> ConfigKeys.CLASSIFIER_MISTRAL_API_KEY;
            case ModelDefaults.PROVIDER_CLOUDFLARE -> ConfigKeys.CLASSIFIER_CLOUDFLARE_API_KEY;
            case ModelDefaults.PROVIDER_COHERE -> ConfigKeys.CLASSIFIER_COHERE_API_KEY;
            case ModelDefaults.PROVIDER_GENERIC_OPENAI -> ConfigKeys.CLASSIFIER_GENERIC_OPENAI_API_KEY;
            case ModelDefaults.PROVIDER_GROQ -> ConfigKeys.CLASSIFIER_GROQ_API_KEY;
            default -> null;
        };
    }
}

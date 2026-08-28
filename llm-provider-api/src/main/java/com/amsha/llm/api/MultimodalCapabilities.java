package com.amsha.llm.api;

import dev.langchain4j.model.chat.ChatModel;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.amsha.channel.api.AttachmentType;

/**
 * Model-specific multimodal capability resolution and composite intersection.
 * Unknown provider/model combinations fail closed to {@link MultimodalCapability#TEXT_ONLY}.
 */
public final class MultimodalCapabilities {
    public static final Set<String> STANDARD_IMAGE_MIMES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");
    public static final Set<String> STANDARD_AUDIO_MIMES = Set.of("audio/mpeg", "audio/wav", "audio/ogg");
    public static final Set<String> STANDARD_PDF_MIMES = Set.of("application/pdf");

    private static final long SIZE_10_MB = 10L * 1024L * 1024L;
    private static final long SIZE_20_MB = 20L * 1024L * 1024L;
    private static final long SIZE_25_MB = 25L * 1024L * 1024L;
    private static final Set<String> OPEN_WEIGHT_PROVIDERS =
            Set.of("generic-openai", "huggingface", "llamacpp", "mistral", "nvidia", "ollama", "openrouter");
    private static final Set<String> OPENROUTER_GEMINI_VENDORS = Set.of("google", "google-ai");
    private static final Set<String> OPENROUTER_ANTHROPIC_VENDORS = Set.of("anthropic");
    private static final Set<String> OPENROUTER_OPENAI_VENDORS = Set.of("openai");
    private static final Set<String> OPENROUTER_OPEN_WEIGHT_VENDORS =
            Set.of("meta-llama", "mistralai", "qwen", "microsoft", "google", "nousresearch");

    private MultimodalCapabilities() {}

    /** Resolves the capability declared by a runtime model, failing closed when absent. */
    public static MultimodalCapability resolveCapability(ChatModel model) {
        if (model instanceof MultimodalCapable capable) {
            MultimodalCapability capability = capable.multimodalCapability();
            return capability != null ? capability : MultimodalCapability.TEXT_ONLY;
        }
        return MultimodalCapability.TEXT_ONLY;
    }

    /** Computes the safe intersection across candidates in a composite/failover wrapper. */
    public static MultimodalCapability intersect(Collection<MultimodalCapability> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return MultimodalCapability.TEXT_ONLY;
        }

        Set<AttachmentType> commonTypes = null;
        Set<String> commonMimes = null;
        long minFileSize = 0L;
        int minMediaCount = 0;
        boolean supportsBase64 = true;
        boolean supportsUrls = true;
        for (MultimodalCapability capability : capabilities) {
            if (capability == null || !capability.isMultimodal()) {
                return MultimodalCapability.TEXT_ONLY;
            }
            if (commonTypes == null) {
                commonTypes = new HashSet<>(capability.supportedTypes());
                commonMimes = new HashSet<>(capability.supportedMimeTypes());
            } else {
                commonTypes.retainAll(capability.supportedTypes());
                commonMimes.retainAll(capability.supportedMimeTypes());
            }
            minFileSize = intersectBound(minFileSize, capability.maxFileSize());
            minMediaCount = (int) intersectBound(minMediaCount, capability.maxMediaPerRequest());
            supportsBase64 = supportsBase64 && capability.supportsInlineBase64();
            supportsUrls = supportsUrls && capability.supportsMediaUrls();
        }

        if (commonTypes == null || commonTypes.isEmpty() || commonMimes.isEmpty()) {
            return MultimodalCapability.TEXT_ONLY;
        }
        return new MultimodalCapability(
                commonTypes, commonMimes, minFileSize, minMediaCount, supportsBase64, supportsUrls);
    }

    private static long intersectBound(long current, long candidate) {
        if (current <= 0L) {
            return candidate;
        }
        if (candidate <= 0L) {
            return current;
        }
        return Math.min(current, candidate);
    }

    /**
     * Resolves only explicit, known model families. Provider names are part of the allowlist so a
     * custom endpoint cannot gain multimodal admission merely by choosing a familiar model label.
     * OpenRouter's vendor/model form is parsed deliberately rather than by substring matching.
     */
    public static MultimodalCapability defaultCapabilityForModel(String provider, String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return MultimodalCapability.TEXT_ONLY;
        }

        String model = modelName.trim().toLowerCase(Locale.ROOT);
        String providerName = provider != null ? provider.trim().toLowerCase(Locale.ROOT) : "";
        String vendor = namespace(model);
        String family = modelFamily(model);

        if (isGeminiMultimodal(providerName, model, family, vendor)) {
            return geminiMultimodalCapability();
        }
        if (isAnthropicMultimodal(providerName, model, family, vendor)) {
            return anthropicMultimodalCapability();
        }
        if (isOpenAiVision(providerName, family, vendor)) {
            return openAiVisionCapability();
        }
        if (isOpenWeightVision(providerName, model, family, vendor)) {
            return openWeightVisionCapability();
        }
        return MultimodalCapability.TEXT_ONLY;
    }

    private static boolean isGeminiMultimodal(String provider, String model, String family, String vendor) {
        boolean knownFamily = family.startsWith("gemini-1.5")
                || family.startsWith("gemini-2.0")
                || family.startsWith("gemini-2.5")
                || family.startsWith("gemini-3");
        if (!knownFamily) {
            return false;
        }
        return "gemini".equals(provider)
                || (!model.contains("/") && provider.isEmpty())
                || ("openrouter".equals(provider) && OPENROUTER_GEMINI_VENDORS.contains(vendor));
    }

    private static boolean isAnthropicMultimodal(String provider, String model, String family, String vendor) {
        boolean knownFamily = family.startsWith("claude-3-") || family.startsWith("claude-3.");
        if (!knownFamily) {
            return false;
        }
        return "anthropic".equals(provider)
                || (!model.contains("/") && provider.isEmpty())
                || ("openrouter".equals(provider) && OPENROUTER_ANTHROPIC_VENDORS.contains(vendor));
    }

    private static boolean isOpenAiVision(String provider, String family, String vendor) {
        boolean knownFamily = family.startsWith("gpt-4o")
                || family.startsWith("chatgpt-4o")
                || family.startsWith("gpt-4-turbo")
                || family.startsWith("gpt-4-vision")
                || "o1".equals(family)
                || family.startsWith("o1-");
        if (!knownFamily) {
            return false;
        }
        return "openai".equals(provider)
                || "generic-openai".equals(provider)
                || ("openrouter".equals(provider) && OPENROUTER_OPENAI_VENDORS.contains(vendor));
    }

    private static boolean isOpenWeightVision(String provider, String model, String family, String vendor) {
        if (!OPEN_WEIGHT_PROVIDERS.contains(provider)) {
            return false;
        }
        if ("openrouter".equals(provider) && model.contains("/") && !OPENROUTER_OPEN_WEIGHT_VENDORS.contains(vendor)) {
            return false;
        }
        return family.startsWith("llava")
                || family.startsWith("bakllava")
                || family.startsWith("moondream")
                || family.startsWith("pixtral")
                || family.startsWith("qwen-vl")
                || family.startsWith("qwen2-vl")
                || family.startsWith("minicpm-v")
                || family.startsWith("llama-3.2-11b-vision")
                || family.startsWith("llama-3.2-90b-vision");
    }

    private static String namespace(String model) {
        int slash = model.indexOf('/');
        return slash > 0 ? model.substring(0, slash) : "";
    }

    private static String modelFamily(String model) {
        int slash = model.indexOf('/');
        return slash > 0 && slash + 1 < model.length() ? model.substring(slash + 1) : model;
    }

    private static MultimodalCapability geminiMultimodalCapability() {
        Set<String> allMimes = new HashSet<>();
        allMimes.addAll(STANDARD_IMAGE_MIMES);
        allMimes.addAll(STANDARD_AUDIO_MIMES);
        allMimes.addAll(STANDARD_PDF_MIMES);
        return new MultimodalCapability(
                Set.of(AttachmentType.IMAGE, AttachmentType.AUDIO, AttachmentType.DOCUMENT_PDF),
                allMimes,
                SIZE_25_MB,
                16,
                true,
                true);
    }

    private static MultimodalCapability anthropicMultimodalCapability() {
        Set<String> mimes = new HashSet<>();
        mimes.addAll(STANDARD_IMAGE_MIMES);
        mimes.addAll(STANDARD_PDF_MIMES);
        return new MultimodalCapability(
                Set.of(AttachmentType.IMAGE, AttachmentType.DOCUMENT_PDF), mimes, SIZE_25_MB, 10, true, true);
    }

    private static MultimodalCapability openAiVisionCapability() {
        return new MultimodalCapability(Set.of(AttachmentType.IMAGE), STANDARD_IMAGE_MIMES, SIZE_20_MB, 10, true, true);
    }

    private static MultimodalCapability openWeightVisionCapability() {
        return new MultimodalCapability(Set.of(AttachmentType.IMAGE), STANDARD_IMAGE_MIMES, SIZE_10_MB, 5, true, true);
    }
}

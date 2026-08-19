package com.amsha.core.routing;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.amsha.core.agent.reasoning.StructuredOutputMode;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.LaunchMode;
import io.smallrye.common.annotation.Identifier;

/**
 * Produces an {@link InfiniteLoopChatModel} for compaction with slot rotation
 * across multiple providers. Each provider in the list gets its own slot with
 * independent health tracking.
 *
 * <p>Per-provider config keys (uppercase in env vars):
 * <pre>
 * AMSHA_COMPACTION_{PROVIDER}_API_KEY   → amsha.compaction.{provider}.api-key
 * AMSHA_COMPACTION_{PROVIDER}_MODEL     → amsha.compaction.{provider}.model
 * AMSHA_COMPACTION_{PROVIDER}_BASE_URL  → amsha.compaction.{provider}.base-url
 * AMSHA_COMPACTION_{PROVIDER}_MAX_RETRIES → amsha.compaction.{provider}.max-retries
 * </pre>
 *
 * <p>Example: {@code AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS=groq,ollama}
 * requires {@code AMSHA_COMPACTION_GROQ_*} and {@code AMSHA_COMPACTION_OLLAMA_*} config.
 */
@ApplicationScoped
@Unremovable
public class InfiniteLoopCompactionChatModelProvider {

    private static final Logger log = Logger.getLogger(InfiniteLoopCompactionChatModelProvider.class);
    private static final String CONFIG_PREFIX = "amsha.compaction.";

    @ConfigProperty(name = "amsha.infinite-loop.compaction.providers", defaultValue = "groq,ollama")
    String loopProviders;

    @ConfigProperty(name = "amsha.infinite-loop.cooldown-base-seconds", defaultValue = "30")
    int cooldownBaseSeconds;

    @ConfigProperty(name = "amsha.infinite-loop.cooldown-max-seconds", defaultValue = "300")
    int cooldownMaxSeconds;

    @ConfigProperty(name = "amsha.infinite-loop.cooldown-transient-seconds", defaultValue = "5")
    int cooldownTransientSeconds;

    @ConfigProperty(name = "amsha.memory.compaction.max-output-tokens", defaultValue = "1024")
    int maxTokens;

    @Inject
    LlmModelFactory modelFactory;

    private volatile InfiniteLoopChatModel instance;

    @Produces
    @ApplicationScoped
    @Identifier("compaction")
    public InfiniteLoopChatModel compactionModel() {
        if (instance != null) return instance;
        synchronized (this) {
            if (instance != null) return instance;

            String[] providerNames = loopProviders.split(",", -1);
            List<InfiniteLoopChatModel.Slot> slots = new ArrayList<>();

            for (String name : providerNames) {
                String trimmed = name.trim().toLowerCase(Locale.ROOT);
                if (trimmed.isEmpty()) continue;
                try {
                    var modelAndBaseUrl = createModelForProvider(trimmed);
                    slots.add(new InfiniteLoopChatModel.Slot(
                            trimmed, modelAndBaseUrl.model(), modelAndBaseUrl.baseUrl()));
                    log.infof("Compaction loop: added slot [%s] baseUrl=%s", trimmed, modelAndBaseUrl.baseUrl());
                } catch (Exception e) {
                    log.warnf("Compaction loop: skipping slot [%s] — %s", trimmed, e.getMessage());
                }
            }

            if (slots.isEmpty()) {
                boolean isTest = LaunchMode.current() == LaunchMode.TEST;
                if (isTest && !Boolean.getBoolean("amsha.test.force-error")) {
                    log.warn(
                            "Compaction loop: No compaction providers could be initialized in test profile. Using dummy compaction model.");
                    ChatModel dummy = new ChatModel() {
                        @Override
                        public ChatResponse chat(ChatRequest request) {
                            return ChatResponse.builder()
                                    .aiMessage(AiMessage.from("Dummy compaction response"))
                                    .build();
                        }
                    };
                    return new InfiniteLoopChatModel(
                            "compaction-chat",
                            List.of(new InfiniteLoopChatModel.Slot("dummy", dummy, "unknown")),
                            cooldownBaseSeconds,
                            cooldownMaxSeconds,
                            cooldownTransientSeconds);
                }
                throw new IllegalStateException("No compaction providers configured. "
                        + "Check amsha.infinite-loop.compaction.providers (currently: " + loopProviders + ")");
            }

            log.infof(
                    "Compaction loop: %d slot(s) — %s",
                    slots.size(),
                    slots.stream().map(InfiniteLoopChatModel.Slot::name).toList());

            instance = new InfiniteLoopChatModel(
                    "compaction-chat", slots, cooldownBaseSeconds, cooldownMaxSeconds, cooldownTransientSeconds);
            return instance;
        }
    }

    private record ModelAndBaseUrl(ChatModel model, String baseUrl) {}

    private ModelAndBaseUrl createModelForProvider(String providerName) {
        var config = ConfigProvider.getConfig();
        String baseUrl = resolveRequired(config, providerName, "base-url");
        String apiKey = resolveOptional(config, providerName, "api-key", "");
        if (apiKey.isBlank()) {
            apiKey = config.getOptionalValue("amsha.llm." + providerName + ".api-key", String.class)
                    .or(() -> Optional.ofNullable(System.getProperty(
                            "AMSHA_" + providerName.toUpperCase().replace('-', '_') + "_API_KEY")))
                    .or(() -> Optional.ofNullable(
                            System.getenv("AMSHA_" + providerName.toUpperCase().replace('-', '_') + "_API_KEY")))
                    .orElse("");
        }
        String model = resolveRequired(config, providerName, "model");
        int retries = resolveOptionalInt(config, providerName, "max-retries", 3);
        String accountId = resolveOptional(config, providerName, "account-id", "");
        if (accountId.isBlank()) {
            accountId = config.getOptionalValue("amsha.llm." + providerName + ".account-id", String.class)
                    .or(() -> Optional.ofNullable(System.getProperty(
                            "AMSHA_" + providerName.toUpperCase().replace('-', '_') + "_ACCOUNT_ID")))
                    .or(() -> Optional.ofNullable(
                            System.getenv("AMSHA_" + providerName.toUpperCase().replace('-', '_') + "_ACCOUNT_ID")))
                    .orElse("");
        }
        if (baseUrl.contains("{account_id}") && !accountId.isBlank()) {
            String cleanAccId = sanitizeAccountId(accountId);
            baseUrl = baseUrl.replace("{account_id}", cleanAccId);
        }
        baseUrl = normalizeBaseUrl(baseUrl);

        StructuredOutputMode mode = resolveStructuredOutputMode(config, providerName);

        return new ModelAndBaseUrl(
                modelFactory.createModel(
                        providerName,
                        baseUrl,
                        apiKey,
                        model,
                        maxTokens,
                        Duration.ofSeconds(10),
                        retries,
                        baseUrl,
                        model,
                        mode,
                        true),
                baseUrl);
    }

    private static StructuredOutputMode resolveStructuredOutputMode(Config config, String providerName) {
        String modeStr = config.getOptionalValue(CONFIG_PREFIX + providerName + ".structured-output-mode", String.class)
                .or(() -> config.getOptionalValue("amsha.compaction.structured-output-mode", String.class))
                .or(() -> Optional.ofNullable(System.getProperty("AMSHA_COMPACTION_STRUCTURED_OUTPUT_MODE")))
                .or(() -> Optional.ofNullable(System.getenv("AMSHA_COMPACTION_STRUCTURED_OUTPUT_MODE")))
                .orElse("PROMPT_DIRECTIVE");
        try {
            return StructuredOutputMode.valueOf(modeStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException _) {
            return StructuredOutputMode.PROMPT_DIRECTIVE;
        }
    }

    private static String sanitizeAccountId(String rawAccId) {
        if (rawAccId == null || rawAccId.isBlank()) return "";
        String clean = rawAccId.trim();
        if (clean.contains("accounts/")) {
            clean = clean.substring(clean.indexOf("accounts/") + "accounts/".length());
        }
        if (clean.contains("/")) {
            clean = clean.substring(0, clean.indexOf("/"));
        }
        return clean.trim();
    }

    private static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) return url;
        String normalized = url.trim();
        // Defense for MP Config "${ENV:…/{account_id}/ai/v1}" early-'}' corruption.
        while (normalized.endsWith("}")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        while (normalized.contains("/ai/v1/ai/v1")) {
            normalized = normalized.replace("/ai/v1/ai/v1", "/ai/v1");
        }
        while (normalized.contains("/v1/v1")) {
            normalized = normalized.replace("/v1/v1", "/v1");
        }
        if (normalized.indexOf('}') >= 0) {
            throw new IllegalStateException("Cloudflare Workers AI base URL contains illegal '}': " + normalized);
        }
        return normalized;
    }

    private static String resolveRequired(Config config, String provider, String key) {
        String prop = CONFIG_PREFIX + provider + "." + key;
        return config.getOptionalValue(prop, String.class)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing required config: " + prop + " (env: AMSHA_COMPACTION_" + provider.toUpperCase() + "_"
                                + key.replace("-", "_").toUpperCase() + ")"));
    }

    private static String resolveOptional(Config config, String provider, String key, String defaultValue) {
        return config.getOptionalValue(CONFIG_PREFIX + provider + "." + key, String.class)
                .orElse(defaultValue);
    }

    private static int resolveOptionalInt(Config config, String provider, String key, int defaultValue) {
        return config.getOptionalValue(CONFIG_PREFIX + provider + "." + key, Integer.class)
                .orElse(defaultValue);
    }
}

package com.arcclaw.core.routing;

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

import com.arcclaw.core.agent.reasoning.StructuredOutputMode;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;
import io.smallrye.common.annotation.Identifier;

/**
 * Produces an {@link InfiniteLoopChatModel} for compaction with slot rotation
 * across multiple providers. Each provider in the list gets its own slot with
 * independent health tracking.
 *
 * <p>Per-provider config keys (uppercase in env vars):
 * <pre>
 * ARCCLAW_COMPACTION_{PROVIDER}_API_KEY   → arcclaw.compaction.{provider}.api-key
 * ARCCLAW_COMPACTION_{PROVIDER}_MODEL     → arcclaw.compaction.{provider}.model
 * ARCCLAW_COMPACTION_{PROVIDER}_BASE_URL  → arcclaw.compaction.{provider}.base-url
 * ARCCLAW_COMPACTION_{PROVIDER}_MAX_RETRIES → arcclaw.compaction.{provider}.max-retries
 * </pre>
 *
 * <p>Example: {@code ARCCLAW_INFINITE_LOOP_COMPACTION_PROVIDERS=groq,ollama}
 * requires {@code ARCCLAW_COMPACTION_GROQ_*} and {@code ARCCLAW_COMPACTION_OLLAMA_*} config.
 */
@ApplicationScoped
@Unremovable
public class InfiniteLoopCompactionChatModelProvider {

    private static final Logger log = Logger.getLogger(InfiniteLoopCompactionChatModelProvider.class);
    private static final String CONFIG_PREFIX = "arcclaw.compaction.";

    @ConfigProperty(name = "arcclaw.infinite-loop.compaction.providers", defaultValue = "groq,ollama")
    String loopProviders;

    @ConfigProperty(name = "arcclaw.infinite-loop.cooldown-base-seconds", defaultValue = "30")
    int cooldownBaseSeconds;

    @ConfigProperty(name = "arcclaw.infinite-loop.cooldown-max-seconds", defaultValue = "300")
    int cooldownMaxSeconds;

    @ConfigProperty(name = "arcclaw.infinite-loop.cooldown-transient-seconds", defaultValue = "5")
    int cooldownTransientSeconds;

    @ConfigProperty(name = "arcclaw.memory.compaction.max-output-tokens", defaultValue = "1024")
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
                    ChatModel model = createModelForProvider(trimmed);
                    slots.add(new InfiniteLoopChatModel.Slot(trimmed, model));
                    log.infof("Compaction loop: added slot [%s]", trimmed);
                } catch (Exception e) {
                    log.warnf("Compaction loop: skipping slot [%s] — %s", trimmed, e.getMessage());
                }
            }

            if (slots.isEmpty()) {
                if (ConfigProvider.getConfig()
                        .getOptionalValue("quarkus.profile", String.class)
                        .orElse("prod")
                        .equals("test")) {
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
                            List.of(new InfiniteLoopChatModel.Slot("dummy", dummy)),
                            cooldownBaseSeconds,
                            cooldownMaxSeconds,
                            cooldownTransientSeconds);
                }
                throw new IllegalStateException("No compaction providers configured. "
                        + "Check arcclaw.infinite-loop.compaction.providers (currently: " + loopProviders + ")");
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

    private ChatModel createModelForProvider(String providerName) {
        var config = ConfigProvider.getConfig();
        String baseUrl = resolveRequired(config, providerName, "base-url");
        String apiKey = resolveOptional(config, providerName, "api-key", "");
        String model = resolveRequired(config, providerName, "model");
        int retries = resolveOptionalInt(config, providerName, "max-retries", 3);

        return modelFactory.createModel(
                providerName,
                baseUrl,
                apiKey,
                model,
                maxTokens,
                Duration.ofSeconds(10),
                retries,
                baseUrl,
                model,
                StructuredOutputMode.NATIVE,
                true);
    }

    private static String resolveRequired(Config config, String provider, String key) {
        String prop = CONFIG_PREFIX + provider + "." + key;
        return config.getOptionalValue(prop, String.class)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing required config: " + prop + " (env: ARCCLAW_COMPACTION_" + provider.toUpperCase() + "_"
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

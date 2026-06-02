package com.arcclaw.core.routing;

import dev.langchain4j.model.chat.ChatModel;

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
 * Produces an {@link InfiniteLoopChatModel} for intent classification with slot rotation
 * across multiple providers. Each provider in the list gets its own slot with
 * independent health tracking.
 *
 * <p>Per-provider config keys (uppercase in env vars):
 * <pre>
 * ARCCLAW_CLASSIFIER_{PROVIDER}_API_KEY    → arcclaw.classifier.{provider}.api-key
 * ARCCLAW_CLASSIFIER_{PROVIDER}_MODEL      → arcclaw.classifier.{provider}.model
 * ARCCLAW_CLASSIFIER_{PROVIDER}_BASE_URL   → arcclaw.classifier.{provider}.base-url
 * ARCCLAW_CLASSIFIER_{PROVIDER}_MAX_RETRIES → arcclaw.classifier.{provider}.max-retries
 * </pre>
 */
@ApplicationScoped
@Unremovable
public class InfiniteLoopIntentChatModelProvider {

    private static final Logger log = Logger.getLogger(InfiniteLoopIntentChatModelProvider.class);
    private static final String CONFIG_PREFIX = "arcclaw.classifier.";

    @ConfigProperty(name = "arcclaw.infinite-loop.classifier.providers", defaultValue = "groq,ollama")
    String loopProviders;

    @ConfigProperty(name = "arcclaw.infinite-loop.cooldown-base-seconds", defaultValue = "30")
    int cooldownBaseSeconds;

    @ConfigProperty(name = "arcclaw.infinite-loop.cooldown-max-seconds", defaultValue = "300")
    int cooldownMaxSeconds;

    @ConfigProperty(name = "arcclaw.infinite-loop.cooldown-transient-seconds", defaultValue = "5")
    int cooldownTransientSeconds;

    @Inject
    LlmModelFactory modelFactory;

    private volatile InfiniteLoopChatModel instance;

    @Produces
    @ApplicationScoped
    @Identifier("intent-classifier")
    public InfiniteLoopChatModel intentClassifierModel() {
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
                    log.infof("Classifier loop: added slot [%s]", trimmed);
                } catch (Exception e) {
                    log.warnf("Classifier loop: skipping slot [%s] — %s", trimmed, e.getMessage());
                }
            }

            if (slots.isEmpty()) {
                throw new IllegalStateException("No classifier providers configured. "
                        + "Check arcclaw.infinite-loop.classifier.providers (currently: " + loopProviders + ")");
            }

            log.infof(
                    "Classifier loop: %d slot(s) — %s",
                    slots.size(),
                    slots.stream().map(InfiniteLoopChatModel.Slot::name).toList());

            instance =
                    new InfiniteLoopChatModel(slots, cooldownBaseSeconds, cooldownMaxSeconds, cooldownTransientSeconds);
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
                null,
                Duration.ofSeconds(5),
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
                        "Missing required config: " + prop + " (env: ARCCLAW_CLASSIFIER_" + provider.toUpperCase() + "_"
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

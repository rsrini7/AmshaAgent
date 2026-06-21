package com.arcclaw.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import com.arcclaw.core.routing.InfiniteLoopProvider;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Startup;

/**
 * Produces an {@link InfiniteLoopEmbeddingModel} with slot rotation across
 * multiple embedding providers. Each provider in the list gets its own slot
 * with independent health tracking.
 *
 * <p>Per-provider config keys (uppercase in env vars):
 * <pre>
 * ARCCLAW_EMBEDDING_{PROVIDER}_API_KEY    → arcclaw.embedding.{provider}.api-key
 * ARCCLAW_EMBEDDING_{PROVIDER}_MODEL      → arcclaw.embedding.{provider}.model
 * ARCCLAW_EMBEDDING_{PROVIDER}_BASE_URL   → arcclaw.embedding.{provider}.base-url
 * ARCCLAW_EMBEDDING_{PROVIDER}_TIMEOUT    → arcclaw.embedding.{provider}.timeout-seconds
 * </pre>
 */
@ApplicationScoped
public class InfiniteLoopEmbeddingModelProvider {

    private static final Logger log = Logger.getLogger(InfiniteLoopEmbeddingModelProvider.class);
    private static final String CONFIG_PREFIX = "arcclaw.embedding.";

    @Inject
    @Any
    Instance<EmbeddingProviderFactory> factories;

    @ConfigProperty(name = "arcclaw.infinite-loop.embedding.providers", defaultValue = "ollama")
    String loopProviders;

    @ConfigProperty(name = "arcclaw.infinite-loop.cooldown-base-seconds", defaultValue = "30")
    int cooldownBaseSeconds;

    @ConfigProperty(name = "arcclaw.infinite-loop.cooldown-max-seconds", defaultValue = "300")
    int cooldownMaxSeconds;

    @ConfigProperty(name = "arcclaw.infinite-loop.cooldown-transient-seconds", defaultValue = "5")
    int cooldownTransientSeconds;

    @ConfigProperty(name = "arcclaw.infinite-loop.max-latency-ms", defaultValue = "60000")
    long maxLatencyMs;

    private volatile InfiniteLoopEmbeddingModel instance;

    @Produces
    @ApplicationScoped
    @Startup
    public EmbeddingModel embeddingModel() {
        if (instance != null) return instance;
        synchronized (this) {
            if (instance != null) return instance;

            String[] providerNames = loopProviders.split(",", -1);
            List<InfiniteLoopProvider.Slot<EmbeddingModel>> slots = new ArrayList<>();
            List<EmbeddingProviderFactory> allFactories = factories.stream().toList();

            for (String name : providerNames) {
                String trimmed = name.trim().toLowerCase(Locale.ROOT);
                if (trimmed.isEmpty()) continue;
                try {
                    EmbeddingModel model = createModelForProvider(trimmed, allFactories);
                    slots.add(new InfiniteLoopProvider.Slot<>(trimmed, model));
                    log.infof("Embedding loop: added slot [%s]", trimmed);
                } catch (Exception e) {
                    log.warnf("Embedding loop: skipping slot [%s] — %s", trimmed, e.getMessage());
                }
            }

            if (slots.isEmpty()) {
                boolean isTest = LaunchMode.current() == LaunchMode.TEST;
                if (isTest && !Boolean.getBoolean("arcclaw.test.force-error")) {
                    log.warn(
                            "Embedding loop: No embedding slots could be initialized in test profile. Using dummy embedding model.");
                    return new EmbeddingModel() {
                        @Override
                        public Response<Embedding> embed(String text) {
                            return Response.from(new Embedding(new float[768]));
                        }

                        @Override
                        public Response<Embedding> embed(TextSegment textSegment) {
                            return Response.from(new Embedding(new float[768]));
                        }

                        @Override
                        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
                            return Response.from(textSegments.stream()
                                    .map(s -> new Embedding(new float[768]))
                                    .toList());
                        }
                    };
                }
                throw new IllegalStateException("No embedding providers configured. "
                        + "Check arcclaw.infinite-loop.embedding.providers (currently: " + loopProviders + ")");
            }

            log.infof(
                    "Embedding loop: %d slot(s) — %s",
                    slots.size(),
                    slots.stream().map(InfiniteLoopProvider.Slot::name).toList());

            instance = new InfiniteLoopEmbeddingModel(
                    slots, cooldownBaseSeconds, cooldownMaxSeconds, cooldownTransientSeconds, maxLatencyMs);
            return instance;
        }
    }

    private EmbeddingModel createModelForProvider(String providerName, List<EmbeddingProviderFactory> allFactories) {
        EmbeddingProviderFactory factory = allFactories.stream()
                .filter(f -> f.providerId().equals(providerName))
                .findFirst()
                .orElseThrow(() ->
                        new IllegalArgumentException("Unknown embedding provider '" + providerName + "'. Available: "
                                + allFactories.stream()
                                        .map(EmbeddingProviderFactory::providerId)
                                        .collect(Collectors.joining(", "))));

        var config = ConfigProvider.getConfig();
        String model = resolveRequired(config, providerName, "model");
        String baseUrl = resolveOptional(config, providerName, "base-url", factory.defaultBaseUrl());
        String apiKey = resolveOptional(config, providerName, "api-key", "");
        int timeout = resolveOptionalInt(config, providerName, "timeout-seconds", 30);
        Optional<String> optKey = Optional.ofNullable(apiKey).filter(k -> !k.isBlank());

        EmbeddingModelConfig embedConfig =
                new EmbeddingModelConfig(providerName, model, baseUrl, optKey, Duration.ofSeconds(timeout));
        return factory.create(embedConfig);
    }

    private static String resolveRequired(Config config, String provider, String key) {
        String prop = CONFIG_PREFIX + provider + "." + key;
        return config.getOptionalValue(prop, String.class)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing required config: " + prop + " (env: ARCCLAW_EMBEDDING_" + provider.toUpperCase() + "_"
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

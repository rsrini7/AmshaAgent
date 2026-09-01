package com.amsha.rag;

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

import com.amsha.common.util.LogUtil;
import com.amsha.core.routing.InfiniteLoopProvider;
import com.amsha.persistence.api.EmbeddingIndexDescriptor;
import com.amsha.persistence.api.EmbeddingIndexDescriptorSource;

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
 * AMSHA_EMBEDDING_{PROVIDER}_API_KEY    → amsha.embedding.{provider}.api-key
 * AMSHA_EMBEDDING_{PROVIDER}_MODEL      → amsha.embedding.{provider}.model
 * AMSHA_EMBEDDING_{PROVIDER}_BASE_URL   → amsha.embedding.{provider}.base-url
 * AMSHA_EMBEDDING_{PROVIDER}_TIMEOUT    → amsha.embedding.{provider}.timeout-seconds
 * </pre>
 */
@ApplicationScoped
public class InfiniteLoopEmbeddingModelProvider implements EmbeddingIndexDescriptorSource {

    private static final Logger log = Logger.getLogger(InfiniteLoopEmbeddingModelProvider.class);
    private static final String CONFIG_PREFIX = "amsha.embedding.";

    @Inject
    @Any
    Instance<EmbeddingProviderFactory> factories;

    @ConfigProperty(name = "amsha.infinite-loop.embedding.providers", defaultValue = "ollama")
    String loopProviders;

    @ConfigProperty(name = "amsha.infinite-loop.cooldown-base-seconds", defaultValue = "30")
    int cooldownBaseSeconds;

    @ConfigProperty(name = "amsha.infinite-loop.cooldown-max-seconds", defaultValue = "300")
    int cooldownMaxSeconds;

    @ConfigProperty(name = "amsha.infinite-loop.cooldown-transient-seconds", defaultValue = "5")
    int cooldownTransientSeconds;

    @ConfigProperty(name = "amsha.infinite-loop.max-latency-ms", defaultValue = "60000")
    long maxLatencyMs;

    @ConfigProperty(name = "amsha.rag.embed-dimensions", defaultValue = "768")
    int configuredVectorDimension;

    private volatile InfiniteLoopEmbeddingModel instance;
    private volatile EmbeddingIndexDescriptor activeIndexDescriptor;

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
            EmbeddingSpaceDescriptor configuredSpace = null;

            for (String name : providerNames) {
                String trimmed = name.trim().toLowerCase(Locale.ROOT);
                if (trimmed.isEmpty()) continue;
                try {
                    ConfiguredEmbeddingModel configured = createModelForProvider(trimmed, allFactories);
                    if (configuredSpace == null) {
                        configuredSpace = configured.space();
                    } else if (!configuredSpace.isCompatibleWith(configured.space())) {
                        log.warnf(
                                "Embedding loop: skipping incompatible slot [%s] space=%s; expected fingerprint=%s",
                                trimmed, configured.space().spaceId(), configuredSpace.fingerprint());
                        continue;
                    }
                    slots.add(new InfiniteLoopProvider.Slot<>(trimmed, configured.model()));
                    log.infof(
                            "Embedding loop: added slot [%s] space=%s",
                            trimmed, configured.space().spaceId());
                } catch (Exception e) {
                    log.warnf("Embedding loop: skipping slot [%s] — %s", trimmed, LogUtil.rootMessage(e));
                }
            }

            if (slots.isEmpty()) {
                boolean isTest = LaunchMode.current() == LaunchMode.TEST;
                if (isTest && !Boolean.getBoolean("amsha.test.force-error")) {
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
                throw new IllegalStateException("No embedding providers configured. Check mpnet model is available. "
                        + "Check amsha.infinite-loop.embedding.providers (currently: " + loopProviders + ")");
            }

            log.infof(
                    "Embedding loop: %d slot(s) — %s",
                    slots.size(),
                    slots.stream().map(InfiniteLoopProvider.Slot::name).toList());

            InfiniteLoopEmbeddingModel initialized = new InfiniteLoopEmbeddingModel(
                    slots, cooldownBaseSeconds, cooldownMaxSeconds, cooldownTransientSeconds, maxLatencyMs);
            activeIndexDescriptor = toIndexDescriptor(configuredSpace);
            instance = initialized;
            return initialized;
        }
    }

    @Override
    public Optional<EmbeddingIndexDescriptor> activeDescriptor() {
        if (activeIndexDescriptor == null) embeddingModel();
        return Optional.ofNullable(activeIndexDescriptor);
    }

    private EmbeddingIndexDescriptor toIndexDescriptor(EmbeddingSpaceDescriptor space) {
        int dimension = space.vectorDimension() > 0 ? space.vectorDimension() : configuredVectorDimension;
        return EmbeddingIndexDescriptor.create(
                space.providerId(),
                space.spaceId(),
                space.modelRevision(),
                space.tokenizerRevision(),
                space.preprocessingRevision(),
                space.poolingStrategy(),
                space.l2Normalize(),
                dimension,
                space.numericPrecision(),
                space.chunkingStrategy(),
                space.chunkingRevision(),
                space.maxWindowTokens(),
                space.windowOverlapTokens(),
                space.multiWindowAggregation());
    }

    private ConfiguredEmbeddingModel createModelForProvider(
            String providerName, List<EmbeddingProviderFactory> allFactories) {
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
        String spaceId = resolveOptional(config, providerName, "space-id", "");
        String spaceRevision = resolveOptional(config, providerName, "space-revision", "");
        Optional<String> optKey = Optional.ofNullable(apiKey).filter(k -> !k.isBlank());

        EmbeddingModelConfig embedConfig = new EmbeddingModelConfig(
                providerName, model, baseUrl, optKey, Duration.ofSeconds(timeout), spaceId, spaceRevision);
        return new ConfiguredEmbeddingModel(factory.create(embedConfig), factory.embeddingSpace(embedConfig));
    }

    private static String resolveRequired(Config config, String provider, String key) {
        String prop = CONFIG_PREFIX + provider + "." + key;
        return config.getOptionalValue(prop, String.class)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing required config: " + prop + " (env: AMSHA_EMBEDDING_" + provider.toUpperCase() + "_"
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

    private record ConfiguredEmbeddingModel(EmbeddingModel model, EmbeddingSpaceDescriptor space) {}
}

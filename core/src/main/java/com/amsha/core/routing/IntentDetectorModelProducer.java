package com.amsha.core.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.time.Duration;

import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.StructuredOutputMode;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;
import io.smallrye.common.annotation.Identifier;

/**
 * Produces the {@code intent-classifier-mode} {@link StructuredOutputMode} bean
 * and the {@code intent-classifier-fallback} safety-net {@link ChatModel}.
 * <p>
 * The primary {@code intent-classifier} {@link ChatModel} bean is now produced by
 * {@link InfiniteLoopIntentChatModelProvider} with slot rotation.
 */
@ApplicationScoped
@Unremovable
public class IntentDetectorModelProducer {

    private static final Logger log = Logger.getLogger(IntentDetectorModelProducer.class);

    @ConfigProperty(name = "amsha.routing.intent-classifier.structured-output-mode", defaultValue = "NATIVE")
    String structuredOutputMode;

    // Fallback safety-net config (Ollama) — used when all infinite-loop slots are exhausted
    @ConfigProperty(name = "quarkus.langchain4j.ollama.base-url", defaultValue = ModelDefaults.DEFAULT_OLLAMA_BASE_URL)
    String ollamaBaseUrl;

    @ConfigProperty(
            name = "amsha.routing.intent-classifier.ollama-model",
            defaultValue = ModelDefaults.DEFAULT_OLLAMA_MODEL)
    String ollamaModelId;

    @Inject
    LlmModelFactory modelFactory;

    @Produces
    @Dependent
    @Identifier("intent-classifier-mode")
    public StructuredOutputMode intentDetectorMode() {
        StructuredOutputMode mode = StructuredOutputMode.valueOf(structuredOutputMode.toUpperCase());
        log.infof("IntentDetectorModelProducer intent-classifier-mode=%s", mode);
        return mode;
    }

    /**
     * Fallback safety-net model (Ollama) — used when all infinite-loop slots are exhausted.
     * The {@link com.amsha.core.routing.IntentDetectorService} tries the primary
     * {@code intent-classifier} first, then falls back to this, then to local heuristics.
     */
    @Produces
    @ApplicationScoped
    @Identifier("intent-classifier-fallback")
    public ChatModel intentDetectorFallbackModel() {
        log.infof("IntentDetectorModelProducer intent-classifier-fallback model=%s", ollamaModelId);
        return modelFactory.createModel(
                ModelDefaults.PROVIDER_OLLAMA,
                ollamaBaseUrl,
                null,
                ollamaModelId,
                null,
                Duration.ofSeconds(5),
                1,
                ollamaBaseUrl,
                ollamaModelId,
                StructuredOutputMode.NATIVE,
                true);
    }
}

package com.arcclaw.core.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.time.Duration;

import com.arcclaw.core.agent.reasoning.StructuredOutputMode;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;
import io.smallrye.common.annotation.Identifier;

/**
 * Produces the {@code intent-classifier} {@link ChatModel} bean.
 * Reads provider config and delegates to {@link LlmModelFactory} which
 * discovers the active {@link RoutingChatModelProvider} via CDI.
 */
@ApplicationScoped
@Unremovable
public class IntentDetectorModelProducer {

    private static final Logger log = Logger.getLogger(IntentDetectorModelProducer.class);

    @ConfigProperty(name = "arcclaw.routing.intent-classifier.provider", defaultValue = "groq")
    String provider;

    @ConfigProperty(
            name = "arcclaw.routing.intent-classifier.base-url",
            defaultValue = "https://api.groq.com/openai/v1")
    String intentBaseUrl;

    @ConfigProperty(name = "arcclaw.routing.intent-classifier.api-key", defaultValue = "not-configured")
    String intentApiKey;

    @ConfigProperty(name = "arcclaw.routing.intent-classifier.model", defaultValue = "llama-3.1-8b-instant")
    String intentModelName;

    @ConfigProperty(name = "arcclaw.routing.intent-classifier.timeout-seconds", defaultValue = "5")
    int timeoutSeconds;

    @ConfigProperty(name = "arcclaw.routing.intent-classifier.ollama-model", defaultValue = "mistral-small:latest")
    String ollamaModelId;

    @ConfigProperty(name = "quarkus.langchain4j.ollama.base-url", defaultValue = "http://localhost:11434")
    String ollamaBaseUrl;

    @ConfigProperty(name = "arcclaw.routing.intent-classifier.max-retries", defaultValue = "3")
    int maxRetries;

    @ConfigProperty(name = "arcclaw.routing.intent-classifier.structured-output-mode", defaultValue = "NATIVE")
    StructuredOutputMode structuredOutputMode;

    @Inject
    LlmModelFactory modelFactory;

    @Produces
    @ApplicationScoped
    @Identifier("intent-classifier")
    public ChatModel intentDetectorModel() {
        String cleanProvider = clean(provider);
        log.infof(
                "IntentDetectorModelProducer intent-classifier provider=%s model=%s mode=%s",
                cleanProvider, intentModelName, structuredOutputMode);
        return modelFactory.createModel(
                cleanProvider,
                intentBaseUrl,
                intentApiKey,
                intentModelName,
                10,
                Duration.ofSeconds(timeoutSeconds),
                maxRetries,
                ollamaBaseUrl,
                ollamaModelId,
                structuredOutputMode,
                true);
    }

    private String clean(String v) {
        if (v == null) return null;
        return v.split("#", 2)[0].trim();
    }

    @Produces
    @Identifier("intent-classifier-mode")
    @Dependent
    @Unremovable
    public StructuredOutputMode intentDetectorMode() {
        return structuredOutputMode;
    }

    @Produces
    @ApplicationScoped
    @Identifier("intent-classifier-fallback")
    public ChatModel intentDetectorFallbackModel() {
        log.infof("IntentDetectorModelProducer intent-classifier-fallback model=%s", ollamaModelId);
        return modelFactory.createModel(
                "ollama",
                ollamaBaseUrl,
                null,
                ollamaModelId,
                10,
                Duration.ofMillis(1500),
                1,
                ollamaBaseUrl,
                ollamaModelId,
                structuredOutputMode,
                true);
    }
}

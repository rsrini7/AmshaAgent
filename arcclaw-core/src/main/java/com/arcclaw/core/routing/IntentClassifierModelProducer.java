package com.arcclaw.core.routing;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import dev.langchain4j.model.chat.ChatModel;
import io.smallrye.common.annotation.Identifier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Produces the {@code intent-classifier} {@link ChatModel} bean.
 * Reads provider config and delegates to {@link LlmModelFactory} which
 * discovers the active {@link RoutingChatModelProvider} via CDI.
 */
@ApplicationScoped
public class IntentClassifierModelProducer {

    private static final Logger log = Logger.getLogger(IntentClassifierModelProducer.class);

    @ConfigProperty(name = "arcclaw.routing.intent-classifier.provider", defaultValue = "groq")
    String provider;

    @ConfigProperty(name = "arcclaw.routing.intent-classifier.base-url",
                defaultValue = "https://api.groq.com/openai/v1")
    String intentBaseUrl;

    @ConfigProperty(name = "arcclaw.routing.intent-classifier.api-key",
                    defaultValue = "not-configured")
    String intentApiKey;

    @ConfigProperty(name = "arcclaw.routing.intent-classifier.model",
                    defaultValue = "llama-3.1-8b-instant")
    String intentModelName;

    @ConfigProperty(name = "arcclaw.routing.intent-classifier.timeout-seconds",
                    defaultValue = "5")
    int timeoutSeconds;

    @ConfigProperty(name = "arcclaw.routing.intent-classifier.ollama-model",
                    defaultValue = "llama3.2")
    String ollamaModelId;

    @ConfigProperty(name = "quarkus.langchain4j.ollama.base-url",
                    defaultValue = "http://localhost:11434")
    String ollamaBaseUrl;

    @ConfigProperty(name = "arcclaw.routing.intent-classifier.max-retries", defaultValue = "3")
    int maxRetries;

    @Inject
    LlmModelFactory modelFactory;

    @Produces
    @ApplicationScoped
    @Identifier("intent-classifier")
    public ChatModel intentClassifierModel() {
        log.infof("IntentClassifierModelProducer intent-classifier provider=%s model=%s",
                  provider, intentModelName);
        return modelFactory.createModel(
            provider, intentBaseUrl, intentApiKey,
            intentModelName, 10, Duration.ofSeconds(timeoutSeconds),
            maxRetries, ollamaBaseUrl, ollamaModelId);
    }
}
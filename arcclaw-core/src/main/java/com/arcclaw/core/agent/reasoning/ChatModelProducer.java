package com.arcclaw.core.agent.reasoning;

import java.time.Duration;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import com.arcclaw.core.routing.FallbackChatModel;
import com.arcclaw.core.routing.LlmModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Produces the single {@link ChatModel} bean used by the agent reasoning engine.
 * Selects the active {@link AgentChatModelProvider} based on
 * {@code arcclaw.llm.provider} config and delegates model creation to it.
 */
@ApplicationScoped
public class ChatModelProducer {

    private static final Logger log = Logger.getLogger(ChatModelProducer.class);

    @ConfigProperty(name = "arcclaw.llm.provider", defaultValue = "groq")
    String provider;

    @ConfigProperty(name = "quarkus.langchain4j.openai.chat-model.model-name",
                    defaultValue = "llama-3.3-70b-versatile")
    String modelName;

    @ConfigProperty(name = "arcclaw.llm.max-retries", defaultValue = "3")
    int maxRetries;

    @ConfigProperty(name = "arcclaw.llm.timeout", defaultValue = "PT60S")
    Duration timeout;

    @ConfigProperty(name = "arcclaw.llm.fallback-provider")
    Optional<String> fallbackProvider;

    @ConfigProperty(name = "arcclaw.llm.fallback-model")
    Optional<String> fallbackModel;

    @ConfigProperty(name = "arcclaw.llm.fallback-base-url")
    Optional<String> fallbackBaseUrl;

    @ConfigProperty(name = "arcclaw.llm.fallback-api-key")
    Optional<String> fallbackApiKey;

    @ConfigProperty(name = "arcclaw.llm.fallback-ollama-base-url",
                    defaultValue = "http://localhost:11434")
    String ollamaBaseUrl;

    @ConfigProperty(name = "arcclaw.llm.fallback-ollama-model",
                    defaultValue = "llama3.2")
    String ollamaModelId;

    @Inject
    @Any
    Instance<AgentChatModelProvider> providers;

    @Inject
    LlmModelFactory modelFactory;

    @Produces
    @ApplicationScoped
    public ChatModel chatModel() {
        AgentChatModelProvider providerFactory = providers.stream()
            .filter(p -> p.providerName().equalsIgnoreCase(provider))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No AgentChatModelProvider found for provider='" + provider +
                "'. Available providers: " +
                providers.stream().map(AgentChatModelProvider::providerName).toList()));

        log.infof("ChatModelProducer: using primary provider=%s", provider);
        ChatModel primary = providerFactory.create();

        String configuredFallbackModel = fallbackModel.filter(s -> !s.isBlank()).orElse("").trim();
        String configuredFallbackProvider = fallbackProvider.filter(s -> !s.isBlank()).orElse("").trim();

        // Fallback is enabled if a provider is explicitly set OR a model name is provided
        boolean hasFallback = !configuredFallbackProvider.isBlank() || !configuredFallbackModel.isBlank();

        if (hasFallback) {
            String configuredFallbackBaseUrl = fallbackBaseUrl.filter(s -> !s.isBlank()).orElse("");
            String configuredFallbackApiKey = fallbackApiKey.filter(s -> !s.isBlank()).orElse("");
            
            String fbProvider = !configuredFallbackProvider.isBlank() ? configuredFallbackProvider
                : LlmModelFactory.inferProviderFromUrl(configuredFallbackBaseUrl);
            
            String displayModel = !configuredFallbackModel.isBlank() ? configuredFallbackModel : 
                                 ("ollama".equalsIgnoreCase(fbProvider) ? ollamaModelId : "default");

            log.infof("ChatModelProducer: 🟢 Fallback ENABLED. provider=%s, model=%s", fbProvider, displayModel);

            try {
                ChatModel fallback = modelFactory.createModel(
                    fbProvider, configuredFallbackBaseUrl, configuredFallbackApiKey,
                    configuredFallbackModel, 8192, timeout, maxRetries,
                    ollamaBaseUrl, ollamaModelId);
                    
                return new FallbackChatModel(primary, fallback, modelName, displayModel);
            } catch (Exception e) {
                log.errorf("ChatModelProducer: Failed to initialize fallback model: %s. Falling back to primary ONLY.", e.getMessage());
                return primary;
            }
        }

        log.infof("ChatModelProducer: ⚪ Fallback NOT enabled (no fallback provider or model configured)");
        return primary;
    }
}

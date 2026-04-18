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
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
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

        // 🟢 DEBUG DUMP: Print all LLM-related variables seen by the app (masked)
        log.info("--- LLM Configuration Context ---");
        System.getenv().forEach((k, v) -> {
            if (k.startsWith("ARCCLAW_LLM_")) {
                String masked = v.length() > 4 ? v.substring(0, 3) + "***" : "***";
                log.infof("Env: %s = %s", k, masked);
            }
        });
        System.getProperties().forEach((k, v) -> {
            String key = String.valueOf(k);
            if (key.startsWith("arcclaw.llm.") || key.startsWith("ARCCLAW_LLM_")) {
                String val = String.valueOf(v);
                String masked = val.length() > 4 ? val.substring(0, 3) + "***" : "***";
                log.infof("SysProp: %s = %s", key, masked);
            }
        });
        log.info("--------------------------------");

        log.infof("ChatModelProducer: using primary provider=%s", provider);
        ChatModel primary = providerFactory.create();

        // 🟢 ULTIMATE FALLBACK DETECTION: Probe environment directly to bypass injection quirks
        Config config = ConfigProvider.getConfig();
        
        String fbProvider = config.getOptionalValue("ARCCLAW_LLM_FALLBACK_PROVIDER", String.class)
                .or(() -> config.getOptionalValue("arcclaw.llm.fallback-provider", String.class))
                .filter(s -> !s.isBlank())
                .orElse("").trim();
                
        String fbModel = config.getOptionalValue("ARCCLAW_LLM_FALLBACK_MODEL", String.class)
                .or(() -> config.getOptionalValue("arcclaw.llm.fallback-model", String.class))
                .filter(s -> !s.isBlank())
                .orElse("").trim();

        log.debugf("DIAGNOSTIC: resolved fallbackProvider='%s', fallbackModel='%s'", fbProvider, fbModel);

        // Fallback is enabled if a provider is explicitly set OR a model name is provided
        boolean hasFallback = !fbProvider.isBlank() || !fbModel.isBlank();

        if (hasFallback) {
            String fbBaseUrl = config.getOptionalValue("ARCCLAW_LLM_FALLBACK_BASE_URL", String.class)
                    .or(() -> config.getOptionalValue("arcclaw.llm.fallback-base-url", String.class))
                    .orElse("");
            String fbApiKey = config.getOptionalValue("ARCCLAW_LLM_FALLBACK_API_KEY", String.class)
                    .or(() -> config.getOptionalValue("arcclaw.llm.fallback-api-key", String.class))
                    .orElse("");
            
            String resolvedProvider = !fbProvider.isBlank() ? fbProvider
                : LlmModelFactory.inferProviderFromUrl(fbBaseUrl);
            
            String displayModel = !fbModel.isBlank() ? fbModel : 
                                 ("ollama".equalsIgnoreCase(resolvedProvider) ? ollamaModelId : "default");

            log.infof("ChatModelProducer: 🟢 Fallback ENABLED. provider=%s, model=%s", resolvedProvider, displayModel);

            try {
                ChatModel fallback = modelFactory.createModel(
                    resolvedProvider, fbBaseUrl, fbApiKey,
                    fbModel, 8192, timeout, maxRetries,
                    ollamaBaseUrl, ollamaModelId);
                    
                return new FallbackChatModel(primary, fallback, modelName, displayModel);
            } catch (Exception e) {
                log.errorf("ChatModelProducer: Failed to initialize fallback model: %s. Falling back to primary ONLY.", e.getMessage());
                return primary;
            }
        }

        log.infof("ChatModelProducer: ⚪ Fallback NOT enabled. Checked ARCCLAW_LLM_FALLBACK_PROVIDER and ARCCLAW_LLM_FALLBACK_MODEL.");
        return primary;
    }
}

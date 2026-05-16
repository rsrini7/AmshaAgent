package com.arcclaw.core.agent.reasoning;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import com.arcclaw.core.routing.FallbackChatModel;
import com.arcclaw.core.routing.LlmModelFactory;
import com.arcclaw.core.util.LogUtil;
import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.arc.Unremovable;
import io.smallrye.common.annotation.Identifier;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Produces the single {@link ChatModel} bean used by the agent reasoning engine.
 * Selects the active {@link AgentChatModelProvider} and manages fallbacks.
 */
@ApplicationScoped
@Unremovable
public class ChatModelProducer {

    private static final Logger log = Logger.getLogger(ChatModelProducer.class);
    private static final AtomicBoolean loggedConfig = new AtomicBoolean(false);

    @ConfigProperty(name = "arcclaw.llm.provider", defaultValue = "groq")
    String provider;

    @ConfigProperty(name = "quarkus.langchain4j.openai.chat-model.model-name",
                    defaultValue = "llama-3.3-70b-versatile")
    String modelName;

    @ConfigProperty(name = "arcclaw.llm.max-output-tokens", defaultValue = "8192")
    int maxTokens;

    @ConfigProperty(name = "arcclaw.llm.max-retries", defaultValue = "3")
    int maxRetries;

    @ConfigProperty(name = "arcclaw.llm.timeout", defaultValue = "PT120S")
    Duration timeout;

    @ConfigProperty(name = "arcclaw.llm.fallback-provider")
    Optional<String> fallbackProvider;

    @ConfigProperty(name = "arcclaw.llm.fallback-model")
    Optional<String> fallbackModel;

    @ConfigProperty(name = "arcclaw.llm.fallback-base-url")
    Optional<String> fallbackBaseUrl;

    @ConfigProperty(name = "arcclaw.llm.fallback-api-key")
    Optional<String> fallbackApiKey;

    @ConfigProperty(name = "arcclaw.llm.fallback-message-threshold", defaultValue = "14")
    int fallbackMessageThreshold;

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

    private void logConfig(String resolvedFbProvider, String displayModel) {
        if (loggedConfig.compareAndSet(false, true)) {
            log.debugf("ChatModelProducer: primary=%s model=%s fallback=%s model=%s", 
                provider, modelName, resolvedFbProvider.isEmpty() ? "none" : resolvedFbProvider, displayModel);
            
            if (log.isTraceEnabled()) {
                log.trace("--- LLM Configuration Context ---");
                System.getenv().forEach((k, v) -> {
                    if (k.startsWith("ARCCLAW_LLM_")) log.tracef("Env: %s = %s", k, mask(v));
                });
                log.trace("--------------------------------");
            }
        }
    }

    @Produces
    @ApplicationScoped
    public ChatModel chatModel() {
        AgentChatModelProvider providerFactory = findProvider();

        // 🟢 Robust fallback detection (bypassing injection quirks)
        Config config = ConfigProvider.getConfig();
        String fbProvider = config.getOptionalValue("ARCCLAW_LLM_FALLBACK_PROVIDER", String.class)
                .or(() -> config.getOptionalValue("arcclaw.llm.fallback-provider", String.class))
                .or(() -> fallbackProvider)
                .orElse("").trim();
        String fbModel = config.getOptionalValue("ARCCLAW_LLM_FALLBACK_MODEL", String.class)
                .or(() -> config.getOptionalValue("arcclaw.llm.fallback-model", String.class))
                .or(() -> fallbackModel)
                .orElse("").trim();
        String fbBaseUrl = config.getOptionalValue("ARCCLAW_LLM_FALLBACK_BASE_URL", String.class)
                .or(() -> config.getOptionalValue("arcclaw.llm.fallback-base-url", String.class))
                .or(() -> fallbackBaseUrl)
                .orElse("");

        String resolvedFbProvider = !fbProvider.isEmpty() ? fbProvider 
                : LlmModelFactory.inferProviderFromUrl(fbBaseUrl);
        
        String displayModel = !fbModel.isEmpty() ? fbModel 
                : ("ollama".equalsIgnoreCase(resolvedFbProvider) ? ollamaModelId : "default");

        logConfig(resolvedFbProvider, displayModel);

        ChatModel primary = providerFactory.create();

        if (!resolvedFbProvider.isEmpty() || !fbModel.isEmpty()) {
            try {
                String fbApiKey = config.getOptionalValue("ARCCLAW_LLM_FALLBACK_API_KEY", String.class)
                        .or(() -> config.getOptionalValue("arcclaw.llm.fallback-api-key", String.class))
                        .or(() -> fallbackApiKey)
                        .orElse("");

                ChatModel fallback = modelFactory.createModel(
                    resolvedFbProvider, fbBaseUrl, fbApiKey,
                    fbModel, maxTokens, timeout, maxRetries, 
                    ollamaBaseUrl, ollamaModelId);
                
                return new FallbackChatModel(primary, fallback, modelName, displayModel, fallbackMessageThreshold);
            } catch (Exception e) {
                log.errorf("ChatModelProducer: Fallback init failed: %s", LogUtil.rootMessage(e));
                return primary;
            }
        }

        return primary;
    }

    @Produces
    @Identifier("agent-output-mode")
    @Dependent
    @Unremovable
    public StructuredOutputMode structuredOutputMode() {
        StructuredOutputMode primaryMode = findProvider().structuredOutputMode();
        
        // Resolve fallback mode from config
        Config config = ConfigProvider.getConfig();
        String fallbackModeStr = config.getOptionalValue("ARCCLAW_LLM_FALLBACK_STRUCTURED_OUTPUT_MODE", String.class)
                .or(() -> config.getOptionalValue("arcclaw.llm.fallback-structured-output-mode", String.class))
                .orElse("NATIVE");
        
        StructuredOutputMode fbMode = StructuredOutputMode.valueOf(fallbackModeStr.toUpperCase());

        // Effective logic: if EITHER needs a prompt directive, we include it.
        if (primaryMode == StructuredOutputMode.PROMPT_DIRECTIVE || fbMode == StructuredOutputMode.PROMPT_DIRECTIVE) {
            return StructuredOutputMode.PROMPT_DIRECTIVE;
        }
        
        return primaryMode;
    }

    private AgentChatModelProvider findProvider() {
        // We use the injected 'provider' field but ensure it's cleaned of inline comments/whitespace
        String cleanProvider = clean(provider != null ? provider : "groq");
        
        for (AgentChatModelProvider p : providers) {
            if (p.providerName().equalsIgnoreCase(cleanProvider)) {
                return p;
            }
        }
        throw new RuntimeException("No AgentChatModelProvider found for provider='" + cleanProvider + "' (raw: '" + provider + "')");
    }

    private String clean(String value) {
        if (value == null) return null;
        return value.split("#")[0].trim();
    }

    private String mask(String v) {
        if (v == null || v.length() < 5) return "***";
        return v.substring(0, 3) + "***";
    }
}

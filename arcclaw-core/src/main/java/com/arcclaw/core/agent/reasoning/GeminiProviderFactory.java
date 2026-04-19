package com.arcclaw.core.agent.reasoning;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkiverse.langchain4j.ai.runtime.gemini.AiGeminiChatLanguageModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * {@link AgentChatModelProvider} for Google Gemini AI using the Quarkiverse implementation.
 */
@ApplicationScoped
public class GeminiProviderFactory implements AgentChatModelProvider {

    private static final Logger log = Logger.getLogger(GeminiProviderFactory.class);

    @ConfigProperty(name = "arcclaw.llm.gemini.api-key")
    String apiKey;

    @ConfigProperty(name = "arcclaw.llm.gemini.model-name",
                    defaultValue = "gemini-1.5-flash")
    String modelName;

    @ConfigProperty(name = "arcclaw.llm.max-output-tokens",
                defaultValue = "8192")
    int maxOutputTokens;

    @ConfigProperty(name = "arcclaw.llm.temperature", defaultValue = "0.1")
    double temperature;

    @ConfigProperty(name = "arcclaw.llm.timeout",
                    defaultValue = "PT60S")
    Duration timeout;

    @ConfigProperty(name = "arcclaw.llm.gemini.log-requests", defaultValue = "false")
    boolean logRequests;

    @ConfigProperty(name = "arcclaw.llm.gemini.log-responses", defaultValue = "false")
    boolean logResponses;

    @ConfigProperty(name = "arcclaw.llm.max-retries", defaultValue = "3")
    int maxRetries;

    @Override
    public String providerName() {
        return "gemini";
    }

    @Override
    public ChatModel create() {
        String key = apiKey;
        
        // Fallback for environment variables if injected value is a placeholder
        if (key == null || key.equals("not-configured") || key.contains("<") || key.contains("xxxx")) {
            key = System.getProperty("ARCCLAW_LLM_API_KEY", System.getenv("ARCCLAW_LLM_API_KEY"));
        }

        if (key == null || key.isBlank() || key.equals("not-configured") || key.contains("<")) {
            throw new IllegalStateException("Gemini provider requires a valid API key. " +
                    "Set ARCCLAW_LLM_API_KEY in your .env or arcclaw.llm.gemini.api-key in config.");
        }

        // Hardening: Remove ALL non-printable characters, quotes, and whitespace
        key = key.replaceAll("[\\p{Cntrl}\\s\"']", "").trim();

        if (logRequests || logResponses) {
            String masked = key.length() > 8 
                ? key.substring(0, 4) + "..." + key.substring(key.length() - 4)
                : "****";
            log.infof("[GeminiProviderFactory] Initializing Quarkiverse Gemini model %s with key: %s", 
                modelName, masked);
        }

        return AiGeminiChatLanguageModel.builder()
            .key(key)
            .modelId(modelName)
            .temperature(temperature)
            .maxOutputTokens(maxOutputTokens)
            .timeout(timeout)
            .logRequests(logRequests)
            .logResponses(logResponses)
            .build();
    }
}

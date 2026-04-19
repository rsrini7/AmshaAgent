package com.arcclaw.core.agent.reasoning;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * {@link AgentChatModelProvider} for Google Gemini AI using its native SDK.
 */
@ApplicationScoped
public class GeminiProviderFactory implements AgentChatModelProvider {

    private static final Logger log = Logger.getLogger(GeminiProviderFactory.class);

    @ConfigProperty(name = "arcclaw.llm.gemini.api-key", defaultValue = "not-configured")
    String apiKey;

    @ConfigProperty(name = "arcclaw.llm.gemini.model-name",
                    defaultValue = "gemini-flash-latest")
    String modelName;

    @ConfigProperty(name = "arcclaw.llm.max-output-tokens",
                defaultValue = "8192")
    int maxOutputTokens;

    @ConfigProperty(name = "arcclaw.llm.temperature", defaultValue = "0.1")
    double temperature;

    @ConfigProperty(name = "arcclaw.llm.timeout",
                    defaultValue = "PT60S")
    Duration timeout;

    @ConfigProperty(name = "arcclaw.llm.log-http", defaultValue = "false")
    boolean logHttp;

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

        if (logHttp) {
            String masked = key.length() > 8 
                ? key.substring(0, 4) + "..." + key.substring(key.length() - 4)
                : "****";
            
            // Log first 2 bytes in hex to detect encoding issues/invisible prefix
            String hexPrefix = "";
            if (key.length() > 2) {
                hexPrefix = String.format(" (Hex Prefix: %02x %02x)", (int)key.charAt(0), (int)key.charAt(1));
            }

            log.infof("[GeminiProviderFactory] Initializing model %s with key: %s (length: %d)%s", 
                modelName, masked, key.length(), hexPrefix);
        }

        return GoogleAiGeminiChatModel.builder()
            .apiKey(key)
            .modelName(modelName)
            .timeout(timeout)
            .maxOutputTokens(maxOutputTokens)
            .maxRetries(maxRetries)
            .temperature(temperature)
            .logRequests(logHttp)
            .logResponses(logHttp)
            .build();
    }
}

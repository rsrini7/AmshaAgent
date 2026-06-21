package com.amsha.core.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.amsha.core.llm.LlmValidationException;
import com.amsha.core.llm.LlmValidationGateway;
import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultConstants;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class LlmValidationService implements LlmValidationGateway {

    @Inject
    SecretVault vault;

    @Inject
    LlmModelFactory modelFactory;

    @ConfigProperty(name = "AMSHA_GENERIC_OPENAI_BASE_URL")
    Optional<String> genericOpenAiBaseUrl;

    @Override
    public boolean validate(String provider, String model, String apiKey) throws LlmValidationException {
        try {
            if (!"claude-code".equals(provider)) {
                vault.storeSystemKey(VaultConstants.LLM_API_KEY, apiKey.getBytes(StandardCharsets.UTF_8));
            }
            String effectiveBaseUrl =
                    switch (provider.toLowerCase(Locale.ROOT)) {
                        case "groq" -> "https://api.groq.com/openai/v1";
                        case "openai" -> "https://api.openai.com/v1";
                        case "anthropic" -> "https://api.anthropic.com";
                        case "openrouter" -> "https://openrouter.ai/api/v1";
                        case "cerebras" -> "https://api.cerebras.ai/v1";
                        case "ollama" -> "http://localhost:11434";
                        case "nvidia" -> "https://integrate.api.nvidia.com/v1";
                        case "omlx" -> "http://127.0.0.1:8000/v1";
                        case "llamacpp" -> "http://127.0.0.1:9000/v1";
                        case "generic-openai" -> genericOpenAiBaseUrl.orElse("");
                        default -> "";
                    };

            if ("generic-openai".equalsIgnoreCase(provider) && effectiveBaseUrl.isBlank()) {
                throw new LlmValidationException(
                        "generic-openai provider requires AMSHA_GENERIC_OPENAI_BASE_URL to be configured");
            }

            // Use the factory to create a model manually passing api key
            ChatModel chatModel = modelFactory.createModel(
                    provider,
                    effectiveBaseUrl,
                    apiKey,
                    model,
                    10,
                    Duration.ofSeconds(15),
                    1, // maxRetries
                    "ollama".equalsIgnoreCase(provider) ? effectiveBaseUrl : null,
                    "ollama".equalsIgnoreCase(provider) ? model : null);

            var future =
                    CompletableFuture.supplyAsync(() -> chatModel.chat("hi")).orTimeout(15, TimeUnit.SECONDS);
            String reply = future.join();
            return reply != null;
        } catch (Exception e) {
            Throwable cause = e;
            while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                    && cause.getCause() != null) {
                cause = cause.getCause();
            }
            String msg = cause.getMessage();
            // Attempt to extract "message" from JSON if present (OpenAI/Anthropic style)
            if (msg != null && msg.trim().startsWith("{")) {
                Matcher m = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]+)\"").matcher(msg);
                if (m.find()) {
                    msg = m.group(1);
                }
            }
            throw new LlmValidationException(msg, cause);
        }
    }
}

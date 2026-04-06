package com.arcclaw.app.routing;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.llm.LlmValidationException;
import com.arcclaw.core.llm.LlmValidationGateway;
import com.arcclaw.core.security.SecretVault;
import com.arcclaw.core.security.VaultConstants;
import dev.langchain4j.model.chat.ChatModel;

@ApplicationScoped
public class LlmValidationService implements LlmValidationGateway {

    @Inject
    SecretVault vault;

    @Inject
    LlmModelFactory modelFactory;

    @Override
    public boolean validate(String provider, String model, String apiKey) throws LlmValidationException {
        try {
            if (!"claude-code".equals(provider)) {
                vault.storeSystemKey(VaultConstants.LLM_API_KEY, apiKey.getBytes(StandardCharsets.UTF_8));
            }
            String effectiveBaseUrl = switch (provider.toLowerCase()) {
                case "groq"      -> "https://api.groq.com/openai/v1";
                case "openai"    -> "https://api.openai.com/v1";
                case "anthropic" -> "https://api.anthropic.com";
                case "openrouter"-> "https://openrouter.ai/api/v1";
                case "ollama"    -> "http://localhost:11434";
                default          -> "";
            };

            // Use the factory to create a model manually passing api key
            ChatModel chatModel = modelFactory.createModel(
                provider,
                effectiveBaseUrl,
                apiKey,
                model,
                10,
                Duration.ofSeconds(15),
                "ollama".equalsIgnoreCase(provider) ? effectiveBaseUrl : null,
                "ollama".equalsIgnoreCase(provider) ? model : null
            );
            
            var future = CompletableFuture
                .supplyAsync(() -> chatModel.chat("hi"))
                .orTimeout(15, TimeUnit.SECONDS);
            String reply = future.join();
            return reply != null;
        } catch (Exception e) {
            Throwable cause = e;
            while ((cause instanceof CompletionException || 
                    cause instanceof ExecutionException) && 
                   cause.getCause() != null) {
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

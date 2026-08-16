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
import java.util.concurrent.Executor;
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

    @Inject
    Executor validationExecutor;

    @ConfigProperty(name = "AMSHA_GENERIC_OPENAI_BASE_URL")
    Optional<String> genericOpenAiBaseUrl;

    @Override
    public boolean validate(String provider, String model, String apiKey) throws LlmValidationException {
        try {
            if (!ModelDefaults.PROVIDER_CLAUDE_CODE.equals(provider)) {
                vault.storeSystemKey(VaultConstants.LLM_API_KEY, apiKey.getBytes(StandardCharsets.UTF_8));
            }
            String effectiveBaseUrl =
                    switch (provider.toLowerCase(Locale.ROOT)) {
                        case ModelDefaults.PROVIDER_GROQ -> ModelDefaults.DEFAULT_GROQ_BASE_URL;
                        case ModelDefaults.PROVIDER_OPENAI -> ModelDefaults.DEFAULT_OPENAI_BASE_URL;
                        case ModelDefaults.PROVIDER_ANTHROPIC -> ModelDefaults.DEFAULT_ANTHROPIC_BASE_URL;
                        case ModelDefaults.PROVIDER_OPENROUTER -> ModelDefaults.DEFAULT_OPENROUTER_BASE_URL;
                        case ModelDefaults.PROVIDER_CEREBRAS -> ModelDefaults.DEFAULT_CEREBRAS_BASE_URL;
                        case ModelDefaults.PROVIDER_SARVAM -> ModelDefaults.DEFAULT_SARVAM_BASE_URL;
                        case ModelDefaults.PROVIDER_OPENCODE -> ModelDefaults.DEFAULT_OPENCODE_BASE_URL;
                        case ModelDefaults.PROVIDER_HUGGINGFACE -> ModelDefaults.DEFAULT_HUGGINGFACE_BASE_URL;
                        case ModelDefaults.PROVIDER_OLLAMA -> ModelDefaults.DEFAULT_OLLAMA_BASE_URL;
                        case ModelDefaults.PROVIDER_NVIDIA -> ModelDefaults.DEFAULT_NVIDIA_BASE_URL;
                        case ModelDefaults.PROVIDER_OMLX -> ModelDefaults.DEFAULT_OMLX_BASE_URL;
                        case ModelDefaults.PROVIDER_LLAMACPP -> ModelDefaults.DEFAULT_LLAMACPP_BASE_URL;
                        case ModelDefaults.PROVIDER_GENERIC_OPENAI -> genericOpenAiBaseUrl.orElse("");
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

            var future = CompletableFuture.supplyAsync(() -> chatModel.chat("hi"), validationExecutor)
                    .orTimeout(15, TimeUnit.SECONDS);
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

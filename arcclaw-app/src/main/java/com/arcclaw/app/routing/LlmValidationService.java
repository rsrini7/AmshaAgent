package com.arcclaw.app.routing;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
                vault.store(VaultConstants.LLM_API_KEY, apiKey.getBytes(StandardCharsets.UTF_8));
            }
            // Use the factory to create a model manually passing api key
            ChatModel chatModel = modelFactory.createModel(
                provider,
                null, 
                apiKey, 
                model,
                10, 
                Duration.ofSeconds(15), 
                null, 
                null 
            );
            
            var future = CompletableFuture
                .supplyAsync(() -> chatModel.chat("Reply with the single word: ok"))
                .orTimeout(15, TimeUnit.SECONDS);
            String reply = future.join();
            return reply != null && reply.toLowerCase().contains("ok");
        } catch (Exception e) {
            throw new LlmValidationException(e.getMessage(), e);
        }
    }
}

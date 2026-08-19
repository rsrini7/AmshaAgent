package com.amsha.llm.providers.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;

import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.RoutingChatModelProvider;
import com.amsha.llm.providers.catalog.*;
import com.amsha.llm.providers.helper.*;
import com.amsha.llm.providers.reasoning.*;
import com.amsha.llm.providers.wrapper.*;

import io.quarkiverse.langchain4j.ai.runtime.gemini.AiGeminiChatLanguageModel;
import io.quarkus.arc.Unremovable;

/**
 * {@link RoutingChatModelProvider} for Google Gemini AI using the Quarkiverse implementation.
 */
@ApplicationScoped
@Unremovable
public class GeminiLlmProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() {
        return ModelDefaults.PROVIDER_GEMINI;
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        String key = config.apiKey()
                .orElseThrow(
                        () -> new IllegalStateException("Gemini provider requires an API key (AMSHA_LLM_API_KEY)"));

        // Hardening: Strip quotes and whitespace
        key = key.replaceAll("[\\p{Cntrl}\\s\"']", "").trim();

        return AiGeminiChatLanguageModel.builder()
                .key(key)
                .modelId(config.modelName())
                .temperature(0.0)
                // .includeThoughts(false)
                // .thinkingBudget(0L)
                .timeout(config.timeout().orElse(Duration.ofSeconds(30)))
                .logRequests(false)
                .logResponses(false)
                .build();
    }
}

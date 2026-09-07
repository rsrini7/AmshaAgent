package com.amsha.llm.providers.routing;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;

import com.amsha.core.routing.CapabilityAwareChatModel;
import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.MultimodalCapabilities;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.llm.api.RoutingChatModelProvider;
import com.amsha.llm.providers.transport.GovernedLangChainHttpClientBuilder;
import com.amsha.security.http.GovernedTransportService;

import io.quarkus.arc.Unremovable;

/**
 * {@link RoutingChatModelProvider} for Google Gemini AI using the Quarkiverse implementation.
 */
@ApplicationScoped
@Unremovable
public class GeminiLlmProviderFactory implements RoutingChatModelProvider {

    @Inject
    GovernedTransportService governedTransport;

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

        var builder = GoogleAiGeminiChatModel.builder()
                .httpClientBuilder(new GovernedLangChainHttpClientBuilder(governedTransport))
                .apiKey(key)
                .modelName(config.modelName())
                .temperature(0.0)
                // .includeThoughts(false)
                // .thinkingBudget(0L)
                .timeout(config.timeout().orElse(Duration.ofSeconds(30)))
                .logRequests(false)
                .logResponses(false);
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            builder.baseUrl(config.baseUrl());
        }
        ChatModel model = builder.build();
        MultimodalCapability multimodalCap =
                MultimodalCapabilities.defaultCapabilityForModel("gemini", config.modelName());
        return CapabilityAwareChatModel.withCapabilities(model, false, multimodalCap);
    }
}

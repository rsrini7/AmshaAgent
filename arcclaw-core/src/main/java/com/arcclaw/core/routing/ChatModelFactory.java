package com.arcclaw.core.routing;

import java.time.Duration;
import java.util.Map;

import com.arcclaw.core.agent.reasoning.StructuredOutputMode;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;

/**
 * Shared factory for building LangChain4j ChatModel instances.
 * Consolidates the OpenAiChatModel builder pattern previously duplicated across
 * every RoutingChatModelProvider and AgentChatModelProvider implementation.
 * New providers should use these methods instead of repeating builder calls.
 */
public final class ChatModelFactory {

    private ChatModelFactory() {}

    /**
     * Creates an {@link OpenAiChatModel} builder with the given parameters.
     * Caller may further customize (customHeaders, responseFormat, etc.) before {@code build()}.
     */
    public static OpenAiChatModel.OpenAiChatModelBuilder openAiBuilder(
            String baseUrl, String apiKey, String modelName,
            Integer maxTokens, Duration timeout, Integer maxRetries,
            double temperature, boolean logRequests, boolean logResponses) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .timeout(timeout)
                .maxRetries(maxRetries)
                .temperature(temperature)
                .logRequests(logRequests)
                .logResponses(logResponses);
    }

    /**
     * Applies {@link ResponseFormat#JSON} when the model supports native structured output
     * and no tools are needed (toolless mode). Used by intent-classifier and compaction models.
     */
    public static void applyJsonResponseFormat(
            OpenAiChatModel.OpenAiChatModelBuilder builder,
            StructuredOutputMode mode, boolean toolless) {
        if (mode == StructuredOutputMode.NATIVE && toolless) {
            builder.responseFormat(ResponseFormat.JSON);
        }
    }

    /**
     * Convenience: builds an OpenAI-compatible ChatModel from an {@link LlmModelConfig}.
     * Used by RoutingChatModelProvider implementations that need no custom headers.
     */
    public static ChatModel createFromConfig(LlmModelConfig config) {
        return openAiBuilder(
                config.baseUrl(),
                config.apiKey().orElseThrow(() -> new IllegalStateException(
                        config.provider() + " provider requires an API key")),
                config.modelName(),
                config.maxTokens().orElse(null),
                config.timeout().orElse(null),
                config.maxRetries().orElse(3),
                0.0, false, false)
                .build();
    }

    /**
     * Convenience: builds an OpenAI-compatible ChatModel from direct parameters.
     * Used by AgentChatModelProvider implementations.
     */
    public static ChatModel createOpenAi(
            String baseUrl, String apiKey, String modelName,
            int maxTokens, Duration timeout, int maxRetries,
            double temperature, boolean logHttp) {
        return createOpenAi(baseUrl, apiKey, modelName,
                maxTokens, timeout, maxRetries, temperature, logHttp, null);
    }

    /**
     * Builds an OpenAI-compatible ChatModel with optional custom request parameters.
     * Used by AgentChatModelProvider implementations that need extra body params (e.g. enable_thinking).
     */
    public static ChatModel createOpenAi(
            String baseUrl, String apiKey, String modelName,
            int maxTokens, Duration timeout, int maxRetries,
            double temperature, boolean logHttp,
            Map<String, Object> customParameters) {
        var builder = openAiBuilder(baseUrl, apiKey, modelName,
                maxTokens, timeout, maxRetries,
                temperature, logHttp, logHttp);
        if (customParameters != null && !customParameters.isEmpty()) {
            builder.defaultRequestParameters(
                    OpenAiChatRequestParameters.builder()
                            .customParameters(customParameters)
                            .build());
        }
        return builder.build();
    }

    /**
     * Applies OpenRouter-specific HTTP-Referer and X-Title headers to a builder.
     */
    public static OpenAiChatModel.OpenAiChatModelBuilder withOpenRouterHeaders(
            OpenAiChatModel.OpenAiChatModelBuilder builder,
            String siteUrl, String appName) {
        return builder.customHeaders(Map.of("HTTP-Referer", siteUrl, "X-Title", appName));
    }
}

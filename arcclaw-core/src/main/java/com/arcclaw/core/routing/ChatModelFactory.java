package com.arcclaw.core.routing;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import com.arcclaw.core.agent.reasoning.StructuredOutputMode;

import org.jboss.logging.Logger;

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
            String baseUrl,
            String apiKey,
            String modelName,
            Integer maxTokens,
            Duration timeout,
            Integer maxRetries,
            double temperature,
            boolean logRequests,
            boolean logResponses) {
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
            OpenAiChatModel.OpenAiChatModelBuilder builder, StructuredOutputMode mode, boolean toolless) {
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
                        config.apiKey()
                                .orElseThrow(() ->
                                        new IllegalStateException(config.provider() + " provider requires an API key")),
                        config.modelName(),
                        config.maxTokens().orElse(null),
                        config.timeout().orElse(null),
                        config.maxRetries().orElse(3),
                        0.0,
                        false,
                        false)
                .build();
    }

    /**
     * Convenience: builds an OpenAI-compatible ChatModel from direct parameters.
     * Used by AgentChatModelProvider implementations.
     */
    public static ChatModel createOpenAi(
            String baseUrl,
            String apiKey,
            String modelName,
            int maxTokens,
            Duration timeout,
            int maxRetries,
            double temperature,
            boolean logHttp) {
        return createOpenAi(baseUrl, apiKey, modelName, maxTokens, timeout, maxRetries, temperature, logHttp, null);
    }

    /**
     * Builds an OpenAI-compatible ChatModel with optional custom request parameters.
     * Used by AgentChatModelProvider implementations that need extra body params (e.g. enable_thinking).
     */
    public static ChatModel createOpenAi(
            String baseUrl,
            String apiKey,
            String modelName,
            int maxTokens,
            Duration timeout,
            int maxRetries,
            double temperature,
            boolean logHttp,
            Map<String, Object> customParameters) {
        var builder = openAiBuilder(
                baseUrl, apiKey, modelName, maxTokens, timeout, maxRetries, temperature, logHttp, logHttp);
        if (customParameters != null && !customParameters.isEmpty()) {
            builder.defaultRequestParameters(OpenAiChatRequestParameters.builder()
                    .customParameters(customParameters)
                    .build());
        }
        return builder.build();
    }

    /**
     * Applies OpenRouter-specific HTTP-Referer and X-Title headers to a builder.
     */
    public static OpenAiChatModel.OpenAiChatModelBuilder withOpenRouterHeaders(
            OpenAiChatModel.OpenAiChatModelBuilder builder, String siteUrl, String appName) {
        return builder.customHeaders(Map.of("HTTP-Referer", siteUrl, "X-Title", appName));
    }

    /**
     * Builds a list of MultiModelChatModel.ModelEntry from comma-separated API keys and model names.
     * Pairing rules:
     * - Single key + single model → 1 entry
     * - Multiple keys + single model → one entry per key (same model)
     * - Single key + multiple models → one entry per model (same key)
     * - Multiple keys + multiple models → paired by index (zip, up to shorter list)
     *
     * @param keys comma-separated API keys
     * @param models comma-separated model names
     * @param providerName for logging
     * @param modelFactory function that creates a ChatModel from (apiKey, modelName)
     * @return list of ModelEntry, never empty
     */
    public static List<MultiModelChatModel.ModelEntry> buildMultiModelEntries(
            String keys, String models, String providerName, BiFunction<String, String, ChatModel> modelFactory) {
        String[] keyArr = keys.split(",");
        String[] modelArr = models.split(",");

        // Trim and filter empty values
        keyArr = Arrays.stream(keyArr)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        modelArr = Arrays.stream(modelArr)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        List<MultiModelChatModel.ModelEntry> entries = new ArrayList<>();

        if (keyArr.length == 1 && modelArr.length == 1) {
            // Single key + single model
            entries.add(new MultiModelChatModel.ModelEntry(providerName, modelFactory.apply(keyArr[0], modelArr[0])));
        } else if (keyArr.length > 1 && modelArr.length == 1) {
            // Multiple keys, single model
            for (int i = 0; i < keyArr.length; i++) {
                String label = providerName + "-key" + (i + 1);
                entries.add(new MultiModelChatModel.ModelEntry(label, modelFactory.apply(keyArr[i], modelArr[0])));
            }
        } else if (keyArr.length == 1 && modelArr.length > 1) {
            // Single key, multiple models
            for (int i = 0; i < modelArr.length; i++) {
                String label = providerName + "-" + modelArr[i];
                entries.add(new MultiModelChatModel.ModelEntry(label, modelFactory.apply(keyArr[0], modelArr[i])));
            }
        } else {
            // Multiple keys AND models — pair by index
            int len = Math.min(keyArr.length, modelArr.length);
            if (keyArr.length != modelArr.length) {
                Logger.getLogger(ChatModelFactory.class)
                        .warnf(
                                "Provider %s: %d API keys but %d models — pairing by index (using first %d)",
                                providerName, keyArr.length, modelArr.length, len);
            }
            for (int i = 0; i < len; i++) {
                String label = providerName + "-key" + (i + 1) + "/" + modelArr[i];
                entries.add(new MultiModelChatModel.ModelEntry(label, modelFactory.apply(keyArr[i], modelArr[i])));
            }
        }

        return entries;
    }
}

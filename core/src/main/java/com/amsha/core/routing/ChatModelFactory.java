package com.amsha.core.routing;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.reasoning.AgentLlmConfig;
import com.amsha.core.agent.reasoning.StructuredOutputMode;
import com.amsha.core.security.http.GovernedTransportService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

import io.quarkiverse.langchain4j.ai.runtime.gemini.AiGeminiChatLanguageModel;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;

/**
 * Shared factory for building LangChain4j ChatModel instances.
 * Consolidates the OpenAiChatModel builder pattern previously duplicated across
 * every RoutingChatModelProvider and AgentChatModelProvider implementation.
 * New providers should use these methods instead of repeating builder calls.
 */
public final class ChatModelFactory {

    private static final Logger LOG = Logger.getLogger(ChatModelFactory.class);

    /** Matches headroom proxy ports 8788–8797 (e.g. {@code :8789}). */
    private static final Pattern HEADROOM_PORT_PATTERN = Pattern.compile(":87[89]\\d");

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
        applyJsonResponseFormat(builder, mode, toolless, null);
    }

    /**
     * Applies {@link ResponseFormat#JSON} when the model supports native structured output
     * and no tools are needed (toolless mode). Used by intent-classifier and compaction models.
     * Bypasses JSON mode if the base URL routes through any Headroom proxy (ports 8788–8797).
     */
    public static void applyJsonResponseFormat(
            OpenAiChatModel.OpenAiChatModelBuilder builder,
            StructuredOutputMode mode,
            boolean toolless,
            String baseUrl) {
        if (mode == StructuredOutputMode.NATIVE && toolless) {
            if (isHeadroomProxyUrl(baseUrl)) {
                LOG.info("Bypassing native JSON response format due to Headroom proxy routing: " + baseUrl);
                return;
            }
            builder.responseFormat(ResponseFormat.JSON);
        }
    }

    /** Returns {@code true} if the URL routes through a Headroom proxy instance. */
    private static boolean isHeadroomProxyUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("headroom")
                || HEADROOM_PORT_PATTERN.matcher(lower).find();
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
        return createOpenAi(baseUrl, apiKey, modelName, maxTokens, timeout, maxRetries, temperature, logHttp, Map.of());
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
        if (!customParameters.isEmpty()) {
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
     * Check if the model name is a DeepSeek thinking-capable model that defaults to
     * thinking mode and returns {@code reasoning_content}.
     *
     * <p>DeepSeek v4 and r1 series default to thinking mode. This causes 400 errors
     * in OpenAI-compatible clients that do not passthrough {@code reasoning_content}.
     */
    public static boolean isDeepSeekThinkingModel(String modelName) {
        if (modelName == null || modelName.isBlank()) return false;
        String lower = modelName.trim().toLowerCase();
        return lower.contains("deepseek-v") || lower.contains("deepseek-r");
    }

    /**
     * Returns a map with DeepSeek thinking workaround ({@code thinking: {type: "disabled"}})
     * if the model is a DeepSeek v4/thinking model, otherwise {@code null}.
     */
    public static Map<String, Object> deepSeekWorkaroundParams(String modelName) {
        if (isDeepSeekThinkingModel(modelName)) {
            return Map.of("thinking", Map.of("type", "disabled"));
        }
        return Map.of();
    }

    private static final ObjectMapper EXTRA_BODY_MAPPER = new ObjectMapper();

    /**
     * Parses a JSON string from {@code AMSHA_GENERIC_OPENAI_EXTRA_BODY} into a map.
     * Returns {@code null} if blank or unparseable (logs a warning).
     */
    public static Map<String, Object> parseExtraBody(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return EXTRA_BODY_MAPPER.readValue(json.trim(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            LOG.warnf("Failed to parse AMSHA_GENERIC_OPENAI_EXTRA_BODY '%s': %s", json, LogUtil.rootMessage(e));
            return Map.of();
        }
    }

    /**
     * Merges auto-detected custom parameters with user-supplied extra-body params.
     * User values win on key collision. Returns an empty map when both inputs are empty.
     */
    public static Map<String, Object> mergeCustomParameters(Map<String, Object> auto, Map<String, Object> user) {
        if (auto == null || auto.isEmpty()) {
            return user == null ? Map.of() : user;
        }
        if (user == null || user.isEmpty()) {
            return auto;
        }
        HashMap<String, Object> merged = new HashMap<>(auto);
        merged.putAll(user);
        return merged;
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
        String[] keyArr = keys.split(",", -1);
        String[] modelArr = models.split(",", -1);

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

    /**
     * Builds a {@link ChatModel} for a resolved profile credential, honoring the persisted provider
     * adapter ID. OpenAI-compatible adapters (groq, openai, openrouter, cerebras, nvidia, omlx,
     * generic-openai, llamacpp, etc.) use the OpenAI chat protocol; gemini, anthropic/claude, and
     * ollama use their native LangChain4j builders. This is the runtime factory path required by the
     * onboarding ADR: clients are built from a short-lived, closable credential view rather than the
     * deployment {@code AgentChatModelProvider.create(model)} path that reads deployment config.
     *
     * @param adapterId the persisted provider adapter ID (e.g. "openai", "gemini", "anthropic")
     * @param baseUrl   resolved base URL (adapter default or connection customBaseUrl)
     * @param apiKey    plaintext key from the closable {@link com.amsha.core.routing.ResolvedCredential}
     * @param model     model id from the resolved profile
     * @param config    shared LLM tuning config
     * @return a configured ChatModel
     */
    public static ChatModel createForAdapter(
            String adapterId, String baseUrl, String apiKey, String model, AgentLlmConfig config) {
        String adapter = adapterId == null ? "" : adapterId.toLowerCase();
        return switch (adapter) {
            case "gemini" -> createGemini(baseUrl, apiKey, model, config);
            case "anthropic", "claude" -> createAnthropic(baseUrl, apiKey, model, config);
            case "claude-code" -> createClaudeCode(baseUrl, apiKey, model, config);
            case "ollama" -> createOllama(baseUrl, model, config);
            case "codex" -> createCodex(baseUrl, apiKey, null, model, config);
            default ->
                createOpenAi(
                        baseUrl,
                        apiKey,
                        model,
                        config.maxTokens(),
                        config.timeout(),
                        config.maxRetries(),
                        config.temperature(),
                        config.logHttp());
        };
    }

    private static GovernedTransportService resolveGovernedTransport() {
        try {
            ArcContainer container = Arc.container();
            if (container != null) {
                InstanceHandle<GovernedTransportService> handle = container.instance(GovernedTransportService.class);
                if (handle.isAvailable()) {
                    return handle.get();
                }
            }
        } catch (Exception _) {
            // CDI not active
        }
        return null;
    }

    /** Builds a Claude Code ChatModel (direct Anthropic Messages endpoint supporting CLI OAuth / API keys). */
    public static ChatModel createClaudeCode(String baseUrl, String token, String model, AgentLlmConfig config) {
        return createClaudeCode(baseUrl, token, model, config, resolveGovernedTransport());
    }

    /** Builds a Claude Code ChatModel with explicit governed transport. */
    public static ChatModel createClaudeCode(
            String baseUrl,
            String token,
            String model,
            AgentLlmConfig config,
            GovernedTransportService governedTransport) {
        String modelName = model;
        if (modelName == null || modelName.isBlank() || "mistral-small".equalsIgnoreCase(modelName)) {
            modelName = ModelDefaults.DEFAULT_CLAUDE_CODE_MODEL;
        }
        return new ClaudeCodeResponsesChatModel(
                baseUrl,
                token,
                modelName,
                config.maxTokens(),
                config.timeout(),
                config.temperature(),
                governedTransport != null ? governedTransport : resolveGovernedTransport());
    }

    /** Builds a Codex ChatModel (direct ChatGPT Plus Responses endpoint or OpenAI proxy). */
    public static ChatModel createCodex(
            String baseUrl, String token, String accountId, String model, AgentLlmConfig config) {
        return createCodex(
                baseUrl,
                token,
                accountId,
                model,
                config.maxTokens(),
                config.timeout(),
                config.maxRetries(),
                config.temperature(),
                config.logHttp(),
                resolveGovernedTransport());
    }

    /** Builds a Codex ChatModel with explicit parameter overrides. */
    public static ChatModel createCodex(
            String baseUrl,
            String token,
            String accountId,
            String model,
            Integer maxTokens,
            Duration timeout,
            Integer maxRetries,
            Double temperature,
            boolean logHttp) {
        return createCodex(
                baseUrl,
                token,
                accountId,
                model,
                maxTokens,
                timeout,
                maxRetries,
                temperature,
                logHttp,
                resolveGovernedTransport());
    }

    /** Builds a Codex ChatModel with explicit parameter overrides and governed transport. */
    public static ChatModel createCodex(
            String baseUrl,
            String token,
            String accountId,
            String model,
            Integer maxTokens,
            Duration timeout,
            Integer maxRetries,
            Double temperature,
            boolean logHttp,
            GovernedTransportService governedTransport) {
        if (baseUrl == null || baseUrl.isBlank() || baseUrl.contains("chatgpt.com/backend-api/codex")) {
            String endpoint = (baseUrl != null && baseUrl.endsWith("/responses"))
                    ? baseUrl
                    : (baseUrl != null && !baseUrl.isBlank()
                            ? baseUrl + "/responses"
                            : "https://chatgpt.com/backend-api/codex/responses");
            return new CodexResponsesChatModel(
                    endpoint, token, accountId, model, maxTokens, timeout, temperature, governedTransport);
        }
        return createOpenAi(baseUrl, token, model, maxTokens, timeout, maxRetries, temperature, logHttp);
    }

    /** Builds a Gemini ChatModel from a resolved credential. */
    public static ChatModel createGemini(String baseUrl, String apiKey, String model, AgentLlmConfig config) {
        String modelName = (model == null || model.isBlank()) ? ModelDefaults.DEFAULT_GEMINI_MODEL : model;
        AiGeminiChatLanguageModel.Builder builder = AiGeminiChatLanguageModel.builder()
                .key(apiKey)
                .modelId(modelName)
                .temperature(config.temperature())
                .maxOutputTokens(config.maxTokens())
                .timeout(config.timeout())
                .logRequests(config.logHttp())
                .logResponses(config.logHttp());
        // Honor a persisted custom endpoint; otherwise the adapter default is used.
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(Optional.of(baseUrl));
        }
        return builder.build();
    }

    /** Builds an Anthropic/Claude ChatModel from a resolved credential. */
    public static ChatModel createAnthropic(String baseUrl, String apiKey, String model, AgentLlmConfig config) {
        String modelName = (model == null || model.isBlank()) ? ModelDefaults.DEFAULT_CLAUDE_CODE_MODEL : model;
        AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(config.timeout())
                .logRequests(config.logHttp())
                .logResponses(config.logHttp());
        // Honor a persisted custom endpoint; otherwise the Anthropic default is used.
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    /** Builds an Ollama ChatModel from a resolved credential. */
    public static ChatModel createOllama(String baseUrl, String model, AgentLlmConfig config) {
        String url = (baseUrl == null || baseUrl.isBlank()) ? ModelDefaults.DEFAULT_OLLAMA_BASE_URL : baseUrl;
        String modelName = (model == null || model.isBlank()) ? ModelDefaults.DEFAULT_OLLAMA_MODEL : model;
        return OllamaChatModel.builder()
                .baseUrl(url)
                .modelName(modelName)
                .logRequests(config.logHttp())
                .logResponses(config.logHttp())
                .build();
    }
}

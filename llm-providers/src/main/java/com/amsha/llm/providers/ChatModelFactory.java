package com.amsha.llm.providers;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.CDI;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

import com.amsha.common.util.LogUtil;
import com.amsha.core.config.CoreSpiResolutionValidator;
import com.amsha.core.routing.CapabilityAwareChatModel;
import com.amsha.core.routing.MultiModelChatModel;
import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.ChatModelCreationPort;
import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.MultimodalCapabilities;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.llm.api.StructuredOutputCapabilities;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.llm.providers.wrapper.AgyResponsesChatModel;
import com.amsha.llm.providers.wrapper.ClaudeCodeResponsesChatModel;
import com.amsha.llm.providers.wrapper.CodexResponsesChatModel;
import com.amsha.llm.providers.wrapper.FoundryResponsesChatModel;
import com.amsha.llm.providers.wrapper.KimchiResponsesChatModel;
import com.amsha.security.http.GovernedTransportService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.arc.Unremovable;

/**
 * Shared factory for building LangChain4j ChatModel instances.
 * Implements {@link ChatModelCreationPort} for decoupling consumers from concrete SDKs.
 */
@ApplicationScoped
@Unremovable
public class ChatModelFactory implements ChatModelCreationPort {

    private static final Logger LOG = Logger.getLogger(ChatModelFactory.class);
    private static final ObjectMapper EXTRA_BODY_MAPPER = new ObjectMapper();

    public ChatModelFactory() {}

    /** Creates an {@link OpenAiChatModel} builder with the given parameters. */
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
        var builder = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .timeout(timeout)
                .maxRetries(maxRetries)
                .temperature(temperature)
                .logRequests(logRequests)
                .logResponses(logResponses);
        resolveChatModelListener().ifPresent(listener -> builder.listeners(List.of(listener)));
        return builder;
    }

    /** Resolves the optional listener through the repository's canonical CDI SPI algorithm. */
    private static Optional<ChatModelListener> resolveChatModelListener() {
        try {
            return CoreSpiResolutionValidator.resolveOptional(CDI.current().getBeanManager(), ChatModelListener.class);
        } catch (RuntimeException e) {
            // Static/unit-test construction may happen without an active CDI container. The
            // listener is optional and must never make the provider builder unusable.
            LOG.debugf("ChatModelListener resolution skipped (%s)", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Applies JSON format when model supports native structured output and toolless mode is active. */
    public static void applyJsonResponseFormat(
            OpenAiChatModel.OpenAiChatModelBuilder builder, StructuredOutputMode mode, boolean toolless) {
        applyJsonResponseFormat(builder, mode, toolless, null);
    }

    /** Applies JSON format when model supports native structured output and toolless mode is active. */
    public static void applyJsonResponseFormat(
            OpenAiChatModel.OpenAiChatModelBuilder builder,
            StructuredOutputMode mode,
            boolean toolless,
            String baseUrl) {
        if (mode == StructuredOutputMode.NATIVE && toolless) {
            if (StructuredOutputCapabilities.isHeadroomProxyUrl(baseUrl)) {
                LOG.info("Bypassing native JSON response format due to Headroom proxy routing: " + baseUrl);
                return;
            }
            builder.responseFormat(ResponseFormat.JSON);
        }
    }

    /** Convenience: builds an OpenAI-compatible ChatModel from an {@link LlmModelConfig}. */
    public static ChatModel createFromConfig(LlmModelConfig config) {
        ChatModel model = openAiBuilder(
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
        boolean eligible = StructuredOutputCapabilities.resolvesToStrictSchema(
                config.structuredOutputMode(),
                config.strictJsonSchemaAdvertised(),
                config.baseUrl(),
                config.toolless());
        MultimodalCapability multimodalCap =
                MultimodalCapabilities.defaultCapabilityForModel(config.provider(), config.modelName());
        return CapabilityAwareChatModel.withCapabilities(model, eligible, multimodalCap);
    }

    /** Convenience: builds an OpenAI-compatible ChatModel from direct parameters. */
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

    /** Builds an OpenAI-compatible ChatModel with optional custom request parameters. */
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

    /** Applies OpenRouter-specific HTTP-Referer and X-Title headers to a builder. */
    public static OpenAiChatModel.OpenAiChatModelBuilder withOpenRouterHeaders(
            OpenAiChatModel.OpenAiChatModelBuilder builder, String siteUrl, String appName) {
        return builder.customHeaders(Map.of("HTTP-Referer", siteUrl, "X-Title", appName));
    }

    /** Check if model name defaults to thinking mode and returns {@code reasoning_content}. */
    public static boolean isDeepSeekThinkingModel(String modelName) {
        if (modelName == null || modelName.isBlank()) return false;
        String lower = modelName.trim().toLowerCase();
        return lower.contains("deepseek-v") || lower.contains("deepseek-r");
    }

    /** Returns map with DeepSeek thinking workaround if model is DeepSeek thinking-capable. */
    public static Map<String, Object> deepSeekWorkaroundParams(String modelName) {
        return isDeepSeekThinkingModel(modelName) ? Map.of("thinking", Map.of("type", "disabled")) : Map.of();
    }

    /** Parses a JSON string from {@code AMSHA_GENERIC_OPENAI_EXTRA_BODY} into a map. */
    public static Map<String, Object> parseExtraBody(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return EXTRA_BODY_MAPPER.readValue(json.trim(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            LOG.warnf("Failed to parse AMSHA_GENERIC_OPENAI_EXTRA_BODY '%s': %s", json, LogUtil.rootMessage(e));
            return Map.of();
        }
    }

    /** Merges auto-detected custom parameters with user-supplied extra-body params. */
    public static Map<String, Object> mergeCustomParameters(Map<String, Object> auto, Map<String, Object> user) {
        if (auto == null || auto.isEmpty()) return user == null ? Map.of() : user;
        if (user == null || user.isEmpty()) return auto;
        HashMap<String, Object> merged = new HashMap<>(auto);
        merged.putAll(user);
        return merged;
    }

    /** Builds a list of MultiModelChatModel.ModelEntry from comma-separated API keys and model names. */
    public static List<MultiModelChatModel.ModelEntry> buildMultiModelEntries(
            String keys, String models, String providerName, BiFunction<String, String, ChatModel> modelFactory) {
        String[] keyArr = Arrays.stream(keys.split(",", -1))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        String[] modelArr = Arrays.stream(models.split(",", -1))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        List<MultiModelChatModel.ModelEntry> entries = new ArrayList<>();
        if (keyArr.length == 1 && modelArr.length == 1) {
            entries.add(new MultiModelChatModel.ModelEntry(providerName, modelFactory.apply(keyArr[0], modelArr[0])));
        } else if (keyArr.length > 1 && modelArr.length == 1) {
            for (int i = 0; i < keyArr.length; i++) {
                entries.add(new MultiModelChatModel.ModelEntry(
                        providerName + "-key" + (i + 1), modelFactory.apply(keyArr[i], modelArr[0])));
            }
        } else if (keyArr.length == 1 && modelArr.length > 1) {
            for (String m : modelArr) {
                entries.add(
                        new MultiModelChatModel.ModelEntry(providerName + "-" + m, modelFactory.apply(keyArr[0], m)));
            }
        } else {
            int len = Math.min(keyArr.length, modelArr.length);
            if (keyArr.length != modelArr.length) {
                LOG.warnf(
                        "Provider %s: %d keys but %d models — pairing by index (using first %d)",
                        providerName, keyArr.length, modelArr.length, len);
            }
            for (int i = 0; i < len; i++) {
                entries.add(new MultiModelChatModel.ModelEntry(
                        providerName + "-key" + (i + 1) + "/" + modelArr[i],
                        modelFactory.apply(keyArr[i], modelArr[i])));
            }
        }
        return entries;
    }

    private static final Set<String> SUPPORTED_ADAPTERS = Set.of(
            "gemini",
            "anthropic",
            "claude",
            "claude-code",
            "agy",
            "antigravity",
            "kimchi",
            "foundry",
            "foundrylocal",
            "ollama",
            "codex",
            "openai",
            "groq",
            "cerebras",
            "sarvam",
            "opencode",
            "huggingface",
            "mistral",
            "cloudflare",
            "cohere",
            "nvidia",
            "omlx",
            "llamacpp",
            "openrouter",
            "generic-openai");

    @Override
    public boolean supportsAdapter(String provider) {
        if (provider == null) return false;
        return SUPPORTED_ADAPTERS.contains(provider.toLowerCase(Locale.ROOT));
    }

    @Override
    public ChatModel createForAdapter(
            String adapterId, String baseUrl, String apiKey, String model, AgentLlmConfig config) {
        String adapter = adapterId == null ? "" : adapterId.toLowerCase(Locale.ROOT);
        ChatModel created =
                switch (adapter) {
                    case "gemini" -> createGemini(baseUrl, apiKey, model, config);
                    case "anthropic", "claude" -> createAnthropic(baseUrl, apiKey, model, config);
                    case "claude-code" -> createClaudeCode(baseUrl, apiKey, model, config);
                    case "agy", "antigravity" -> createAgy(baseUrl, apiKey, model, config);
                    case "kimchi" -> createKimchi(baseUrl, apiKey, model, config);
                    case "foundry", "foundrylocal" -> createFoundry(baseUrl, model, config);
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
        boolean eligible = "openai".equals(adapter)
                && StructuredOutputCapabilities.resolvesToStrictSchema(
                        config.structuredOutputMode(), true, baseUrl, true);
        MultimodalCapability multimodalCap = MultimodalCapabilities.defaultCapabilityForModel(adapter, model);
        return CapabilityAwareChatModel.withCapabilities(created, eligible, multimodalCap);
    }

    private static GovernedTransportService resolveGovernedTransport() {
        try {
            ArcContainer container = Arc.container();
            if (container != null) {
                InstanceHandle<GovernedTransportService> handle = container.instance(GovernedTransportService.class);
                if (handle.isAvailable()) return handle.get();
            }
        } catch (Exception _) {
            // CDI not active
        }
        return null;
    }

    /** Builds an Antigravity/AGY ChatModel. */
    public static ChatModel createAgy(String baseUrl, String token, String model, AgentLlmConfig config) {
        return createAgy(baseUrl, token, model, config, resolveGovernedTransport());
    }

    /** Builds an Antigravity/AGY ChatModel with explicit governed transport. */
    public static ChatModel createAgy(
            String baseUrl,
            String token,
            String model,
            AgentLlmConfig config,
            GovernedTransportService governedTransport) {
        String modelName = (model == null || model.isBlank() || "mistral-small".equalsIgnoreCase(model))
                ? ModelDefaults.DEFAULT_AGY_MODEL
                : model;
        return new AgyResponsesChatModel(
                baseUrl,
                token,
                modelName,
                config.maxTokens(),
                config.timeout(),
                config.temperature(),
                governedTransport != null ? governedTransport : resolveGovernedTransport());
    }

    /** Builds a Claude Code ChatModel. */
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
        String modelName = (model == null || model.isBlank() || "mistral-small".equalsIgnoreCase(model))
                ? ModelDefaults.DEFAULT_CLAUDE_CODE_MODEL
                : model;
        return new ClaudeCodeResponsesChatModel(
                baseUrl,
                token,
                modelName,
                config.maxTokens(),
                config.timeout(),
                config.temperature(),
                governedTransport != null ? governedTransport : resolveGovernedTransport());
    }

    /** Builds a Codex ChatModel. */
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
        GoogleAiGeminiChatModel.GoogleAiGeminiChatModelBuilder builder = GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(config.temperature())
                .maxOutputTokens(config.maxTokens())
                .timeout(config.timeout())
                .logRequests(config.logHttp())
                .logResponses(config.logHttp());
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
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

    /** Builds a Kimchi ChatModel. */
    public static ChatModel createKimchi(String baseUrl, String token, String model, AgentLlmConfig config) {
        return createKimchi(baseUrl, token, model, config, resolveGovernedTransport());
    }

    /** Builds a Kimchi ChatModel with explicit governed transport. */
    public static ChatModel createKimchi(
            String baseUrl,
            String token,
            String model,
            AgentLlmConfig config,
            GovernedTransportService governedTransport) {
        String modelName = (model == null || model.isBlank() || "mistral-small".equalsIgnoreCase(model))
                ? ModelDefaults.DEFAULT_KIMCHI_MODEL
                : model;
        return new KimchiResponsesChatModel(
                baseUrl,
                token,
                modelName,
                config.maxTokens(),
                config.timeout(),
                config.temperature(),
                governedTransport != null ? governedTransport : resolveGovernedTransport());
    }

    /** Builds a Microsoft Foundry Local ChatModel. */
    public static ChatModel createFoundry(String baseUrl, String model, AgentLlmConfig config) {
        return createFoundry(baseUrl, model, config, resolveGovernedTransport());
    }

    /** Builds a Microsoft Foundry Local ChatModel with explicit governed transport. */
    public static ChatModel createFoundry(
            String baseUrl, String model, AgentLlmConfig config, GovernedTransportService governedTransport) {
        String modelName = (model == null || model.isBlank() || "mistral-small".equalsIgnoreCase(model))
                ? ModelDefaults.DEFAULT_FOUNDRY_MODEL
                : model;
        return new FoundryResponsesChatModel(
                baseUrl,
                modelName,
                config.maxTokens(),
                config.timeout(),
                config.temperature(),
                governedTransport != null ? governedTransport : resolveGovernedTransport());
    }
}

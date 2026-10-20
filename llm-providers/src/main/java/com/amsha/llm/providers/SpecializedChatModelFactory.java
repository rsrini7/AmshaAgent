package com.amsha.llm.providers;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;

import java.time.Duration;

import com.amsha.common.util.LogUtil;
import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.providers.transport.GovernedLangChainHttpClientBuilder;
import com.amsha.llm.providers.wrapper.AgyResponsesChatModel;
import com.amsha.llm.providers.wrapper.ClaudeCodeResponsesChatModel;
import com.amsha.llm.providers.wrapper.CodexResponsesChatModel;
import com.amsha.llm.providers.wrapper.FoundryResponsesChatModel;
import com.amsha.llm.providers.wrapper.KimchiResponsesChatModel;
import com.amsha.security.http.GovernedTransportService;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;

/**
 * Package-private factory for building specialized non-OpenAI and wrapper {@link ChatModel} instances.
 */
final class SpecializedChatModelFactory {

    private SpecializedChatModelFactory() {}

    static GovernedTransportService resolveGovernedTransport() {
        try {
            ArcContainer container = Arc.container();
            if (container != null) {
                InstanceHandle<GovernedTransportService> handle = container.instance(GovernedTransportService.class);
                if (handle.isAvailable()) return handle.get();
            }
        } catch (NoClassDefFoundError _) {
            // Standalone unit tests without CDI
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to resolve GovernedTransportService from CDI container: " + LogUtil.rootMessage(e), e);
        }
        return null;
    }

    static ChatModel createSpecializedAdapter(
            String adapter, String baseUrl, String apiKey, String model, AgentLlmConfig config) {
        return switch (adapter) {
            case ModelDefaults.PROVIDER_GEMINI -> createGemini(baseUrl, apiKey, model, config);
            case ModelDefaults.PROVIDER_ANTHROPIC, "claude" -> createAnthropic(baseUrl, apiKey, model, config);
            case ModelDefaults.PROVIDER_CLAUDE_CODE -> createClaudeCode(baseUrl, apiKey, model, config);
            case ModelDefaults.PROVIDER_AGY, "antigravity" -> createAgy(baseUrl, apiKey, model, config);
            case ModelDefaults.PROVIDER_KIMCHI -> createKimchi(baseUrl, apiKey, model, config);
            case ModelDefaults.PROVIDER_FOUNDRY, "foundrylocal" -> createFoundry(baseUrl, model, config);
            case ModelDefaults.PROVIDER_OLLAMA -> createOllama(baseUrl, model, config);
            case ModelDefaults.PROVIDER_CODEX -> createCodex(baseUrl, apiKey, null, model, config);
            default -> null;
        };
    }

    static ChatModel createAgy(String baseUrl, String token, String model, AgentLlmConfig config) {
        return createAgy(baseUrl, token, model, config, resolveGovernedTransport());
    }

    static ChatModel createAgy(
            String baseUrl,
            String token,
            String model,
            AgentLlmConfig config,
            GovernedTransportService governedTransport) {
        String modelName = (model == null || model.isBlank() || ModelDefaults.PLACEHOLDER_MODEL.equalsIgnoreCase(model))
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

    static ChatModel createClaudeCode(String baseUrl, String token, String model, AgentLlmConfig config) {
        return createClaudeCode(baseUrl, token, model, config, resolveGovernedTransport());
    }

    static ChatModel createClaudeCode(
            String baseUrl,
            String token,
            String model,
            AgentLlmConfig config,
            GovernedTransportService governedTransport) {
        String modelName = (model == null || model.isBlank() || ModelDefaults.PLACEHOLDER_MODEL.equalsIgnoreCase(model))
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

    static ChatModel createCodex(String baseUrl, String token, String accountId, String model, AgentLlmConfig config) {
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

    static ChatModel createCodex(
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

    static ChatModel createCodex(
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
        return ChatModelFactory.createOpenAi(
                baseUrl, token, model, maxTokens, timeout, maxRetries, temperature, logHttp);
    }

    static ChatModel createGemini(String baseUrl, String apiKey, String model, AgentLlmConfig config) {
        String modelName = (model == null || model.isBlank()) ? ModelDefaults.DEFAULT_GEMINI_MODEL : model;
        GoogleAiGeminiChatModel.GoogleAiGeminiChatModelBuilder builder = GoogleAiGeminiChatModel.builder()
                .httpClientBuilder(new GovernedLangChainHttpClientBuilder(resolveGovernedTransport()))
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

    static ChatModel createAnthropic(String baseUrl, String apiKey, String model, AgentLlmConfig config) {
        String modelName = (model == null || model.isBlank()) ? ModelDefaults.DEFAULT_CLAUDE_CODE_MODEL : model;
        AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder()
                .httpClientBuilder(new GovernedLangChainHttpClientBuilder(resolveGovernedTransport()))
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

    static ChatModel createOllama(String baseUrl, String model, AgentLlmConfig config) {
        String url = (baseUrl == null || baseUrl.isBlank()) ? ModelDefaults.DEFAULT_OLLAMA_BASE_URL : baseUrl;
        String modelName = (model == null || model.isBlank()) ? ModelDefaults.DEFAULT_OLLAMA_MODEL : model;
        return OllamaChatModel.builder()
                .httpClientBuilder(new GovernedLangChainHttpClientBuilder(resolveGovernedTransport()))
                .baseUrl(url)
                .modelName(modelName)
                .logRequests(config.logHttp())
                .logResponses(config.logHttp())
                .build();
    }

    static ChatModel createKimchi(String baseUrl, String token, String model, AgentLlmConfig config) {
        return createKimchi(baseUrl, token, model, config, resolveGovernedTransport());
    }

    static ChatModel createKimchi(
            String baseUrl,
            String token,
            String model,
            AgentLlmConfig config,
            GovernedTransportService governedTransport) {
        String modelName = (model == null || model.isBlank() || ModelDefaults.PLACEHOLDER_MODEL.equalsIgnoreCase(model))
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

    static ChatModel createFoundry(String baseUrl, String model, AgentLlmConfig config) {
        return createFoundry(baseUrl, model, config, resolveGovernedTransport());
    }

    static ChatModel createFoundry(
            String baseUrl, String model, AgentLlmConfig config, GovernedTransportService governedTransport) {
        String modelName = (model == null || model.isBlank() || ModelDefaults.PLACEHOLDER_MODEL.equalsIgnoreCase(model))
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

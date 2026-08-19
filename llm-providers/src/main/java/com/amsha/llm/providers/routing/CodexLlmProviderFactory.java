package com.amsha.llm.providers.routing;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;

import com.amsha.core.security.http.GovernedTransportService;
import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.RoutingChatModelProvider;
import com.amsha.llm.providers.ChatModelFactory;
import com.amsha.llm.providers.catalog.*;
import com.amsha.llm.providers.helper.*;
import com.amsha.llm.providers.reasoning.*;
import com.amsha.llm.providers.wrapper.*;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

/**
 * Routing chat model provider for OpenAI Codex / ChatGPT Plus CLI OAuth.
 * Used by classifier, compaction, and routing subsystems.
 */
@ApplicationScoped
@Unremovable
public class CodexLlmProviderFactory implements RoutingChatModelProvider {

    @Inject
    CodexTokenResolver tokenResolver;

    @Inject
    GovernedTransportService governedTransport;

    @ConfigProperty(name = "amsha.llm.log-http", defaultValue = "false")
    boolean logHttp;

    @Override
    public String providerId() {
        return ModelDefaults.PROVIDER_CODEX;
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        String token = tokenResolver.resolveToken(config.apiKey());
        String baseUrl = (config.baseUrl() != null && !config.baseUrl().isBlank())
                ? config.baseUrl()
                : ModelDefaults.DEFAULT_CODEX_BASE_URL;
        String rawModelName = (config.modelName() != null && !config.modelName().isBlank())
                ? config.modelName()
                : ModelDefaults.DEFAULT_CODEX_MODEL;
        String modelName = rawModelName.contains(",") ? rawModelName.split(",")[0].trim() : rawModelName;

        if (baseUrl.contains("chatgpt.com/backend-api/codex")) {
            String accountId = tokenResolver.resolveAccountId().orElse(null);
            return ChatModelFactory.createCodex(
                    baseUrl,
                    token,
                    accountId,
                    modelName,
                    config.maxTokens().orElse(null),
                    config.timeout().orElse(Duration.ofMinutes(3)),
                    config.maxRetries().orElse(2),
                    0.0,
                    logHttp,
                    governedTransport);
        }

        OpenAiChatModel.OpenAiChatModelBuilder builder = ChatModelFactory.openAiBuilder(
                baseUrl,
                token,
                modelName,
                config.maxTokens().orElse(null),
                config.timeout().orElse(Duration.ofMinutes(3)),
                config.maxRetries().orElse(2),
                0.0,
                logHttp,
                logHttp);

        ChatModelFactory.applyJsonResponseFormat(builder, config.structuredOutputMode(), config.toolless(), baseUrl);

        return builder.build();
    }
}

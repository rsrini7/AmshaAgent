package com.amsha.llm.providers.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.core.routing.MultiModelChatModel;
import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.LlmConfigKeys;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.llm.providers.ChatModelFactory;
import com.amsha.llm.providers.helper.CodexTokenResolver;
import com.amsha.security.http.GovernedTransportService;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

/**
 * Agent-reasoning provider for OpenAI Codex / ChatGPT Plus CLI OAuth.
 */
@ApplicationScoped
@Unremovable
public class CodexProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @Inject
    CodexTokenResolver tokenResolver;

    @Inject
    GovernedTransportService governedTransport;

    @ConfigProperty(name = LlmConfigKeys.CODEX_API_KEY)
    Optional<String> apiKey;

    @ConfigProperty(name = LlmConfigKeys.CODEX_BASE_URL, defaultValue = ModelDefaults.DEFAULT_CODEX_BASE_URL)
    String baseUrl;

    @ConfigProperty(name = LlmConfigKeys.CODEX_MODEL, defaultValue = ModelDefaults.DEFAULT_CODEX_MODEL)
    String modelName;

    @Override
    public String providerName() {
        return ModelDefaults.PROVIDER_CODEX;
    }

    @Override
    public StructuredOutputMode structuredOutputMode() {
        return agentConfig.structuredOutputMode();
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public ChatModel create() {
        return createModel(modelName);
    }

    @Override
    public ChatModel create(String customModelName) {
        return createModel(customModelName);
    }

    private ChatModel createModel(String model) {
        String token = tokenResolver != null ? tokenResolver.resolveToken(apiKey) : null;
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Codex provider requires an active login. "
                    + "Please run 'codex login' in your local terminal first, or set AMSHA_CODEX_API_KEY.");
        }
        String accountId =
                tokenResolver != null ? tokenResolver.resolveAccountId().orElse(null) : null;
        List<MultiModelChatModel.ModelEntry> entries = ChatModelFactory.buildMultiModelEntries(
                token,
                model,
                ModelDefaults.PROVIDER_CODEX,
                (entryKey, entryModel) -> ChatModelFactory.createCodex(
                        baseUrl,
                        entryKey,
                        accountId,
                        entryModel,
                        agentConfig.maxTokens(),
                        agentConfig.timeout(),
                        agentConfig.maxRetries(),
                        agentConfig.temperature(),
                        agentConfig.logHttp(),
                        governedTransport));
        if (entries.size() == 1) return entries.getFirst().model();
        return new MultiModelChatModel(ModelDefaults.PROVIDER_CODEX, entries, 5);
    }
}

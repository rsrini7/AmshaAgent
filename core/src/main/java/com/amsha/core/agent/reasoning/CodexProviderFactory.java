package com.amsha.core.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.core.routing.ChatModelFactory;
import com.amsha.core.routing.CodexTokenResolver;
import com.amsha.core.routing.ModelDefaults;
import com.amsha.core.routing.MultiModelChatModel;
import com.amsha.core.security.http.GovernedTransportService;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Agent-reasoning provider for OpenAI Codex / ChatGPT Plus CLI OAuth.
 */
@ApplicationScoped
public class CodexProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @Inject
    CodexTokenResolver tokenResolver;

    @Inject
    GovernedTransportService governedTransport;

    @ConfigProperty(name = "amsha.llm.codex.api-key")
    Optional<String> apiKey;

    @ConfigProperty(name = "amsha.llm.codex.base-url", defaultValue = ModelDefaults.DEFAULT_CODEX_BASE_URL)
    String baseUrl;

    @ConfigProperty(name = "amsha.llm.codex.model", defaultValue = ModelDefaults.DEFAULT_CODEX_MODEL)
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

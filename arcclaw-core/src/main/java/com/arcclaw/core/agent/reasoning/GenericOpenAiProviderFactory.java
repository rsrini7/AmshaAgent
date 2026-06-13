package com.arcclaw.core.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.arcclaw.core.routing.ChatModelFactory;
import com.arcclaw.core.routing.MultiModelChatModel;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * {@link AgentChatModelProvider} for any OpenAI-compatible endpoint.
 * Fully configurable via {@code ARCCLAW_GENERIC_OPENAI_BASE_URL},
 * {@code ARCCLAW_GENERIC_OPENAI_API_KEY}, and {@code ARCCLAW_GENERIC_OPENAI_MODEL}.
 * Supports comma-separated API keys and model names for multi-model fallback.
 */
@ApplicationScoped
public class GenericOpenAiProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "ARCCLAW_GENERIC_OPENAI_API_KEY")
    Optional<String> apiKey;

    @ConfigProperty(name = "ARCCLAW_GENERIC_OPENAI_BASE_URL", defaultValue = "")
    String baseUrl;

    @ConfigProperty(name = "ARCCLAW_GENERIC_OPENAI_MODEL", defaultValue = "")
    String modelName;

    @ConfigProperty(name = "ARCCLAW_GENERIC_OPENAI_EXTRA_BODY")
    Optional<String> extraBody;

    @Override
    public String providerName() {
        return "generic-openai";
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
        if (baseUrl.isBlank()) {
            throw new IllegalStateException("generic-openai requires ARCCLAW_GENERIC_OPENAI_BASE_URL");
        }
        String key = apiKey.orElseThrow(
                () -> new IllegalStateException("generic-openai requires ARCCLAW_GENERIC_OPENAI_API_KEY"));
        if (modelName.isBlank()) {
            throw new IllegalStateException("generic-openai requires ARCCLAW_GENERIC_OPENAI_MODEL");
        }
        List<MultiModelChatModel.ModelEntry> entries = ChatModelFactory.buildMultiModelEntries(
                key,
                modelName,
                "generic-openai",
                (k, m) -> ChatModelFactory.createOpenAi(
                        baseUrl,
                        k,
                        m,
                        agentConfig.maxTokens(),
                        agentConfig.timeout(),
                        agentConfig.maxRetries(),
                        agentConfig.temperature(),
                        agentConfig.logHttp(),
                        ChatModelFactory.mergeCustomParameters(
                                ChatModelFactory.deepSeekWorkaroundParams(m),
                                ChatModelFactory.parseExtraBody(extraBody.orElse("")))));
        if (entries.size() == 1) {
            return entries.get(0).model();
        }
        return new MultiModelChatModel("generic-openai", entries, 5);
    }
}

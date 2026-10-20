package com.amsha.llm.providers.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.common.config.ConfigKeys;
import com.amsha.core.routing.CapabilityAwareChatModel;
import com.amsha.core.routing.MultiModelChatModel;
import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.MultimodalCapabilities;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.llm.providers.ChatModelFactory;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

/**
 * {@link AgentChatModelProvider} for any OpenAI-compatible endpoint.
 * Fully configurable via {@code AMSHA_GENERIC_OPENAI_BASE_URL},
 * {@code AMSHA_GENERIC_OPENAI_API_KEY}, and {@code AMSHA_GENERIC_OPENAI_MODEL}.
 * Supports comma-separated API keys and model names for multi-model fallback.
 */
@ApplicationScoped
@Unremovable
public class GenericOpenAiProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = ConfigKeys.GENERIC_OPENAI_API_KEY)
    Optional<String> apiKey;

    @ConfigProperty(name = ConfigKeys.GENERIC_OPENAI_BASE_URL)
    Optional<String> baseUrl;

    @ConfigProperty(name = ConfigKeys.GENERIC_OPENAI_MODEL)
    Optional<String> modelName;

    @ConfigProperty(name = "AMSHA_GENERIC_OPENAI_EXTRA_BODY")
    Optional<String> extraBody;

    @Override
    public String providerName() {
        return ModelDefaults.PROVIDER_GENERIC_OPENAI;
    }

    @Override
    public StructuredOutputMode structuredOutputMode() {
        return agentConfig.structuredOutputMode();
    }

    @Override
    public String baseUrl() {
        return baseUrl.orElse("");
    }

    @Override
    public ChatModel create() {
        return doCreate(modelName.orElseThrow(
                () -> new IllegalStateException("generic-openai requires AMSHA_GENERIC_OPENAI_MODEL")));
    }

    @Override
    public ChatModel create(String customModelName) {
        return doCreate(customModelName);
    }

    private ChatModel doCreate(String overrideModel) {
        String resolvedBaseUrl = baseUrl.orElse("");
        if (resolvedBaseUrl.isBlank()) {
            throw new IllegalStateException("generic-openai requires AMSHA_GENERIC_OPENAI_BASE_URL");
        }
        String key = apiKey.orElseThrow(
                () -> new IllegalStateException("generic-openai requires AMSHA_GENERIC_OPENAI_API_KEY"));
        String resolvedModel = overrideModel;
        if (resolvedModel.isBlank()) {
            throw new IllegalStateException("generic-openai requires AMSHA_GENERIC_OPENAI_MODEL");
        }
        List<MultiModelChatModel.ModelEntry> entries = ChatModelFactory.buildMultiModelEntries(
                key, resolvedModel, ModelDefaults.PROVIDER_GENERIC_OPENAI, (k, m) -> {
                    ChatModel raw = ChatModelFactory.createOpenAi(
                            resolvedBaseUrl,
                            k,
                            m,
                            agentConfig.maxTokens(),
                            agentConfig.timeout(),
                            agentConfig.maxRetries(),
                            agentConfig.temperature(),
                            agentConfig.logHttp(),
                            ChatModelFactory.mergeCustomParameters(
                                    ChatModelFactory.deepSeekWorkaroundParams(m),
                                    ChatModelFactory.parseExtraBody(extraBody.orElse(""))));
                    MultimodalCapability cap =
                            MultimodalCapabilities.defaultCapabilityForModel(ModelDefaults.PROVIDER_GENERIC_OPENAI, m);
                    return CapabilityAwareChatModel.withCapabilities(raw, false, cap);
                });
        if (entries.size() == 1) {
            return entries.get(0).model();
        }
        return new MultiModelChatModel(ModelDefaults.PROVIDER_GENERIC_OPENAI, entries, 5);
    }
}

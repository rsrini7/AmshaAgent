package com.amsha.llm.providers.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.routing.CapabilityAwareChatModel;
import com.amsha.core.routing.MultiModelChatModel;
import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.LlmConfigKeys;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.MultimodalCapabilities;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.llm.providers.ChatModelFactory;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

/** Agent-reasoning provider for Sarvam's v1 OpenAI-compatible chat endpoint. */
@ApplicationScoped
@Unremovable
public class SarvamProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = LlmConfigKeys.SARVAM_API_KEY)
    Optional<String> apiKey;

    @ConfigProperty(name = LlmConfigKeys.SARVAM_BASE_URL, defaultValue = ModelDefaults.DEFAULT_SARVAM_BASE_URL)
    String baseUrl;

    @ConfigProperty(name = LlmConfigKeys.SARVAM_MODEL, defaultValue = ModelDefaults.DEFAULT_SARVAM_MODEL)
    String modelName;

    @Override
    public String providerName() {
        return ModelDefaults.PROVIDER_SARVAM;
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
        String key = apiKey.orElseThrow(() -> new IllegalStateException("Sarvam requires AMSHA_SARVAM_API_KEY"));
        String cleanKey = key.replaceAll("[\\p{Cntrl}\\s\"']", "").trim();
        List<MultiModelChatModel.ModelEntry> entries = ChatModelFactory.buildMultiModelEntries(
                cleanKey, model, ModelDefaults.PROVIDER_SARVAM, (entryKey, entryModel) -> {
                    var builder = ChatModelFactory.openAiBuilder(
                            baseUrl,
                            entryKey,
                            entryModel,
                            agentConfig.maxTokens(),
                            agentConfig.timeout(),
                            agentConfig.maxRetries(),
                            agentConfig.temperature(),
                            agentConfig.logHttp(),
                            agentConfig.logHttp());
                    builder.customHeaders(Map.of("api-subscription-key", entryKey));
                    ChatModel raw = builder.build();
                    MultimodalCapability cap =
                            MultimodalCapabilities.defaultCapabilityForModel(ModelDefaults.PROVIDER_SARVAM, entryModel);
                    return CapabilityAwareChatModel.withCapabilities(raw, false, cap);
                });
        if (entries.size() == 1) return entries.getFirst().model();
        return new MultiModelChatModel(ModelDefaults.PROVIDER_SARVAM, entries, 5);
    }
}

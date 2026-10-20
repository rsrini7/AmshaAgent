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
import com.amsha.llm.api.StructuredOutputCapabilities;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.llm.providers.ChatModelFactory;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class OpenAiProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = ConfigKeys.OPENAI_API_KEY)
    Optional<String> apiKey;

    @ConfigProperty(name = ConfigKeys.OPENAI_BASE_URL, defaultValue = ModelDefaults.DEFAULT_OPENAI_BASE_URL)
    String baseUrl;

    @ConfigProperty(name = ConfigKeys.OPENAI_MODEL)
    Optional<String> modelName;

    @Override
    public String providerName() {
        return ModelDefaults.PROVIDER_OPENAI;
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
    public boolean supportsStrictJsonSchema() {
        return true;
    }

    @Override
    public ChatModel create() {
        return doCreate(modelName.orElseThrow(() -> new IllegalStateException("OpenAI requires AMSHA_OPENAI_MODEL")));
    }

    @Override
    public ChatModel create(String customModelName) {
        return doCreate(customModelName);
    }

    private ChatModel doCreate(String overrideModel) {
        String key = apiKey.orElseThrow(() -> new IllegalStateException("OpenAI requires AMSHA_OPENAI_API_KEY"));
        String model = overrideModel;
        boolean strictEligible = strictSchemaEligible();
        List<MultiModelChatModel.ModelEntry> entries =
                ChatModelFactory.buildMultiModelEntries(key, model, ModelDefaults.PROVIDER_OPENAI, (k, m) -> {
                    ChatModel raw = ChatModelFactory.createOpenAi(
                            baseUrl,
                            k,
                            m,
                            agentConfig.maxTokens(),
                            agentConfig.timeout(),
                            agentConfig.maxRetries(),
                            agentConfig.temperature(),
                            agentConfig.logHttp());
                    MultimodalCapability cap =
                            MultimodalCapabilities.defaultCapabilityForModel(ModelDefaults.PROVIDER_OPENAI, m);
                    return CapabilityAwareChatModel.withCapabilities(raw, strictEligible, cap);
                });
        if (entries.size() == 1) return entries.get(0).model();
        return new MultiModelChatModel(ModelDefaults.PROVIDER_OPENAI, entries, 5);
    }

    private boolean strictSchemaEligible() {
        return StructuredOutputCapabilities.resolvesToStrictSchema(
                agentConfig.structuredOutputMode(), supportsStrictJsonSchema(), baseUrl, true);
    }
}

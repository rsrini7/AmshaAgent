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
import com.amsha.llm.api.LlmConfigKeys;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.MultimodalCapabilities;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.llm.providers.ChatModelFactory;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class OpenRouterProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = ConfigKeys.OPENROUTER_API_KEY)
    Optional<String> apiKey;

    @ConfigProperty(name = ConfigKeys.OPENROUTER_BASE_URL, defaultValue = ModelDefaults.DEFAULT_OPENROUTER_BASE_URL)
    String baseUrl;

    @ConfigProperty(name = ConfigKeys.OPENROUTER_MODEL, defaultValue = ModelDefaults.DEFAULT_OPENROUTER_MODEL)
    String modelName;

    @ConfigProperty(name = LlmConfigKeys.OPENROUTER_SITE_URL, defaultValue = "https://github.com/rsrini7/amsha")
    String siteUrl;

    @ConfigProperty(name = LlmConfigKeys.OPENROUTER_APP_NAME, defaultValue = "Amsha Agent")
    String appName;

    @Override
    public String providerName() {
        return ModelDefaults.PROVIDER_OPENROUTER;
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
        return doCreate(null);
    }

    @Override
    public ChatModel create(String customModelName) {
        return doCreate(customModelName);
    }

    private ChatModel doCreate(String overrideModel) {
        String key =
                apiKey.orElseThrow(() -> new IllegalStateException("OpenRouter requires AMSHA_OPENROUTER_API_KEY"));
        List<MultiModelChatModel.ModelEntry> entries = ChatModelFactory.buildMultiModelEntries(
                key, overrideModel != null ? overrideModel : modelName, ModelDefaults.PROVIDER_OPENROUTER, (k, m) -> {
                    ChatModel raw = ChatModelFactory.withOpenRouterHeaders(
                                    ChatModelFactory.openAiBuilder(
                                            baseUrl,
                                            k,
                                            m,
                                            agentConfig.maxTokens(),
                                            agentConfig.timeout(),
                                            agentConfig.maxRetries(),
                                            agentConfig.temperature(),
                                            agentConfig.logHttp(),
                                            agentConfig.logHttp()),
                                    siteUrl,
                                    appName)
                            .build();
                    MultimodalCapability cap =
                            MultimodalCapabilities.defaultCapabilityForModel(ModelDefaults.PROVIDER_OPENROUTER, m);
                    return CapabilityAwareChatModel.withCapabilities(raw, false, cap);
                });
        if (entries.size() == 1) return entries.get(0).model();
        return new MultiModelChatModel(ModelDefaults.PROVIDER_OPENROUTER, entries, 5);
    }
}

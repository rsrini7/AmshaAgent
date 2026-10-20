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
import com.amsha.llm.providers.helper.KimchiTokenResolver;
import com.amsha.llm.providers.wrapper.KimchiResponsesChatModel;
import com.amsha.security.http.GovernedTransportService;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

/**
 * {@link AgentChatModelProvider} for Kimchi (kimchi.dev) models using Kimchi CLI credentials.
 * Mirrors {@link com.amsha.core.routing.KimchiLlmProviderFactory} for the agent reasoning engine.
 */
@ApplicationScoped
@Unremovable
public class KimchiProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @Inject
    KimchiTokenResolver tokenResolver;

    @Inject
    GovernedTransportService governedTransport;

    @ConfigProperty(name = LlmConfigKeys.KIMCHI_MODEL, defaultValue = ModelDefaults.DEFAULT_KIMCHI_MODEL)
    String modelName;

    @ConfigProperty(name = LlmConfigKeys.KIMCHI_BASE_URL, defaultValue = ModelDefaults.DEFAULT_KIMCHI_BASE_URL)
    String baseUrl;

    @ConfigProperty(name = LlmConfigKeys.KIMCHI_API_KEY)
    Optional<String> apiKey;

    @Override
    public String providerName() {
        return ModelDefaults.PROVIDER_KIMCHI;
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
        String key = tokenResolver.resolveToken(apiKey);

        String resolvedModel = (overrideModel != null && !overrideModel.isBlank()) ? overrideModel : modelName;
        if (resolvedModel == null
                || resolvedModel.isBlank()
                || ModelDefaults.PLACEHOLDER_MODEL.equalsIgnoreCase(resolvedModel)) {
            resolvedModel = ModelDefaults.DEFAULT_KIMCHI_MODEL;
        }

        List<MultiModelChatModel.ModelEntry> entries = ChatModelFactory.buildMultiModelEntries(
                key,
                resolvedModel,
                ModelDefaults.PROVIDER_KIMCHI,
                (k, m) -> new KimchiResponsesChatModel(
                        baseUrl,
                        k,
                        m,
                        agentConfig.maxTokens(),
                        agentConfig.timeout(),
                        agentConfig.temperature(),
                        governedTransport));

        if (entries.size() == 1) return entries.get(0).model();
        return new MultiModelChatModel(ModelDefaults.PROVIDER_KIMCHI, entries, 5);
    }
}

package com.amsha.llm.providers.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

import com.amsha.core.routing.MultiModelChatModel;
import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.llm.providers.ChatModelFactory;
import com.amsha.llm.providers.wrapper.FoundryResponsesChatModel;
import com.amsha.security.http.GovernedTransportService;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

/**
 * {@link AgentChatModelProvider} for Microsoft Foundry Local models using Foundry daemon or CLI.
 * Mirrors {@link com.amsha.core.routing.FoundryLlmProviderFactory} for the agent reasoning engine.
 */
@ApplicationScoped
@Unremovable
public class FoundryProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @Inject
    GovernedTransportService governedTransport;

    @ConfigProperty(name = "amsha.llm.foundry.model", defaultValue = ModelDefaults.DEFAULT_FOUNDRY_MODEL)
    String modelName;

    @ConfigProperty(name = "amsha.llm.foundry.base-url", defaultValue = ModelDefaults.DEFAULT_FOUNDRY_BASE_URL)
    String baseUrl;

    @Override
    public String providerName() {
        return ModelDefaults.PROVIDER_FOUNDRY;
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
        String resolvedModel = (overrideModel != null && !overrideModel.isBlank()) ? overrideModel : modelName;
        if (resolvedModel == null || resolvedModel.isBlank() || "mistral-small".equalsIgnoreCase(resolvedModel)) {
            resolvedModel = ModelDefaults.DEFAULT_FOUNDRY_MODEL;
        }

        List<MultiModelChatModel.ModelEntry> entries = ChatModelFactory.buildMultiModelEntries(
                "not-needed",
                resolvedModel,
                ModelDefaults.PROVIDER_FOUNDRY,
                (_, m) -> new FoundryResponsesChatModel(
                        baseUrl,
                        m,
                        agentConfig.maxTokens(),
                        agentConfig.timeout(),
                        agentConfig.temperature(),
                        governedTransport));

        if (entries.size() == 1) return entries.get(0).model();
        return new MultiModelChatModel(ModelDefaults.PROVIDER_FOUNDRY, entries, 5);
    }
}

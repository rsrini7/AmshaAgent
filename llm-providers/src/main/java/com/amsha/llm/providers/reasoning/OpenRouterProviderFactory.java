package com.amsha.llm.providers.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.amsha.core.routing.MultiModelChatModel;
import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.llm.providers.ChatModelFactory;
import com.amsha.llm.providers.catalog.*;
import com.amsha.llm.providers.helper.*;
import com.amsha.llm.providers.routing.*;
import com.amsha.llm.providers.wrapper.*;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class OpenRouterProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "AMSHA_OPENROUTER_API_KEY")
    Optional<String> apiKey;

    @ConfigProperty(name = "AMSHA_OPENROUTER_BASE_URL", defaultValue = ModelDefaults.DEFAULT_OPENROUTER_BASE_URL)
    String baseUrl;

    @ConfigProperty(name = "AMSHA_OPENROUTER_MODEL", defaultValue = ModelDefaults.DEFAULT_OPENROUTER_MODEL)
    String modelName;

    @ConfigProperty(name = "amsha.llm.openrouter.site-url", defaultValue = "https://github.com/rsrini7/amsha")
    String siteUrl;

    @ConfigProperty(name = "amsha.llm.openrouter.app-name", defaultValue = "Amsha Agent")
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
                key,
                overrideModel != null ? overrideModel : modelName,
                "openrouter",
                (k, m) -> ChatModelFactory.withOpenRouterHeaders(
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
                        .build());
        if (entries.size() == 1) return entries.get(0).model();
        return new MultiModelChatModel("openrouter", entries, 5);
    }
}

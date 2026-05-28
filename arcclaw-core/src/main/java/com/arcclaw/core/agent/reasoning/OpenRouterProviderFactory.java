package com.arcclaw.core.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

import com.arcclaw.core.routing.ChatModelFactory;
import com.arcclaw.core.routing.MultiModelChatModel;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class OpenRouterProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @ConfigProperty(name = "ARCCLAW_OPENROUTER_API_KEY")
    Optional<String> apiKey;

    @ConfigProperty(name = "ARCCLAW_OPENROUTER_BASE_URL", defaultValue = "https://openrouter.ai/api/v1")
    String baseUrl;

    @ConfigProperty(name = "ARCCLAW_OPENROUTER_MODEL", defaultValue = "openai/gpt-oss-20b:free")
    String modelName;

    @ConfigProperty(name = "arcclaw.llm.openrouter.site-url", defaultValue = "https://github.com/rsrini7/arcclaw")
    String siteUrl;

    @ConfigProperty(name = "arcclaw.llm.openrouter.app-name", defaultValue = "ArcClaw")
    String appName;

    @Override
    public String providerName() {
        return "openrouter";
    }

    @Override
    public StructuredOutputMode structuredOutputMode() {
        return agentConfig.structuredOutputMode();
    }

    @Override
    public ChatModel create() {
        String key =
                apiKey.orElseThrow(() -> new IllegalStateException("OpenRouter requires ARCCLAW_OPENROUTER_API_KEY"));
        List<MultiModelChatModel.ModelEntry> entries = ChatModelFactory.buildMultiModelEntries(
                key,
                modelName,
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

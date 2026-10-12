package com.amsha.llm.providers.reasoning;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;

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
import com.amsha.llm.providers.transport.GovernedLangChainHttpClientBuilder;
import com.amsha.security.http.GovernedTransportService;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class GeminiProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @Inject
    GovernedTransportService governedTransport;

    @ConfigProperty(name = ConfigKeys.GEMINI_API_KEY)
    Optional<String> apiKey;

    @ConfigProperty(name = ConfigKeys.GEMINI_BASE_URL, defaultValue = ModelDefaults.DEFAULT_GEMINI_BASE_URL)
    String baseUrl;

    @ConfigProperty(name = ConfigKeys.GEMINI_MODEL, defaultValue = ModelDefaults.DEFAULT_GEMINI_MODEL)
    String modelName;

    @ConfigProperty(name = LlmConfigKeys.GEMINI_LOG_REQUESTS, defaultValue = "false")
    boolean logRequests;

    @ConfigProperty(name = LlmConfigKeys.GEMINI_LOG_RESPONSES, defaultValue = "false")
    boolean logResponses;

    @Override
    public String providerName() {
        return ModelDefaults.PROVIDER_GEMINI;
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
        String key = apiKey.orElseThrow(() -> new IllegalStateException("Gemini requires AMSHA_GEMINI_API_KEY"));
        String cleanKey = key.replaceAll("[\\p{Cntrl}\\s\"']", "").trim();
        String effectiveModel = overrideModel != null ? overrideModel : modelName;

        List<MultiModelChatModel.ModelEntry> entries = ChatModelFactory.buildMultiModelEntries(
                cleanKey, effectiveModel, ModelDefaults.PROVIDER_GEMINI, (entryKey, entryModel) -> {
                    var builder = GoogleAiGeminiChatModel.builder()
                            .httpClientBuilder(new GovernedLangChainHttpClientBuilder(governedTransport))
                            .apiKey(entryKey)
                            .modelName(entryModel)
                            .temperature(agentConfig.temperature())
                            .maxOutputTokens(agentConfig.maxTokens())
                            .timeout(agentConfig.timeout())
                            .logRequests(logRequests)
                            .logResponses(logResponses);
                    if (baseUrl != null && !baseUrl.isBlank()) {
                        builder.baseUrl(baseUrl);
                    }
                    ChatModel raw = builder.build();
                    MultimodalCapability cap =
                            MultimodalCapabilities.defaultCapabilityForModel(ModelDefaults.PROVIDER_GEMINI, entryModel);
                    return CapabilityAwareChatModel.withCapabilities(raw, false, cap);
                });
        if (entries.size() == 1) return entries.getFirst().model();
        return new MultiModelChatModel(ModelDefaults.PROVIDER_GEMINI, entries, 5);
    }
}

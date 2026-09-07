package com.amsha.llm.providers.reasoning;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.amsha.core.routing.CapabilityAwareChatModel;
import com.amsha.llm.api.AgentChatModelProvider;
import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.MultimodalCapabilities;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.llm.providers.transport.GovernedLangChainHttpClientBuilder;
import com.amsha.llm.providers.transport.GovernedLangChainHttpClientBuilderFactory;
import com.amsha.security.http.GovernedTransportService;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.Unremovable;

/**
 * Agent-level provider for llama.cpp server (OpenAI-compatible API).
 * Used by the infinite-loop agent for main reasoning.
 */
@ApplicationScoped
@Unremovable
public class LlamaCppProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @Inject
    @Any
    Instance<GovernedTransportService> governedTransportInstance;

    private GovernedTransportService directTransport;

    @ConfigProperty(name = "AMSHA_LLAMACPP_BASE_URL", defaultValue = ModelDefaults.DEFAULT_LLAMACPP_BASE_URL)
    String baseUrl;

    @ConfigProperty(name = "AMSHA_LLAMACPP_MODEL", defaultValue = ModelDefaults.DEFAULT_LLAMACPP_MODEL)
    String modelId;

    public LlamaCppProviderFactory() {}

    public LlamaCppProviderFactory(AgentLlmConfig agentConfig, String baseUrl, String modelId) {
        this(agentConfig, baseUrl, modelId, null);
    }

    public LlamaCppProviderFactory(
            AgentLlmConfig agentConfig, String baseUrl, String modelId, GovernedTransportService transport) {
        this.agentConfig = agentConfig;
        this.baseUrl = baseUrl;
        this.modelId = modelId;
        this.directTransport = transport;
    }

    @Override
    public String providerName() {
        return ModelDefaults.PROVIDER_LLAMACPP;
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
        GovernedLangChainHttpClientBuilderFactory.ensureConfigured();
        GovernedTransportService transport = directTransport != null
                ? directTransport
                : (governedTransportInstance != null && governedTransportInstance.isResolvable()
                        ? governedTransportInstance.get()
                        : null);
        String resolvedModel = overrideModel != null ? overrideModel : modelId;
        ChatModel raw = OpenAiChatModel.builder()
                .httpClientBuilder(new GovernedLangChainHttpClientBuilder(transport))
                .baseUrl(baseUrl)
                .apiKey("not-needed")
                .modelName(resolvedModel)
                .logRequests(agentConfig.logHttp())
                .logResponses(agentConfig.logHttp())
                .temperature(agentConfig.temperature())
                .build();
        MultimodalCapability cap =
                MultimodalCapabilities.defaultCapabilityForModel(ModelDefaults.PROVIDER_LLAMACPP, resolvedModel);
        return CapabilityAwareChatModel.withCapabilities(raw, false, cap);
    }
}

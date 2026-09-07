package com.amsha.llm.providers.reasoning;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;

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

@ApplicationScoped
@Unremovable
public class OllamaProviderFactory implements AgentChatModelProvider {

    @Inject
    AgentLlmConfig agentConfig;

    @Inject
    @Any
    Instance<GovernedTransportService> governedTransportInstance;

    private GovernedTransportService directTransport;

    @ConfigProperty(name = "quarkus.langchain4j.ollama.base-url", defaultValue = ModelDefaults.DEFAULT_OLLAMA_BASE_URL)
    String baseUrl;

    @ConfigProperty(
            name = "quarkus.langchain4j.ollama.chat-model.model-id",
            defaultValue = ModelDefaults.DEFAULT_OLLAMA_MODEL)
    String modelId;

    public OllamaProviderFactory() {}

    public OllamaProviderFactory(AgentLlmConfig agentConfig, String baseUrl, String modelId) {
        this(agentConfig, baseUrl, modelId, null);
    }

    public OllamaProviderFactory(
            AgentLlmConfig agentConfig, String baseUrl, String modelId, GovernedTransportService transport) {
        this.agentConfig = agentConfig;
        this.baseUrl = baseUrl;
        this.modelId = modelId;
        this.directTransport = transport;
    }

    @Override
    public String providerName() {
        return ModelDefaults.PROVIDER_OLLAMA;
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
        ChatModel raw = OllamaChatModel.builder()
                .httpClientBuilder(new GovernedLangChainHttpClientBuilder(transport))
                .baseUrl(baseUrl)
                .modelName(resolvedModel)
                .logRequests(agentConfig.logHttp())
                .logResponses(agentConfig.logHttp())
                .build();
        MultimodalCapability cap =
                MultimodalCapabilities.defaultCapabilityForModel(ModelDefaults.PROVIDER_OLLAMA, resolvedModel);
        return CapabilityAwareChatModel.withCapabilities(raw, false, cap);
    }
}

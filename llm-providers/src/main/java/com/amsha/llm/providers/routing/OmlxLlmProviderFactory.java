package com.amsha.llm.providers.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;

import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.RoutingChatModelProvider;
import com.amsha.llm.providers.ChatModelFactory;

import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class OmlxLlmProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() {
        return ModelDefaults.PROVIDER_OMLX;
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        return ChatModelFactory.createFromConfig(config);
    }
}

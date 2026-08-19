package com.amsha.llm.providers.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;

import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.RoutingChatModelProvider;
import com.amsha.llm.providers.ChatModelFactory;
import com.amsha.llm.providers.catalog.*;
import com.amsha.llm.providers.helper.*;
import com.amsha.llm.providers.reasoning.*;
import com.amsha.llm.providers.wrapper.*;

import io.quarkus.arc.Unremovable;

@ApplicationScoped
@Unremovable
public class OpenAiLlmProviderFactory implements RoutingChatModelProvider {

    @Override
    public String providerId() {
        return ModelDefaults.PROVIDER_OPENAI;
    }

    @Override
    public ChatModel create(LlmModelConfig config) {
        return ChatModelFactory.createFromConfig(config);
    }
}

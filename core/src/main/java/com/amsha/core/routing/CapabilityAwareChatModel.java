package com.amsha.core.routing;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.amsha.llm.api.StrictJsonSchemaCapable;

/**
 * Preserves a routed {@link ChatModel} while carrying Amsha's explicit strict
 * JSON-Schema admission decision alongside it.
 */
public final class CapabilityAwareChatModel implements ChatModel, StrictJsonSchemaCapable {

    private final ChatModel delegate;

    public CapabilityAwareChatModel(ChatModel delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** Avoids adding another wrapper when a factory already marked the model. */
    public static ChatModel markStrict(ChatModel model) {
        if (model instanceof StrictJsonSchemaCapable capable && capable.supportsStrictJsonSchema()) {
            return model;
        }
        return new CapabilityAwareChatModel(model);
    }

    @Override
    public boolean supportsStrictJsonSchema() {
        return true;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return delegate.chat(request);
    }

    @Override
    public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
        return delegate.chat(request, options);
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return delegate.doChat(request);
    }

    @Override
    public ChatResponse chat(ChatMessage... messages) {
        return delegate.chat(messages);
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages) {
        return delegate.chat(messages);
    }

    @Override
    public String chat(String text) {
        return delegate.chat(text);
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return delegate.listeners();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        Set<Capability> capabilities = delegate.supportedCapabilities();
        if (capabilities != null && capabilities.contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA)) {
            return capabilities;
        }
        List<Capability> augmented = new ArrayList<>();
        if (capabilities != null) {
            augmented.addAll(capabilities);
        }
        augmented.add(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
        return Set.copyOf(augmented);
    }
}

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

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public class LazyChatModel implements ChatModel {
    private final Supplier<ChatModel> delegateSupplier;
    private volatile ChatModel delegate;

    public LazyChatModel(Supplier<ChatModel> delegateSupplier) {
        this.delegateSupplier = delegateSupplier;
    }

    private ChatModel getDelegate() {
        ChatModel result = delegate;
        if (result == null) {
            synchronized (this) {
                result = delegate;
                if (result == null) {
                    delegate = result = delegateSupplier.get();
                }
            }
        }
        return result;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return getDelegate().chat(request);
    }

    @Override
    public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
        return getDelegate().chat(request, options);
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return getDelegate().doChat(request);
    }

    @Override
    public ChatResponse chat(ChatMessage... messages) {
        return getDelegate().chat(messages);
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages) {
        return getDelegate().chat(messages);
    }

    @Override
    public String chat(String text) {
        return getDelegate().chat(text);
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return getDelegate().defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return getDelegate().listeners();
    }

    @Override
    public ModelProvider provider() {
        return getDelegate().provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return getDelegate().supportedCapabilities();
    }
}

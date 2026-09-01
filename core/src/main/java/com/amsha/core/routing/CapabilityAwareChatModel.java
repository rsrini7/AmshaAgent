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

import com.amsha.llm.api.MultimodalCapabilities;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.llm.api.MultimodalCapable;
import com.amsha.llm.api.StrictJsonSchemaCapable;

/**
 * Preserves a routed {@link ChatModel} while carrying Amsha's explicit strict
 * JSON-Schema and multimodal capability decisions alongside it.
 */
public final class CapabilityAwareChatModel implements ChatModel, StrictJsonSchemaCapable, MultimodalCapable {

    private final ChatModel delegate;
    private final boolean strictJsonSchema;
    private final MultimodalCapability multimodalCapability;

    public CapabilityAwareChatModel(
            ChatModel delegate, boolean strictJsonSchema, MultimodalCapability multimodalCapability) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.strictJsonSchema = strictJsonSchema;
        this.multimodalCapability =
                multimodalCapability != null ? multimodalCapability : MultimodalCapability.TEXT_ONLY;
    }

    public CapabilityAwareChatModel(ChatModel delegate) {
        this(delegate, true, MultimodalCapability.TEXT_ONLY);
    }

    /** Avoids adding another wrapper when a factory already marked the model. */
    public static ChatModel markStrict(ChatModel model) {
        return withCapabilities(model, true, null);
    }

    /** Wraps a model with explicit strict-schema and multimodal capabilities. */
    public static ChatModel withCapabilities(
            ChatModel model, boolean strictSchema, MultimodalCapability multimodalCapability) {
        if (model == null) {
            return null;
        }
        boolean existingStrict = model instanceof StrictJsonSchemaCapable sc && sc.supportsStrictJsonSchema();
        MultimodalCapability existingMulti = MultimodalCapabilities.resolveCapability(model);

        boolean effectiveStrict = strictSchema || existingStrict;
        MultimodalCapability effectiveMulti = multimodalCapability != null ? multimodalCapability : existingMulti;

        if (!effectiveStrict && !effectiveMulti.isMultimodal() && !(model instanceof CapabilityAwareChatModel)) {
            return model;
        }

        if (model instanceof CapabilityAwareChatModel ca
                && ca.supportsStrictJsonSchema() == effectiveStrict
                && Objects.equals(ca.multimodalCapability(), effectiveMulti)) {
            return model;
        }

        return new CapabilityAwareChatModel(model, effectiveStrict, effectiveMulti);
    }

    @Override
    public boolean supportsStrictJsonSchema() {
        return strictJsonSchema;
    }

    @Override
    public MultimodalCapability multimodalCapability() {
        return multimodalCapability;
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
        if (strictJsonSchema) {
            augmented.add(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
        }
        return Set.copyOf(augmented);
    }
}

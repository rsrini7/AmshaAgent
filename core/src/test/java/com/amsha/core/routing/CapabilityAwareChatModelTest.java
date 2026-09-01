package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
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

import org.junit.jupiter.api.Test;

class CapabilityAwareChatModelTest {

    @Test
    void constructor_throwsOnNullDelegate() {
        assertThrows(NullPointerException.class, () -> new CapabilityAwareChatModel(null));
    }

    @Test
    void markStrict_returnsSameModelIfAlreadyCapable() {
        ChatModel mockModel = mock(ChatModel.class);
        ChatModel capable = new CapabilityAwareChatModel(mockModel);

        ChatModel result = CapabilityAwareChatModel.markStrict(capable);
        assertSame(capable, result);
    }

    @Test
    void markStrict_wrapsModelIfNotCapable() {
        ChatModel mockModel = mock(ChatModel.class);
        ChatModel result = CapabilityAwareChatModel.markStrict(mockModel);

        assertTrue(result instanceof CapabilityAwareChatModel);
        assertTrue(((CapabilityAwareChatModel) result).supportsStrictJsonSchema());
    }

    @Test
    void supportsStrictJsonSchema_returnsTrue() {
        ChatModel mockModel = mock(ChatModel.class);
        CapabilityAwareChatModel model = new CapabilityAwareChatModel(mockModel);

        assertTrue(model.supportsStrictJsonSchema());
    }

    @Test
    void chat_delegatesAllForms() {
        ChatModel delegate = mock(ChatModel.class);
        CapabilityAwareChatModel model = new CapabilityAwareChatModel(delegate);

        ChatRequest req =
                ChatRequest.builder().messages(List.of(UserMessage.from("hi"))).build();
        ChatResponse resp =
                ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
        ChatRequestOptions options = ChatRequestOptions.builder().build();

        when(delegate.chat(req)).thenReturn(resp);
        when(delegate.chat(req, options)).thenReturn(resp);
        when(delegate.doChat(req)).thenReturn(resp);
        when(delegate.chat("hi")).thenReturn("ok");

        assertSame(resp, model.chat(req));
        assertSame(resp, model.chat(req, options));
        assertSame(resp, model.doChat(req));
        assertEquals("ok", model.chat("hi"));

        ChatMessage userMsg = UserMessage.from("msg");
        when(delegate.chat(userMsg)).thenReturn(resp);
        when(delegate.chat(List.of(userMsg))).thenReturn(resp);

        assertSame(resp, model.chat(userMsg));
        assertSame(resp, model.chat(List.of(userMsg)));
    }

    @Test
    void delegatesMetadataAndListeners() {
        ChatModel delegate = mock(ChatModel.class);
        CapabilityAwareChatModel model = new CapabilityAwareChatModel(delegate);

        ChatRequestParameters params = mock(ChatRequestParameters.class);
        ChatModelListener listener = mock(ChatModelListener.class);

        when(delegate.defaultRequestParameters()).thenReturn(params);
        when(delegate.listeners()).thenReturn(List.of(listener));
        when(delegate.provider()).thenReturn(ModelProvider.OPEN_AI);

        assertSame(params, model.defaultRequestParameters());
        assertEquals(List.of(listener), model.listeners());
        assertEquals(ModelProvider.OPEN_AI, model.provider());
    }

    @Test
    void supportedCapabilities_augmentsWithJsonSchema() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.supportedCapabilities()).thenReturn(Set.of());
        CapabilityAwareChatModel model = new CapabilityAwareChatModel(delegate);

        Set<Capability> caps = model.supportedCapabilities();
        assertTrue(caps.contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA));
    }

    @Test
    void supportedCapabilities_handlesNullAndExistingCapabilities() {
        ChatModel delegate = mock(ChatModel.class);
        when(delegate.supportedCapabilities()).thenReturn(null);
        CapabilityAwareChatModel model = new CapabilityAwareChatModel(delegate);

        Set<Capability> caps = model.supportedCapabilities();
        assertTrue(caps.contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA));

        when(delegate.supportedCapabilities()).thenReturn(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA));
        Set<Capability> existingCaps = model.supportedCapabilities();
        assertTrue(existingCaps.contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA));
    }
}

package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;
import java.util.Set;

import com.amsha.channel.api.AttachmentType;
import com.amsha.llm.api.MultimodalCapabilities;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.llm.api.MultimodalCapable;
import com.amsha.llm.api.StrictJsonSchemaCapable;
import com.amsha.llm.api.StructuredSchemaRequests;
import com.amsha.llm.api.error.LlmMalformedResponseException;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class InfiniteLoopChatModelTest {

    private interface CapableChatModel extends ChatModel, StrictJsonSchemaCapable {}

    private static final String SAMPLE_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}";

    private static ChatRequest schemaRequest() {
        ChatRequest base = ChatRequest.builder()
                .messages(List.of(UserMessage.from("hello")))
                .build();
        return StructuredSchemaRequests.withNativeJsonSchema(base, "sample", SAMPLE_SCHEMA);
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    @Test
    void supportsStrictJsonSchema_reflectsAllSlots() {
        CapableChatModel strict1 = mock(CapableChatModel.class);
        when(strict1.supportsStrictJsonSchema()).thenReturn(true);

        CapableChatModel strict2 = mock(CapableChatModel.class);
        when(strict2.supportsStrictJsonSchema()).thenReturn(true);

        ChatModel nonStrict = mock(ChatModel.class);

        InfiniteLoopChatModel allStrict = new InfiniteLoopChatModel(
                "loop-1",
                List.of(new InfiniteLoopChatModel.Slot("s1", strict1), new InfiniteLoopChatModel.Slot("s2", strict2)),
                1,
                1,
                1,
                1000L);
        assertTrue(allStrict.supportsStrictJsonSchema());

        InfiniteLoopChatModel mixed = new InfiniteLoopChatModel(
                "loop-2",
                List.of(new InfiniteLoopChatModel.Slot("s1", strict1), new InfiniteLoopChatModel.Slot("s2", nonStrict)),
                1,
                1,
                1,
                1000L);
        assertFalse(mixed.supportsStrictJsonSchema());
    }

    @Test
    void schemaBearingRequest_firstSlotNativeAndPromptRetryFail_secondSlotReceivesNoNativeSchema() {
        CapableChatModel fallbackModel = mock(CapableChatModel.class);
        when(fallbackModel.supportsStrictJsonSchema()).thenReturn(true);

        CapableChatModel primaryModel = mock(CapableChatModel.class);
        when(primaryModel.supportsStrictJsonSchema()).thenReturn(true);

        // Primary slot (index 1, called first by round-robin):
        // attempt 1 (with schema) throws LlmMalformedResponseException (schema rejected)
        // attempt 2 (without schema) throws RuntimeException (connection error)
        when(primaryModel.chat(any(ChatRequest.class)))
                .thenThrow(new LlmMalformedResponseException("schema rejected"))
                .thenThrow(new RuntimeException("slot 1 connection refused"));

        // Fallback slot (index 0, called second): succeeds and returns response
        when(fallbackModel.chat(any(ChatRequest.class))).thenReturn(response("fallback answer"));

        InfiniteLoopChatModel loopModel = new InfiniteLoopChatModel(
                "failover-loop",
                List.of(
                        new InfiniteLoopChatModel.Slot("slot-fallback", fallbackModel),
                        new InfiniteLoopChatModel.Slot("slot-primary", primaryModel)),
                1,
                1,
                1,
                0L);

        ChatRequest request = schemaRequest();
        assertTrue(StructuredSchemaRequests.hasNativeJsonSchema(request));

        // When
        ChatResponse resp = loopModel.chat(request);

        // Then
        assertNotNull(resp);
        assertEquals("fallback answer", resp.aiMessage().text());

        // Primary slot assertions: 2 attempts
        ArgumentCaptor<ChatRequest> primaryCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(primaryModel, times(2)).chat(primaryCaptor.capture());
        List<ChatRequest> primaryRequests = primaryCaptor.getAllValues();
        assertTrue(
                StructuredSchemaRequests.hasNativeJsonSchema(primaryRequests.get(0)),
                "Primary slot attempt 1 must have native schema");
        assertFalse(
                StructuredSchemaRequests.hasNativeJsonSchema(primaryRequests.get(1)),
                "Primary slot attempt 2 (retry) must have schema stripped");

        // Fallback slot assertions: 1 attempt, must NOT receive native schema
        ArgumentCaptor<ChatRequest> fallbackCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(fallbackModel, times(1)).chat(fallbackCaptor.capture());
        ChatRequest fallbackRequest = fallbackCaptor.getValue();
        assertFalse(
                StructuredSchemaRequests.hasNativeJsonSchema(fallbackRequest),
                "Fallback slot must receive stripped schema request because failover chain already rejected schema");
    }

    @Test
    void schemaBearingRequest_firstSlotNativeSucceeds_schemaKept() {
        CapableChatModel slot1Model = mock(CapableChatModel.class);
        when(slot1Model.supportsStrictJsonSchema()).thenReturn(true);

        when(slot1Model.chat(any(ChatRequest.class))).thenReturn(response("{\"name\":\"Alice\"}"));

        InfiniteLoopChatModel loopModel = new InfiniteLoopChatModel(
                "success-loop", List.of(new InfiniteLoopChatModel.Slot("slot-1", slot1Model)), 1, 1, 1, 1000L);

        ChatRequest request = schemaRequest();
        ChatResponse resp = loopModel.chat(request);

        assertNotNull(resp);
        assertEquals("{\"name\":\"Alice\"}", resp.aiMessage().text());

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(slot1Model).chat(captor.capture());
        assertTrue(StructuredSchemaRequests.hasNativeJsonSchema(captor.getValue()));
    }

    @Test
    void nativeSchemaRejectionRecordsPayloadFreeMetric() {
        CapableChatModel model = mock(CapableChatModel.class);
        when(model.supportsStrictJsonSchema()).thenReturn(true);
        when(model.chat(any(ChatRequest.class)))
                .thenThrow(new LlmMalformedResponseException("schema rejected", null))
                .thenReturn(response("fallback"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        InfiniteLoopChatModel loopModel = new InfiniteLoopChatModel(
                "metric-loop",
                List.of(new InfiniteLoopChatModel.Slot("slot-1", model)),
                1,
                3,
                1,
                1_000L,
                meterRegistry);

        loopModel.chat(schemaRequest());

        assertEquals(
                1.0,
                meterRegistry
                        .get("amsha.llm.structured.schema.rejection")
                        .tag("operation", "infinite-loop")
                        .counter()
                        .count());
    }

    @Test
    void multimodalCapability_computesIntersectionAcrossSlots() {
        MultimodalCapable visionModel =
                mock(MultimodalCapable.class, Mockito.withSettings().extraInterfaces(ChatModel.class));
        when(visionModel.multimodalCapability())
                .thenReturn(new MultimodalCapability(
                        Set.of(AttachmentType.IMAGE),
                        MultimodalCapabilities.STANDARD_IMAGE_MIMES,
                        10_000_000L,
                        10,
                        true,
                        true));

        ChatModel textOnlyModel = mock(ChatModel.class);

        InfiniteLoopChatModel visionLoop = new InfiniteLoopChatModel(
                "vision-loop", List.of(new InfiniteLoopChatModel.Slot("s1", (ChatModel) visionModel)), 1, 1, 1, 1000L);
        assertTrue(visionLoop.multimodalCapability().isMultimodal());
        assertTrue(visionLoop.multimodalCapability().supportsType(AttachmentType.IMAGE));

        InfiniteLoopChatModel mixedLoop = new InfiniteLoopChatModel(
                "mixed-loop",
                List.of(
                        new InfiniteLoopChatModel.Slot("s1", (ChatModel) visionModel),
                        new InfiniteLoopChatModel.Slot("s2", textOnlyModel)),
                1,
                1,
                1,
                1000L);
        assertFalse(mixedLoop.multimodalCapability().isMultimodal());
        assertEquals(MultimodalCapability.TEXT_ONLY, mixedLoop.multimodalCapability());
    }
}

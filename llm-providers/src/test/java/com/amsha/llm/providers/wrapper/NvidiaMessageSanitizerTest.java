package com.amsha.llm.providers.wrapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings("java:S1192")
class NvidiaMessageSanitizerTest {

    private NvidiaMessageSanitizer sanitizer;
    private ChatModel mockModel;

    @BeforeEach
    void setUp() {
        mockModel = mock(ChatModel.class);
        sanitizer = new NvidiaMessageSanitizer(mockModel);
    }

    @Test
    void testChatDelegatesToModel() {
        ChatResponse mockResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from("Sanitized answer"))
                .build();
        when(mockModel.chat(any(ChatRequest.class))).thenReturn(mockResponse);

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("Hello")))
                .build();
        ChatResponse response = sanitizer.chat(request);

        assertNotNull(response);
        assertEquals("Sanitized answer", response.aiMessage().text());
    }

    @Test
    void testRewriteAllIdsForAbsoluteSafety() {
        List<ToolExecutionRequest> requests = List.of(
                ToolExecutionRequest.builder()
                        .id("call_123")
                        .name("tool1")
                        .arguments("{}")
                        .build(),
                ToolExecutionRequest.builder()
                        .id("too-long-id-123")
                        .name("tool2")
                        .arguments("{}")
                        .build());
        AiMessage aiMessage = AiMessage.from(requests);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(aiMessage);
        messages.add(ToolExecutionResultMessage.from("call_123", "tool1", "result1"));
        messages.add(ToolExecutionResultMessage.from("too-long-id-123", "tool2", "result2"));

        List<ChatMessage> sanitized = sanitizer.sanitize(messages);

        assertEquals(3, sanitized.size());
        AiMessage sanitizedAi = (AiMessage) sanitized.get(0);

        String id1 = sanitizedAi.toolExecutionRequests().get(0).id();
        String id2 = sanitizedAi.toolExecutionRequests().get(1).id();

        assertEquals(9, id1.length());
        assertEquals(9, id2.length());
        assertTrue(id1.matches("[a-zA-Z0-9]{9}"));
        assertTrue(id2.matches("[a-zA-Z0-9]{9}"));

        assertEquals(id1, ((ToolExecutionResultMessage) sanitized.get(1)).id());
        assertEquals(id2, ((ToolExecutionResultMessage) sanitized.get(2)).id());
    }

    @Test
    void testBipartiteGuardPreservesPairedMessages() {
        List<ToolExecutionRequest> requests = List.of(ToolExecutionRequest.builder()
                .id("id1234567")
                .name("tool1")
                .arguments("{}")
                .build());
        AiMessage aiMessage = AiMessage.from(requests);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(aiMessage);
        messages.add(ToolExecutionResultMessage.from("id1234567", "tool1", "result1"));

        List<ChatMessage> sanitized = sanitizer.sanitize(messages);
        assertEquals(2, sanitized.size());
    }

    @Test
    void testBipartiteGuardPrunesOrphanedCalls() {
        List<ToolExecutionRequest> requests = List.of(ToolExecutionRequest.builder()
                .id("orphan01x")
                .name("orphan-tool")
                .arguments("{}")
                .build());
        AiMessage orphanAi = AiMessage.from("Thinking...", requests);
        AiMessage followUp = AiMessage.from("Next message after orphan");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(orphanAi);
        messages.add(followUp);

        List<ChatMessage> sanitized = sanitizer.sanitize(messages);

        assertEquals(3, sanitized.size());
        AiMessage sanitizedOrphan = (AiMessage) sanitized.get(0);
        assertFalse(sanitizedOrphan.hasToolExecutionRequests());
        assertEquals("Thinking...", sanitizedOrphan.text());
        assertTrue(sanitized.get(2) instanceof UserMessage);
    }

    @Test
    void testBipartiteGuardPrunesOrphanedResults() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("hello"));
        messages.add(ToolExecutionResultMessage.from("no-call-id", "tool1", "orphaned-result"));

        List<ChatMessage> sanitized = sanitizer.sanitize(messages);

        assertEquals(1, sanitized.size());
        assertEquals("hello", ((UserMessage) sanitized.get(0)).singleText());
    }

    @Test
    void testBracketPatternStripping() {
        String thought = "I will call tool1 [tool1(arg1=val1)] and tool2 [tool2(arg2=val2)]";

        List<ToolExecutionRequest> requests = List.of(ToolExecutionRequest.builder()
                .id("id1")
                .name("tool1")
                .arguments("{}")
                .build());
        AiMessage aiMessage = AiMessage.from(thought, requests);

        List<ChatMessage> messages = List.of(aiMessage, ToolExecutionResultMessage.from("id1", "tool1", "done"));

        List<ChatMessage> sanitized = sanitizer.sanitize(messages);
        AiMessage sanitizedAi = (AiMessage) sanitized.get(0);

        assertFalse(sanitizedAi.text().contains("[tool1("));
        assertFalse(sanitizedAi.text().contains("[tool2("));
        assertTrue(sanitizedAi.text().contains("I will call tool1"));
    }

    @Test
    void testMixedMessagesPassThrough() {
        List<ChatMessage> messages = List.of(UserMessage.from("hello"), AiMessage.from("thought"));
        List<ChatMessage> sanitized = sanitizer.sanitize(messages);
        assertEquals(3, sanitized.size());
        assertEquals("hello", ((UserMessage) sanitized.get(0)).singleText());
        assertTrue(sanitized.get(2) instanceof UserMessage);
    }

    // ------------------------------------------------------------------ request shape

    @Test
    void testToolSpecificationsAreForwardedToTheDelegate() {
        ToolSpecification spec =
                ToolSpecification.builder().name("allowed_tool").build();
        when(mockModel.chat(any(ChatRequest.class)))
                .thenReturn(
                        ChatResponse.builder().aiMessage(AiMessage.from("ok")).build());

        sanitizer.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("hi")))
                .toolSpecifications(List.of(spec))
                .build());

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(mockModel).chat(captor.capture());
        assertEquals(1, captor.getValue().toolSpecifications().size());
        assertEquals(
                "allowed_tool", captor.getValue().toolSpecifications().get(0).name());
    }

    @Test
    void testNullAiMessageResponseIsReturnedUntouched() {
        // A response without an AiMessage short-circuits both sanitisation passes.
        ChatResponse response = mock(ChatResponse.class);
        when(response.aiMessage()).thenReturn(null);
        when(mockModel.chat(any(ChatRequest.class))).thenReturn(response);

        assertSame(response, sanitizer.chat(request(UserMessage.from("hi"))));
    }

    // ------------------------------------------------------- Mistral [TOOL_CALLS] leak

    @Test
    void testMistralArrayFormWithStringArgumentsIsNormalized() {
        ChatResponse out = chatWithDelegateResponse(
                "thinking\n[TOOL_CALLS] [{\"name\":\"lookup\",\"arguments\":\"{}\"}]", request(UserMessage.from("hi")));

        assertEquals(FinishReason.TOOL_EXECUTION, out.finishReason());
        assertEquals("thinking", out.aiMessage().text());
        List<ToolExecutionRequest> requests = out.aiMessage().toolExecutionRequests();
        assertEquals(1, requests.size());
        assertEquals("lookup", requests.get(0).name());
        assertEquals("{}", requests.get(0).arguments());
    }

    @Test
    void testMistralArrayFormWithObjectArgumentsIsNormalized() {
        ChatResponse out = chatWithDelegateResponse(
                "[TOOL_CALLS] [{\"name\":\"lookup\",\"arguments\":{\"q\":\"x\"}}]", request(UserMessage.from("hi")));

        List<ToolExecutionRequest> requests = out.aiMessage().toolExecutionRequests();
        assertEquals(1, requests.size());
        assertEquals("lookup", requests.get(0).name());
        assertEquals("{\"q\":\"x\"}", requests.get(0).arguments());
        assertNull(out.aiMessage().text(), "an empty thought prefix must yield no text");
    }

    @Test
    void testMistralSingleCallFormIsNormalized() {
        ChatResponse out = chatWithDelegateResponse(
                "let me check\n[TOOL_CALLS] lookup {\"q\":\"x\"}", request(UserMessage.from("hi")));

        List<ToolExecutionRequest> requests = out.aiMessage().toolExecutionRequests();
        assertEquals(1, requests.size());
        assertEquals("lookup", requests.get(0).name());
        assertEquals("{\"q\":\"x\"}", requests.get(0).arguments());
        assertEquals("let me check", out.aiMessage().text());
    }

    @Test
    void testMistralChainedCallsAreAllExtracted() {
        ChatResponse out = chatWithDelegateResponse(
                "[TOOL_CALLS] first {\"a\":1}\n[TOOL_CALLS] second {\"b\":2}", request(UserMessage.from("hi")));

        List<ToolExecutionRequest> requests = out.aiMessage().toolExecutionRequests();
        assertEquals(2, requests.size());
        assertEquals("first", requests.get(0).name());
        assertEquals("second", requests.get(1).name());
    }

    @Test
    void testMistralMalformedArrayPayloadIsSwallowedAndTheResponseIsReturned() {
        // readTree throws on the array form, the catch logs it, and with nothing extracted the original
        // response passes through untouched.
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("[TOOL_CALLS] [{not valid json}]"))
                .build();
        when(mockModel.chat(any(ChatRequest.class))).thenReturn(response);

        assertSame(response, sanitizer.chat(request(UserMessage.from("hi"))));
    }

    @Test
    void testMistralMarkerWithoutAPayloadIsIgnored() {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("the marker [TOOL_CALLS] with no payload follows"))
                .build();
        when(mockModel.chat(any(ChatRequest.class))).thenReturn(response);

        assertSame(response, sanitizer.chat(request(UserMessage.from("hi"))));
    }

    private ChatResponse chatWithDelegateResponse(String delegateText, ChatRequest request) {
        ChatResponse response =
                ChatResponse.builder().aiMessage(AiMessage.from(delegateText)).build();
        when(mockModel.chat(any(ChatRequest.class))).thenReturn(response);
        return sanitizer.chat(request);
    }

    private static ChatRequest request(ChatMessage... messages) {
        return ChatRequest.builder().messages(List.of(messages)).build();
    }

    // ------------------------------------------------------- role-switching guard

    @Test
    void testOutOfSchemaToolCallsInHistoryArePruned() {
        List<ToolExecutionRequest> requests = List.of(ToolExecutionRequest.builder()
                .id("id1234567")
                .name("forbidden_tool")
                .arguments("{}")
                .build());
        List<ChatMessage> messages = List.of(
                AiMessage.from("thinking", requests),
                ToolExecutionResultMessage.from("id1234567", "forbidden_tool", "result"));

        List<ChatMessage> sanitized = sanitizer.sanitize(messages, new HashMap<>(), Set.of("allowed_tool"));

        // Pass 1 skips the out-of-schema call, so its id never joins the bipartite set and both the call
        // and its matching result are pruned.
        assertEquals(2, sanitized.size());
        AiMessage ai = (AiMessage) sanitized.get(0);
        assertFalse(ai.hasToolExecutionRequests());
        assertEquals("thinking", ai.text());
        assertTrue(sanitized.get(1) instanceof UserMessage);
    }

    @Test
    void testOutOfSchemaToolCallsInTheResponseAreDropped() {
        ToolSpecification spec =
                ToolSpecification.builder().name("allowed_tool").build();
        when(mockModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from(
                                "here is the answer",
                                List.of(ToolExecutionRequest.builder()
                                        .id("call_1")
                                        .name("forbidden_tool")
                                        .arguments("{}")
                                        .build())))
                        .build());

        ChatResponse out = sanitizer.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("hi")))
                .toolSpecifications(List.of(spec))
                .build());

        assertFalse(out.aiMessage().hasToolExecutionRequests());
        assertEquals("here is the answer", out.aiMessage().text());
    }

    @Test
    void testOutOfSchemaResponseCallWithNullTextYieldsAnEmptyMessage() {
        ToolSpecification spec =
                ToolSpecification.builder().name("allowed_tool").build();
        when(mockModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from(List.of(ToolExecutionRequest.builder()
                                .id("call_1")
                                .name("forbidden_tool")
                                .arguments("{}")
                                .build())))
                        .build());

        ChatResponse out = sanitizer.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("hi")))
                .toolSpecifications(List.of(spec))
                .build());

        assertFalse(out.aiMessage().hasToolExecutionRequests());
        assertEquals("", out.aiMessage().text());
    }

    // ------------------------------------------------------------ sequence guards

    @Test
    void testSystemHintAfterAToolResultIsMergedIntoIt() {
        List<ToolExecutionRequest> requests = List.of(ToolExecutionRequest.builder()
                .id("id1234567")
                .name("tool1")
                .arguments("{}")
                .build());
        List<ChatMessage> messages = List.of(
                AiMessage.from("thinking", requests),
                ToolExecutionResultMessage.from("id1234567", "tool1", "tool output"),
                SystemMessage.from("remember the timezone"));

        Map<String, String> idMap = new HashMap<>();
        List<ChatMessage> sanitized = sanitizer.sanitize(messages, idMap, null);

        assertEquals(2, sanitized.size(), "the trailing system hint must be folded in, not kept as a message");
        ToolExecutionResultMessage merged = (ToolExecutionResultMessage) sanitized.get(1);
        assertTrue(merged.text().contains("tool output"), merged.text());
        assertTrue(merged.text().contains("[System Hint: remember the timezone]"), merged.text());
        assertEquals(
                merged.id(),
                ((AiMessage) sanitized.get(0)).toolExecutionRequests().get(0).id());
    }

    @Test
    void testSystemHintIsNotMergedWhenThePrecedingMessageIsNotAToolResult() {
        List<ChatMessage> messages = List.of(UserMessage.from("hello"), SystemMessage.from("hint"));

        List<ChatMessage> sanitized = sanitizer.sanitize(messages, new HashMap<>(), null);

        assertEquals(2, sanitized.size());
        assertTrue(sanitized.get(1) instanceof SystemMessage);
    }
}

package com.amsha.llm.providers.wrapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.security.http.GovernedTransportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;

/**
 * Loopback test for direct ChatGPT Plus Responses SSE transport ({@link CodexResponsesChatModel}).
 */
class CodexResponsesChatModelTest {

    private HttpServer server;
    private int port;
    private String endpoint;
    private GovernedTransportService governedTransport;
    private final List<String> capturedHeaders = new CopyOnWriteArrayList<>();
    private final List<String> capturedBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        governedTransport = Mockito.mock(GovernedTransportService.class);
        Mockito.when(governedTransport.send(anyString(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenAnswer(inv -> {
                    HttpRequest req = inv.getArgument(5);
                    return client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                });

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        endpoint = "http://127.0.0.1:" + port + "/backend-api/codex/responses";

        server.createContext("/backend-api/codex/responses", new SseHandler());
        server.setExecutor(null);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void testDirectResponsesTextAndToolCallingFlow() throws Exception {
        CodexResponsesChatModel model = new CodexResponsesChatModel(
                endpoint,
                "test-oauth-access-token",
                "test-account-id-123",
                "gpt-5.6-luna",
                1024,
                Duration.ofSeconds(5),
                0.0,
                governedTransport);

        // Turn 1: User sends prompt; Model returns function_call via SSE
        ToolSpecification toolSpec = ToolSpecification.builder()
                .name("reverse_text")
                .description("Reverses text")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("input", "Text to reverse")
                        .required("input")
                        .build())
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("You are a helpful assistant."));
        messages.add(UserMessage.from("Reverse 'hello'"));

        ChatRequest request1 = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(List.of(toolSpec))
                .build();

        ChatResponse response1 = model.chat(request1);
        assertNotNull(response1);
        assertEquals(FinishReason.TOOL_EXECUTION, response1.finishReason());
        assertTrue(response1.aiMessage().hasToolExecutionRequests());
        assertEquals(1, response1.aiMessage().toolExecutionRequests().size());
        assertEquals(
                "reverse_text",
                response1.aiMessage().toolExecutionRequests().get(0).name());

        // Verify request headers
        assertTrue(capturedHeaders.stream().anyMatch(h -> h.contains("Bearer test-oauth-access-token")));
        assertTrue(capturedHeaders.stream().anyMatch(h -> h.contains("ChatGPT-Account-ID: test-account-id-123")));

        // Verify JSON request body
        JsonNode req1Body = objectMapper.readTree(capturedBodies.get(0));
        assertEquals("gpt-5.6-luna", req1Body.path("model").asText());
        assertFalse(req1Body.path("store").asBoolean());
        assertTrue(req1Body.path("stream").asBoolean());
        assertEquals(2, req1Body.path("input").size());

        // Turn 2: Send tool output; Model returns final text
        messages.add(response1.aiMessage());
        messages.add(ToolExecutionResultMessage.from(
                response1.aiMessage().toolExecutionRequests().get(0).id(), "reverse_text", "olleh"));

        ChatRequest request2 = ChatRequest.builder().messages(messages).build();

        ChatResponse response2 = model.chat(request2);
        assertNotNull(response2);
        assertEquals(FinishReason.STOP, response2.finishReason());
        assertEquals("The reversed string is olleh", response2.aiMessage().text());
    }

    private class SseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            capturedHeaders.add("Authorization: " + exchange.getRequestHeaders().getFirst("Authorization"));
            capturedHeaders.add(
                    "ChatGPT-Account-ID: " + exchange.getRequestHeaders().getFirst("ChatGPT-Account-ID"));

            try (InputStream is = exchange.getRequestBody()) {
                capturedBodies.add(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            }

            int count = requestCount.incrementAndGet();
            String sseData;

            if (count == 1) {
                sseData = """
                        data: {"type":"response.created","response":{"id":"resp_1"}}

                        data: {"type":"response.output_item.added","item":{"id":"fc_1","type":"function_call","name":"reverse_text","call_id":"call_1"}}

                        data: {"type":"response.function_call_arguments.delta","delta":"{\\"input\\":\\"hello\\"}"}

                        data: {"type":"response.function_call_arguments.done","arguments":"{\\"input\\":\\"hello\\"}"}

                        data: {"type":"response.output_item.done","item":{"id":"fc_1","type":"function_call","name":"reverse_text","call_id":"call_1","arguments":"{\\"input\\":\\"hello\\"}"}}

                        data: {"type":"response.completed","response":{"id":"resp_1"}}

                        """;
            } else {
                sseData = """
                        data: {"type":"response.created","response":{"id":"resp_2"}}

                        data: {"type":"response.output_text.delta","delta":"The reversed string is olleh"}

                        data: {"type":"response.completed","response":{"id":"resp_2"}}

                        """;
            }

            byte[] bytes = sseData.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    @Test
    void testRemoteEndpointWithoutGovernedTransport_throwsIllegalStateException() {
        CodexResponsesChatModel model = new CodexResponsesChatModel(
                "https://chatgpt.com/backend-api/codex/responses",
                "test-oauth-access-token",
                "test-account-id-123",
                "gpt-5.6-luna",
                1024,
                Duration.ofSeconds(5),
                0.0,
                null);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> model.chat(ChatRequest.builder()
                        .messages(List.of(UserMessage.from("Ping remote")))
                        .build()));
        assertTrue(ex.getMessage().contains("Governed outbound transport"));
    }

    // ---- request payload shape ----

    @Test
    void anAssistantMessageWithTextIsIncludedInTheRequestPayload() throws Exception {
        modelWithTransport()
                .chat(ChatRequest.builder()
                        .messages(List.of(UserMessage.from("hi"), AiMessage.from("I am the assistant")))
                        .build());

        JsonNode input = objectMapper.readTree(capturedBodies.get(0)).path("input");
        assertEquals(2, input.size());
        assertEquals("assistant", input.get(1).path("role").asText());
        assertEquals("I am the assistant", input.get(1).path("content").asText());
    }

    // ---- SSE event handling ----

    @Test
    void textAndToolCallInOneStreamProduceAMessageCarryingBoth() {
        stubSse("""
                data: {"type":"response.output_text.delta","delta":"thinking..."}

                data: {"type":"response.output_item.added","item":{"type":"function_call","name":"reverse_text","call_id":"call_9"}}

                data: {"type":"response.function_call_arguments.delta","delta":"{\\"input\\":\\"hi\\"}"}

                data: {"type":"response.output_item.done","item":{"type":"function_call","name":"reverse_text","call_id":"call_9"}}

                data: {"type":"response.completed","response":{"usage":{"input_tokens":7,"output_tokens":3,"total_tokens":10}}}

                """, 200);

        ChatResponse response = chat("Reverse 'hi'");

        assertEquals(FinishReason.TOOL_EXECUTION, response.finishReason());
        assertEquals("thinking...", response.aiMessage().text());
        assertEquals(1, response.aiMessage().toolExecutionRequests().size());
        // The stream reported usage, so the character-count estimate must not be used.
        assertEquals(7, response.tokenUsage().inputTokenCount().intValue());
        assertEquals(10, response.tokenUsage().totalTokenCount().intValue());
    }

    @Test
    void outputTextDoneSuppliesTheTextWhenNoDeltaArrived() {
        stubSse("data: {\"type\":\"response.output_text.done\",\"text\":\"complete text\"}\n\n", 200);

        ChatResponse response = chat("hi");

        assertEquals("complete text", response.aiMessage().text());
        assertEquals(FinishReason.STOP, response.finishReason());
    }

    @Test
    void messageOutputItemTextIsExtractedFromItsContentParts() {
        stubSse("""
                data: {"type":"response.output_item.done","item":{"type":"message","content":[{"type":"output_text","text":"from parts"},{"type":"output_text","text":"!"}]}}

                """, 200);

        assertEquals("from parts!", chat("hi").aiMessage().text());
    }

    @Test
    void anErrorEventFailsTheChatWithTheUpstreamMessage() {
        stubSse("data: {\"type\":\"error\",\"message\":\"rate limited\"}\n\n", 200);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> chat("hi"));

        assertTrue(ex.getMessage().contains("Codex SSE Error"));
        assertTrue(ex.getMessage().contains("rate limited"));
    }

    // ---- transport and status handling ----

    @Test
    void aNon200ResponsesStatusIsReportedWithItsCode() {
        stubSse("upstream boom", 503);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> chat("hi"));

        assertTrue(ex.getMessage().contains("HTTP 503"));
    }

    @Test
    void anUnexpectedFailureIsWrappedWithItsRootMessage() {
        CodexResponsesChatModel model = new CodexResponsesChatModel(
                "http://[not-a-host", "tok", null, "gpt-5.6-luna", 1024, Duration.ofSeconds(5), 0.0, governedTransport);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> model.chat(request("hi")));

        assertFalse(ex instanceof IllegalStateException, "only IllegalStateException escapes unwrapped");
        assertTrue(ex.getMessage().contains("Failed to execute Codex chat request"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theConvenienceConstructorResolvesTheTransportFromCdi() {
        InstanceHandle<GovernedTransportService> handle = Mockito.mock(InstanceHandle.class);
        Mockito.when(handle.isAvailable()).thenReturn(true);
        Mockito.when(handle.get()).thenReturn(governedTransport);
        ArcContainer container = Mockito.mock(ArcContainer.class);
        Mockito.when(container.instance(GovernedTransportService.class)).thenReturn(handle);
        stubSse("data: {\"type\":\"response.output_text.delta\",\"delta\":\"via cdi\"}\n\n", 200);

        try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
            arc.when(Arc::container).thenReturn(container);

            // The 7-argument constructor has no transport parameter, so it must come from CDI.
            CodexResponsesChatModel model = new CodexResponsesChatModel(
                    endpoint, "tok", "acct", "gpt-5.6-luna", 1024, Duration.ofSeconds(5), 0.0);

            assertEquals("via cdi", model.chat(request("hi")).aiMessage().text());
        }
    }

    @Test
    void aBrokenCdiContainerLeavesTheTransportUnresolved() {
        try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
            arc.when(Arc::container).thenThrow(new IllegalStateException("no arc container"));

            CodexResponsesChatModel model = new CodexResponsesChatModel(
                    endpoint, "tok", "acct", "gpt-5.6-luna", 1024, Duration.ofSeconds(5), 0.0);

            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> model.chat(request("hi")));
            assertTrue(ex.getMessage().contains("Governed outbound transport"));
        }
    }

    // ---- helpers ----

    private CodexResponsesChatModel modelWithTransport() {
        return new CodexResponsesChatModel(
                endpoint,
                "test-oauth-access-token",
                "test-account-id-123",
                "gpt-5.6-luna",
                1024,
                Duration.ofSeconds(5),
                0.0,
                governedTransport);
    }

    private static ChatRequest request(String prompt) {
        return ChatRequest.builder().messages(List.of(UserMessage.from(prompt))).build();
    }

    private ChatResponse chat(String prompt) {
        return modelWithTransport().chat(request(prompt));
    }

    /** Serves a fixed SSE payload instead of going through the loopback handler. */
    @SuppressWarnings("unchecked")
    private void stubSse(String ssePayload, int status) {
        HttpResponse<InputStream> response = Mockito.mock(HttpResponse.class);
        Mockito.when(response.statusCode()).thenReturn(status);
        Mockito.when(response.body()).thenReturn(new ByteArrayInputStream(ssePayload.getBytes(StandardCharsets.UTF_8)));
        Mockito.doReturn(response)
                .when(governedTransport)
                .send(anyString(), any(), any(), any(), anyBoolean(), any(), any());
    }
}

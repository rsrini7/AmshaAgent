package com.amsha.llm.providers.wrapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.security.http.GovernedTransportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;

class ClaudeCodeResponsesChatModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A stream that never reports usage, so the token estimate path is exercised. */
    private static final String SSE_TEXT_ONLY_NO_USAGE = """
            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}
            """;

    private HttpServer server;
    private int port;
    private GovernedTransportService governedTransport;
    private final List<RecordedRequest> recordedRequests = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> sseResponseData = new AtomicReference<>("");

    record RecordedRequest(
            String path, String method, String authHeader, String betaHeader, String apiKeyHeader, String body) {}

    @BeforeEach
    void setUp() throws Exception {
        recordedRequests.clear();
        sseResponseData.set("");

        HttpClient client = HttpClient.newHttpClient();
        governedTransport = Mockito.mock(GovernedTransportService.class);
        Mockito.when(governedTransport.send(anyString(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenAnswer(inv -> {
                    HttpRequest req = inv.getArgument(5);
                    return client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                });

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] bodyBytes;
                try (InputStream is = exchange.getRequestBody()) {
                    bodyBytes = is.readAllBytes();
                }
                String body = new String(bodyBytes, StandardCharsets.UTF_8);
                recordedRequests.add(new RecordedRequest(
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestMethod(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("anthropic-beta"),
                        exchange.getRequestHeaders().getFirst("x-api-key"),
                        body));

                String sse = sseResponseData.get();
                if (sse.startsWith("HTTP_ERROR_")) {
                    int status = Integer.parseInt(sse.substring("HTTP_ERROR_".length()));
                    byte[] errBytes = "{\"type\":\"error\",\"error\":{\"message\":\"Simulated error\"}}"
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, errBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(errBytes);
                    }
                    return;
                }

                byte[] respBytes = sse.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, respBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(respBytes);
                }
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void chat_withOAuthToken_sendsBearerAndBetaHeaders() {
        sseResponseData.set("""
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":15,"output_tokens":1}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello from Claude"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":6}}

                event: message_stop
                data: {"type":"message_stop"}
                """);

        ClaudeCodeResponsesChatModel model = new ClaudeCodeResponsesChatModel(
                "http://127.0.0.1:" + port + "/v1/messages",
                "sk-ant-oauth-token-123",
                "claude-sonnet-4-5",
                4096,
                Duration.ofSeconds(5),
                0.0,
                governedTransport);

        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(List.of(new SystemMessage("You are a helpful assistant."), new UserMessage("Hi Claude")))
                .build());

        assertNotNull(response);
        assertEquals("Hello from Claude", response.aiMessage().text());
        assertEquals(FinishReason.STOP, response.finishReason());
        assertNotNull(response.tokenUsage());
        assertEquals(15, response.tokenUsage().inputTokenCount());
        assertEquals(6, response.tokenUsage().outputTokenCount());

        assertEquals(1, recordedRequests.size());
        RecordedRequest req = recordedRequests.getFirst();
        assertEquals("Bearer sk-ant-oauth-token-123", req.authHeader());
        assertEquals("oauth-2024-11-01", req.betaHeader());
        assertTrue(req.apiKeyHeader() == null || req.apiKeyHeader().isBlank());

        try {
            JsonNode root = MAPPER.readTree(req.body());
            assertTrue(root.has("temperature"));
            assertEquals(0.0, root.path("temperature").asDouble());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void chat_withStandardApiKey_sendsXApiKeyHeader() {
        sseResponseData.set("""
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_2","usage":{"input_tokens":10,"output_tokens":1}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Standard API Key response"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":8}}

                event: message_stop
                data: {"type":"message_stop"}
                """);

        ClaudeCodeResponsesChatModel model = new ClaudeCodeResponsesChatModel(
                "http://127.0.0.1:" + port,
                "sk-ant-api03-test-key-999",
                "claude-sonnet-4-5",
                4096,
                Duration.ofSeconds(5),
                0.0,
                governedTransport);

        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(List.of(new UserMessage("Test query")))
                .build());

        assertNotNull(response);
        assertEquals("Standard API Key response", response.aiMessage().text());

        assertEquals(1, recordedRequests.size());
        RecordedRequest req = recordedRequests.getFirst();
        assertEquals("sk-ant-api03-test-key-999", req.apiKeyHeader());
        assertTrue(req.authHeader() == null || req.authHeader().isBlank());
    }

    @Test
    void chat_withToolCall_parsesToolExecutionRequests() throws Exception {
        sseResponseData.set("""
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_3","usage":{"input_tokens":20,"output_tokens":1}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_calc_1","name":"calculate","input":{}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"expr\\":\\""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"2 + 2\\"}"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":12}}

                event: message_stop
                data: {"type":"message_stop"}
                """);

        ClaudeCodeResponsesChatModel model = new ClaudeCodeResponsesChatModel(
                "http://127.0.0.1:" + port,
                "sk-ant-oauth-test",
                "claude-sonnet-4-5",
                4096,
                Duration.ofSeconds(5),
                0.0,
                governedTransport);

        ToolSpecification toolSpec = ToolSpecification.builder()
                .name("calculate")
                .description("Evaluates a math expression")
                .parameters(JsonObjectSchema.builder().build())
                .build();

        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(List.of(new UserMessage("Calculate 2+2")))
                .toolSpecifications(List.of(toolSpec))
                .build());

        assertNotNull(response);
        assertEquals(FinishReason.TOOL_EXECUTION, response.finishReason());
        assertTrue(response.aiMessage().hasToolExecutionRequests());
        assertEquals(1, response.aiMessage().toolExecutionRequests().size());

        ToolExecutionRequest toolReq =
                response.aiMessage().toolExecutionRequests().getFirst();
        assertEquals("toolu_calc_1", toolReq.id());
        assertEquals("calculate", toolReq.name());
        assertTrue(toolReq.arguments().contains("2 + 2"));

        RecordedRequest req = recordedRequests.getFirst();
        JsonNode root = MAPPER.readTree(req.body());
        assertTrue(root.has("tools"));
        assertEquals("calculate", root.path("tools").get(0).path("name").asText());
    }

    @Test
    void chat_consecutiveSameRoleMessages_areMergedInPayload() throws Exception {
        sseResponseData.set("""
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_4","usage":{"input_tokens":10,"output_tokens":5}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":"Merged response"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}

                event: message_stop
                data: {"type":"message_stop"}
                """);

        ClaudeCodeResponsesChatModel model = new ClaudeCodeResponsesChatModel(
                "http://127.0.0.1:" + port,
                "sk-ant-oauth-test",
                "claude-sonnet-4-5",
                4096,
                Duration.ofSeconds(5),
                0.0,
                governedTransport);

        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(List.of(
                        new UserMessage("Message 1"),
                        new UserMessage("Message 2"),
                        new AiMessage("Assistant reply"),
                        new ToolExecutionResultMessage("tool_1", "calc", "Result is 4")))
                .build());

        assertNotNull(response);
        assertEquals(1, recordedRequests.size());

        JsonNode root = MAPPER.readTree(recordedRequests.getFirst().body());
        JsonNode messages = root.path("messages");
        // Merged consecutive users -> user (parts 1 and 2), assistant (part 3), user (tool result part 4)
        assertEquals(3, messages.size());
        assertEquals("user", messages.get(0).path("role").asText());
        assertEquals("assistant", messages.get(1).path("role").asText());
        assertEquals("user", messages.get(2).path("role").asText());
    }

    @Test
    void chat_httpError_throwsIllegalStateException() {
        sseResponseData.set("HTTP_ERROR_401");

        ClaudeCodeResponsesChatModel model = new ClaudeCodeResponsesChatModel(
                "http://127.0.0.1:" + port,
                "sk-ant-bad-token",
                "claude-sonnet-4-5",
                4096,
                Duration.ofSeconds(5),
                0.0,
                governedTransport);

        assertThrows(
                IllegalStateException.class,
                () -> model.chat(ChatRequest.builder()
                        .messages(List.of(new UserMessage("Hello")))
                        .build()));
    }

    @Test
    void chat_withTrailingSlashV1BaseUrl_resolvesEndpointCorrectly() {
        sseResponseData.set("""
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_5","usage":{"input_tokens":5,"output_tokens":2}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":"OK"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}

                event: message_stop
                data: {"type":"message_stop"}
                """);

        ClaudeCodeResponsesChatModel model = new ClaudeCodeResponsesChatModel(
                "http://127.0.0.1:" + port + "/v1/",
                "sk-ant-oauth-test",
                "claude-sonnet-4-5",
                4096,
                Duration.ofSeconds(5),
                0.0,
                governedTransport);

        ChatResponse response = model.chat(
                ChatRequest.builder().messages(List.of(new UserMessage("Ping"))).build());

        assertNotNull(response);
        assertEquals("OK", response.aiMessage().text());
        assertEquals(1, recordedRequests.size());
        assertEquals("/v1/messages", recordedRequests.getFirst().path());
    }

    @Test
    void chat_maxTokensStopReason_mapsToFinishReasonLength() {
        sseResponseData.set("""
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_6","usage":{"input_tokens":5,"output_tokens":10}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":"Truncated..."}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":10}}

                event: message_stop
                data: {"type":"message_stop"}
                """);

        ClaudeCodeResponsesChatModel model = new ClaudeCodeResponsesChatModel(
                "http://127.0.0.1:" + port,
                "sk-ant-oauth-test",
                "claude-sonnet-4-5",
                4096,
                Duration.ofSeconds(5),
                0.0,
                governedTransport);

        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(List.of(new UserMessage("Long text request")))
                .build());

        assertNotNull(response);
        assertEquals("Truncated...", response.aiMessage().text());
        assertEquals(FinishReason.LENGTH, response.finishReason());
    }

    @Test
    void chat_remoteEndpointWithoutGovernedTransport_throwsIllegalStateException() {
        ClaudeCodeResponsesChatModel model = new ClaudeCodeResponsesChatModel(
                "https://api.anthropic.com/v1/messages",
                "sk-ant-oauth-test",
                "claude-sonnet-4-5",
                4096,
                Duration.ofSeconds(5),
                0.0,
                null);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> model.chat(ChatRequest.builder()
                        .messages(List.of(new UserMessage("Ping remote")))
                        .build()));
        assertTrue(ex.getMessage().contains("Governed outbound transport"));
    }

    // ---- endpoint resolution and auth ----

    @Test
    void aBlankBaseUrlFallsBackToTheDefaultAnthropicEndpoint() {
        stubSse("data: {\"type\":\"message_stop\"}\n\n", 200);
        ClaudeCodeResponsesChatModel model = new ClaudeCodeResponsesChatModel(
                "   ", "sk-ant-oauth-test", "claude-sonnet-4-5", 4096, Duration.ofSeconds(5), 0.0, governedTransport);

        model.chat(request("hi"));

        ArgumentCaptor<String> sentUrl = ArgumentCaptor.forClass(String.class);
        Mockito.verify(governedTransport).send(sentUrl.capture(), any(), any(), any(), anyBoolean(), any(), any());
        assertEquals("https://api.anthropic.com/v1/messages", sentUrl.getValue());
    }

    @Test
    void aBlankTokenSendsNoAuthHeaderAtAll() {
        sseResponseData.set(SSE_TEXT_ONLY_NO_USAGE);

        model("   ").chat(request("hi"));

        RecordedRequest req = recordedRequests.getFirst();
        assertTrue(req.authHeader() == null || req.authHeader().isBlank());
        assertTrue(req.apiKeyHeader() == null || req.apiKeyHeader().isBlank());
        assertTrue(req.betaHeader() == null || req.betaHeader().isBlank());
    }

    @Test
    void anUnexpectedFailureIsWrappedWithItsRootMessage() {
        ClaudeCodeResponsesChatModel model = new ClaudeCodeResponsesChatModel(
                "http://[not-a-host",
                "sk-ant-oauth-test",
                "claude-sonnet-4-5",
                4096,
                Duration.ofSeconds(5),
                0.0,
                governedTransport);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> model.chat(request("hi")));

        assertTrue(ex.getMessage().contains("Failed to communicate with Claude Code API"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theConvenienceConstructorResolvesTheTransportFromCdi() {
        InstanceHandle<GovernedTransportService> handle = Mockito.mock(InstanceHandle.class);
        Mockito.when(handle.isAvailable()).thenReturn(true);
        Mockito.when(handle.get()).thenReturn(governedTransport);
        ArcContainer container = Mockito.mock(ArcContainer.class);
        Mockito.when(container.instance(GovernedTransportService.class)).thenReturn(handle);
        stubSse(
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"via cdi\"}}\n\n",
                200);

        try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
            arc.when(Arc::container).thenReturn(container);

            ClaudeCodeResponsesChatModel model = new ClaudeCodeResponsesChatModel(
                    "http://127.0.0.1:" + port,
                    "sk-ant-oauth-test",
                    "claude-sonnet-4-5",
                    4096,
                    Duration.ofSeconds(5),
                    0.0);

            assertEquals("via cdi", model.chat(request("hi")).aiMessage().text());
        }
    }

    @Test
    void aBrokenCdiContainerLeavesTheTransportUnresolved() {
        try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
            arc.when(Arc::container).thenThrow(new IllegalStateException("no arc container"));

            // The 6-argument constructor has no transport parameter, so it must come from CDI.
            ClaudeCodeResponsesChatModel model = new ClaudeCodeResponsesChatModel(
                    "http://127.0.0.1:" + port,
                    "sk-ant-oauth-test",
                    "claude-sonnet-4-5",
                    4096,
                    Duration.ofSeconds(5),
                    0.0);

            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> model.chat(request("hi")));
            assertTrue(ex.getMessage().contains("Governed outbound transport"));
        }
    }

    // ---- request payload shape ----

    @Test
    void assistantToolRequestsBecomeToolUseContentParts() throws Exception {
        sseResponseData.set(SSE_TEXT_ONLY_NO_USAGE);

        model().chat(ChatRequest.builder()
                .messages(List.of(
                        new UserMessage("go"),
                        AiMessage.from(
                                "Let me check",
                                List.of(
                                        ToolExecutionRequest.builder()
                                                .id("toolu_1")
                                                .name("calculate")
                                                .arguments("{\"expr\":\"2+2\"}")
                                                .build(),
                                        ToolExecutionRequest.builder()
                                                .id(null)
                                                .name("lookup")
                                                .arguments(null)
                                                .build(),
                                        ToolExecutionRequest.builder()
                                                .id("toolu_3")
                                                .name("broken")
                                                .arguments("not json")
                                                .build()))))
                .build());

        JsonNode content = MAPPER.readTree(recordedRequests.getFirst().body())
                .path("messages")
                .get(1)
                .path("content");

        assertEquals(4, content.size(), "one text part plus three tool_use parts");
        assertEquals("text", content.get(0).path("type").asText());
        assertEquals("Let me check", content.get(0).path("text").asText());
        assertEquals("tool_use", content.get(1).path("type").asText());
        assertEquals("toolu_1", content.get(1).path("id").asText());
        assertEquals("calculate", content.get(1).path("name").asText());
        assertEquals("2+2", content.get(1).path("input").path("expr").asText());
        // A missing id is replaced with a generated one; absent or unparseable arguments degrade to {}.
        assertFalse(content.get(2).path("id").asText().isBlank());
        assertTrue(content.get(2).path("input").isObject());
        assertTrue(content.get(2).path("input").isEmpty());
        assertTrue(content.get(3).path("input").isEmpty());
    }

    @Test
    void consecutiveToolResultsAreMergedIntoASingleUserMessage() throws Exception {
        sseResponseData.set(SSE_TEXT_ONLY_NO_USAGE);

        // Tool results are sent as user-role messages, so consecutive ones coalesce into one entry.
        model().chat(ChatRequest.builder()
                .messages(List.of(
                        new ToolExecutionResultMessage("toolu_1", "calculate", "4"),
                        new ToolExecutionResultMessage("toolu_2", "lookup", "found")))
                .build());

        JsonNode messages = MAPPER.readTree(recordedRequests.getFirst().body()).path("messages");

        assertEquals(1, messages.size(), "the two tool results merge into one user message");
        assertEquals("user", messages.get(0).path("role").asText());
        JsonNode merged = messages.get(0).path("content");
        assertEquals(2, merged.size());
        assertEquals("tool_result", merged.get(0).path("type").asText());
        assertEquals("toolu_1", merged.get(0).path("tool_use_id").asText());
        assertEquals("4", merged.get(0).path("content").asText());
        assertEquals("toolu_2", merged.get(1).path("tool_use_id").asText());
        assertEquals("found", merged.get(1).path("content").asText());
    }

    @Test
    void aToolWithoutParametersGetsAnEmptyInputSchema() throws Exception {
        sseResponseData.set(SSE_TEXT_ONLY_NO_USAGE);

        model().chat(ChatRequest.builder()
                .messages(List.of(new UserMessage("go")))
                .toolSpecifications(
                        List.of(ToolSpecification.builder().name("ping").build()))
                .build());

        JsonNode tool = MAPPER.readTree(recordedRequests.getFirst().body())
                .path("tools")
                .get(0);

        assertEquals("ping", tool.path("name").asText());
        assertEquals("object", tool.path("input_schema").path("type").asText());
    }

    // ---- SSE event handling ----

    @Test
    void tokenUsageIsEstimatedWhenTheStreamReportsNone() {
        sseResponseData.set(SSE_TEXT_ONLY_NO_USAGE);

        ChatResponse response = model().chat(request("hi"));

        assertEquals("hi", response.aiMessage().text());
        assertEquals(FinishReason.STOP, response.finishReason());
        // No usage events: a fixed 10 input tokens plus max(1, characters / 4) output tokens.
        assertEquals(10, response.tokenUsage().inputTokenCount().intValue());
        assertEquals(1, response.tokenUsage().outputTokenCount().intValue());
        assertEquals(11, response.tokenUsage().totalTokenCount().intValue());
    }

    @Test
    void textAndToolUseInOneStreamProduceAMessageCarryingBoth() {
        sseResponseData.set("""
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_7","usage":{"input_tokens":9,"output_tokens":1}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Let me calculate"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_9","name":"calculate","input":{}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"expr\\":\\"2+2\\"}"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":1}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":9}}

                event: message_stop
                data: {"type":"message_stop"}
                """);

        ChatResponse response = model().chat(request("Calculate 2+2"));

        assertEquals(FinishReason.TOOL_EXECUTION, response.finishReason());
        assertEquals("Let me calculate", response.aiMessage().text());
        assertEquals(1, response.aiMessage().toolExecutionRequests().size());
        assertEquals(
                "calculate",
                response.aiMessage().toolExecutionRequests().getFirst().name());
    }

    @Test
    void anErrorEventFailsTheChatWithTheUpstreamMessage() {
        sseResponseData.set("""
                event: error
                data: {"type":"error","error":{"message":"overloaded"}}
                """);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> model().chat(request("hi")));

        assertTrue(ex.getMessage().contains("Claude Code SSE Error"));
        assertTrue(ex.getMessage().contains("overloaded"));
    }

    // ---- helpers ----

    private ClaudeCodeResponsesChatModel model() {
        return model("sk-ant-oauth-test");
    }

    private ClaudeCodeResponsesChatModel model(String token) {
        return new ClaudeCodeResponsesChatModel(
                "http://127.0.0.1:" + port,
                token,
                "claude-sonnet-4-5",
                4096,
                Duration.ofSeconds(5),
                0.0,
                governedTransport);
    }

    private static ChatRequest request(String prompt) {
        return ChatRequest.builder().messages(List.of(new UserMessage(prompt))).build();
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

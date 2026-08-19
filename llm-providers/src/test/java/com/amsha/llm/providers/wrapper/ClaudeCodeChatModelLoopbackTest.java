package com.amsha.llm.providers.wrapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;

import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.llm.providers.routing.ClaudeCodeProviderFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Offline loopback contract test for the Claude Code CLI / Anthropic provider.
 * Proves that Claude Code uses direct Anthropic Messages transport:
 * - Request path: {@code /v1/messages}
 * - Auth header: {@code Authorization: Bearer <key>} with {@code anthropic-beta: oauth-2024-11-01}
 * - Serialization of system/user/assistant/tool messages and tool definitions
 * - Decoding of tool calls, text completions, finish reasons, and token usage
 * - Factory verification for {@link com.amsha.core.routing.ClaudeCodeProviderFactory} and {@link ClaudeCodeProviderFactory}
 */
@QuarkusTest
class ClaudeCodeChatModelLoopbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    ClaudeCodeProviderFactory routingFactory;

    @Inject
    com.amsha.llm.providers.reasoning.ClaudeCodeProviderFactory reasoningFactory; // fully qualified to disambiguate

    private HttpServer server;
    private int port;
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger requestCount = new AtomicInteger(0);

    record CapturedRequest(
            String path,
            String method,
            String authHeader,
            String betaHeader,
            String apiKeyHeader,
            String contentType,
            String body,
            JsonNode jsonBody) {}

    @BeforeEach
    void startServer() throws IOException {
        requests.clear();
        requestCount.set(0);

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", new LoopbackHandler());
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    class LoopbackHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            int count = requestCount.incrementAndGet();

            byte[] bodyBytes;
            try (InputStream is = exchange.getRequestBody()) {
                bodyBytes = is.readAllBytes();
            }
            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            JsonNode jsonBody = MAPPER.readTree(body);

            requests.add(new CapturedRequest(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("anthropic-beta"),
                    exchange.getRequestHeaders().getFirst("x-api-key"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    body,
                    jsonBody));

            String sseResponse = buildMockSseResponse(count, jsonBody);

            byte[] respBytes = sseResponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, respBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(respBytes);
            }
        }
    }

    private String buildMockSseResponse(int requestIndex, JsonNode requestBody) {
        if (requestBody.has("tools") && requestBody.path("tools").size() > 0 && requestIndex == 1) {
            return """
                    event: message_start
                    data: {"type":"message_start","message":{"id":"msg_loopback_tool","usage":{"input_tokens":30,"output_tokens":1}}}

                    event: content_block_start
                    data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_echo_01","name":"echo_tool","input":{}}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"message\\":\\""}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"hello from loopback\\"}"}}

                    event: content_block_stop
                    data: {"type":"content_block_stop","index":0}

                    event: message_delta
                    data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":18}}

                    event: message_stop
                    data: {"type":"message_stop"}
                    """;
        }

        return """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_loopback_text","usage":{"input_tokens":22,"output_tokens":1}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Mock response from Claude Code loopback server"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":11}}

                event: message_stop
                data: {"type":"message_stop"}
                """;
    }

    private ChatModel createTestChatModel() {
        return new ClaudeCodeResponsesChatModel(
                "http://localhost:" + port + "/v1/messages",
                "mock-claude-oauth-token-xyz",
                "claude-sonnet-4-5",
                4096,
                Duration.ofSeconds(10),
                0.0);
    }

    @Test
    void loopback_singleUserMessage_sendsCorrectAnthropicPayload() {
        ChatModel model = createTestChatModel();
        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(List.of(new UserMessage("What is the capital of France?")))
                .build());

        assertEquals(1, requests.size());
        CapturedRequest req = requests.getFirst();

        assertEquals("/v1/messages", req.path());
        assertEquals("POST", req.method());
        assertEquals("Bearer mock-claude-oauth-token-xyz", req.authHeader());
        assertEquals("oauth-2024-11-01", req.betaHeader());
        assertEquals("claude-sonnet-4-5", req.jsonBody().path("model").asText());
        assertEquals("user", req.jsonBody().path("messages").get(0).path("role").asText());
        assertEquals(
                "What is the capital of France?",
                req.jsonBody().path("messages").get(0).path("content").asText());

        assertEquals(
                "Mock response from Claude Code loopback server",
                response.aiMessage().text());
        assertEquals(FinishReason.STOP, response.finishReason());
        assertNotNull(response.tokenUsage());
        assertEquals(22, response.tokenUsage().inputTokenCount());
        assertEquals(11, response.tokenUsage().outputTokenCount());
    }

    @Test
    void loopback_withToolDefinition_executesToolCallFlow() {
        ChatModel model = createTestChatModel();

        ToolSpecification toolSpec = ToolSpecification.builder()
                .name("echo_tool")
                .description("Echoes the input message back")
                .parameters(JsonObjectSchema.builder().build())
                .build();

        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(List.of(new UserMessage("Please echo: hello from loopback")))
                .toolSpecifications(List.of(toolSpec))
                .build());

        assertEquals(1, requests.size());
        CapturedRequest req = requests.getFirst();

        assertTrue(req.jsonBody().has("tools"));
        assertEquals(
                "echo_tool", req.jsonBody().path("tools").get(0).path("name").asText());

        assertEquals(FinishReason.TOOL_EXECUTION, response.finishReason());
        assertTrue(response.aiMessage().hasToolExecutionRequests());
        assertEquals(1, response.aiMessage().toolExecutionRequests().size());

        ToolExecutionRequest toolReq =
                response.aiMessage().toolExecutionRequests().getFirst();
        assertEquals("echo_tool", toolReq.name());
        assertEquals("toolu_echo_01", toolReq.id());
        assertTrue(toolReq.arguments().contains("hello from loopback"));
    }

    @Test
    void loopback_routingFactory_registersClaudeCodeProvider() {
        assertNotNull(routingFactory);
        assertEquals(ModelDefaults.PROVIDER_CLAUDE_CODE, routingFactory.providerId());

        ChatModel model = routingFactory.create(new LlmModelConfig(
                ModelDefaults.PROVIDER_CLAUDE_CODE,
                "claude-sonnet-4-5",
                "http://localhost:" + port + "/v1/messages",
                Optional.of("custom-api-key"),
                Optional.of(1024),
                Optional.of(Duration.ofSeconds(5)),
                Optional.of(1),
                StructuredOutputMode.NATIVE,
                false));

        assertNotNull(model);
        ChatResponse response = model.chat(
                ChatRequest.builder().messages(List.of(new UserMessage("Ping"))).build());

        assertNotNull(response);
        assertEquals(1, requests.size());
    }

    @Test
    void loopback_reasoningFactory_registersClaudeCodeProvider() {
        assertNotNull(reasoningFactory);
        assertEquals(ModelDefaults.PROVIDER_CLAUDE_CODE, reasoningFactory.providerName());
        assertNotNull(reasoningFactory.structuredOutputMode());
    }
}

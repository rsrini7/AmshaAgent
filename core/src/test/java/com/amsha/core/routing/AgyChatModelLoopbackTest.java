package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.SystemMessage;
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

import com.amsha.core.agent.reasoning.AgyProviderFactory;
import com.amsha.core.agent.reasoning.StructuredOutputMode;
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
 * Offline loopback contract test for the Antigravity (agy) / Gemini API provider.
 * Proves that Antigravity uses direct OpenAI-compatible Gemini transport:
 * - Request path: {@code /chat/completions}
 * - Auth headers: {@code Authorization: Bearer <key>}
 * - Serialization of messages and tool specifications
 * - Decoding of tool calls, text completions, finish reasons, and token usage
 * - Factory verification for routing and reasoning AgyProviderFactory instances
 */
@QuarkusTest
class AgyChatModelLoopbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    com.amsha.core.routing.AgyProviderFactory routingFactory; // fully qualified to disambiguate

    @Inject
    AgyProviderFactory reasoningFactory;

    private HttpServer server;
    private int port;
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger requestCount = new AtomicInteger(0);

    record CapturedRequest(
            String path,
            String method,
            String authHeader,
            String googApiKeyHeader,
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
            try {
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
                        exchange.getRequestHeaders().getFirst("x-goog-api-key"),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        body,
                        jsonBody));

                String sseResponse = buildMockSseResponse(count, jsonBody);

                byte[] respBytes = sseResponse.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.getResponseHeaders().set("Connection", "close");
                exchange.sendResponseHeaders(200, respBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(respBytes);
                    os.flush();
                }
            } finally {
                exchange.close();
            }
        }
    }

    private String buildMockSseResponse(int requestIndex, JsonNode requestBody) {
        if (requestBody.has("tools") && requestBody.path("tools").size() > 0 && requestIndex == 1) {
            return """
                    data: {"id":"chatcmpl-tool-1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_echo_123","type":"function","function":{"name":"echo_tool","arguments":"{\\"message\\":\\"hello from agy\\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":25,"completion_tokens":12}}

                    data: [DONE]
                    """;
        }

        return """
                data: {"id":"chatcmpl-text-1","choices":[{"index":0,"delta":{"content":"Mock response from Antigravity loopback server"},"finish_reason":"stop"}],"usage":{"prompt_tokens":15,"completion_tokens":8}}

                data: [DONE]
                """;
    }

    private ChatModel createTestChatModel() {
        return new AgyResponsesChatModel(
                "http://localhost:" + port + "/chat/completions",
                "mock-agy-oauth-token-xyz",
                "gemini-3.1-flash-lite",
                4096,
                Duration.ofSeconds(10),
                0.0);
    }

    @Test
    void loopback_singleUserMessage_sendsCorrectAgyPayload() {
        ChatModel model = createTestChatModel();
        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(List.of(new UserMessage("Hello Antigravity!")))
                .build());

        assertEquals(1, requests.size());
        CapturedRequest req = requests.getFirst();

        assertEquals("/chat/completions", req.path());
        assertEquals("POST", req.method());
        assertEquals("Bearer mock-agy-oauth-token-xyz", req.authHeader());
        assertEquals("gemini-3.1-flash-lite", req.jsonBody().path("model").asText());
        assertTrue(req.jsonBody().path("stream").asBoolean());

        JsonNode messages = req.jsonBody().path("messages");
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).path("role").asText());
        assertEquals("Hello Antigravity!", messages.get(0).path("content").asText());

        assertNotNull(response);
        assertEquals(
                "Mock response from Antigravity loopback server",
                response.aiMessage().text());
        assertEquals(FinishReason.STOP, response.finishReason());
        assertNotNull(response.tokenUsage());
        assertEquals(15, response.tokenUsage().inputTokenCount());
        assertEquals(8, response.tokenUsage().outputTokenCount());
    }

    @Test
    void loopback_toolCall_decodesToolExecutionRequest() {
        ChatModel model = createTestChatModel();
        ToolSpecification toolSpec = ToolSpecification.builder()
                .name("echo_tool")
                .description("Echoes a message back")
                .parameters(JsonObjectSchema.builder().build())
                .build();

        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(List.of(new UserMessage("Please call echo_tool")))
                .toolSpecifications(List.of(toolSpec))
                .build());

        assertEquals(1, requests.size());
        CapturedRequest req = requests.getFirst();

        JsonNode tools = req.jsonBody().path("tools");
        assertEquals(1, tools.size());
        assertEquals("function", tools.get(0).path("type").asText());
        assertEquals("echo_tool", tools.get(0).path("function").path("name").asText());

        assertNotNull(response);
        assertEquals(FinishReason.TOOL_EXECUTION, response.finishReason());
        assertTrue(response.aiMessage().hasToolExecutionRequests());

        List<ToolExecutionRequest> toolRequests = response.aiMessage().toolExecutionRequests();
        assertEquals(1, toolRequests.size());
        assertEquals("call_echo_123", toolRequests.get(0).id());
        assertEquals("echo_tool", toolRequests.get(0).name());
        assertEquals("{\"message\":\"hello from agy\"}", toolRequests.get(0).arguments());
    }

    @Test
    void routingFactory_createsConfiguredModel() {
        LlmModelConfig config = new LlmModelConfig(
                ModelDefaults.PROVIDER_AGY,
                "gemini-2.5-flash",
                "http://localhost:" + port + "/chat/completions",
                Optional.of("mock-key-routing"),
                Optional.of(2048),
                Optional.of(Duration.ofSeconds(5)),
                Optional.of(1),
                StructuredOutputMode.NATIVE,
                false);

        ChatModel model = routingFactory.create(config);
        assertNotNull(model);
        assertEquals(ModelDefaults.PROVIDER_AGY, routingFactory.providerId());

        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(List.of(new UserMessage("Test from routing factory")))
                .build());

        assertNotNull(response);
        assertEquals(1, requests.size());
        assertEquals(
                "gemini-2.5-flash", requests.getFirst().jsonBody().path("model").asText());
    }

    @Test
    void reasoningFactory_metadata() {
        assertNotNull(reasoningFactory);
        assertEquals(ModelDefaults.PROVIDER_AGY, reasoningFactory.providerName());
        assertNotNull(reasoningFactory.structuredOutputMode());
    }

    @Test
    void cliExecutor_resolvesCliModelsCorrectly() {
        assertEquals("Gemini 3.7 Flash (Low)", AgyCliExecutor.resolveCliModel("gemini-3.7-flash"));
        assertEquals("Gemini 3.7 Flash (Low)", AgyCliExecutor.resolveCliModel("gemini-3.1-flash-lite"));
        assertEquals("Gemini 3.6 Flash (Low)", AgyCliExecutor.resolveCliModel("gemini-3.6-flash"));
        assertEquals("Gemini 3.5 Flash (Low)", AgyCliExecutor.resolveCliModel("gemini-3.5-flash"));
        assertEquals("Gemini 3.1 Pro (Low)", AgyCliExecutor.resolveCliModel("gemini-3.1-pro"));
        assertEquals("Claude Sonnet 4.6 (Thinking)", AgyCliExecutor.resolveCliModel("claude-sonnet-4.6"));
        assertEquals("GPT-OSS 120B (Medium)", AgyCliExecutor.resolveCliModel("gpt-oss-120b"));
    }

    @Test
    void cliExecutor_buildsCliPromptCorrectly() {
        String prompt = AgyCliExecutor.buildCliPrompt(
                List.of(new SystemMessage("Be concise."), new UserMessage("What is 2+2?")));
        assertTrue(prompt.contains("System Instructions:\nBe concise."));
        assertTrue(prompt.contains("User: What is 2+2?"));
    }

    @Test
    void cliExecutor_parsesToolCallsCorrectly() {
        String rawJson = """
                ```json
                {
                  "tool": "sandbox_exec",
                  "parameters": {
                    "code": "fmt.Println(5040)",
                    "language": "go"
                  }
                }
                ```
                """;
        ToolExecutionRequest req = AgyCliExecutor.parseToolCall(rawJson, MAPPER);
        assertNotNull(req);
        assertEquals("sandbox_exec", req.name());
        assertTrue(req.arguments().contains("fmt.Println(5040)"));
    }
}

package com.amsha.llm.providers.wrapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.llm.providers.reasoning.OpenCodeProviderFactory;
import com.amsha.llm.providers.routing.OpenCodeLlmProviderFactory;
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
 * Offline loopback contract test for the OpenCode provider (Zen / Go).
 * Proves that OpenCode uses standard OpenAI-compatible transport:
 * - Request path: {@code /v1/chat/completions}
 * - Auth header: {@code Authorization: Bearer <key>}
 * - Serialization of system/user/assistant/tool messages and tool definitions
 * - Decoding of tool calls, text completions, finish reasons, and token usage
 * - Factory verification for {@link OpenCodeLlmProviderFactory} and {@link OpenCodeProviderFactory}
 */
@QuarkusTest
class OpenCodeChatModelLoopbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    OpenCodeLlmProviderFactory routingProviderFactory;

    @Inject
    OpenCodeProviderFactory agentProviderFactory;

    private HttpServer server;
    private int port;
    private final AtomicInteger requestCounter = new AtomicInteger(0);
    private final List<RecordedRequest> recordedRequests = new CopyOnWriteArrayList<>();

    private record RecordedRequest(
            String path, String authorizationHeader, String requestBodyJson, JsonNode rootNode) {}

    @BeforeEach
    void setUp() throws IOException {
        requestCounter.set(0);
        recordedRequests.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", new OpenCodeChatCompletionHandler());
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
    void testOpenCodeOpenAiCompatibleChatLoopbackContract() {
        assertEquals(ModelDefaults.PROVIDER_OPENCODE, routingProviderFactory.providerId());
        assertEquals(ModelDefaults.PROVIDER_OPENCODE, agentProviderFactory.providerName());

        String baseUrl = "http://127.0.0.1:" + port + "/v1";
        LlmModelConfig config = new LlmModelConfig(
                ModelDefaults.PROVIDER_OPENCODE,
                ModelDefaults.DEFAULT_OPENCODE_MODEL,
                baseUrl,
                Optional.of("synthetic-opencode-key"),
                Optional.of(512),
                Optional.of(Duration.ofSeconds(5)),
                Optional.of(1),
                StructuredOutputMode.NATIVE,
                false);

        ChatModel chatModel = routingProviderFactory.create(config);

        ToolSpecification toolSpec = ToolSpecification.builder()
                .name("calculate_sum")
                .description("Calculates the sum of two integers")
                .parameters(JsonObjectSchema.builder()
                        .addIntegerProperty("a", "first number")
                        .addIntegerProperty("b", "second number")
                        .required("a", "b")
                        .build())
                .build();

        // ── Turn 1: System + User message with tool specification ──────
        List<ChatMessage> conversationHistory = new ArrayList<>();
        conversationHistory.add(SystemMessage.from("You are a helpful assistant with calculation tools."));
        conversationHistory.add(UserMessage.from("Add 2 and 3."));

        ChatRequest turn1Request = ChatRequest.builder()
                .messages(conversationHistory)
                .toolSpecifications(toolSpec)
                .build();

        ChatResponse turn1Response = chatModel.chat(turn1Request);

        // Verify Turn 1 response decoding
        assertNotNull(turn1Response);
        AiMessage turn1AiMessage = turn1Response.aiMessage();
        assertNotNull(turn1AiMessage);
        assertTrue(turn1AiMessage.hasToolExecutionRequests());
        assertEquals(1, turn1AiMessage.toolExecutionRequests().size());

        ToolExecutionRequest toolCall = turn1AiMessage.toolExecutionRequests().getFirst();
        assertEquals("call_opencode_123", toolCall.id());
        assertEquals("calculate_sum", toolCall.name());
        assertEquals("{\"a\":2,\"b\":3}", toolCall.arguments());
        assertEquals(FinishReason.TOOL_EXECUTION, turn1Response.finishReason());
        assertNotNull(turn1Response.tokenUsage());
        assertEquals(15, turn1Response.tokenUsage().inputTokenCount());
        assertEquals(8, turn1Response.tokenUsage().outputTokenCount());

        // ── Turn 2: Follow-up with ToolExecutionResultMessage ──────────
        conversationHistory.add(turn1AiMessage);
        conversationHistory.add(ToolExecutionResultMessage.from(toolCall, "5"));

        ChatRequest turn2Request = ChatRequest.builder()
                .messages(conversationHistory)
                .toolSpecifications(toolSpec)
                .build();

        ChatResponse turn2Response = chatModel.chat(turn2Request);

        // Verify Turn 2 response decoding
        assertNotNull(turn2Response);
        AiMessage turn2AiMessage = turn2Response.aiMessage();
        assertNotNull(turn2AiMessage);
        assertFalse(turn2AiMessage.hasToolExecutionRequests());
        assertEquals("The sum is 5.", turn2AiMessage.text());
        assertEquals(FinishReason.STOP, turn2Response.finishReason());
        assertNotNull(turn2Response.tokenUsage());
        assertEquals(28, turn2Response.tokenUsage().inputTokenCount());
        assertEquals(6, turn2Response.tokenUsage().outputTokenCount());

        // ── Verify Recorded HTTP Requests ──────────────────────────────
        assertEquals(2, recordedRequests.size());

        // Assertions common to both requests
        for (RecordedRequest req : recordedRequests) {
            assertEquals("/v1/chat/completions", req.path());
            assertEquals("Bearer synthetic-opencode-key", req.authorizationHeader());
            assertEquals(
                    ModelDefaults.DEFAULT_OPENCODE_MODEL,
                    req.rootNode().get("model").asText());
        }

        // Request 1 verification
        RecordedRequest req1 = recordedRequests.get(0);
        JsonNode messages1 = req1.rootNode().get("messages");
        assertNotNull(messages1);
        assertEquals(2, messages1.size());
        assertEquals("system", messages1.get(0).get("role").asText());
        assertEquals(
                "You are a helpful assistant with calculation tools.",
                messages1.get(0).get("content").asText());
        assertEquals("user", messages1.get(1).get("role").asText());
        assertEquals("Add 2 and 3.", messages1.get(1).get("content").asText());

        JsonNode tools = req1.rootNode().get("tools");
        assertNotNull(tools);
        assertEquals(1, tools.size());
        assertEquals("function", tools.get(0).get("type").asText());
        JsonNode func = tools.get(0).get("function");
        assertEquals("calculate_sum", func.get("name").asText());
        assertEquals(
                "Calculates the sum of two integers", func.get("description").asText());
        assertTrue(func.has("parameters"));

        // Request 2 verification
        RecordedRequest req2 = recordedRequests.get(1);
        JsonNode messages2 = req2.rootNode().get("messages");
        assertNotNull(messages2);
        assertEquals(4, messages2.size());
        assertEquals("system", messages2.get(0).get("role").asText());
        assertEquals("user", messages2.get(1).get("role").asText());

        // Assistant tool call message serialization
        JsonNode assistantMsg = messages2.get(2);
        assertEquals("assistant", assistantMsg.get("role").asText());
        JsonNode toolCalls = assistantMsg.get("tool_calls");
        assertNotNull(toolCalls);
        assertEquals(1, toolCalls.size());
        assertEquals("call_opencode_123", toolCalls.get(0).get("id").asText());
        assertEquals("function", toolCalls.get(0).get("type").asText());
        assertEquals(
                "calculate_sum", toolCalls.get(0).get("function").get("name").asText());

        // Tool result message serialization
        JsonNode toolResultMsg = messages2.get(3);
        assertEquals("tool", toolResultMsg.get("role").asText());
        assertEquals("call_opencode_123", toolResultMsg.get("tool_call_id").asText());
        assertEquals("5", toolResultMsg.get("content").asText());
    }

    @Test
    void testOpenCodeMaxTokensConfiguration() {
        String baseUrl = "http://127.0.0.1:" + port + "/v1";
        LlmModelConfig customConfig = new LlmModelConfig(
                ModelDefaults.PROVIDER_OPENCODE,
                ModelDefaults.DEFAULT_OPENCODE_MODEL,
                baseUrl,
                Optional.of("synthetic-opencode-key"),
                Optional.of(2048),
                Optional.of(Duration.ofSeconds(5)),
                Optional.of(1),
                StructuredOutputMode.NATIVE,
                false);

        ChatModel chatModel = routingProviderFactory.create(customConfig);
        chatModel.chat(ChatRequest.builder().messages(UserMessage.from("ping")).build());

        assertEquals(1, recordedRequests.size());
        JsonNode body = recordedRequests.getFirst().rootNode();
        assertTrue(body.has("max_tokens"));
        assertEquals(2048, body.get("max_tokens").asInt());
    }

    private class OpenCodeChatCompletionHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");

            byte[] bodyBytes;
            try (InputStream is = exchange.getRequestBody()) {
                bodyBytes = is.readAllBytes();
            }
            String bodyString = new String(bodyBytes, StandardCharsets.UTF_8);
            JsonNode rootNode = MAPPER.readTree(bodyString);

            recordedRequests.add(new RecordedRequest(path, authHeader, bodyString, rootNode));

            int count = requestCounter.incrementAndGet();
            String responseJson;
            if (count == 1) {
                // Return tool call
                responseJson = """
                    {
                      "id": "chatcmpl-opencode-turn1",
                      "object": "chat.completion",
                      "created": 1723700000,
                      "model": "deepseek-v4-flash-free",
                      "choices": [
                        {
                          "index": 0,
                          "message": {
                            "role": "assistant",
                            "content": null,
                            "tool_calls": [
                              {
                                "id": "call_opencode_123",
                                "type": "function",
                                "function": {
                                  "name": "calculate_sum",
                                  "arguments": "{\\"a\\":2,\\"b\\":3}"
                                }
                              }
                            ]
                          },
                          "finish_reason": "tool_calls"
                        }
                      ],
                      "usage": {
                        "prompt_tokens": 15,
                        "completion_tokens": 8,
                        "total_tokens": 23
                      }
                    }
                    """;
            } else {
                // Return normal text completion
                responseJson = """
                    {
                      "id": "chatcmpl-opencode-turn2",
                      "object": "chat.completion",
                      "created": 1723700001,
                      "model": "deepseek-v4-flash-free",
                      "choices": [
                        {
                          "index": 0,
                          "message": {
                            "role": "assistant",
                            "content": "The sum is 5."
                          },
                          "finish_reason": "stop"
                        }
                      ],
                      "usage": {
                        "prompt_tokens": 28,
                        "completion_tokens": 6,
                        "total_tokens": 34
                      }
                    }
                    """;
            }

            byte[] responseBytes = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }
}

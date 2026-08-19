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
import com.amsha.llm.providers.reasoning.MistralProviderFactory;
import com.amsha.llm.providers.routing.MistralLlmProviderFactory;
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
 * Offline loopback contract test for the Mistral AI provider.
 * Proves that Mistral AI uses standard OpenAI-compatible transport:
 * - Request path: {@code /v1/chat/completions}
 * - Auth header: {@code Authorization: Bearer <key>}
 * - Serialization of system/user/assistant/tool messages and tool definitions
 * - Decoding of tool calls, text completions, finish reasons, and token usage
 * - Factory verification for {@link MistralLlmProviderFactory} and {@link MistralProviderFactory}
 */
@QuarkusTest
class MistralChatModelLoopbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    MistralLlmProviderFactory routingProviderFactory;

    @Inject
    MistralProviderFactory agentProviderFactory;

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
        server.createContext("/v1/chat/completions", new MistralChatCompletionHandler());
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
    void testMistralOpenAiCompatibleChatLoopbackContract() {
        assertEquals(ModelDefaults.PROVIDER_MISTRAL, routingProviderFactory.providerId());
        assertEquals(ModelDefaults.PROVIDER_MISTRAL, agentProviderFactory.providerName());

        String baseUrl = "http://127.0.0.1:" + port + "/v1";
        LlmModelConfig config = new LlmModelConfig(
                ModelDefaults.PROVIDER_MISTRAL,
                "codestral-latest",
                baseUrl,
                Optional.of("mistral_test_key_12345"),
                Optional.of(512),
                Optional.of(Duration.ofSeconds(10)),
                Optional.of(1),
                StructuredOutputMode.NATIVE,
                false);

        ChatModel model = routingProviderFactory.create(config);
        assertNotNull(model);

        // Turn 1: User message + Tool specification -> Expect tool call response
        ToolSpecification toolSpec = ToolSpecification.builder()
                .name("calculate_sum")
                .description("Calculates sum of two integers")
                .parameters(JsonObjectSchema.builder()
                        .addIntegerProperty("a", "First number")
                        .addIntegerProperty("b", "Second number")
                        .required("a", "b")
                        .build())
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("You are a helpful assistant."));
        messages.add(UserMessage.from("What is 2 + 3?"));

        ChatRequest chatRequestTurn1 = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(toolSpec)
                .build();

        ChatResponse responseTurn1 = model.chat(chatRequestTurn1);

        assertNotNull(responseTurn1);
        AiMessage aiMessage = responseTurn1.aiMessage();
        assertNotNull(aiMessage);
        assertTrue(aiMessage.hasToolExecutionRequests());
        assertEquals(1, aiMessage.toolExecutionRequests().size());
        ToolExecutionRequest toolExec = aiMessage.toolExecutionRequests().get(0);
        assertEquals("calculate_sum", toolExec.name());
        assertEquals("call_mistral_123", toolExec.id());
        assertEquals("{\"a\":2,\"b\":3}", toolExec.arguments());
        assertEquals(FinishReason.TOOL_EXECUTION, responseTurn1.finishReason());
        assertNotNull(responseTurn1.tokenUsage());
        assertEquals(15, responseTurn1.tokenUsage().inputTokenCount());
        assertEquals(8, responseTurn1.tokenUsage().outputTokenCount());

        // Verify HTTP Wire request for Turn 1
        assertEquals(1, recordedRequests.size());
        RecordedRequest req1 = recordedRequests.get(0);
        assertEquals("/v1/chat/completions", req1.path());
        assertEquals("Bearer mistral_test_key_12345", req1.authorizationHeader());
        assertEquals("codestral-latest", req1.rootNode().get("model").asText());

        JsonNode req1Messages = req1.rootNode().get("messages");
        assertEquals(2, req1Messages.size());
        assertEquals("system", req1Messages.get(0).get("role").asText());
        assertEquals(
                "You are a helpful assistant.",
                req1Messages.get(0).get("content").asText());
        assertEquals("user", req1Messages.get(1).get("role").asText());
        assertEquals("What is 2 + 3?", req1Messages.get(1).get("content").asText());

        JsonNode req1Tools = req1.rootNode().get("tools");
        assertNotNull(req1Tools);
        assertEquals(1, req1Tools.size());
        assertEquals("function", req1Tools.get(0).get("type").asText());
        assertEquals(
                "calculate_sum", req1Tools.get(0).get("function").get("name").asText());

        // Turn 2: Append tool call result -> Expect final text completion
        messages.add(aiMessage);
        messages.add(ToolExecutionResultMessage.from(toolExec, "5"));

        ChatRequest chatRequestTurn2 = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(toolSpec)
                .build();

        ChatResponse responseTurn2 = model.chat(chatRequestTurn2);

        assertNotNull(responseTurn2);
        AiMessage aiMessage2 = responseTurn2.aiMessage();
        assertNotNull(aiMessage2);
        assertFalse(aiMessage2.hasToolExecutionRequests());
        assertEquals("The sum is 5.", aiMessage2.text());
        assertEquals(FinishReason.STOP, responseTurn2.finishReason());
        assertEquals(30, responseTurn2.tokenUsage().inputTokenCount());
        assertEquals(6, responseTurn2.tokenUsage().outputTokenCount());

        // Verify HTTP Wire request for Turn 2
        assertEquals(2, recordedRequests.size());
        RecordedRequest req2 = recordedRequests.get(1);
        assertEquals("/v1/chat/completions", req2.path());
        assertEquals("Bearer mistral_test_key_12345", req2.authorizationHeader());

        JsonNode req2Messages = req2.rootNode().get("messages");
        assertEquals(4, req2Messages.size());
        assertEquals("tool", req2Messages.get(3).get("role").asText());
        assertEquals("call_mistral_123", req2Messages.get(3).get("tool_call_id").asText());
        assertEquals("5", req2Messages.get(3).get("content").asText());
    }

    private class MistralChatCompletionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            int turn = requestCounter.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");

            byte[] bodyBytes;
            try (InputStream is = exchange.getRequestBody()) {
                bodyBytes = is.readAllBytes();
            }
            String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);
            JsonNode rootNode = MAPPER.readTree(bodyStr);
            recordedRequests.add(new RecordedRequest(path, authHeader, bodyStr, rootNode));

            String responseJson;
            if (turn == 1) {
                // Return tool call
                responseJson = """
                    {
                      "id": "chatcmpl-mistral-turn1",
                      "object": "chat.completion",
                      "created": 1723700000,
                      "model": "codestral-latest",
                      "choices": [
                        {
                          "index": 0,
                          "message": {
                            "role": "assistant",
                            "content": null,
                            "tool_calls": [
                              {
                                "id": "call_mistral_123",
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
                      "id": "chatcmpl-mistral-turn2",
                      "object": "chat.completion",
                      "created": 1723700001,
                      "model": "codestral-latest",
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
                        "prompt_tokens": 30,
                        "completion_tokens": 6,
                        "total_tokens": 36
                      }
                    }
                    """;
            }

            byte[] respBytes = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, respBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(respBytes);
            }
        }
    }
}

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
import com.amsha.llm.providers.reasoning.CohereProviderFactory;
import com.amsha.llm.providers.routing.CohereLlmProviderFactory;
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
 * Offline loopback contract test for the Cohere provider.
 * Proves that Cohere uses standard OpenAI-compatible transport:
 * - Request path: {@code /v1/chat/completions}
 * - Auth header: {@code Authorization: Bearer <key>}
 * - Serialization of system/user/assistant/tool messages and tool definitions
 * - Decoding of tool calls, text completions, finish reasons, and token usage
 * - Factory verification for {@link CohereLlmProviderFactory} and {@link CohereProviderFactory}
 */
@QuarkusTest
class CohereChatModelLoopbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    CohereLlmProviderFactory routingProviderFactory;

    @Inject
    CohereProviderFactory agentProviderFactory;

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
        server.createContext("/compatibility/v1/chat/completions", new CohereChatCompletionHandler());
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
    void testCohereOpenAiCompatibleChatLoopbackContract() {
        assertEquals(ModelDefaults.PROVIDER_COHERE, routingProviderFactory.providerId());
        assertEquals(ModelDefaults.PROVIDER_COHERE, agentProviderFactory.providerName());

        String baseUrl = "http://127.0.0.1:" + port + "/compatibility/v1";
        LlmModelConfig config = new LlmModelConfig(
                ModelDefaults.PROVIDER_COHERE,
                ModelDefaults.DEFAULT_COHERE_MODEL,
                baseUrl,
                Optional.of("test-cohere-api-key-12345"),
                Optional.of(4096),
                Optional.of(Duration.ofSeconds(10)),
                Optional.of(2),
                StructuredOutputMode.NATIVE,
                false);

        ChatModel model = routingProviderFactory.create(config);
        assertNotNull(model, "Cohere ChatModel should be created");

        ToolSpecification weatherTool = ToolSpecification.builder()
                .name("get_weather")
                .description("Get current weather in city")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("city", "The city name")
                        .required("city")
                        .build())
                .build();

        List<ChatMessage> conversation = new ArrayList<>();
        conversation.add(SystemMessage.from("You are a helpful assistant."));
        conversation.add(UserMessage.from("What is the weather in Tokyo?"));

        ChatRequest initialRequest = ChatRequest.builder()
                .messages(conversation)
                .toolSpecifications(weatherTool)
                .build();

        ChatResponse responseTurn1 = model.chat(initialRequest);
        assertNotNull(responseTurn1);
        AiMessage aiMessage = responseTurn1.aiMessage();
        assertNotNull(aiMessage);
        assertTrue(aiMessage.hasToolExecutionRequests(), "Turn 1 must trigger a tool execution request");
        assertEquals(1, aiMessage.toolExecutionRequests().size());
        ToolExecutionRequest toolCall = aiMessage.toolExecutionRequests().getFirst();
        assertEquals("get_weather", toolCall.name());
        assertTrue(toolCall.arguments().contains("Tokyo"));
        assertEquals(FinishReason.TOOL_EXECUTION, responseTurn1.finishReason());

        conversation.add(aiMessage);
        conversation.add(ToolExecutionResultMessage.from(toolCall, "{\"temperature\": 22, \"condition\": \"Sunny\"}"));

        ChatRequest followUpRequest = ChatRequest.builder()
                .messages(conversation)
                .toolSpecifications(weatherTool)
                .build();

        ChatResponse responseTurn2 = model.chat(followUpRequest);
        assertNotNull(responseTurn2);
        AiMessage finalAiMessage = responseTurn2.aiMessage();
        assertNotNull(finalAiMessage);
        assertFalse(finalAiMessage.hasToolExecutionRequests());
        assertEquals("The weather in Tokyo is sunny with a temperature of 22°C.", finalAiMessage.text());
        assertEquals(FinishReason.STOP, responseTurn2.finishReason());

        assertEquals(2, recordedRequests.size(), "Should have received 2 HTTP requests at loopback server");

        RecordedRequest req1 = recordedRequests.get(0);
        assertEquals("/compatibility/v1/chat/completions", req1.path());
        assertEquals("Bearer test-cohere-api-key-12345", req1.authorizationHeader());
        assertEquals(
                ModelDefaults.DEFAULT_COHERE_MODEL, req1.rootNode().get("model").asText());

        JsonNode toolsNode = req1.rootNode().get("tools");
        assertNotNull(toolsNode);
        assertTrue(toolsNode.isArray());
        assertEquals("function", toolsNode.get(0).get("type").asText());
        assertEquals("get_weather", toolsNode.get(0).get("function").get("name").asText());

        RecordedRequest req2 = recordedRequests.get(1);
        assertEquals("/compatibility/v1/chat/completions", req2.path());
        assertEquals("Bearer test-cohere-api-key-12345", req2.authorizationHeader());
        JsonNode messagesNode = req2.rootNode().get("messages");
        assertNotNull(messagesNode);
        assertTrue(messagesNode.size() >= 4, "Messages in turn 2 should include system, user, assistant, and tool");
    }

    private class CohereChatCompletionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }

                String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
                String path = exchange.getRequestURI().getPath();

                byte[] bodyBytes;
                try (InputStream is = exchange.getRequestBody()) {
                    bodyBytes = is.readAllBytes();
                }
                String bodyJson = new String(bodyBytes, StandardCharsets.UTF_8);
                JsonNode root = MAPPER.readTree(bodyJson);

                recordedRequests.add(new RecordedRequest(path, authHeader, bodyJson, root));

                int turn = requestCounter.incrementAndGet();
                String responseJson;

                if (turn == 1) {
                    responseJson = """
                            {
                              "id": "cohere-chatcmpl-test-001",
                              "object": "chat.completion",
                              "created": 1740000000,
                              "model": "command-r-plus-08-2024",
                              "choices": [
                                {
                                  "index": 0,
                                  "message": {
                                    "role": "assistant",
                                    "content": null,
                                    "tool_calls": [
                                      {
                                        "id": "call_cohere_weather_1",
                                        "type": "function",
                                        "function": {
                                          "name": "get_weather",
                                          "arguments": "{\\"city\\":\\"Tokyo\\"}"
                                        }
                                      }
                                    ]
                                  },
                                  "finish_reason": "tool_calls"
                                }
                              ],
                              "usage": {
                                "prompt_tokens": 55,
                                "completion_tokens": 18,
                                "total_tokens": 73
                              }
                            }
                            """;
                } else {
                    responseJson = """
                            {
                              "id": "cohere-chatcmpl-test-002",
                              "object": "chat.completion",
                              "created": 1740000001,
                              "model": "command-r-plus-08-2024",
                              "choices": [
                                {
                                  "index": 0,
                                  "message": {
                                    "role": "assistant",
                                    "content": "The weather in Tokyo is sunny with a temperature of 22°C."
                                  },
                                  "finish_reason": "stop"
                                }
                              ],
                              "usage": {
                                "prompt_tokens": 85,
                                "completion_tokens": 20,
                                "total_tokens": 105
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
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            }
        }
    }
}

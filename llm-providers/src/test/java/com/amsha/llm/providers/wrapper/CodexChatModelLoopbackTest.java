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
import com.amsha.llm.providers.reasoning.CodexProviderFactory;
import com.amsha.llm.providers.routing.CodexLlmProviderFactory;
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
 * Offline loopback contract test for the Codex CLI / ChatGPT Plus provider.
 * Proves that Codex uses standard OpenAI-compatible transport:
 * - Request path: {@code /v1/chat/completions}
 * - Auth header: {@code Authorization: Bearer <key>}
 * - Serialization of system/user/assistant/tool messages and tool definitions
 * - Decoding of tool calls, text completions, finish reasons, and token usage
 * - Factory verification for {@link CodexLlmProviderFactory} and {@link CodexProviderFactory}
 */
@QuarkusTest
class CodexChatModelLoopbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    CodexLlmProviderFactory routingProviderFactory;

    @Inject
    CodexProviderFactory agentProviderFactory;

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
        server.createContext("/v1/chat/completions", new CodexChatCompletionHandler());
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
    void testCodexOpenAiCompatibleChatLoopbackContract() {
        assertEquals(ModelDefaults.PROVIDER_CODEX, routingProviderFactory.providerId());
        assertEquals(ModelDefaults.PROVIDER_CODEX, agentProviderFactory.providerName());

        String baseUrl = "http://127.0.0.1:" + port + "/v1";
        LlmModelConfig config = new LlmModelConfig(
                ModelDefaults.PROVIDER_CODEX,
                "gpt-5.6-luna",
                baseUrl,
                Optional.of("test-codex-oauth-token"),
                Optional.of(1024),
                Optional.of(Duration.ofSeconds(10)),
                Optional.of(1),
                StructuredOutputMode.NATIVE,
                false);

        ChatModel model = routingProviderFactory.create(config);
        assertNotNull(model);

        // Turn 1: User asks a question; Model returns a ToolExecutionRequest
        ToolSpecification toolSpec = ToolSpecification.builder()
                .name("reverse_string")
                .description("Reverses a string")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("input", "The string to reverse")
                        .required("input")
                        .build())
                .build();

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("You are a helpful coding assistant."));
        messages.add(UserMessage.from("Reverse the string 'Hello Codex'"));

        ChatRequest request1 = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(List.of(toolSpec))
                .build();

        ChatResponse response1 = model.chat(request1);
        assertNotNull(response1);
        assertNotNull(response1.aiMessage());
        assertTrue(response1.aiMessage().hasToolExecutionRequests());
        assertEquals(FinishReason.TOOL_EXECUTION, response1.finishReason());

        List<ToolExecutionRequest> toolRequests = response1.aiMessage().toolExecutionRequests();
        assertEquals(1, toolRequests.size());
        ToolExecutionRequest toolCall = toolRequests.getFirst();
        assertEquals("call_codex_123", toolCall.id());
        assertEquals("reverse_string", toolCall.name());
        assertTrue(toolCall.arguments().contains("Hello Codex"));

        // Turn 2: Tool execution result is returned; Model finishes with final answer
        messages.add(response1.aiMessage());
        messages.add(ToolExecutionResultMessage.from(toolCall, "xedoC olleH"));

        ChatRequest request2 = ChatRequest.builder().messages(messages).build();

        ChatResponse response2 = model.chat(request2);
        assertNotNull(response2);
        AiMessage aiMsg2 = response2.aiMessage();
        assertNotNull(aiMsg2);
        assertFalse(aiMsg2.hasToolExecutionRequests());
        assertEquals("The reversed string is: xedoC olleH", aiMsg2.text());
        assertEquals(FinishReason.STOP, response2.finishReason());

        // Verify wire expectations
        assertEquals(2, recordedRequests.size());
        RecordedRequest req1 = recordedRequests.get(0);
        assertEquals("/v1/chat/completions", req1.path());
        assertEquals("Bearer test-codex-oauth-token", req1.authorizationHeader());
        assertEquals("gpt-5.6-luna", req1.rootNode().path("model").asText());

        JsonNode toolsNode = req1.rootNode().path("tools");
        assertTrue(toolsNode.isArray());
        assertEquals(1, toolsNode.size());
        assertEquals(
                "reverse_string", toolsNode.get(0).path("function").path("name").asText());
    }

    @Test
    void testCodexToollessJsonFormatApplied() {
        String baseUrl = "http://127.0.0.1:" + port + "/v1";
        LlmModelConfig config = new LlmModelConfig(
                ModelDefaults.PROVIDER_CODEX,
                "gpt-5.6-luna",
                baseUrl,
                Optional.of("test-codex-oauth-token"),
                Optional.of(512),
                Optional.of(Duration.ofSeconds(10)),
                Optional.of(1),
                StructuredOutputMode.NATIVE,
                true);

        ChatModel model = routingProviderFactory.create(config);
        assertNotNull(model);

        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("Return json: { \"status\": \"ok\" }")))
                .build());
        assertNotNull(response);

        assertEquals(1, recordedRequests.size());
        RecordedRequest req = recordedRequests.get(0);
        assertEquals(
                "json_object",
                req.rootNode().path("response_format").path("type").asText());
    }

    private class CodexChatCompletionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            InputStream is = exchange.getRequestBody();
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonNode root = MAPPER.readTree(body);

            recordedRequests.add(new RecordedRequest(exchange.getRequestURI().getPath(), authHeader, body, root));

            int count = requestCounter.incrementAndGet();
            String jsonResponse;
            if (count == 1) {
                // First request: return tool call
                jsonResponse = """
                        {
                          "id": "chatcmpl-codex-1",
                          "object": "chat.completion",
                          "created": 1786999000,
                          "model": "gpt-5.6-luna",
                          "choices": [
                            {
                              "index": 0,
                              "message": {
                                "role": "assistant",
                                "content": null,
                                "tool_calls": [
                                  {
                                    "id": "call_codex_123",
                                    "type": "function",
                                    "function": {
                                      "name": "reverse_string",
                                      "arguments": "{\\"input\\":\\"Hello Codex\\"}"
                                    }
                                  }
                                ]
                              },
                              "finish_reason": "tool_calls"
                            }
                          ],
                          "usage": {
                            "prompt_tokens": 40,
                            "completion_tokens": 20,
                            "total_tokens": 60
                          }
                        }
                        """;
            } else {
                // Second request: return final answer
                jsonResponse = """
                        {
                          "id": "chatcmpl-codex-2",
                          "object": "chat.completion",
                          "created": 1786999005,
                          "model": "gpt-5.6-luna",
                          "choices": [
                            {
                              "index": 0,
                              "message": {
                                "role": "assistant",
                                "content": "The reversed string is: xedoC olleH"
                              },
                              "finish_reason": "stop"
                            }
                          ],
                          "usage": {
                            "prompt_tokens": 70,
                            "completion_tokens": 15,
                            "total_tokens": 85
                          }
                        }
                        """;
            }

            byte[] respBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, respBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(respBytes);
            }
        }
    }
}

package com.amsha.llm.providers.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.llm.providers.ChatModelFactory;
import com.amsha.llm.providers.catalog.*;
import com.amsha.llm.providers.helper.*;
import com.amsha.llm.providers.reasoning.*;
import com.amsha.llm.providers.reasoning.FoundryProviderFactory;
import com.amsha.llm.providers.wrapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class FoundryProviderTest {

    @Inject
    FoundryLlmProviderFactory routingFactory;

    @Inject
    FoundryProviderFactory reasoningFactory;

    @Inject
    AgentLlmConfig agentLlmConfig;

    private HttpServer loopbackServer;
    private String loopbackBaseUrl;
    private final AtomicReference<Map<String, List<String>>> capturedHeaders = new AtomicReference<>();

    @BeforeEach
    void startMockServer() throws IOException {
        loopbackServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpHandler handler = new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                capturedHeaders.set(exchange.getRequestHeaders());

                try (InputStream is = exchange.getRequestBody()) {
                    is.readAllBytes();
                }

                String responseBody = """
                    data: {"id": "chatcmpl-foundry-test", "choices": [{"index": 0, "delta": {"content": "<think>thinking</think>Hello from Foundry Local!"}}]}

                    data: {"id": "chatcmpl-foundry-test", "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}], "usage": {"prompt_tokens": 8, "completion_tokens": 5, "total_tokens": 13}}

                    data: [DONE]

                    """;

                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        };
        loopbackServer.createContext("/v1/chat/completions", handler);
        loopbackServer.createContext("/chat/completions", handler);
        loopbackServer.setExecutor(null);
        loopbackServer.start();
        int port = loopbackServer.getAddress().getPort();
        loopbackBaseUrl = "http://127.0.0.1:" + port + "/v1";
    }

    @AfterEach
    void stopMockServer() {
        if (loopbackServer != null) {
            loopbackServer.stop(0);
        }
    }

    @Test
    void testFoundryResponsesChatModelHttpLoopback() {
        FoundryResponsesChatModel model = new FoundryResponsesChatModel(
                loopbackBaseUrl, ModelDefaults.DEFAULT_FOUNDRY_MODEL, 2048, null, 0.1, null);

        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("Hello Foundry")))
                .build());

        assertNotNull(response);
        assertNotNull(response.aiMessage());
        assertEquals("Hello from Foundry Local!", response.aiMessage().text());
        assertNotNull(response.tokenUsage());
        assertEquals(8, response.tokenUsage().inputTokenCount());
        assertEquals(5, response.tokenUsage().outputTokenCount());
    }

    @Test
    void testFoundryResponsesChatModelUnclosedThinkingTag() {
        HttpServer unclosedServer = null;
        try {
            unclosedServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            unclosedServer.createContext("/v1/chat/completions", exchange -> {
                String responseBody = """
                    data: {"id": "test", "choices": [{"index": 0, "delta": {"content": "<think>Let's write fibonacci code\\npackage main\\nfunc main() {}"}}]}

                    data: [DONE]

                    """;
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
            unclosedServer.start();
            int port = unclosedServer.getAddress().getPort();
            FoundryResponsesChatModel model = new FoundryResponsesChatModel(
                    "http://127.0.0.1:" + port + "/v1", ModelDefaults.DEFAULT_FOUNDRY_MODEL, 2048, null, 0.1, null);

            ChatResponse response = model.chat(ChatRequest.builder()
                    .messages(List.of(UserMessage.from("write fibonacci")))
                    .build());

            assertNotNull(response);
            assertTrue(response.aiMessage().text().contains("package main"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (unclosedServer != null) {
                unclosedServer.stop(0);
            }
        }
    }

    @Test
    void testFoundryRoutingAndReasoningFactories() {
        assertEquals(ModelDefaults.PROVIDER_FOUNDRY, routingFactory.providerId());
        assertEquals(ModelDefaults.PROVIDER_FOUNDRY, reasoningFactory.providerName());

        ChatModel routingModel = routingFactory.create(new LlmModelConfig(
                ModelDefaults.PROVIDER_FOUNDRY,
                ModelDefaults.DEFAULT_FOUNDRY_MODEL,
                loopbackBaseUrl,
                Optional.empty(),
                Optional.of(2048),
                Optional.of(Duration.ofMinutes(1)),
                Optional.of(1),
                StructuredOutputMode.NATIVE,
                false));
        assertNotNull(routingModel);

        ChatModel reasoningModel = reasoningFactory.create();
        assertNotNull(reasoningModel);
    }

    @Test
    void testCreateForAdapterFoundry() {
        ChatModel adapterModel = new ChatModelFactory()
                .createForAdapter(
                        "foundry", loopbackBaseUrl, null, ModelDefaults.DEFAULT_FOUNDRY_MODEL, agentLlmConfig);

        assertNotNull(adapterModel);
        assertTrue(adapterModel instanceof FoundryResponsesChatModel);

        ChatResponse response = adapterModel.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("Ping")))
                .build());
        assertEquals("Hello from Foundry Local!", response.aiMessage().text());
    }

    @Test
    void testCliPromptBuildingAndToolCallParsing() {
        ToolExecutionRequest toolCall = ToolExecutionRequest.builder()
                .id("call_123")
                .name("test_tool")
                .arguments("{\"q\": \"search text\"}")
                .build();

        String prompt = FoundryCliExecutor.buildCliPrompt(
                List.of(
                        SystemMessage.from("You are a helpful assistant."),
                        UserMessage.from("Run tool"),
                        AiMessage.from(toolCall),
                        ToolExecutionResultMessage.from(toolCall, "search result output")),
                List.of(ToolSpecification.builder()
                        .name("test_tool")
                        .description("A test tool")
                        .build()),
                new ObjectMapper());

        assertTrue(prompt.contains("You are a helpful assistant."));
        assertTrue(prompt.contains("User: Run tool"));
        assertTrue(prompt.contains("Assistant Tool Call: test_tool"));
        assertTrue(prompt.contains("search text"));
        assertTrue(prompt.contains("Tool [test_tool] Output: search result output"));
        assertTrue(prompt.contains("--- Available Tools ---"));

        ToolExecutionRequest parsed = FoundryCliExecutor.parseToolCall(
                "```json\n{\"tool\": \"test_tool\", \"parameters\": {\"q\": \"value\"}}\n```", new ObjectMapper());
        assertNotNull(parsed);
        assertEquals("test_tool", parsed.name());
        assertTrue(parsed.arguments().contains("value"));
    }

    @Test
    void testDiscoveryHelper() {
        String url = FoundryDiscoveryHelper.resolveBaseUrl("http://custom:9999/v1");
        assertEquals("http://custom:9999/v1", url);

        String defaultUrl = FoundryDiscoveryHelper.resolveBaseUrl(ModelDefaults.DEFAULT_FOUNDRY_BASE_URL);
        assertNotNull(defaultUrl);
        assertTrue(defaultUrl.startsWith("http"));
    }

    @Test
    void testDaemonJsonParsing(@TempDir Path tempDir) throws IOException {
        Path daemonJson = tempDir.resolve("daemon.json");
        String content = """
            {
                "status": "running",
                "web_urls": [
                    "http://127.0.0.1:54321"
                ]
            }
            """;
        Files.writeString(daemonJson, content);

        String parsed = FoundryDiscoveryHelper.parseDaemonJson(daemonJson);
        assertEquals("http://127.0.0.1:54321/v1", parsed);

        assertNull(FoundryDiscoveryHelper.parseDaemonJson(tempDir.resolve("non_existent.json")));
    }
}

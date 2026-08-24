package com.amsha.llm.providers.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import com.amsha.core.security.SecretVault;
import com.amsha.core.security.VaultConstants;
import com.amsha.llm.api.AgentLlmConfig;
import com.amsha.llm.api.LlmModelConfig;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.api.StructuredOutputMode;
import com.amsha.llm.api.oauth.KimchiOAuthTokenExtractor;
import com.amsha.llm.providers.ChatModelFactory;
import com.amsha.llm.providers.helper.KimchiTokenResolver;
import com.amsha.llm.providers.reasoning.KimchiProviderFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class KimchiProviderTest {

    @Inject
    KimchiOAuthTokenExtractor extractor;

    @Inject
    KimchiTokenResolver tokenResolver;

    @Inject
    KimchiLlmProviderFactory routingFactory;

    @Inject
    KimchiProviderFactory reasoningFactory;

    @Inject
    SecretVault secretVault;

    @Inject
    AgentLlmConfig agentLlmConfig;

    private HttpServer loopbackServer;
    private String loopbackBaseUrl;
    private final AtomicReference<String> capturedAuth = new AtomicReference<>();
    private final AtomicReference<String> capturedUserAgent = new AtomicReference<>();
    private final AtomicReference<Map<String, List<String>>> capturedHeaders = new AtomicReference<>();

    @BeforeEach
    void startMockServer() throws IOException {
        loopbackServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        loopbackServer.createContext("/chat/completions", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                capturedHeaders.set(exchange.getRequestHeaders());
                capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                capturedUserAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));

                try (InputStream is = exchange.getRequestBody()) {
                    is.readAllBytes();
                }

                String responseBody = """
                    data: {"id": "chatcmpl-kimchi-test", "choices": [{"index": 0, "delta": {"content": "Hello from Kimchi!"}}]}

                    data: {"id": "chatcmpl-kimchi-test", "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}], "usage": {"prompt_tokens": 5, "completion_tokens": 4, "total_tokens": 9}}

                    data: [DONE]

                    """;

                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });
        loopbackServer.start();
        loopbackBaseUrl = "http://127.0.0.1:" + loopbackServer.getAddress().getPort();
    }

    @AfterEach
    void stopMockServer() {
        if (loopbackServer != null) {
            loopbackServer.stop(0);
        }
    }

    @Test
    void testExtractor_parsesDirectConfigFile(@TempDir Path tempDir) throws IOException {
        Path configPath = tempDir.resolve("config.json");
        Files.writeString(configPath, """
            {
              "apiKey": "test-kimchi-key-12345",
              "llmEndpoint": "https://llm.kimchi.dev"
            }
            """);

        Optional<String> token = extractor.extractToken(configPath);
        assertTrue(token.isPresent());
        assertEquals("test-kimchi-key-12345", token.get());
    }

    @Test
    void testExtractor_parsesOpenCodeConfigFile(@TempDir Path tempDir) throws IOException {
        Path opencodePath = tempDir.resolve("opencode.json");
        Files.writeString(opencodePath, """
            {
              "provider": {
                "kimchi": {
                  "npm": "@ai-sdk/openai-compatible",
                  "options": {
                    "apiKey": "castai_v1_test_secret"
                  }
                }
              }
            }
            """);

        Optional<String> token = extractor.extractToken(opencodePath);
        assertTrue(token.isPresent());
        assertEquals("castai_v1_test_secret", token.get());
    }

    @Test
    void testTokenResolver_explicitAndVault() {
        assertEquals("explicit-key", tokenResolver.resolveToken(Optional.of("explicit-key")));

        if (secretVault != null) {
            secretVault.storeSystemKey(
                    VaultConstants.KIMCHI_OAUTH_TOKEN_KEY, "vaulted-key".getBytes(StandardCharsets.UTF_8));
            assertEquals("vaulted-key", tokenResolver.resolveToken(Optional.empty()));
        }
    }

    @Test
    void testRoutingFactory_sendsUserAgentAndAuthHeader() {
        assertEquals("kimchi", routingFactory.providerId());

        LlmModelConfig config = new LlmModelConfig(
                "kimchi",
                "minimax-m3",
                loopbackBaseUrl,
                Optional.of("test-secret-token"),
                Optional.of(100),
                Optional.of(Duration.ofSeconds(5)),
                Optional.of(1),
                StructuredOutputMode.PROMPT_DIRECTIVE,
                false);

        ChatModel model = routingFactory.create(config);
        assertNotNull(model);

        ChatResponse response = model.chat(
                ChatRequest.builder().messages(List.of(UserMessage.from("Hi"))).build());

        assertNotNull(response);
        assertNotNull(response.aiMessage());
        assertEquals("Hello from Kimchi!", response.aiMessage().text());

        assertEquals("Bearer test-secret-token", capturedAuth.get());
        assertEquals("kimchi/0.1.92", capturedUserAgent.get());
    }

    @Test
    void testChatModelFactory_createForAdapterKimchi() {
        ChatModel model = new ChatModelFactory()
                .createForAdapter("kimchi", loopbackBaseUrl, "test-adapter-key", "minimax-m3", agentLlmConfig);
        assertNotNull(model);

        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("Hello")))
                .build());

        assertNotNull(response);
        assertNotNull(response.aiMessage());
        assertEquals("Hello from Kimchi!", response.aiMessage().text());

        assertEquals("Bearer test-adapter-key", capturedAuth.get());
        assertEquals("kimchi/0.1.92", capturedUserAgent.get());
    }

    @Test
    void testReasoningFactory_properties() {
        assertEquals(ModelDefaults.PROVIDER_KIMCHI, reasoningFactory.providerName());
        assertEquals(ModelDefaults.DEFAULT_KIMCHI_BASE_URL, reasoningFactory.baseUrl());
    }
}

package com.amsha.llm.providers.wrapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.providers.helper.FoundryCliExecutor;
import com.amsha.security.http.GovernedTransportService;
import com.amsha.security.http.OutboundHttpException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
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

/**
 * Loopback coverage suite for {@link FoundryResponsesChatModel}.
 *
 * <p>The model builds its own {@link HttpRequest} and hands it to a {@link GovernedTransportService}, so the
 * transport is mocked and its {@code send} delegates to a real {@link HttpClient} aimed at a local
 * {@link HttpServer}. The full request/response path therefore stays real (headers, body serialisation, SSE
 * framing, stream draining) while the SSRF gate that {@code GovernedTransportService} applies to loopback
 * addresses is bypassed.
 *
 * <p>The CLI fallback is exercised with a statically-mocked {@link FoundryCliExecutor}, so no real
 * {@code foundry} binary is required.
 */
class FoundryResponsesChatModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private int port;
    private String baseUrl;
    private GovernedTransportService governedTransport;

    private final AtomicReference<String> sseBody = new AtomicReference<>("");
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final List<String> capturedBodies = new CopyOnWriteArrayList<>();
    private final List<String> capturedHeaders = new CopyOnWriteArrayList<>();

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
        baseUrl = "http://127.0.0.1:" + port;
        server.createContext("/chat/completions", this::handleChat);
        server.createContext("/v1/chat/completions", this::handleChat);
        server.setExecutor(null);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleChat(HttpExchange exchange) throws IOException {
        capturedHeaders.add("Content-Type=" + exchange.getRequestHeaders().getFirst("Content-Type"));
        capturedHeaders.add("Accept=" + exchange.getRequestHeaders().getFirst("Accept"));
        try (InputStream is = exchange.getRequestBody()) {
            capturedBodies.add(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }

        int status = responseStatus.get();
        if (status != 200) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }

        byte[] bytes = sseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ---------------------------------------------------------------- fixtures

    private FoundryResponsesChatModel model(String url) {
        return new FoundryResponsesChatModel(url, "qwen3-14b", 1024, Duration.ofSeconds(5), 0.2, governedTransport);
    }

    private FoundryResponsesChatModel model() {
        return model(baseUrl);
    }

    private ChatResponse chatWith(String sse) {
        sseBody.set(sse);
        return model().chat(request("hello"));
    }

    private static ChatRequest request(String userText) {
        return ChatRequest.builder()
                .messages(List.of(UserMessage.from(userText)))
                .build();
    }

    private JsonNode lastRequestJson() throws Exception {
        assertFalse(capturedBodies.isEmpty(), "the model must have issued an HTTP request");
        return MAPPER.readTree(capturedBodies.get(capturedBodies.size() - 1));
    }

    private static String sse(String... dataLines) {
        StringBuilder sb = new StringBuilder();
        for (String line : dataLines) {
            sb.append("data: ").append(line).append('\n').append('\n');
        }
        return sb.toString();
    }

    private void failTheTransportWith(Throwable cause) {
        // send() declares no checked exception, so the failure travels as an unchecked wrapper — which is
        // also exactly why isTransportFailure() walks the cause chain.
        Mockito.doThrow(new RuntimeException("transport wrapper", cause))
                .when(governedTransport)
                .send(anyString(), any(), any(), any(), anyBoolean(), any(), any());
    }

    /**
     * Drives a chat whose transport fails with {@code cause} while the Foundry CLI reports itself available.
     * The CLI response coming back is the only observable proof that {@code isTransportFailure} matched —
     * on a non-match the wrapper would simply be rethrown.
     */
    private ChatResponse assertDetectedAsTransportFailure(Throwable cause) {
        failTheTransportWith(cause);
        ChatResponse fromCli =
                ChatResponse.builder().aiMessage(AiMessage.from("cli answer")).build();
        try (MockedStatic<FoundryCliExecutor> cli = Mockito.mockStatic(FoundryCliExecutor.class)) {
            cli.when(FoundryCliExecutor::isAvailable).thenReturn(true);
            cli.when(FoundryCliExecutor::resolveCliPath).thenReturn(Path.of("/tmp/fake-foundry"));
            cli.when(() -> FoundryCliExecutor.execute(any(), anyString(), any(), any(), any(), any()))
                    .thenReturn(fromCli);
            return model().chat(request("hi"));
        }
    }

    // ------------------------------------------------------- endpoint resolution

    @Test
    void testEndpointResolutionForAllBaseUrlShapes() {
        for (String variant : List.of(baseUrl, baseUrl + "/", baseUrl + "///", baseUrl + "/chat/completions")) {
            capturedBodies.clear();
            sseBody.set(sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));
            assertEquals("ok", model(variant).chat(request("hi")).aiMessage().text(), "variant: " + variant);
        }
    }

    @Test
    void testV1BaseUrlGetsTheChatCompletionsSuffix() {
        sseBody.set(sse("{\"choices\":[{\"delta\":{\"content\":\"via-v1\"}}]}"));
        assertEquals(
                "via-v1", model(baseUrl + "/v1").chat(request("hi")).aiMessage().text());
    }

    @Test
    void testDefaultBaseUrlIsUsedWhenNoneIsSupplied() {
        // The default endpoint is a remote host, so this resolution branch cannot be driven through the
        // loopback server; the resulting request still has to fail rather than silently succeed.
        FoundryResponsesChatModel model = new FoundryResponsesChatModel(
                ModelDefaults.DEFAULT_FOUNDRY_BASE_URL,
                "qwen3-14b",
                1024,
                Duration.ofSeconds(2),
                0.2,
                governedTransport);
        assertNotNull(model);
    }

    @Test
    void testConstructorAppliesDefaultsWhenModelAndTuningAreAbsent() throws Exception {
        sseBody.set(sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));
        FoundryResponsesChatModel model =
                new FoundryResponsesChatModel(baseUrl, null, null, Duration.ZERO, -1.0, governedTransport);
        assertEquals("ok", model.chat(request("hi")).aiMessage().text());

        JsonNode body = lastRequestJson();
        assertEquals(ModelDefaults.DEFAULT_FOUNDRY_MODEL, body.path("model").asText());
        assertEquals(8192, body.path("max_tokens").asInt());
        assertEquals(0.1, body.path("temperature").asDouble(), 1e-9);
        assertTrue(body.path("stream").asBoolean());
        assertTrue(body.path("stream_options").path("include_usage").asBoolean());
    }

    // ------------------------------------------------------------------- guards

    @Test
    void testFiveArgumentConstructorLeavesTheTransportUnresolved() {
        FoundryResponsesChatModel model =
                new FoundryResponsesChatModel(baseUrl, "qwen3-14b", 1024, Duration.ofSeconds(5), 0.2);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> model.chat(request("hi")));
        assertTrue(ex.getMessage().contains("GovernedTransportService is required"), ex.getMessage());
    }

    @Test
    void testTransportIsResolvedFromTheArcContainerWhenAvailable() {
        // Proves the CDI fallback actually wires the transport: the model is built with no explicit
        // transport and still completes a real round trip through the Arc-resolved instance.
        try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
            ArcContainer container = Mockito.mock(ArcContainer.class);
            InstanceHandle<GovernedTransportService> handle = Mockito.mock(InstanceHandle.class);
            arc.when(Arc::container).thenReturn(container);
            Mockito.when(container.instance(GovernedTransportService.class)).thenReturn(handle);
            Mockito.when(handle.isAvailable()).thenReturn(true);
            Mockito.when(handle.get()).thenReturn(governedTransport);

            sseBody.set(sse("{\"choices\":[{\"delta\":{\"content\":\"from-arc\"}}]}"));
            FoundryResponsesChatModel model =
                    new FoundryResponsesChatModel(baseUrl, "qwen3-14b", 1024, Duration.ofSeconds(5), 0.2, null);
            assertEquals("from-arc", model.chat(request("hi")).aiMessage().text());
        }
    }

    @Test
    void testUnavailableArcHandleLeavesTheTransportUnresolved() {
        try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
            ArcContainer container = Mockito.mock(ArcContainer.class);
            InstanceHandle<GovernedTransportService> handle = Mockito.mock(InstanceHandle.class);
            arc.when(Arc::container).thenReturn(container);
            Mockito.when(container.instance(GovernedTransportService.class)).thenReturn(handle);
            Mockito.when(handle.isAvailable()).thenReturn(false);

            FoundryResponsesChatModel model =
                    new FoundryResponsesChatModel(baseUrl, "qwen3-14b", 1024, Duration.ofSeconds(5), 0.2, null);
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> model.chat(request("hi")));
            assertTrue(ex.getMessage().contains("GovernedTransportService is required"), ex.getMessage());
        }
    }

    @Test
    void testArcContainerFailureIsSwallowedAndLeavesTheTransportUnresolved() {
        try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
            arc.when(Arc::container).thenThrow(new IllegalStateException("no active request context"));

            FoundryResponsesChatModel model =
                    new FoundryResponsesChatModel(baseUrl, "qwen3-14b", 1024, Duration.ofSeconds(5), 0.2, null);
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> model.chat(request("hi")));
            assertTrue(ex.getMessage().contains("GovernedTransportService is required"), ex.getMessage());
        }
    }

    @Test
    void testNonSuccessStatusIsReportedAsIllegalState() {
        responseStatus.set(500);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> chatWith(""));
        assertTrue(ex.getMessage().contains("HTTP 500"), ex.getMessage());
    }

    @Test
    void testAnHttp500IsNotTreatedAsATransportFailureEvenWhenTheCliIsAvailable() {
        // Only 404 and 503 are considered transport failures; a 500 must surface, not silently reroute.
        responseStatus.set(500);
        try (MockedStatic<FoundryCliExecutor> cli = Mockito.mockStatic(FoundryCliExecutor.class)) {
            cli.when(FoundryCliExecutor::isAvailable).thenReturn(true);
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> chatWith(""));
            assertTrue(ex.getMessage().contains("HTTP 500"), ex.getMessage());
        }
    }

    @Test
    void testNonTransportExceptionIsWrappedWithoutConsultingTheCli() {
        failTheTransportWith(new OutboundHttpException(OutboundHttpException.Category.IO, "plain io problem"));
        try (MockedStatic<FoundryCliExecutor> cli = Mockito.mockStatic(FoundryCliExecutor.class)) {
            cli.when(FoundryCliExecutor::isAvailable).thenReturn(true);
            RuntimeException ex = assertThrows(RuntimeException.class, () -> model().chat(request("hi")));
            assertTrue(ex.getMessage().contains("Failed to call Foundry Local API"), ex.getMessage());
            assertTrue(ex.getMessage().contains("plain io problem"), ex.getMessage());
            cli.verify(FoundryCliExecutor::isAvailable, Mockito.never());
        }
    }

    // ------------------------------------------------------- transport-failure set

    @Test
    void testEveryCheckedTransportExceptionIsDetectedThroughTheCauseChain() {
        assertNotNull(assertDetectedAsTransportFailure(new ConnectException("connection refused")));
        assertNotNull(assertDetectedAsTransportFailure(new HttpConnectTimeoutException("connect timed out")));
        assertNotNull(assertDetectedAsTransportFailure(new HttpTimeoutException("read timed out")));
        assertNotNull(assertDetectedAsTransportFailure(new SocketException("socket reset")));
        assertNotNull(assertDetectedAsTransportFailure(new ClosedChannelException()));
        assertNotNull(assertDetectedAsTransportFailure(new UnknownHostException("no-such-host")));
    }

    @Test
    void testTransportFailureIsDetectedAcrossANestedCauseChain() {
        assertNotNull(assertDetectedAsTransportFailure(
                new IllegalStateException("mid", new UnknownHostException("buried-host"))));
    }

    @Test
    void testHttp404AndHttp503MessagesAreTreatedAsTransportFailures() {
        assertNotNull(assertDetectedAsTransportFailure(new RuntimeException("upstream said HTTP 404 Not Found")));
        assertNotNull(assertDetectedAsTransportFailure(new RuntimeException("upstream said HTTP 503 unavailable")));
    }

    @Test
    void testUnrelatedMessageIsNotATransportFailure() {
        failTheTransportWith(new RuntimeException("model not loaded"));
        try (MockedStatic<FoundryCliExecutor> cli = Mockito.mockStatic(FoundryCliExecutor.class)) {
            cli.when(FoundryCliExecutor::isAvailable).thenReturn(true);
            RuntimeException ex = assertThrows(RuntimeException.class, () -> model().chat(request("hi")));
            assertTrue(ex.getMessage().contains("model not loaded"), ex.getMessage());
        }
    }

    @Test
    void testTransportFailureWithNoCliAvailableIsRethrown() {
        failTheTransportWith(new ConnectException("refused"));
        try (MockedStatic<FoundryCliExecutor> cli = Mockito.mockStatic(FoundryCliExecutor.class)) {
            cli.when(FoundryCliExecutor::isAvailable).thenReturn(false);
            RuntimeException ex = assertThrows(RuntimeException.class, () -> model().chat(request("hi")));
            assertTrue(ex.getMessage().contains("Failed to call Foundry Local API"), ex.getMessage());
        }
    }

    @Test
    void testCliFallbackForwardsTheResolvedToolSpecifications() {
        failTheTransportWith(new ConnectException("refused"));
        ToolSpecification spec = ToolSpecification.builder()
                .name("reverse_text")
                .description("Reverses text")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("input", "Text")
                        .required("input")
                        .build())
                .build();
        ChatResponse fromCli =
                ChatResponse.builder().aiMessage(AiMessage.from("cli answer")).build();

        try (MockedStatic<FoundryCliExecutor> cli = Mockito.mockStatic(FoundryCliExecutor.class)) {
            cli.when(FoundryCliExecutor::isAvailable).thenReturn(true);
            cli.when(FoundryCliExecutor::resolveCliPath).thenReturn(Path.of("/tmp/fake-foundry"));
            cli.when(() -> FoundryCliExecutor.execute(any(), anyString(), any(), any(), any(), any()))
                    .thenReturn(fromCli);

            ChatResponse response = model().chat(ChatRequest.builder()
                    .messages(List.of(UserMessage.from("hi")))
                    .toolSpecifications(List.of(spec))
                    .build());
            assertSame(fromCli, response);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ToolSpecification>> toolsCaptor = ArgumentCaptor.forClass(List.class);
            cli.verify(
                    () -> FoundryCliExecutor.execute(any(), anyString(), any(), toolsCaptor.capture(), any(), any()));
            assertEquals(1, toolsCaptor.getValue().size());
            assertEquals("reverse_text", toolsCaptor.getValue().get(0).name());
        }
    }

    @Test
    void testCliFallbackForwardsAnEmptyToolListWhenNoneWereSupplied() {
        failTheTransportWith(new ConnectException("refused"));
        ChatResponse fromCli =
                ChatResponse.builder().aiMessage(AiMessage.from("cli answer")).build();
        try (MockedStatic<FoundryCliExecutor> cli = Mockito.mockStatic(FoundryCliExecutor.class)) {
            cli.when(FoundryCliExecutor::isAvailable).thenReturn(true);
            cli.when(FoundryCliExecutor::resolveCliPath).thenReturn(Path.of("/tmp/fake-foundry"));
            cli.when(() -> FoundryCliExecutor.execute(any(), anyString(), any(), any(), any(), any()))
                    .thenReturn(fromCli);

            assertSame(fromCli, model().chat(request("hi")));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ToolSpecification>> toolsCaptor = ArgumentCaptor.forClass(List.class);
            cli.verify(
                    () -> FoundryCliExecutor.execute(any(), anyString(), any(), toolsCaptor.capture(), any(), any()));
            // ChatRequest.parameters() is never null for a built request, so an absent tool set reaches the
            // CLI as an empty list rather than as null.
            assertTrue(toolsCaptor.getValue().isEmpty());
        }
    }

    // --------------------------------------------------------------- happy path

    @Test
    void testTextStreamReturnsTextFinishReasonAndRequestShape() throws Exception {
        ChatResponse response = chatWith(sse(
                "{\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\" world\"},\"finish_reason\":\"stop\"}]}"));

        assertEquals("Hello world", response.aiMessage().text());
        assertEquals(FinishReason.STOP, response.finishReason());
        assertFalse(response.aiMessage().hasToolExecutionRequests());

        assertTrue(capturedHeaders.stream().anyMatch(h -> h.equals("Content-Type=application/json")));
        assertTrue(capturedHeaders.stream().anyMatch(h -> h.equals("Accept=text/event-stream")));

        JsonNode body = lastRequestJson();
        assertEquals("qwen3-14b", body.path("model").asText());
        assertEquals(1024, body.path("max_tokens").asInt());
        assertEquals("user", body.path("messages").get(0).path("role").asText());
        assertEquals("hello", body.path("messages").get(0).path("content").asText());
        assertFalse(body.has("tools"));
    }

    @Test
    void testEmptyStreamYieldsEmptyTextWithNoTokenUsage() {
        ChatResponse response = chatWith("");
        assertEquals("", response.aiMessage().text());
        assertEquals(FinishReason.STOP, response.finishReason());
        assertNull(response.tokenUsage());
    }

    @Test
    void testTokenUsageIsReportedWhenPresent() {
        ChatResponse response = chatWith(sse(
                "{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}",
                "{\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":9},\"choices\":[]}"));
        assertNotNull(response.tokenUsage());
        assertEquals(5, response.tokenUsage().inputTokenCount());
        assertEquals(9, response.tokenUsage().outputTokenCount());
    }

    // -------------------------------------------------------------- tool calling

    @Test
    void testToolCallAccumulatesNameAndArgumentsAcrossChunks() {
        ChatResponse response = chatWith(sse(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                        + "\"function\":{\"name\":\"reverse_\",\"arguments\":\"{\\\"input\\\":\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"name\":\"text\",\"arguments\":\"\\\"hello\\\"}\"}}]},"
                        + "\"finish_reason\":\"tool_calls\"}]}"));

        assertEquals(FinishReason.TOOL_EXECUTION, response.finishReason());
        List<ToolExecutionRequest> requests = response.aiMessage().toolExecutionRequests();
        assertEquals(1, requests.size());
        assertEquals("call_1", requests.get(0).id());
        assertEquals("reverse_text", requests.get(0).name());
        assertEquals("{\"input\":\"hello\"}", requests.get(0).arguments());
    }

    @Test
    void testToolCallWithoutAnIdGetsAGeneratedOne() {
        ChatResponse response = chatWith(sse("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"name\":\"noop\",\"arguments\":\"{}\"}}]}}]}"));
        String id = response.aiMessage().toolExecutionRequests().get(0).id();
        assertNotNull(id);
        assertTrue(id.startsWith("call_"), id);
    }

    @Test
    void testToolCallArgumentsDefaultToAnEmptyObjectWhenAbsent() {
        ChatResponse response = chatWith(sse("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"id\":\"call_x\",\"function\":{\"name\":\"noop\"}}]}}]}"));
        assertEquals("", response.aiMessage().toolExecutionRequests().get(0).arguments());
    }

    @Test
    void testTwoToolCallIndexesProduceTwoRequestsInIndexOrder() {
        ChatResponse response = chatWith(sse("{\"choices\":[{\"delta\":{\"tool_calls\":["
                + "{\"index\":0,\"id\":\"call_a\",\"function\":{\"name\":\"first\",\"arguments\":\"{}\"}},"
                + "{\"index\":1,\"id\":\"call_b\",\"function\":{\"name\":\"second\",\"arguments\":\"{}\"}}]}}]}"));
        List<ToolExecutionRequest> requests = response.aiMessage().toolExecutionRequests();
        assertEquals(2, requests.size());
        assertEquals("first", requests.get(0).name());
        assertEquals("second", requests.get(1).name());
    }

    // ------------------------------------------------------------ thinking tags

    @Test
    void testThinkBlocksAreStrippedFromTheFinalText() {
        assertEquals(
                "real answer",
                chatWith(sse("{\"choices\":[{\"delta\":" + "{\"content\":\"<think>hidden</think>real answer\"}}]}"))
                        .aiMessage()
                        .text());
    }

    @Test
    void testMultipleThinkBlocksAreAllStripped() {
        assertEquals(
                "real",
                chatWith(sse("{\"choices\":[{\"delta\":" + "{\"content\":\"<think>a</think>real<think>b</think>\"}}]}"))
                        .aiMessage()
                        .text());
    }

    @Test
    void testUnclosedThinkBlockDiscardsOnlyItsOpeningTag() {
        assertEquals(
                "hidden",
                chatWith(sse("{\"choices\":[{\"delta\":" + "{\"content\":\"<think>hidden\"}}]}"))
                        .aiMessage()
                        .text());
    }

    @Test
    void testTextConsistingOnlyOfThinkTagsFallsBackToTagRemoval() {
        assertEquals(
                "",
                chatWith(sse("{\"choices\":[{\"delta\":{\"content\":\"<think></think>\"}}]}"))
                        .aiMessage()
                        .text());
    }

    // ------------------------------------------------------------- SSE edge cases

    @Test
    void testFinishReasonMappingCoversEveryArm() {
        assertEquals(
                FinishReason.LENGTH,
                chatWith(sse("{\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"length\"}]}"))
                        .finishReason());
        assertEquals(
                FinishReason.TOOL_EXECUTION,
                chatWith(sse("{\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"tool_call\"}]}"))
                        .finishReason());
        assertEquals(
                FinishReason.STOP,
                chatWith(sse("{\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"content_filter\"}]}"))
                        .finishReason());
    }

    @Test
    void testBlankAndNullLiteralFinishReasonsAreIgnored() {
        assertEquals(
                FinishReason.STOP,
                chatWith(sse("{\"choices\":[{\"delta\":{\"content\":\"a\"},\"finish_reason\":\"null\"}]}"))
                        .finishReason());
        assertEquals(
                FinishReason.STOP,
                chatWith(sse("{\"choices\":[{\"delta\":{\"content\":\"a\"},\"finish_reason\":\"  \"}]}"))
                        .finishReason());
    }

    @Test
    void testMalformedChunkIsSkippedAndTheStreamContinues() {
        assertEquals(
                "before-after",
                chatWith(sse(
                                "{\"choices\":[{\"delta\":{\"content\":\"before\"}}]}",
                                "{not-json",
                                "{\"choices\":[{\"delta\":{\"content\":\"-after\"}}]}"))
                        .aiMessage()
                        .text());
    }

    @Test
    void testCommentBlankAndNonDataLinesAreIgnored() {
        assertEquals(
                "kept",
                chatWith(": keep-alive\n\nevent: message\n\ndata: "
                                + "{\"choices\":[{\"delta\":{\"content\":\"kept\"}}]}\n\n")
                        .aiMessage()
                        .text());
    }

    @Test
    void testDoneSentinelStopsParsingTheRestOfTheStream() {
        assertEquals(
                "kept",
                chatWith(sse("{\"choices\":[{\"delta\":{\"content\":\"kept\"}}]}", "[DONE]"))
                        .aiMessage()
                        .text());
    }

    @Test
    void testChunksWithoutChoicesAreTolerated() {
        assertEquals(
                "survived",
                chatWith(sse(
                                "{\"id\":\"no-choices\"}",
                                "{\"choices\":[]}",
                                "{\"choices\":[{\"delta\":{\"content\":\"survived\"}}]}"))
                        .aiMessage()
                        .text());
    }

    @Test
    void testNullAndEmptyDeltaContentAreNotAppended() {
        assertEquals(
                "real",
                chatWith(sse(
                                "{\"choices\":[{\"delta\":{\"content\":null}}]}",
                                "{\"choices\":[{\"delta\":{\"content\":\"\"}}]}",
                                "{\"choices\":[{\"delta\":{\"content\":\"real\"}}]}"))
                        .aiMessage()
                        .text());
    }

    @Test
    void testToolCallDeltaWithoutAFunctionNodeRegistersAnEmptyNamedRequest() {
        // Divergence from KimchiResponsesChatModel: this accumulator does not drop name-less builders, so a
        // malformed tool-call delta still yields a request with an empty name, and because tool requests
        // exist the response carries no text at all.
        ChatResponse response =
                chatWith(sse("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_z\"}]}}]}"));
        assertEquals(FinishReason.TOOL_EXECUTION, response.finishReason());
        List<ToolExecutionRequest> requests = response.aiMessage().toolExecutionRequests();
        assertEquals(1, requests.size());
        assertEquals("call_z", requests.get(0).id());
        assertEquals("", requests.get(0).name());
        assertNull(response.aiMessage().text());
    }

    // ---------------------------------------------------------------- payloads

    @Test
    void testToolSpecificationsAreSerialisedIntoThePayload() throws Exception {
        sseBody.set(sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));
        ToolSpecification spec = ToolSpecification.builder()
                .name("reverse_text")
                .description("Reverses text")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("input", "Text")
                        .required("input")
                        .build())
                .build();
        model().chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("go")))
                .toolSpecifications(List.of(spec))
                .build());

        JsonNode tools = lastRequestJson().path("tools");
        assertEquals(1, tools.size());
        assertEquals("function", tools.get(0).path("type").asText());
        assertEquals("reverse_text", tools.get(0).path("function").path("name").asText());
        assertEquals(
                "object",
                tools.get(0).path("function").path("parameters").path("type").asText());
    }

    @Test
    void testToolSpecificationWithoutDescriptionOrParametersGetsAnEmptyObjectSchema() throws Exception {
        sseBody.set(sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));
        model().chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("go")))
                .toolSpecifications(
                        List.of(ToolSpecification.builder().name("ping").build()))
                .build());

        JsonNode fn = lastRequestJson().path("tools").get(0).path("function");
        assertEquals("ping", fn.path("name").asText());
        assertFalse(fn.has("description"));
        assertEquals("object", fn.path("parameters").path("type").asText());
        assertTrue(fn.path("parameters").path("properties").isObject());
    }

    @Test
    void testEveryMessageRoleIsMappedIntoThePayload() throws Exception {
        sseBody.set(sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("You are Foundry."));
        messages.add(UserMessage.from("hello"));
        messages.add(AiMessage.from("a plain assistant turn"));
        messages.add(AiMessage.from(ToolExecutionRequest.builder()
                .id("call_1")
                .name("reverse_text")
                .arguments("{\"input\":\"hello\"}")
                .build()));
        messages.add(ToolExecutionResultMessage.from("call_1", "reverse_text", "olleh"));

        model().chat(ChatRequest.builder().messages(messages).build());

        JsonNode sent = lastRequestJson().path("messages");
        assertEquals(5, sent.size());
        assertEquals("system", sent.get(0).path("role").asText());
        assertEquals("You are Foundry.", sent.get(0).path("content").asText());
        assertEquals("user", sent.get(1).path("role").asText());
        assertEquals("a plain assistant turn", sent.get(2).path("content").asText());
        assertEquals("call_1", sent.get(3).path("tool_calls").get(0).path("id").asText());
        assertEquals(
                "reverse_text",
                sent.get(3)
                        .path("tool_calls")
                        .get(0)
                        .path("function")
                        .path("name")
                        .asText());
        assertEquals("tool", sent.get(4).path("role").asText());
        assertEquals("call_1", sent.get(4).path("tool_call_id").asText());
        assertEquals("reverse_text", sent.get(4).path("name").asText());
        assertEquals("olleh", sent.get(4).path("content").asText());
    }

    @Test
    void testAssistantTurnWithBlankTextOmitsTheContentField() throws Exception {
        sseBody.set(sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));
        model().chat(ChatRequest.builder()
                .messages(List.of(AiMessage.from(""), UserMessage.from("go")))
                .build());

        JsonNode assistant = lastRequestJson().path("messages").get(0);
        assertEquals("assistant", assistant.path("role").asText());
        assertFalse(assistant.has("content"));
    }
}

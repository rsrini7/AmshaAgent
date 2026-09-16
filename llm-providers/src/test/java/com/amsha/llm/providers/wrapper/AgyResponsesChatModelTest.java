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
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.providers.helper.AgyCliExecutor;
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
 * Plain-JUnit coverage suite for {@link AgyResponsesChatModel}, complementing the
 * {@link AgyChatModelLoopbackTest} contract test.
 *
 * <p>That sibling is a {@code @QuarkusTest}, so it runs against a live ArC container and a real
 * {@code agy} CLI probe — which makes the standalone and degraded branches (no transport, no CLI,
 * unresolvable CDI handle) unreachable there. This suite drives the same class with no container at all,
 * so every rung of the {@code chat()} dispatch ladder is exercised deterministically.
 *
 * <p>The HTTP path stays real: {@link GovernedTransportService} is mocked and its {@code send} delegates to a
 * real {@link HttpClient} aimed at a local {@link HttpServer}, which keeps header, body and SSE handling
 * genuine while bypassing the SSRF gate the transport would apply to a loopback address.
 */
class AgyResponsesChatModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private int port;
    private String baseUrl;
    private GovernedTransportService governedTransport;

    private final AtomicReference<String> responseBody = new AtomicReference<>("");
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final List<String> capturedPaths = new CopyOnWriteArrayList<>();
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
        // A single root context accepts every resolved path, so endpoint-resolution assertions can read the
        // actual request path instead of requiring one context per variant.
        server.createContext("/", this::handleChat);
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
        capturedPaths.add(exchange.getRequestURI().getPath());
        capturedHeaders.add("Authorization=" + exchange.getRequestHeaders().getFirst("Authorization"));
        capturedHeaders.add("Content-Type=" + exchange.getRequestHeaders().getFirst("Content-Type"));
        capturedHeaders.add("Accept=" + exchange.getRequestHeaders().getFirst("Accept"));
        try (InputStream is = exchange.getRequestBody()) {
            capturedBodies.add(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }

        byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
        int status = responseStatus.get();
        if (bytes.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
        exchange.close();
    }

    // ---------------------------------------------------------------- fixtures

    private AgyResponsesChatModel model(String url, String token, String modelName) {
        return new AgyResponsesChatModel(url, token, modelName, 1024, Duration.ofSeconds(5), 0.2, governedTransport);
    }

    private AgyResponsesChatModel model() {
        return model(baseUrl, "agy-test-key", "gemini-2.0-flash");
    }

    private ChatResponse chatWith(String sse) {
        responseBody.set(sse);
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

    private String lastRequestPath() {
        assertFalse(capturedPaths.isEmpty(), "the model must have issued an HTTP request");
        return capturedPaths.get(capturedPaths.size() - 1);
    }

    private String normalizedModelFor(String rawModel) throws Exception {
        responseBody.set(sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));
        model(baseUrl, "agy-test-key", rawModel).chat(request("hi"));
        return lastRequestJson().path("model").asText();
    }

    private static String sse(String... dataLines) {
        StringBuilder sb = new StringBuilder();
        for (String line : dataLines) {
            sb.append("data: ").append(line).append('\n').append('\n');
        }
        return sb.toString();
    }

    private static String endpointOf(AgyResponsesChatModel model) throws Exception {
        Field field = AgyResponsesChatModel.class.getDeclaredField("endpoint");
        field.setAccessible(true);
        return (String) field.get(model);
    }

    // ------------------------------------------------------- endpoint resolution

    @Test
    void testEndpointResolutionForEveryBaseUrlShape() {
        responseBody.set(sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));
        assertEquals("/chat/completions", pathFor(baseUrl));
        assertEquals("/chat/completions", pathFor(baseUrl + "/"));
        assertEquals("/chat/completions", pathFor(baseUrl + "///"));
        assertEquals("/chat/completions", pathFor(baseUrl + "/chat/completions"));
        assertEquals("/v1/chat/completions", pathFor(baseUrl + "/v1"));
        assertEquals("/v1beta/openai/chat/completions", pathFor(baseUrl + "/v1beta"));
        assertEquals("/openai/chat/completions", pathFor(baseUrl + "/openai"));
    }

    private String pathFor(String variant) {
        capturedPaths.clear();
        model(variant, "agy-test-key", "gemini-2.0-flash").chat(request("hi"));
        return lastRequestPath();
    }

    @Test
    void testBlankBaseUrlFallsBackToTheDefaultGeminiEndpoint() throws Exception {
        // The default endpoint is a remote host, so read the resolved field rather than driving a request.
        AgyResponsesChatModel model = new AgyResponsesChatModel(
                "   ", "agy-test-key", "gemini-2.0-flash", 1024, Duration.ofSeconds(5), 0.2, governedTransport);
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", endpointOf(model));
    }

    // ---------------------------------------------------------- model normalisation

    @Test
    void testModelNormalisationCoversEveryRule() throws Exception {
        assertEquals(ModelDefaults.DEFAULT_AGY_MODEL, normalizedModelFor(null));
        assertEquals(ModelDefaults.DEFAULT_AGY_MODEL, normalizedModelFor("   "));
        assertEquals("gemini-2.0-flash", normalizedModelFor("models/gemini-2.0-flash"));
        assertEquals("gemini-pro", normalizedModelFor("gemini pro"));
        assertEquals("gemma-2b", normalizedModelFor("gemma-2b"));
        assertEquals("antigravity-experimental", normalizedModelFor("antigravity experimental"));
        assertEquals("gemini-2.0-flash", normalizedModelFor("  2.0-flash  "));
    }

    // ------------------------------------------------------------------- guards

    @Test
    void testHttpTokenWithoutATransportIsRejected() {
        AgyResponsesChatModel model = new AgyResponsesChatModel(
                baseUrl, "agy-test-key", "gemini-2.0-flash", 1024, Duration.ofSeconds(5), 0.2, null);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> model.chat(request("hi")));
        assertTrue(ex.getMessage().contains("Governed outbound transport"), ex.getMessage());
    }

    @Test
    void testNoTokenNoCliAndNoTransportIsRejected() {
        try (MockedStatic<AgyCliExecutor> cli = Mockito.mockStatic(AgyCliExecutor.class)) {
            cli.when(AgyCliExecutor::isAvailable).thenReturn(false);
            AgyResponsesChatModel model = new AgyResponsesChatModel(
                    baseUrl, null, "gemini-2.0-flash", 1024, Duration.ofSeconds(5), 0.2, null);
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> model.chat(request("hi")));
            assertTrue(ex.getMessage().contains("Governed outbound transport"), ex.getMessage());
        }
    }

    @Test
    void testNoTokenNoCliButTransportPresentAsksForCredentials() {
        try (MockedStatic<AgyCliExecutor> cli = Mockito.mockStatic(AgyCliExecutor.class)) {
            cli.when(AgyCliExecutor::isAvailable).thenReturn(false);
            AgyResponsesChatModel model = new AgyResponsesChatModel(
                    baseUrl, null, "gemini-2.0-flash", 1024, Duration.ofSeconds(5), 0.2, governedTransport);
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> model.chat(request("hi")));
            assertTrue(ex.getMessage().contains("requires either a local 'agy' CLI"), ex.getMessage());
        }
    }

    // ------------------------------------------------------------- CLI dispatch

    @Test
    void testCliSessionSentinelRoutesToTheCliWhenAvailable() {
        ChatResponse fromCli =
                ChatResponse.builder().aiMessage(AiMessage.from("cli answer")).build();
        try (MockedStatic<AgyCliExecutor> cli = Mockito.mockStatic(AgyCliExecutor.class)) {
            cli.when(AgyCliExecutor::isAvailable).thenReturn(true);
            cli.when(AgyCliExecutor::resolveCliPath).thenReturn(Path.of("/tmp/fake-agy"));
            cli.when(() -> AgyCliExecutor.execute(any(), anyString(), any(), any(), any(), any()))
                    .thenReturn(fromCli);

            AgyResponsesChatModel model = new AgyResponsesChatModel(
                    baseUrl, "agy-cli-session", "gemini-2.0-flash", 1024, Duration.ofSeconds(5), 0.2, null);
            assertSame(fromCli, model.chat(request("hi")));
            // The CLI path never issues an HTTP request.
            assertTrue(capturedPaths.isEmpty());
        }
    }

    @Test
    void testCliDispatchForwardsTheResolvedToolSpecifications() {
        ChatResponse fromCli =
                ChatResponse.builder().aiMessage(AiMessage.from("cli answer")).build();
        ToolSpecification spec = ToolSpecification.builder()
                .name("reverse_text")
                .description("Reverses text")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("input", "Text")
                        .required("input")
                        .build())
                .build();
        try (MockedStatic<AgyCliExecutor> cli = Mockito.mockStatic(AgyCliExecutor.class)) {
            cli.when(AgyCliExecutor::isAvailable).thenReturn(true);
            cli.when(AgyCliExecutor::resolveCliPath).thenReturn(Path.of("/tmp/fake-agy"));
            cli.when(() -> AgyCliExecutor.execute(any(), anyString(), any(), any(), any(), any()))
                    .thenReturn(fromCli);

            AgyResponsesChatModel model = new AgyResponsesChatModel(
                    baseUrl, null, "gemini-2.0-flash", 1024, Duration.ofSeconds(5), 0.2, governedTransport);
            ChatResponse response = model.chat(ChatRequest.builder()
                    .messages(List.of(UserMessage.from("hi")))
                    .toolSpecifications(List.of(spec))
                    .build());
            assertSame(fromCli, response);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ToolSpecification>> toolsCaptor = ArgumentCaptor.forClass(List.class);
            cli.verify(() -> AgyCliExecutor.execute(any(), anyString(), any(), toolsCaptor.capture(), any(), any()));
            assertEquals(1, toolsCaptor.getValue().size());
            assertEquals("reverse_text", toolsCaptor.getValue().get(0).name());
        }
    }

    // ------------------------------------------------------------ HTTP failures

    @Test
    void testNon200StatusIncludesTheUpstreamErrorBody() {
        responseStatus.set(429);
        responseBody.set("{\"error\":{\"message\":\"rate limited\"}}");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> model().chat(request("hi")));
        assertTrue(ex.getMessage().contains("HTTP 429"), ex.getMessage());
        assertTrue(ex.getMessage().contains("rate limited"), ex.getMessage());
    }

    @Test
    void testNon200StatusWithAnEmptyBodyOmitsTheDetailsSuffix() {
        responseStatus.set(500);
        responseBody.set("");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> model().chat(request("hi")));
        assertTrue(ex.getMessage().contains("HTTP 500"), ex.getMessage());
        assertFalse(ex.getMessage().contains(": "), ex.getMessage());
    }

    @Test
    void testTransportFailureIsWrappedInARuntimeException() {
        // send() declares no checked exception, so the failure must be an unchecked one — and it must not be
        // an IllegalStateException, which chatViaHttp rethrows untouched.
        Mockito.doThrow(new OutboundHttpException(OutboundHttpException.Category.IO, "socket died"))
                .when(governedTransport)
                .send(anyString(), any(), any(), any(), anyBoolean(), any(), any());
        RuntimeException ex = assertThrows(RuntimeException.class, () -> model().chat(request("hi")));
        assertTrue(ex.getMessage().contains("Failed to execute Antigravity chat request"), ex.getMessage());
        assertTrue(ex.getMessage().contains("socket died"), ex.getMessage());
        assertNotNull(ex.getCause());
    }

    // --------------------------------------------------------------- happy path

    @Test
    void testTextStreamReturnsTextAndRequestShape() throws Exception {
        ChatResponse response = chatWith(sse(
                "{\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\" world\"},\"finish_reason\":\"stop\"}]}"));

        assertEquals("Hello world", response.aiMessage().text());
        assertEquals(FinishReason.STOP, response.finishReason());
        assertNull(response.tokenUsage());

        assertTrue(capturedHeaders.contains("Authorization=Bearer agy-test-key"), capturedHeaders.toString());
        assertTrue(capturedHeaders.contains("Content-Type=application/json"), capturedHeaders.toString());
        assertTrue(capturedHeaders.contains("Accept=text/event-stream"), capturedHeaders.toString());

        JsonNode body = lastRequestJson();
        assertEquals("gemini-2.0-flash", body.path("model").asText());
        assertTrue(body.path("stream").asBoolean());
        assertEquals(1024, body.path("max_tokens").asInt());
        assertEquals(0.2, body.path("temperature").asDouble(), 1e-9);
        assertFalse(body.has("tools"));
    }

    @Test
    void testAlreadyBearerPrefixedTokenIsNotPrefixedTwice() {
        responseBody.set(sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));
        model(baseUrl, "Bearer agy-test-key", "gemini-2.0-flash").chat(request("hi"));
        assertTrue(capturedHeaders.contains("Authorization=Bearer agy-test-key"), capturedHeaders.toString());
    }

    @Test
    void testTokenIsTrimmedBeforeUse() {
        responseBody.set(sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));
        model(baseUrl, "   agy-test-key   ", "gemini-2.0-flash").chat(request("hi"));
        assertTrue(capturedHeaders.contains("Authorization=Bearer agy-test-key"), capturedHeaders.toString());
    }

    @Test
    void testEmptyStreamYieldsEmptyText() {
        ChatResponse response = chatWith("");
        assertEquals("", response.aiMessage().text());
        assertEquals(FinishReason.STOP, response.finishReason());
    }

    @Test
    void testTokenUsageIsReportedWhenPresent() {
        ChatResponse response = chatWith(sse(
                "{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}",
                "{\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":6}}"));
        assertNotNull(response.tokenUsage());
        assertEquals(4, response.tokenUsage().inputTokenCount());
        assertEquals(6, response.tokenUsage().outputTokenCount());
    }

    @Test
    void testPartialUsageIsStillReported() {
        ChatResponse response =
                chatWith(sse("{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}", "{\"usage\":{\"prompt_tokens\":3}}"));
        assertNotNull(response.tokenUsage());
        assertEquals(3, response.tokenUsage().inputTokenCount());
        assertEquals(0, response.tokenUsage().outputTokenCount());
    }

    // -------------------------------------------------------------- tool calling

    @Test
    void testToolCallIsParsedIntoARequest() {
        ChatResponse response = chatWith(sse("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"id\":\"call_1\",\"function\":{\"name\":\"reverse_text\","
                + "\"arguments\":\"{\\\"input\\\":\\\"hello\\\"}\"}}]}}]}"));

        List<ToolExecutionRequest> requests = response.aiMessage().toolExecutionRequests();
        assertEquals(1, requests.size());
        assertEquals("call_1", requests.get(0).id());
        assertEquals("reverse_text", requests.get(0).name());
        assertEquals("{\"input\":\"hello\"}", requests.get(0).arguments());
        assertEquals(FinishReason.TOOL_EXECUTION, response.finishReason());
    }

    @Test
    void testToolCallArgumentsAccumulateAcrossChunks() {
        ChatResponse response = chatWith(sse(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                        + "\"function\":{\"name\":\"reverse_text\",\"arguments\":\"{\\\"input\\\":\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"\\\"hello\\\"}\"}}]}}]}"));
        assertEquals(
                "{\"input\":\"hello\"}",
                response.aiMessage().toolExecutionRequests().get(0).arguments());
    }

    @Test
    void testToolCallWithoutAnIdOrIndexGetsDefaults() {
        ChatResponse response = chatWith(sse("{\"choices\":[{\"delta\":{\"tool_calls\":["
                + "{\"function\":{\"name\":\"noop\",\"arguments\":\"{}\"}}]}}]}"));
        List<ToolExecutionRequest> requests = response.aiMessage().toolExecutionRequests();
        assertEquals(1, requests.size());
        assertEquals("", requests.get(0).id());
        assertEquals("noop", requests.get(0).name());
    }

    @Test
    void testToolCallWithoutANameIsDropped() {
        // buildToolRequests() skips blank-named builders, so a delta carrying only an id yields no request
        // and the response falls through to the plain-text branch.
        ChatResponse response = chatWith(sse("{\"choices\":[{\"delta\":{\"content\":\"text only\","
                + "\"tool_calls\":[{\"index\":0,\"id\":\"call_x\"}]}}]}"));
        assertFalse(response.aiMessage().hasToolExecutionRequests());
        assertEquals("text only", response.aiMessage().text());
    }

    @Test
    void testToolCallsWithoutTextProduceATextlessMessage() {
        ChatResponse response = chatWith(sse("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"id\":\"call_1\",\"function\":{\"name\":\"noop\",\"arguments\":\"{}\"}}]}}]}"));
        assertEquals(1, response.aiMessage().toolExecutionRequests().size());
        assertNull(response.aiMessage().text());
    }

    @Test
    void testTextAndToolCallsCombineIntoOneMessage() {
        ChatResponse response = chatWith(sse("{\"choices\":[{\"delta\":{\"content\":\"thinking\","
                + "\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                + "\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}}]}}]}"));
        assertEquals("thinking", response.aiMessage().text());
        assertEquals(1, response.aiMessage().toolExecutionRequests().size());
    }

    // ------------------------------------------------------------ finish reasons

    @Test
    void testFinishReasonMappingCoversEveryArm() {
        // No tool requests are parsed here, so the switch is reached rather than short-circuited by
        // parseFinishReason's tool-execution guard.
        assertEquals(
                FinishReason.TOOL_EXECUTION,
                chatWith(sse("{\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"tool_calls\"}]}"))
                        .finishReason());
        assertEquals(
                FinishReason.TOOL_EXECUTION,
                chatWith(sse("{\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"function_call\"}]}"))
                        .finishReason());
        assertEquals(
                FinishReason.LENGTH,
                chatWith(sse("{\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"length\"}]}"))
                        .finishReason());
        assertEquals(
                FinishReason.STOP,
                chatWith(sse("{\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"content_filter\"}]}"))
                        .finishReason());
    }

    // ------------------------------------------------------------- SSE edge cases

    @Test
    void testNonDataLinesAreIgnored() {
        // processSseLine returns early unless the line starts with "data:", so comments, event names and
        // blank lines contribute nothing.
        assertEquals(
                "", chatWith(": keep-alive\n\nevent: message\n\n").aiMessage().text());
        assertEquals(
                "kept",
                chatWith(": keep-alive\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"kept\"}}]}\n\n")
                        .aiMessage()
                        .text());
    }

    @Test
    void testEmptyDataLineAndDoneSentinelStopProcessing() {
        assertEquals(
                "kept",
                chatWith(sse("{\"choices\":[{\"delta\":{\"content\":\"kept\"}}]}", "[DONE]", "{\"x\":1}"))
                        .aiMessage()
                        .text());
        assertEquals("", chatWith("data:\n\n").aiMessage().text());
    }

    @Test
    void testChunksWithoutUsageOrChoicesAreTolerated() {
        // Covers both early returns: a chunk with no usage field and a chunk with no choices array.
        assertEquals(
                "survived",
                chatWith(sse(
                                "{\"id\":\"no-usage-no-choices\"}",
                                "{\"choices\":[]}",
                                "{\"choices\":[{\"delta\":{\"content\":\"survived\"}}]}"))
                        .aiMessage()
                        .text());
    }

    @Test
    void testChoiceWithoutFinishReasonOrDeltaIsTolerated() {
        assertEquals(
                "after",
                chatWith(sse(
                                "{\"choices\":[{\"message\":{\"role\":\"assistant\"}}]}",
                                "{\"choices\":[{\"delta\":\"not-an-object\"}]}",
                                "{\"choices\":[{\"delta\":{\"content\":\"after\"}}]}"))
                        .aiMessage()
                        .text());
    }

    @Test
    void testNullAndEmptyContentAreNotAppended() {
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
    void testToolCallsFieldThatIsNotAnArrayIsIgnored() {
        assertEquals(
                "after",
                chatWith(sse(
                                "{\"choices\":[{\"delta\":{\"tool_calls\":\"nope\"}}]}",
                                "{\"choices\":[{\"delta\":{\"content\":\"after\"}}]}"))
                        .aiMessage()
                        .text());
    }

    // ---------------------------------------------------------------- payloads

    @Test
    void testToolSpecificationsAreSerialisedIntoThePayload() throws Exception {
        responseBody.set(sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));
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
    void testToolSpecificationWithoutDescriptionOrParametersIsStillSerialised() throws Exception {
        responseBody.set(sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));
        model().chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("go")))
                .toolSpecifications(
                        List.of(ToolSpecification.builder().name("ping").build()))
                .build());

        JsonNode fn = lastRequestJson().path("tools").get(0).path("function");
        assertEquals("ping", fn.path("name").asText());
        assertFalse(fn.has("description"));
        assertFalse(fn.has("parameters"));
    }

    @Test
    void testEveryMessageRoleIsMappedIntoThePayload() throws Exception {
        responseBody.set(sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("You are Antigravity."));
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
        assertEquals("You are Antigravity.", sent.get(0).path("content").asText());
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
        assertEquals("olleh", sent.get(4).path("content").asText());
    }

    @Test
    void testAssistantTurnWithBlankTextOmitsTheContentField() throws Exception {
        responseBody.set(sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));
        model().chat(ChatRequest.builder()
                .messages(List.of(AiMessage.from(""), UserMessage.from("go")))
                .build());

        JsonNode assistant = lastRequestJson().path("messages").get(0);
        assertEquals("assistant", assistant.path("role").asText());
        assertFalse(assistant.has("content"));
    }

    // ------------------------------------------------------------- Arc resolution

    @Test
    void testTransportIsResolvedFromTheArcContainerWhenAvailable() {
        try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
            ArcContainer container = Mockito.mock(ArcContainer.class);
            InstanceHandle<GovernedTransportService> handle = Mockito.mock(InstanceHandle.class);
            arc.when(Arc::container).thenReturn(container);
            Mockito.when(container.instance(GovernedTransportService.class)).thenReturn(handle);
            Mockito.when(handle.isAvailable()).thenReturn(true);
            Mockito.when(handle.get()).thenReturn(governedTransport);

            responseBody.set(sse("{\"choices\":[{\"delta\":{\"content\":\"from-arc\"}}]}"));
            AgyResponsesChatModel model = new AgyResponsesChatModel(
                    baseUrl, "agy-test-key", "gemini-2.0-flash", 1024, Duration.ofSeconds(5), 0.2, null);
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

            AgyResponsesChatModel model = new AgyResponsesChatModel(
                    baseUrl, "agy-test-key", "gemini-2.0-flash", 1024, Duration.ofSeconds(5), 0.2, null);
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> model.chat(request("hi")));
            assertTrue(ex.getMessage().contains("Governed outbound transport"), ex.getMessage());
        }
    }

    @Test
    void testArcContainerFailureIsSwallowedAndLeavesTheTransportUnresolved() {
        try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
            arc.when(Arc::container).thenThrow(new IllegalStateException("no active request context"));

            AgyResponsesChatModel model = new AgyResponsesChatModel(
                    baseUrl, "agy-test-key", "gemini-2.0-flash", 1024, Duration.ofSeconds(5), 0.2, null);
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> model.chat(request("hi")));
            assertTrue(ex.getMessage().contains("Governed outbound transport"), ex.getMessage());
        }
    }
}

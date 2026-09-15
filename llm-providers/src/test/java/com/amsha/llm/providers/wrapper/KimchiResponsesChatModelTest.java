package com.amsha.llm.providers.wrapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.llm.api.ModelDefaults;
import com.amsha.security.http.GovernedTransportService;
import com.amsha.security.http.OutboundHttpException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;

/**
 * Loopback coverage suite for {@link KimchiResponsesChatModel}.
 *
 * <p>The model builds its own {@link HttpRequest} and hands it to a {@link GovernedTransportService}, so the
 * transport is mocked and its {@code send} delegates to a real {@link HttpClient} pointed at a local
 * {@link HttpServer}. That keeps the entire request/response path real (headers, body serialisation, SSE
 * framing, stream draining) while sidestepping the SSRF gate that {@code GovernedTransportService} would
 * otherwise apply to a loopback address.
 */
class KimchiResponsesChatModelTest {

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
        capturedHeaders.add("Authorization=" + exchange.getRequestHeaders().getFirst("Authorization"));
        capturedHeaders.add("User-Agent=" + exchange.getRequestHeaders().getFirst("User-Agent"));
        capturedHeaders.add("Accept=" + exchange.getRequestHeaders().getFirst("Accept"));
        capturedHeaders.add("Content-Type=" + exchange.getRequestHeaders().getFirst("Content-Type"));
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

    private KimchiResponsesChatModel model(String url, String token) {
        return new KimchiResponsesChatModel(
                url, token, "minimax-m3", 1024, Duration.ofSeconds(5), 0.2, governedTransport);
    }

    private KimchiResponsesChatModel model() {
        return model(baseUrl, "kimchi-test-token");
    }

    /** Runs one chat against the loopback server, returning the SSE payload the handler should emit. */
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

    private static String endpointOf(KimchiResponsesChatModel model) throws Exception {
        Field field = KimchiResponsesChatModel.class.getDeclaredField("endpoint");
        field.setAccessible(true);
        return (String) field.get(model);
    }

    private static String sse(String... dataLines) {
        StringBuilder sb = new StringBuilder();
        for (String line : dataLines) {
            sb.append("data: ").append(line).append('\n').append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------- endpoint resolution

    @Test
    void testEndpointResolutionForAllThreeBaseUrlShapes() {
        // A bare base URL, one with a trailing slash, and one that already names the path all resolve to
        // the same endpoint — each is driven end to end so the resolution is proven, not just assumed.
        for (String variant : List.of(baseUrl, baseUrl + "/", baseUrl + "/chat/completions")) {
            capturedBodies.clear();
            sseBody.set(sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));
            ChatResponse response = model(variant, "tok").chat(request("hi"));
            assertEquals("ok", response.aiMessage().text(), "base URL variant: " + variant);
        }
    }

    @Test
    void testBlankBaseUrlFallsBackToTheKimchiDefaultEndpoint() throws Exception {
        // The default base URL is a remote host, so this one resolution branch cannot be driven through a
        // loopback server; read the resolved field directly instead.
        KimchiResponsesChatModel model = new KimchiResponsesChatModel(
                "   ", "tok", "minimax-m3", 1024, Duration.ofSeconds(5), 0.2, governedTransport);
        assertEquals(ModelDefaults.DEFAULT_KIMCHI_BASE_URL + "/chat/completions", endpointOf(model));
    }

    @Test
    void testConstructorAppliesDefaultsWhenModelAndTuningAreAbsent() throws Exception {
        sseBody.set(sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));
        KimchiResponsesChatModel model =
                new KimchiResponsesChatModel(baseUrl, "tok", null, null, Duration.ZERO, -1.0, governedTransport);

        assertEquals("ok", model.chat(request("hi")).aiMessage().text());

        JsonNode body = lastRequestJson();
        assertEquals(ModelDefaults.DEFAULT_KIMCHI_MODEL, body.path("model").asText());
        assertEquals(8192, body.path("max_tokens").asInt());
        assertEquals(0.1, body.path("temperature").asDouble(), 1e-9);
    }

    @Test
    void testConstructorTrimsModelAndKeepsSuppliedTuning() throws Exception {
        sseBody.set(sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));
        new KimchiResponsesChatModel(
                        baseUrl, "tok", "  minimax-m9  ", 42, Duration.ofSeconds(9), 0.75, governedTransport)
                .chat(request("hi"));

        JsonNode body = lastRequestJson();
        assertEquals("minimax-m9", body.path("model").asText());
        assertEquals(42, body.path("max_tokens").asInt());
        assertEquals(0.75, body.path("temperature").asDouble(), 1e-9);
    }

    // ------------------------------------------------------------------- guards

    @Test
    void testChatRejectsABlankToken() {
        KimchiResponsesChatModel model = new KimchiResponsesChatModel(
                baseUrl, "   ", "minimax-m3", 1024, Duration.ofSeconds(5), 0.2, governedTransport);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> model.chat(request("hi")));
        assertTrue(ex.getMessage().contains("requires active API credentials"), ex.getMessage());
    }

    @Test
    void testChatRejectsAMissingGovernedTransport() {
        // No explicit transport and no ArC container in a plain unit test, so resolution yields null.
        KimchiResponsesChatModel model =
                new KimchiResponsesChatModel(baseUrl, "tok", "minimax-m3", 1024, Duration.ofSeconds(5), 0.2, null);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> model.chat(request("hi")));
        assertTrue(ex.getMessage().contains("GovernedTransportService is required"), ex.getMessage());
    }

    @Test
    void testSixArgumentConstructorLeavesTheTransportUnresolved() {
        KimchiResponsesChatModel model =
                new KimchiResponsesChatModel(baseUrl, "tok", "minimax-m3", 1024, Duration.ofSeconds(5), 0.2);
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
            KimchiResponsesChatModel model =
                    new KimchiResponsesChatModel(baseUrl, "tok", "minimax-m3", 1024, Duration.ofSeconds(5), 0.2, null);
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

            KimchiResponsesChatModel model =
                    new KimchiResponsesChatModel(baseUrl, "tok", "minimax-m3", 1024, Duration.ofSeconds(5), 0.2, null);
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> model.chat(request("hi")));
            assertTrue(ex.getMessage().contains("GovernedTransportService is required"), ex.getMessage());
        }
    }

    @Test
    void testArcContainerFailureIsSwallowedAndLeavesTheTransportUnresolved() {
        try (MockedStatic<Arc> arc = Mockito.mockStatic(Arc.class)) {
            arc.when(Arc::container).thenThrow(new IllegalStateException("no active request context"));

            KimchiResponsesChatModel model =
                    new KimchiResponsesChatModel(baseUrl, "tok", "minimax-m3", 1024, Duration.ofSeconds(5), 0.2, null);
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> model.chat(request("hi")));
            assertTrue(ex.getMessage().contains("GovernedTransportService is required"), ex.getMessage());
        }
    }

    @Test
    void testNonSuccessStatusIsReportedAsIllegalState() {
        responseStatus.set(503);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> chatWith(""));
        assertTrue(ex.getMessage().contains("HTTP 503"), ex.getMessage());
    }

    @Test
    void testTransportFailureIsWrappedInARuntimeException() {
        // doThrow, not when(...).thenThrow: the @BeforeEach stub already answers this call, and a second
        // when(...) would invoke that answer with null matcher arguments (NPE inside the HttpClient).
        // send() declares no checked exception, so the failure must be an unchecked one.
        Mockito.doThrow(new OutboundHttpException(OutboundHttpException.Category.IO, "socket died"))
                .when(governedTransport)
                .send(anyString(), any(), any(), any(), anyBoolean(), any(), any());
        RuntimeException ex = assertThrows(RuntimeException.class, () -> model().chat(request("hi")));
        assertTrue(ex.getMessage().contains("Failed to execute Kimchi chat request"), ex.getMessage());
        assertTrue(ex.getMessage().contains("socket died"), ex.getMessage());
        assertNotNull(ex.getCause());
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

        assertTrue(
                capturedHeaders.stream().anyMatch(h -> h.equals("Authorization=Bearer kimchi-test-token")),
                capturedHeaders.toString());
        assertTrue(
                capturedHeaders.stream()
                        .anyMatch(h -> h.equals("User-Agent=" + KimchiResponsesChatModel.KIMCHI_USER_AGENT)),
                capturedHeaders.toString());
        assertTrue(capturedHeaders.stream().anyMatch(h -> h.equals("Accept=text/event-stream")));
        assertTrue(capturedHeaders.stream().anyMatch(h -> h.equals("Content-Type=application/json")));

        JsonNode body = lastRequestJson();
        assertEquals("minimax-m3", body.path("model").asText());
        assertTrue(body.path("stream").asBoolean());
        assertEquals(1024, body.path("max_tokens").asInt());
        assertEquals("user", body.path("messages").get(0).path("role").asText());
        assertEquals("hello", body.path("messages").get(0).path("content").asText());
        assertFalse(body.has("tools"), "no tool specifications were supplied");
    }

    @Test
    void testEmptyStreamYieldsEmptyTextWithNoTokenUsage() {
        ChatResponse response = chatWith("");
        assertEquals("", response.aiMessage().text());
        assertEquals(FinishReason.STOP, response.finishReason());
        assertNull(response.tokenUsage());
    }

    @Test
    void testTokenUsageIsReportedWhenTheFinalChunkCarriesIt() {
        ChatResponse response = chatWith(sse(
                "{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}",
                "{\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7},\"choices\":[]}"));
        assertNotNull(response.tokenUsage());
        assertEquals(11, response.tokenUsage().inputTokenCount());
        assertEquals(7, response.tokenUsage().outputTokenCount());
    }

    // -------------------------------------------------------------- tool calling

    @Test
    void testToolCallNameAndArgumentsAccumulateAcrossChunks() {
        ChatResponse response = chatWith(sse(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_a\","
                        + "\"function\":{\"name\":\"reverse_\",\"arguments\":\"{\\\"input\\\":\"}}]}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"name\":\"text\",\"arguments\":\"\\\"hello\\\"}\"}}]},"
                        + "\"finish_reason\":\"tool_calls\"}]}"));

        assertEquals(FinishReason.TOOL_EXECUTION, response.finishReason());
        assertTrue(response.aiMessage().hasToolExecutionRequests());
        List<ToolExecutionRequest> requests = response.aiMessage().toolExecutionRequests();
        assertEquals(1, requests.size());
        assertEquals("call_a", requests.get(0).id());
        assertEquals("reverse_text", requests.get(0).name());
        assertEquals("{\"input\":\"hello\"}", requests.get(0).arguments());
    }

    @Test
    void testToolCallWithoutAnIdYieldsAnEmptyId() {
        ChatResponse response = chatWith(sse("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"function\":{\"name\":\"noop\",\"arguments\":\"{}\"}}]}}]}"));
        assertEquals("", response.aiMessage().toolExecutionRequests().get(0).id());
    }

    @Test
    void testToolCallIndexGapLeavesNoPlaceholderRequest() {
        // Index 1 only: the builder list must grow to hold it, and the nameless index-0 builder must be
        // dropped by build() rather than emitted as an empty tool call.
        ChatResponse response = chatWith(sse("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"call_b\","
                + "\"function\":{\"name\":\"second\",\"arguments\":\"{}\"}}]}}]}"));
        List<ToolExecutionRequest> requests = response.aiMessage().toolExecutionRequests();
        assertEquals(1, requests.size());
        assertEquals("second", requests.get(0).name());
        assertEquals("call_b", requests.get(0).id());
    }

    @Test
    void testTextAndToolCallsCombineIntoASingleAiMessage() {
        ChatResponse response = chatWith(sse("{\"choices\":[{\"delta\":{\"content\":\"thinking\","
                + "\"tool_calls\":[{\"index\":0,\"id\":\"call_c\","
                + "\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}}]}}]}"));
        assertEquals("thinking", response.aiMessage().text());
        assertEquals(1, response.aiMessage().toolExecutionRequests().size());
        assertEquals(
                "lookup", response.aiMessage().toolExecutionRequests().get(0).name());
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
                chatWith(sse("{\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"function_call\"}]}"))
                        .finishReason());
        assertEquals(
                FinishReason.STOP,
                chatWith(sse("{\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":\"content_filter\"}]}"))
                        .finishReason());
    }

    @Test
    void testBlankAndNullLiteralFinishReasonsAreIgnored() {
        // The literal string "null" and a blank value must not overwrite the default; build() then
        // substitutes STOP because no reason was ever recorded.
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
        ChatResponse response = chatWith(sse(
                "{\"choices\":[{\"delta\":{\"content\":\"before\"}}]}",
                "{not-json",
                "{\"choices\":[{\"delta\":{\"content\":\"-after\"}}]}"));
        assertEquals("before-after", response.aiMessage().text());
    }

    @Test
    void testCommentBlankAndNonDataLinesAreIgnored() {
        ChatResponse response = chatWith(": keep-alive\n\n"
                + "event: message\n"
                + "\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"kept\"}}]}\n"
                + "\n");
        assertEquals("kept", response.aiMessage().text());
    }

    @Test
    void testDoneSentinelStopsParsingTheRestOfTheStream() {
        ChatResponse response = chatWith(sse("{\"choices\":[{\"delta\":{\"content\":\"kept\"}}]}", "[DONE]"));
        assertEquals("kept", response.aiMessage().text());
    }

    @Test
    void testChunksWithoutChoicesOrWithAnEmptyChoiceArrayAreTolerated() {
        ChatResponse response = chatWith(sse(
                "{\"id\":\"no-choices-here\"}",
                "{\"choices\":[]}",
                "{\"choices\":[{\"delta\":{\"content\":\"survived\"}}]}"));
        assertEquals("survived", response.aiMessage().text());
    }

    @Test
    void testNullContentAndTheNullLiteralAreNotAppended() {
        // consumeDelta() skips both a missing/JSON-null content field and the literal string "null".
        ChatResponse response = chatWith(sse(
                "{\"choices\":[{\"delta\":{\"content\":null}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"null\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"real\"}}]}"));
        assertEquals("real", response.aiMessage().text());
    }

    // ---------------------------------------------------------------- payloads

    @Test
    void testToolSpecificationsAreSerialisedIntoThePayload() throws Exception {
        sseBody.set(sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));
        ToolSpecification spec = ToolSpecification.builder()
                .name("reverse_text")
                .description("Reverses text")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("input", "Text to reverse")
                        .required("input")
                        .build())
                .build();
        model().chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("go")))
                .toolSpecifications(List.of(spec))
                .build());

        JsonNode tools = lastRequestJson().path("tools");
        assertTrue(tools.isArray());
        assertEquals(1, tools.size());
        assertEquals("function", tools.get(0).path("type").asText());
        assertEquals("reverse_text", tools.get(0).path("function").path("name").asText());
        assertEquals(
                "Reverses text",
                tools.get(0).path("function").path("description").asText());
        assertEquals(
                "object",
                tools.get(0).path("function").path("parameters").path("type").asText());
    }

    @Test
    void testToolSpecificationWithoutDescriptionOrParametersIsStillSerialised() throws Exception {
        sseBody.set(sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));
        ToolSpecification bare = ToolSpecification.builder().name("ping").build();
        model().chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from("go")))
                .toolSpecifications(List.of(bare))
                .build());

        JsonNode fn = lastRequestJson().path("tools").get(0).path("function");
        assertEquals("ping", fn.path("name").asText());
        assertFalse(fn.has("description"), "a null description must be omitted, not serialised as null");
        assertFalse(fn.has("parameters"), "null parameters must be omitted");
    }

    @Test
    void testEveryMessageRoleIsMappedIntoThePayload() throws Exception {
        sseBody.set(sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from("You are Kimchi."));
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
        assertEquals("You are Kimchi.", sent.get(0).path("content").asText());
        assertEquals("user", sent.get(1).path("role").asText());
        assertEquals("assistant", sent.get(2).path("role").asText());
        assertEquals("a plain assistant turn", sent.get(2).path("content").asText());
        assertEquals("assistant", sent.get(3).path("role").asText());
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
        sseBody.set(sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));
        model().chat(ChatRequest.builder()
                .messages(List.of(AiMessage.from(""), UserMessage.from("go")))
                .build());

        JsonNode assistant = lastRequestJson().path("messages").get(0);
        assertEquals("assistant", assistant.path("role").asText());
        assertFalse(assistant.has("content"), "blank assistant text must be omitted");
    }

    // ------------------------------------------------------------- secret lifecycle

    @Test
    void testClearSecretZeroesTheTokenAndBlocksFurtherUse() {
        KimchiResponsesChatModel model = model();
        model.clearSecret();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> model.chat(request("hi")));
        assertTrue(ex.getMessage().contains("requires active API credentials"), ex.getMessage());
    }

    @Test
    void testClearSecretIsIdempotent() {
        KimchiResponsesChatModel model = model();
        model.clearSecret();
        model.clearSecret(); // second call sees a zero-length token and only resets the mapper
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> model.chat(request("hi")));
        assertTrue(ex.getMessage().contains("requires active API credentials"), ex.getMessage());
    }
}

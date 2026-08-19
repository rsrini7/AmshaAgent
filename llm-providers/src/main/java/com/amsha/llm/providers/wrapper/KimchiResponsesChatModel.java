package com.amsha.llm.providers.wrapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.amsha.common.util.LogUtil;
import com.amsha.core.security.OutboundPurpose;
import com.amsha.core.security.http.GovernedTransportService;
import com.amsha.core.tools.ToolSchemaProvider;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.providers.catalog.*;
import com.amsha.llm.providers.helper.*;
import com.amsha.llm.providers.reasoning.*;
import com.amsha.llm.providers.routing.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;

/**
 * Direct LangChain4j ChatModel implementation for Kimchi (kimchi.dev) inference API.
 * Connects over OpenAI-compatible gateway with required custom User-Agent and governed transport.
 */
public class KimchiResponsesChatModel implements ChatModel {

    private static final Logger log = Logger.getLogger(KimchiResponsesChatModel.class);
    public static final String KIMCHI_USER_AGENT = "kimchi/0.1.92";

    private final String endpoint;
    private final char[] token;
    private final String model;
    private final int maxTokens;
    private final Duration timeout;
    private final double temperature;
    private final ObjectMapper objectMapper;
    private final GovernedTransportService governedTransport;

    public KimchiResponsesChatModel(
            String baseUrl, String token, String model, Integer maxTokens, Duration timeout, Double temperature) {
        this(baseUrl, token, model, maxTokens, timeout, temperature, null);
    }

    public KimchiResponsesChatModel(
            String baseUrl,
            String token,
            String model,
            Integer maxTokens,
            Duration timeout,
            Double temperature,
            GovernedTransportService governedTransport) {
        this.endpoint = resolveEndpoint(baseUrl);
        this.token = (token != null && !token.isBlank()) ? token.trim().toCharArray() : new char[0];
        this.model = (model != null && !model.isBlank()) ? model.trim() : ModelDefaults.DEFAULT_KIMCHI_MODEL;
        this.maxTokens = (maxTokens != null && maxTokens > 0) ? maxTokens : 8192;
        this.timeout =
                (timeout != null && !timeout.isZero() && !timeout.isNegative()) ? timeout : Duration.ofMinutes(3);
        this.temperature = (temperature != null && temperature >= 0.0) ? temperature : 0.1;
        this.objectMapper = new ObjectMapper();
        this.governedTransport = resolveGovernedTransport(governedTransport);
    }

    private static GovernedTransportService resolveGovernedTransport(GovernedTransportService explicit) {
        if (explicit != null) {
            return explicit;
        }
        try {
            ArcContainer container = Arc.container();
            if (container != null) {
                InstanceHandle<GovernedTransportService> handle = container.instance(GovernedTransportService.class);
                if (handle != null && handle.isAvailable()) {
                    return handle.get();
                }
            }
        } catch (Exception _) {
            // Context not active or standalone test mode
        }
        return null;
    }

    private static String resolveEndpoint(String baseUrl) {
        String base = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl.trim() : ModelDefaults.DEFAULT_KIMCHI_BASE_URL;
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        if (base.endsWith("/")) {
            return base + "chat/completions";
        }
        return base + "/chat/completions";
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        if (token.length == 0) {
            throw new IllegalStateException("Kimchi provider requires active API credentials (AMSHA_KIMCHI_API_KEY).");
        }

        try {
            Map<String, Object> payload = buildPayload(request);
            String jsonBody = objectMapper.writeValueAsString(payload);

            HttpRequest.Builder httpReqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", KIMCHI_USER_AGENT)
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));

            applyAuthHeaders(httpReqBuilder, token);

            HttpRequest httpRequest = httpReqBuilder.build();
            HttpResponse<InputStream> httpResp;
            if (governedTransport != null) {
                httpResp = governedTransport.send(
                        endpoint,
                        OutboundPurpose.PROVIDER_API_CALL,
                        null,
                        "system",
                        true,
                        httpRequest,
                        HttpResponse.BodyHandlers.ofInputStream());
            } else {
                HttpClient client = HttpClient.newHttpClient();
                httpResp = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            }

            if (httpResp.statusCode() != 200) {
                throw new IllegalStateException("Kimchi API request failed with HTTP " + httpResp.statusCode());
            }

            return parseSseStream(httpResp.body());

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute Kimchi chat request: " + LogUtil.rootMessage(e), e);
        }
    }

    private static void applyAuthHeaders(HttpRequest.Builder builder, char[] tokenChars) {
        if (tokenChars == null || tokenChars.length == 0) return;
        String tokenStr = new String(tokenChars).trim();
        builder.header("Authorization", "Bearer " + tokenStr);
    }

    private Map<String, Object> buildPayload(ChatRequest request) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model", model);
        root.put("stream", true);
        root.put("temperature", temperature);
        root.put("max_tokens", maxTokens);
        root.put("messages", buildMessages(request.messages()));

        List<Map<String, Object>> tools = buildTools(request.toolSpecifications());
        if (!tools.isEmpty()) {
            root.put("tools", tools);
        }

        return root;
    }

    private List<Map<String, Object>> buildMessages(List<ChatMessage> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (messages == null) return out;

        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage sm) {
                out.add(Map.of("role", "system", "content", sm.text()));
            } else if (msg instanceof UserMessage um) {
                out.add(Map.of("role", "user", "content", um.singleText()));
            } else if (msg instanceof AiMessage am) {
                Map<String, Object> aiMsg = new LinkedHashMap<>();
                aiMsg.put("role", "assistant");
                if (am.text() != null && !am.text().isBlank()) {
                    aiMsg.put("content", am.text());
                }
                if (am.hasToolExecutionRequests()) {
                    List<Map<String, Object>> toolCalls = new ArrayList<>();
                    for (ToolExecutionRequest req : am.toolExecutionRequests()) {
                        Map<String, Object> tc = new LinkedHashMap<>();
                        tc.put("id", req.id() != null ? req.id() : "");
                        tc.put("type", "function");
                        tc.put(
                                "function",
                                Map.of(
                                        "name",
                                        req.name(),
                                        "arguments",
                                        req.arguments() != null ? req.arguments() : "{}"));
                        toolCalls.add(tc);
                    }
                    aiMsg.put("tool_calls", toolCalls);
                }
                out.add(aiMsg);
            } else if (msg instanceof ToolExecutionResultMessage trm) {
                Map<String, Object> toolResp = new LinkedHashMap<>();
                toolResp.put("role", "tool");
                toolResp.put("tool_call_id", trm.id() != null ? trm.id() : "");
                toolResp.put("content", trm.text() != null ? trm.text() : "");
                out.add(toolResp);
            }
        }
        return out;
    }

    private List<Map<String, Object>> buildTools(List<ToolSpecification> toolSpecifications) {
        List<Map<String, Object>> tools = new ArrayList<>();
        if (toolSpecifications == null || toolSpecifications.isEmpty()) {
            return tools;
        }

        for (ToolSpecification spec : toolSpecifications) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", spec.name());
            if (spec.description() != null) {
                fn.put("description", spec.description());
            }
            if (spec.parameters() != null) {
                fn.put("parameters", ToolSchemaProvider.schemaToMap(spec.parameters()));
            }
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", fn);
            tools.add(tool);
        }
        return tools;
    }

    private ChatResponse parseSseStream(InputStream inputStream) throws Exception {
        SseAccumulator acc = new SseAccumulator();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(":")) {
                    continue;
                }
                if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    try {
                        JsonNode chunkNode = objectMapper.readTree(data);
                        acc.consumeChunk(chunkNode);
                    } catch (Exception e) {
                        log.debugf("Failed to parse Kimchi SSE chunk: %s", LogUtil.rootMessage(e));
                    }
                }
            }
        }

        return acc.build();
    }

    private static class SseAccumulator {
        private final StringBuilder textBuilder = new StringBuilder();
        private final List<ToolCallBuilder> toolCallBuilders = new ArrayList<>();
        private FinishReason finishReason = null;
        private int inputTokens = 0;
        private int outputTokens = 0;

        void consumeChunk(JsonNode chunk) {
            if (chunk == null) return;
            consumeUsage(chunk);
            consumeChoices(chunk);
        }

        private void consumeUsage(JsonNode chunk) {
            JsonNode usageNode = chunk.path("usage");
            if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                inputTokens = usageNode.path("prompt_tokens").asInt(inputTokens);
                outputTokens = usageNode.path("completion_tokens").asInt(outputTokens);
            }
        }

        private void consumeChoices(JsonNode chunk) {
            JsonNode choices = chunk.path("choices");
            if (!choices.isArray() || choices.isEmpty()) return;

            JsonNode choice0 = choices.get(0);
            String finish = choice0.path("finish_reason").asText(null);
            if (finish != null && !finish.isBlank() && !"null".equalsIgnoreCase(finish)) {
                this.finishReason = mapFinishReason(finish);
            }

            JsonNode delta = choice0.path("delta");
            if (!delta.isMissingNode() && !delta.isNull()) {
                consumeDelta(delta);
            }
        }

        private void consumeDelta(JsonNode delta) {
            String content = delta.path("content").asText(null);
            if (content != null && !"null".equalsIgnoreCase(content)) {
                textBuilder.append(content);
            }
            JsonNode toolCallsNode = delta.path("tool_calls");
            if (toolCallsNode.isArray()) {
                consumeToolCalls(toolCallsNode);
            }
        }

        private void consumeToolCalls(JsonNode toolCallsNode) {
            for (JsonNode tcNode : toolCallsNode) {
                int index = tcNode.path("index").asInt(0);
                while (toolCallBuilders.size() <= index) {
                    toolCallBuilders.add(new ToolCallBuilder());
                }
                ToolCallBuilder tcb = toolCallBuilders.get(index);
                String id = tcNode.path("id").asText(null);
                if (id != null && !id.isBlank()) {
                    tcb.id = id;
                }
                JsonNode fnNode = tcNode.path("function");
                if (!fnNode.isMissingNode() && !fnNode.isNull()) {
                    String name = fnNode.path("name").asText(null);
                    if (name != null && !name.isBlank()) {
                        tcb.name.append(name);
                    }
                    String args = fnNode.path("arguments").asText(null);
                    if (args != null && !args.isBlank()) {
                        tcb.arguments.append(args);
                    }
                }
            }
        }

        ChatResponse build() {
            List<ToolExecutionRequest> toolRequests = new ArrayList<>();
            for (ToolCallBuilder tcb : toolCallBuilders) {
                if (tcb.name.length() > 0) {
                    toolRequests.add(ToolExecutionRequest.builder()
                            .id(tcb.id != null ? tcb.id : "")
                            .name(tcb.name.toString())
                            .arguments(tcb.arguments.toString())
                            .build());
                }
            }

            AiMessage aiMessage;
            if (!toolRequests.isEmpty()) {
                aiMessage = (textBuilder.length() > 0)
                        ? AiMessage.from(textBuilder.toString(), toolRequests)
                        : AiMessage.from(toolRequests);
            } else {
                aiMessage = AiMessage.from(textBuilder.toString());
            }

            TokenUsage tokenUsage =
                    (inputTokens > 0 || outputTokens > 0) ? new TokenUsage(inputTokens, outputTokens) : null;

            return ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .finishReason(finishReason != null ? finishReason : FinishReason.STOP)
                    .tokenUsage(tokenUsage)
                    .build();
        }

        private static FinishReason mapFinishReason(String reason) {
            if (reason == null) return FinishReason.STOP;
            return switch (reason.toLowerCase(Locale.ROOT)) {
                case "tool_calls", "function_call" -> FinishReason.TOOL_EXECUTION;
                case "length" -> FinishReason.LENGTH;
                default -> FinishReason.STOP;
            };
        }
    }

    private static class ToolCallBuilder {
        String id;
        StringBuilder name = new StringBuilder();
        StringBuilder arguments = new StringBuilder();
    }
}

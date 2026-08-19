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
import java.net.ConnectException;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
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
 * Direct LangChain4j ChatModel implementation for Microsoft Foundry Local (daemon / CLI).
 * Connects over OpenAI-compatible local API with fallback to Foundry CLI execution.
 */
public class FoundryResponsesChatModel implements ChatModel {

    private static final Logger log = Logger.getLogger(FoundryResponsesChatModel.class);

    private final String endpoint;
    private final String model;
    private final int maxTokens;
    private final Duration timeout;
    private final double temperature;
    private final ObjectMapper objectMapper;
    private final GovernedTransportService governedTransport;

    public FoundryResponsesChatModel(
            String baseUrl, String model, Integer maxTokens, Duration timeout, Double temperature) {
        this(baseUrl, model, maxTokens, timeout, temperature, null);
    }

    public FoundryResponsesChatModel(
            String baseUrl,
            String model,
            Integer maxTokens,
            Duration timeout,
            Double temperature,
            GovernedTransportService governedTransport) {
        this.endpoint = resolveEndpoint(baseUrl);
        this.model = (model != null && !model.isBlank()) ? model.trim() : ModelDefaults.DEFAULT_FOUNDRY_MODEL;
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
        String resolved = FoundryDiscoveryHelper.resolveBaseUrl(baseUrl);
        String clean = resolved.trim();
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.endsWith("/chat/completions")) {
            return clean;
        }
        if (clean.endsWith("/v1")) {
            return clean + "/chat/completions";
        }
        return clean + "/chat/completions";
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        try {
            return chatViaHttp(request);
        } catch (Exception e) {
            if (isTransportFailure(e) && FoundryCliExecutor.isAvailable()) {
                log.debugf("Foundry HTTP endpoint failed (%s); falling back to CLI execution", LogUtil.rootMessage(e));
                var tools =
                        (request.parameters() != null) ? request.parameters().toolSpecifications() : null;
                return FoundryCliExecutor.execute(
                        FoundryCliExecutor.resolveCliPath(), model, request.messages(), tools, timeout, objectMapper);
            }
            throw e;
        }
    }

    private static boolean isTransportFailure(Throwable t) {
        Throwable curr = t;
        while (curr != null) {
            if (curr instanceof ConnectException
                    || curr instanceof HttpConnectTimeoutException
                    || curr instanceof HttpTimeoutException
                    || curr instanceof SocketException
                    || curr instanceof ClosedChannelException
                    || curr instanceof UnknownHostException) {
                return true;
            }
            String msg = curr.getMessage();
            if (msg != null && (msg.contains("HTTP 404") || msg.contains("HTTP 503"))) {
                return true;
            }
            curr = curr.getCause();
        }
        return false;
    }

    private ChatResponse chatViaHttp(ChatRequest request) {
        try {
            Map<String, Object> payload = buildPayload(request);
            byte[] bodyBytes = objectMapper.writeValueAsBytes(payload);

            HttpRequest.Builder httpReqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes));

            HttpRequest httpRequest = httpReqBuilder.build();

            HttpResponse<InputStream> response;
            if (governedTransport != null) {
                response = governedTransport.send(
                        endpoint,
                        OutboundPurpose.PROVIDER_API_CALL,
                        null,
                        "system",
                        true,
                        httpRequest,
                        HttpResponse.BodyHandlers.ofInputStream());
            } else {
                try (HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build()) {
                    response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                }
            }

            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                throw new IllegalStateException("Foundry Local request failed with HTTP " + statusCode);
            }

            try (InputStream is = response.body();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return parseSseResponse(reader);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to call Foundry Local API: " + LogUtil.rootMessage(e), e);
        }
    }

    private Map<String, Object> buildPayload(ChatRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("temperature", temperature);
        payload.put("max_tokens", maxTokens);
        payload.put("stream", true);
        payload.put("stream_options", Map.of("include_usage", true));
        payload.put("messages", buildMessages(request.messages()));

        if (request.parameters() != null && request.parameters().toolSpecifications() != null) {
            List<Map<String, Object>> tools = buildTools(request.parameters().toolSpecifications());
            if (!tools.isEmpty()) {
                payload.put("tools", tools);
            }
        }
        return payload;
    }

    private static List<Map<String, Object>> buildMessages(List<ChatMessage> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (messages == null) return out;

        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage sm) {
                out.add(Map.of("role", "system", "content", sm.text()));
            } else if (msg instanceof UserMessage um) {
                out.add(Map.of("role", "user", "content", um.singleText()));
            } else if (msg instanceof AiMessage am) {
                out.add(buildAiMessage(am));
            } else if (msg instanceof ToolExecutionResultMessage trm) {
                out.add(Map.of(
                        "role", "tool",
                        "tool_call_id", trm.id() != null ? trm.id() : "call_1",
                        "name", trm.toolName() != null ? trm.toolName() : "",
                        "content", trm.text() != null ? trm.text() : ""));
            }
        }
        return out;
    }

    private static Map<String, Object> buildAiMessage(AiMessage am) {
        Map<String, Object> aiMap = new LinkedHashMap<>();
        aiMap.put("role", "assistant");
        if (am.text() != null && !am.text().isBlank()) {
            aiMap.put("content", am.text());
        }
        if (am.hasToolExecutionRequests()) {
            List<Map<String, Object>> toolCalls = new ArrayList<>();
            for (ToolExecutionRequest req : am.toolExecutionRequests()) {
                toolCalls.add(Map.of(
                        "id",
                        req.id() != null ? req.id() : "call_" + System.currentTimeMillis(),
                        "type",
                        "function",
                        "function",
                        Map.of("name", req.name(), "arguments", req.arguments() != null ? req.arguments() : "{}")));
            }
            aiMap.put("tool_calls", toolCalls);
        }
        return aiMap;
    }

    private static List<Map<String, Object>> buildTools(List<ToolSpecification> toolSpecifications) {
        List<Map<String, Object>> tools = new ArrayList<>();
        if (toolSpecifications == null || toolSpecifications.isEmpty()) {
            return tools;
        }
        for (ToolSpecification ts : toolSpecifications) {
            Map<String, Object> funcDef = new LinkedHashMap<>();
            funcDef.put("name", ts.name());
            if (ts.description() != null) {
                funcDef.put("description", ts.description());
            }
            if (ts.parameters() != null) {
                funcDef.put("parameters", ToolSchemaProvider.schemaToMap(ts.parameters()));
            } else {
                funcDef.put("parameters", Map.of("type", "object", "properties", Map.of()));
            }
            tools.add(Map.of("type", "function", "function", funcDef));
        }
        return tools;
    }

    private ChatResponse parseSseResponse(BufferedReader reader) throws Exception {
        SseAccumulator acc = new SseAccumulator();
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith(":")) {
                continue;
            }
            if (trimmed.startsWith("data:")) {
                String data = trimmed.substring(5).trim();
                if ("[DONE]".equalsIgnoreCase(data)) {
                    break;
                }
                try {
                    JsonNode node = objectMapper.readTree(data);
                    acc.consumeChunk(node);
                } catch (Exception e) {
                    log.debugf("Failed to parse Foundry SSE chunk: %s", LogUtil.rootMessage(e));
                }
            }
        }
        return acc.build();
    }

    private static class SseAccumulator {
        private final StringBuilder textBuilder = new StringBuilder();
        private final Map<Integer, ToolCallAccumulator> toolCallsMap = new LinkedHashMap<>();
        private int inputTokens = 0;
        private int outputTokens = 0;
        private FinishReason finishReason = FinishReason.STOP;

        void consumeChunk(JsonNode chunk) {
            if (chunk == null) return;
            consumeUsage(chunk);
            consumeChoices(chunk);
        }

        private void consumeUsage(JsonNode chunk) {
            JsonNode usage = chunk.path("usage");
            if (!usage.isMissingNode() && !usage.isNull()) {
                inputTokens = usage.path("prompt_tokens").asInt(inputTokens);
                outputTokens = usage.path("completion_tokens").asInt(outputTokens);
            }
        }

        private void consumeChoices(JsonNode chunk) {
            JsonNode choices = chunk.path("choices");
            if (!choices.isArray() || choices.isEmpty()) return;

            JsonNode choice = choices.get(0);
            String reasonStr = choice.path("finish_reason").asText(null);
            if (reasonStr != null && !reasonStr.isBlank() && !"null".equalsIgnoreCase(reasonStr)) {
                this.finishReason = mapFinishReason(reasonStr);
            }

            JsonNode delta = choice.path("delta");
            if (!delta.isMissingNode() && !delta.isNull()) {
                consumeDelta(delta);
            }
        }

        private void consumeDelta(JsonNode delta) {
            String content = delta.path("content").asText(null);
            if (content != null && !content.isEmpty()) {
                textBuilder.append(content);
            }
            JsonNode toolCalls = delta.path("tool_calls");
            if (toolCalls.isArray()) {
                consumeToolCalls(toolCalls);
            }
        }

        private void consumeToolCalls(JsonNode toolCalls) {
            for (JsonNode tc : toolCalls) {
                int index = tc.path("index").asInt(0);
                ToolCallAccumulator acc = toolCallsMap.computeIfAbsent(index, _ -> new ToolCallAccumulator());
                if (tc.hasNonNull("id")) {
                    acc.id = tc.get("id").asText();
                }
                JsonNode fn = tc.path("function");
                if (!fn.isMissingNode() && !fn.isNull()) {
                    if (fn.hasNonNull("name")) {
                        acc.name.append(fn.get("name").asText());
                    }
                    if (fn.hasNonNull("arguments")) {
                        acc.arguments.append(fn.get("arguments").asText());
                    }
                }
            }
        }

        ChatResponse build() {
            List<ToolExecutionRequest> toolExecutionRequests = new ArrayList<>();
            for (ToolCallAccumulator acc : toolCallsMap.values()) {
                toolExecutionRequests.add(ToolExecutionRequest.builder()
                        .id(acc.id != null ? acc.id : "call_" + System.currentTimeMillis())
                        .name(acc.name.toString().trim())
                        .arguments(acc.arguments.toString().trim())
                        .build());
            }

            TokenUsage tokenUsage =
                    (inputTokens > 0 || outputTokens > 0) ? new TokenUsage(inputTokens, outputTokens) : null;

            AiMessage aiMessage;
            if (!toolExecutionRequests.isEmpty()) {
                finishReason = FinishReason.TOOL_EXECUTION;
                aiMessage = AiMessage.from(toolExecutionRequests);
            } else {
                String fullText = stripThinkingTags(textBuilder.toString());
                aiMessage = AiMessage.from(fullText);
            }

            return ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .tokenUsage(tokenUsage)
                    .finishReason(finishReason)
                    .build();
        }

        private static String stripThinkingTags(String text) {
            if (text == null || text.isBlank()) {
                return "";
            }
            String result = text;
            int start = result.indexOf("<think>");
            while (start != -1) {
                int end = result.indexOf("</think>", start);
                if (end != -1) {
                    result = result.substring(0, start) + result.substring(end + 8);
                } else {
                    result = result.substring(start + 7);
                    break;
                }
                start = result.indexOf("<think>");
            }
            String stripped = result.trim();
            if (!stripped.isEmpty()) {
                return stripped;
            }
            return text.replace("<think>", "").replace("</think>", "").trim();
        }

        private static FinishReason mapFinishReason(String reason) {
            if (reason == null) return FinishReason.STOP;
            return switch (reason.toLowerCase(Locale.ROOT)) {
                case "tool_calls", "tool_call" -> FinishReason.TOOL_EXECUTION;
                case "length" -> FinishReason.LENGTH;
                default -> FinishReason.STOP;
            };
        }
    }

    private static final class ToolCallAccumulator {
        String id;
        final StringBuilder name = new StringBuilder();
        final StringBuilder arguments = new StringBuilder();
    }
}

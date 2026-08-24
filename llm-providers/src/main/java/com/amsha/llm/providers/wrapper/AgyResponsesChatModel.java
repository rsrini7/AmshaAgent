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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.amsha.common.util.LogUtil;
import com.amsha.core.tools.ToolSchemaProvider;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.llm.providers.helper.AgyCliExecutor;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.http.GovernedTransportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;

/**
 * Direct LangChain4j ChatModel implementation for Antigravity (agy) / Google Gemini endpoint.
 * Connects directly using agy CLI OAuth session tokens or Gemini API keys with governed transport.
 */
public class AgyResponsesChatModel implements ChatModel {

    private static final String DEFAULT_AGY_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";

    private final String endpoint;
    private final char[] token;
    private final String model;
    private final int maxTokens;
    private final Duration timeout;
    private final double temperature;
    private final ObjectMapper objectMapper;
    private final GovernedTransportService governedTransport;

    public AgyResponsesChatModel(
            String baseUrl, String token, String model, Integer maxTokens, Duration timeout, Double temperature) {
        this(baseUrl, token, model, maxTokens, timeout, temperature, null);
    }

    public AgyResponsesChatModel(
            String baseUrl,
            String token,
            String model,
            Integer maxTokens,
            Duration timeout,
            Double temperature,
            GovernedTransportService governedTransport) {
        this.endpoint = resolveEndpoint(baseUrl);
        this.token = (token != null && !token.isBlank()) ? token.trim().toCharArray() : new char[0];
        this.model = normalizeModel(model);
        this.maxTokens = (maxTokens != null && maxTokens > 0) ? maxTokens : 8192;
        this.timeout =
                (timeout != null && !timeout.isZero() && !timeout.isNegative()) ? timeout : Duration.ofMinutes(3);
        this.temperature = (temperature != null && temperature >= 0.0) ? temperature : 0.1;
        this.objectMapper = new ObjectMapper();
        this.governedTransport = resolveGovernedTransport(governedTransport);
    }

    private static String normalizeModel(String model) {
        if (model == null || model.isBlank()) {
            return ModelDefaults.DEFAULT_AGY_MODEL;
        }
        String trimmed = model.trim();
        if (trimmed.startsWith("models/")) {
            trimmed = trimmed.substring("models/".length());
        }
        trimmed = trimmed.replace(' ', '-');
        if (!trimmed.startsWith("gemini-") && !trimmed.startsWith("gemma-") && !trimmed.startsWith("antigravity-")) {
            trimmed = "gemini-" + trimmed;
        }
        return trimmed;
    }

    private static GovernedTransportService resolveGovernedTransport(GovernedTransportService explicit) {
        if (explicit != null) {
            return explicit;
        }
        try {
            ArcContainer container = Arc.container();
            if (container != null) {
                InstanceHandle<GovernedTransportService> handle = container.instance(GovernedTransportService.class);
                if (handle.isAvailable()) {
                    return handle.get();
                }
            }
        } catch (Exception _) {
            // CDI not active
        }
        return null;
    }

    private static String resolveEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return DEFAULT_AGY_ENDPOINT;
        }
        String clean = baseUrl.trim();
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.endsWith("/chat/completions")) {
            return clean;
        }
        if (clean.endsWith("/openai")) {
            return clean + "/chat/completions";
        }
        if (clean.endsWith("/v1beta")) {
            return clean + "/openai/chat/completions";
        }
        if (clean.endsWith("/v1")) {
            return clean + "/chat/completions";
        }
        return clean + "/chat/completions";
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        boolean hasHttpToken = token != null && token.length > 0 && !"agy-cli-session".equals(new String(token));
        if (hasHttpToken) {
            return chatViaHttp(request);
        }
        if (AgyCliExecutor.isAvailable()) {
            var tools = (request.parameters() != null) ? request.parameters().toolSpecifications() : null;
            return AgyCliExecutor.execute(
                    AgyCliExecutor.resolveCliPath(), model, request.messages(), tools, timeout, objectMapper);
        }
        if (governedTransport == null) {
            throw new IllegalStateException(
                    "Governed outbound transport (GovernedTransportService) is mandatory for provider calls to "
                            + endpoint);
        }
        throw new IllegalStateException(
                "Antigravity provider requires either a local 'agy' CLI installation or active API credentials "
                        + "(AMSHA_AGY_API_KEY / AMSHA_GEMINI_API_KEY).");
    }

    private ChatResponse chatViaHttp(ChatRequest request) {
        if (governedTransport == null) {
            throw new IllegalStateException(
                    "Governed outbound transport (GovernedTransportService) is mandatory for provider calls to "
                            + endpoint);
        }

        try {
            Map<String, Object> payload = buildPayload(request);
            String jsonBody = objectMapper.writeValueAsString(payload);

            HttpRequest.Builder httpReqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
            applyAuthHeaders(httpReqBuilder, token);

            HttpRequest httpRequest = httpReqBuilder.build();
            HttpResponse<InputStream> httpResp = governedTransport.send(
                    endpoint,
                    OutboundPurpose.PROVIDER_API_CALL,
                    null,
                    "system",
                    true,
                    httpRequest,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (httpResp.statusCode() != 200) {
                String errorDetails;
                try (InputStream errStream = httpResp.body()) {
                    byte[] errBytes = errStream != null ? errStream.readAllBytes() : new byte[0];
                    errorDetails = new String(errBytes, StandardCharsets.UTF_8).trim();
                }
                throw new IllegalStateException("Antigravity API request failed with HTTP " + httpResp.statusCode()
                        + (errorDetails.isEmpty() ? "" : ": " + errorDetails));
            }

            return parseSseStream(httpResp.body());

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute Antigravity chat request: " + LogUtil.rootMessage(e), e);
        }
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
                processSseLine(line.trim(), acc);
            }
        }

        List<ToolExecutionRequest> toolRequests = buildToolRequests(acc.toolCallBuilders);
        AiMessage aiMessage = buildAiMessage(acc.text.toString(), toolRequests);
        FinishReason finishReason = parseFinishReason(acc.finishReason, toolRequests);
        TokenUsage tokenUsage = (acc.inputTokens > 0 || acc.outputTokens > 0)
                ? new TokenUsage(acc.inputTokens, acc.outputTokens)
                : null;

        return ChatResponse.builder()
                .aiMessage(aiMessage)
                .finishReason(finishReason)
                .tokenUsage(tokenUsage)
                .build();
    }

    private void processSseLine(String line, SseAccumulator acc) throws Exception {
        if (!line.startsWith("data:")) {
            return;
        }
        String data = line.substring(5).trim();
        if (data.isEmpty() || "[DONE]".equals(data)) {
            return;
        }

        JsonNode root = objectMapper.readTree(data);
        extractUsage(root, acc);
        extractChoice(root, acc);
    }

    private static void extractUsage(JsonNode root, SseAccumulator acc) {
        if (!root.hasNonNull("usage")) {
            return;
        }
        JsonNode usage = root.get("usage");
        if (usage.hasNonNull("prompt_tokens")) {
            acc.inputTokens = usage.get("prompt_tokens").asInt();
        }
        if (usage.hasNonNull("completion_tokens")) {
            acc.outputTokens = usage.get("completion_tokens").asInt();
        }
    }

    private static void extractChoice(JsonNode root, SseAccumulator acc) {
        if (!root.hasNonNull("choices")
                || !root.get("choices").isArray()
                || root.get("choices").isEmpty()) {
            return;
        }
        JsonNode choice = root.get("choices").get(0);
        if (choice.hasNonNull("finish_reason")) {
            acc.finishReason = choice.get("finish_reason").asText();
        }
        if (choice.hasNonNull("delta") && choice.get("delta").isObject()) {
            extractDelta(choice.get("delta"), acc);
        }
    }

    private static void extractDelta(JsonNode delta, SseAccumulator acc) {
        if (delta.hasNonNull("content")) {
            acc.text.append(delta.get("content").asText());
        }
        if (delta.hasNonNull("tool_calls") && delta.get("tool_calls").isArray()) {
            for (JsonNode tcNode : delta.get("tool_calls")) {
                extractToolCall(tcNode, acc);
            }
        }
    }

    private static void extractToolCall(JsonNode tcNode, SseAccumulator acc) {
        int index = tcNode.hasNonNull("index") ? tcNode.get("index").asInt() : 0;
        ToolExecutionRequestBuilder builder =
                acc.toolCallBuilders.computeIfAbsent(index, _ -> new ToolExecutionRequestBuilder());

        if (tcNode.hasNonNull("id")) {
            builder.id = tcNode.get("id").asText();
        }
        if (tcNode.hasNonNull("function") && tcNode.get("function").isObject()) {
            JsonNode fn = tcNode.get("function");
            if (fn.hasNonNull("name")) {
                builder.name = fn.get("name").asText();
            }
            if (fn.hasNonNull("arguments")) {
                builder.arguments.append(fn.get("arguments").asText());
            }
        }
    }

    private static List<ToolExecutionRequest> buildToolRequests(Map<Integer, ToolExecutionRequestBuilder> builders) {
        List<ToolExecutionRequest> toolRequests = new ArrayList<>();
        for (ToolExecutionRequestBuilder b : builders.values()) {
            if (b.name != null && !b.name.isBlank()) {
                toolRequests.add(ToolExecutionRequest.builder()
                        .id(b.id != null ? b.id : "")
                        .name(b.name)
                        .arguments(b.arguments.toString())
                        .build());
            }
        }
        return toolRequests;
    }

    private static AiMessage buildAiMessage(String text, List<ToolExecutionRequest> toolRequests) {
        if (!toolRequests.isEmpty()) {
            return text.isBlank() ? AiMessage.from(toolRequests) : AiMessage.from(text, toolRequests);
        }
        return AiMessage.from(text);
    }

    private FinishReason parseFinishReason(String reason, List<ToolExecutionRequest> toolRequests) {
        if (!toolRequests.isEmpty()) {
            return FinishReason.TOOL_EXECUTION;
        }
        if (reason == null) {
            return FinishReason.STOP;
        }
        return switch (reason.toLowerCase()) {
            case "tool_calls", "function_call" -> FinishReason.TOOL_EXECUTION;
            case "length" -> FinishReason.LENGTH;
            default -> FinishReason.STOP;
        };
    }

    private static void applyAuthHeaders(HttpRequest.Builder builder, char[] tokenChars) {
        if (tokenChars == null || tokenChars.length == 0) {
            return;
        }
        String tokenStr = new String(tokenChars).trim();
        if (tokenStr.startsWith("Bearer ")) {
            builder.header("Authorization", tokenStr);
        } else {
            builder.header("Authorization", "Bearer " + tokenStr);
        }
    }

    private static final class SseAccumulator {
        final StringBuilder text = new StringBuilder();
        final Map<Integer, ToolExecutionRequestBuilder> toolCallBuilders = new LinkedHashMap<>();
        int inputTokens;
        int outputTokens;
        String finishReason;
    }

    private static class ToolExecutionRequestBuilder {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}

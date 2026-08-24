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
import com.amsha.core.security.OutboundPurpose;
import com.amsha.core.security.http.GovernedTransportService;
import com.amsha.core.tools.ToolSchemaProvider;
import com.amsha.llm.api.ModelDefaults;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;

/**
 * Direct LangChain4j ChatModel implementation for the Anthropic Messages API.
 * Supports both standard Anthropic API keys (x-api-key) and Claude Code CLI OAuth session
 * tokens (Authorization: Bearer + anthropic-beta: oauth-2024-11-01) with governed outbound transport.
 */
public class ClaudeCodeResponsesChatModel implements ChatModel {

    private static final String DEFAULT_ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";

    private final String endpoint;
    private final char[] token;
    private final String model;
    private final int maxTokens;
    private final Duration timeout;
    private final double temperature;
    private final ObjectMapper objectMapper;
    private final GovernedTransportService governedTransport;

    public ClaudeCodeResponsesChatModel(
            String baseUrl, String token, String model, Integer maxTokens, Duration timeout, Double temperature) {
        this(baseUrl, token, model, maxTokens, timeout, temperature, null);
    }

    public ClaudeCodeResponsesChatModel(
            String baseUrl,
            String token,
            String model,
            Integer maxTokens,
            Duration timeout,
            Double temperature,
            GovernedTransportService governedTransport) {
        this.endpoint = resolveEndpoint(baseUrl);
        this.token = (token != null && !token.isBlank()) ? token.trim().toCharArray() : new char[0];
        this.model = (model != null && !model.isBlank()) ? model : ModelDefaults.DEFAULT_CLAUDE_CODE_MODEL;
        this.maxTokens = (maxTokens != null && maxTokens > 0) ? maxTokens : 4096;
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
            return DEFAULT_ANTHROPIC_URL;
        }
        String clean = baseUrl.trim();
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.endsWith("/messages")) {
            return clean;
        }
        if (clean.endsWith("/v1")) {
            return clean + "/messages";
        }
        return clean + "/v1/messages";
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
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
                    .header("anthropic-version", "2023-06-01")
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
                try (InputStream errStream = httpResp.body()) {
                    errStream.readAllBytes();
                }
                throw new IllegalStateException("Claude Code API request failed with HTTP " + httpResp.statusCode());
            }

            return parseSseStream(httpResp.body());

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to communicate with Claude Code API: " + LogUtil.rootMessage(e), e);
        }
    }

    private static void applyAuthHeaders(HttpRequest.Builder builder, char[] tokenChars) {
        if (tokenChars == null || tokenChars.length == 0) {
            return;
        }
        String tokenStr = new String(tokenChars);
        if (tokenStr.startsWith("sk-ant-api")) {
            builder.header("x-api-key", tokenStr);
        } else {
            builder.header("Authorization", "Bearer " + tokenStr);
            builder.header("anthropic-beta", "oauth-2024-11-01");
        }
    }

    private Map<String, Object> buildPayload(ChatRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("max_tokens", maxTokens);
        payload.put("temperature", temperature);
        payload.put("stream", true);

        StringBuilder systemBuilder = new StringBuilder();
        List<Map<String, Object>> messages = new ArrayList<>();

        for (ChatMessage msg : request.messages()) {
            if (msg instanceof SystemMessage sysMsg) {
                if (systemBuilder.length() > 0) systemBuilder.append("\n\n");
                systemBuilder.append(sysMsg.text());
            } else {
                appendChatMessage(messages, msg);
            }
        }

        if (systemBuilder.length() > 0) {
            payload.put("system", systemBuilder.toString());
        }

        payload.put("messages", mergeConsecutiveRoles(messages));

        if (request.toolSpecifications() != null
                && !request.toolSpecifications().isEmpty()) {
            payload.put("tools", buildTools(request.toolSpecifications()));
        }

        return payload;
    }

    private void appendChatMessage(List<Map<String, Object>> messages, ChatMessage msg) {
        if (msg instanceof UserMessage userMsg) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", "user");
            entry.put("content", userMsg.singleText());
            messages.add(entry);
        } else if (msg instanceof AiMessage aiMsg) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", "assistant");
            if (aiMsg.hasToolExecutionRequests()) {
                List<Map<String, Object>> contentParts = new ArrayList<>();
                if (aiMsg.text() != null && !aiMsg.text().isBlank()) {
                    contentParts.add(Map.of("type", "text", "text", aiMsg.text()));
                }
                for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
                    Map<String, Object> toolUse = new LinkedHashMap<>();
                    toolUse.put("type", "tool_use");
                    toolUse.put("id", req.id() != null ? req.id() : "tool_" + System.nanoTime());
                    toolUse.put("name", req.name());
                    toolUse.put("input", parseJsonOrEmpty(req.arguments()));
                    contentParts.add(toolUse);
                }
                entry.put("content", contentParts);
            } else {
                entry.put("content", aiMsg.text() != null ? aiMsg.text() : "");
            }
            messages.add(entry);
        } else if (msg instanceof ToolExecutionResultMessage toolRes) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", "user");
            List<Map<String, Object>> parts = List.of(Map.of(
                    "type", "tool_result",
                    "tool_use_id", toolRes.id() != null ? toolRes.id() : "",
                    "content", toolRes.text() != null ? toolRes.text() : ""));
            entry.put("content", parts);
            messages.add(entry);
        }
    }

    private List<Map<String, Object>> mergeConsecutiveRoles(List<Map<String, Object>> rawMessages) {
        List<Map<String, Object>> merged = new ArrayList<>();
        for (Map<String, Object> msg : rawMessages) {
            if (merged.isEmpty()) {
                merged.add(new LinkedHashMap<>(msg));
                continue;
            }
            Map<String, Object> last = merged.get(merged.size() - 1);
            String lastRole = (String) last.get("role");
            String currRole = (String) msg.get("role");

            if (lastRole.equals(currRole)) {
                List<Map<String, Object>> lastContent = normalizeContentList(last.get("content"));
                List<Map<String, Object>> currContent = normalizeContentList(msg.get("content"));
                lastContent.addAll(currContent);
                last.put("content", lastContent);
            } else {
                merged.add(new LinkedHashMap<>(msg));
            }
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeContentList(Object content) {
        if (content instanceof List<?>) {
            return new ArrayList<>((List<Map<String, Object>>) content);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        if (content instanceof String s) {
            list.add(Map.of("type", "text", "text", s));
        }
        return list;
    }

    private List<Map<String, Object>> buildTools(List<ToolSpecification> specs) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolSpecification spec : specs) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", spec.name());
            if (spec.description() != null && !spec.description().isBlank()) {
                tool.put("description", spec.description());
            }
            if (spec.parameters() != null) {
                Map<String, Object> schema = ToolSchemaProvider.schemaToMap(spec.parameters());
                tool.put("input_schema", schema != null ? schema : Map.of("type", "object", "properties", Map.of()));
            } else {
                tool.put("input_schema", Map.of("type", "object", "properties", Map.of()));
            }
            tools.add(tool);
        }
        return tools;
    }

    private Object parseJsonOrEmpty(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception _) {
            return Map.of();
        }
    }

    private ChatResponse parseSseStream(InputStream inputStream) throws Exception {
        ClaudeSseAccumulator acc = new ClaudeSseAccumulator();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;

                JsonNode node = objectMapper.readTree(data);
                acc.handleEvent(node, data);
            }
        }

        TokenUsage tokenUsage = acc.tokenUsage;
        if (tokenUsage == null) {
            int inEst = 10;
            int outEst = Math.max(1, acc.textBuilder.length() / 4);
            tokenUsage = new TokenUsage(inEst, outEst, inEst + outEst);
        }

        FinishReason finishReason;
        if (!acc.toolRequests.isEmpty() || "tool_use".equals(acc.stopReason)) {
            finishReason = FinishReason.TOOL_EXECUTION;
        } else if ("max_tokens".equals(acc.stopReason)) {
            finishReason = FinishReason.LENGTH;
        } else {
            finishReason = FinishReason.STOP;
        }

        if (!acc.toolRequests.isEmpty()) {
            AiMessage aiMessage = acc.textBuilder.length() > 0
                    ? AiMessage.from(acc.textBuilder.toString(), acc.toolRequests)
                    : AiMessage.from(acc.toolRequests);
            return ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .tokenUsage(tokenUsage)
                    .finishReason(finishReason)
                    .build();
        }

        AiMessage aiMessage = AiMessage.from(acc.textBuilder.toString());
        return ChatResponse.builder()
                .aiMessage(aiMessage)
                .tokenUsage(tokenUsage)
                .finishReason(finishReason)
                .build();
    }

    private static class ClaudeSseAccumulator {
        final StringBuilder textBuilder = new StringBuilder();
        final List<ToolExecutionRequest> toolRequests = new ArrayList<>();
        TokenUsage tokenUsage;
        String stopReason;
        int inputTokens = 0;
        int outputTokens = 0;

        String currentCallId;
        String currentFuncName;
        StringBuilder currentArgs;

        void handleEvent(JsonNode node, String data) {
            String type = node.path("type").asText("");
            switch (type) {
                case "message_start" -> handleMessageStart(node);
                case "content_block_start" -> handleContentBlockStart(node);
                case "content_block_delta" -> handleContentBlockDelta(node);
                case "content_block_stop" -> handleContentBlockStop();
                case "message_delta" -> handleMessageDelta(node);
                case "message_stop" -> updateFinalUsage();
                case "error" ->
                    throw new IllegalStateException("Claude Code SSE Error: "
                            + node.path("error").path("message").asText(data));
                default -> {
                    /* ignore other event types */
                }
            }
        }

        private void handleMessageStart(JsonNode node) {
            JsonNode msg = node.path("message");
            JsonNode usage = msg.path("usage");
            if (!usage.isMissingNode()) {
                inputTokens = usage.path("input_tokens").asInt(inputTokens);
                outputTokens = usage.path("output_tokens").asInt(outputTokens);
            }
        }

        private void handleContentBlockStart(JsonNode node) {
            JsonNode block = node.path("content_block");
            String blockType = block.path("type").asText("");
            if ("tool_use".equals(blockType)) {
                currentCallId = block.path("id").asText("toolu_" + System.nanoTime());
                currentFuncName = block.path("name").asText("");
                currentArgs = new StringBuilder();
            } else if ("text".equals(blockType)) {
                String initialText = block.path("text").asText("");
                if (!initialText.isEmpty()) {
                    textBuilder.append(initialText);
                }
            }
        }

        private void handleContentBlockDelta(JsonNode node) {
            JsonNode delta = node.path("delta");
            String deltaType = delta.path("type").asText("");
            if ("text_delta".equals(deltaType)) {
                textBuilder.append(delta.path("text").asText(""));
            } else if ("input_json_delta".equals(deltaType)) {
                if (currentArgs != null) {
                    currentArgs.append(delta.path("partial_json").asText(""));
                }
            }
        }

        private void handleContentBlockStop() {
            if (currentCallId != null && currentFuncName != null) {
                String args = currentArgs != null && currentArgs.length() > 0 ? currentArgs.toString() : "{}";
                toolRequests.add(ToolExecutionRequest.builder()
                        .id(currentCallId)
                        .name(currentFuncName)
                        .arguments(args)
                        .build());
                currentCallId = null;
                currentFuncName = null;
                currentArgs = null;
            }
        }

        private void handleMessageDelta(JsonNode node) {
            String sr = node.path("delta").path("stop_reason").asText("");
            if (!sr.isEmpty()) {
                this.stopReason = sr;
            }
            JsonNode usage = node.path("usage");
            if (!usage.isMissingNode()) {
                outputTokens = usage.path("output_tokens").asInt(outputTokens);
            }
            updateFinalUsage();
        }

        private void updateFinalUsage() {
            int total = inputTokens + outputTokens;
            if (total > 0) {
                tokenUsage = new TokenUsage(inputTokens, outputTokens, total);
            }
        }
    }
}

package com.amsha.core.routing;

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
import java.util.Map;

import com.amsha.core.tools.ToolSchemaProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Direct LangChain4j ChatModel implementation for the OpenAI Responses API
 * (https://chatgpt.com/backend-api/codex/responses).
 * Connects directly using ChatGPT Plus subscriber OAuth tokens with zero proxy dependencies.
 */
public class CodexResponsesChatModel implements ChatModel {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String endpoint;
    private final String token;
    private final String accountId;
    private final String model;
    private final Duration timeout;
    private final HttpClient httpClient;

    public CodexResponsesChatModel(
            String endpoint,
            String token,
            String accountId,
            String model,
            Integer maxTokens,
            Duration timeout,
            Double temperature) {
        this.endpoint =
                endpoint != null && !endpoint.isBlank() ? endpoint : "https://chatgpt.com/backend-api/codex/responses";
        this.token = token;
        this.accountId = accountId;
        this.model = model != null && !model.isBlank() ? model : "gpt-5.6-luna";
        this.timeout = timeout != null ? timeout : Duration.ofMinutes(3);
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        try {
            Map<String, Object> payload = buildPayload(request);
            String jsonBody = OBJECT_MAPPER.writeValueAsString(payload);

            HttpRequest.Builder httpReqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));

            if (accountId != null && !accountId.isBlank()) {
                httpReqBuilder.header("ChatGPT-Account-ID", accountId);
            }

            HttpResponse<InputStream> httpResp =
                    httpClient.send(httpReqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());

            if (httpResp.statusCode() != 200) {
                String errorBody;
                try (InputStream errStream = httpResp.body()) {
                    errorBody = new String(errStream.readAllBytes(), StandardCharsets.UTF_8);
                }
                throw new IllegalStateException(
                        "Codex Responses API error HTTP " + httpResp.statusCode() + ": " + errorBody);
            }

            return parseSseStream(httpResp.body());

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute Codex chat request: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> buildPayload(ChatRequest request) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model", model);
        root.put("store", false);
        root.put("stream", true);
        root.put("input", buildInputMessages(request.messages()));

        List<Map<String, Object>> tools = buildTools(request.toolSpecifications());
        if (!tools.isEmpty()) {
            root.put("tools", tools);
        }

        return root;
    }

    private List<Map<String, Object>> buildInputMessages(List<ChatMessage> messages) {
        List<Map<String, Object>> input = new ArrayList<>();
        if (messages == null) return input;

        for (ChatMessage msg : messages) {
            convertChatMessage(msg, input);
        }
        return input;
    }

    private void convertChatMessage(ChatMessage msg, List<Map<String, Object>> input) {
        if (msg instanceof SystemMessage sm) {
            input.add(Map.of("role", "system", "content", sm.text()));
        } else if (msg instanceof UserMessage um) {
            input.add(Map.of("role", "user", "content", um.singleText()));
        } else if (msg instanceof AiMessage am) {
            convertAiMessage(am, input);
        } else if (msg instanceof ToolExecutionResultMessage trm) {
            input.add(Map.of(
                    "type", "function_call_output",
                    "call_id", trm.id() != null ? trm.id() : "",
                    "output", trm.text() != null ? trm.text() : ""));
        }
    }

    private void convertAiMessage(AiMessage am, List<Map<String, Object>> input) {
        if (am.hasToolExecutionRequests()) {
            for (ToolExecutionRequest req : am.toolExecutionRequests()) {
                input.add(Map.of(
                        "type",
                        "function_call",
                        "name",
                        req.name(),
                        "call_id",
                        req.id() != null ? req.id() : "",
                        "arguments",
                        req.arguments() != null ? req.arguments() : "{}"));
            }
        }
        if (am.text() != null && !am.text().isBlank()) {
            input.add(Map.of("role", "assistant", "content", am.text()));
        }
    }

    private List<Map<String, Object>> buildTools(List<ToolSpecification> toolSpecifications) {
        List<Map<String, Object>> tools = new ArrayList<>();
        if (toolSpecifications == null || toolSpecifications.isEmpty()) {
            return tools;
        }

        for (ToolSpecification spec : toolSpecifications) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("name", spec.name());
            if (spec.description() != null) {
                tool.put("description", spec.description());
            }
            if (spec.parameters() != null) {
                tool.put("parameters", ToolSchemaProvider.schemaToMap(spec.parameters()));
            }
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
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;

                JsonNode node = OBJECT_MAPPER.readTree(data);
                acc.handleEvent(node, data);
            }
        }

        TokenUsage tokenUsage = acc.tokenUsage;
        if (tokenUsage == null) {
            int outEst = Math.max(1, acc.textBuilder.length() / 4);
            tokenUsage = new TokenUsage(10, outEst, 10 + outEst);
        }

        if (!acc.toolRequests.isEmpty()) {
            AiMessage aiMessage = acc.textBuilder.length() > 0
                    ? AiMessage.from(acc.textBuilder.toString(), acc.toolRequests)
                    : AiMessage.from(acc.toolRequests);
            return ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .tokenUsage(tokenUsage)
                    .finishReason(FinishReason.TOOL_EXECUTION)
                    .build();
        }

        AiMessage aiMessage = AiMessage.from(acc.textBuilder.toString());
        return ChatResponse.builder()
                .aiMessage(aiMessage)
                .tokenUsage(tokenUsage)
                .finishReason(FinishReason.STOP)
                .build();
    }

    private static class SseAccumulator {
        final StringBuilder textBuilder = new StringBuilder();
        final List<ToolExecutionRequest> toolRequests = new ArrayList<>();
        TokenUsage tokenUsage;
        String currentCallId;
        String currentFuncName;
        StringBuilder currentArgs;

        void handleEvent(JsonNode node, String data) {
            String type = node.path("type").asText("");
            switch (type) {
                case "response.output_text.delta" ->
                    textBuilder.append(node.path("delta").asText(""));
                case "response.output_text.done" -> handleOutputTextDone(node);
                case "response.output_item.added" -> handleOutputItemAdded(node.path("item"));
                case "response.function_call_arguments.delta" -> handleFuncArgsDelta(node);
                case "response.function_call_arguments.done" -> handleFuncArgsDone(node);
                case "response.output_item.done" -> handleOutputItemDone(node.path("item"));
                case "response.completed" ->
                    handleCompleted(node.path("response").path("usage"));
                case "error" ->
                    throw new IllegalStateException(
                            "Codex SSE Error: " + node.path("message").asText(data));
                default -> {
                    /* ignore other event types */
                }
            }
        }

        private void handleOutputTextDone(JsonNode node) {
            if (textBuilder.length() == 0 && node.has("text")) {
                textBuilder.append(node.path("text").asText(""));
            }
        }

        private void handleOutputItemAdded(JsonNode item) {
            if ("function_call".equals(item.path("type").asText(""))) {
                currentCallId = item.path("call_id").asText("");
                currentFuncName = item.path("name").asText("");
                currentArgs = new StringBuilder(item.path("arguments").asText(""));
            }
        }

        private void handleFuncArgsDelta(JsonNode node) {
            if (currentArgs != null) {
                currentArgs.append(node.path("delta").asText(""));
            }
        }

        private void handleFuncArgsDone(JsonNode node) {
            if (currentArgs != null && node.has("arguments")) {
                currentArgs = new StringBuilder(node.path("arguments").asText());
            }
        }

        private void handleOutputItemDone(JsonNode item) {
            String itemType = item.path("type").asText("");
            if ("function_call".equals(itemType)) {
                String callId = item.path("call_id").asText(currentCallId);
                String name = item.path("name").asText(currentFuncName);
                String args = item.path("arguments").asText(currentArgs != null ? currentArgs.toString() : "{}");
                toolRequests.add(ToolExecutionRequest.builder()
                        .id(callId)
                        .name(name)
                        .arguments(args)
                        .build());
                currentCallId = null;
                currentFuncName = null;
                currentArgs = null;
            } else if ("message".equals(itemType) && textBuilder.length() == 0) {
                extractMessageText(item.path("content"));
            }
        }

        private void extractMessageText(JsonNode content) {
            if (content.isArray()) {
                for (JsonNode part : content) {
                    if (part.has("text")) {
                        textBuilder.append(part.path("text").asText(""));
                    }
                }
            }
        }

        private void handleCompleted(JsonNode usageNode) {
            if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                int inTokens = usageNode.path("input_tokens").asInt(0);
                int outTokens = usageNode.path("output_tokens").asInt(0);
                int totalTokens = usageNode.path("total_tokens").asInt(inTokens + outTokens);
                tokenUsage = new TokenUsage(inTokens, outTokens, totalTokens);
            }
        }
    }
}

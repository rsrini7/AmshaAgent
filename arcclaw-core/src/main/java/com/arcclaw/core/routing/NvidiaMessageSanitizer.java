package com.arcclaw.core.routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

// NvidiaMessageSanitizer.java
public class NvidiaMessageSanitizer implements ChatModel {

    private static final Pattern SHORT_CALL_ID = Pattern.compile("^call_\\d+$");
    private final ChatModel delegate;

    public NvidiaMessageSanitizer(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        List<ChatMessage> sanitized = sanitize(request.messages());

        ChatRequest.Builder builder = ChatRequest.builder().messages(sanitized);

        // Mutually exclusive — set only whichever is present
        if (request.parameters() != null) {
            builder.parameters(request.parameters());
        } else if (request.toolSpecifications() != null) {
            builder.toolSpecifications(request.toolSpecifications());
        }

        ChatResponse response = delegate.chat(builder.build());
        return rewriteToolCallIds(response);
    }

    private List<ChatMessage> sanitize(List<ChatMessage> messages) {
        // Build mapping ONCE — same old ID maps to same new ID for both AiMessage + ToolResult
        Map<String, String> idMapping = new HashMap<>();
        for (ChatMessage msg : messages) {
            if (msg instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                    if (needsIdFix(req.id())) {
                        idMapping.computeIfAbsent(req.id(), k -> generateNvidiaId());
                    }
                }
            }
        }

        List<ChatMessage> system = messages.stream()
                .filter(m -> m instanceof SystemMessage)
                .limit(1).toList();

        List<ChatMessage> rest = messages.stream()
                .filter(m -> !(m instanceof SystemMessage))
                .map(msg -> rewriteWithMapping(msg, idMapping))
                .toList();

        List<ChatMessage> result = new ArrayList<>(system);
        result.addAll(rest);
        return result;
    }

    private boolean needsIdFix(String id) {
        // NVIDIA rejects: call_0, call_1 etc. (too short or wrong format)
        return id == null || SHORT_CALL_ID.matcher(id).matches();
    }

    private ChatMessage rewriteWithMapping(ChatMessage msg, Map<String, String> idMapping) {
        if (msg instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
            List<ToolExecutionRequest> fixed = ai.toolExecutionRequests().stream()
                    .map(req -> {
                        String newId = idMapping.getOrDefault(req.id(), req.id());
                        return newId.equals(req.id()) ? req
                                : ToolExecutionRequest.builder()
                                    .id(newId).name(req.name()).arguments(req.arguments())
                                    .build();
                    }).toList();
            return AiMessage.from(fixed);
        }
        if (msg instanceof ToolExecutionResultMessage tool) {
            String newId = idMapping.getOrDefault(tool.id(), tool.id());
            return newId.equals(tool.id()) ? tool
                    : ToolExecutionResultMessage.from(newId, tool.toolName(), tool.text());
        }
        return msg;
    }

    private ChatResponse rewriteToolCallIds(ChatResponse response) {
        if (!response.aiMessage().hasToolExecutionRequests()) return response;

        // Build mapping for this response's tool calls
        Map<String, String> idMapping = new HashMap<>();
        response.aiMessage().toolExecutionRequests().forEach(req -> {
            if (needsIdFix(req.id())) {
                idMapping.computeIfAbsent(req.id(), k -> generateNvidiaId());
            }
        });
        if (idMapping.isEmpty()) return response;

        List<ToolExecutionRequest> fixed = response.aiMessage().toolExecutionRequests()
                .stream().map(req -> {
                    String newId = idMapping.getOrDefault(req.id(), req.id());
                    return newId.equals(req.id()) ? req
                            : ToolExecutionRequest.builder()
                                .id(newId).name(req.name()).arguments(req.arguments())
                                .build();
                }).toList();

        return ChatResponse.builder()
                .aiMessage(AiMessage.from(fixed))
                .finishReason(response.finishReason())
                .tokenUsage(response.tokenUsage())
                .build();
    }

    // Exactly 9 alphanumeric chars — matches NVIDIA requirement
    private static String generateNvidiaId() {
        return UUID.randomUUID().toString()
                .replaceAll("-", "")
                .substring(0, 9);
    }
}

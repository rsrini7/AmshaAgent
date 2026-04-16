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

    private final ChatModel delegate;

    public NvidiaMessageSanitizer(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        List<ChatMessage> sanitized = sanitize(request.messages());

        ChatRequest.Builder builder = ChatRequest.builder().messages(sanitized);

        // §7.33: ChatRequest.Builder forbids setting both parameters and toolSpecifications.
        // We prioritize toolSpecifications to ensure the agent doesn't lose its core functionality.
        if (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty()) {
            builder.toolSpecifications(request.toolSpecifications());
        } else if (request.parameters() != null) {
            builder.parameters(request.parameters());
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

        List<SystemMessage> system = messages.stream()
                .filter(m -> m instanceof SystemMessage)
                .map(m -> (SystemMessage) m)
                .limit(1).toList();

        List<ChatMessage> nonSystem = messages.stream()
                .filter(m -> !(m instanceof SystemMessage))
                .toList();

        List<ChatMessage> rest = new ArrayList<>();
        for (int i = 0; i < nonSystem.size(); i++) {
            ChatMessage current = nonSystem.get(i);
            
            // §7.32: NVIDIA 'DEGRADED function' protection.
            // If an Assistant message has tool calls, it MUST be followed by ToolExecutionResultMessage(s).
            // If the very next message is NOT a tool result, this is an abandoned/leaked call
            // that will cause a 400 error. We drop it.
            if (current instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                boolean hasFollowingResult = (i + 1 < nonSystem.size()) 
                        && (nonSystem.get(i + 1) instanceof ToolExecutionResultMessage);
                if (!hasFollowingResult) {
                    continue; // Skip abandoned tool call
                }
            }
            
            rest.add(rewriteWithMapping(current, idMapping));
        }

        List<ChatMessage> result = new ArrayList<>(system);
        result.addAll(rest);
        return result;
    }

    private static final Pattern NVIDIA_ID_FORMAT = Pattern.compile("^[a-zA-Z0-9]{9}$");

    private boolean needsIdFix(String id) {
        // NVIDIA requires exactly 9 alphanumeric chars. 
        // UUIDs, 'call_X' or other formats trigger 'DEGRADED function' 400 errors.
        return id == null || !NVIDIA_ID_FORMAT.matcher(id).matches();
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
            // NVIDIA NIMs prefer explicit empty text over null when tool calls are present
            return AiMessage.aiMessage(ai.text() != null ? ai.text() : "", fixed);
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
                .aiMessage(AiMessage.aiMessage(
                    response.aiMessage().text() != null ? response.aiMessage().text() : "", 
                    fixed))
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

package com.arcclaw.core.routing;

import java.util.ArrayList;
import java.util.List;
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
        // 1. Extract system messages → keep only first, move to front
        List<ChatMessage> systemMsgs = messages.stream()
                .filter(m -> m instanceof SystemMessage)
                .limit(1)
                .toList();

        List<ChatMessage> nonSystem = messages.stream()
                .filter(m -> !(m instanceof SystemMessage))
                .toList();

        // 2. Fix tool call IDs in assistant messages and tool result messages
        List<ChatMessage> idFixed = nonSystem.stream()
                .map(this::fixMessageIds)
                .toList();

        List<ChatMessage> result = new ArrayList<>(systemMsgs);
        result.addAll(idFixed);
        return result;
    }

    private ChatMessage fixMessageIds(ChatMessage msg) {
        if (msg instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
            List<ToolExecutionRequest> fixed = ai.toolExecutionRequests().stream()
                    .map(req -> SHORT_CALL_ID.matcher(req.id()).matches()
                            ? ToolExecutionRequest.builder()
                                .id(generateNvidiaId())
                                .name(req.name())
                                .arguments(req.arguments())
                                .build()
                            : req)
                    .toList();
            return AiMessage.from(fixed);
        }
        if (msg instanceof ToolExecutionResultMessage tool
                && SHORT_CALL_ID.matcher(tool.id()).matches()) {
            return ToolExecutionResultMessage.from(
                    generateNvidiaId(), tool.toolName(), tool.text());
        }
        return msg;
    }

    private ChatResponse rewriteToolCallIds(ChatResponse response) {
        if (response.aiMessage().hasToolExecutionRequests()) {
            List<ToolExecutionRequest> fixed = response.aiMessage()
                    .toolExecutionRequests().stream()
                    .map(req -> SHORT_CALL_ID.matcher(req.id()).matches()
                            ? ToolExecutionRequest.builder()
                                .id(generateNvidiaId())
                                .name(req.name())
                                .arguments(req.arguments())
                                .build()
                            : req)
                    .toList();
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(fixed))
                    .finishReason(response.finishReason())
                    .tokenUsage(response.tokenUsage())
                    .build();
        }
        return response;
    }

    // Exactly 9 alphanumeric chars — matches NVIDIA requirement
    private static String generateNvidiaId() {
        return UUID.randomUUID().toString()
                .replaceAll("-", "")
                .substring(0, 9);
    }
}

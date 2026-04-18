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
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jboss.logging.Logger;

/**
 * Sanitizes messages for NVIDIA NIM compliance.
 * Rewrites tool-call IDs to 9-char alphanumeric and prunes orphans.
 * 
 * Note: Not ApplicationScoped as this is a decorator used by specific LLM adapters.
 */
public class NvidiaMessageSanitizer implements ChatModel {

    private static final Logger log = Logger.getLogger(NvidiaMessageSanitizer.class);

    private final ChatModel model;

    public NvidiaMessageSanitizer(ChatModel model) {
        this.model = model;
    }

    private static final Pattern BRACKET_TOOL_PATTERN = Pattern.compile("\\[[a-zA-Z0-9_]+\\(.*?\\)\\]");

    @Override
    public ChatResponse chat(ChatRequest request) {
        log.debugf("NvidiaMessageSanitizer: sanitizing request with %d messages", request.messages().size());
        if (model == null) throw new IllegalStateException("NvidiaMessageSanitizer: model is null");
        
        List<ChatMessage> originalMessages = request.messages();
        List<ChatMessage> sanitizedIn = sanitize(originalMessages);
        
        ChatRequest sanitizedRequest = ChatRequest.builder()
                .messages(sanitizedIn)
                .toolSpecifications(request.toolSpecifications())
                .parameters(request.parameters())
                .build();
                
        ChatResponse response = model.chat(sanitizedRequest);
        
        if (response.aiMessage() != null) {
             AiMessage sanitizedAi = sanitizeAiMessage(response.aiMessage(), new HashMap<>());
             return ChatResponse.builder()
                     .aiMessage(sanitizedAi)
                     .metadata(response.metadata())
                     .build();
        }
        
        return response;
    }

    public List<ChatMessage> sanitize(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return messages;

        List<ChatMessage> result = new ArrayList<>();
        Map<String, String> idMap = new HashMap<>();

        for (ChatMessage msg : messages) {
            if (msg instanceof AiMessage ai) {
                result.add(sanitizeAiMessage(ai, idMap));
            } else if (msg instanceof ToolExecutionResultMessage tool) {
                String newId = idMap.get(tool.id());
                if (newId != null) {
                    result.add(ToolExecutionResultMessage.from(newId, tool.toolName(), tool.text()));
                } else {
                    result.add(tool);
                }
            } else {
                result.add(msg);
            }
        }
        return result;
    }

    private AiMessage sanitizeAiMessage(AiMessage ai, Map<String, String> idMap) {
        String text = ai.text();
        if (text != null) {
            text = BRACKET_TOOL_PATTERN.matcher(text).replaceAll("").strip();
        }

        List<ToolExecutionRequest> requests = ai.toolExecutionRequests();
        if (requests == null || requests.isEmpty()) {
            return AiMessage.from(text);
        }

        List<ToolExecutionRequest> sanitizedRequests = new ArrayList<>();
        for (ToolExecutionRequest req : requests) {
            String oldId = req.id();
            String newId = isNvidiaCompliant(oldId) ? oldId : generateNvidiaId();
            idMap.put(oldId, newId);
            sanitizedRequests.add(ToolExecutionRequest.builder()
                    .id(newId)
                    .name(req.name())
                    .arguments(req.arguments())
                    .build());
        }

        return AiMessage.from(text, sanitizedRequests);
    }

    private boolean isNvidiaCompliant(String id) {
        return id != null && id.length() == 9 && id.matches("[a-zA-Z0-9]+");
    }

    private static String generateNvidiaId() {
        return UUID.randomUUID().toString()
                .replaceAll("-", "")
                .substring(0, 9);
    }
}

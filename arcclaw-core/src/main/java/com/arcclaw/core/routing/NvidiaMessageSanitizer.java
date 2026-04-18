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

    private static final Pattern BRACKET_TOOL_PATTERN =
        Pattern.compile("\\[[a-zA-Z0-9_]+[\\(\\s][^\\]]*\\]");

    @Override
    public ChatResponse chat(ChatRequest request) {
        log.debugf("NvidiaMessageSanitizer: sanitizing request with %d messages", request.messages().size());
        if (model == null) throw new IllegalStateException("NvidiaMessageSanitizer: model is null");
        
        List<ChatMessage> originalMessages = request.messages();
        List<ChatMessage> sanitizedIn = sanitize(originalMessages);
        
        // 🟢 FIX: Avoid setting both parameters and toolSpecifications if they might conflict.
        // LangChain4j builder is strict about this.
        ChatRequest.Builder builder = ChatRequest.builder()
                .messages(sanitizedIn);

        if (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty()) {
            builder.toolSpecifications(request.toolSpecifications());
        } else if (request.parameters() != null) {
            builder.parameters(request.parameters());
        }
                
        ChatResponse response = model.chat(builder.build());
        
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

        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof AiMessage ai) {
                // §7.32: NVIDIA 'DEGRADED function' protection.
                // If an Assistant message has tool calls, it MUST be followed by ToolExecutionResultMessage(s).
                // If the very next message is NOT a tool result, this is an abandoned/leaked call
                // that will cause a 400 error. We drop it.
                if (ai.hasToolExecutionRequests()) {
                    boolean hasFollowingResult = (i + 1 < messages.size()) 
                            && (messages.get(i + 1) instanceof ToolExecutionResultMessage);
                    if (!hasFollowingResult) {
                        log.warn("NvidiaMessageSanitizer: pruning orphaned tool call");
                        continue; // Skip abandoned tool call
                    }
                }
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

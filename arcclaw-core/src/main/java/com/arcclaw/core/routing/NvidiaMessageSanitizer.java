package com.arcclaw.core.routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.jboss.logging.Logger;

/**
 * Sanitizes messages for NVIDIA NIM compliance.
 * §7.32: Hardens history against "DEGRADED function" and 500 sequence errors.
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
        
        List<ChatMessage> sanitizedIn = sanitize(request.messages());
        
        ChatRequest.Builder builder = ChatRequest.builder()
                .messages(sanitizedIn);

        if (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty()) {
            builder.toolSpecifications(request.toolSpecifications());
        } else if (request.parameters() != null) {
            builder.parameters(request.parameters());
        }
                
        ChatResponse response = model.chat(builder.build());
        
        if (response.aiMessage() != null) {
             AiMessage sanitizedAi = internalSanitizeAiMessage(response.aiMessage(), new HashMap<>(), response.aiMessage().toolExecutionRequests());
             return ChatResponse.builder()
                     .aiMessage(sanitizedAi)
                     .metadata(response.metadata())
                     .build();
        }
        
        return response;
    }

    public List<ChatMessage> sanitize(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return messages;

        // Pass 1: Build a complete ID map for EVERY tool call and result in history.
        // Even if a call is orphaned, its ID must be compliant if we send it in a result.
        Map<String, String> idMap = new HashMap<>();
        Set<String> callIdsInHistory = new HashSet<>();
        Set<String> resultIdsInHistory = new HashSet<>();

        for (ChatMessage m : messages) {
            if (m instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                    String id = req.id();
                    if (id != null) {
                        callIdsInHistory.add(id);
                        if (!isNvidiaCompliant(id)) idMap.computeIfAbsent(id, k -> generateNvidiaId());
                    }
                }
            } else if (m instanceof ToolExecutionResultMessage result) {
                String id = result.id();
                if (id != null) {
                    resultIdsInHistory.add(id);
                    if (!isNvidiaCompliant(id)) idMap.computeIfAbsent(id, k -> generateNvidiaId());
                }
            }
        }

        // Bipartite Enforcement: NIM backend (500) fails if result exists without call.
        // We prune any result whose parent call is not in the history.
        Set<String> validBipartiteIds = new HashSet<>(callIdsInHistory);
        validBipartiteIds.retainAll(resultIdsInHistory);

        List<ChatMessage> result = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            
            // sequence guard: system hints following tool results must be merged into result text.
            if (msg instanceof SystemMessage sm && !result.isEmpty() 
                    && result.get(result.size() - 1) instanceof ToolExecutionResultMessage lastTool) {
                
                ToolExecutionResultMessage merged = ToolExecutionResultMessage.from(
                    lastTool.id(), 
                    lastTool.toolName(), 
                    lastTool.text() + "\n\n[System Hint: " + sm.text() + "]");
                
                result.set(result.size() - 1, merged);
                continue;
            }

            if (msg instanceof AiMessage ai) {
                if (ai.hasToolExecutionRequests()) {
                    // Only keep tool calls that have a corresponding result (NIM requirement)
                    List<ToolExecutionRequest> bipartite = ai.toolExecutionRequests().stream()
                            .filter(req -> validBipartiteIds.contains(req.id()))
                            .toList();
                    
                    if (bipartite.isEmpty() && ai.text() != null && !ai.text().isBlank()) {
                        result.add(AiMessage.from(stripBrackets(ai.text())));
                    } else if (!bipartite.isEmpty()) {
                        result.add(internalSanitizeAiMessage(ai, idMap, bipartite));
                    }
                } else {
                    result.add(msg);
                }
            } else if (msg instanceof ToolExecutionResultMessage tool) {
                // Prune results missing their call (NIM backend protection)
                if (validBipartiteIds.contains(tool.id())) {
                    String newId = idMap.getOrDefault(tool.id(), tool.id());
                    result.add(ToolExecutionResultMessage.from(newId, tool.toolName(), tool.text()));
                } else {
                    log.warnf("NvidiaMessageSanitizer: pruning orphaned tool result %s", tool.id());
                }
            } else {
                result.add(msg);
            }
        }
        return result;
    }

    private String stripBrackets(String text) {
        if (text == null) return null;
        return BRACKET_TOOL_PATTERN.matcher(text).replaceAll("").strip();
    }

    private AiMessage internalSanitizeAiMessage(AiMessage ai, Map<String, String> idMap, List<ToolExecutionRequest> requests) {
        String text = stripBrackets(ai.text());

        if (requests == null || requests.isEmpty()) {
            return AiMessage.from(text);
        }

        List<ToolExecutionRequest> sanitizedRequests = new ArrayList<>();
        for (ToolExecutionRequest req : requests) {
            String oldId = req.id();
            String newId = idMap.getOrDefault(oldId, oldId);
            if (!isNvidiaCompliant(newId)) newId = generateNvidiaId();
            
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

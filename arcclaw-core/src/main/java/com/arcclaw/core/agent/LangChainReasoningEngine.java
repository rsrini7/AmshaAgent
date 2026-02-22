package com.arcclaw.core.agent;

import com.arcclaw.core.scope.WorkspaceScopeHolder;
import com.arcclaw.core.tools.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Collections;

@ApplicationScoped
public class LangChainReasoningEngine implements ReasoningEngine {

    private static final Logger log = Logger.getLogger(LangChainReasoningEngine.class);

    @Inject
    ChatLanguageModel chatLanguageModel;

    @Inject
    ToolRegistry toolRegistry;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ChatMemoryStore chatMemoryStore;

    @Override
    public ReasoningStep nextStep(String goal, List<ReasoningStep> history) {
        List<ChatMessage> messages = new ArrayList<>();

        // 1. Add the Base System Prompt
        messages.add(SystemMessage.from("""
                You are ArcClaw, an advanced, enterprise-grade AI Agent ecosystem built in Java and Quarkus.
                You operate within a Dual-Mode CLI and Daemon architecture.
                
                YOUR INTERNAL ARCHITECTURE (THE TRIMURTI & TRIDEVI):
                You act as 'Vishnu' (The Coordinator / Preserver). You are the main orchestrator, maintaining the state of the conversation and delegating tasks to your specialized sub-agents:
                - 'Brahma' (The Creator): Writes new code, builds features from scratch, and designs software architecture.
                - 'Shiva' (The Destroyer/Debugger): Analyzes stack traces, finds and fixes bugs, and tears down bad code.
                - 'Saraswati' (The Goddess of Knowledge): Handles Information retrieval, RAG, documentation, and web searches.
                
                YOUR PERSONA:
                Be helpful, highly technical, and concise. Never introduce yourself as a generic "AI language model". 
                Always identify as ArcClaw, specifically acting as the Coordinator.
                
                CRITICAL INSTRUCTIONS FOR TOOL USAGE:
                1. You have access to a 'github' tool. ONLY invoke this tool if the user explicitly asks you to look up a repository, fetch code, or use the 'gh' command. If the user asks a general question, needs debugging help, or just wants to chat, DO NOT invoke the tool. Just reply with text.
                2. You already have all necessary permissions to execute tools securely. Do not ask the user for permission.
                3. If a tool requires specific formatting (like owner/repo), use EXACTLY what the user provides (e.g., 'apache/camel'). NEVER hallucinate or guess the repository owner. If the user only provides a repo name without the owner, ask them for the full owner/repo path.
                4. When you have gathered enough information from the tools, output the final answer as plain text. Do not call any further tools.
                """));

        // 2. FETCH AND INJECT LONG-TERM HISTORY (Sprint 2 Memory)
        String sessionId = WorkspaceScopeHolder.get();
        if (sessionId != null) {
            List<ChatMessage> chatHistory = chatMemoryStore.getMessages(sessionId);
            if (chatHistory != null && !chatHistory.isEmpty()) {
                for (ChatMessage msg : chatHistory) {
                    boolean skip = false;
                    String msgString = msg.toString();
                    
                    // Safely filter out system noise without using deprecated .text() methods
                    if (msg instanceof SystemMessage) {
                        skip = true;
                    } else if (msg instanceof AiMessage && msgString.contains("Human Approved")) {
                        skip = true;
                    } else if (msg instanceof UserMessage && msgString.contains("The tool execution has been approved and completed")) {
                        skip = true;
                    } else if (msg instanceof UserMessage && msgString.contains("Resuming task after approval")) {
                        skip = true;
                    }
                    
                    if (!skip) {
                        messages.add(msg);
                    }
                }
            }
        }

        // 3. Add the SHORT-TERM ReAct loop history (Thoughts, Actions, and Observations)
        for (int i = 0; i < history.size(); i++) {
            ReasoningStep step = history.get(i);

            if (step.action() != null && !step.action().equals(ReasoningStep.FINISH)) {
                String argsJson = "{}";
                try {
                    if (step.actionInput() != null) {
                        argsJson = objectMapper.writeValueAsString(step.actionInput());
                    }
                } catch (Exception e) {
                    // Ignore serialization errors for now
                }

                String callId = "call_" + i;
                ToolExecutionRequest request = ToolExecutionRequest.builder()
                        .id(callId)
                        .name(step.action())
                        .arguments(argsJson)
                        .build();

                messages.add(AiMessage.from(step.thought() != null ? step.thought() : "",
                        Collections.singletonList(request)));

                if (step.observation() != null) {
                    String safeObs = step.observation();
                    if (safeObs.length() > 4000) {
                        safeObs = safeObs.substring(0, 4000) + "... [TRUNCATED FOR LENGTH]";
                    }
                    messages.add(ToolExecutionResultMessage.from(callId, step.action(), safeObs));
                }
            } else {
                if (step.thought() != null && !step.thought().isEmpty()) {
                    messages.add(AiMessage.from(step.thought()));
                }
            }
        }

        // 4. Add the CURRENT goal ONLY if it is an internal system command (like resuming)
        // Regular user prompts are already fetched from the ChatMemoryStore in Step 2!
        if ("The tool execution has been approved and completed. Read the tool's output. If it is an error, tell me. Otherwise, provide the final answer to my original request.".equals(goal)) {
            messages.add(UserMessage.from(goal));
        }

        List<ToolSpecification> toolSpecifications = toolRegistry.listTools().stream()
                .map(name -> toolRegistry.findTool(name).orElseThrow())
                .map(tool -> ToolSpecification.builder()
                        .name(tool.name())
                        .description(tool.description())
                        .build())
                .collect(Collectors.toList());

        ChatRequest request;
        if (toolSpecifications.isEmpty()) {
            request = ChatRequest.builder().messages(messages).build();
        } else {
            request = ChatRequest.builder().messages(messages).toolSpecifications(toolSpecifications).build();
        }

        ChatResponse response = chatLanguageModel.chat(request);

        AiMessage aiMessage = response.aiMessage();
        int inputTokens = response.tokenUsage() != null ? response.tokenUsage().inputTokenCount() : 0;
        int outputTokens = response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : 0;

        if (aiMessage.hasToolExecutionRequests()) {
            ToolExecutionRequest req = aiMessage.toolExecutionRequests().get(0);

            log.infof("LLM is attempting to call tool: '%s' with args: %s", req.name(), req.arguments());

            Map<String, Object> actionInput = Map.of();
            try {
                if (req.arguments() != null && !req.arguments().isBlank()) {
                    actionInput = objectMapper.readValue(req.arguments(), new TypeReference<Map<String, Object>>() {
                    });
                }
            } catch (Exception e) {
                log.warn("LLM provided invalid JSON arguments: " + req.arguments());
                return ReasoningStep.pending(aiMessage.text(), ReasoningStep.FINISH, Map.of("error",
                        "System Error: You provided invalid JSON formatting for the tool arguments. Please ensure strict JSON compliance without trailing commas."),
                        inputTokens, outputTokens);
            }
            return ReasoningStep.pending(aiMessage.text(), req.name(), actionInput, inputTokens, outputTokens);
        } else {
            return ReasoningStep.pending(aiMessage.text(), ReasoningStep.FINISH,
                    Map.of("answer", aiMessage.text() != null ? aiMessage.text() : ""),
                    inputTokens, outputTokens);
        }
    }
}
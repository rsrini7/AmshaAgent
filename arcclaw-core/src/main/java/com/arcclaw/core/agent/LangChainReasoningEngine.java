package com.arcclaw.core.agent;

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

    @Inject
    com.arcclaw.core.state.CompactionSummaryRepository compactionSummaryRepository;

    /**
     * Returns the strict tool binding constraint for the given agent role.
     * This is injected into the system prompt to prevent tool bias.
     */
    private String getRoleBinding(String role) {
        if (role == null) return "PERMITTED TOOLS: None";
        return switch (role.toLowerCase()) {
            // Coordinator routes user requests and may invoke github when explicitly asked
            case "coordinator", "vishnu" ->
                "PERMITTED TOOLS: github (ONLY when user explicitly uses words like " +
                "'fetch', 'gh', 'repository', 'repo', or 'github'. " +
                "For all other requests, output text only.)";
            // Researcher is the knowledge agent — github is its primary tool
            case "researcher", "saraswati" ->
                "PERMITTED TOOLS: github";
            // Coder generates code — never needs external data
            case "coder", "brahma" ->
                "PERMITTED TOOLS: None. You are a pure code generation engine. " +
                "Do not invoke any tools under any circumstances. Output code only.";
            // Debugger analyzes provided information — never fetches external data
            case "debugger", "shiva" ->
                "PERMITTED TOOLS: None. You are a pure analysis engine. " +
                "Analyze only the information provided. Do not invoke any tools.";
            default -> "PERMITTED TOOLS: None";
        };
    }

    @Override
    public ReasoningStep nextStep(String sessionId, String agentRole, String goal, List<ReasoningStep> history) {
        List<ChatMessage> messages = new ArrayList<>();

        // 1. Add the Base System Prompt
        String baseSystemPrompt = """
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
                2. You already have all necessary permissions to execute tools securely. Do not ask the user for permission.
                3. If a tool requires specific formatting (like owner/repo), use EXACTLY what the user provides (e.g., 'apache/camel'). NEVER hallucinate or guess the repository owner. If the user only provides a repo name without the owner, ask them for the full owner/repo path.
                4. When you have gathered enough information from the tools, output the final answer as plain text. Do not call any further tools.
                """;

        String strictBinding = String.format("""
                
                ════════════════════════════════════════
                STRICT TOOL BINDING CONTRACT
                ════════════════════════════════════════
                ACTIVE PERSONA: %s
                %s
                
                This is a HARD constraint. A tool call that violates the PERMITTED TOOLS 
                list above is a critical failure and will break the orchestration pipeline.
                When in doubt, output text only.
                ════════════════════════════════════════
                """, agentRole, getRoleBinding(agentRole));

        String finalSystemPrompt = baseSystemPrompt + strictBinding;
        messages.add(SystemMessage.from(finalSystemPrompt));

        // 2. FETCH AND INJECT LONG-TERM HISTORY (Sprint 2 Memory)
        if (sessionId != null) {
            compactionSummaryRepository.findBySessionId(sessionId).ifPresent(s -> {
                messages.add(SystemMessage.from("[PRIOR CONTEXT SUMMARY]\n" + s.summary));
            });
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
                        Map<String, Object> input = step.actionInput();
                        if (input.size() == 1 && input.containsKey("parameters") && input.get("parameters") instanceof String) {
                            argsJson = (String) input.get("parameters");
                        } else {
                            argsJson = objectMapper.writeValueAsString(input);
                        }
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
        if (goal != null && (goal.startsWith("The tool execution") || goal.contains("System: Your previous tool call"))) {
            messages.add(UserMessage.from(goal));
        }

        // 1. Determine which tools the current persona is allowed to see
        List<String> allowedTools = switch (agentRole.toLowerCase()) {
            case "coder", "brahma", "debugger", "shiva" -> List.of(); // NO tools in schema
            case "researcher", "saraswati", "coordinator", "vishnu" -> List.of("github");
            default -> List.of();
        };

        // 2. Dynamically filter + build specs with parameter schema
        List<ToolSpecification> toolSpecifications = toolRegistry.listTools().stream()
            .filter(allowedTools::contains)                             // Sprint 25A: Schema Jailbreak fix
            .map(name -> toolRegistry.findTool(name).orElseThrow())
            .map(tool -> ToolSpecification.builder()
                .name(tool.name())
                .description(tool.description())
                .parameters(tool.parametersSchema())                    // Sprint 25B: Fix null args
                .build())
            .collect(Collectors.toList());

        // 3. Build the request (If empty, LangChain4j omits the tools array entirely from the API call)
        ChatRequest request = toolSpecifications.isEmpty()
                ? ChatRequest.builder().messages(messages).build()
                : ChatRequest.builder().messages(messages).toolSpecifications(toolSpecifications).build();

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
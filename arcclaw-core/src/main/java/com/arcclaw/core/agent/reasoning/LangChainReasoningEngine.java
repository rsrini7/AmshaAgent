package com.arcclaw.core.agent.reasoning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.RoleRegistry;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.util.AgentErrorMessageUtil;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import com.arcclaw.core.governance.GovernanceLayer;
import com.arcclaw.core.observability.AgentOtelContext;
import io.opentelemetry.api.trace.Span;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class LangChainReasoningEngine implements ReasoningEngine {

    private static final Logger log = Logger.getLogger(LangChainReasoningEngine.class);

    private boolean isSchemaValidationError(String message) {
        if (message == null) return false;
        return message.contains("tool call validation failed")
                || message.contains("did not match schema")
                || message.contains("value must be one of")
                || message.contains("errors:");
    }

    @Inject
    ChatModel chatModel;

    @Inject
    ToolRegistry toolRegistry;

    @Inject
    RoleRegistry roleRegistry;

    @Inject
    SystemPromptBuilder promptBuilder;

    @Inject
    ChatHistoryAssembler historyAssembler;

    @Inject
    ReasoningStepParser stepParser;

    @Inject
    GovernanceLayer governanceLayer;

    @Inject
    AgentOtelContext otelContext;

    @Override
    public ReasoningStep nextStep(String sessionId, String agentRole, String goal, List<ReasoningStep> history) {
        // 1. Build system prompt
        String systemPrompt = promptBuilder.build(agentRole, sessionId, goal);

        // 2. Assemble chat history (WITHOUT SystemMessage)
        List<ChatMessage> assembled = historyAssembler.assemble(sessionId, goal, history);

        // 3. Prepend SystemMessage + build full message list
        List<ChatMessage> messages = new ArrayList<>();
        // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
        messages.add(SystemMessage.systemMessage(systemPrompt));
        messages.addAll(assembled);

        // 4. Build tool specifications
        Set<String> allowedTools = roleRegistry.permittedTools(AgentRoleEnum.fromString(agentRole));
        boolean isCronFired = sessionId != null && sessionId.startsWith("cron-");
        List<ToolSpecification> toolSpecifications = toolRegistry.listTools().stream()
                .filter(allowedTools::contains)
                .filter(name -> !isCronFired || (!name.equals(ToolCapability.SCHEDULE_TASK.toolName()) && !name.equals(ToolCapability.MANAGE_SCHEDULE.toolName())))
                .map(name -> toolRegistry.findTool(name).orElseThrow())
                .map(tool -> ToolSpecification.builder()
                        .name(tool.name())
                        .description(tool.description())
                        .parameters(tool.parametersSchema())
                        .build())
                .collect(Collectors.toList());

        // 5. Build ChatRequest and call LLM
        ChatRequest request = toolSpecifications.isEmpty()
                ? ChatRequest.builder().messages(messages).build()
                : ChatRequest.builder().messages(messages).toolSpecifications(toolSpecifications).build();

        ChatResponse response;
        try {
            response = chatModel.chat(request);
        } catch (InvalidRequestException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";

            // Schema validation error (e.g. Groq rejects bad parameter values).
            // Re-throw so AgentStateMachineRunner.processEngineStep handles it
            // via llmSchemaErrorCount + retryEngine — which already injects the
            // correct transient correction. Do NOT catch it here.
            if (isSchemaValidationError(msg)) {
                log.warnf("[LangChainReasoningEngine] Schema validation error — " +
                        "re-throwing to state machine for retry handling: %s", msg);
                throw e;
            }

            // Tool truly absent from request.tools (different condition).
            // Return an inline correction — this is the original intended path.
            if (msg.contains("was not in request.tools") || msg.contains("tool_use_failed")) {
                log.warnf("[LangChainReasoningEngine] LLM called unavailable tool — " +
                        "injecting inline correction: %s", msg);
                return ReasoningStep.pending(
                        "Attempted to call a tool not available in this context",
                        "OBSERVE",
                        Map.of(),
                        0, 0
                ).withObservation(
                        "That tool is not available in the current execution context. " +
                        "Use only the tools listed in PERMITTED TOOLS. " +
                        "If no tool is needed, output your answer directly and call FINISH."
                );
            }
            throw e;
        } catch (Exception e) {
            String clean = AgentErrorMessageUtil.sanitizeErrorMessage(e.getMessage());
            throw new RuntimeException(clean, e);
        }

        // 6. Parse response
        return stepParser.parse(sessionId, agentRole, response);
    }

    public String chatForStructuredOutput(String sessionId, String agentRole, String schemaPrompt) {
        Span span = otelContext.childSpan(sessionId, "structured_output_generation");
        try {
            // 1. Build system prompt with empty goal to avoid ReAct contamination
            String systemPrompt = promptBuilder.build(agentRole, sessionId, "");

            // 2. Build message list: SystemPrompt + UserMessage(schemaPrompt)
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.systemMessage(systemPrompt));
            messages.add(UserMessage.userMessage(schemaPrompt));

            // 3. Build ChatRequest and call LLM (JSON MODE is NOT set globally)
            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .build();

            ChatResponse response = chatModel.chat(request);

            // 4. Record consumption
            TokenUsage usage = response.tokenUsage();
            if (usage != null) {
                try {
                    governanceLayer.recordLlmCall(sessionId, usage.inputTokenCount(), usage.outputTokenCount());
                    otelContext.recordLlmCall(sessionId, usage.inputTokenCount(), usage.outputTokenCount());
                } catch (com.arcclaw.core.governance.BudgetWarningException w) {
                    log.warnf("Budget warning for session %s structured call: %s", sessionId, w.getMessage());
                    // LLM call succeeded — absorb soft warning and return normally
                }
            }

            return response.aiMessage().text();
        } catch (Exception e) {
            String clean = AgentErrorMessageUtil.sanitizeErrorMessage(e.getMessage());
            throw new RuntimeException("Structured output LLM call failed: " + clean, e);
        } finally {
            if (span != null) {
                span.end();
            }
        }
    }
}

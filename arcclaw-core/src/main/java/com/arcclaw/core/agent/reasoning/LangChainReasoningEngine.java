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
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class LangChainReasoningEngine implements ReasoningEngine {

    private static final Logger log = Logger.getLogger(LangChainReasoningEngine.class);

    @Inject
    ChatModel ChatModel;

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

    @Override
    public ReasoningStep nextStep(String sessionId, String agentRole, String goal, List<ReasoningStep> history) {
        // 1. Build system prompt
        String systemPrompt = promptBuilder.build(agentRole, sessionId);

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
            response = ChatModel.chat(request);
        } catch (InvalidRequestException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("was not in request.tools") || msg.contains("tool_use_failed")) {
                log.warnf("LLM called unavailable tool — injecting correction: %s", msg);
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
}

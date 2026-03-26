package com.arcclaw.core.agent.reasoning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.RoleRegistry;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.governance.BudgetWarningException;
import com.arcclaw.core.governance.GovernanceStrategy;
import com.arcclaw.core.observability.AgentOtelContext;
import com.arcclaw.core.tools.ToolRegistry;
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
import io.opentelemetry.api.trace.Span;
import org.jboss.logging.Logger;

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

    /**
     * Checks whether a message string signals a Groq rate-limit rejection.
     * Covers both TPM (tokens-per-minute) and TPD (tokens-per-day) variants.
     */
    private boolean isRateLimitError(String message) {
        if (message == null) return false;
        return message.contains("rate_limit_exceeded")
                || message.contains("tokens per minute")
                || message.contains("tokens per day")
                || message.contains("TPM")
                || message.contains("TPD")
                || message.contains("Rate limit");
    }

    /**
     * Walks the full exception cause chain looking for a rate-limit signal.
     * Returns the first message that contains a Groq rate-limit indicator,
     * or null if none is found.
     *
     * This is necessary because the Quarkus REST client sometimes wraps
     * the original HttpException inside a NumberFormatException when it fails
     * to parse the Retry-After header value (e.g. "570m") as a plain integer.
     * Without cause-chain inspection, the generic catch only sees
     * "For input string: \"570m\"" and loses the rate-limit context entirely.
     */
    private String findRateLimitMessage(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (isRateLimitError(t.getMessage())) return t.getMessage();
            t = t.getCause();
        }
        return null;
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
    GovernanceStrategy governanceLayer;

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
        messages.add(SystemMessage.systemMessage(systemPrompt));
        messages.addAll(assembled);

        // 4. Build tool specifications
        Set<String> allowedTools = roleRegistry.permittedTools(AgentRoleEnum.fromString(agentRole));
        
        // Extended: also treat goals with CRON: prefix as cron-fired even on REST sessions.
        // This ensures schedule_task is never available when the goal originated from the scheduler,
        // regardless of how the session was created.
        boolean isCronFired = (sessionId != null && sessionId.toLowerCase().startsWith("cron-"))
                || (goal != null && goal.toUpperCase().startsWith("CRON:"));
        
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

            if (isSchemaValidationError(msg)) {
                log.warnf("[LangChainReasoningEngine] Schema validation error — " +
                        "re-throwing to state machine for retry handling: %s", msg);
                throw e;
            }

            if (msg.contains("tool_use_failed") || msg.contains("was not in request.tools")) {

                // Distinguish prose-generation failure from nonexistent-tool failure.
                // Groq rejects raw markdown prose with tool_use_failed when tools are present.
                // The failed_generation field contains the prose — detect by checking it
                // looks like text (starts with a letter/markdown marker) rather than JSON.
                if (isProseGenerationFailure(msg)) {
                    String extractedProse = extractProseFromFailedGeneration(msg);
                    if (extractedProse != null && !extractedProse.isBlank()) {
                        log.infof("[LangChainReasoningEngine] Prose extracted directly from " +
                                "failed_generation — returning as FINISH (0 extra LLM calls)");
                        return ReasoningStep.pending(
                                "LLM generated valid prose — extracted from failed_generation",
                                "FINISH",
                                Map.of("answer", extractedProse),
                                0, 0
                        );
                    }
                    // Extraction failed — last-resort fallback: strip tools on retry by re-throwing
                    // as a schema error so the state machine injects a tool-free correction context
                    log.warnf("[LangChainReasoningEngine] Prose detected but extraction failed " +
                            "— falling back to correction");
                    return ReasoningStep.pending(
                            "Generated raw text instead of structured FINISH call",
                            "OBSERVE", Map.of(), 0, 0
                    ).withObservation(
                            "You must respond using FINISH[answer=<content>]. " +
                            "Do NOT output raw markdown or plain text outside of a FINISH call."
                    );
                }

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
            // Walk the full cause chain first — the Quarkus REST client can wrap the
            // original Groq HttpException inside a NumberFormatException when it fails
            // to parse the Retry-After header (e.g. "570m") as a plain integer.
            // Without this check, the generic path would surface "For input string: \"570m\""
            // to the user instead of the proper rate-limit message.
            String rateLimitMsg = findRateLimitMessage(e);
            if (rateLimitMsg != null) {
                log.warnf("[LangChainReasoningEngine] Rate limit detected in cause chain — " +
                        "surfacing clean message: %s", rateLimitMsg);
                String clean = AgentErrorMessageUtil.sanitizeErrorMessage(rateLimitMsg);
                throw new RuntimeException(clean, e);
            }

            String clean = AgentErrorMessageUtil.sanitizeErrorMessage(e.getMessage());
            throw new RuntimeException(clean, e);
        }

        // 6. Parse response
        return stepParser.parse(sessionId, agentRole, response);
    }

    private static final String FAILED_GEN_KEY = "\"failed_generation\":\"";

    private boolean isProseGenerationFailure(String message) {
        if (message == null) return false;
        int idx = message.indexOf(FAILED_GEN_KEY);   // ← finds actual JSON key, not the description
        if (idx == -1) return false;
        int start = idx + FAILED_GEN_KEY.length();   // first char of actual value
        if (start >= message.length()) return false;
        String snippet = message.substring(start)
            .replace("\\n", "\n").replace("\\\"", "\"").stripLeading();
        return !snippet.startsWith("{") && !snippet.startsWith("[");
    }

    private String extractProseFromFailedGeneration(String message) {
        if (message == null) return null;
        int idx = message.indexOf(FAILED_GEN_KEY);   // ← same fix
        if (idx == -1) return null;
        int start = idx + FAILED_GEN_KEY.length();   // first char of actual value
        // Read character-by-character until closing unescaped "
        StringBuilder sb = new StringBuilder();
        boolean escape = false;
        for (int i = start; i < message.length(); i++) {
            char c = message.charAt(i);
            if (escape) {
                if (c == 'n') sb.append('\n');
                else if (c == 't') sb.append('\t');
                else sb.append(c);
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                break;                               // end of JSON string value
            } else {
                sb.append(c);
            }
        }
        String result = sb.toString().strip();
        return result.isBlank() ? null : result;
    }

    public String chatForStructuredOutput(String sessionId, String agentRole, String schemaPrompt) {
        Span span = otelContext.childSpan(sessionId, "structured_output_generation");
        try {
            String systemPrompt = promptBuilder.build(agentRole, sessionId, "");

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.systemMessage(systemPrompt));
            messages.add(UserMessage.userMessage(schemaPrompt));

            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .build();

            ChatResponse response = chatModel.chat(request);

            TokenUsage usage = response.tokenUsage();
            if (usage != null) {
                try {
                    governanceLayer.recordLlmCall(sessionId, usage.inputTokenCount(), usage.outputTokenCount());
                    otelContext.recordLlmCall(sessionId, usage.inputTokenCount(), usage.outputTokenCount());
                } catch (BudgetWarningException w) {
                    log.warnf("Budget warning for session %s structured call: %s", sessionId, w.getMessage());
                    throw w;
                } catch (BudgetExceededException be) {
                    log.warnf("Budget exceeded in structured output for session %s: %s", sessionId, be.getMessage());
                    throw be;
                }
            }

            return response.aiMessage().text();
        } catch (BudgetWarningException | BudgetExceededException e) {
            throw e;
        } catch (Exception e) {
            // Same cause-chain check for structured output path
            String rateLimitMsg = findRateLimitMessage(e);
            if (rateLimitMsg != null) {
                log.warnf("[LangChainReasoningEngine] Rate limit in structured output — %s", rateLimitMsg);
                String clean = AgentErrorMessageUtil.sanitizeErrorMessage(rateLimitMsg);
                throw new RuntimeException("Structured output LLM call failed: " + clean, e);
            }
            String clean = AgentErrorMessageUtil.sanitizeErrorMessage(e.getMessage());
            throw new RuntimeException("Structured output LLM call failed: " + clean, e);
        } finally {
            if (span != null) {
                span.end();
            }
        }
    }
}

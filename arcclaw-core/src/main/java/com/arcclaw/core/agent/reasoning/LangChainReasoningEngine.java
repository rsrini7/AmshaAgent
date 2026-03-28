package com.arcclaw.core.agent.reasoning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.RoleRegistry;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.governance.BudgetWarningException;
import com.arcclaw.core.governance.GovernanceStrategy;
import com.arcclaw.core.governance.RateLimitTracker;
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
     */
    private String findRateLimitMessage(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (isRateLimitError(t.getMessage())) return t.getMessage();
            t = t.getCause();
        }
        return null;
    }

    @Inject ChatModel chatModel;
    @Inject ToolRegistry toolRegistry;
    @Inject RoleRegistry roleRegistry;
    @Inject SystemPromptBuilder promptBuilder;
    @Inject ChatHistoryAssembler historyAssembler;
    @Inject ReasoningStepParser stepParser;
    @Inject GovernanceStrategy governanceLayer;
    @Inject RateLimitTracker rateLimitTracker;
    @Inject AgentOtelContext otelContext;

    @Override
    public ReasoningStep nextStep(String sessionId, String agentRole, String goal, List<ReasoningStep> history) {
        String systemPrompt = promptBuilder.build(agentRole, sessionId, goal);

        List<ChatMessage> assembled = historyAssembler.assemble(sessionId, goal, history);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.systemMessage(systemPrompt));
        messages.addAll(assembled);

        Set<String> allowedTools = roleRegistry.permittedTools(AgentRoleEnum.fromString(agentRole));

        boolean isCronFired = (sessionId != null && sessionId.toLowerCase().startsWith("cron-"))
                || AgentSentinels.isCronGoal(goal);

        List<ToolSpecification> toolSpecifications = toolRegistry.listTools().stream()
                .filter(allowedTools::contains)
                .filter(name -> !isCronFired || (!name.equals(ToolCapability.SCHEDULE_TASK.toolName())
                        && !name.equals(ToolCapability.MANAGE_SCHEDULE.toolName())))
                .map(name -> toolRegistry.findTool(name).orElseThrow())
                .map(tool -> ToolSpecification.builder()
                        .name(tool.name())
                        .description(tool.description())
                        .parameters(tool.parametersSchema())
                        .build())
                .collect(Collectors.toList());

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

                if (isProseGenerationFailure(msg)) {
                    String extractedProse = extractProseFromFailedGeneration(msg);

                    // ── Guard: if failed_generation contains code fragments (partial/truncated
                    // tool-call or raw code without markdown fences), do NOT send it as prose.
                    // Instead, inject an OBSERVE step telling the LLM to shorten and retry
                    // via sandbox_exec so the approval flow is preserved.
                    if (extractedProse != null && looksLikeTruncatedOrPartialCode(extractedProse)) {
                        log.warnf("[LangChainReasoningEngine] Truncated/partial code detected in " +
                                "failed_generation for session %s — injecting size-constraint retry",
                                sessionId);
                        return ReasoningStep.pending(
                                "Response was truncated because the code was too long",
                                "OBSERVE", Map.of(), 0, 0
                        ).withObservation(
                                "Your previous response was cut off because the code was too large. " +
                                "Please write a SHORTER, SIMPLER version (max 30 lines). " +
                                "Then call sandbox_exec to execute it so the user can approve."
                        );
                    }

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
                    // Extraction failed — fallback: inject correction context
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
            String rateLimitMsg = findRateLimitMessage(e);
            if (rateLimitMsg != null) {
                log.warnf("[LangChainReasoningEngine] Rate limit detected in cause chain — " +
                        "surfacing clean message: %s", rateLimitMsg);
                rateLimitTracker.recordHit("primary");
                String clean = AgentErrorMessageUtil.sanitizeErrorMessage(rateLimitMsg);
                throw new RuntimeException(clean, e);
            }

            String clean = AgentErrorMessageUtil.sanitizeErrorMessage(e.getMessage());
            throw new RuntimeException(clean, e);
        }

        return stepParser.parse(sessionId, agentRole, response);
    }

    private static final String FAILED_GEN_KEY = "\"failed_generation\":\"";

    private boolean isProseGenerationFailure(String message) {
        if (message == null) return false;
        int idx = message.indexOf(FAILED_GEN_KEY);
        if (idx == -1) return false;
        int start = idx + FAILED_GEN_KEY.length();
        if (start >= message.length()) return false;
        String snippet = message.substring(start)
                .replace("\\n", "\n").replace("\\\"", "\"").stripLeading();
        return !snippet.startsWith("{") && !snippet.startsWith("[");
    }

    private String extractProseFromFailedGeneration(String message) {
        if (message == null) return null;
        int idx = message.indexOf(FAILED_GEN_KEY);
        if (idx == -1) return null;
        int start = idx + FAILED_GEN_KEY.length();
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
                break;
            } else {
                sb.append(c);
            }
        }
        String result = sb.toString().strip();
        return result.isBlank() ? null : result;
    }

    /**
     * Detects when failed_generation content is partial/truncated code rather than
     * legitimate prose. This happens when Groq truncates a large sandbox_exec tool
     * call mid-JSON — the code content spills into failed_generation without fences
     * or structure.
     *
     * Returning true causes the caller to inject a "shorten your code" OBSERVE step
     * instead of sending the raw fragments as a prose answer — preserving the approval
     * flow and preventing garbled output like "int i low - 1 for int j u003c high".
     */
    private boolean looksLikeTruncatedOrPartialCode(String prose) {
        if (prose == null || prose.isBlank()) return false;

        // Unescaped unicode: u003c is < — a clear sign of JSON-escaped code in failed_generation
        if (prose.contains("u003c") || prose.contains("u003e") || prose.contains("u0026")) return true;

        // Mid-statement fragments with no markdown fences
        boolean noFences = !prose.contains("```");
        if (!noFences) return false;   // has proper fences — legitimate formatted prose

        // Java/Go/Python/Bash code identifiers without markdown wrapper
        String lower = prose.toLowerCase();
        boolean hasCodeKeywords =
                lower.contains("public class") || lower.contains("public static void")
                || lower.contains("func main") || lower.contains("func ")
                || lower.contains("import java") || lower.contains("import \"fmt\"")
                || lower.contains("def ") || lower.contains("#!/bin/bash")
                || lower.contains("#!/usr/bin/env");

        // Prose is suspiciously short AND starts mid-sentence (truncated)
        boolean looksStartMidSentence = prose.length() < 200
                && Character.isLowerCase(prose.charAt(0))
                && !prose.trim().endsWith(".");

        return hasCodeKeywords || looksStartMidSentence;
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
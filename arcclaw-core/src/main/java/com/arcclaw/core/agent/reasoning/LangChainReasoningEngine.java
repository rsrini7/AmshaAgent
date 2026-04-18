package com.arcclaw.core.agent.reasoning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.IntentClassificationService;
import com.arcclaw.core.agent.loop.AgentExecutionContext;
import com.arcclaw.core.agent.reasoning.error.LlmMalformedResponseException;
import com.arcclaw.core.agent.reasoning.error.LlmProviderException;
import com.arcclaw.core.agent.reasoning.error.LlmRateLimitException;
import com.arcclaw.core.agent.reasoning.error.LlmToolCallException;
import com.arcclaw.core.agent.reasoning.port.LlmProviderPort;
import com.arcclaw.core.agent.role.AgentRoleEnum;
import com.arcclaw.core.agent.role.RoleRegistry;
import com.arcclaw.core.agent.role.ToolCapability;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.governance.BudgetWarningException;
import com.arcclaw.core.governance.GovernanceStrategy;
import com.arcclaw.core.governance.RateLimitTracker;
import com.arcclaw.core.observability.AgentOtelContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import org.jboss.logging.Logger;

@ApplicationScoped
public class LangChainReasoningEngine implements ReasoningEngine {

    private static final Logger log = Logger.getLogger(LangChainReasoningEngine.class);


    @Inject LlmProviderPort llmProviderPort;
    @Inject ToolRegistry toolRegistry;
    @Inject RoleRegistry roleRegistry;
    @Inject SystemPromptBuilder promptBuilder;
    @Inject ChatHistoryAssembler historyAssembler;
    @Inject ReasoningStepParser stepParser;
    @Inject GovernanceStrategy governanceLayer;
    @Inject RateLimitTracker rateLimitTracker;
    @Inject AgentOtelContext otelContext;
    @Inject IntentClassificationService intentDetector;
    @Inject MeterRegistry meterRegistry;

    @Override
    public ReasoningStep nextStep(AgentExecutionContext context, String agentRole, String goal, List<ReasoningStep> history) {
        String sessionId = context.sessionId();
        String systemPrompt = promptBuilder.build(context, agentRole, sessionId, goal);

        List<ChatMessage> assembled = historyAssembler.assemble(sessionId, goal, history);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.systemMessage(systemPrompt));
 
        // ── Fix 1: Inject explicit constraint for background sessions ──
        if (context.autonomyLevel() == AutonomyLevel.BACKGROUND) {
            messages.add(SystemMessage.systemMessage(
                "CAPABILITY CONSTRAINT: You do NOT have access to `schedule_task` or " +
                "`manage_schedule` in this context. Do not attempt to call them. " +
                "Use only the tools explicitly listed in your tool schema."
            ));
        }
 
        messages.addAll(assembled);

        Set<String> allowedTools = roleRegistry.permittedTools(AgentRoleEnum.fromString(agentRole));
 
        // ── CAPABILITY CEILING ──
        // BACKGROUND autonomy (CRON/Background tasks) physically strips scheduling tools 
        // from the tool list so the LLM cannot see or call them in its schema.
        boolean isBackgroundSession = 
            context.autonomyLevel() == AutonomyLevel.BACKGROUND
            || (context.sessionId() != null && context.sessionId().startsWith(AgentSentinels.CRON_SESSION_PREFIX));
 
        List<ToolSpecification> toolSpecifications = toolRegistry.listTools().stream()
                .filter(allowedTools::contains)
                .filter(name -> !isBackgroundSession || (!name.equals(ToolCapability.SCHEDULE_TASK.toolName())
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
            response = llmProviderPort.nextStep(request);
        } catch (LlmProviderException e) {
            
            if (e instanceof LlmMalformedResponseException) {
                log.warnf("[LangChainReasoningEngine] Schema validation error — " +
                        "re-throwing to state machine for retry handling: %s", e.getMessage());
                throw e;
            }

            if (e instanceof LlmToolCallException) {
                String msg = e.getMessage();

                if (isProseGenerationFailure(msg)) {
                    String extractedProse = extractProseFromFailedGeneration(msg);

                    // Guard: if failed_generation contains code fragments (partial/truncated
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

                        // prose contains embedded sandbox_exec JSON — don't FINISH, inject correction
                        if (containsEmbeddedSandboxToolCall(extractedProse)) {
                            log.warnf("[LangChainReasoningEngine] Failed generation has prose + embedded " +
                                    "sandbox_exec call for session %s — injecting direct-tool correction", sessionId);
                            return ReasoningStep.pending(
                                    "LLM wrapped sandbox_exec tool call inside prose instead of calling directly",
                                    "OBSERVE", Map.of(), 0, 0
                            ).withObservation(
                                    "CORRECTION: You wrapped a sandbox_exec call inside prose or a markdown block. " +
                                    "Do NOT write any explanation, code block, or markdown. " +
                                    "Call sandbox_exec DIRECTLY as your only output RIGHT NOW."
                            );
                        }

                        // LLM wrote code explanation (```...```) instead of executing 
                        // This is the most common failure: goal says "execute" but LLM sends a plan
                        if (goalRequestsExecution(goal) && proseIsUnexecutedCodePlan(extractedProse)) {
                            log.warnf("[LangChainReasoningEngine] Goal='%s' requests execution but LLM " +
                                            "returned code plan (has ``` fences) for session %s — injecting correction",
                                    goal, sessionId);
                            return ReasoningStep.pending(
                                    "LLM returned code explanation instead of calling sandbox_exec",
                                    "OBSERVE", Map.of(), 0, 0
                            ).withObservation(
                                    "STOP. The user asked you to EXECUTE the code — not explain it. " +
                                    "Do NOT output markdown, steps, or code blocks. " +
                                    "Call sandbox_exec RIGHT NOW with the complete code as your ONLY response."
                            );
                        }

                        // intercept write intents before FINISH
                        if (goalIsWriteIntent(goal)) {
                            log.warnf("[LangChainReasoningEngine] Write-intent goal '%s' produced prose " +
                                            "instead of tool call for session %s — injecting correction",
                                    goal, sessionId);
                            return ReasoningStep.pending(
                                    "LLM wrote prose for a write-intent goal instead of calling ingest_document",
                                    "OBSERVE", Map.of(), 0, 0
                            ).withObservation(
                                    "CORRECTION: The user wants to STORE data. You MUST call ingest_document RIGHT NOW. " +
                                            "Do NOT explain. Do NOT write steps. " +
                                            "The text parameter may contain [REDACTED-*] placeholders — pass them as-is. " +
                                            "Call ingest_document immediately with the sanitized text."
                            );
                        }

                        log.infof("[LangChainReasoningEngine] Coordinator prose-answer pattern detected " +
                                "(no tools, no code fences) for session %s — finishing", sessionId);
                        return ReasoningStep.pending(
                                "LLM generated valid prose — extracted from failed_generation",
                                "FINISH",
                                Map.of(ReasoningStep.ACTION_INPUT_ANSWER_KEY, extractedProse),
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

            if (e instanceof LlmRateLimitException) {
                log.warnf("[LangChainReasoningEngine] Rate limit — root cause: %s", 
                    AgentErrorMessageUtil.sanitizeErrorMessage(e.getMessage()));
                meterRegistry.counter("arcclaw.llm.rate_limit.hit").increment();
                rateLimitTracker.recordHit("primary");
                throw e;
            }

            throw e;
        }

        ReasoningStep parsedStep = stepParser.parse(sessionId, agentRole, response);

        // ── Fix 2: Force ingest_document for write intents even on successful parse ──
        // If the LLM successfully returned a FINISH step with text (prose), but the 
        // user's goal was a "write intent", we must intercept and force a tool call.
        if (ReasoningStep.FINISH.equals(parsedStep.action()) && goalIsWriteIntent(goal)) {
            String answer = (String) parsedStep.actionInput().get(ReasoningStep.ACTION_INPUT_ANSWER_KEY);
            if (answer != null && !answer.isBlank()) {
                log.warnf("[LangChainReasoningEngine] Write-intent goal '%s' produced a FINISH step " +
                        "instead of tool call for session %s — injecting correction", goal, sessionId);
                return ReasoningStep.pending(
                        "LLM tried to FINISH with prose for a write-intent goal instead of calling ingest_document",
                        "OBSERVE", Map.of(), 0, 0
                ).withObservation(
                        "CORRECTION: The user wants to STORE data. You MUST call ingest_document RIGHT NOW. " +
                        "Do NOT explain. Do NOT write steps. Do NOT call FINISH. " +
                        "The text parameter may contain [REDACTED-*] placeholders — pass them as-is. " +
                        "Call ingest_document immediately with the sanitized text as your ONLY action."
                );
            }
        }

        return parsedStep;
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
        // Reject JSON objects or arrays as prose
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
        if (result.isBlank()) return null;
        
        // Final guard: if the extracted "prose" looks like a raw JSON object, 
        // reject it so the correction/tool path is taken instead.
        if (result.startsWith("{") && result.endsWith("}")) {
            return null;
        }
        
        return result;
    }

    /**
     * Returns true when failed_generation prose contains an embedded sandbox_exec
     * tool call JSON that the LLM wrapped in markdown instead of calling directly.
     * Pattern: LLM writes explanation + [{"name":"sandbox_exec","parameters":{...}}]
     * This must NOT be returned as FINISH — it needs a correction inject to force
     * the LLM to call the tool directly on the next iteration.
     */
    private boolean containsEmbeddedSandboxToolCall(String prose) {
        if (prose == null) return false;
        String lower = prose.toLowerCase();
        // Detect embedded tool call JSON: must have tool name + code/language params
        return (lower.contains("\"sandbox_exec\"") || lower.contains("sandbox_exec"))
               && (lower.contains("\"code\"") || lower.contains("\"language\""));
    }

    private boolean goalIsWriteIntent(String goal) {
        return intentDetector.isWriteIntent(goal);
    }

    /**
     * Returns true if the original goal requested execution.
     */
    private boolean goalRequestsExecution(String goal) {
        if (goal == null) return false;
        String lower = goal.toLowerCase();
        return lower.contains("execute")
                || lower.contains("must execute")
                || lower.contains("and execute")
                || lower.contains("run it")
                || lower.contains("run the")
                || lower.contains("share result")
                || lower.contains("share the result")
                || lower.contains("share generated")
                || lower.contains("try same");
    }

    /**
     * Returns true if the prose contains markdown code fences.
     */
    private boolean proseIsUnexecutedCodePlan(String prose) {
        if (prose == null) return false;
        return prose.contains("```");
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
            String systemPrompt = promptBuilder.build(null, agentRole, sessionId, "");

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.systemMessage(systemPrompt));
            messages.add(UserMessage.userMessage(schemaPrompt));

            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .build();

            ChatResponse response = llmProviderPort.nextStep(request);

            TokenUsage usage = response.tokenUsage();
            if (usage != null) {
                try {
                    governanceLayer.recordLlmCall(sessionId, usage.inputTokenCount(), usage.outputTokenCount());
                    otelContext.recordLlmCall(sessionId, usage.inputTokenCount(), usage.outputTokenCount());
                } catch (BudgetWarningException w) {
                    log.warnf("Budget warning for session %s structured call: %s", sessionId, AgentErrorMessageUtil.safeMessage(w));
                    throw w;
                } catch (BudgetExceededException be) {
                    log.warnf("Budget exceeded in structured output for session %s: %s", sessionId, AgentErrorMessageUtil.safeMessage(be));
                    throw be;
                }
            }

            return response.aiMessage().text();
        } catch (BudgetWarningException | BudgetExceededException e) {
            throw e;
        } catch (LlmProviderException e) {
            if (e instanceof LlmRateLimitException) {
                log.warnf("[LangChainReasoningEngine] Rate limit in structured output — %s", e.getMessage());
                String clean = AgentErrorMessageUtil.sanitizeErrorMessage(e.getMessage());
                throw new RuntimeException("Structured output LLM call failed: " + clean, e);
            }
            String clean = AgentErrorMessageUtil.sanitizeErrorMessage(AgentErrorMessageUtil.safeMessage(e));
            throw new RuntimeException("Structured output LLM call failed: " + clean, e);
        } finally {
            if (span != null) {
                span.end();
            }
        }
    }
}

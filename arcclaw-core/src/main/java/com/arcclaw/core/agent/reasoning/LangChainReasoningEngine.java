package com.arcclaw.core.agent.reasoning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.security.AutonomyLevel;
import com.arcclaw.core.tools.ToolRegistry;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import com.arcclaw.core.util.TextPatternUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
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
    private static final Pattern FENCED_CODE_BLOCK_PATTERN =
            Pattern.compile("(?s)```([\\w+#-]*)\\R(.*?)```");


    @Inject LlmProviderPort llmProviderPort;
    @Inject ToolRegistry toolRegistry;
    @Inject RoleRegistry roleRegistry;
    @Inject SystemPromptBuilder promptBuilder;
    @Inject ChatHistoryAssembler historyAssembler;
    @Inject ReasoningStepParser stepParser;
    @Inject GovernanceStrategy governanceLayer;
    @Inject RateLimitTracker rateLimitTracker;
    @Inject AgentOtelContext otelContext;
    @Inject IntentClassificationService intentClassifier;
    @Inject MeterRegistry meterRegistry;
    @Inject ObjectMapper objectMapper;

    @Override
    public ReasoningStep nextStep(AgentExecutionContext context, String agentRole, String goal, List<ReasoningStep> history) {
        String sessionId = context.sessionId();

        // §7.46: Prune history to prevent unbounded growth across turns and retries.
        // 10 steps is enough to capture prior code and recent context.
        int MAX_CARRY_STEPS = 10;
        List<ReasoningStep> cappedHistory = (history != null && history.size() > MAX_CARRY_STEPS)
                ? history.subList(history.size() - MAX_CARRY_STEPS, history.size())
                : history;

        // 🟢 FIX 1: Fast-path execution from history (§7.46)
        // If the user asks to "execute" or "run" code that was just written in a prior turn,
        // bypass the LLM and issue the sandbox_exec tool call directly using the code
        // block found in history. This prevents model-side truncation errors.
        // Scoped by language (§Bug-1 fix) to allow Go execution after successful Java run.
        if (AgentRoleEnum.CODER.id().equals(agentRole) 
                && TextPatternUtils.goalRequestsExecution(goal)
                && !context.isForceFinishRetry()) {
            String targetLanguage = TextPatternUtils.goalMentionsLanguage(goal);
            CodeBlock priorCode = extractLatestCodeBlock(cappedHistory);

            // §Bug: Narrow generic "execute it" check to the latest block's language
            String languageForSuccessCheck = (targetLanguage == null && priorCode != null) 
                    ? priorCode.language() : targetLanguage;

            if (!historyAlreadyContainsAttemptForLanguage(history, ToolCapability.SANDBOX_EXEC.toolName(), languageForSuccessCheck)) {
                if (priorCode != null) {
                    // If target language is specified, we must match it. If not, we take the latest.
                    boolean languageMatches = targetLanguage == null || targetLanguage.equalsIgnoreCase(priorCode.language());

                    if (languageMatches) {
                        log.infof("[LangChainReasoningEngine] Fast-path: Reusing prior %s code block for execution goal in session %s",
                                priorCode.language(), sessionId);
                        
                        return ReasoningStep.fastPath(
                                "Fast-path execution from history.",
                                ToolCapability.SANDBOX_EXEC.toolName(),
                                Map.of("code", priorCode.code(), "language", priorCode.language())
                        );
                    }
                }
            }
        }

        String systemPrompt = promptBuilder.build(context, agentRole, sessionId, goal);

        List<ChatMessage> assembled = historyAssembler.assemble(sessionId, goal, cappedHistory);

        // §Bug-3 fix: Enforce a message-count cap to prevent fallback model trigger.
        // 12-15 messages is enough for turn-to-turn context while staying within 
        // the primary model's reliable reasoning window.
        int MAX_ASSEMBLED_MESSAGES = 15;
        if (assembled.size() > MAX_ASSEMBLED_MESSAGES) {
            log.debugf("[LangChainReasoningEngine] Pruning assembled history for session %s from %d to %d messages.",
                    sessionId, assembled.size(), MAX_ASSEMBLED_MESSAGES);
            assembled = assembled.subList(assembled.size() - MAX_ASSEMBLED_MESSAGES, assembled.size());
        }

        // § Resilience: Extract the compacted summary from history to merge into the main SystemPrompt.
        // This ensures only ONE SystemMessage is sent (for adapter compatibility) and that
        // the summary survives the 'isSimpleChitchat' history stripping path.
        String summaryContent = assembled.stream()
                .filter(m -> m instanceof SystemMessage sm && sm.text().contains(AgentSentinels.COMPACTED_SUMMARY_SENTINEL))
                .map(m -> ((SystemMessage) m).text())
                .findFirst()
                .orElse("");

        List<ChatMessage> filteredAssembled = assembled.stream()
                .filter(m -> !(m instanceof SystemMessage sm && sm.text().contains(AgentSentinels.COMPACTED_SUMMARY_SENTINEL)))
                .toList();

        boolean isWriteIntent = intentClassifier != null && intentClassifier.isWriteIntent(goal);
        boolean isSimpleChitchat = intentClassifier != null && intentClassifier.isSimpleConversational(goal);
        boolean isHistoryQuery = intentClassifier != null && intentClassifier.isHistoryQuery(goal);
        
        // ── Fix: Mutual exclusion for functional roles and write intents ──
        // If the user wants to store data, retrieve history, or if the agent is in a 
        // functional role (coder/researcher/debugger), it's NOT simple chitchat.
        if (isWriteIntent || isHistoryQuery 
                || AgentRoleEnum.CODER.id().equalsIgnoreCase(agentRole)
                || AgentRoleEnum.RESEARCHER.id().equalsIgnoreCase(agentRole)
                || AgentRoleEnum.DEBUGGER.id().equalsIgnoreCase(agentRole)) {
            isSimpleChitchat = false;
        }
        
        final boolean finalIsSimpleChitchat = isSimpleChitchat;
        final boolean finalIsHistoryQuery = isHistoryQuery;

        String finalSystemPrompt = summaryContent.isEmpty() ? systemPrompt
                : summaryContent + "\n\n" + systemPrompt;

        // ── Fix: Write-intent correction override in system prompt ──
        // Injected into system prompt on retry to force compliance from weaker fallback models.
        if (isWriteIntent && context.isForceFinishRetry()) {
            finalSystemPrompt = "CRITICAL OVERRIDE: The user wants to STORE data. " +
                "You MUST call ingest_document RIGHT NOW as your ONLY action. " +
                "Use the user's original message as the 'text' parameter. " +
                "Do NOT call FINISH. Do NOT explain.\n\n" + finalSystemPrompt;
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.systemMessage(finalSystemPrompt));
 
        // ── Fix 1: Inject explicit constraint for background sessions ──
        if (context.autonomyLevel() == AutonomyLevel.BACKGROUND) {
            // Note: If multiple SystemMessages are forbidden, we may need to merge this as well.
            // However, most adapters allow a few SystemMessages as long as they are at the top.
            // If it crashes, this should be moved into 'finalSystemPrompt'.
            messages.add(SystemMessage.systemMessage(
                "CAPABILITY CONSTRAINT: You do NOT have access to `schedule_task` or " +
                "`manage_schedule` in this context. Do not attempt to call them. " +
                "Use only the tools explicitly listed in your tool schema."
            ));
        }
 
        List<ChatMessage> historyToInclude = filteredAssembled;
        if (finalIsSimpleChitchat || context.isForceFinishRetry()) {
            log.debugf("[LangChainReasoningEngine] %s detected for session %s — scrubbing tool history to prevent pattern mimicry",
                    finalIsSimpleChitchat ? "Simple chitchat" : "Force finish retry", sessionId);
            
            historyToInclude = filteredAssembled.stream()
                .filter(m -> !finalIsSimpleChitchat || m.type() != ChatMessageType.TOOL_EXECUTION_RESULT)
                .map(m -> {
                    if (m instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                        if (!finalIsSimpleChitchat) return m; // Preserve tool calls if not chitchat (i.e. force finish retry)
                        // If the AI message has text, keep ONLY the text to preserve conversational context.
                        // If it has NO text (pure tool call), filter it out.
                        return (ai.text() != null && !ai.text().isBlank()) 
                                ? AiMessage.aiMessage(ai.text()) : null;
                    }
                    return m;
                })
                .filter(Objects::nonNull)
                .toList();
        }

        if (finalIsSimpleChitchat && !finalIsHistoryQuery) {
            // Force direct answer by stripping ALL prior turns (§ recommendation)
            // prevents pattern-matching hallucinations from conversation history.
            // finalSystemPrompt already contains the summary, so it's preserved here!
            messages.add(UserMessage.userMessage(goal));
        } else {
            messages.addAll(historyToInclude);
            
            // ── Fix 3: Force finish retry constraint ──
            if (context.isForceFinishRetry()) {
                messages.add(UserMessage.userMessage(
                    "Your previous response contained raw JSON or tool-call syntax. " +
                    "Answer in plain prose only. All tools have already returned their results " +
                    "— do NOT call any more tools. Summarize the findings and call FINISH."
                ));
            }
        }

        if (log.isDebugEnabled()) {
            long totalContentChars = messages.stream()
                    .mapToLong(m -> m.toString().length())
                    .sum();
            log.debugf("[LangChainReasoningEngine] Assembled %d messages for turn (system=%d, history=%d). " +
                       "System prompt: %d chars. Total messages content: %d chars (~%d tokens)",
                    messages.size(),
                    messages.stream().filter(m -> m instanceof SystemMessage).count(),
                    assembled.size(),
                    systemPrompt.length(),
                    totalContentChars,
                    totalContentChars / 4);
        }

        Set<String> allowedTools = roleRegistry.permittedTools(AgentRoleEnum.fromString(agentRole));
 
        // ── CAPABILITY CEILING ──
        // BACKGROUND autonomy (CRON/Background tasks) physically strips scheduling tools 
        // from the tool list so the LLM cannot see or call them in its schema.
        boolean isBackgroundSession = 
            context.autonomyLevel() == AutonomyLevel.BACKGROUND
            || (context.sessionId() != null && context.sessionId().startsWith(AgentSentinels.CRON_SESSION_PREFIX));
 
        List<ToolSpecification> toolSpecifications;
        if (finalIsSimpleChitchat || context.isForceFinishRetry()) {
            log.debugf("[LangChainReasoningEngine] %s detected for session %s — stripping all tools from request",
                    finalIsSimpleChitchat ? "Simple chitchat" : "Force finish retry", sessionId);
            toolSpecifications = List.of();
        } else {
            toolSpecifications = toolRegistry.listTools().stream()
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
        }

        ChatRequest request = toolSpecifications.isEmpty()
                ? ChatRequest.builder().messages(messages).build()
                : ChatRequest.builder().messages(messages).toolSpecifications(toolSpecifications).build();

        ChatResponse response;
        try {
            boolean isCompact = finalIsSimpleChitchat || context.isForceFinishRetry();

            response = ScopedValue.where(WorkspaceContext.IS_WRITE_INTENT, isWriteIntent)
                    .where(WorkspaceContext.IS_COMPACT, isCompact)
                    .call(() -> llmProviderPort.nextStep(request));
        } catch (Throwable e) {
            // ScopedValue.call propagates the exception type. 
            // If the provider port threw LlmProviderException, it's here directly.
            // If for some reason it was wrapped in a generic Exception, unwrap once.
            Throwable effective = (e.getClass() == Exception.class && e.getCause() != null) ? e.getCause() : e;
            
            if (effective instanceof LlmMalformedResponseException ex) {
                log.warnf("[LangChainReasoningEngine] Schema validation error — " +
                        "re-throwing to state machine for retry handling: %s", ex.getMessage());
                throw ex;
            }

            if (effective instanceof LlmToolCallException ex) {
                String msg = ex.getMessage();

                if (isProseGenerationFailure(msg)) {
                    String extractedProse = extractProseFromFailedGeneration(msg);

                    // 🟢 FIX: Implement a truncation counter to force a FINISH exit after repeated failures.
                    // This prevents exhausting the budget on redundant retries of oversized code.
                    int truncationRetryCount = countTruncationAttempts(history);

                    // If the truncated generation contains a sandbox_exec call, 
                    // OR if we've already tried and failed twice, extract whatever we have and FINISH.
                    if (extractedProse != null && (containsEmbeddedSandboxToolCall(extractedProse) || truncationRetryCount >= 2)) {
                        String partialCode = extractPartialCodeFromTruncatedJson(extractedProse);
                        // Fallback for raw source code (not JSON wrapped)
                        if (partialCode == null && truncationRetryCount >= 2) {
                            partialCode = extractedProse.strip();
                        }

                        if (partialCode != null && !partialCode.isBlank()) {
                            log.infof("[LangChainReasoningEngine] Recovered partial code from truncation (attempt %d) for session %s", 
                                    truncationRetryCount + 1, sessionId);
                            String lang = TextPatternUtils.inferLanguage(partialCode);
                            String markdown = String.format("```%s\n%s\n```", lang != null ? lang : "", partialCode);
                            return ReasoningStep.pending(
                                    "Extracted code after repeated truncation attempts.",
                                    ReasoningStep.FINISH,
                                    Map.of(ReasoningStep.ACTION_INPUT_ANSWER_KEY, markdown),
                                    0, 0
                            );
                        }
                    }

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
                                "STOP. Do NOT attempt to call sandbox_exec directly with this code. " +
                                "Instead, provide the code using a FINISH answer first, " +
                                "then wait for the user to request execution."
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
                        if (TextPatternUtils.goalRequestsExecution(goal) && proseIsUnexecutedCodePlan(extractedProse)) {
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

                        // intercept write intents before FINISH (only if not already done)
                        if (goalIsWriteIntent(goal) && !historyAlreadyContains(history, ToolCapability.INGEST_DOCUMENT.toolName())) {
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

                        // §7.31: Coordinator fast-path — if model returns prose instead of tool call,
                        // and it's NOT a write-intent, extract it as a valid answer.
                        if (AgentRoleEnum.COORDINATOR.id().equals(agentRole)) {
                            log.infof("[LangChainReasoningEngine] Coordinator prose-answer pattern detected " +
                                    "(no tools, no code fences) for session %s — finishing", sessionId);
                            return ReasoningStep.pending(
                                    "LLM generated valid prose — extracted from failed_generation",
                                    "FINISH",
                                    Map.of(ReasoningStep.ACTION_INPUT_ANSWER_KEY, extractedProse),
                                    0, 0
                            );
                        }

                        // §7.40: Coder hard-correction — if model returns prose for an execution goal,
                        // force it back into tool-calling mode immediately.
                        if (AgentRoleEnum.CODER.id().equals(agentRole) && TextPatternUtils.goalRequestsExecution(goal)) {
                            log.warnf("[LangChainReasoningEngine] Coder produced prose for execution goal '%s' " +
                                    "in session %s — injecting hard-tool correction", goal, sessionId);
                            return ReasoningStep.pending(
                                    "Coder returned text instead of calling sandbox_exec",
                                    "OBSERVE", Map.of(), 0, 0
                            ).withObservation(
                                    "STOP. You must EXECUTE code to fulfill this request. " +
                                    "Do NOT write text. Do NOT explain. Do NOT use markdown. " +
                                    "Call sandbox_exec RIGHT NOW as your ONLY output."
                            );
                        }
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
        if (ReasoningStep.FINISH.equals(parsedStep.action()) && goalIsWriteIntent(goal) 
                && !historyAlreadyContains(history, ToolCapability.INGEST_DOCUMENT.toolName())) {
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
    private record CodeBlock(String language, String code) {}

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
        // try to extract the "answer" field if it's a FINISH-like structure
        if (result.startsWith("{") && result.endsWith("}")) {
            try {
                JsonNode node = objectMapper.readTree(result);
                JsonNode answerNode = node.get("answer");
                if (answerNode != null && !answerNode.isNull()) return answerNode.asText();

                JsonNode paramsNode = node.get("parameters");
                if (paramsNode != null && !paramsNode.isNull()) {
                    JsonNode pAnswerNode = paramsNode.get("answer");
                    if (pAnswerNode != null && !pAnswerNode.isNull()) return pAnswerNode.asText();
                }
            } catch (Exception e) {
                // ignore and return null to trigger correction path
            }
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
        return (lower.contains("\"sandbox_exec\"") || lower.contains(ToolCapability.SANDBOX_EXEC.toolName()))
               && (lower.contains("\"code\"") || lower.contains("\"language\""));
    }

    private boolean goalIsWriteIntent(String goal) {
        return intentClassifier.isWriteIntent(goal);
    }

    /**
     * Extracts partial code from a truncated JSON string where "code" was mid-generation.
     * Searches for the start of the "code" value and grabs everything up to the current end.
     */
    private String extractPartialCodeFromTruncatedJson(String json) {
        if (json == null) return null;
        int codeIdx = json.indexOf("\"code\"");
        if (codeIdx == -1) return null;
        
        int startQuote = json.indexOf("\"", json.indexOf(":", codeIdx) + 1);
        if (startQuote == -1) return null;
        
        String partial = json.substring(startQuote + 1).strip();
        
        // 🟢 FIX: Correct unescaping order (\\ first) to preserve sequences like \n
        return partial
                .replace("\\\\", "\\")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\t", "\t");
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

    private boolean historyAlreadyContainsAttemptForLanguage(List<ReasoningStep> history, String toolName, String language) {
        if (history == null) return false;
        return history.stream().anyMatch(step -> {
            if (!toolName.equals(step.action()) || step.observation() == null || step.observation().isBlank()) {
                return false;
            }
            if (language == null) return true; // Generic check requested
            
            Object langParam = step.actionInput() != null ? step.actionInput().get("language") : null;
            return language.equalsIgnoreCase(String.valueOf(langParam));
        });
    }

    private boolean historyAlreadyContains(List<ReasoningStep> history, String toolName) {
        if (history == null) return false;
        return history.stream().anyMatch(step ->
                toolName.equals(step.action()) && step.observation() != null && !step.observation().isBlank()
        );
    }

    private int countTruncationAttempts(List<ReasoningStep> history) {
        if (history == null) return 0;
        return (int) history.stream()
                .filter(step -> ReasoningStep.OBSERVE.equals(step.action())
                        && step.observation() != null
                        && step.observation().contains("was cut off because the code was too large"))
                .count();
    }

    private CodeBlock extractLatestCodeBlock(List<ReasoningStep> history) {
        if (history == null || history.isEmpty()) return null;
        for (int i = history.size() - 1; i >= 0; i--) {
            ReasoningStep step = history.get(i);
            CodeBlock block = extractCodeBlockFromText(extractCandidateText(step));
            if (block != null) return block;
        }
        return null;
    }

    private String extractCandidateText(ReasoningStep step) {
        if (step == null) return null;
        if (step.actionInput() != null) {
            Object answer = step.actionInput().get(ReasoningStep.ACTION_INPUT_ANSWER_KEY);
            if (answer instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        if (step.thought() != null && !step.thought().isBlank()) {
            return step.thought();
        }
        return step.observation();
    }

    private CodeBlock extractCodeBlockFromText(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher matcher = FENCED_CODE_BLOCK_PATTERN.matcher(text);
        CodeBlock last = null;
        while (matcher.find()) {
            String rawSpecifier = matcher.group(1);
            String language = normalizeExecutionLanguage(rawSpecifier);
            String code = matcher.group(2) != null ? matcher.group(2).trim() : "";
            
            // §7.46: Only infer language if the specifier was genuinely absent or blank.
            // If a specifier was present but unrecognized (e.g. ```rust), we keep it null
            // to avoid mis-detecting it as another language via content scanning.
            if (language == null && (rawSpecifier == null || rawSpecifier.isBlank()) && !code.isBlank()) {
                language = TextPatternUtils.inferLanguage(code);
            }

            if (language != null && !code.isBlank()) {
                last = new CodeBlock(language, code);
            }
        }
        return last;
    }

    private String normalizeExecutionLanguage(String rawLanguage) {
        if (rawLanguage == null) return null;
        String language = rawLanguage.trim().toLowerCase();
        return switch (language) {
            case "python3" -> "python";
            case "sh" -> "bash";
            case "javascript", "js" -> "node";
            case "java", "python", "bash", "node", "go" -> language;
            default -> null;
        };
    }
}

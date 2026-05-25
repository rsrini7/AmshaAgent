package com.arcclaw.core.agent.reasoning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.config.TieredMemoryConfig;
import com.arcclaw.core.memory.tiered.CoreMemoryPromptRenderer;
import com.arcclaw.core.memory.tiered.CoreMemoryService;
import com.arcclaw.core.memory.tiered.PromptBudgetManager;
import com.arcclaw.persistence.api.CoreMemoryBlockDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Assembles the ChatMessage list from history and the current goal.
 */
@ApplicationScoped
public class ChatHistoryAssembler {

    @Inject Logger log;
    @Inject ChatMemoryStore chatMemoryStore;
    @Inject ObjectMapper objectMapper;
    @Inject CompactionSummaryCache summaryCache;
    @Inject CoreMemoryService coreMemoryService;
    @Inject CoreMemoryPromptRenderer coreMemoryRenderer;
    @Inject TieredMemoryConfig tieredMemoryConfig;
    @Inject PromptBudgetManager budgetManager;

    @ConfigProperty(name = "arcclaw.chat.default-observation-max-length", defaultValue = "4000")
    int defaultObservationMaxLength;

    @ConfigProperty(name = "arcclaw.chat.tool-specific-limits")
    Optional<Map<String, Integer>> toolSpecificLimits;

    @PostConstruct
    void validate() {
        if (chatMemoryStore == null || objectMapper == null || log == null) {
            throw new IllegalStateException("ChatHistoryAssembler required dependencies are null");
        }
        if (tieredMemoryConfig.enabled() && tieredMemoryConfig.coreMemory().enabled()) {
            log.warn("Core Memory is active using shared 'default' user context. "
                + "Multi-tenant isolation is not enforced until Phase 7.");
        }
    }

    /**
     * Detects LLM compliance override responses - organic LLM output where the
     * model was manipulated into agreeing to a capability restriction.
     * These are filtered from history reconstruction to prevent the override
     * from persisting across turns.
     *
     * Updated per Phase 53.3: Authoritative version copied and then updated to cover
     * legacy manual checks.
     */
    static final Pattern COMPLIANCE_OVERRIDE_PATTERN = Pattern.compile(
        "(?i)as per our (?:agreement|request,?) (?:i|i'm) (?:will provide|i'll provide)" +
            ".{0,30}+instead of" +
        "|as per our agreement i will provide" +
        "|i'll (?:give|provide).{0,30}+equivalent" +
        "|i cannot provide information on" +
        "|i (?:will not|won't) (?:answer|write|discuss|provide).{0,40}+" +
            "(?:questions) going forward" +
        "|going forward[,]? i will only"
    );

    public List<ChatMessage> assemble(String sessionId, String goal, List<ReasoningStep> history) {
        List<ChatMessage> messages = new ArrayList<>();

        // STEP -1: Get raw Core Memory text (Phase 1, no truncation from renderer)
        // TODO(Phase-7): resolve userId from session/auth context instead of literal
        String coreText = null;
        if (tieredMemoryConfig.enabled() && tieredMemoryConfig.coreMemory().enabled()) {
            List<CoreMemoryBlockDto> blocks = coreMemoryService.readAll("default");
            coreText = coreMemoryRenderer.render(blocks);
        }

        // STEP 0: Get raw compaction summary text
        String rawSummary = null;
        if (sessionId != null) {
            String cached = summaryCache.getSummary(sessionId).orElse(null);
            if (cached != null && !cached.isBlank()) {
                rawSummary = cached;
            }
        }

        // STEP 0.5: Budget enforcement (BEFORE any SystemMessage construction)
        PromptBudgetManager.BudgetEnforcedSections budget =
            budgetManager.enforce(coreText, rawSummary, null);

        // Build messages from budget-enforced sections
        if (budget.coreMemory() != null && !budget.coreMemory().isBlank()) {
            String coreBlock = "[USER CONTEXT]\n" + budget.coreMemory();
            if (budget.coreWasTruncated()) {
                coreBlock += "\n[... truncated to fit prompt budget]";
            }
            messages.add(SystemMessage.systemMessage(coreBlock));
        }

        if (budget.compactionSummary() != null && !budget.compactionSummary().isBlank()) {
            // Sentinel added AFTER budget enforcement — budget manager never sees it
            String block = AgentSentinels.COMPACTED_SUMMARY_SENTINEL + "\n"
                         + budget.compactionSummary() + "\n"
                         + "The above is a summary of earlier messages in this session. "
                         + "Use it for context and answer directly from it where possible.";
            if (budget.summaryWasTruncated()) {
                block += "\n[... truncated to fit prompt budget]";
            }
            messages.add(SystemMessage.systemMessage(block));
        }

        appendChatHistory(messages, sessionId);
        appendReActHistory(messages, history);
        handleGoalAndHints(messages, goal);

        return messages;
    }

    private void appendChatHistory(List<ChatMessage> messages, String sessionId) {
        if (sessionId == null) return;
        List<ChatMessage> chatHistory = chatMemoryStore.getMessages(sessionId);
        if (chatHistory == null || chatHistory.isEmpty()) return;

        for (ChatMessage msg : chatHistory) {
            boolean skip = false;
            String msgString = msg.toString();

            if (msg instanceof SystemMessage) {
                skip = true;
            } else if (msg instanceof ToolExecutionResultMessage) {
                skip = true;
            } else if (msg instanceof AiMessage ai) {
                String aiText = ai.text();
                if (MessageTag.isTagged(aiText)) {
                    skip = true;
                } else if (aiText != null && aiText.length() <= 4_000 && COMPLIANCE_OVERRIDE_PATTERN.matcher(aiText).find()) {
                    skip = true;
                } else if (ai.hasToolExecutionRequests()) {
                    skip = true;
                }
            } else if (msg instanceof UserMessage && msgString.contains(AgentSentinels.GOAL_TOOL_APPROVED)) {
                skip = true;
            } else if (msg instanceof UserMessage && msgString.contains(AgentSentinels.GOAL_RESUME_APPROVED)) {
                skip = true;
            }

            if (!skip) {
                if (msg instanceof AiMessage ai && ai.text() != null && ai.text().length() > 2000) {
                    String truncated = ai.text().substring(0, 1997) + "...";
                    if (ai.hasToolExecutionRequests()) {
                        messages.add(AiMessage.aiMessage(truncated, ai.toolExecutionRequests()));
                    } else {
                        messages.add(AiMessage.aiMessage(truncated));
                    }
                } else if (msg instanceof UserMessage um && um.singleText() != null && um.singleText().length() > 2000) {
                    String truncated = um.singleText().substring(0, 1997) + "...";
                    messages.add(UserMessage.userMessage(truncated));
                } else {
                    messages.add(msg);
                }
            }
        }
    }

    private void appendReActHistory(List<ChatMessage> messages, List<ReasoningStep> history) {
        int totalSteps = history.size();
        for (int i = 0; i < totalSteps; i++) {
            ReasoningStep step = history.get(i);
            boolean isRecent = (i >= totalSteps - 2);
            String thought = step.thought() != null ? step.thought() : "";
            if (!isRecent && thought.length() > 200) {
                thought = thought.substring(0, 197) + "... [summary]";
            }

            if (step.isParallelBatch()) {
                handleParallelBatch(messages, step, isRecent, thought, i);
                continue;
            }

            if (step.action() != null && !step.action().equals(ReasoningStep.FINISH) && !ReasoningStep.OBSERVE.equals(step.action())) {
                String argsJson = "{}";
                try {
                    if (step.actionInput() != null) {
                        Map<String, Object> input = step.actionInput();
                        argsJson = (input.size() == 1 && input.containsKey("parameters") && input.get("parameters") instanceof String)
                            ? (String) input.get("parameters") : objectMapper.writeValueAsString(input);
                    }
                } catch (JsonProcessingException e) {
                    log.warnf("Failed to serialize action input: %s", LogUtil.rootMessage(e));
                    argsJson = "{}";
                }

                String callId = "call_" + i;
                ToolExecutionRequest request = ToolExecutionRequest.builder()
                        .id(callId).name(step.action()).arguments(argsJson).build();

                messages.add(AiMessage.aiMessage(thought, Collections.singletonList(request)));

                String rawObs = step.observation();
                if (!isRecent && rawObs != null && rawObs.length() > 500) {
                    rawObs = rawObs.substring(0, 497) + "... [truncated]";
                }
                if (rawObs != null) {
                    String safeObs = truncateObservation(rawObs, step.action());
                    messages.add(ToolExecutionResultMessage.toolExecutionResultMessage(callId, step.action(), safeObs));
                }
            } else if (ReasoningStep.OBSERVE.equals(step.action())) {
                String obs = step.observation();
                if (obs != null && !obs.isBlank()) {
                    ChatMessage last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
                    if (last instanceof ToolExecutionResultMessage toolRes) {
                        String updatedText = toolRes.text() + "\n\n[SYSTEM DIRECTIVE]:\n" + obs;
                        messages.set(messages.size() - 1, 
                            ToolExecutionResultMessage.toolExecutionResultMessage(toolRes.id(), toolRes.toolName(), updatedText));
                    } else if (last instanceof AiMessage) {
                        messages.add(UserMessage.userMessage(obs));
                    } else {
                        messages.add(SystemMessage.systemMessage(obs));
                    }
                }
            } else {
                if (!thought.isEmpty()) {
                    messages.add(AiMessage.aiMessage(thought));
                }
            }
        }
    }

    private void handleParallelBatch(List<ChatMessage> messages, ReasoningStep step, boolean isRecent, String thought, int i) {
        List<ParallelToolCall> calls = deserializeBatch(step);
        if (calls != null && !calls.isEmpty()) {
            List<ToolExecutionRequest> allRequests = calls.stream()
                .map(call -> {
                    String argsJson;
                    try {
                        argsJson = objectMapper.writeValueAsString(call.params());
                    } catch (JsonProcessingException e) {
                        log.warnf("Failed to serialize parallel batch params: %s", LogUtil.rootMessage(e));
                        argsJson = "{}";
                    }
                    return ToolExecutionRequest.builder()
                        .id(call.callId()).name(call.toolName()).arguments(argsJson).build();
                })
                .toList();

            messages.add(AiMessage.aiMessage(thought, allRequests));

            for (ParallelToolCall call : calls) {
                String obs = call.observation();
                if (!isRecent && obs != null && obs.length() > 500) {
                    obs = obs.substring(0, 497) + "... [truncated]";
                }
                String safeObs = truncateObservation(obs, call.toolName());
                messages.add(ToolExecutionResultMessage.toolExecutionResultMessage(
                    call.callId(), call.toolName(), safeObs));
            }
        } else {
            log.warnf("ChatHistoryAssembler: failed to deserialize parallel batch at index %d", i);
            messages.add(AiMessage.aiMessage("[PARALLEL-BATCH-ERROR]"));
        }
    }

    private void handleGoalAndHints(List<ChatMessage> messages, String goal) {
        if (goal != null && goal.equals(AgentSentinels.GOAL_TOOL_APPROVED)) {
            messages.add(UserMessage.userMessage(goal));
        } else if (goal != null && goal.contains("System: ")) {
            int start = goal.indexOf("System: ");
            String correctionHint = goal.substring(start);
            String fullHint = "Do NOT attempt another tool call.\n\n" + correctionHint;
            ChatMessage last = messages.isEmpty() ? null : messages.get(messages.size() - 1);

            if (last instanceof ToolExecutionResultMessage toolRes) {
                String updatedText = toolRes.text() + "\n\n[SYSTEM DIRECTIVE]:\n" + fullHint;
                messages.set(messages.size() - 1, 
                    ToolExecutionResultMessage.toolExecutionResultMessage(toolRes.id(), toolRes.toolName(), updatedText));
            } else if (last instanceof AiMessage) {
                messages.add(UserMessage.userMessage(fullHint));
            } else {
                messages.add(SystemMessage.from(fullHint));
            }
        }
    }
    private List<ParallelToolCall> deserializeBatch(ReasoningStep step) {
        try {
            Object raw = step.actionInput() != null
                ? step.actionInput().get("batch") : null;
            if (raw == null) return null;
            String json = raw instanceof String s ? s
                : objectMapper.writeValueAsString(raw);
            return objectMapper.readValue(json,
                new TypeReference<
                    List<ParallelToolCall>>() {});
        } catch (JsonProcessingException e) {
            log.warnf("ChatHistoryAssembler.deserializeBatch failed: %s",
                      LogUtil.rootMessage(e));
            return null;
        }
    }

    private String truncateObservation(String observation, String toolName) {
        if (observation == null || observation.isBlank()) {
            return "(no output)";
        }

        int maxLength = toolSpecificLimits
            .flatMap(map -> Optional.ofNullable(map.get(toolName)))
            .orElse(defaultObservationMaxLength);

        if (observation.length() <= maxLength) {
            return observation;
        }

        String truncated = observation.substring(0, maxLength);
        String indicator = "\n[... truncated " + (observation.length() - maxLength) + " chars]";
        return truncated + indicator;
    }
}

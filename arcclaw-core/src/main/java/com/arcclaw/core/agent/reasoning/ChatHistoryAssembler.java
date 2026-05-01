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

import com.arcclaw.core.agent.AgentSentinels;
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

    @ConfigProperty(name = "arcclaw.chat.default-observation-max-length", defaultValue = "4000")
    int defaultObservationMaxLength;

    @ConfigProperty(name = "arcclaw.chat.tool-specific-limits")
    Optional<Map<String, Integer>> toolSpecificLimits;

    @PostConstruct
    void validate() {
        if (chatMemoryStore == null || objectMapper == null || log == null) {
            throw new IllegalStateException("ChatHistoryAssembler required dependencies are null");
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

        // STEP 0 - inject compacted summary (§ Resilience / Memory Re-capture)
        if (sessionId != null) {
            summaryCache.getSummary(sessionId).ifPresent(summary -> {
                if (!summary.isBlank()) {
                    String block = AgentSentinels.COMPACTED_SUMMARY_SENTINEL + "\n" 
                                 + summary + "\n"
                                 + "The above is a summary of earlier messages in this session. "
                                 + "Use it for context and answer directly from it where possible.";
                    messages.add(SystemMessage.systemMessage(block));
                }
            });
        }

        // STEP A - persistent chat history
        if (sessionId != null) {
            List<ChatMessage> chatHistory = chatMemoryStore.getMessages(sessionId);
            if (chatHistory != null && !chatHistory.isEmpty()) {
                for (ChatMessage msg : chatHistory) {
                    boolean skip = false;
                    String msgString = msg.toString();

                    if (msg instanceof SystemMessage) {
                        skip = true;
                    } else if (msg instanceof ToolExecutionResultMessage) {
                        // 🟢 FIX 5: Scrub tool results from persistent history. 
                        // Current turn tools are reconstructed from ReAct 'history' parameter.
                        // Persistent history tools are from PRIOR turns and cause hallucinations.
                        skip = true;
                    } else if (msg instanceof AiMessage ai) {
                        String aiText = ai.text();
                        if (MessageTag.isTagged(aiText)) {
                            skip = true;
                        } else if (aiText != null && aiText.length() <= 4_000 && COMPLIANCE_OVERRIDE_PATTERN.matcher(aiText).find()) {
                            skip = true;
                        } else if (ai.hasToolExecutionRequests()) {
                            // 🟢 FIX 1.1: Scrub tool calls from persistent history.
                            // If we kept the AiMessage but scrubbed the ToolExecutionResultMessage,
                            // the LLM would see an unfulfilled tool call and re-issue it.
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
        }

        // STEP B - ReAct loop steps
        int totalSteps = history.size();
        for (int i = 0; i < totalSteps; i++) {
            ReasoningStep step = history.get(i);
            
            // 🟢 TOKEN OPTIMIZATION: Only send full details for the last 2 steps.
            // Older steps get truncated thoughts and observations to save context space.
            // This prevents hitting low TPM limits (e.g. 30k on Groq Free/Tier1).
            boolean isRecent = (i >= totalSteps - 2);
            String thought = step.thought() != null ? step.thought() : "";
            if (!isRecent && thought.length() > 200) {
                thought = thought.substring(0, 197) + "... [summary]";
            }

            // NEW: Handle parallel batch step (§7.3)
            if (step.isParallelBatch()) {
                List<ParallelToolCall> calls = deserializeBatch(step);
                if (calls != null && !calls.isEmpty()) {
                    List<ToolExecutionRequest> allRequests = calls.stream()
                        .map(call -> {
                            String argsJson;
                            try {
                                argsJson = objectMapper.writeValueAsString(call.params());
                            } catch (Exception e) { argsJson = "{}"; }
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
                } catch (Exception e) { argsJson = "{}"; }

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
                        // 🟢 FIX: 'Unexpected role 'user' after role 'tool''
                        // Providers like OpenAI/OpenRouter reject User/System messages immediately
                        // following a Tool result before the AI has answered. 
                        // Instead of adding a new message, append the directive to the tool's output.
                        String updatedText = toolRes.text() + "\n\n[SYSTEM DIRECTIVE]:\n" + obs;
                        messages.set(messages.size() - 1, 
                            ToolExecutionResultMessage.toolExecutionResultMessage(toolRes.id(), toolRes.toolName(), updatedText));
                    } else if (last instanceof AiMessage) {
                        // Use UserMessage if last was AiMessage to "flip" the turn
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

        // STEP C - special goal injection or correction hints (§7.31 turn-flipping)
        if (goal != null && goal.equals(AgentSentinels.GOAL_TOOL_APPROVED)) {
            messages.add(UserMessage.userMessage(goal));
        } else if (goal != null && goal.contains("System: ")) {
            int start = goal.indexOf("System: ");
            String correctionHint = goal.substring(start);
            
            // §4.11: Always prefix with the hard stop so LLM cannot retry a raw tool call.
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

        return messages;
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
        } catch (Exception e) {
            log.warnf("ChatHistoryAssembler.deserializeBatch failed: %s",
                      e.getMessage());
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

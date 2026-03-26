package com.arcclaw.core.agent.reasoning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.arcclaw.core.agent.AgentSentinels;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

/**
 * Assembles the ChatMessage list from history and the current goal.
 */
@ApplicationScoped
public class ChatHistoryAssembler {

    @Inject
    Logger log;

    private final ChatMemoryStore chatMemoryStore;
    private final ObjectMapper objectMapper;

    protected ChatHistoryAssembler() {
        this.chatMemoryStore = null;
        this.objectMapper = null;
    }

    @Inject
    public ChatHistoryAssembler(ChatMemoryStore chatMemoryStore, ObjectMapper objectMapper) {
        this.chatMemoryStore = chatMemoryStore;
        this.objectMapper = objectMapper;
    }

    /**
     * Detects LLM compliance override responses — organic LLM output where the
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

        // STEP A — persistent chat history
        if (sessionId != null) {
            List<ChatMessage> chatHistory = chatMemoryStore.getMessages(sessionId);
            if (chatHistory != null && !chatHistory.isEmpty()) {
                for (ChatMessage msg : chatHistory) {
                    boolean skip = false;
                    String msgString = msg.toString();

                    if (msg instanceof SystemMessage) {
                        skip = true;
                    } else if (msg instanceof AiMessage ai) {
                        String aiText = ai.text();
                        if (MessageTag.isTagged(aiText)) {
                            skip = true;
                        } else if (aiText != null && aiText.length() <= 4_000 && COMPLIANCE_OVERRIDE_PATTERN.matcher(aiText).find()) {
                            skip = true;
                        }
                    } else if (msg instanceof UserMessage && msgString.contains(AgentSentinels.GOAL_TOOL_APPROVED)) {
                        skip = true;
                    } else if (msg instanceof UserMessage && msgString.contains(AgentSentinels.GOAL_RESUME_APPROVED)) {
                        skip = true;
                    }

                    if (!skip) {
                        messages.add(msg);
                    }
                }
            }
        }

        // STEP B — ReAct loop steps
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
                    argsJson = "{}";
                    log.warnf("[ChatHistoryAssembler] Failed to serialize tool args at step %d" +
                              " — using empty params. Cause: %s", i, e.getMessage());
                }

                String callId = "call_" + i;
                ToolExecutionRequest request = ToolExecutionRequest.builder()
                        .id(callId)
                        .name(step.action())
                        .arguments(argsJson)
                        .build();

                // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
                messages.add(AiMessage.aiMessage(step.thought() != null ? step.thought() : "",
                        Collections.singletonList(request)));

                if (step.observation() != null) {
                    String safeObs = step.observation();
                    if (safeObs.length() > 4000) {
                        safeObs = safeObs.substring(0, 4000) + "... [TRUNCATED FOR LENGTH]";
                    }
                    // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
                    messages.add(ToolExecutionResultMessage.toolExecutionResultMessage(callId, step.action(), safeObs));
                }
            } else {
                if (step.thought() != null && !step.thought().isEmpty()) {
                    // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
                    messages.add(AiMessage.aiMessage(step.thought()));
                }
            }
        }

        // STEP C — special goal injection
        if (goal != null && goal.equals(AgentSentinels.GOAL_TOOL_APPROVED)) {
            // LangChain4j 0.26.x→1.x migration: renamed .from() to factory method
            messages.add(UserMessage.userMessage(goal));
        } else if (goal != null && goal.contains("System: Your previous tool call")) {
            String correctionHint = goal.substring(goal.indexOf("System: Your previous tool call"));
            messages.add(SystemMessage.from(
                correctionHint +
                "\nIMPORTANT: Do NOT attempt another tool call. Output your final answer as plain text only."
            ));
        }

        return messages;
    }
}

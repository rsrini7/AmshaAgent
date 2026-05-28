package com.arcclaw.core.agent.loop;

import java.util.List;
import java.util.regex.Pattern;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.reasoning.ParallelToolCall;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.agent.role.ToolCapability;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

class HallucinationDetector {

    private static final Logger log = Logger.getLogger(HallucinationDetector.class);

    private static final Pattern HALLUCINATED_STORAGE_CLAIM = Pattern.compile(
            "(?i)\\b(?:I (?:have |'ve )?(?:stored|saved|remembered|noted|recorded|kept)|(?:your|the) (?:name|identity|interest|preference|information) (?:is|has been|was) (?:stored|saved|remembered|noted))\\b",
            Pattern.MULTILINE);

    private static final Pattern HALLUCINATED_SCHEDULE_CLAIM = Pattern.compile(
            "(?i)\\b(?:I (?:have |'ve )?(?:scheduled|set a (?:reminder|timer)|created a (?:cron|task))|task (?:is |has been )?scheduled|scheduled the task|cron job has been|will run (?:in|at|every)|remind you in|reminder has been set|timer has been set)\\b",
            Pattern.MULTILINE);

    private static final Pattern KNOWLEDGE_DISCLAIMER = Pattern.compile(
            "(?i)(?:I (?:do(?:n't| not) (?:have|know|possess|store|remember|recall)|(?:have|possess) (?:no|not)) (?:any )?(?:information|data|knowledge|record|detail|identity|name|preference)|(?:no|not? any) (?:information|data|knowledge|record) (?:about|regarding|on|of) (?:you|the user|their))",
            Pattern.MULTILINE);

    private final ObjectMapper objectMapper;

    HallucinationDetector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    FinishActionHandler.FinishOutcome checkHallucination(
            String sessionId, List<ReasoningStep> history, ReasoningStep step, String answer) {
        if (answer == null) return null;

        FinishActionHandler.FinishOutcome result;
        result = checkHallucinatedSchedule(sessionId, history, step, answer);
        if (result != null) return result;
        result = checkHallucinatedStorage(sessionId, history, step, answer);
        if (result != null) return result;
        result = checkKnowledgeDisclaimer(sessionId, history, step, answer);
        return result;
    }

    private FinishActionHandler.FinishOutcome checkHallucinatedSchedule(
            String sessionId, List<ReasoningStep> history, ReasoningStep step, String answer) {
        boolean hasScheduleClaim = answer.contains(AgentSentinels.SCHEDULE_SUCCESS_MESSAGE)
                || HALLUCINATED_SCHEDULE_CLAIM.matcher(answer).find();
        if (!hasScheduleClaim) return null;

        boolean isNegative = answer.toLowerCase().contains("not scheduled")
                || answer.toLowerCase().contains("cannot schedule")
                || answer.toLowerCase().contains("can't schedule")
                || answer.toLowerCase().contains("failed to schedule")
                || answer.toLowerCase().contains("unable to schedule");
        if (isNegative) return null;
        if (historyContainsSuccessfulSchedule(history)) return null;

        log.warnf(
                "FINISH-HANDLER: Hallucinated scheduling success detected for session %s. "
                        + "No 'schedule_task' call found in history. Answer: %s",
                sessionId, answer);
        ReasoningStep cleanStep = step.withObservation("Detected hallucinated scheduling success in FINISH answer");
        String hint = answer.contains(AgentSentinels.SCHEDULE_SUCCESS_MESSAGE)
                ? "System: STOP. You claimed '" + AgentSentinels.SCHEDULE_SUCCESS_MESSAGE
                        + "' but you did NOT call the schedule_task tool. You must invoke the schedule_task tool with "
                        + "the correct cron expression and goal BEFORE calling FINISH."
                : "System: STOP. You claimed to have scheduled/set a reminder but you did NOT call "
                        + "the schedule_task tool. You must invoke the schedule_task tool with "
                        + "the correct cron expression and goal BEFORE calling FINISH.";
        return new FinishActionHandler.FinishOutcome.Correction(cleanStep, hint);
    }

    private FinishActionHandler.FinishOutcome checkHallucinatedStorage(
            String sessionId, List<ReasoningStep> history, ReasoningStep step, String answer) {
        if (!HALLUCINATED_STORAGE_CLAIM.matcher(answer).find()) return null;
        if (historyContainsIngestDocument(history)) return null;

        log.warnf(
                "FINISH-HANDLER: Hallucinated memory storage claim detected for session %s. "
                        + "No 'ingest_document' call found in history.",
                sessionId);
        ReasoningStep cleanStep = step.withObservation("Detected hallucinated memory storage claim in FINISH answer");
        String hint = "System: STOP. You claimed to have stored/remembered user information but you did NOT call "
                + "the ingest_document tool. You CANNOT store information without calling ingest_document. "
                + "Either: (1) Call ingest_document NOW with the information, or (2) Answer honestly that "
                + "you have NOT stored anything. NEVER claim a tool action you did not perform.";
        return new FinishActionHandler.FinishOutcome.Correction(cleanStep, hint);
    }

    private FinishActionHandler.FinishOutcome checkKnowledgeDisclaimer(
            String sessionId, List<ReasoningStep> history, ReasoningStep step, String answer) {
        if (!KNOWLEDGE_DISCLAIMER.matcher(answer).find()) return null;
        String memoryResult = findMemorySearchResult(history);
        if (memoryResult == null) return null;

        log.warnf(
                "FINISH-HANDLER: Memory search synthesis failure detected for session %s. "
                        + "Memory search returned results but answer disclaims knowledge.",
                sessionId);
        ReasoningStep cleanStep = step.withObservation("Detected memory search synthesis failure");
        String hint = "System: STOP. You just searched your memory and found information about this user. "
                + "Your memory search tool returned these results:\n---\n"
                + memoryResult
                + "\n---\n" + "You MUST synthesize these results into a natural, conversational answer. "
                + "Do NOT say 'I don't have information' when your memory search just returned it. "
                + "Read the tool results above and answer the user directly.";
        return new FinishActionHandler.FinishOutcome.Correction(cleanStep, hint);
    }

    boolean historyContainsSuccessfulSchedule(List<ReasoningStep> history) {
        return history.stream().anyMatch(this::stepContainsSuccessfulSchedule);
    }

    private boolean historyContainsIngestDocument(List<ReasoningStep> history) {
        return history.stream()
                .anyMatch(step -> step instanceof ReasoningStep.ToolCallStep tc
                        && ToolCapability.INGEST_DOCUMENT.toolName().equals(tc.action())
                        && Boolean.TRUE.equals(tc.success()));
    }

    private String findMemorySearchResult(List<ReasoningStep> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ReasoningStep step = history.get(i);
            if (step instanceof ReasoningStep.ToolCallStep tc
                    && isMemorySearchTool(tc.action())
                    && Boolean.TRUE.equals(tc.success())
                    && tc.observation() != null
                    && !tc.observation().isBlank()) {
                return tc.observation();
            }
        }
        return null;
    }

    private static boolean isMemorySearchTool(String toolName) {
        return ToolCapability.RECALL_MEMORY_SEARCH.toolName().equals(toolName)
                || ToolCapability.ARCHIVAL_MEMORY_SEARCH.toolName().equals(toolName);
    }

    private boolean stepContainsSuccessfulSchedule(ReasoningStep step) {
        return switch (step) {
            case ReasoningStep.ToolCallStep toolCall ->
                ToolCapability.SCHEDULE_TASK.toolName().equals(toolCall.action())
                        && Boolean.TRUE.equals(toolCall.success());
            case ReasoningStep.ParallelStep parallel -> {
                Object batchRaw = parallel.actionInput().get("batch");
                if (!(batchRaw instanceof String batchJson) || batchJson.isBlank()) yield false;
                try {
                    List<ParallelToolCall> calls =
                            objectMapper.readValue(batchJson, new TypeReference<List<ParallelToolCall>>() {});
                    yield calls.stream()
                            .anyMatch(call ->
                                    ToolCapability.SCHEDULE_TASK.toolName().equals(call.toolName()) && call.success());
                } catch (Exception e) {
                    log.debugf("FINISH-HANDLER: failed to parse parallel batch: %s", LogUtil.rootMessage(e));
                    yield false;
                }
            }
            default -> false;
        };
    }
}

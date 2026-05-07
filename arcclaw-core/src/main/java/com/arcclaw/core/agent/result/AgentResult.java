package com.arcclaw.core.agent.result;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.arcclaw.core.agent.loop.AgentLoop;
import com.arcclaw.core.agent.reasoning.ReasoningStep;
import com.arcclaw.core.governance.ApprovalFrame;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.TemplateData;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jboss.logging.Logger;

/**
 * Result of an {@link AgentLoop} execution.
 */
public sealed interface AgentResult
        permits AgentResult.Finished,
                AgentResult.StructuredFinished,
                AgentResult.AwaitingApproval,
                AgentResult.AwaitingRetry,
                AgentResult.LoopExceeded,
                AgentResult.Failed {

    Logger log = Logger.getLogger(AgentResult.class);

    List<ReasoningStep> steps();
    int totalInputTokens();
    int totalOutputTokens();

    enum FailureCategory {
        COMPLIANCE_VIOLATION,
        SYSTEM_ERROR,
        BUDGET_EXCEEDED,
        SCHEMA_ERROR,
        USER_DENIED,
        RATE_LIMIT,
        SECURITY_POLICY
    }

    @TemplateData
    record Finished(
            String answer,
            List<ReasoningStep> steps,
            int totalInputTokens,
            int totalOutputTokens
    ) implements AgentResult {}

    @TemplateData
    record StructuredFinished(
            String answer,
            Object structured,
            List<ReasoningStep> steps,
            int totalInputTokens,
            int totalOutputTokens
    ) implements AgentResult {}

    @TemplateData
    record AwaitingApproval(
            ApprovalFrame frame,
            String sessionId,
            List<ReasoningStep> steps,
            int totalInputTokens,
            int totalOutputTokens
    ) implements AgentResult {
        
        @JsonIgnore
        public String toolName() { return frame.tool(); }

        @JsonIgnore
        public String paramsJson() {
            try {
                // Ideally we'd use a shared ObjectMapper, but for this DTO compatibility
                // we use a fresh one if not provided. In production, this DTO
                // should be refactored to NOT expose paramsJson.
                return new ObjectMapper().writeValueAsString(frame.params());
            } catch (Exception e) {
                log.debugf("Non-fatal: %s", ExceptionUtils.getRootCauseMessage(e));
                return "{}";
            }
        }
    }

    @TemplateData
    record LoopExceeded(
            int maxIterations,
            List<ReasoningStep> steps,
            int totalInputTokens,
            int totalOutputTokens
    ) implements AgentResult {}

    @TemplateData
    record AwaitingRetry(
            String sessionId,
            int retryAfterMs,
            List<ReasoningStep> steps,
            int totalInputTokens,
            int totalOutputTokens
    ) implements AgentResult {}

    @TemplateData
    record Failed(
            String reason,
            FailureCategory category,
            List<ReasoningStep> steps,
            int totalInputTokens,
            int totalOutputTokens
    ) implements AgentResult {}

    // Static factories mapping to the new types

    static AgentResult finished(String answer, List<ReasoningStep> steps) {
        return new Finished(answer, steps, sumIn(steps), sumOut(steps));
    }

    static AgentResult structuredFinished(String answer, Object structured, List<ReasoningStep> steps) {
        return new StructuredFinished(answer, structured, steps, sumIn(steps), sumOut(steps));
    }

    static AgentResult awaitingApproval(String toolName, String paramsJson, String sessionId, List<ReasoningStep> steps) {
        Map<String, Object> params = Collections.emptyMap();
        try {
            params = new ObjectMapper().readValue(paramsJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.debugf("Non-fatal: %s", ExceptionUtils.getRootCauseMessage(e));
        }
        return awaitingApproval(new ApprovalFrame(toolName, params, ApprovalFrame.RiskLevel.MEDIUM), sessionId, steps);
    }

    static AgentResult awaitingApproval(ApprovalFrame frame, String sessionId, List<ReasoningStep> steps) {
        return new AwaitingApproval(frame, sessionId, steps, sumIn(steps), sumOut(steps));
    }

    static AgentResult loopExceeded(int maxIterations, List<ReasoningStep> steps) {
        return new LoopExceeded(maxIterations, steps, sumIn(steps), sumOut(steps));
    }

    static AgentResult failed(String reason, FailureCategory category, List<ReasoningStep> steps) {
        return new Failed(reason, category, steps, sumIn(steps), sumOut(steps));
    }

    static AgentResult awaitingRetry(String sessionId, int retryAfterMs, List<ReasoningStep> steps) {
        return new AwaitingRetry(sessionId, retryAfterMs, steps, sumIn(steps), sumOut(steps));
    }

    private static int sumIn(List<ReasoningStep> steps) {
        if (steps == null) return 0;
        return steps.stream().mapToInt(ReasoningStep::inputTokens).sum();
    }

    private static int sumOut(List<ReasoningStep> steps) {
        if (steps == null) return 0;
        return steps.stream().mapToInt(ReasoningStep::outputTokens).sum();
    }
}

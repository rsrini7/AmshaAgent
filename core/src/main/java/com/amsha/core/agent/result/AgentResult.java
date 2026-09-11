package com.amsha.core.agent.result;

import java.util.List;
import java.util.Map;

import com.amsha.common.util.LogUtil;
import com.amsha.core.agent.loop.AgentLoop;
import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.governance.ApprovalFrame;
import com.amsha.core.governance.ApprovalParameterSanitizer;
import com.amsha.core.util.BatchDecisionUtil;
import com.amsha.security.api.RiskLevel;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

import io.quarkus.qute.TemplateData;

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
        SECURITY_POLICY,
        USER_ABORT
    }

    @TemplateData
    record Finished(String answer, List<ReasoningStep> steps, int totalInputTokens, int totalOutputTokens)
            implements AgentResult {}

    @TemplateData
    record StructuredFinished(
            String answer, Object structured, List<ReasoningStep> steps, int totalInputTokens, int totalOutputTokens)
            implements AgentResult {}

    @TemplateData
    record AwaitingApproval(
            ApprovalFrame frame,
            String sessionId,
            List<ReasoningStep> steps,
            int totalInputTokens,
            int totalOutputTokens)
            implements AgentResult {

        @JsonIgnore
        public String toolName() {
            return frame.tool();
        }

        /** Redacted parameters for external responses and approval-channel displays. */
        @JsonIgnore
        public String paramsJson() {
            return displayParamsJson();
        }

        /** Raw parameters retained for protected delegation/checkpoint transport only. */
        @JsonIgnore
        public String rawParamsJson() {
            try {
                return MAPPER.writeValueAsString(frame.params());
            } catch (Exception e) {
                log.debugf("Serialization fallback (non-fatal): %s", LogUtil.rootMessage(e));
                return "{}";
            }
        }

        /** Redacted parameters suitable for channels, REST responses, and UI rendering. */
        @JsonIgnore
        public String displayParamsJson() {
            return ApprovalParameterSanitizer.toJson(frame.params());
        }
    }

    @TemplateData
    record LoopExceeded(int maxIterations, List<ReasoningStep> steps, int totalInputTokens, int totalOutputTokens)
            implements AgentResult {}

    @TemplateData
    record AwaitingRetry(
            String sessionId, int retryAfterMs, List<ReasoningStep> steps, int totalInputTokens, int totalOutputTokens)
            implements AgentResult {}

    @TemplateData
    record Failed(
            String reason,
            FailureCategory category,
            List<ReasoningStep> steps,
            int totalInputTokens,
            int totalOutputTokens)
            implements AgentResult {}

    // Static factories mapping to the new types

    static AgentResult finished(String answer, List<ReasoningStep> steps) {
        return new Finished(answer, steps, sumIn(steps), sumOut(steps));
    }

    static AgentResult structuredFinished(String answer, Object structured, List<ReasoningStep> steps) {
        return new StructuredFinished(answer, structured, steps, sumIn(steps), sumOut(steps));
    }

    ObjectMapper MAPPER = new ObjectMapper();

    static AgentResult awaitingApproval(
            String toolName, String paramsJson, String sessionId, List<ReasoningStep> steps) {
        Map<String, Object> params = BatchDecisionUtil.parseBatchDisplayParams(paramsJson, MAPPER);
        return awaitingApproval(new ApprovalFrame(toolName, params, RiskLevel.MEDIUM), sessionId, steps);
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

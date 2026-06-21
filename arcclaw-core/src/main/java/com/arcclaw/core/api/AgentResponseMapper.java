package com.arcclaw.core.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.arcclaw.core.agent.AgentSentinels;
import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.api.dto.ArcClawErrorResponse;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceContext;
import com.arcclaw.core.state.CheckpointStatus;

import org.jboss.logging.Logger;

@ApplicationScoped
public class AgentResponseMapper {

    private static final Logger log = Logger.getLogger(AgentResponseMapper.class);

    private static final Pattern FAKE_CRON_PATTERN = Pattern.compile(
            "Task scheduled successfully.*ID:\\s*[a-f0-9-]{36}", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern REPEATING_UUID_PATTERN =
            Pattern.compile("([0-9a-f]{4,8})\\1.*\\1.*\\1", Pattern.CASE_INSENSITIVE);

    @Inject
    AuditService auditService;

    public record AgentResponse(String answer, int totalInputTokens, int totalOutputTokens) {}

    public record ApprovalRequiredResponse(String toolName, String paramsJson, String sessionId) {}

    public record RateLimitRetryResponse(long retryAfterMs, String sessionId) {}

    public record AgentErrorResponse(String reason, String code) {}

    public record MappedResult(Object entity, int suggestedHttpStatus) {}

    public MappedResult mapResult(AgentResult result, boolean isAsync) {
        return switch (result) {
            case AgentResult.Finished f -> {
                String answer = sanitizeAnswer(f.answer());
                AgentResponse entity = new AgentResponse(answer, f.totalInputTokens(), f.totalOutputTokens());
                yield new MappedResult(entity, isAsync ? 202 : 200);
            }
            case AgentResult.StructuredFinished sf -> {
                String answer = sanitizeAnswer(sf.answer());
                AgentResponse entity = new AgentResponse(answer, sf.totalInputTokens(), sf.totalOutputTokens());
                yield new MappedResult(entity, isAsync ? 202 : 200);
            }
            case AgentResult.AwaitingApproval a ->
                new MappedResult(new ApprovalRequiredResponse(a.toolName(), a.paramsJson(), a.sessionId()), 202);
            case AgentResult.AwaitingRetry r ->
                new MappedResult(new RateLimitRetryResponse(r.retryAfterMs(), r.sessionId()), 202);
            case AgentResult.LoopExceeded l ->
                new MappedResult(
                        new ArcClawErrorResponse(
                                "Iteration limit " + l.maxIterations() + " reached",
                                CheckpointStatus.LOOP_EXCEEDED.name()),
                        408);
            case AgentResult.Failed f -> mapFailedResponse(f);
        };
    }

    private String sanitizeAnswer(String answer) {
        if (answer == null) return null;

        String stripped = answer.strip();
        if (AgentSentinels.CONTEXT_RETRIEVED_ECHO_PATTERN.matcher(stripped).find()) {
            answer = AgentSentinels.CONTEXT_RETRIEVED_ECHO_PATTERN
                    .matcher(stripped)
                    .replaceFirst("")
                    .strip();
        }

        if (AgentSentinels.SYSTEM_PROMPT_ECHO_PATTERN.matcher(answer).find()) {
            log.warnf("AgentResponseMapper: stripping system prompt echo from answer");
            Matcher m = AgentSentinels.SYSTEM_PROMPT_ECHO_PATTERN.matcher(answer);
            if (m.find() && m.start() > 0) {
                String salvaged = answer.substring(0, m.start()).strip();
                if (!salvaged.isBlank()) {
                    answer = salvaged;
                } else {
                    answer = "\u26a0\ufe0f I wasn't able to generate a clean response. Please try again.";
                }
            } else {
                answer = "\u26a0\ufe0f I wasn't able to generate a clean response. Please try again.";
            }
        }

        boolean looksLikeCronConfirmation = FAKE_CRON_PATTERN.matcher(answer).find();
        boolean hasRepeatingUuid = REPEATING_UUID_PATTERN.matcher(answer).find();

        if (!looksLikeCronConfirmation && !hasRepeatingUuid) return answer;

        String sessionId = WorkspaceContext.SESSION_ID.isBound() ? WorkspaceContext.SESSION_ID.get() : null;

        if (sessionId == null) return answer;

        boolean realCronExists = auditService.hasEventInSession(sessionId, SecurityEventType.CRONCREATED);

        if (!realCronExists) {
            log.warnf("LLM hallucinated cron confirmation for session=%s - stripping", sessionId);
            return "I was unable to schedule the task. " + "Please provide a specific, self-contained goal.";
        }

        return answer;
    }

    private MappedResult mapFailedResponse(AgentResult.Failed f) {
        return switch (f.category()) {
            case COMPLIANCE_VIOLATION -> new MappedResult(new ArcClawErrorResponse(f.reason(), "COMPLIANCE"), 451);
            case BUDGET_EXCEEDED -> new MappedResult(new ArcClawErrorResponse(f.reason(), "BUDGET"), 429);
            case USER_DENIED -> new MappedResult(new ArcClawErrorResponse(f.reason(), "USER_DENIED"), 422);
            case SCHEMA_ERROR ->
                new MappedResult(
                        new ArcClawErrorResponse(
                                "The agent encountered a schema error and could not complete the request. Try rephrasing your goal.",
                                "SCHEMA"),
                        422);
            case SYSTEM_ERROR -> new MappedResult(new ArcClawErrorResponse(f.reason(), "SYSTEM"), 500);
            case RATE_LIMIT -> new MappedResult(new ArcClawErrorResponse(f.reason(), "RATE_LIMIT"), 429);
            case SECURITY_POLICY -> new MappedResult(new ArcClawErrorResponse(f.reason(), "SECURITY_POLICY"), 403);
            case USER_ABORT -> new MappedResult(new ArcClawErrorResponse("Session halted by user.", "HALTED"), 499);
        };
    }
}

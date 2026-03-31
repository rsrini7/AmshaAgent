package com.arcclaw.core.api;

import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import com.arcclaw.core.agent.result.AgentResult;
import com.arcclaw.core.audit.AuditService;
import com.arcclaw.core.audit.SecurityEventType;
import com.arcclaw.core.scope.WorkspaceContext;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AgentResponseMapper {

    private static final Logger log = Logger.getLogger(AgentResponseMapper.class);

    private static final Pattern FAKE_CRON_PATTERN = Pattern.compile(
            "Task scheduled successfully.*ID:\\s*[a-f0-9-]{36}",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern REPEATING_UUID_PATTERN = Pattern.compile(
            "([0-9a-f]{4,8})\\1.*\\1.*\\1",   // same segment repeated 3+ times
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern CONTEXT_BLOCK_PATTERN = Pattern.compile(
        "##\\s*Retrieved Context\\s*<context>.*?</context>\\s*",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

    @Inject
    AuditService auditService;

    public record AgentResponse(String answer, int totalInputTokens, int totalOutputTokens) {}
    public record ApprovalRequiredResponse(String toolName, String paramsJson, String sessionId) {}
    public record ErrorResponse(String reason, String code) {}

    public Response mapResult(AgentResult result, boolean isAsync) {
        return switch (result) {
            case AgentResult.Finished f -> {
                String answer = sanitizeAnswer(f.answer());
                AgentResponse entity = new AgentResponse(
                        answer, f.totalInputTokens(), f.totalOutputTokens());
                yield isAsync
                        ? Response.accepted(entity).build()
                        : Response.ok(entity).build();
            }
            case AgentResult.AwaitingApproval a -> Response.status(202)
                    .entity(new ApprovalRequiredResponse(
                            a.toolName(), a.paramsJson(), a.sessionId()))
                    .build();
            case AgentResult.LoopExceeded l -> Response.status(408)
                    .entity(new ErrorResponse(
                            "Iteration limit " + l.maxIterations() + " reached",
                            "LOOP_EXCEEDED"))
                    .build();
            case AgentResult.Failed f -> mapFailedResponse(f);
        };
    }

    /**
     * Strips hallucinated cron confirmations — LLM sometimes fabricates
     * "Task scheduled successfully. ID: <uuid>" without actually calling the tool.
     * Verified by checking for a real CRONCREATED audit event in the current session.
     */
    private String sanitizeAnswer(String answer) {
        if (answer == null) return null;

        answer = CONTEXT_BLOCK_PATTERN.matcher(answer).replaceAll("").strip();
        boolean looksLikeCronConfirmation = FAKE_CRON_PATTERN.matcher(answer).find();
        boolean hasRepeatingUuid = REPEATING_UUID_PATTERN.matcher(answer).find();

        if (!looksLikeCronConfirmation && !hasRepeatingUuid) return answer;

        String sessionId = WorkspaceContext.SESSION_ID.isBound()
                ? WorkspaceContext.SESSION_ID.get() : null;

        if (sessionId == null) return answer; // can't verify — pass through

        boolean realCronExists = auditService.hasEventInSession(
                sessionId, SecurityEventType.CRONCREATED);

        if (!realCronExists) {
            log.warnf("LLM hallucinated cron confirmation for session=%s — stripping", sessionId);
            return "I was unable to schedule the task. "
                    + "Please provide a specific, self-contained goal.";
        }

        return answer; // real CRONCREATED exists — answer is legitimate
    }

    private Response mapFailedResponse(AgentResult.Failed f) {
        return switch (f.category()) {
            case COMPLIANCE_VIOLATION -> Response.status(451)
                    .entity(new ErrorResponse(f.reason(), "COMPLIANCE")).build();
            case BUDGET_EXCEEDED -> Response.status(429)
                    .entity(new ErrorResponse(f.reason(), "BUDGET")).build();
            case USER_DENIED -> Response.status(422)
                    .entity(new ErrorResponse(f.reason(), "USER_DENIED")).build();
            case SCHEMA_ERROR -> Response.status(422)
                    .entity(new ErrorResponse(
                            "The agent encountered a schema error and could not complete "
                            + "the request. Try rephrasing your goal.",
                            "SCHEMA"))
                    .build();
            case SYSTEM_ERROR -> Response.serverError()
                    .entity(new ErrorResponse(f.reason(), "SYSTEM")).build();
            case RATE_LIMIT -> Response.status(429)
                    .entity(new ErrorResponse(f.reason(), "RATE_LIMIT")).build();
        };
    }
}

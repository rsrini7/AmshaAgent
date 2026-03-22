package com.arcclaw.core.api;

import com.arcclaw.core.agent.result.AgentResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class AgentResponseMapper {

    public record ApprovalRequest(String agentRole) {}
    public record AgentResponse(String answer, int totalInputTokens, int totalOutputTokens) {}
    public record ApprovalRequiredResponse(String toolName, String paramsJson, String sessionId) {}
    public record ErrorResponse(String reason, String code) {}

    public Response mapResult(AgentResult result, boolean isAsync) {
        return switch (result) {
            case AgentResult.Finished f -> {
                AgentResponse entity = new AgentResponse(f.answer(), f.totalInputTokens(), f.totalOutputTokens());
                yield isAsync ? Response.accepted(entity).build() : Response.ok(entity).build();
            }
            case AgentResult.AwaitingApproval a -> Response.status(202)
                    .entity(new ApprovalRequiredResponse(a.toolName(), a.paramsJson(), a.sessionId()))
                    .build();
            case AgentResult.LoopExceeded l -> Response.status(408)
                    .entity(new ErrorResponse("Iteration limit " + l.maxIterations() + " reached", "LOOP_EXCEEDED"))
                    .build();
            case AgentResult.Failed f -> mapFailedResponse(f);
        };
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
                            "The agent encountered a schema error and could not complete the request. " +
                                    "Try rephrasing your goal or simplifying the parameters.",
                            "SCHEMA"))
                    .build();
            case SYSTEM_ERROR -> Response.serverError()
                    .entity(new ErrorResponse(f.reason(), "SYSTEM")).build();
            case RATE_LIMIT -> Response.status(429)
                    .entity(new ErrorResponse(f.reason(), "RATE_LIMIT")).build();
        };
    }
}

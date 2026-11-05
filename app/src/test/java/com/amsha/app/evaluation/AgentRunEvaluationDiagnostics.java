package com.amsha.app.evaluation;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.restassured.response.Response;

/** Redacts approval failure detail into an allowlisted diagnostic category. */
final class AgentRunEvaluationDiagnostics {

    private AgentRunEvaluationDiagnostics() {}

    static String safeApprovalFailureCategory(ObjectMapper objectMapper, Response response) throws IOException {
        String message = objectMapper
                .readTree(response.asString())
                .path("error")
                .asText()
                .toLowerCase(Locale.ROOT);
        if (message.contains("identity")) return "operation-identity";
        if (message.contains("audit")) return "audit";
        if (message.contains("workspace")) return "workspace";
        if (message.contains("attempt")) return "attempt-admission";
        if (message.contains("checkpoint")) return "checkpoint-state";
        if (message.contains("null")) return "null-state";
        return "unclassified-system-failure";
    }

    static String safeFailureKind(Throwable failure) {
        String message = failure.getMessage();
        if (message != null) {
            for (String category : List.of(
                    "operation-identity",
                    "audit",
                    "workspace",
                    "attempt-admission",
                    "checkpoint-state",
                    "null-state",
                    "unclassified-system-failure")) {
                if (message.contains("failure category=" + category)) return category;
            }
        }
        String simpleName = failure.getClass().getSimpleName();
        return simpleName.matches("[A-Za-z][A-Za-z0-9]{0,63}") ? simpleName : "harness_error";
    }
}

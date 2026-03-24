package com.arcclaw.core.api.dto;

import java.util.List;

import com.arcclaw.core.agent.reasoning.ReasoningStep;

public record SessionStatusResponse(
        String sessionId,
        String status,
        List<ReasoningStep> steps,
        String failReason) {
}

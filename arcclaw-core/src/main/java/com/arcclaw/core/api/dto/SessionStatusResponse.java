package com.arcclaw.core.api.dto;

import com.arcclaw.core.agent.ReasoningStep;
import java.util.List;

public record SessionStatusResponse(
        String sessionId,
        String status,
        List<ReasoningStep> steps) {
}

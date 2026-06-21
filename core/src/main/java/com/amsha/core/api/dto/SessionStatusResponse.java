package com.amsha.core.api.dto;

import java.util.List;

import com.amsha.core.agent.reasoning.ReasoningStep;

public record SessionStatusResponse(String sessionId, String status, List<ReasoningStep> steps, String failReason) {}

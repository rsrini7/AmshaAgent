package com.amsha.app.ui.dto;

import java.time.Instant;
import java.util.Map;

public record PendingApprovalDto(
        String taskId,
        String sessionId,
        String toolName,
        Map<String, Object> params,
        Instant requestedAt,
        String agentName) {}

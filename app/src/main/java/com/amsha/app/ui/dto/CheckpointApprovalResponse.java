package com.amsha.app.ui.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

// Duo-record for REST HITL approval granularity §7.34
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckpointApprovalResponse(String status, String taskId, String toolName, String reason) {}

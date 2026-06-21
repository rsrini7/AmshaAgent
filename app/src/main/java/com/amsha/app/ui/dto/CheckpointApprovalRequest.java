package com.amsha.app.ui.dto;

import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonInclude;

// Duo-record for REST HITL approval granularity §7.34
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckpointApprovalRequest(
        @Size(max = 128) String toolName, @Size(max = 1000) String reason) {}

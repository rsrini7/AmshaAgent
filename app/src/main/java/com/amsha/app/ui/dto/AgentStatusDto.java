package com.amsha.app.ui.dto;

public record AgentStatusDto(
        String agentName, String role, String status, String currentTask, String autonomyLevel, long tokenCount) {}

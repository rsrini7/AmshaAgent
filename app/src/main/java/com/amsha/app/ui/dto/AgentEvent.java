package com.amsha.app.ui.dto;

public record AgentEvent(String sessionId, String eventType, String agentName, String payload) {}

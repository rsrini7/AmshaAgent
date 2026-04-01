package com.arcclaw.onboarding.dto;

public record McpServerRequest(
    String name,
    String transport,
    String url,
    String command,
    String args,
    String env
) {}

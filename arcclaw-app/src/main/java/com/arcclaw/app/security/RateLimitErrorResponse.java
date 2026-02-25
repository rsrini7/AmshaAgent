package com.arcclaw.app.security;

public record RateLimitErrorResponse(String error, String message, int retryAfter, String timestamp) {}

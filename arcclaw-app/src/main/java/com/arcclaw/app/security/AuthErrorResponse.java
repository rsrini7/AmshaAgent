package com.arcclaw.app.security;

public record AuthErrorResponse(String error, String message, String timestamp) {}

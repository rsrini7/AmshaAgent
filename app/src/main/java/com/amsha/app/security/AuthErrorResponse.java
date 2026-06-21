package com.amsha.app.security;

public record AuthErrorResponse(String error, String message, String timestamp) {}

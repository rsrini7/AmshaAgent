package com.amsha.core.events;

import java.util.Map;

/**
 * CDI event fired before/after tool execution.
 * Observed by self-evolve module for trace capture.
 */
public record ToolExecutionEvent(
        Type type,
        String toolName,
        String sessionId,
        String userId,
        String role,
        Map<String, Object> params,
        String resultOutput,
        boolean success,
        Integer turnNumber) {
    public enum Type {
        TOOL_CALL,
        TOOL_RESULT,
        TOOL_ERROR
    }

    public static ToolExecutionEvent call(
            String toolName,
            String sessionId,
            String userId,
            String role,
            Map<String, Object> params,
            Integer turnNumber) {
        return new ToolExecutionEvent(
                Type.TOOL_CALL, toolName, sessionId, userId, role, params, null, false, turnNumber);
    }

    public static ToolExecutionEvent result(
            String toolName,
            String sessionId,
            String userId,
            String role,
            String output,
            boolean success,
            Integer turnNumber) {
        return new ToolExecutionEvent(
                success ? Type.TOOL_RESULT : Type.TOOL_ERROR,
                toolName,
                sessionId,
                userId,
                role,
                null,
                output,
                success,
                turnNumber);
    }
}

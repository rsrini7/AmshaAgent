package com.amsha.mcp.transport;

/** Raised when a lifecycle mutation was based on a stale durable connection revision. */
public class McpRevisionConflictException extends McpConnectionException {
    public McpRevisionConflictException(long expected, long actual) {
        super("MCP connection changed; refresh and retry (expected revision " + expected + ", actual " + actual + ")");
    }
}

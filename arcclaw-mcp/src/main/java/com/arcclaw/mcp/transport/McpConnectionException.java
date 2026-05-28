package com.arcclaw.mcp.transport;

import com.arcclaw.core.exception.ArcClawException;

public class McpConnectionException extends ArcClawException {
    public McpConnectionException(String message) {
        super(message);
    }

    public McpConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}

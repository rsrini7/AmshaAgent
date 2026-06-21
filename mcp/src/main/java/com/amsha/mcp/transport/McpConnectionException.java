package com.amsha.mcp.transport;

import com.amsha.core.exception.AmshaException;

public class McpConnectionException extends AmshaException {
    public McpConnectionException(String message) {
        super(message);
    }

    public McpConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}

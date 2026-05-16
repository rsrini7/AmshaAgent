package com.arcclaw.mcp.bridge;

import com.arcclaw.core.exception.ArcClawException;

public class McpToolNotAllowedException extends ArcClawException {
    public McpToolNotAllowedException(String message) {
        super(message);
    }
}

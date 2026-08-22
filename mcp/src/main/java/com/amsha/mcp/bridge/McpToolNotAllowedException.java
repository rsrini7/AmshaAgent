package com.amsha.mcp.bridge;

import com.amsha.common.exception.AmshaException;

public class McpToolNotAllowedException extends AmshaException {
    public McpToolNotAllowedException(String message) {
        super(message);
    }
}

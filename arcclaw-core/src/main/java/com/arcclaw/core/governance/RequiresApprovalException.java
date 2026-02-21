package com.arcclaw.core.governance;

/**
 * Exception thrown when a tool execution requires explicit human approval.
 */
public class RequiresApprovalException extends RuntimeException {

    private final String toolName;
    private final String toolParametersJson;
    private final String sessionId;

    public RequiresApprovalException(String toolName, String toolParametersJson, String sessionId) {
        super("Tool '" + toolName + "' requires human approval for session " + sessionId);
        this.toolName = toolName;
        this.toolParametersJson = toolParametersJson;
        this.sessionId = sessionId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getToolParametersJson() {
        return toolParametersJson;
    }

    public String getSessionId() {
        return sessionId;
    }
}

package com.arcclaw.core.governance;

import java.util.Map;

/**
 * Exception thrown when a tool execution requires explicit human approval.
 */
public class RequiresApprovalException extends RuntimeException {

    private final ApprovalFrame frame;
    private final String sessionId;

    public RequiresApprovalException(ApprovalFrame frame, String sessionId) {
        super("Tool '" + frame.tool() + "' requires human approval for session " + sessionId);
        this.frame = frame;
        this.sessionId = sessionId;
    }

    public RequiresApprovalException(String toolName, String paramsJson, String sessionId) {
        super("Tool '" + toolName + "' requires human approval for session " + sessionId);
        this.frame = new ApprovalFrame(toolName, Map.of(), ApprovalFrame.RiskLevel.MEDIUM);
        this.sessionId = sessionId;
    }

    public ApprovalFrame getFrame() {
        return frame;
    }

    public String getSessionId() {
        return sessionId;
    }
}

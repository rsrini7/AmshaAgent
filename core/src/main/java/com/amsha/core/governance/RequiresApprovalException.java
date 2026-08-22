package com.amsha.core.governance;

import java.util.Map;

import com.amsha.common.exception.AmshaException;
import com.amsha.security.api.RiskLevel;

/**
 * Exception thrown when a tool execution requires explicit human approval.
 */
public class RequiresApprovalException extends AmshaException {

    private final transient ApprovalFrame frame;
    private final String sessionId;

    public RequiresApprovalException(ApprovalFrame frame, String sessionId) {
        super("Tool '" + frame.tool() + "' requires human approval for session " + sessionId);
        this.frame = frame;
        this.sessionId = sessionId;
    }

    public RequiresApprovalException(String toolName, String paramsJson, String sessionId) {
        super("Tool '" + toolName + "' requires human approval for session " + sessionId);
        this.frame = new ApprovalFrame(toolName, Map.of(), RiskLevel.MEDIUM);
        this.sessionId = sessionId;
    }

    public ApprovalFrame getFrame() {
        return frame;
    }

    public String getSessionId() {
        return sessionId;
    }
}

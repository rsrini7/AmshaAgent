package com.arcclaw.channel.api;

/**
 * Structured approval prompt shown to the human operator before a
 * potentially side-effecting tool execution.
 *
 * @param toolName   the tool awaiting approval
 * @param paramsJson JSON representation of the tool parameters
 * @param sessionId  the session that will be resumed on approval
 */
public record ApprovalPrompt(String toolName, String paramsJson, String sessionId) implements InteractionNode {

    @Override
    public String fallbackText() {
        return "[Approval Required] Tool: " + toolName;
    }
}

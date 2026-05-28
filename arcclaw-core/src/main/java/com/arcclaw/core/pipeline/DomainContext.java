package com.arcclaw.core.pipeline;

import com.arcclaw.core.agent.result.AgentState;
import com.arcclaw.core.security.AutonomyLevel;

/**
 * Encapsulates domain-layer metadata and enriched state for a message.
 */
public final class DomainContext {
    private String sessionId;
    private String agentRole;
    private AgentState initialState;
    private String goal;
    private boolean resumeSignal = false;
    private AutonomyLevel autonomyLevel;
    private SessionContext sessionContext;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getAgentRole() {
        return agentRole;
    }

    public void setAgentRole(String agentRole) {
        this.agentRole = agentRole;
    }

    public AgentState getInitialState() {
        return initialState;
    }

    public void setInitialState(AgentState initialState) {
        this.initialState = initialState;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public boolean isResumeSignal() {
        return resumeSignal;
    }

    public void setResumeSignal(boolean resumeSignal) {
        this.resumeSignal = resumeSignal;
    }

    public AutonomyLevel getAutonomyLevel() {
        return autonomyLevel;
    }

    public void setAutonomyLevel(AutonomyLevel autonomyLevel) {
        this.autonomyLevel = autonomyLevel;
    }

    public SessionContext getSessionContext() {
        return sessionContext;
    }

    public void setSessionContext(SessionContext sessionContext) {
        this.sessionContext = sessionContext;
    }
}

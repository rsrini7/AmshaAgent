package com.arcclaw.core.pipeline;

import java.util.Optional;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.agent.result.AgentState;
import com.arcclaw.core.security.AutonomyLevel;

/**
 * Context for a message passing through the pipeline.
 * Contains both raw input and enriched metadata as handlers process it.
 */
public final class MessageContext {
    private final String chatId;
    private final ChannelUser user;
    private final ChannelMessage message;
    private final ChannelSender sender;
    
    // Enriched fields
    private String sessionId;
    private String agentRole;
    private AgentState initialState;
    private String goal;
    private boolean resumeSignal = false;
    private AutonomyLevel autonomyLevel;

    public MessageContext(String chatId, ChannelUser user, ChannelMessage message, ChannelSender sender) {
        this.chatId = chatId;
        this.user = user;
        this.message = message;
        this.sender = sender;
    }

    public String getChatId() { return chatId; }
    public ChannelUser getUser() { return user; }
    public ChannelMessage getMessage() { return message; }
    public ChannelSender getSender() { return sender; }

    public Optional<String> getSessionId() { return Optional.ofNullable(sessionId); }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public Optional<String> getAgentRole() { return Optional.ofNullable(agentRole); }
    public void setAgentRole(String agentRole) { this.agentRole = agentRole; }

    public Optional<AgentState> getInitialState() { return Optional.ofNullable(initialState); }
    public void setInitialState(AgentState initialState) { this.initialState = initialState; }

    public boolean isResumeSignal() { return resumeSignal; }
    public void setResumeSignal(boolean resumeSignal) { this.resumeSignal = resumeSignal; }

    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }

    public Optional<AutonomyLevel> getAutonomyLevel() { return Optional.ofNullable(autonomyLevel); }
    public void setAutonomyLevel(AutonomyLevel autonomyLevel) { this.autonomyLevel = autonomyLevel; }

    private SessionContext sessionContext;
    public Optional<SessionContext> getSessionContext() { return Optional.ofNullable(sessionContext); }
    public void setSessionContext(SessionContext sessionContext) { this.sessionContext = sessionContext; }
}


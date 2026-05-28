package com.arcclaw.core.pipeline;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.agent.result.AgentState;
import com.arcclaw.core.security.AutonomyLevel;

/**
 * Context for a message passing through the pipeline.
 * Composes {@link TransportContext} and {@link DomainContext}.
 */
public final class MessageContext {
    private final TransportContext transport;
    private final DomainContext domain = new DomainContext();

    public MessageContext(String chatId, ChannelUser user, ChannelMessage message) {
        this.transport = new TransportContext(chatId, user, message);
    }

    public TransportContext transport() {
        return transport;
    }

    public DomainContext domain() {
        return domain;
    }

    // --- Transport Delegates ---

    public String getChatId() {
        return transport.chatId();
    }

    public ChannelUser getUser() {
        return transport.user();
    }

    public ChannelMessage getMessage() {
        return transport.message();
    }

    public ChannelType getChannelType() {
        return transport.channelType();
    }

    // --- Domain Delegates ---

    public Optional<String> getSessionId() {
        return Optional.ofNullable(domain.getSessionId());
    }

    public void setSessionId(String sessionId) {
        domain.setSessionId(sessionId);
    }

    public Optional<String> getAgentRole() {
        return Optional.ofNullable(domain.getAgentRole());
    }

    public void setAgentRole(String agentRole) {
        domain.setAgentRole(agentRole);
    }

    public Optional<AgentState> getInitialState() {
        return Optional.ofNullable(domain.getInitialState());
    }

    public void setInitialState(AgentState initialState) {
        domain.setInitialState(initialState);
    }

    public boolean isResumeSignal() {
        return domain.isResumeSignal();
    }

    public void setResumeSignal(boolean resumeSignal) {
        domain.setResumeSignal(resumeSignal);
    }

    public String getGoal() {
        return domain.getGoal();
    }

    public void setGoal(String goal) {
        domain.setGoal(goal);
    }

    public Optional<AutonomyLevel> getAutonomyLevel() {
        return Optional.ofNullable(domain.getAutonomyLevel());
    }

    public void setAutonomyLevel(AutonomyLevel autonomyLevel) {
        domain.setAutonomyLevel(autonomyLevel);
    }

    public Optional<SessionContext> getSessionContext() {
        return Optional.ofNullable(domain.getSessionContext());
    }

    public void setSessionContext(SessionContext sessionContext) {
        domain.setSessionContext(sessionContext);
    }

    // --- Extensibility ---

    private final Map<String, Object> data = new HashMap<>();

    public Map<String, Object> getData() {
        return data;
    }

    public Object getData(String key) {
        return data.get(key);
    }

    public void setData(String key, Object value) {
        this.data.put(key, value);
    }
}

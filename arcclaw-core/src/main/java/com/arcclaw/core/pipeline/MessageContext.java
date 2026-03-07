package com.arcclaw.core.pipeline;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.core.agent.result.AgentState;

import java.util.Optional;

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
}

package com.amsha.core.events;

/**
 * CDI event fired when user/agent conversational messages are persisted.
 * Observed by the self-evolve module for behavioral extraction.
 */
public record ConversationMessageEvent(
        Type type, String sessionId, String userId, String role, String content, Integer turnNumber) {

    public enum Type {
        USER_MESSAGE,
        AGENT_MESSAGE
    }

    public static ConversationMessageEvent user(String sessionId, String userId, String content, Integer turnNumber) {
        return new ConversationMessageEvent(Type.USER_MESSAGE, sessionId, userId, "user", content, turnNumber);
    }

    public static ConversationMessageEvent agent(
            String sessionId, String userId, String role, String content, Integer turnNumber) {
        return new ConversationMessageEvent(Type.AGENT_MESSAGE, sessionId, userId, role, content, turnNumber);
    }
}

package com.amsha.core.reflex;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.session.SessionCatalogService;

@ApplicationScoped
@ReflexPriority(500)
public class NewSessionReflex implements AgentReflex {
    @Inject
    SessionCatalogService sessions;

    @Override
    public boolean matches(String input) {
        return "/newsession".equalsIgnoreCase(input == null ? "" : input.trim());
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        String next = sessions.rotate(
                context.getUser().channel().id(), context.getUser().channelId(), userId, sessionId);
        context.setSessionId(next);
        return ReflexResult.respond("Started a new session: " + next, context);
    }
}

package com.amsha.core.reflex;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.session.SessionCatalogService;

@ApplicationScoped
@ReflexPriority(500)
public class ResumeReflex implements AgentReflex {
    @Inject
    SessionCatalogService sessions;

    @Override
    public boolean matches(String input) {
        return input != null && input.trim().matches("(?i)^/resume\\s+\\S+$");
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        String target = input.trim().substring("/resume".length()).trim();
        String channelType = context.getUser().channel().id();
        String channelId = context.getUser().channelId();
        if (target.matches("\\d+")) {
            int sequence = Integer.parseInt(target);
            target = sessions.listScoped(userId, channelType, channelId).stream()
                    .filter(e -> e.seq() == sequence)
                    .findFirst()
                    .map(e -> e.sessionId())
                    .orElse("");
        }
        if (target.isBlank()) return ReflexResult.respond("Session not found.", context);
        try {
            String resumed = sessions.resume(target, userId, channelType, channelId);
            context.setSessionId(resumed);
            return ReflexResult.respond("Resumed session: " + resumed, context);
        } catch (IllegalArgumentException e) {
            return ReflexResult.respond(e.getMessage(), context);
        }
    }
}

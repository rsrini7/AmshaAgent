package com.amsha.core.reflex;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.session.SessionCatalogService;

@ApplicationScoped
@ReflexPriority(500)
public class SessionsReflex implements AgentReflex {
    @Inject
    SessionCatalogService sessions;

    @Override
    public boolean matches(String input) {
        return "/sessions".equalsIgnoreCase(input == null ? "" : input.trim());
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        var entries = sessions.listScoped(
                userId, context.getUser().channel().id(), context.getUser().channelId());
        if (entries.isEmpty()) return ReflexResult.respond("No saved sessions.", context);
        String reply = entries.stream()
                .map(e -> "%d. %s %s — %s"
                        .formatted(
                                e.seq(),
                                e.sessionId(),
                                e.active() ? "(active)" : "",
                                e.goalPreview() == null ? "—" : e.goalPreview()))
                .reduce("Sessions:\n", (a, b) -> a + b + "\n");
        return ReflexResult.respond(reply.strip(), context);
    }
}

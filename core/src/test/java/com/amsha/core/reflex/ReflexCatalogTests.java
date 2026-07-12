package com.amsha.core.reflex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.session.SessionCatalogService;
import com.amsha.persistence.api.SessionCatalogEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReflexCatalogTests {

    private static final ChannelUser USER = new ChannelUser("user-1", "chat-1", "Test", ChannelType.CLI);

    private MessageContext ctx() {
        var msg = new ChannelMessage.CliMessage(USER, "test", "sess-1", Instant.now());
        var context = new MessageContext("chat-1", USER, msg);
        context.setSessionId("sess-1");
        return context;
    }

    @Test
    void newSessionReflexRotatesAndSetsContextSession(@Mock SessionCatalogService catalog) {
        var ctx = ctx();
        when(catalog.rotate("cli", "chat-1", "user-1", "sess-1")).thenReturn("cli-fresh");
        var reflex = new NewSessionReflex();
        reflex.sessions = catalog;

        ReflexResult result = reflex.execute("sess-1", "user-1", "chat-1", "/newsession", ctx);

        assertEquals(Optional.of("cli-fresh"), ctx.getSessionId());
        assertTrue(result.commandResponse().isPresent());
        assertTrue(result.commandResponse().get().contains("cli-fresh"));
    }

    @Test
    void sessionsReflexListsFromCatalog(@Mock SessionCatalogService catalog) {
        var ctx = ctx();
        var entry = new SessionCatalogEntry(
                "cat-1",
                "user-1",
                "cli",
                "chat-1",
                "sess-1",
                null,
                "summarize stuff",
                Instant.now(),
                Instant.now(),
                1,
                true,
                "ACTIVE");
        when(catalog.listScoped("user-1", "cli", "chat-1")).thenReturn(List.of(entry));
        var reflex = new SessionsReflex();
        reflex.sessions = catalog;

        ReflexResult result = reflex.execute("sess-1", "user-1", "chat-1", "/sessions", ctx);

        assertTrue(result.commandResponse().isPresent());
        var response = result.commandResponse().get();
        assertTrue(response.contains("sess-1"));
        assertTrue(response.contains("summarize stuff"));
    }

    @Test
    void resumeReflexBySessionIdCallsCatalog(@Mock SessionCatalogService catalog) {
        var ctx = ctx();
        when(catalog.resume("target-id", "user-1", "cli", "chat-1")).thenReturn("target-id");
        var reflex = new ResumeReflex();
        reflex.sessions = catalog;

        ReflexResult result = reflex.execute("sess-1", "user-1", "chat-1", "/resume target-id", ctx);

        assertEquals(Optional.of("target-id"), ctx.getSessionId());
        assertTrue(result.commandResponse().isPresent());
        assertTrue(result.commandResponse().get().contains("target-id"));
    }

    @Test
    void resumeReflexBySequenceLooksUpAndCallsCatalog(@Mock SessionCatalogService catalog) {
        var ctx = ctx();
        var entry = new SessionCatalogEntry(
                "cat-3",
                "user-1",
                "cli",
                "chat-1",
                "sess-3",
                null,
                null,
                Instant.now(),
                Instant.now(),
                3,
                false,
                "INACTIVE");
        when(catalog.listScoped("user-1", "cli", "chat-1")).thenReturn(List.of(entry));
        when(catalog.resume("sess-3", "user-1", "cli", "chat-1")).thenReturn("sess-3");
        var reflex = new ResumeReflex();
        reflex.sessions = catalog;

        ReflexResult result = reflex.execute("sess-1", "user-1", "chat-1", "/resume 3", ctx);

        assertEquals(Optional.of("sess-3"), ctx.getSessionId());
        verify(catalog).resume("sess-3", "user-1", "cli", "chat-1");
    }

    @Test
    void resumeReflexRejectsCatalogErrorGracefully(@Mock SessionCatalogService catalog) {
        var ctx = ctx();
        when(catalog.resume(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("Unknown session"));
        var reflex = new ResumeReflex();
        reflex.sessions = catalog;

        ReflexResult result = reflex.execute("sess-1", "user-1", "chat-1", "/resume bad-id", ctx);

        assertTrue(result.commandResponse().isPresent());
        assertTrue(result.commandResponse().get().contains("Unknown session"));
    }
}

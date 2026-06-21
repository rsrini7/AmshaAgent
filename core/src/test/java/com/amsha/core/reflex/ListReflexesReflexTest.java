package com.amsha.core.reflex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.amsha.core.pipeline.MessageContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Regression tests for {@link ListReflexesReflex} map-based descriptor lookup.
 * Verifies that the extracted {@code DESCRIPTORS} map covers all known reflex types
 * and that the fallback handles cross-module reflexes correctly.
 */
class ListReflexesReflexTest {

    private ListReflexesReflex listReflexes;
    private ReflexRegistry reflexRegistry;

    @BeforeEach
    void setUp() {
        listReflexes = new ListReflexesReflex();
        reflexRegistry = Mockito.mock(ReflexRegistry.class);
        listReflexes.reflexRegistry = reflexRegistry;
    }

    @Test
    void matchesReflexCommand() {
        assertTrue(listReflexes.matches("/reflex"));
        assertTrue(listReflexes.matches("/reflexes"));
        assertTrue(listReflexes.matches("/commands"));
        assertTrue(listReflexes.matches("/help"));
    }

    @Test
    void doesNotMatchOtherCommands() {
        assertFalse(listReflexes.matches("/reset"));
        assertFalse(listReflexes.matches("hello"));
        assertFalse(listReflexes.matches(null));
    }

    @Test
    void executeIncludesKnownReflexes() {
        ResetReflex resetReflex = new ResetReflex();
        HaltReflex haltReflex = new HaltReflex();
        MessageContext ctx = Mockito.mock(MessageContext.class);

        Mockito.when(reflexRegistry.getSortedReflexes()).thenReturn(List.of(resetReflex, haltReflex));

        ReflexResult result = listReflexes.execute("session-1", "user-1", "chat-1", "/reflex", ctx);

        String response = result.commandResponse().get();
        assertTrue(response.contains("/reset"), "Expected /reset in output");
        assertTrue(response.contains("/halt"), "Expected /halt in output");
        assertTrue(response.contains("Registered Reflexes (2)"), "Expected count in header");
    }

    @Test
    void executeHandlesUnknownReflexGracefully() {
        AgentReflex unknownReflex = Mockito.mock(AgentReflex.class);
        MessageContext ctx = Mockito.mock(MessageContext.class);

        Mockito.when(reflexRegistry.getSortedReflexes()).thenReturn(List.of(unknownReflex));

        ReflexResult result = listReflexes.execute("session-1", "user-1", "chat-1", "/reflex", ctx);

        String response = result.commandResponse().get();
        assertTrue(response.contains("Registered Reflexes (1)"), "Expected count in header");
        assertTrue(response.contains("\u2014"), "Expected em-dash fallback for unknown reflex");
    }

    @Test
    void executeIncludesPriorityColumn() {
        ResetReflex resetReflex = new ResetReflex();
        MessageContext ctx = Mockito.mock(MessageContext.class);
        Mockito.when(reflexRegistry.getSortedReflexes()).thenReturn(List.of(resetReflex));

        ReflexResult result = listReflexes.execute("session-1", "user-1", "chat-1", "/reflex", ctx);

        String response = result.commandResponse().get();
        assertTrue(response.contains("| Command | Purpose |"), "Expected table header");
        assertTrue(response.contains("|---------|--------|"), "Expected table separator");
    }
}

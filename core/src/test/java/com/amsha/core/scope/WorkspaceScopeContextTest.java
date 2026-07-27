package com.amsha.core.scope;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkspaceScopeContextTest {

    private WorkspaceScopeContext context;

    @BeforeEach
    void setUp() {
        context = new WorkspaceScopeContext();
    }

    @Test
    void testScopeMetadata() {
        assertEquals(WorkspaceScoped.class, context.getScope());
        assertTrue(context.isNormal());
        assertFalse(context.isActive());
    }

    @Test
    void testGetAndDestroyBeanInWorkspaceScope() {
        WorkspaceScopeHolder.run("sess-scope-1", "user-1", "chat-1", () -> {
            assertTrue(context.isActive());

            @SuppressWarnings("unchecked")
            Contextual<String> contextual = mock(Contextual.class);
            @SuppressWarnings("unchecked")
            CreationalContext<String> creationalContext = mock(CreationalContext.class);

            when(contextual.create(creationalContext)).thenReturn("instance-1");

            String instance = context.get(contextual, creationalContext);
            assertEquals("instance-1", instance);

            // Second get retrieves from store without creating again
            String cached = context.get(contextual);
            assertEquals("instance-1", cached);

            context.destroy(contextual);
            verify(contextual).destroy(eq("instance-1"), eq(creationalContext));
        });

        assertFalse(context.isActive());
    }

    @Test
    void testDestroySession() {
        WorkspaceScopeHolder.run("sess-scope-2", "user-2", "chat-2", () -> {
            @SuppressWarnings("unchecked")
            Contextual<String> contextual = mock(Contextual.class);
            @SuppressWarnings("unchecked")
            CreationalContext<String> creationalContext = mock(CreationalContext.class);
            when(contextual.create(creationalContext)).thenReturn("instance-2");

            context.get(contextual, creationalContext);

            context.destroySession("sess-scope-2");
            verify(contextual).destroy(eq("instance-2"), eq(creationalContext));
        });
    }

    @Test
    void testGetIfActiveWhenInactive() {
        @SuppressWarnings("unchecked")
        Contextual<String> contextual = mock(Contextual.class);
        assertNull(context.getIfActive(contextual, c -> null));
    }
}

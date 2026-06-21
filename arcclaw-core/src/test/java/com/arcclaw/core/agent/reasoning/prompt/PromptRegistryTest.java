package com.arcclaw.core.agent.reasoning.prompt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.enterprise.inject.Instance;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class PromptRegistryTest {

    @Mock
    Instance<?> storeInstance;

    private PromptRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        when(storeInstance.isUnsatisfied()).thenReturn(true);
        registry = new PromptRegistry();
        setField(registry, "storeInstance", storeInstance);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void get_returnsFallback_whenNoStore() {
        assertEquals("fallback-text", registry.get("x", "y", "fallback-text"));
    }

    @Test
    void get_returnsNull_whenNoStoreAndNoFallback() {
        assertNull(registry.get("x", "y"));
    }

    @Test
    void list_returnsEmpty_whenNoStore() {
        assertTrue(registry.list().isEmpty());
    }

    @Test
    void listByRole_returnsEmpty_whenNoStore() {
        assertTrue(registry.listByRole("coordinator").isEmpty());
    }

    @Test
    void getDto_returnsEmpty_whenNoStore() {
        assertTrue(registry.getDto("coordinator", "persona_identity").isEmpty());
    }

    @Test
    void save_doesNotThrow_whenNoStore() {
        registry.save(null);
    }

    @Test
    void delete_returnsFalse_whenNoStore() {
        assertFalse(registry.delete("some-id"));
    }

    @Test
    void constants_areValid() {
        assertEquals("ACTIVE", PromptConstants.STATUS_ACTIVE);
        assertEquals("DRAFT", PromptConstants.STATUS_DRAFT);
        assertEquals("ARCHIVED", PromptConstants.STATUS_ARCHIVED);
        assertEquals("core", PromptConstants.ROLE_CORE);
        assertEquals(1, PromptConstants.DEFAULT_VERSION);
        assertEquals(100, PromptConstants.DEFAULT_ROLLOUT);
    }

    @Test
    void sectionKeys_areNonBlank() {
        assertFalse(PromptConstants.KEY_PERSONA_IDENTITY.isBlank());
        assertFalse(PromptConstants.KEY_ROLE_TEMPLATE.isBlank());
        assertFalse(PromptConstants.KEY_SECURITY_RULES.isBlank());
    }
}

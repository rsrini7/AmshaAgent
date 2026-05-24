package com.arcclaw.core.agent.reasoning.prompt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import jakarta.enterprise.inject.Instance;

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
    void sha256_isDeterministic() {
        assertEquals(sha256("hello"), sha256("hello"));
    }

    @Test
    void sha256_differsForDifferentInput() {
        assertNotEquals(sha256("hello"), sha256("world"));
    }

    @Test
    void sha256_hasExpectedLength() {
        assertEquals(64, sha256("test content").length());
    }

    @Test
    void parseHash_returnsEmpty_whenNull() {
        assertEquals("", parseHash(null));
    }

    @Test
    void parseHash_returnsEmpty_whenBlank() {
        assertEquals("", parseHash(""));
    }

    @Test
    void parseHash_returnsEmpty_whenNoSha256() {
        assertEquals("", parseHash("{\"source\":\"classpath\"}"));
    }

    @Test
    void parseHash_extractsSha256() {
        assertEquals("abc123def456", parseHash("{\"source\":\"classpath\",\"sha256\":\"abc123def456\"}"));
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

    private static String sha256(String content) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            var digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String parseHash(String auditMetadata) {
        if (auditMetadata == null || auditMetadata.isBlank()) return "";
        int idx = auditMetadata.indexOf("\"sha256\":\"");
        if (idx < 0) return "";
        int start = idx + "\"sha256\":\"".length();
        int end = auditMetadata.indexOf("\"", start);
        return end > start ? auditMetadata.substring(start, end) : "";
    }
}

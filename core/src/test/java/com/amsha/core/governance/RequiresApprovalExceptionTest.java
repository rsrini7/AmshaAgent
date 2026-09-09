package com.amsha.core.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.amsha.security.api.RiskLevel;

import org.junit.jupiter.api.Test;

class RequiresApprovalExceptionTest {

    @Test
    void testConstructorWithApprovalFrame() {
        ApprovalFrame frame = new ApprovalFrame("web_fetch", Map.of("url", "https://example.com"), RiskLevel.HIGH);
        RequiresApprovalException ex = new RequiresApprovalException(frame, "sess-123");

        assertEquals("sess-123", ex.getSessionId());
        assertEquals(frame, ex.getFrame());
        assertEquals("web_fetch", ex.getFrame().tool());
        assertEquals("https://example.com", ex.getFrame().params().get("url"));
        assertEquals(RiskLevel.HIGH, ex.getFrame().riskLevel());
        assertTrue(ex.getMessage().contains("web_fetch"));
        assertTrue(ex.getMessage().contains("sess-123"));
    }

    @Test
    void testConstructorWithValidParamsJson() {
        String paramsJson = "{\"url\":\"https://example.com/test\",\"maxChars\":1000}";
        RequiresApprovalException ex = new RequiresApprovalException("web_fetch", paramsJson, "sess-456");

        assertEquals("sess-456", ex.getSessionId());
        assertNotNull(ex.getFrame());
        assertEquals("web_fetch", ex.getFrame().tool());
        assertEquals(RiskLevel.MEDIUM, ex.getFrame().riskLevel());
        assertEquals("https://example.com/test", ex.getFrame().params().get("url"));
        assertEquals(1000, ex.getFrame().params().get("maxChars"));
    }

    @Test
    void testConstructorWithEmptyOrMalformedParamsJson() {
        RequiresApprovalException exNull = new RequiresApprovalException("sandbox_exec", null, "sess-1");
        assertTrue(exNull.getFrame().params().isEmpty());

        RequiresApprovalException exBlank = new RequiresApprovalException("sandbox_exec", "   ", "sess-1");
        assertTrue(exBlank.getFrame().params().isEmpty());

        RequiresApprovalException exEmptyObj = new RequiresApprovalException("sandbox_exec", "{}", "sess-1");
        assertTrue(exEmptyObj.getFrame().params().isEmpty());

        assertThrows(
                IllegalArgumentException.class,
                () -> new RequiresApprovalException("sandbox_exec", "{not valid json", "sess-1"));
    }
}

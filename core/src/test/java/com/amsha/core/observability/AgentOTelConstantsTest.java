package com.amsha.core.observability;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AgentOTelConstantsTest {

    @Test
    void spanNamesAreAllNonBlank() {
        assertNotNull(AgentOTelConstants.SPAN_SESSION);
        assertNotNull(AgentOTelConstants.SPAN_AGENT_ITERATION);
        assertNotNull(AgentOTelConstants.SPAN_TOOL_CALL);
        assertNotNull(AgentOTelConstants.SPAN_TOOL_CONTAINER_EXEC);
        assertNotNull(AgentOTelConstants.SPAN_TOOL_GOVERNANCE);
        assertNotNull(AgentOTelConstants.SPAN_REFLEX_EXECUTE);
        assertNotNull(AgentOTelConstants.SPAN_LLM_PROVIDER_CALL);
        assertNotNull(AgentOTelConstants.SPAN_VAULT_STORE);
        assertNotNull(AgentOTelConstants.SPAN_VAULT_RETRIEVE);
        assertNotNull(AgentOTelConstants.SPAN_HITL_RESOLVE);
        assertNotNull(AgentOTelConstants.SPAN_DELEGATION_DISPATCH);
        assertNotNull(AgentOTelConstants.SPAN_STRUCTURED_OUTPUT);
        assertNotNull(AgentOTelConstants.SPAN_COMPACTION);
        assertNotNull(AgentOTelConstants.SPAN_CRON_DISPATCH);
        assertNotNull(AgentOTelConstants.SPAN_CHECKPOINT_TRANSITION);
    }

    @Test
    void attributeKeysAreAllNonBlank() {
        assertNotNull(AgentOTelConstants.ATTR_SESSION_ID.getKey());
        assertNotNull(AgentOTelConstants.ATTR_USER_ID.getKey());
        assertNotNull(AgentOTelConstants.ATTR_AGENT_ROLE.getKey());
        assertNotNull(AgentOTelConstants.ATTR_TOOL_NAME.getKey());
        assertNotNull(AgentOTelConstants.ATTR_ITERATION.getKey());
        assertNotNull(AgentOTelConstants.ATTR_INPUT_TOKENS.getKey());
        assertNotNull(AgentOTelConstants.ATTR_OUTPUT_TOKENS.getKey());
    }

    @Test
    void privateConstructorCannotInstantiate() {
        var ctor = AgentOTelConstants.class.getDeclaredConstructors();
        assertEquals(1, ctor.length);
        assertFalse(ctor[0].canAccess(null));
    }

    @Test
    void oiKindValuesAreDefined() {
        assertEquals("AGENT", AgentOTelConstants.OI_KIND_AGENT);
        assertEquals("CHAIN", AgentOTelConstants.OI_KIND_CHAIN);
        assertEquals("TOOL", AgentOTelConstants.OI_KIND_TOOL);
        assertEquals("LLM", AgentOTelConstants.OI_KIND_LLM);
        assertEquals("RETRIEVER", AgentOTelConstants.OI_KIND_RETRIEVER);
    }
}

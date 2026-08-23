package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amsha.core.agent.role.AgentGroupRegistry;
import com.amsha.core.agent.role.AgentRoleEnum;

import org.junit.jupiter.api.Test;

/**
 * F6 regression: DelegateToAgentTool must query the group registry through
 * GroupConsultationEngine's proxy-safe accessors ({@code hasGroupRegistry()} /
 * {@code findGroupById(String)}), never by reading the {@code groupRegistry} field
 * directly — field access on an {@code @ApplicationScoped} client proxy always reads
 * the proxy's own null field, which made group consultation silently fall back in
 * every runtime (JVM and native).
 */
class GroupConsultationEngineAccessorTest {

    @Test
    void hasGroupRegistry_FalseWhenUnresolved() {
        var engine = new GroupConsultationEngine();
        assertFalse(engine.hasGroupRegistry());
    }

    @Test
    void hasGroupRegistry_TrueWhenResolved() {
        var engine = new GroupConsultationEngine();
        engine.groupRegistry = new AgentGroupRegistry();
        assertTrue(engine.hasGroupRegistry());
    }

    @Test
    void findGroupById_DelegatesToRegistry() {
        var engine = new GroupConsultationEngine();
        engine.groupRegistry = new AgentGroupRegistry();

        var kailasa = engine.findGroupById("kailasa");
        assertTrue(kailasa.isPresent());
        assertEquals(AgentRoleEnum.DEBUGGER_ID, kailasa.get().leadRoleId());

        assertTrue(engine.findGroupById("brahmaloka").isPresent());
        assertFalse(engine.findGroupById("no-such-group").isPresent());
        assertFalse(engine.findGroupById(null).isPresent());
    }
}

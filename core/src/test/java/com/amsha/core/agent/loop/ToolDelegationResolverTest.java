package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.role.CapabilityRegistry;
import com.amsha.tool.api.ToolCapability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ToolDelegationResolverTest {
    private static final ToolDelegationResolver RESOLVER = new ToolDelegationResolver();

    static Stream<Arguments> toolOwners() {
        return Stream.of(
                Arguments.of(ToolCapability.SANDBOX_EXEC.toolName(), AgentRoleEnum.CODER_ID),
                Arguments.of(ToolCapability.MANAGE_CHECKLIST.toolName(), AgentRoleEnum.CODER_ID),
                Arguments.of(ToolCapability.REGISTER_MCP_SERVER.toolName(), AgentRoleEnum.CODER_ID),
                Arguments.of(ToolCapability.GITHUB.toolName(), AgentRoleEnum.RESEARCHER_ID),
                Arguments.of(ToolCapability.BROWSER_NAVIGATE.toolName(), AgentRoleEnum.RESEARCHER_ID),
                Arguments.of(ToolCapability.WEB_SEARCH_BRAVE.toolName(), AgentRoleEnum.RESEARCHER_ID),
                Arguments.of(ToolCapability.WEB_SEARCH_SERPER.toolName(), AgentRoleEnum.RESEARCHER_ID),
                Arguments.of(ToolCapability.WEB_FETCH.toolName(), AgentRoleEnum.RESEARCHER_ID),
                Arguments.of(ToolCapability.RECALL_MEMORY_SEARCH.toolName(), AgentRoleEnum.COORDINATOR_ID),
                Arguments.of(ToolCapability.ARCHIVAL_MEMORY_SEARCH.toolName(), AgentRoleEnum.COORDINATOR_ID),
                Arguments.of(ToolCapability.CORE_MEMORY_READ.toolName(), AgentRoleEnum.COORDINATOR_ID),
                Arguments.of(ToolCapability.CORE_MEMORY_APPEND.toolName(), AgentRoleEnum.COORDINATOR_ID),
                Arguments.of(ToolCapability.CORE_MEMORY_REPLACE.toolName(), AgentRoleEnum.COORDINATOR_ID),
                Arguments.of(ToolCapability.ARCHIVAL_MEMORY_INSERT.toolName(), AgentRoleEnum.COORDINATOR_ID),
                Arguments.of(ToolCapability.INGEST_DOCUMENT.toolName(), AgentRoleEnum.COORDINATOR_ID),
                Arguments.of(ToolCapability.SCHEDULE_TASK.toolName(), AgentRoleEnum.COORDINATOR_ID),
                Arguments.of(ToolCapability.MANAGE_SCHEDULE.toolName(), AgentRoleEnum.COORDINATOR_ID),
                Arguments.of(ToolCapability.AGENT_TASK.toolName(), AgentRoleEnum.COORDINATOR_ID),
                Arguments.of(ToolCapability.SELF_EVOLVE_REFLECT.toolName(), AgentRoleEnum.COORDINATOR_ID));
    }

    @ParameterizedTest(name = "{0} routes unauthorized personas to {1}")
    @MethodSource("toolOwners")
    void everyBuiltInPersonaUsesTheOwnerWithoutReceivingExtraAccess(String toolName, String owner) {
        for (String role : ToolDelegationResolver.builtInRoles()) {
            Optional<String> actual = RESOLVER.resolveOwner(role, toolName);
            if (role.equals(owner) || isAuthorizedRole(role, toolName)) {
                assertTrue(actual.isEmpty(), () -> role + " must retain its existing " + toolName + " access");
            } else {
                assertEquals(Optional.of(owner), actual, () -> role + " must delegate " + toolName);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("builtInRoles")
    void everyBuiltInPersonaCanAlwaysUseGaruda(String role) {
        assertTrue(RESOLVER.resolveOwner(role, ToolCapability.DELEGATE_TO_AGENT.toolName())
                .isEmpty());
    }

    @Test
    void everyGovernedToolHasAnExplicitOwner() {
        for (ToolCapability capability : ToolCapability.values()) {
            if (capability == ToolCapability.DELEGATE_TO_AGENT || capability == ToolCapability.HOST_EXEC) {
                continue;
            }
            assertTrue(
                    RESOLVER.hasExplicitOwner(capability.toolName()),
                    () -> "Missing redelegation owner for " + capability.toolName());
        }
    }

    @Test
    void runtimeCapabilityRegistryIsAuthoritative() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.grant(AgentRoleEnum.COORDINATOR_ID, ToolCapability.WEB_FETCH.toolName());

        ToolDelegationResolver runtimeResolver = new ToolDelegationResolver(registry);

        assertEquals(
                Optional.of(AgentRoleEnum.COORDINATOR_ID),
                runtimeResolver.resolveOwner(AgentRoleEnum.CODER_ID, ToolCapability.WEB_FETCH.toolName()));
        assertTrue(runtimeResolver
                .resolveOwner(AgentRoleEnum.CODER_ID, ToolCapability.BROWSER_NAVIGATE.toolName())
                .isEmpty());
    }

    @Test
    void runtimeRegistryFallbackUsesDeclaredPriorityInsteadOfAlphabeticalOrder() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.grant(AgentRoleEnum.ARCHITECT_ID, "custom_tool");
        registry.grant(AgentRoleEnum.RESEARCHER_ID, "custom_tool");
        ToolDelegationResolver runtimeResolver = new ToolDelegationResolver(registry);

        assertEquals(
                Optional.of(AgentRoleEnum.RESEARCHER_ID),
                runtimeResolver.resolveOwner(AgentRoleEnum.CODER_ID, "custom_tool"));
    }

    static Stream<String> builtInRoles() {
        return AgentRoleEnum.values().stream().map(AgentRoleEnum::id);
    }

    private boolean isAuthorizedRole(String role, String toolName) {
        ToolCapability capability = ToolCapability.fromToolName(toolName).orElse(null);
        if (capability == null) return false;
        return switch (capability) {
            case GITHUB ->
                SetOf.roles(
                                AgentRoleEnum.ARCHITECT_ID,
                                AgentRoleEnum.COORDINATOR_ID,
                                AgentRoleEnum.RESEARCHER_ID,
                                AgentRoleEnum.DEBUGGER_ID,
                                AgentRoleEnum.SYNTHESIZER_ID)
                        .contains(role);
            case SCHEDULE_TASK, MANAGE_SCHEDULE ->
                SetOf.roles(AgentRoleEnum.COORDINATOR_ID, AgentRoleEnum.SYNTHESIZER_ID)
                        .contains(role);
            case RECALL_MEMORY_SEARCH ->
                SetOf.roles(
                                AgentRoleEnum.ARCHITECT_ID,
                                AgentRoleEnum.COORDINATOR_ID,
                                AgentRoleEnum.RESEARCHER_ID,
                                AgentRoleEnum.SYNTHESIZER_ID,
                                AgentRoleEnum.CODER_ID,
                                AgentRoleEnum.DEBUGGER_ID)
                        .contains(role);
            case ARCHIVAL_MEMORY_SEARCH,
                    CORE_MEMORY_READ,
                    CORE_MEMORY_APPEND,
                    CORE_MEMORY_REPLACE,
                    ARCHIVAL_MEMORY_INSERT ->
                SetOf.roles(
                                AgentRoleEnum.ARCHITECT_ID,
                                AgentRoleEnum.COORDINATOR_ID,
                                AgentRoleEnum.RESEARCHER_ID,
                                AgentRoleEnum.SYNTHESIZER_ID,
                                AgentRoleEnum.CODER_ID)
                        .contains(role);
            case BROWSER_NAVIGATE -> role.equals(AgentRoleEnum.RESEARCHER_ID);
            case SANDBOX_EXEC, MANAGE_CHECKLIST, REGISTER_MCP_SERVER ->
                SetOf.roles(AgentRoleEnum.CODER_ID, AgentRoleEnum.DEBUGGER_ID).contains(role);
            case INGEST_DOCUMENT ->
                SetOf.roles(AgentRoleEnum.COORDINATOR_ID, AgentRoleEnum.SYNTHESIZER_ID, AgentRoleEnum.NURTURER_ID)
                        .contains(role);
            case WEB_SEARCH_BRAVE, WEB_SEARCH_SERPER ->
                SetOf.roles(AgentRoleEnum.RESEARCHER_ID, AgentRoleEnum.SYNTHESIZER_ID)
                        .contains(role);
            case AGENT_TASK ->
                SetOf.roles(AgentRoleEnum.COORDINATOR_ID, AgentRoleEnum.SYNTHESIZER_ID, AgentRoleEnum.RESEARCHER_ID)
                        .contains(role);
            case WEB_FETCH ->
                SetOf.roles(AgentRoleEnum.RESEARCHER_ID, AgentRoleEnum.SYNTHESIZER_ID, AgentRoleEnum.COORDINATOR_ID)
                        .contains(role);
            case SELF_EVOLVE_REFLECT -> role.equals(AgentRoleEnum.COORDINATOR_ID);
            default -> false;
        };
    }

    private static final class SetOf {
        private SetOf() {}

        static Set<String> roles(String... roles) {
            return Set.of(roles);
        }
    }
}

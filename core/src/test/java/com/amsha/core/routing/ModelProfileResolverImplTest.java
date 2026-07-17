package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.core.agent.role.AgentGroupRegistry;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.scope.ActiveWorkspaceContext;
import com.amsha.core.util.MdcKeys;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.persistence.api.LlmProfileStorePort;
import com.amsha.persistence.api.ModelProfileDto;
import com.amsha.persistence.api.ModelRouteAssignmentDto;
import com.amsha.persistence.api.WorkspaceConnectionGrantStorePort;

import org.jboss.logging.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelProfileResolverImplTest {

    @Mock
    LlmProfileStorePort profileStore;

    @Mock
    WorkspaceConnectionGrantStorePort grantStore;

    @Mock
    AgentGroupRegistry groupRegistry;

    @Mock
    RoleRegistry roleRegistry;

    @Mock
    ActiveWorkspaceContext activeWorkspaceContext;

    @InjectMocks
    ModelProfileResolverImpl resolver;

    private static final String OWNER = "user-1";
    private static final String WS = "ws-1";
    private static final Instant NOW = Instant.now();

    @BeforeEach
    void setUp() {
        // Resolver resolves the current user via WorkspaceContext.USER_ID then MDC userId.
        MDC.put(MdcKeys.USER_ID, OWNER);
        // Default: no active workspace, no group membership.
        when(activeWorkspaceContext.workspaceId()).thenReturn(Optional.empty());
        when(groupRegistry.findByMember(any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        MDC.remove(MdcKeys.USER_ID);
    }

    private ModelRouteAssignmentDto route(
            String scopeType, String scopeId, String targetKind, String targetId, List<String> profiles) {
        return new ModelRouteAssignmentDto(
                "route-" + scopeType + "-" + targetKind,
                scopeType,
                scopeId,
                targetKind,
                targetId,
                profiles,
                true,
                1,
                NOW,
                NOW);
    }

    private void seedProfileAndConnection(String profileId, String connId) {
        when(profileStore.findModelProfile(profileId))
                .thenReturn(Optional.of(
                        new ModelProfileDto(profileId, connId, "gpt-4o", "NATIVE", "{}", "{}", true, 1, NOW, NOW)));
        when(profileStore.findConnection(connId))
                .thenReturn(Optional.of(new LlmConnectionDto(
                        connId, "USER", OWNER, OWNER, "conn", "openai", null, null, "api_key", null, "ACTIVE", 1, NOW,
                        NOW)));
    }

    @Test
    void ownerRoleTakesPrecedenceOverOwnerDefault() {
        seedProfileAndConnection("p-role", "c-role");
        seedProfileAndConnection("p-default", "c-default");
        when(profileStore.listRoutesByScope("USER", OWNER))
                .thenReturn(List.of(
                        route("USER", OWNER, "DEFAULT", null, List.of("p-default")),
                        route("USER", OWNER, "ROLE", "coder", List.of("p-role"))));

        RouteResolution r = resolver.resolve("coder");

        assertTrue(r.resolved());
        assertEquals("user-role", r.precedenceTier());
        assertEquals(
                List.of("p-role"),
                r.chain().stream().map(ResolvedProfile::profileId).toList());
    }

    @Test
    void ownerDefaultUsedWhenNoOwnerRoleRoute() {
        seedProfileAndConnection("p-default", "c-default");
        when(profileStore.listRoutesByScope("USER", OWNER))
                .thenReturn(List.of(route("USER", OWNER, "DEFAULT", null, List.of("p-default"))));

        RouteResolution r = resolver.resolve("coder");

        assertTrue(r.resolved());
        assertEquals("user-default", r.precedenceTier());
    }

    @Test
    void workspaceRouteUsedWhenNoOwnerRoute() {
        seedProfileAndConnection("p-ws", "c-ws");
        when(activeWorkspaceContext.workspaceId()).thenReturn(Optional.of(WS));
        when(profileStore.listRoutesByScope("USER", OWNER)).thenReturn(List.of());
        when(profileStore.listRoutesByScope("WORKSPACE", WS))
                .thenReturn(List.of(route("WORKSPACE", WS, "ROLE", "coder", List.of("p-ws"))));

        RouteResolution r = resolver.resolve("coder");

        assertTrue(r.resolved());
        assertEquals("workspace-role", r.precedenceTier());
    }

    private void seedSystemProfileAndConnection(String profileId, String connId) {
        when(profileStore.findModelProfile(profileId))
                .thenReturn(Optional.of(
                        new ModelProfileDto(profileId, connId, "gpt-4o", "NATIVE", "{}", "{}", true, 1, NOW, NOW)));
        when(profileStore.findConnection(connId))
                .thenReturn(Optional.of(new LlmConnectionDto(
                        connId, "SYSTEM", "SYSTEM", "system", "conn", "openai", null, null, "api_key", null, "ACTIVE",
                        1, NOW, NOW)));
    }

    @Test
    void systemRouteUsedWhenNoOwnedOrWorkspaceRoute() {
        seedSystemProfileAndConnection("p-sys", "c-sys");
        when(activeWorkspaceContext.workspaceId()).thenReturn(Optional.empty());
        when(profileStore.listRoutesByScope("USER", OWNER)).thenReturn(List.of());
        when(profileStore.listRoutesByScope("SYSTEM", "SYSTEM"))
                .thenReturn(List.of(route("SYSTEM", "SYSTEM", "DEFAULT", null, List.of("p-sys"))));

        RouteResolution r = resolver.resolve("coder");

        assertTrue(r.resolved());
        assertEquals("system-default", r.precedenceTier());
        assertEquals(
                List.of("p-sys"),
                r.chain().stream().map(ResolvedProfile::profileId).toList());
    }

    @Test
    void noOwnedRoute_returnsEmpty() {
        when(profileStore.listRoutesByScope("USER", OWNER)).thenReturn(List.of());
        when(activeWorkspaceContext.workspaceId()).thenReturn(Optional.empty());

        RouteResolution r = resolver.resolve("coder");

        assertFalse(r.resolved());
        assertEquals("no-owned-route", r.precedenceTier());
    }

    @Test
    void disabledProfileExcludedFromChain() {
        when(profileStore.findModelProfile("p-off"))
                .thenReturn(Optional.of(
                        new ModelProfileDto("p-off", "c-off", "gpt-4o", "NATIVE", "{}", "{}", false, 1, NOW, NOW)));
        seedProfileAndConnection("p-on", "c-on");
        when(profileStore.listRoutesByScope("USER", OWNER))
                .thenReturn(List.of(route("USER", OWNER, "DEFAULT", null, List.of("p-off", "p-on"))));

        RouteResolution r = resolver.resolve("coder");

        assertTrue(r.resolved());
        assertEquals(
                List.of("p-on"),
                r.chain().stream().map(ResolvedProfile::profileId).toList());
    }

    @Test
    void registeredDynamicRole_resolvesRoute() {
        seedProfileAndConnection("p-skill", "c-skill");
        when(roleRegistry.isRegisteredDynamic("skill:reviewer")).thenReturn(true);
        when(profileStore.listRoutesByScope("USER", OWNER))
                .thenReturn(List.of(route("USER", OWNER, "ROLE", "skill:reviewer", List.of("p-skill"))));

        RouteResolution r = resolver.resolve("skill:reviewer");

        assertTrue(r.resolved());
        assertEquals("user-role", r.precedenceTier());
        assertEquals(
                List.of("p-skill"),
                r.chain().stream().map(ResolvedProfile::profileId).toList());
    }

    @Test
    void unregisteredDynamicRole_rejectedEvenWhenRouteExists() {
        seedProfileAndConnection("p-skill", "c-skill");
        // Route stored against an unregistered dynamic role must NOT be resolved.
        when(roleRegistry.isRegisteredDynamic("skill:ghost")).thenReturn(false);
        when(profileStore.listRoutesByScope("USER", OWNER))
                .thenReturn(List.of(route("USER", OWNER, "ROLE", "skill:ghost", List.of("p-skill"))));
        when(profileStore.listRoutesByScope("WORKSPACE", WS)).thenReturn(List.of());
        when(activeWorkspaceContext.workspaceId()).thenReturn(Optional.empty());

        RouteResolution r = resolver.resolve("skill:ghost");

        assertFalse(r.resolved());
        assertEquals("no-owned-route", r.precedenceTier());
    }
}

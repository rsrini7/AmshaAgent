package com.amsha.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.time.Instant;
import java.util.Optional;

import com.amsha.core.scope.ActiveWorkspaceContext;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.WorkspaceMembershipDto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests for {@link ActiveWorkspaceFilter}.
 *
 * <p>This filter is what turns an {@code X-Amsha-Workspace-Id} header into a bound workspace, so both
 * rejection paths (unauthenticated, not a member) and the one success path are asserted against the exact
 * HTTP status and against whether the workspace was bound at all.
 */
@SuppressWarnings("UnnecessaryAssignment")
class ActiveWorkspaceFilterTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private ActiveWorkspaceFilter filter;
    private ContainerRequestContext requestContext;
    private SecurityContext securityContext;
    private ActiveWorkspaceContext activeWorkspaceContext;
    private IdentityStorePort identityStore;

    @BeforeEach
    void setUp() {
        filter = new ActiveWorkspaceFilter();
        requestContext = mock(ContainerRequestContext.class);
        securityContext = mock(SecurityContext.class);
        activeWorkspaceContext = mock(ActiveWorkspaceContext.class);
        identityStore = mock(IdentityStorePort.class);
        filter.activeWorkspaceContext = activeWorkspaceContext;
        filter.identityStore = identityStore;
        when(requestContext.getSecurityContext()).thenReturn(securityContext);
    }

    private void givenCaller(String userId) {
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn(userId);
        when(securityContext.getUserPrincipal()).thenReturn(principal);
    }

    private void givenMembership(String status) {
        WorkspaceMembershipDto membership = new WorkspaceMembershipDto("ws-1", "user-1", "OWNER", status, 0, NOW, NOW);
        when(identityStore.findMembership("ws-1", "user-1")).thenReturn(Optional.of(membership));
    }

    private int abortedStatus() {
        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(captor.capture());
        return captor.getValue().getStatus();
    }

    @Test
    @DisplayName("an absent workspace header leaves the request untouched")
    void absentHeaderIsIgnored() {
        when(requestContext.getHeaderString(ActiveWorkspaceFilter.WORKSPACE_HEADER))
                .thenReturn(null);

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
        verifyNoInteractions(identityStore, activeWorkspaceContext);
    }

    @Test
    @DisplayName("a blank workspace header leaves the request untouched")
    void blankHeaderIsIgnored() {
        when(requestContext.getHeaderString(ActiveWorkspaceFilter.WORKSPACE_HEADER))
                .thenReturn("   ");

        filter.filter(requestContext);

        verify(requestContext, never()).abortWith(any());
        verifyNoInteractions(identityStore, activeWorkspaceContext);
    }

    @Test
    @DisplayName("an unauthenticated request is rejected with 401 and never binds a workspace")
    void unauthenticatedRequestIsRejected() {
        when(requestContext.getHeaderString(ActiveWorkspaceFilter.WORKSPACE_HEADER))
                .thenReturn("ws-1");
        when(securityContext.getUserPrincipal()).thenReturn(null);

        filter.filter(requestContext);

        assertEquals(401, abortedStatus());
        verifyNoInteractions(identityStore, activeWorkspaceContext);
    }

    @Test
    @DisplayName("a caller with no membership row is rejected with 403")
    void nonMemberIsRejected() {
        when(requestContext.getHeaderString(ActiveWorkspaceFilter.WORKSPACE_HEADER))
                .thenReturn("ws-1");
        givenCaller("user-1");
        when(identityStore.findMembership("ws-1", "user-1")).thenReturn(Optional.empty());

        filter.filter(requestContext);

        assertEquals(403, abortedStatus());
        verify(activeWorkspaceContext, never()).setWorkspaceId(any());
    }

    @Test
    @DisplayName("a suspended member is rejected with 403")
    void suspendedMemberIsRejected() {
        when(requestContext.getHeaderString(ActiveWorkspaceFilter.WORKSPACE_HEADER))
                .thenReturn("ws-1");
        givenCaller("user-1");
        givenMembership("SUSPENDED");

        filter.filter(requestContext);

        assertEquals(403, abortedStatus());
        verify(activeWorkspaceContext, never()).setWorkspaceId(any());
    }

    @Test
    @DisplayName("an active member has the workspace bound and the request continues")
    void activeMemberBindsTheWorkspace() {
        when(requestContext.getHeaderString(ActiveWorkspaceFilter.WORKSPACE_HEADER))
                .thenReturn("ws-1");
        givenCaller("user-1");
        givenMembership("ACTIVE");

        filter.filter(requestContext);

        verify(activeWorkspaceContext).setWorkspaceId("ws-1");
        verify(requestContext, never()).abortWith(any());
    }

    @Test
    @DisplayName("the membership status comparison ignores case")
    void membershipStatusIsCaseInsensitive() {
        when(requestContext.getHeaderString(ActiveWorkspaceFilter.WORKSPACE_HEADER))
                .thenReturn("ws-1");
        givenCaller("user-1");
        givenMembership("active");

        filter.filter(requestContext);

        verify(activeWorkspaceContext).setWorkspaceId("ws-1");
        verify(requestContext, never()).abortWith(any());
    }
}

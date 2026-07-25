package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.core.audit.AuditService;
import com.amsha.persistence.api.ApiCredentialDto;
import com.amsha.persistence.api.ApiCredentialStorePort;
import com.amsha.persistence.api.IdentityInvitationDto;
import com.amsha.persistence.api.IdentityInvitationStorePort;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.IdentityUserDto;
import com.amsha.persistence.api.WorkspaceMembershipDto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdentityResourceUnitTest {

    @Mock
    IdentityStorePort identityStore;

    @Mock
    ApiCredentialStorePort apiCredentialStore;

    @Mock
    IdentityInvitationStorePort invitationStore;

    @Mock
    ApiKeyRegistry apiKeyRegistry;

    @Mock
    AuditService auditService;

    @Mock
    SecurityContext securityContext;

    @Mock
    Principal principal;

    @InjectMocks
    IdentityResource resource;

    @BeforeEach
    void setUp() {
        resource.securityContext = securityContext;
    }

    @Test
    void testGetMe_Anonymous() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("anonymous");

        Response response = resource.getMe();
        assertEquals(401, response.getStatus());
    }

    @Test
    void testGetMe_NotFound() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("user-1");
        when(identityStore.findUserById("user-1")).thenReturn(Optional.empty());

        Response response = resource.getMe();
        assertEquals(404, response.getStatus());
    }

    @Test
    void testGetMe_Success() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("user-1");
        IdentityUserDto user = new IdentityUserDto("user-1", "User One", "active", Instant.now(), Instant.now());
        when(identityStore.findUserById("user-1")).thenReturn(Optional.of(user));

        WorkspaceMembershipDto mem =
                new WorkspaceMembershipDto("ws-1", "user-1", "OWNER", "ACTIVE", 1, Instant.now(), Instant.now());
        when(identityStore.listMembershipsByUserId("user-1")).thenReturn(List.of(mem));

        Response response = resource.getMe();
        assertEquals(200, response.getStatus());
    }

    @Test
    void testListMembers_Forbidden() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("user-1");
        when(identityStore.findMembership("ws-1", "user-1")).thenReturn(Optional.empty());

        Response response = resource.listMembers("ws-1");
        assertEquals(403, response.getStatus());
    }

    @Test
    void testListMembers_Success() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("user-1");
        WorkspaceMembershipDto mem =
                new WorkspaceMembershipDto("ws-1", "user-1", "OWNER", "ACTIVE", 1, Instant.now(), Instant.now());
        when(identityStore.findMembership("ws-1", "user-1")).thenReturn(Optional.of(mem));
        when(identityStore.listMembershipsByWorkspaceId("ws-1")).thenReturn(List.of(mem));

        Response response = resource.listMembers("ws-1");
        assertEquals(200, response.getStatus());
    }

    @Test
    void testCreateInvitation_ForbiddenNonAdmin() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("user-1");
        WorkspaceMembershipDto mem =
                new WorkspaceMembershipDto("ws-1", "user-1", "MEMBER", "ACTIVE", 1, Instant.now(), Instant.now());
        when(identityStore.findMembership("ws-1", "user-1")).thenReturn(Optional.of(mem));

        Response response =
                resource.createInvitation("ws-1", new IdentityResource.CreateInvitationRequest("MEMBER", null));
        assertEquals(403, response.getStatus());
    }

    @Test
    void testCreateInvitation_Success() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("user-1");
        WorkspaceMembershipDto mem =
                new WorkspaceMembershipDto("ws-1", "user-1", "OWNER", "ACTIVE", 1, Instant.now(), Instant.now());
        when(identityStore.findMembership("ws-1", "user-1")).thenReturn(Optional.of(mem));
        when(apiKeyRegistry.computeDigest(anyString())).thenReturn("digest-123");

        Response response =
                resource.createInvitation("ws-1", new IdentityResource.CreateInvitationRequest("MEMBER", null));
        assertEquals(200, response.getStatus());
        verify(invitationStore).save(any());
    }

    @Test
    void testAcceptInvitation_NotFound() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("user-1");
        when(apiKeyRegistry.computeDigest("token-123")).thenReturn("digest-123");
        when(invitationStore.findByTokenDigest("digest-123")).thenReturn(Optional.empty());

        Response response = resource.acceptInvitation("token-123");
        assertEquals(404, response.getStatus());
    }

    @Test
    void testAcceptInvitation_Success() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("user-1");
        when(apiKeyRegistry.computeDigest("token-123")).thenReturn("digest-123");

        IdentityInvitationDto inv = new IdentityInvitationDto(
                "inv-1",
                "ws-1",
                "MEMBER",
                "digest-123",
                null,
                Instant.now().plusSeconds(3600),
                null,
                null,
                1,
                Instant.now(),
                Instant.now());
        when(invitationStore.findByTokenDigest("digest-123")).thenReturn(Optional.of(inv));
        when(invitationStore.save(any())).thenReturn(true);
        when(identityStore.saveMembership(any())).thenReturn(true);

        Response response = resource.acceptInvitation("token-123");
        assertEquals(200, response.getStatus());
    }

    @Test
    void testRemoveMember_Success() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("owner-1");
        WorkspaceMembershipDto ownerMem =
                new WorkspaceMembershipDto("ws-1", "owner-1", "OWNER", "ACTIVE", 1, Instant.now(), Instant.now());
        WorkspaceMembershipDto targetMem =
                new WorkspaceMembershipDto("ws-1", "member-2", "MEMBER", "ACTIVE", 1, Instant.now(), Instant.now());
        when(identityStore.findMembership("ws-1", "owner-1")).thenReturn(Optional.of(ownerMem));
        when(identityStore.findMembership("ws-1", "member-2")).thenReturn(Optional.of(targetMem));
        when(identityStore.deleteMembershipIfNotLastOwner("ws-1", "member-2")).thenReturn(1);

        Response response = resource.removeMember("ws-1", "member-2");
        assertEquals(200, response.getStatus());
    }

    @Test
    void testTransferOwnership_Success() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("owner-1");
        WorkspaceMembershipDto ownerMem =
                new WorkspaceMembershipDto("ws-1", "owner-1", "OWNER", "ACTIVE", 1, Instant.now(), Instant.now());
        WorkspaceMembershipDto targetMem =
                new WorkspaceMembershipDto("ws-1", "member-2", "MEMBER", "ACTIVE", 1, Instant.now(), Instant.now());
        when(identityStore.findMembership("ws-1", "owner-1")).thenReturn(Optional.of(ownerMem));
        when(identityStore.findMembership("ws-1", "member-2")).thenReturn(Optional.of(targetMem));
        when(identityStore.saveMembership(any())).thenReturn(true);

        Response response = resource.transferOwnership("ws-1", new IdentityResource.TransferOwnerRequest("member-2"));
        assertEquals(200, response.getStatus());
    }

    @Test
    void testRotateCredentials_Success() {
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("owner-1");
        WorkspaceMembershipDto ownerMem =
                new WorkspaceMembershipDto("ws-1", "owner-1", "OWNER", "ACTIVE", 1, Instant.now(), Instant.now());
        when(identityStore.findMembership("ws-1", "owner-1")).thenReturn(Optional.of(ownerMem));
        when(identityStore.listMembershipsByWorkspaceId("ws-1")).thenReturn(List.of(ownerMem));

        ApiCredentialDto cred = new ApiCredentialDto(
                "cred-1", "owner-1", "digest-1", "ADMIN", 1, null, null, Instant.now(), Instant.now());
        when(apiCredentialStore.listByUserId("owner-1")).thenReturn(List.of(cred));

        Response response = resource.rotateWorkspaceCredentials("ws-1");
        assertEquals(200, response.getStatus());
        verify(apiCredentialStore).save(any());
        verify(apiKeyRegistry).unregisterByDigest("digest-1");
    }
}

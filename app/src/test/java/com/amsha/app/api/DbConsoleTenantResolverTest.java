package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.app.db.DbValidationException;
import com.amsha.app.ui.dto.DbQueryRequest;
import com.amsha.core.scope.ActiveWorkspaceContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.api.BootstrapStateStorePort;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.WorkspaceMembershipDto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DbConsoleTenantResolver}, the tenant-isolation boundary of the database console.
 *
 * <p>The resolution order is a five-step fallback chain and each step is a place where a mistake leaks one
 * tenant's rows to another. Every step therefore gets a positive case and a "this step declined" case, and
 * the explicit-workspace path is tested for both membership outcomes.
 */
class DbConsoleTenantResolverTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static <T> Instance<T> resolvable(T value) {
        @SuppressWarnings("unchecked")
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(value);
        return instance;
    }

    private static <T> Instance<T> unresolvable() {
        @SuppressWarnings("unchecked")
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(false);
        return instance;
    }

    private static WorkspaceMembershipDto membership(String workspaceId, String userId, String status) {
        return new WorkspaceMembershipDto(workspaceId, userId, "OWNER", status, 0, NOW, NOW);
    }

    private static BootstrapStateDto state(String ownerId, String workspaceId) {
        return new BootstrapStateDto("bs-1", "CLAIMED", ownerId, workspaceId, "digest", NOW, NOW, 1, NOW);
    }

    private static DbQueryRequest request(String workspaceId, String sessionId) {
        return new DbQueryRequest("SELECT 1", 10, workspaceId, sessionId);
    }

    private static IdentityStorePort storeWithMembership(String workspaceId, String userId, String status) {
        IdentityStorePort store = mock(IdentityStorePort.class);
        when(store.findMembership(workspaceId, userId))
                .thenReturn(Optional.of(membership(workspaceId, userId, status)));
        return store;
    }

    // ---------- explicit workspace id ----------

    @Test
    @DisplayName("an explicit workspace id is trimmed and returned when the identity store is not resolvable")
    void explicitWorkspaceIdWinsWithoutAnIdentityStore() {
        String resolved = DbConsoleTenantResolver.resolveWorkspaceId(
                request("  ws-explicit  ", null), "user-1", unresolvable(), unresolvable(), unresolvable());

        assertEquals("ws-explicit", resolved);
    }

    @Test
    @DisplayName("an explicit workspace id is accepted for an active member")
    void explicitWorkspaceIdAcceptedForActiveMember() {
        String resolved = DbConsoleTenantResolver.resolveWorkspaceId(
                request("ws-explicit", null),
                "user-1",
                resolvable(storeWithMembership("ws-explicit", "user-1", "ACTIVE")),
                unresolvable(),
                unresolvable());

        assertEquals("ws-explicit", resolved);
    }

    @Test
    @DisplayName("an explicit workspace id is rejected for a non-member")
    void explicitWorkspaceIdRejectedForNonMember() {
        IdentityStorePort store = mock(IdentityStorePort.class);
        when(store.findMembership("ws-explicit", "user-1")).thenReturn(Optional.empty());

        DbValidationException ex = assertThrows(
                DbValidationException.class,
                () -> DbConsoleTenantResolver.resolveWorkspaceId(
                        request("ws-explicit", null), "user-1", resolvable(store), unresolvable(), unresolvable()));

        assertTrue(ex.getMessage().contains("not an active member"), ex.getMessage());
        assertTrue(ex.getMessage().contains("ws-explicit"), ex.getMessage());
    }

    @Test
    @DisplayName("an explicit workspace id is rejected for a suspended member")
    void explicitWorkspaceIdRejectedForSuspendedMember() {
        assertThrows(
                DbValidationException.class,
                () -> DbConsoleTenantResolver.resolveWorkspaceId(
                        request("ws-explicit", null),
                        "user-1",
                        resolvable(storeWithMembership("ws-explicit", "user-1", "SUSPENDED")),
                        unresolvable(),
                        unresolvable()));
    }

    @Test
    @DisplayName("a blank explicit workspace id is treated as absent")
    void blankExplicitWorkspaceIdIsTreatedAsAbsent() {
        ActiveWorkspaceContext context = mock(ActiveWorkspaceContext.class);
        when(context.workspaceId()).thenReturn(Optional.of("ws-ctx"));

        String resolved = DbConsoleTenantResolver.resolveWorkspaceId(
                request("   ", null), "user-1", unresolvable(), resolvable(context), unresolvable());

        assertEquals("ws-ctx", resolved);
    }

    // ---------- request-scoped context ----------

    @Test
    @DisplayName("the request-scoped workspace context is used when it has a workspace")
    void requestScopedContextIsUsed() {
        ActiveWorkspaceContext context = mock(ActiveWorkspaceContext.class);
        when(context.workspaceId()).thenReturn(Optional.of("  ws-ctx  "));

        String resolved = DbConsoleTenantResolver.resolveWorkspaceId(
                request(null, null), "user-1", unresolvable(), resolvable(context), unresolvable());

        assertEquals("ws-ctx", resolved);
    }

    @Test
    @DisplayName("an empty request-scoped workspace falls through")
    void emptyRequestScopedContextFallsThrough() {
        ActiveWorkspaceContext context = mock(ActiveWorkspaceContext.class);
        when(context.workspaceId()).thenReturn(Optional.empty());

        String resolved = DbConsoleTenantResolver.resolveWorkspaceId(
                request(null, null), "user-1", unresolvable(), resolvable(context), unresolvable());

        assertNull(resolved);
    }

    @Test
    @DisplayName("a blank request-scoped workspace falls through")
    void blankRequestScopedContextFallsThrough() {
        ActiveWorkspaceContext context = mock(ActiveWorkspaceContext.class);
        when(context.workspaceId()).thenReturn(Optional.of("   "));

        String resolved = DbConsoleTenantResolver.resolveWorkspaceId(
                request(null, null), "user-1", unresolvable(), resolvable(context), unresolvable());

        assertNull(resolved);
    }

    // ---------- bound scoped value ----------

    @Test
    @DisplayName("the bound ACTIVE_WORKSPACE_ID is used when nothing else resolves")
    void boundScopedValueIsUsed() {
        WorkspaceContext.runWithActiveWorkspace(
                "ws-bound",
                () -> assertEquals(
                        "ws-bound",
                        DbConsoleTenantResolver.resolveWorkspaceId(
                                request(null, null), "user-1", unresolvable(), unresolvable(), unresolvable())));
    }

    @Test
    @DisplayName("a blank bound ACTIVE_WORKSPACE_ID is skipped")
    void blankBoundScopedValueIsSkipped() {
        WorkspaceContext.runWithActiveWorkspace(
                "   ",
                () -> assertNull(DbConsoleTenantResolver.resolveWorkspaceId(
                        request(null, null), "user-1", unresolvable(), unresolvable(), unresolvable())));
    }

    // ---------- membership lookup ----------

    @Test
    @DisplayName("the first active membership is used, skipping inactive ones")
    void membershipLookupSkipsInactiveEntries() {
        IdentityStorePort store = mock(IdentityStorePort.class);
        when(store.listMembershipsByUserId("user-1"))
                .thenReturn(List.of(
                        membership("ws-old", "user-1", "INACTIVE"), membership("  ws-live  ", "user-1", "ACTIVE")));

        String resolved = DbConsoleTenantResolver.resolveWorkspaceId(
                request(null, null), "user-1", resolvable(store), unresolvable(), unresolvable());

        assertEquals("ws-live", resolved);
    }

    @Test
    @DisplayName("the membership DTO rejects a blank workspace id, so the resolver's blank guard is unreachable")
    void membershipDtoRejectsBlankWorkspaceId() {
        // WorkspaceMembershipDto's compact constructor forbids a null/blank workspaceId, which makes the
        // `m.workspaceId() != null && !m.workspaceId().isBlank()` guard in resolveMembershipWorkspace dead.
        assertThrows(IllegalArgumentException.class, () -> membership("   ", "user-1", "ACTIVE"));
        assertThrows(IllegalArgumentException.class, () -> membership(null, "user-1", "ACTIVE"));
    }

    @Test
    @DisplayName("membership lookup returns null when there is no active membership")
    void membershipLookupReturnsNullWithoutAnActiveEntry() {
        IdentityStorePort store = mock(IdentityStorePort.class);
        when(store.listMembershipsByUserId("user-1")).thenReturn(List.of(membership("ws-old", "user-1", "INACTIVE")));

        assertNull(DbConsoleTenantResolver.resolveMembershipWorkspace("user-1", resolvable(store)));
    }

    @Test
    @DisplayName("membership lookup tolerates a null list and a failing store")
    void membershipLookupToleratesNullAndFailure() {
        IdentityStorePort nullList = mock(IdentityStorePort.class);
        when(nullList.listMembershipsByUserId("user-1")).thenReturn(null);
        assertNull(DbConsoleTenantResolver.resolveMembershipWorkspace("user-1", resolvable(nullList)));

        IdentityStorePort failing = mock(IdentityStorePort.class);
        when(failing.listMembershipsByUserId("user-1")).thenThrow(new IllegalStateException("db down"));
        assertNull(
                DbConsoleTenantResolver.resolveMembershipWorkspace("user-1", resolvable(failing)),
                "a failing membership lookup must degrade to null, not propagate");
    }

    @Test
    @DisplayName("membership lookup declines without a resolvable store or a caller id")
    void membershipLookupDeclinesWithoutStoreOrUser() {
        assertNull(DbConsoleTenantResolver.resolveMembershipWorkspace("user-1", unresolvable()));
        assertNull(DbConsoleTenantResolver.resolveMembershipWorkspace(null, resolvable(mock(IdentityStorePort.class))));
        assertNull(DbConsoleTenantResolver.resolveMembershipWorkspace("user-1", null));
    }

    // ---------- bootstrap fallback ----------

    @Test
    @DisplayName("the bootstrap workspace is used when the caller is its owner")
    void bootstrapWorkspaceIsUsedForTheOwner() {
        BootstrapStateStorePort bootstrap = mock(BootstrapStateStorePort.class);
        when(bootstrap.getState()).thenReturn(Optional.of(state("user-1", "ws-boot")));

        String resolved = DbConsoleTenantResolver.resolveWorkspaceId(
                request(null, null), "user-1", unresolvable(), unresolvable(), resolvable(bootstrap));

        assertEquals("ws-boot", resolved);
    }

    @Test
    @DisplayName("the bootstrap workspace requires an active membership for a non-owner")
    void bootstrapWorkspaceRequiresMembershipForNonOwner() {
        BootstrapStateStorePort bootstrap = mock(BootstrapStateStorePort.class);
        when(bootstrap.getState()).thenReturn(Optional.of(state("someone-else", "ws-boot")));

        assertNull(DbConsoleTenantResolver.resolveBootstrapWorkspace("user-1", resolvable(bootstrap), unresolvable()));
    }

    @Test
    @DisplayName("the bootstrap workspace is granted to a non-owner who is an active member")
    void bootstrapWorkspaceIsGrantedToAnActiveMember() {
        BootstrapStateStorePort bootstrap = mock(BootstrapStateStorePort.class);
        when(bootstrap.getState()).thenReturn(Optional.of(state("someone-else", "  ws-boot  ")));

        String resolved = DbConsoleTenantResolver.resolveBootstrapWorkspace(
                "user-1", resolvable(bootstrap), resolvable(storeWithMembership("ws-boot", "user-1", "ACTIVE")));

        assertEquals("ws-boot", resolved);
    }

    @Test
    @DisplayName("bootstrap resolution declines without a resolvable store, state, or workspace id")
    void bootstrapResolutionDeclines() {
        assertNull(DbConsoleTenantResolver.resolveBootstrapWorkspace("user-1", unresolvable(), unresolvable()));

        BootstrapStateStorePort empty = mock(BootstrapStateStorePort.class);
        when(empty.getState()).thenReturn(Optional.empty());
        assertNull(DbConsoleTenantResolver.resolveBootstrapWorkspace("user-1", resolvable(empty), unresolvable()));

        BootstrapStateStorePort blank = mock(BootstrapStateStorePort.class);
        when(blank.getState()).thenReturn(Optional.of(state("user-1", "   ")));
        assertNull(DbConsoleTenantResolver.resolveBootstrapWorkspace("user-1", resolvable(blank), unresolvable()));

        BootstrapStateStorePort nullWs = mock(BootstrapStateStorePort.class);
        when(nullWs.getState()).thenReturn(Optional.of(state("user-1", null)));
        assertNull(DbConsoleTenantResolver.resolveBootstrapWorkspace("user-1", resolvable(nullWs), unresolvable()));
    }

    @Test
    @DisplayName("a failing bootstrap store degrades to null")
    void bootstrapResolutionSwallowsFailures() {
        BootstrapStateStorePort failing = mock(BootstrapStateStorePort.class);
        when(failing.getState()).thenThrow(new IllegalStateException("db down"));

        assertNull(DbConsoleTenantResolver.resolveBootstrapWorkspace("user-1", resolvable(failing), unresolvable()));
    }

    // ---------- membership predicate ----------

    @Test
    @DisplayName("the membership predicate is true only for an ACTIVE status, ignoring case")
    void membershipPredicateAcceptsActive() {
        assertTrue(DbConsoleTenantResolver.isActiveMember(
                "ws-1", "user-1", resolvable(storeWithMembership("ws-1", "user-1", "ACTIVE"))));
        assertTrue(DbConsoleTenantResolver.isActiveMember(
                "ws-1", "user-1", resolvable(storeWithMembership("ws-1", "user-1", "active"))));
    }

    @Test
    @DisplayName("the membership predicate is false for a non-active status")
    void membershipPredicateRejectsNonActive() {
        assertFalse(DbConsoleTenantResolver.isActiveMember(
                "ws-1", "user-1", resolvable(storeWithMembership("ws-1", "user-1", "PENDING"))));
    }

    @Test
    @DisplayName("the membership predicate is false without a resolvable store, a user, or a membership row")
    void membershipPredicateDeclines() {
        assertFalse(DbConsoleTenantResolver.isActiveMember("ws-1", "user-1", unresolvable()));
        assertFalse(DbConsoleTenantResolver.isActiveMember(
                "ws-1", null, resolvable(storeWithMembership("ws-1", "user-1", "ACTIVE"))));
        assertFalse(DbConsoleTenantResolver.isActiveMember("ws-1", "user-1", null));

        IdentityStorePort noRow = mock(IdentityStorePort.class);
        when(noRow.findMembership("ws-1", "user-1")).thenReturn(Optional.empty());
        assertFalse(DbConsoleTenantResolver.isActiveMember("ws-1", "user-1", resolvable(noRow)));
    }

    @Test
    @DisplayName("a failing membership lookup degrades to false")
    void membershipPredicateSwallowsFailures() {
        IdentityStorePort failing = mock(IdentityStorePort.class);
        when(failing.findMembership("ws-1", "user-1")).thenThrow(new IllegalStateException("db down"));

        assertFalse(DbConsoleTenantResolver.isActiveMember("ws-1", "user-1", resolvable(failing)));
    }

    // ---------- session id ----------

    @Test
    @DisplayName("the session id comes from the request when present")
    void sessionIdComesFromTheRequest() {
        assertEquals("sess-1", DbConsoleTenantResolver.resolveSessionId(request(null, "  sess-1  ")));
    }

    @Test
    @DisplayName("a blank or absent session id resolves to null when nothing is bound")
    void sessionIdIsNullWhenNothingIsBound() {
        assertNull(DbConsoleTenantResolver.resolveSessionId(request(null, "   ")));
        assertNull(DbConsoleTenantResolver.resolveSessionId(null));
    }

    @Test
    @DisplayName("a bound SESSION_ID is used when the request has none")
    void sessionIdUsesTheBoundScopedValue() throws Exception {
        String resolved = ScopedValue.where(WorkspaceContext.SESSION_ID, "sess-scoped")
                .call(() -> DbConsoleTenantResolver.resolveSessionId(request(null, null)));

        assertEquals("sess-scoped", resolved);
    }
}

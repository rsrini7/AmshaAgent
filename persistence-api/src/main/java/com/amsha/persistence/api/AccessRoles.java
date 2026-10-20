package com.amsha.persistence.api;

/**
 * Canonical access-role tokens shared by workspace membership, API-key
 * authorization, and admin-endpoint guards.
 * <p>
 * These strings are persisted (for example in {@link WorkspaceMembershipDto#role()})
 * and compared in authorization decisions, so a rename in one place while another
 * copy is missed would silently grant or deny access. Reference these constants
 * instead of retyping the literals.
 */
public final class AccessRoles {

    /** Workspace owner: full control, including role assignment. */
    public static final String OWNER = "OWNER";

    /** Workspace administrator: manages members below the owner. */
    public static final String ADMIN = "ADMIN";

    /** Regular workspace member. */
    public static final String MEMBER = "MEMBER";

    /**
     * The internal operator identity. Used as the internal API-key name, the
     * principal user id, and the security role checked by admin guards
     * ({@code isUserInRole("internal")}) — all three are the same token.
     */
    public static final String INTERNAL = "internal";

    private AccessRoles() {
        // Prevent instantiation
    }
}

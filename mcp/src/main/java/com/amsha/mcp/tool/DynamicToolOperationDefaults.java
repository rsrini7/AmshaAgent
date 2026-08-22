package com.amsha.mcp.tool;

import com.amsha.core.tools.ToolCategory;
import com.amsha.security.api.ToolOperation;

/**
 * Registration-site defaults for MCP dynamic tools.
 *
 * <p>The guard never infers sensitivity from {@link ToolCategory}; these defaults only
 * populate the explicit {@code requiredOperation} field at register time.
 */
public final class DynamicToolOperationDefaults {

    private DynamicToolOperationDefaults() {}

    /**
     * Default operation for a category at MCP registration.
     *
     * <p>{@code COMPUTE → EXECUTE_SHELL}: today's dynamic compute path is host/sandbox
     * execution equivalent. Future non-shell compute must set a different
     * {@link ToolOperation} explicitly at register time.
     *
     * <p>{@code DATABASE}/{@code INTERNAL} have no safe default — callers must pass an
     * explicit operation.
     */
    public static ToolOperation requireFor(ToolCategory category) {
        if (category == null) {
            throw new IllegalArgumentException("category must not be null");
        }
        return switch (category) {
            case NETWORK -> ToolOperation.NETWORK_OUTBOUND;
            // Today's dynamic compute path is host/sandbox execution equivalent.
            case COMPUTE -> ToolOperation.EXECUTE_SHELL;
            case DATABASE, INTERNAL ->
                throw new IllegalArgumentException(
                        "category " + category + " requires an explicit ToolOperation at registration");
        };
    }
}

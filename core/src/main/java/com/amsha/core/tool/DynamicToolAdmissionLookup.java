package com.amsha.core.tool;

import java.util.Optional;

/**
 * Resolves validated admission metadata for dynamically registered tools.
 *
 * <p>Sole dynamic authority for {@code ToolInvocationGuard}: absence, CDI failure, or
 * invalid metadata must fail closed (Block), never Allow.
 */
public interface DynamicToolAdmissionLookup {
    Optional<DynamicToolAdmission> findAdmission(String toolName);
}

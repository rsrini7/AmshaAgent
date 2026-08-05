package com.amsha.mcp.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

import com.amsha.core.tool.DynamicToolAdmission;
import com.amsha.core.tool.DynamicToolAdmissionLookup;

import io.quarkus.arc.Unremovable;

/**
 * Projects {@link DynamicToolCapabilityRegistry} entries into validated admission metadata.
 *
 * <p>{@code @Unremovable} keeps the bean available under native/optional discovery so the
 * guard's unsatisfied-Instance path is only hit when MCP is truly absent.
 */
@ApplicationScoped
@Unremovable
public class DynamicToolAdmissionLookupImpl implements DynamicToolAdmissionLookup {

    @Inject
    DynamicToolCapabilityRegistry registry;

    @Override
    public Optional<DynamicToolAdmission> findAdmission(String toolName) {
        return registry.find(toolName)
                .map(cap -> new DynamicToolAdmission(
                        cap.toolName(), cap.category(), cap.requiresApproval(), cap.requiredOperation()));
    }
}

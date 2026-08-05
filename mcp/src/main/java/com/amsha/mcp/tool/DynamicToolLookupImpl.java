package com.amsha.mcp.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.amsha.core.tool.DynamicToolAdmissionLookup;
import com.amsha.core.tool.DynamicToolLookup;

@ApplicationScoped
public class DynamicToolLookupImpl implements DynamicToolLookup {
    @Inject
    DynamicToolAdmissionLookup admissionLookup;

    @Override
    public boolean isDynamicToolAllowed(String toolName) {
        // Keep legacy callers on the same validated admission authority used by the guard.
        return admissionLookup != null
                && admissionLookup.findAdmission(toolName).isPresent();
    }
}

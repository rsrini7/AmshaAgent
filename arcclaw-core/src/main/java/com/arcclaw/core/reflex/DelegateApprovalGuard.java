package com.arcclaw.core.reflex;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Checks if a userId is a configured delegation admin. §7.33
 */
@ApplicationScoped
public class DelegateApprovalGuard {

    @ConfigProperty(name = "arcclaw.hitl.delegate-approver-ids", defaultValue = "")
    private Optional<List<String>> delegateApproverIds;

    public boolean isDelegate(String userId) {
        if (userId == null) return false;
        return delegateApproverIds.map(ids -> ids.contains(userId)).orElse(false);
    }

    /** For test purposes (§P75-H) */
    public void setDelegateApproverIds(List<String> ids) {
        this.delegateApproverIds = Optional.ofNullable(ids);
    }
}

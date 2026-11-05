package com.amsha.app.tool;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import com.amsha.core.audit.SecurityEventType;
import com.amsha.persistence.api.AuditChainFinalizer;
import com.amsha.persistence.api.AuditEventDto;
import com.amsha.persistence.sqlite.SqliteAuditEventStoreAdapter;

import org.jdbi.v3.core.Jdbi;

/** Test-only SQLite adapter that rejects the admission acknowledgement append. */
@ApplicationScoped
@Alternative
public class FailingAttemptAdmissionAuditStore extends SqliteAuditEventStoreAdapter {
    @Inject
    public FailingAttemptAdmissionAuditStore(Jdbi jdbi) {
        super(jdbi);
    }

    @Override
    public AuditEventDto appendChained(AuditEventDto seed, AuditChainFinalizer finalizer) {
        if (SecurityEventType.TOOL_ATTEMPT_ADMITTED.name().equals(seed.eventType())) {
            throw new IllegalStateException("Injected admission audit acknowledgement failure.");
        }
        return super.appendChained(seed, finalizer);
    }
}

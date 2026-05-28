package com.arcclaw.core.audit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

import com.arcclaw.core.domain.model.AuditEvent;
import com.arcclaw.core.domain.model.AuditEventRecord;
import com.arcclaw.persistence.api.AuditEventStorePort;

@ApplicationScoped
public class AuditVerifier {

    @Inject
    AuditEventStorePort auditStore;

    @Inject
    AuditHashUtil hashUtil;

    public VerificationResult verifyChain(String sessionId) {
        List<AuditEvent> events = auditStore.findBySessionId(sessionId).stream()
                .map(AuditEventRecord::fromDto)
                .map(e -> (AuditEvent) e)
                .toList();
        if (events.isEmpty()) {
            return new VerificationResult(true, null, 0);
        }

        String expectedPrevHash = hashUtil.sha256hex("GENESIS:" + sessionId);

        for (AuditEvent event : events) {
            if (!event.getPrevHash().equals(expectedPrevHash)) {
                return new VerificationResult(false, event.getId().toString(), events.size());
            }

            String payload = event.computePayloadStr();
            String computedHmac = hashUtil.hmacHex(event.getPrevHash() + "|" + payload);

            if (!computedHmac.equals(event.getHmacHash())) {
                return new VerificationResult(false, event.getId().toString(), events.size());
            }

            expectedPrevHash = event.getHmacHash();
        }

        return new VerificationResult(true, null, events.size());
    }

    public record VerificationResult(boolean valid, String brokenAtId, int totalRows) {}
}

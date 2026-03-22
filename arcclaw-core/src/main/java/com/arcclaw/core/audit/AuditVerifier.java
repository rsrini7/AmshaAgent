package com.arcclaw.core.audit;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AuditVerifier {

    @Inject
    AuditEventRepository auditEventRepository;

    @Inject
    AuditHashUtil hashUtil;

    public VerificationResult verifyChain(String sessionId) {
        List<AuditEvent> events = auditEventRepository.findBySessionId(sessionId);
        if (events.isEmpty()) {
            return new VerificationResult(true, null, 0);
        }

        String expectedPrevHash = hashUtil.sha256hex("GENESIS:" + sessionId);

        for (AuditEvent event : events) {
            // 1. Verify link to previous row (or genesis)
            if (!event.prevHash.equals(expectedPrevHash)) {
                return new VerificationResult(false, event.id.toString(), events.size());
            }

            // 2. Verify row integrity
            String payload = event.computePayloadStr();
            String computedHmac = hashUtil.hmacHex(event.prevHash + "|" + payload);
            
            if (!computedHmac.equals(event.hmacHash)) {
                return new VerificationResult(false, event.id.toString(), events.size());
            }

            // 3. Setup expected hash for next iteration
            expectedPrevHash = event.hmacHash;
        }

        return new VerificationResult(true, null, events.size());
    }


    public record VerificationResult(boolean valid, String brokenAtId, int totalRows) {}
}

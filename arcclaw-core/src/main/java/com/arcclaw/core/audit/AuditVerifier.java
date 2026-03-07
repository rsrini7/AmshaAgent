package com.arcclaw.core.audit;

import com.arcclaw.core.security.CryptoConstants;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

@ApplicationScoped
public class AuditVerifier {

    @Inject
    AuditEventRepository auditEventRepository;

    @ConfigProperty(name = "arcclaw.audit.hmac-key", 
                defaultValue = "dev-audit-hmac-key-min-32-bytes-00000")
    String hmacKey;

    public VerificationResult verifyChain(String sessionId) {
        List<AuditEvent> events = auditEventRepository.findBySessionId(sessionId);
        if (events.isEmpty()) {
            return new VerificationResult(true, null, 0);
        }

        String expectedPrevHash = AuditHashUtil.sha256hex("GENESIS:" + sessionId);

        for (AuditEvent event : events) {
            // 1. Verify link to previous row (or genesis)
            if (!event.prevHash.equals(expectedPrevHash)) {
                return new VerificationResult(false, event.id.toString(), events.size());
            }

            // 2. Verify row integrity
            String payload = event.computePayloadStr();
            String computedHmac = computeHmac(event.prevHash + "|" + payload);
            
            if (!computedHmac.equals(event.hmacHash)) {
                return new VerificationResult(false, event.id.toString(), events.size());
            }

            // 3. Setup expected hash for next iteration
            expectedPrevHash = event.hmacHash;
        }

        return new VerificationResult(true, null, events.size());
    }

    private String computeHmac(String data) {
        try {
            Mac mac = Mac.getInstance(CryptoConstants.HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(hmacKey.getBytes(StandardCharsets.UTF_8), CryptoConstants.HMAC_SHA256);
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC", e);
        }
    }

    public record VerificationResult(boolean valid, String brokenAtId, int totalRows) {}
}

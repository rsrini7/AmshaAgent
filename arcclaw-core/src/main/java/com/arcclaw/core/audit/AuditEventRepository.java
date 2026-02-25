package com.arcclaw.core.audit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

@ApplicationScoped
public class AuditEventRepository {

    @Inject
    EntityManager em;

    @ConfigProperty(name = "arcclaw.audit.hmac-key", 
                defaultValue = "dev-audit-hmac-key-min-32-bytes-00000")
    String hmacKey;

    /**
     * Persists an AuditEvent and computes its HMAC chain fields.
     * This method is deliberately NOT annotated with @Transactional.
     * It relies on the caller (AuditService) to provide the appropriate
     * REQUIRED transaction boundary.
     */
    public void record(AuditEvent event) {
        String prevHash = findLatestHash(event.sessionId);
        if (prevHash == null) {
            prevHash = AuditHashUtil.sha256hex("GENESIS:" + event.sessionId);
        }
        event.prevHash = prevHash;
        
        String payload = event.computePayloadStr();
        event.hmacHash = computeHmac(prevHash + "|" + payload);
        
        em.persist(event);
    }

    /**
     * Retrieves the latest HMAC hash for a specific session to continue the chain.
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public String findLatestHash(String sessionId) {
        List<String> results = em.createQuery(
                "SELECT a.hmacHash FROM AuditEvent a WHERE a.sessionId = :sid ORDER BY a.timestamp DESC, a.id DESC",
                String.class)
                .setParameter("sid", sessionId)
                .setMaxResults(1)
                .getResultList();
        
        return results.isEmpty() ? null : results.get(0);
    }


    @Transactional(Transactional.TxType.SUPPORTS)
    public List<AuditEvent> findBySessionId(String sessionId) {
        return em.createQuery(
                "SELECT a FROM AuditEvent a WHERE a.sessionId = :sid ORDER BY a.timestamp ASC, a.id ASC",
                AuditEvent.class)
                .setParameter("sid", sessionId)
                .getResultList();
    }

    private String computeHmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(hmacKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC", e);
        }
    }
}

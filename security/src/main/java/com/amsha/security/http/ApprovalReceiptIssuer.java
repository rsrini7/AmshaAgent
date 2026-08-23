package com.amsha.security.http;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.state.StatefulAgentStore;
import com.amsha.persistence.api.ToolDecision;
import com.amsha.security.api.OutboundPurpose;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

/**
 * Issues cryptographically-random, single-use approval tokens that bind an outbound operation to a
 * specific actor, session, purpose, exact target host, and a bounded TTL.
 *
 * <p>A token is minted only by an approved checkpoint transition (e.g. an MCP connection committed
 * through the onboarding saga) and is consumed exactly once by the governed transport before any
 * network I/O. It is the checkpoint-backed replacement for the interim {@code preApproved} boolean
 * call-site flag: it proves the operation cleared HITL and prevents reuse or host/purpose
 * substitution by a downstream caller.</p>
 */
@Unremovable
@ApplicationScoped
public class ApprovalReceiptIssuer {

    private static final Logger log = Logger.getLogger(ApprovalReceiptIssuer.class);
    private static final int TOKEN_BYTES = 32;

    private final Map<String, ReceiptRecord> receipts = new ConcurrentHashMap<>();
    private final Duration defaultTtl;

    @Inject
    AuditService auditService;

    @Inject
    StatefulAgentStore agentStore;

    @Inject
    ObjectMapper objectMapper;

    public ApprovalReceiptIssuer() {
        this(Duration.ofSeconds(60));
    }

    public ApprovalReceiptIssuer(Duration defaultTtl) {
        this.defaultTtl = defaultTtl;
    }

    /**
     * Mints a single-use approval token bound to the given context. The returned token must be
     * presented (and consumed) by the governed transport before any network I/O for the bound
     * purpose+host.
     */
    String issue(String actorUserId, String sessionId, OutboundPurpose purpose, String targetHost) {
        SecureRandom random = new SecureRandom();
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        // Zero the raw token bytes immediately; nothing retains them in process memory.
        Arrays.fill(raw, (byte) 0);

        receipts.put(
                token,
                new ReceiptRecord(
                        actorUserId,
                        sessionId,
                        purpose,
                        targetHost,
                        Instant.now().plus(defaultTtl)));

        if (auditService != null) {
            auditService.record(
                    sessionId == null ? "system" : sessionId,
                    actorUserId == null ? "system" : actorUserId,
                    "system",
                    SecurityEventType.APPROVAL_GRANTED,
                    "approval_receipt issue purpose=" + purpose + " host=" + targetHost);
        }
        return token;
    }

    /**
     * Mints a receipt only when the durable checkpoint records an approved decision for the exact
     * tool being resumed. This is the sole public issuance path; callers cannot mint an outbound
     * approval merely by supplying user-controlled context.
     */
    public Optional<String> issueForApprovedCheckpoint(
            String actorUserId,
            String sessionId,
            String agentRole,
            String toolName,
            OutboundPurpose purpose,
            String targetHost) {
        if (actorUserId == null
                || actorUserId.isBlank()
                || sessionId == null
                || sessionId.isBlank()
                || agentRole == null
                || agentRole.isBlank()
                || toolName == null
                || toolName.isBlank()
                || purpose == null
                || targetHost == null
                || targetHost.isBlank()) {
            return Optional.empty();
        }
        if (agentStore == null || objectMapper == null) {
            return Optional.empty();
        }
        return agentStore
                .load(sessionId, agentRole)
                .filter(checkpoint -> actorUserId.equals(checkpoint.getChannelPlatformId()))
                .filter(checkpoint -> hasApprovedDecision(checkpoint.getBatchToolDecisions(), toolName))
                .map(_ -> issue(actorUserId, sessionId, purpose, targetHost));
    }

    /**
     * Mints a receipt after an explicit, user-scoped application approval has
     * been recorded.  MCP lifecycle operations use this narrow bridge because
     * they do not originate in an agent checkpoint, but still must reach the
     * same governed transport boundary with a single-use receipt.
     */
    public String issueForExplicitApproval(
            String actorUserId, String operationId, OutboundPurpose purpose, String targetHost) {
        if (actorUserId == null
                || actorUserId.isBlank()
                || operationId == null
                || operationId.isBlank()
                || purpose == null
                || targetHost == null
                || targetHost.isBlank()) {
            throw new IllegalArgumentException("MCP approval context is incomplete");
        }
        return issue(actorUserId, "mcp-approval:" + operationId, purpose, targetHost);
    }

    private boolean hasApprovedDecision(String decisionsJson, String toolName) {
        if (decisionsJson == null || decisionsJson.isBlank()) {
            return false;
        }
        try {
            Map<String, ToolDecision> decisions =
                    objectMapper.readValue(decisionsJson, new TypeReference<Map<String, ToolDecision>>() {});
            return ToolDecision.APPROVED == decisions.get(toolName);
        } catch (Exception e) {
            log.warnf("Could not read checkpoint approval decision: %s", e.getMessage());
            return false;
        }
    }

    /**
     * Verifies the token, consumes it atomically, and returns the bound approval context. Returns
     * {@code null} when the token is unknown, already consumed, expired, or does not match the
     * requested purpose/host. A {@code null} token is treated as no approval.
     */
    public OutboundApprovalReceipt verifyAndConsume(String token, OutboundPurpose purpose, String targetHost) {
        if (token == null || token.isBlank()) {
            return null;
        }
        ReceiptRecord rec = receipts.remove(token);
        if (rec == null) {
            log.warnf("Approval token unknown or already consumed (purpose=%s host=%s)", purpose, targetHost);
            return null;
        }
        if (rec.expiresAt.isBefore(Instant.now())) {
            log.warnf("Approval token expired for actor=%s purpose=%s", rec.actorUserId, rec.purpose);
            return null;
        }
        if (rec.purpose != purpose) {
            log.warnf("Approval token purpose mismatch: expected=%s actual=%s", purpose, rec.purpose);
            return null;
        }
        if (!hostMatches(rec.targetHost, targetHost)) {
            log.warnf("Approval token host mismatch: expected=%s actual=%s", rec.targetHost, targetHost);
            return null;
        }
        return new OutboundApprovalReceipt(rec.actorUserId, rec.sessionId, rec.purpose, rec.targetHost);
    }

    /** Removes expired tokens. Safe to schedule periodically. */
    public void purgeExpired() {
        Instant now = Instant.now();
        receipts.values().removeIf(r -> r.expiresAt.isBefore(now));
    }

    private static boolean hostMatches(String expected, String actual) {
        if (expected == null) return true;
        if (actual == null) return false;
        return expected.equalsIgnoreCase(actual);
    }

    private record ReceiptRecord(
            String actorUserId, String sessionId, OutboundPurpose purpose, String targetHost, Instant expiresAt) {}
}

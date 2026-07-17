package com.amsha.core.security.http;

import com.amsha.core.security.OutboundPurpose;

/**
 * Output of a successful {@link ApprovalReceiptIssuer#verifyAndConsume(String, OutboundPurpose, String)}.
 * Carries the bound approval context so the governed transport can attribute the authorized operation
 * without re-deriving the actor or purpose from the (untrusted) caller.
 */
public record OutboundApprovalReceipt(
        String approvedByUserId, String sessionId, OutboundPurpose purpose, String targetHost) {}

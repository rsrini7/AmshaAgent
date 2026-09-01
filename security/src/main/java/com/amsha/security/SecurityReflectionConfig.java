package com.amsha.security;

import com.amsha.security.http.ApprovalReceiptIssuer;
import com.amsha.security.http.DnsRevalidationResult;
import com.amsha.security.http.GovernedHttpClient;
import com.amsha.security.http.GovernedTransportService;
import com.amsha.security.http.OutboundApprovalReceipt;
import com.amsha.security.http.OutboundHttpException;
import com.amsha.security.media.GovernedMediaDownloadService;
import com.amsha.security.media.MediaSecurityValidator;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * GraalVM Native Image reflection configuration for amsha-security.
 * Governed-HTTP types moved here from core (extract-core-subsystems S7); their
 * entries were relocated from {@code CoreReflectionConfig}. The bootstrap credential
 * records are REST/Jackson-facing and are registered here in their owning module
 * (previously misregistered in {@code OnboardingReflectionConfig}).
 */
@RegisterForReflection(
        targets = {
            ApprovalReceiptIssuer.class,
            OutboundApprovalReceipt.class,
            GovernedHttpClient.class,
            GovernedTransportService.class,
            OutboundHttpException.class,
            DnsRevalidationResult.class,
            PreparedBootstrapCredential.class,
            IssuedCredential.class,
            MediaSecurityValidator.class,
            GovernedMediaDownloadService.class
        },
        classNames = {"com.amsha.security.media.GovernedTelegramMediaResolver"})
public class SecurityReflectionConfig {}

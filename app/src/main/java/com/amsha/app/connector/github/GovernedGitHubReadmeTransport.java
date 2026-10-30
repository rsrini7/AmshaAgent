package com.amsha.app.connector.github;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.core.security.http.GovernedHttpClient;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;

/** Audits and validates each GitHub request before governed outbound HTTP is performed. */
@ApplicationScoped
public class GovernedGitHubReadmeTransport implements GitHubReadmeTransport {

    private final OutboundHttpPolicyService outboundPolicy;
    private final SecretVault secretVault;
    private final AuditService auditService;

    @Inject
    public GovernedGitHubReadmeTransport(
            OutboundHttpPolicyService outboundPolicy, SecretVault secretVault, AuditService auditService) {
        this.outboundPolicy = outboundPolicy;
        this.secretVault = secretVault;
        this.auditService = auditService;
    }

    @Override
    public GitHubHttpResponse send(HttpRequest request, String actorUserId, String operation) {
        auditService.recordMandatory(
                null,
                actorUserId,
                "github-readme-connector",
                SecurityEventType.EXTERNAL_SOURCE_EGRESS_REQUESTED,
                "host=api.github.com purpose=github_read operation=" + operation);
        HttpResponse<String> response = GovernedHttpClient.builder(outboundPolicy)
                .vault(secretVault)
                .purpose(OutboundPurpose.TOOL_FETCH)
                .actor(actorUserId)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new GitHubHttpResponse(
                response.statusCode(),
                response.headers().firstValue("content-type").orElse(""),
                response.headers().firstValue("retry-after").orElse(""),
                response.headers().firstValue("x-ratelimit-remaining").orElse(""),
                response.body());
    }
}

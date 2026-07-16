package com.amsha.app.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.security.ExternalIdentityProvider;
import com.amsha.core.security.ExternalUserProfile;
import com.amsha.persistence.api.ExternalIdentityDto;
import com.amsha.persistence.api.IdentityInvitationDto;
import com.amsha.persistence.api.IdentityInvitationStorePort;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.IdentityUserDto;
import com.amsha.persistence.api.WebSessionDto;
import com.amsha.persistence.api.WebSessionStorePort;
import com.amsha.persistence.api.WorkspaceMembershipDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.smallrye.common.annotation.RunOnVirtualThread;

@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RunOnVirtualThread
public class AuthResource {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    @ConfigProperty(name = "amsha.auth.session.idle-timeout-minutes", defaultValue = "720")
    int idleTimeoutMinutes;

    private final Cache<String, String> stateCache =
            Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build();

    private final Cache<String, String> invitationCache =
            Caffeine.newBuilder().expireAfterWrite(10, TimeUnit.MINUTES).build();

    private final Cache<String, String> pkceCache =
            Caffeine.newBuilder().expireAfterWrite(10, TimeUnit.MINUTES).build();

    private final Cache<String, String> nonceCache =
            Caffeine.newBuilder().expireAfterWrite(10, TimeUnit.MINUTES).build();

    @Inject
    Instance<ExternalIdentityProvider> providers;

    @Inject
    IdentityStorePort identityStore;

    @Inject
    WebSessionStorePort webSessionStore;

    @Inject
    IdentityInvitationStorePort invitationStore;

    @Inject
    ApiKeyRegistry apiKeyRegistry;

    @Inject
    AuditService auditService;

    @Context
    UriInfo uriInfo;

    @Context
    SecurityContext securityContext;

    @Context
    HttpHeaders httpHeaders;

    @GET
    @Path("/providers")
    public Response listProviders() {
        List<String> activeList = providers.stream()
                .filter(ExternalIdentityProvider::isEnabled)
                .map(ExternalIdentityProvider::getProviderId)
                .collect(Collectors.toList());
        return Response.ok(activeList).build();
    }

    @GET
    @Path("/login/{providerId}")
    public Response redirectLogin(
            @PathParam("providerId") String providerId, @QueryParam("invitationToken") String invitationToken) {

        Optional<ExternalIdentityProvider> providerOpt = providers.stream()
                .filter(p -> p.getProviderId().equalsIgnoreCase(providerId))
                .findFirst();

        if (providerOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse("PROVIDER_NOT_FOUND", "Unknown provider: " + providerId))
                    .build();
        }

        if (!providerOpt.get().isEnabled()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse("PROVIDER_DISABLED", "Provider is disabled: " + providerId))
                    .build();
        }

        String state = UUID.randomUUID().toString();
        stateCache.put(state, providerId);

        // PKCE + nonce (consumed by OIDC; providers without support ignore them)
        String codeVerifier = randomUrlSafe(32);
        String codeChallenge = s256(codeVerifier);
        String nonce = randomUrlSafe(32);
        pkceCache.put(state, codeVerifier);
        nonceCache.put(state, nonce);

        if (invitationToken != null && !invitationToken.isBlank()) {
            invitationCache.put(state, invitationToken);
        }

        String redirectUri =
                uriInfo.getBaseUri().resolve("auth/callback/" + providerId).toString();
        String url = providerOpt.get().getAuthorizationUrl(state, redirectUri, codeChallenge, nonce);

        return Response.seeOther(URI.create(url)).build();
    }

    @GET
    @Path("/callback/{providerId}")
    public Response callback(
            @PathParam("providerId") String providerId,
            @QueryParam("code") String code,
            @QueryParam("state") String state) {

        Cookie incomingSessionCookie = httpHeaders.getCookies().get("AMSHA_SESSION_ID");
        String activeSessionToken = incomingSessionCookie != null ? incomingSessionCookie.getValue() : null;

        String cachedProvider = stateCache.getIfPresent(state);
        if (cachedProvider == null || !cachedProvider.equalsIgnoreCase(providerId)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("INVALID_STATE", "Anti-CSRF state token validation failed."))
                    .build();
        }
        stateCache.invalidate(state);

        String codeVerifier = pkceCache.getIfPresent(state);
        String nonce = nonceCache.getIfPresent(state);
        pkceCache.invalidate(state);
        nonceCache.invalidate(state);

        Optional<ExternalIdentityProvider> providerOpt = providers.stream()
                .filter(p -> p.getProviderId().equalsIgnoreCase(providerId))
                .findFirst();

        if (providerOpt.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse("PROVIDER_NOT_FOUND", "Provider not found: " + providerId))
                    .build();
        }

        String redirectUri =
                uriInfo.getBaseUri().resolve("auth/callback/" + providerId).toString();
        ExternalUserProfile profile = providerOpt.get().authenticate(code, redirectUri, codeVerifier, nonce);

        Instant now = Instant.now();
        String issuer = providerId;

        String userId = resolveOrRegisterUser(providerId, issuer, profile, activeSessionToken, state, now);

        // Establish secure browser session
        String sessionToken = "sess-" + randomUrlSafe(32);
        String csrfToken = "csrf-" + randomUrlSafe(32);

        String sessionDigest = apiKeyRegistry.computeDigest(sessionToken);
        String csrfDigest = apiKeyRegistry.computeDigest(csrfToken);

        WebSessionDto newSession = new WebSessionDto(
                UUID.randomUUID().toString(),
                userId,
                sessionDigest,
                csrfDigest,
                now.plus(Duration.ofMinutes(
                        Math.max(5, Math.min(idleTimeoutMinutes, 1440)))), // idle (default 12h, bounded 5m-24h)
                now.plus(Duration.ofDays(7)), // 7 days absolute
                null,
                null,
                "{}",
                0,
                now,
                now);
        webSessionStore.save(newSession);

        boolean secureCookie = "https".equalsIgnoreCase(uriInfo.getBaseUri().getScheme());

        NewCookie sessionCookie = new NewCookie.Builder("AMSHA_SESSION_ID")
                .value(sessionToken)
                .path("/")
                .maxAge(3600 * 24 * 7)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite(NewCookie.SameSite.LAX)
                .build();

        NewCookie csrfCookie = new NewCookie.Builder("X-CSRF-Token")
                .value(csrfToken)
                .path("/")
                .maxAge(3600 * 24 * 7)
                .httpOnly(false)
                .secure(secureCookie)
                .sameSite(NewCookie.SameSite.LAX)
                .build();

        return Response.seeOther(URI.create("/"))
                .cookie(sessionCookie, csrfCookie)
                .build();
    }

    @POST
    @Path("/logout")
    public Response logout() {
        boolean secureCookie = "https".equalsIgnoreCase(uriInfo.getBaseUri().getScheme());
        Cookie incomingSessionCookie = httpHeaders.getCookies().get("AMSHA_SESSION_ID");
        String sessionToken = incomingSessionCookie != null ? incomingSessionCookie.getValue() : null;
        if (sessionToken != null && !sessionToken.isBlank()) {
            String sessionDigest = apiKeyRegistry.computeDigest(sessionToken);
            Optional<WebSessionDto> sessionOpt = webSessionStore.findByTokenDigest(sessionDigest);
            if (sessionOpt.isPresent()) {
                WebSessionDto session = sessionOpt.get();
                Instant now = Instant.now();
                WebSessionDto revoked = new WebSessionDto(
                        session.sessionId(),
                        session.userId(),
                        session.tokenDigest(),
                        session.csrfDigest(),
                        session.idleExpiresAt(),
                        session.absoluteExpiresAt(),
                        session.rotatedAt(),
                        now,
                        session.safeClientMetadata(),
                        session.revision(),
                        session.createdAt(),
                        now);
                webSessionStore.save(revoked);
                auditService.recordMandatory(
                        "logout",
                        session.userId(),
                        "system",
                        SecurityEventType.AUTH_REJECTED,
                        "action=logout session=" + session.sessionId());
            }
        }

        NewCookie sessionCookie = new NewCookie.Builder("AMSHA_SESSION_ID")
                .value("")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite(NewCookie.SameSite.LAX)
                .build();

        NewCookie csrfCookie = new NewCookie.Builder("X-CSRF-Token")
                .value("")
                .path("/")
                .maxAge(0)
                .httpOnly(false)
                .secure(secureCookie)
                .sameSite(NewCookie.SameSite.LAX)
                .build();

        return Response.ok(new LogoutResponse(true))
                .cookie(sessionCookie, csrfCookie)
                .build();
    }

    private String randomUrlSafe(int length) {
        byte[] bytes = new byte[length];
        try {
            new SecureRandom().nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static String s256(String verifier) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(md.digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String resolveOrRegisterUser(
            String providerId,
            String issuer,
            ExternalUserProfile profile,
            String activeSessionToken,
            String state,
            Instant now) {
        Optional<ExternalIdentityDto> externalOpt =
                identityStore.findExternalIdentity(providerId, issuer, profile.providerUserId());

        if (externalOpt.isPresent()) {
            return externalOpt.get().userId();
        }

        // Check for Account Linking (already logged in)
        if (activeSessionToken != null && !activeSessionToken.isBlank()) {
            String sessionDigest = apiKeyRegistry.computeDigest(activeSessionToken);
            Optional<WebSessionDto> sessionOpt = webSessionStore.findByTokenDigest(sessionDigest);
            if (sessionOpt.isPresent() && isActive(sessionOpt.get(), now)) {
                String userId = sessionOpt.get().userId();
                // Link new external identity
                String safeClaimsJson = safeClaims(profile);
                identityStore.saveExternalIdentity(new ExternalIdentityDto(
                        providerId, issuer, profile.providerUserId(), userId, safeClaimsJson, now, now));
                if (identityStore
                        .findExternalIdentity(providerId, issuer, profile.providerUserId())
                        .map(ExternalIdentityDto::userId)
                        .filter(userId::equals)
                        .isEmpty()) {
                    throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                            .entity(new ErrorResponse("IDENTITY_CONFLICT", "External identity is already linked."))
                            .build());
                }
                auditService.recordMandatory(
                        "account-link",
                        userId,
                        "system",
                        SecurityEventType.SECURITY,
                        "action=link provider=" + providerId + " sub=" + profile.providerUserId());
                return userId;
            } else {
                throw new WebApplicationException(Response.status(Response.Status.FORBIDDEN)
                        .entity(new ErrorResponse("LINK_FAILED", "Invalid session context during linking."))
                        .build());
            }
        }

        // Check if registration invitation is present
        String invitationToken = invitationCache.getIfPresent(state);
        if (invitationToken != null) {
            invitationCache.invalidate(state);
            String tokenDigest = apiKeyRegistry.computeDigest(invitationToken);
            Optional<IdentityInvitationDto> invitationOpt = invitationStore.findByTokenDigest(tokenDigest);

            if (invitationOpt.isPresent()) {
                IdentityInvitationDto invitation = invitationOpt.get();
                if (invitation.acceptedBy() != null) {
                    throw new WebApplicationException(Response.status(Response.Status.GONE)
                            .entity(new ErrorResponse("INVITATION_ACCEPTED", "Invitation has already been used."))
                            .build());
                }
                if (invitation.revokedAt() == null && !invitation.expiresAt().isBefore(now)) {
                    // Enforce invitation email constraint
                    if (isEmailConstraintViolated(invitation, profile)) {
                        throw new WebApplicationException(Response.status(Response.Status.FORBIDDEN)
                                .entity(new ErrorResponse(
                                        "EMAIL_CONSTRAINT_MISMATCH",
                                        "This invitation is restricted to a different email address."))
                                .build());
                    }
                    // Register new user
                    String userId = "usr_"
                            + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

                    IdentityInvitationDto acceptedInv = new IdentityInvitationDto(
                            invitation.invitationId(),
                            invitation.workspaceId(),
                            invitation.role(),
                            invitation.tokenDigest(),
                            invitation.optionalEmailConstraint(),
                            invitation.expiresAt(),
                            userId,
                            invitation.revokedAt(),
                            invitation.revision(),
                            invitation.createdAt(),
                            now);
                    if (!invitationStore.save(acceptedInv)) {
                        throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                                .entity(new ErrorResponse(
                                        "INVITATION_CONFLICT", "Invitation was accepted by another request."))
                                .build());
                    }

                    identityStore.saveUser(new IdentityUserDto(
                            userId,
                            profile.displayName() != null ? profile.displayName() : profile.email(),
                            "ACTIVE",
                            now,
                            now));
                    String safeClaimsJson = safeClaims(profile);
                    identityStore.saveExternalIdentity(new ExternalIdentityDto(
                            providerId, issuer, profile.providerUserId(), userId, safeClaimsJson, now, now));
                    if (identityStore
                            .findExternalIdentity(providerId, issuer, profile.providerUserId())
                            .map(ExternalIdentityDto::userId)
                            .filter(userId::equals)
                            .isEmpty()) {
                        throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                                .entity(new ErrorResponse("IDENTITY_CONFLICT", "External identity is already linked."))
                                .build());
                    }

                    // Save default workspace membership
                    WorkspaceMembershipDto membership = new WorkspaceMembershipDto(
                            invitation.workspaceId(), userId, invitation.role(), "ACTIVE", 0, now, now);
                    if (!identityStore.saveMembership(membership)) {
                        throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                                .entity(new ErrorResponse(
                                        "MEMBERSHIP_CONFLICT", "Workspace membership changed concurrently."))
                                .build());
                    }

                    auditService.recordMandatory(
                            "user-register-invite",
                            userId,
                            "system",
                            SecurityEventType.ONBOARDING_COMPLETED,
                            "action=invite_register workspace=" + invitation.workspaceId() + " role="
                                    + invitation.role());
                    return userId;
                } else {
                    throw new WebApplicationException(Response.status(Response.Status.FORBIDDEN)
                            .entity(new ErrorResponse("INVITATION_EXPIRED", "Invitation is expired or revoked."))
                            .build());
                }
            } else {
                throw new WebApplicationException(Response.status(Response.Status.FORBIDDEN)
                        .entity(new ErrorResponse("INVITATION_INVALID", "Invalid invitation token digest."))
                        .build());
            }
        } else {
            throw new WebApplicationException(Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("UNAUTHORIZED", "Registration requires an active invitation."))
                    .build());
        }
    }

    private static boolean isActive(WebSessionDto session, Instant now) {
        return session.revokedAt() == null
                && !session.idleExpiresAt().isBefore(now)
                && !session.absoluteExpiresAt().isBefore(now);
    }

    private static boolean isEmailConstraintViolated(IdentityInvitationDto invitation, ExternalUserProfile profile) {
        if (invitation.optionalEmailConstraint() == null
                || invitation.optionalEmailConstraint().isBlank()) {
            return false;
        }
        return !profile.emailVerified()
                || profile.email() == null
                || !invitation
                        .optionalEmailConstraint()
                        .equalsIgnoreCase(profile.email().trim());
    }

    private static String safeClaims(ExternalUserProfile profile) {
        if (profile.email() == null) {
            return "{}";
        }
        try {
            return JSON.writeValueAsString(Map.of("email", profile.email(), "email_verified", profile.emailVerified()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize safe identity claims", e);
        }
    }

    public record ErrorResponse(String code, String message) {}

    public record LogoutResponse(boolean success) {}
}

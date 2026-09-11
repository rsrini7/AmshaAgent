package com.amsha.llm.providers.oauth;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.quarkus.arc.Unremovable;

/**
 * Stores pending OAuth PKCE authorizations bound to provider, actor, and session.
 * The code verifier is never persisted here: {@link #beginAuthorization} returns it to the caller
 * and retains only the challenge keyed by state. {@link #validateForExchange} recomputes the
 * S256 challenge from the presented verifier and compares it against the stored challenge.
 * Entries expire after ten minutes and are consumed by a successful token exchange.
 */
@ApplicationScoped
@Unremovable
public class ProviderOAuthPkceStore {

    static final Duration AUTHORIZATION_TTL = Duration.ofMinutes(10);
    static final int MAX_ENTRIES = 10_000;

    private final Map<String, PendingAuthorization> pending;
    private final Set<String> inFlight;
    private final Object mutationLock = new Object();

    public ProviderOAuthPkceStore() {
        this.pending = new ConcurrentHashMap<>();
        this.inFlight = ConcurrentHashMap.newKeySet();
    }

    ProviderOAuthPkceStore(Map<String, PendingAuthorization> pending) {
        this.pending = pending;
        this.inFlight = ConcurrentHashMap.newKeySet();
    }

    record PendingAuthorization(
            String providerId, String actorUserId, String sessionId, String codeChallenge, Instant expiresAt) {}

    /**
     * Begins a PKCE authorization flow, storing the challenge bound to provider/actor/session.
     */
    public ProviderOAuthExchangeService.PkceParameters beginAuthorization(
            String providerId, String actorUserId, String sessionId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("Provider ID is required");
        }
        if (actorUserId == null || actorUserId.isBlank()) {
            throw new IllegalArgumentException("Actor user ID is required");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Session ID is required");
        }
        ProviderOAuthExchangeService.PkceParameters params = generatePkceParams();
        synchronized (mutationLock) {
            purgeExpired();
            if (pending.size() >= MAX_ENTRIES) {
                throw new IllegalStateException("PKCE authorization store capacity reached");
            }
            pending.put(
                    params.state(),
                    new PendingAuthorization(
                            normalize(providerId),
                            actorUserId,
                            sessionId,
                            params.codeChallenge(),
                            Instant.now().plus(AUTHORIZATION_TTL)));
        }
        return params;
    }

    /**
     * Validates an OAuth callback state against a pending authorization without consuming it.
     */
    public boolean validateCallbackState(String providerId, String state, String actorUserId, String sessionId) {
        Optional<PendingAuthorization> found = findValid(providerId, state, actorUserId, sessionId);
        return found.isPresent();
    }

    /**
     * Validates the PKCE binding for an authorization-code token exchange.
     * Returns empty when the exchange may proceed; otherwise the failure reason.
     */
    public Optional<String> validateForExchange(
            String providerId,
            Map<String, String> formParameters,
            String actorUserId,
            String sessionId,
            String codeVerifier,
            String oauthState) {
        if (!isAuthorizationCodeFlow(formParameters)) {
            return Optional.empty();
        }
        if (codeVerifier == null || codeVerifier.isBlank()) {
            return Optional.of("PKCE code_verifier is required for authorization_code exchange");
        }
        if (oauthState == null || oauthState.isBlank()) {
            return Optional.of("PKCE state is required for authorization_code exchange");
        }
        Optional<PendingAuthorization> found = findValid(providerId, oauthState, actorUserId, sessionId);
        if (found.isEmpty()) {
            return Optional.of("PKCE state is unknown, expired, or bound to a different actor/session");
        }
        String expected = computeS256Challenge(codeVerifier);
        String stored = found.get().codeChallenge();
        if (expected == null
                || !MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.US_ASCII), stored.getBytes(StandardCharsets.US_ASCII))) {
            return Optional.of("PKCE code_verifier does not match challenge");
        }
        return Optional.empty();
    }

    /**
     * Validates and claims an authorization-code state for one in-flight exchange. A failed
     * exchange must call {@link #release(String)} so a retry remains possible before expiry.
     */
    public Optional<String> claimForExchange(
            String providerId,
            Map<String, String> formParameters,
            String actorUserId,
            String sessionId,
            String codeVerifier,
            String oauthState) {
        if (!isAuthorizationCodeFlow(formParameters)) {
            return Optional.empty();
        }
        synchronized (mutationLock) {
            Optional<String> validation =
                    validateForExchange(providerId, formParameters, actorUserId, sessionId, codeVerifier, oauthState);
            if (validation.isPresent()) {
                return validation;
            }
            if (!inFlight.add(oauthState)) {
                return Optional.of("PKCE state exchange is already in progress");
            }
            return Optional.empty();
        }
    }

    /** Consumes a pending authorization after a successful exchange. */
    public void consume(String oauthState) {
        if (oauthState != null && !oauthState.isBlank()) {
            synchronized (mutationLock) {
                pending.remove(oauthState);
                inFlight.remove(oauthState);
            }
        }
    }

    /** Releases a claimed state after a failed exchange so a retry remains possible. */
    public void release(String oauthState) {
        if (oauthState != null && !oauthState.isBlank()) {
            synchronized (mutationLock) {
                inFlight.remove(oauthState);
            }
        }
    }

    private Optional<PendingAuthorization> findValid(
            String providerId, String state, String actorUserId, String sessionId) {
        if (providerId == null
                || providerId.isBlank()
                || state == null
                || state.isBlank()
                || actorUserId == null
                || actorUserId.isBlank()
                || sessionId == null
                || sessionId.isBlank()) {
            return Optional.empty();
        }
        PendingAuthorization authorization = pending.get(state);
        if (authorization == null) {
            return Optional.empty();
        }
        if (!authorization.expiresAt().isAfter(Instant.now())) {
            pending.remove(state);
            inFlight.remove(state);
            return Optional.empty();
        }
        if (!authorization.providerId().equals(normalize(providerId))
                || !authorization.actorUserId().equals(actorUserId)
                || !authorization.sessionId().equals(sessionId)) {
            return Optional.empty();
        }
        return Optional.of(authorization);
    }

    static boolean isAuthorizationCodeFlow(Map<String, String> formParameters) {
        if (formParameters == null || formParameters.isEmpty()) {
            return false;
        }
        String grantType = formParameters.get("grant_type");
        if (grantType != null && "authorization_code".equalsIgnoreCase(grantType.trim())) {
            return true;
        }
        return formParameters.containsKey("code");
    }

    static String computeS256Challenge(String verifier) {
        if (verifier == null || verifier.isBlank()) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static ProviderOAuthExchangeService.PkceParameters generatePkceParams() {
        SecureRandom random = new SecureRandom();
        byte[] verifierBytes = new byte[32];
        byte[] stateBytes = new byte[16];
        try {
            random.nextBytes(verifierBytes);
            random.nextBytes(stateBytes);
            String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
            String state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes);
            String challenge = computeS256Challenge(verifier);
            return new ProviderOAuthExchangeService.PkceParameters(verifier, challenge, state);
        } finally {
            Arrays.fill(verifierBytes, (byte) 0);
            Arrays.fill(stateBytes, (byte) 0);
        }
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        pending.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().expiresAt().isBefore(now);
            if (expired) {
                inFlight.remove(entry.getKey());
            }
            return expired;
        });
    }

    private static String normalize(String providerId) {
        return providerId.trim().toLowerCase(Locale.ROOT);
    }
}

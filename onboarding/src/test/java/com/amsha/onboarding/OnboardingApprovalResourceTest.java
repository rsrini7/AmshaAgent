package com.amsha.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OnboardingApprovalResourceTest {

    private OnboardingApprovalResource resource;

    @BeforeEach
    void setUp() {
        resource = new OnboardingApprovalResource();
        SecurityContext securityContext = mock(SecurityContext.class);
        Principal principal = () -> "owner-1";
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        resource.securityContext = securityContext;
    }

    @Test
    void issueOAuthGrant_missingSession_rejected() {
        Response response = resource.issueOAuthGrant(Map.of("providerId", "codex"));

        assertEquals(400, response.getStatus());
    }

    @Test
    void issueOAuthGrant_blankSession_rejected() {
        Response response = resource.issueOAuthGrant(Map.of("providerId", "codex", "sessionId", "  "));

        assertEquals(400, response.getStatus());
    }

    @Test
    void issueOAuthGrant_missingProvider_rejected() {
        Response response = resource.issueOAuthGrant(Map.of("sessionId", "sess-1"));

        assertEquals(400, response.getStatus());
    }
}

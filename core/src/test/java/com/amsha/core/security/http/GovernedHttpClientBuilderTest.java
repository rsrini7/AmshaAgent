package com.amsha.core.security.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;

import com.amsha.core.security.OutboundHttpPolicyService;

import org.junit.jupiter.api.Test;

/**
 * Guards the redirect invariant of the {@link GovernedHttpClient} test seam.
 *
 * <p>{@code send} observes 3xx responses itself so it can re-run policy evaluation and DNS
 * revalidation per hop. A caller-supplied client that follows redirects would do so internally and
 * skip both checks, so such a client must be rejected rather than accepted silently.
 */
class GovernedHttpClientBuilderTest {

    private final OutboundHttpPolicyService policyService = new OutboundHttpPolicyService();

    @Test
    void rejectsOverrideThatAlwaysFollowsRedirects() {
        HttpClient follows = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> GovernedHttpClient.builder(policyService).httpClient(follows));

        assertTrue(failure.getMessage().contains("must not follow redirects"), failure.getMessage());
    }

    @Test
    void rejectsOverrideThatNormallyFollowsRedirects() {
        HttpClient follows = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        assertThrows(
                IllegalArgumentException.class,
                () -> GovernedHttpClient.builder(policyService).httpClient(follows));
    }

    @Test
    void acceptsOverrideThatNeverFollowsRedirects() {
        HttpClient never = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        assertDoesNotThrow(() -> {
            GovernedHttpClient.builder(policyService).httpClient(never);
        });
    }

    @Test
    void rejectsNullOverride() {
        assertThrows(
                NullPointerException.class,
                () -> GovernedHttpClient.builder(policyService).httpClient(null));
    }

    /**
     * Pins the assumption that existing callers are safe: the only production caller is the remote
     * decision engine's test seam, which passes a plain JDK client.
     */
    @Test
    void jdkDefaultClientNeverFollowsRedirects() {
        assertEquals(HttpClient.Redirect.NEVER, HttpClient.newHttpClient().followRedirects());
    }
}

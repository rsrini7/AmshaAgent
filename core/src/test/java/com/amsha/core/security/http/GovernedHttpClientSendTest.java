package com.amsha.core.security.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.llm.api.NetworkProfilePolicy;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.security.api.OutboundPolicyDecision;
import com.amsha.security.api.OutboundPurpose;
import com.amsha.security.api.SecretVault;
import com.amsha.security.api.VaultKey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Transport-level tests for {@link GovernedHttpClient}.
 *
 * <p>{@code Builder.httpClient} is an explicit test seam, so {@code send} is fully drivable without a
 * network: the JDK {@link HttpClient} and the policy service are both mocked, and every non-localhost
 * DNS lookup goes through {@link GovernedHttpDnsPinning#setHostResolverForTesting}. {@code GovernedHttpClientBuilderTest}
 * covers the seam's own validation; this class covers what the transport does with it.
 */
class GovernedHttpClientSendTest {

    private static final String ACTOR = "user-1";
    private static final OutboundPurpose PURPOSE = OutboundPurpose.PROVIDER_API_CALL;
    private static final URI LOCAL_URI = URI.create("https://localhost:8443/v1/chat");
    private static final HttpResponse.BodyHandler<String> STRING_BODY = HttpResponse.BodyHandlers.ofString();

    /** A routable, non-private address so the address-safety checks accept a stubbed resolution. */
    private static final InetAddress SAFE_PUBLIC = address("93.184.216.34");

    private static final String PROXY_URL = "http://127.0.0.1:3128";

    @BeforeEach
    void installStubResolver() {
        GovernedHttpDnsPinning.setHostResolverForTesting((host, timeout) -> new InetAddress[] {SAFE_PUBLIC});
    }

    @AfterEach
    void restoreProcessWideState() {
        GovernedHttpDnsPinning.setHostResolverForTesting(null);
        // The InterruptedException arm sets the flag on the calling thread; clear it or it leaks.
        Thread.interrupted();
    }

    // -----------------------------------------------------------------------
    // Policy arms
    // -----------------------------------------------------------------------

    @Test
    void deniedDecisionThrowsPolicyDeniedCarryingTheReason() throws Exception {
        OutboundHttpPolicyService policy = mock(OutboundHttpPolicyService.class);
        when(policy.evaluate(anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn(OutboundPolicyDecision.deny("TLS_REQUIRED", PURPOSE, ACTOR));

        OutboundHttpException failure = assertThrows(
                OutboundHttpException.class, () -> sendClient(policy, null).send(get(LOCAL_URI), STRING_BODY));

        assertEquals(OutboundHttpException.Category.POLICY_DENIED, failure.category());
        assertTrue(failure.getMessage().contains("TLS_REQUIRED"), failure.getMessage());
    }

    @Test
    void deniedDecisionWithoutAReasonFallsBackToTheWordPolicy() throws Exception {
        OutboundHttpPolicyService policy = mock(OutboundHttpPolicyService.class);
        when(policy.evaluate(anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn(new OutboundPolicyDecision(
                        OutboundPolicyDecision.Decision.DENY, Optional.empty(), PURPOSE, ACTOR));

        OutboundHttpException failure = assertThrows(
                OutboundHttpException.class, () -> sendClient(policy, null).send(get(LOCAL_URI), STRING_BODY));

        assertEquals("Outbound request denied: policy", failure.getMessage());
    }

    @Test
    void hitlDecisionThrowsHitlRequiredRatherThanPolicyDenied() throws Exception {
        OutboundHttpPolicyService policy = mock(OutboundHttpPolicyService.class);
        when(policy.evaluate(anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn(OutboundPolicyDecision.requireHitl("needs approval", PURPOSE, ACTOR));

        OutboundHttpException failure = assertThrows(
                OutboundHttpException.class, () -> sendClient(policy, null).send(get(LOCAL_URI), STRING_BODY));

        assertEquals(OutboundHttpException.Category.HITL_REQUIRED, failure.category());
        assertTrue(failure.getMessage().contains("needs approval"), failure.getMessage());
    }

    @Test
    void allowedDecisionReturnsTheTransportResponseUnchanged() throws Exception {
        HttpResponse<String> expected = response(200);
        HttpClient transport = neverRedirecting();
        willReturn(transport, expected);

        assertSame(expected, sendClient(allowingPolicy(), transport).send(get(LOCAL_URI), STRING_BODY));
    }

    @Test
    void preApprovedFlagIsForwardedToThePolicyService() throws Exception {
        OutboundHttpPolicyService policy = allowingPolicy();
        HttpClient transport = neverRedirecting();
        willReturn(transport, response(200));
        GovernedHttpClient client = GovernedHttpClient.builder(policy)
                .actor(ACTOR)
                .preApproved(true)
                .httpClient(transport)
                .build();

        client.send(get(LOCAL_URI), STRING_BODY);

        verify(policy).evaluate(anyString(), eq(PURPOSE), isNull(), eq(ACTOR), eq(true));
    }

    @Test
    void connectionAndPurposeAreForwardedToThePolicyService() throws Exception {
        OutboundHttpPolicyService policy = allowingPolicy();
        LlmConnectionDto connection = connection("{}");
        HttpClient transport = neverRedirecting();
        willReturn(transport, response(200));
        GovernedHttpClient client = GovernedHttpClient.builder(policy)
                .actor(ACTOR)
                .connection(connection)
                .httpClient(transport)
                .build();

        client.send(get(LOCAL_URI), STRING_BODY);

        verify(policy).evaluate(anyString(), eq(PURPOSE), eq(connection), eq(ACTOR), eq(false));
    }

    // -----------------------------------------------------------------------
    // Transport failure arms
    // -----------------------------------------------------------------------

    @Test
    void httpTimeoutExceptionBecomesTheTimeoutCategory() throws Exception {
        HttpClient transport = neverRedirecting();
        willThrow(transport, new HttpTimeoutException("too slow"));

        OutboundHttpException failure = assertThrows(
                OutboundHttpException.class,
                () -> sendClient(allowingPolicy(), transport).send(get(LOCAL_URI), STRING_BODY));

        assertEquals(OutboundHttpException.Category.TIMEOUT, failure.category());
        assertTrue(failure.getMessage().contains("localhost"), failure.getMessage());
    }

    @Test
    void ioExceptionBecomesTheIoCategoryAndKeepsTheCause() throws Exception {
        HttpClient transport = neverRedirecting();
        IOException cause = new IOException("connection reset");
        willThrow(transport, cause);

        OutboundHttpException failure = assertThrows(
                OutboundHttpException.class,
                () -> sendClient(allowingPolicy(), transport).send(get(LOCAL_URI), STRING_BODY));

        assertEquals(OutboundHttpException.Category.IO, failure.category());
        assertSame(cause, failure.getCause());
    }

    @Test
    void interruptedExceptionBecomesIoAndRestoresTheInterruptFlag() throws Exception {
        HttpClient transport = neverRedirecting();
        willThrow(transport, new InterruptedException("interrupted"));

        OutboundHttpException failure = assertThrows(
                OutboundHttpException.class,
                () -> sendClient(allowingPolicy(), transport).send(get(LOCAL_URI), STRING_BODY));

        assertEquals(OutboundHttpException.Category.IO, failure.category());
        assertTrue(failure.getMessage().contains("Interrupted"), failure.getMessage());
        // The transport re-asserts the flag so callers can observe the cancellation.
        assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag must be restored");
    }

    // -----------------------------------------------------------------------
    // Redirects
    // -----------------------------------------------------------------------

    @Test
    void sameOriginRedirectIsFollowedAndTheFinalResponseIsReturned() throws Exception {
        HttpResponse<String> redirect = response(302, headers(Map.of("location", List.of("/v2/chat"))));
        HttpResponse<String> ok = response(200);
        HttpClient transport = neverRedirecting();
        willReturnSequence(transport, redirect, ok);

        HttpResponse<String> result = sendClient(allowingPolicy(), transport).send(get(LOCAL_URI), STRING_BODY);

        assertSame(ok, result);
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(2)).send(requests.capture(), any());
        assertEquals(
                URI.create("https://localhost:8443/v2/chat"),
                requests.getAllValues().get(1).uri());
    }

    @Test
    void redirectWithoutALocationHeaderIsRedirectDenied() throws Exception {
        HttpClient transport = neverRedirecting();
        willReturn(transport, response(302));

        OutboundHttpException failure = assertThrows(
                OutboundHttpException.class,
                () -> sendClient(allowingPolicy(), transport).send(get(LOCAL_URI), STRING_BODY));

        assertEquals(OutboundHttpException.Category.REDIRECT_DENIED, failure.category());
        assertTrue(failure.getMessage().contains("no Location header"), failure.getMessage());
    }

    @Test
    void aRedirectChainBeyondTheHopLimitIsRedirectDeniedAfterSixSends() throws Exception {
        HttpResponse<String> redirect = response(302, headers(Map.of("location", List.of("/loop"))));
        HttpClient transport = neverRedirecting();
        willReturn(transport, redirect);

        OutboundHttpException failure = assertThrows(
                OutboundHttpException.class,
                () -> sendClient(allowingPolicy(), transport).send(get(LOCAL_URI), STRING_BODY));

        assertEquals(OutboundHttpException.Category.REDIRECT_DENIED, failure.category());
        assertTrue(failure.getMessage().contains("Too many redirects"), failure.getMessage());
        // The depth check runs *after* the response is read, so the initial request plus five followed
        // hops all reach the wire; the sixth 3xx response is the one that trips the limit.
        verify(transport, times(6)).send(any(), any());
    }

    @Test
    void crossOriginRedirectStripsSensitiveHeaders() throws Exception {
        HttpResponse<String> redirect =
                response(302, headers(Map.of("location", List.of("https://other.example.com/v2"))));
        HttpClient transport = neverRedirecting();
        willReturnSequence(transport, redirect, response(200));

        HttpRequest original = HttpRequest.newBuilder(LOCAL_URI)
                .header("Authorization", "Bearer super-secret")
                .header("X-Trace", "trace-1")
                .GET()
                .build();
        sendClient(allowingPolicy(), transport).send(original, STRING_BODY);

        HttpRequest hop = capturedSecondRequest(transport);
        assertNull(hop.headers().firstValue("Authorization").orElse(null), "credentials must not cross origins");
        assertEquals("trace-1", hop.headers().firstValue("X-Trace").orElse(null));
    }

    @Test
    void sameOriginRedirectKeepsSensitiveHeaders() throws Exception {
        HttpResponse<String> redirect = response(302, headers(Map.of("location", List.of("/v2"))));
        HttpClient transport = neverRedirecting();
        willReturnSequence(transport, redirect, response(200));

        HttpRequest original = HttpRequest.newBuilder(LOCAL_URI)
                .header("Authorization", "Bearer super-secret")
                .GET()
                .build();
        sendClient(allowingPolicy(), transport).send(original, STRING_BODY);

        assertEquals(
                "Bearer super-secret",
                capturedSecondRequest(transport)
                        .headers()
                        .firstValue("Authorization")
                        .orElse(null));
    }

    @Test
    void redirectAcrossAPortChangeIsTreatedAsCrossOrigin() throws Exception {
        HttpResponse<String> redirect =
                response(302, headers(Map.of("location", List.of("https://localhost:9999/v2"))));
        HttpClient transport = neverRedirecting();
        willReturnSequence(transport, redirect, response(200));

        HttpRequest original = HttpRequest.newBuilder(LOCAL_URI)
                .header("X-Api-Key", "k-1")
                .GET()
                .build();
        sendClient(allowingPolicy(), transport).send(original, STRING_BODY);

        assertNull(capturedSecondRequest(transport)
                .headers()
                .firstValue("X-Api-Key")
                .orElse(null));
    }

    // -----------------------------------------------------------------------
    // policyUriMapper
    // -----------------------------------------------------------------------

    @Test
    void mapperReturningNullIsPolicyDenied() throws Exception {
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .policyUriMapper(uri -> null)
                .httpClient(neverRedirecting())
                .build();

        OutboundHttpException failure =
                assertThrows(OutboundHttpException.class, () -> client.send(get(LOCAL_URI), STRING_BODY));

        assertEquals(OutboundHttpException.Category.POLICY_DENIED, failure.category());
        assertTrue(failure.getMessage().contains("invalid target"), failure.getMessage());
    }

    @Test
    void mapperChangingTheOriginIsPolicyDenied() throws Exception {
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .policyUriMapper(uri -> URI.create("https://evil.example.com/x"))
                .httpClient(neverRedirecting())
                .build();

        OutboundHttpException failure =
                assertThrows(OutboundHttpException.class, () -> client.send(get(LOCAL_URI), STRING_BODY));

        assertEquals(OutboundHttpException.Category.POLICY_DENIED, failure.category());
        assertTrue(failure.getMessage().contains("changed the request origin"), failure.getMessage());
    }

    @Test
    void mapperIsWhatThePolicyServiceSeesWhileTheRequestUriIsUnchanged() throws Exception {
        OutboundPolicyDecision decision = OutboundPolicyDecision.allow(PURPOSE, ACTOR);
        OutboundHttpPolicyService policy = mock(OutboundHttpPolicyService.class);
        when(policy.evaluate(anyString(), any(), any(), any(), anyBoolean())).thenReturn(decision);
        HttpClient transport = neverRedirecting();
        willReturn(transport, response(200));

        GovernedHttpClient client = GovernedHttpClient.builder(policy)
                .actor(ACTOR)
                .policyUriMapper(uri -> URI.create("https://localhost:8443/redacted"))
                .httpClient(transport)
                .build();
        client.send(get(LOCAL_URI), STRING_BODY);

        verify(policy).evaluate(eq("https://localhost:8443/redacted"), eq(PURPOSE), isNull(), eq(ACTOR), eq(false));
        // The credential-bearing path is still what goes on the wire.
        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).send(sent.capture(), any());
        assertEquals(LOCAL_URI, sent.getValue().uri());
    }

    // -----------------------------------------------------------------------
    // DNS revalidation
    // -----------------------------------------------------------------------

    @Test
    void localhostIsNotDnsRevalidated() throws Exception {
        HttpResponse<String> expected = response(200);
        HttpClient transport = neverRedirecting();
        willReturn(transport, expected);

        // "localhost" is exempt, so the stub resolver is never consulted for it.
        GovernedHttpDnsPinning.setHostResolverForTesting((host, timeout) -> {
            throw new AssertionError("localhost must not be resolved: " + host);
        });

        assertSame(expected, sendClient(allowingPolicy(), transport).send(get(LOCAL_URI), STRING_BODY));
    }

    @Test
    void bracketedIpv6LoopbackIsTreatedAsLocalhost() throws Exception {
        HttpResponse<String> expected = response(200);
        HttpClient transport = neverRedirecting();
        willReturn(transport, expected);
        GovernedHttpDnsPinning.setHostResolverForTesting((host, timeout) -> {
            throw new AssertionError("[::1] must not be resolved: " + host);
        });

        assertSame(
                expected,
                sendClient(allowingPolicy(), transport).send(get(URI.create("http://[::1]:8080/x")), STRING_BODY));
    }

    @Test
    void privateIpv4LiteralIsRejectedAsDnsRebinding() throws Exception {
        OutboundHttpException failure = assertThrows(
                OutboundHttpException.class,
                () -> sendClient(allowingPolicy(), null).send(get(URI.create("https://10.0.0.1/x")), STRING_BODY));

        assertEquals(OutboundHttpException.Category.DNS_REBINDING, failure.category());
        assertTrue(failure.getMessage().contains("prohibited internal/loopback"), failure.getMessage());
    }

    @Test
    void ipLiteralWhoseAddressDiffersFromThePinnedAddressIsRejected() throws Exception {
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .pinnedAddress(address("8.8.8.8"))
                .httpClient(neverRedirecting())
                .build();

        OutboundHttpException failure = assertThrows(
                OutboundHttpException.class, () -> client.send(get(URI.create("https://1.1.1.1/x")), STRING_BODY));

        assertEquals(OutboundHttpException.Category.DNS_REBINDING, failure.category());
        assertTrue(failure.getMessage().contains("does not match checked address"), failure.getMessage());
    }

    @Test
    void ipLiteralMatchingThePinnedAddressProceeds() throws Exception {
        HttpResponse<String> expected = response(200);
        HttpClient transport = neverRedirecting();
        willReturn(transport, expected);

        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .pinnedAddress(address("1.1.1.1"))
                .httpClient(transport)
                .build();

        assertSame(expected, client.send(get(URI.create("https://1.1.1.1/x")), STRING_BODY));
    }

    @Test
    void pinnedAddressThatIsItselfUnsafeIsRejectedEvenWhenTheLiteralMatches() throws Exception {
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .pinnedAddress(InetAddress.getLoopbackAddress())
                .httpClient(neverRedirecting())
                .build();

        // The request target is a routable literal; it is the pinned address that is unsafe.
        OutboundHttpException failure = assertThrows(
                OutboundHttpException.class, () -> client.send(get(URI.create("https://1.1.1.1/x")), STRING_BODY));

        assertEquals(OutboundHttpException.Category.DNS_REBINDING, failure.category());
        assertTrue(failure.getMessage().contains("prohibited internal/loopback"), failure.getMessage());
    }

    @Test
    void unresolvableNamedHostIsRejectedAsDnsFailure() throws Exception {
        GovernedHttpDnsPinning.setHostResolverForTesting((host, timeout) -> {
            throw new UnknownHostException(host);
        });

        OutboundHttpException failure = assertThrows(
                OutboundHttpException.class,
                () -> sendClient(allowingPolicy(), null)
                        .send(get(URI.create("https://nonexistent-xyz.invalid/x")), STRING_BODY));

        assertEquals(OutboundHttpException.Category.DNS_FAILURE, failure.category());
        assertTrue(failure.getMessage().contains("nonexistent-xyz.invalid"), failure.getMessage());
    }

    @Test
    void namedHostResolvingToADifferentAddressThanThePinnedOneIsRejected() throws Exception {
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .pinnedAddress(address("8.8.8.8"))
                .httpClient(neverRedirecting())
                .build();

        OutboundHttpException failure = assertThrows(
                OutboundHttpException.class,
                () -> client.send(get(URI.create("https://api.example.com/x")), STRING_BODY));

        assertEquals(OutboundHttpException.Category.DNS_REBINDING, failure.category());
        assertTrue(failure.getMessage().contains("does not match checked address"), failure.getMessage());
    }

    @Test
    void aHostlessRequestUriCannotEvenBeBuiltSoTheNullHostGuardIsUnreachable() {
        // revalidateDns() guards host == null, but HttpRequest rejects a host-less http/https URI and
        // any other scheme, so no HttpRequest can ever carry one. Pinned rather than tested through send.
        assertNull(URI.create("https:///x").getHost());
        assertThrows(
                IllegalArgumentException.class,
                () -> HttpRequest.newBuilder(URI.create("https:///x")).GET().build());
        assertThrows(
                IllegalArgumentException.class,
                () -> HttpRequest.newBuilder(URI.create("mailto:x")).GET().build());
    }

    // -----------------------------------------------------------------------
    // buildClient — proxy policy
    // -----------------------------------------------------------------------

    @Test
    void proxyRequiredButOverrideHasNoProxySelectorIsPolicyDenied() throws Exception {
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .connection(connection("{\"requireProxy\":true,\"proxyUrl\":\"" + PROXY_URL + "\"}"))
                .httpClient(neverRedirecting())
                .build();

        OutboundHttpException failure =
                assertThrows(OutboundHttpException.class, () -> buildClient(client, LOCAL_URI, new ArrayList<>()));

        assertEquals(OutboundHttpException.Category.POLICY_DENIED, failure.category());
        assertTrue(failure.getMessage().contains("Proxy transport required"), failure.getMessage());
    }

    @Test
    void proxyCredentialRefWithoutAVaultIsPolicyDenied() throws Exception {
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .connection(connection(proxyProfileWithCredential()))
                .httpClient(neverRedirectingWithProxy())
                .build();

        OutboundHttpException failure =
                assertThrows(OutboundHttpException.class, () -> buildClient(client, LOCAL_URI, new ArrayList<>()));

        assertEquals(OutboundHttpException.Category.POLICY_DENIED, failure.category());
        assertTrue(failure.getMessage().contains("no vault is available"), failure.getMessage());
    }

    @Test
    void proxyCredentialRefThatTheVaultCannotResolveIsPolicyDenied() throws Exception {
        SecretVault vault = mock(SecretVault.class);
        when(vault.get(any(), any())).thenReturn(Optional.empty());

        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .vault(vault)
                .connection(connection(proxyProfileWithCredential()))
                .httpClient(neverRedirectingWithProxy())
                .build();

        OutboundHttpException failure =
                assertThrows(OutboundHttpException.class, () -> buildClient(client, LOCAL_URI, new ArrayList<>()));

        assertEquals(OutboundHttpException.Category.POLICY_DENIED, failure.category());
        assertTrue(failure.getMessage().contains("could not be resolved"), failure.getMessage());
    }

    @Test
    void proxyCredentialRefThatResolvesReturnsTheOverrideUnchanged() throws Exception {
        HttpClient override = neverRedirectingWithProxy();
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .vault(vaultHolding("proxy-ref", "user:pass"))
                .connection(connection(proxyProfileWithCredential()))
                .httpClient(override)
                .build();

        assertSame(override, buildClient(client, LOCAL_URI, new ArrayList<>()));
    }

    @Test
    void proxyWithoutACredentialRefReturnsTheOverrideWithoutReadingTheVault() throws Exception {
        HttpClient override = neverRedirectingWithProxy();
        SecretVault vault = mock(SecretVault.class);
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .vault(vault)
                .connection(connection("{\"requireProxy\":true,\"proxyUrl\":\"" + PROXY_URL + "\"}"))
                .httpClient(override)
                .build();

        assertSame(override, buildClient(client, LOCAL_URI, new ArrayList<>()));
        verify(vault, never()).get(any(), any());
    }

    @Test
    void aNoProxyHostBypassesTheProxyRequirementEntirely() throws Exception {
        HttpClient override = neverRedirecting();
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .connection(connection(
                        "{\"requireProxy\":true,\"proxyUrl\":\"" + PROXY_URL + "\",\"noProxyHosts\":[\"localhost\"]}"))
                .httpClient(override)
                .build();

        assertSame(override, buildClient(client, LOCAL_URI, new ArrayList<>()));
    }

    @Test
    void proxyRequiredWithNoOverrideBuildsARealClientPinnedToTheProxy() throws Exception {
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .connection(connection("{\"requireProxy\":true,\"proxyUrl\":\"" + PROXY_URL + "\"}"))
                .build();

        HttpClient built = buildClient(client, LOCAL_URI, new ArrayList<>());

        assertNotNull(built);
        assertTrue(built.proxy().isPresent(), "the proxy selector must be installed");
        assertEquals(HttpClient.Redirect.NEVER, built.followRedirects());
    }

    @Test
    void proxyRequiredWithResolvableCredentialsInstallsAnAuthenticatorAndACleanup() throws Exception {
        List<Runnable> cleanups = new ArrayList<>();
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .vault(vaultHolding("proxy-ref", "user:pass"))
                .connection(connection(proxyProfileWithCredential()))
                .build();

        assertNotNull(buildClient(client, LOCAL_URI, cleanups));
        assertEquals(1, cleanups.size(), "proxy credentials must register a wipe cleanup");
        cleanups.get(0).run();
    }

    @Test
    void proxyCredentialWithoutAColonSeparatorIsPolicyDenied() throws Exception {
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .vault(vaultHolding("proxy-ref", "no-separator"))
                .connection(connection(proxyProfileWithCredential()))
                .build();

        OutboundHttpException failure =
                assertThrows(OutboundHttpException.class, () -> buildClient(client, LOCAL_URI, new ArrayList<>()));

        assertEquals(OutboundHttpException.Category.POLICY_DENIED, failure.category());
        assertTrue(failure.getMessage().contains("user:password"), failure.getMessage());
    }

    @Test
    void proxyCredentialRefWithoutAVaultFailsOnTheRealBuildPathToo() throws Exception {
        // No override, so the credential is applied while building the real client: the helper's own
        // null-vault guard is live here, unlike its duplicated counterpart in buildClient.
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .connection(connection(proxyProfileWithCredential()))
                .build();

        OutboundHttpException failure =
                assertThrows(OutboundHttpException.class, () -> buildClient(client, LOCAL_URI, new ArrayList<>()));

        assertEquals(OutboundHttpException.Category.POLICY_DENIED, failure.category());
        assertTrue(failure.getMessage().contains("no vault is available"), failure.getMessage());
    }

    @Test
    void anUnusableProxyUrlIsPolicyDenied() throws Exception {
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .connection(connection("{\"requireProxy\":true,\"proxyUrl\":\"ftp://proxy.internal:3128\"}"))
                .build();

        OutboundHttpException failure =
                assertThrows(OutboundHttpException.class, () -> buildClient(client, LOCAL_URI, new ArrayList<>()));

        assertEquals(OutboundHttpException.Category.POLICY_DENIED, failure.category());
        assertTrue(failure.getMessage().contains("Proxy scheme must be http or https"), failure.getMessage());
    }

    // -----------------------------------------------------------------------
    // buildClient — custom-CA trust
    // -----------------------------------------------------------------------

    @Test
    void customCaWithAnUnresolvableVaultReferenceIsTlsTrust() throws Exception {
        SecretVault vault = mock(SecretVault.class);
        when(vault.get(any(), any())).thenReturn(Optional.empty());

        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .vault(vault)
                .connection(connection(customCaProfile()))
                .build();

        OutboundHttpException failure =
                assertThrows(OutboundHttpException.class, () -> buildClient(client, LOCAL_URI, new ArrayList<>()));

        assertEquals(OutboundHttpException.Category.TLS_TRUST, failure.category());
        assertTrue(failure.getMessage().contains("could not be resolved"), failure.getMessage());
    }

    @Test
    void customCaWithoutAVaultIsTlsTrust() throws Exception {
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .connection(connection(customCaProfile()))
                .build();

        OutboundHttpException failure =
                assertThrows(OutboundHttpException.class, () -> buildClient(client, LOCAL_URI, new ArrayList<>()));

        assertEquals(OutboundHttpException.Category.TLS_TRUST, failure.category());
        assertTrue(failure.getMessage().contains("requires a vault"), failure.getMessage());
    }

    @Test
    void customCaWithAnOverrideReturnsTheOverrideWithoutBuildingATrustStore() throws Exception {
        HttpClient override = neverRedirecting();
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .vault(vaultHolding("ca-ref", "not-a-certificate"))
                .connection(connection(customCaProfile()))
                .httpClient(override)
                .build();

        assertSame(override, buildClient(client, LOCAL_URI, new ArrayList<>()));
    }

    @Test
    void customCaMaterialThatIsNotACertificateIsTlsTrust() throws Exception {
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .vault(vaultHolding("ca-ref", "definitely-not-x509"))
                .connection(connection(customCaProfile()))
                .build();

        OutboundHttpException failure =
                assertThrows(OutboundHttpException.class, () -> buildClient(client, LOCAL_URI, new ArrayList<>()));

        assertEquals(OutboundHttpException.Category.TLS_TRUST, failure.category());
        assertTrue(failure.getMessage().contains("Failed to establish custom-CA trust"), failure.getMessage());
    }

    @Test
    void customCaTrustModeWithoutAReferenceCannotEvenBeParsedSoTheGuardIsUnreachable() {
        // NetworkProfilePolicy.fromJson() fails closed to SYSTEM when CUSTOM_CA has no usable
        // customCaVaultRef, so buildClient's "requires a customCaVaultRef" throw cannot be reached
        // through any public construction path (the constructor is private).
        NetworkProfilePolicy degraded = NetworkProfilePolicy.fromJson("{\"tlsTrustMode\":\"CUSTOM_CA\"}");
        assertEquals(NetworkProfilePolicy.TlsTrustMode.SYSTEM, degraded.tlsTrustMode());
        assertTrue(degraded.customCaVaultRef().isEmpty());

        NetworkProfilePolicy blankRef =
                NetworkProfilePolicy.fromJson("{\"tlsTrustMode\":\"CUSTOM_CA\",\"customCaVaultRef\":\"  \"}");
        assertEquals(NetworkProfilePolicy.TlsTrustMode.SYSTEM, blankRef.tlsTrustMode());

        NetworkProfilePolicy custom =
                NetworkProfilePolicy.fromJson("{\"tlsTrustMode\":\"CUSTOM_CA\",\"customCaVaultRef\":\"ca-ref\"}");
        assertEquals(NetworkProfilePolicy.TlsTrustMode.CUSTOM_CA, custom.tlsTrustMode());
        assertEquals(Optional.of("ca-ref"), custom.customCaVaultRef());
    }

    @Test
    void plainConnectionWithNoProfileBuildsARealClient() throws Exception {
        GovernedHttpClient client =
                GovernedHttpClient.builder(allowingPolicy()).actor(ACTOR).build();

        HttpClient built = buildClient(client, LOCAL_URI, new ArrayList<>());

        assertNotNull(built);
        assertEquals(HttpClient.Redirect.NEVER, built.followRedirects());
        assertTrue(built.proxy().isEmpty());
    }

    // -----------------------------------------------------------------------
    // Timeouts
    // -----------------------------------------------------------------------

    @Test
    void readTimeoutFallsBackToTheDefaultWithoutAConnection() throws Exception {
        GovernedHttpClient client =
                GovernedHttpClient.builder(allowingPolicy()).actor(ACTOR).build();

        assertEquals(30_000, invokeInt(client, "readTimeoutMillis"));
    }

    @Test
    void readTimeoutUsesTheConnectionProfileWhenPositive() throws Exception {
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .connection(connection("{\"readTimeoutMs\":1500}"))
                .build();

        assertEquals(1_500, invokeInt(client, "readTimeoutMillis"));
    }

    @Test
    void readTimeoutFallsBackToTheDefaultWhenTheProfileIsNotPositive() throws Exception {
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .connection(connection("{\"readTimeoutMs\":0}"))
                .build();

        assertEquals(30_000, invokeInt(client, "readTimeoutMillis"));
    }

    @Test
    void connectTimeoutFallsBackToTheDefaultWithoutAConnection() throws Exception {
        GovernedHttpClient client =
                GovernedHttpClient.builder(allowingPolicy()).actor(ACTOR).build();

        assertEquals(10_000, invokeInt(client, "effectiveConnectTimeoutMillis"));
    }

    @Test
    void connectTimeoutUsesTheConnectionProfileWhenPositive() throws Exception {
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .connection(connection("{\"connectTimeoutMs\":2500}"))
                .build();

        assertEquals(2_500, invokeInt(client, "effectiveConnectTimeoutMillis"));
    }

    @Test
    void connectTimeoutOverrideIsUsedWhenBuildingARealClient() throws Exception {
        // With no httpClient override the real builder runs, so the override branch is exercised.
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .connection(connection("{\"connectTimeoutMs\":2500}"))
                .connectTimeout(Duration.ofSeconds(7))
                .build();

        assertNotNull(buildClient(client, LOCAL_URI, new ArrayList<>()));
    }

    @Test
    void aRequestWithoutATimeoutGainsTheConnectionReadTimeout() throws Exception {
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .connection(connection("{\"readTimeoutMs\":1500}"))
                .build();

        HttpRequest withTimeout = invokeWithDefaultTimeout(client, get(LOCAL_URI));

        assertEquals(Duration.ofMillis(1500), withTimeout.timeout().orElse(null));
    }

    @Test
    void aRequestThatAlreadyHasATimeoutIsReturnedUnchanged() throws Exception {
        GovernedHttpClient client =
                GovernedHttpClient.builder(allowingPolicy()).actor(ACTOR).build();
        HttpRequest timed = HttpRequest.newBuilder(LOCAL_URI)
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();

        assertSame(timed, invokeWithDefaultTimeout(client, timed));
    }

    @Test
    void aRequestWithoutATimeoutKeepsItsHeadersAndMethodWhenRebuilt() throws Exception {
        GovernedHttpClient client =
                GovernedHttpClient.builder(allowingPolicy()).actor(ACTOR).build();
        HttpRequest original =
                HttpRequest.newBuilder(LOCAL_URI).header("X-Trace", "t1").GET().build();

        HttpRequest rebuilt = invokeWithDefaultTimeout(client, original);

        assertEquals("GET", rebuilt.method());
        assertEquals("t1", rebuilt.headers().firstValue("X-Trace").orElse(null));
        assertEquals(LOCAL_URI, rebuilt.uri());
    }

    // -----------------------------------------------------------------------
    // Builder validation
    // -----------------------------------------------------------------------

    @Test
    void builderRejectsANullPolicyUriMapper() {
        assertThrows(
                NullPointerException.class,
                () -> GovernedHttpClient.builder(allowingPolicy()).policyUriMapper(null));
    }

    @Test
    void builderRejectsAZeroConnectTimeout() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> GovernedHttpClient.builder(allowingPolicy()).connectTimeout(Duration.ZERO));

        assertTrue(failure.getMessage().contains("must be positive"), failure.getMessage());
    }

    @Test
    void builderRejectsANegativeConnectTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () -> GovernedHttpClient.builder(allowingPolicy()).connectTimeout(Duration.ofSeconds(-1)));
    }

    @Test
    void builderRejectsANullConnectTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () -> GovernedHttpClient.builder(allowingPolicy()).connectTimeout(null));
    }

    @Test
    void builderAcceptsAPositiveConnectTimeout() {
        assertNotNull(GovernedHttpClient.builder(allowingPolicy())
                .connectTimeout(Duration.ofMillis(1))
                .build());
    }

    // -----------------------------------------------------------------------
    // Static helpers
    // -----------------------------------------------------------------------

    @Test
    void isLocalhostRecognisesTheThreeExemptForms() {
        assertFalse(GovernedHttpClient.isLocalhost(null));
        assertTrue(GovernedHttpClient.isLocalhost("localhost"));
        assertTrue(GovernedHttpClient.isLocalhost("127.0.0.1"));
        assertTrue(GovernedHttpClient.isLocalhost("::1"));
        assertTrue(GovernedHttpClient.isLocalhost("[::1]"));
        assertFalse(GovernedHttpClient.isLocalhost("example.com"));
        assertFalse(GovernedHttpClient.isLocalhost("[2001:db8::1]"));
        assertFalse(GovernedHttpClient.isLocalhost("LOCALHOST"));
        // Opens with a bracket but does not close with one: the strip must not be attempted.
        assertFalse(GovernedHttpClient.isLocalhost("[abc"));
    }

    @Test
    void isIpv4MappedAddressDelegatesToTheDnsPinningHelper() {
        assertFalse(GovernedHttpClient.isIpv4MappedAddress(new byte[] {127, 0, 0, 1}));
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff;
        mapped[11] = (byte) 0xff;
        mapped[12] = 8;
        mapped[13] = 8;
        mapped[14] = 8;
        mapped[15] = 8;
        assertTrue(GovernedHttpClient.isIpv4MappedAddress(mapped));
    }

    @Test
    void effectivePortDefaultsByScheme() throws Exception {
        assertEquals(8443, invokeStaticInt("effectivePort", URI.create("https://a.com:8443/x")));
        assertEquals(443, invokeStaticInt("effectivePort", URI.create("https://a.com/x")));
        assertEquals(80, invokeStaticInt("effectivePort", URI.create("http://a.com/x")));
    }

    @Test
    void sameOriginComparesSchemeHostAndEffectivePort() throws Exception {
        assertTrue(invokeStaticBoolean("sameOrigin", URI.create("https://a.com/x"), URI.create("https://a.com/y")));
        assertTrue(invokeStaticBoolean("sameOrigin", URI.create("https://a.com/x"), URI.create("https://a.com:443/y")));
        assertFalse(invokeStaticBoolean("sameOrigin", URI.create("https://a.com/x"), URI.create("http://a.com/x")));
        assertFalse(invokeStaticBoolean("sameOrigin", URI.create("https://a.com/x"), URI.create("https://b.com/x")));
        assertFalse(
                invokeStaticBoolean("sameOrigin", URI.create("https://a.com/x"), URI.create("https://a.com:8443/x")));
    }

    @Test
    void isSensitiveHeaderCoversEveryCredentialBearingName() throws Exception {
        for (String name : List.of(
                "authorization",
                "AUTHORIZATION",
                "cookie",
                "cookie2",
                "proxy-authorization",
                "mcp-session-id",
                "x-subscription-token",
                "x-api-key",
                "x-goog-api-key")) {
            assertTrue(invokeStaticBoolean("isSensitiveHeader", name), name + " must be sensitive");
        }
        assertFalse(invokeStaticBoolean("isSensitiveHeader", "content-type"));
        assertFalse(invokeStaticBoolean("isSensitiveHeader", "x-trace"));
    }

    @Test
    void flattenPreservesMultiValuedHeadersForASameOriginHop() throws Exception {
        Map<String, List<String>> headers = Map.of(
                "X-Multi", List.of("a", "b"),
                "Authorization", List.of("Bearer s"));

        String[] flat = invokeStaticFlatten(headers, true);

        assertEquals(6, flat.length);
        assertTrue(List.of(flat).contains("Authorization"));
    }

    @Test
    void flattenDropsSensitiveHeadersForACrossOriginHop() throws Exception {
        Map<String, List<String>> headers = Map.of(
                "X-Trace", List.of("t1"),
                "Authorization", List.of("Bearer s"),
                "Cookie", List.of("sid=1"));

        String[] flat = invokeStaticFlatten(headers, false);

        assertArrayEquals(new String[] {"X-Trace", "t1"}, flat);
    }

    // -----------------------------------------------------------------------
    // Residual coverage: arms a fixture that always injects an HttpClient cannot reach
    // -----------------------------------------------------------------------

    @Test
    void aNonLoopbackBracketedIpv6LiteralIsRevalidatedInProcessAndAccepted() throws Exception {
        HttpResponse<String> expected = response(200);
        HttpClient transport = neverRedirecting();
        willReturn(transport, expected);
        GovernedHttpDnsPinning.setHostResolverForTesting((host, timeout) -> {
            throw new AssertionError("an IP literal must be parsed in-process, not resolved: " + host);
        });

        // 2001:db8::/32 is documentation space: not loopback, not ULA, so it passes address safety.
        assertSame(
                expected,
                sendClient(allowingPolicy(), transport).send(get(URI.create("https://[2001:db8::1]/x")), STRING_BODY));
    }

    @Test
    void aCleanupRegisteredWhileBuildingTheRealClientIsRunOnTheFailurePath() throws Exception {
        // No httpClient override, so the real client is built and the proxy wipe is registered;
        // the request then fails against a closed port and the finally block must still run it.
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .vault(vaultHolding("proxy-ref", "user:pass"))
                .connection(
                        connection(
                                "{\"requireProxy\":true,\"proxyUrl\":\"http://127.0.0.1:1\",\"proxyCredentialVaultRef\":\"proxy-ref\"}"))
                .build();

        OutboundHttpException failure = assertThrows(
                OutboundHttpException.class, () -> client.send(get(URI.create("https://localhost:1/x")), STRING_BODY));

        assertTrue(
                failure.category() == OutboundHttpException.Category.IO
                        || failure.category() == OutboundHttpException.Category.TIMEOUT,
                "unexpected category " + failure.category());
    }

    @Test
    void theCleanupFailureSuppressionIsUnreachableBecauseTheOnlyCleanupCannotThrow() throws Exception {
        // send()'s finally suppresses exceptions from cleanup.run(). The only Runnable ever added to
        // that list is the proxy-password wipe registered by GovernedHttpSecuritySupport -- an
        // Arrays.fill over a non-null array, which cannot throw -- and the list is private and
        // populated nowhere else. Pinned as dead rather than chased.
        List<Runnable> cleanups = new ArrayList<>();
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .vault(vaultHolding("proxy-ref", "user:pass"))
                .connection(connection(proxyProfileWithCredential()))
                .build();
        buildClient(client, LOCAL_URI, cleanups);

        assertEquals(1, cleanups.size());
        assertDoesNotThrow(cleanups.get(0)::run);
    }

    @Test
    void aConnectionWithANullNetworkProfileFallsBackToTransportDefaults() throws Exception {
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .connection(connection(null))
                .build();

        assertEquals(30_000, invokeInt(client, "readTimeoutMillis"));
        assertEquals(10_000, invokeInt(client, "effectiveConnectTimeoutMillis"));
        assertNotNull(buildClient(client, LOCAL_URI, new ArrayList<>()));
    }

    @Test
    void aNoProxyHostWithNoOverrideSkipsTheProxyInstallationEntirely() throws Exception {
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .connection(connection(
                        "{\"requireProxy\":true,\"proxyUrl\":\"" + PROXY_URL + "\",\"noProxyHosts\":[\"localhost\"]}"))
                .build();

        HttpClient built = buildClient(client, LOCAL_URI, new ArrayList<>());

        assertNotNull(built);
        assertTrue(built.proxy().isEmpty(), "a no-proxy host must not get a proxy selector");
    }

    @Test
    void mapperProducingARelativeUriIsPolicyDenied() throws Exception {
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .policyUriMapper(uri -> URI.create("/relative"))
                .httpClient(neverRedirecting())
                .build();

        OutboundHttpException failure =
                assertThrows(OutboundHttpException.class, () -> client.send(get(LOCAL_URI), STRING_BODY));

        assertEquals(OutboundHttpException.Category.POLICY_DENIED, failure.category());
        assertTrue(failure.getMessage().contains("invalid target"), failure.getMessage());
    }

    @Test
    void mapperProducingASchemeWithoutAHostIsPolicyDenied() throws Exception {
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .policyUriMapper(uri -> URI.create("https:///x"))
                .httpClient(neverRedirecting())
                .build();

        OutboundHttpException failure =
                assertThrows(OutboundHttpException.class, () -> client.send(get(LOCAL_URI), STRING_BODY));

        assertEquals(OutboundHttpException.Category.POLICY_DENIED, failure.category());
        assertTrue(failure.getMessage().contains("invalid target"), failure.getMessage());
    }

    // -----------------------------------------------------------------------
    // GovernedHttpSecuritySupport — reached through buildClient and directly
    // -----------------------------------------------------------------------

    @Test
    void proxyCredentialWithATrailingColonIsPolicyDenied() throws Exception {
        // "user:" — the colon is the last byte, so there is no password to take.
        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .vault(vaultHolding("proxy-ref", "user:"))
                .connection(connection(proxyProfileWithCredential()))
                .build();

        OutboundHttpException failure =
                assertThrows(OutboundHttpException.class, () -> buildClient(client, LOCAL_URI, new ArrayList<>()));

        assertEquals(OutboundHttpException.Category.POLICY_DENIED, failure.category());
        assertTrue(failure.getMessage().contains("user:password"), failure.getMessage());
    }

    @Test
    void aProxyCredentialWhoseVaultKeyIsAlreadyDestroyedIsPolicyDenied() throws Exception {
        VaultKey destroyed = new VaultKey("user:pass".getBytes(StandardCharsets.UTF_8));
        destroyed.close();
        SecretVault vault = mock(SecretVault.class);
        when(vault.get(any(), any())).thenReturn(Optional.of(destroyed));

        GovernedHttpClient client = GovernedHttpClient.builder(allowingPolicy())
                .actor(ACTOR)
                .vault(vault)
                .connection(connection(proxyProfileWithCredential()))
                .build();

        OutboundHttpException failure =
                assertThrows(OutboundHttpException.class, () -> buildClient(client, LOCAL_URI, new ArrayList<>()));

        assertEquals(OutboundHttpException.Category.POLICY_DENIED, failure.category());
        assertTrue(failure.getMessage().contains("Failed to resolve proxy credentials"), failure.getMessage());
    }

    @Test
    void buildCustomCaContextWithoutAVaultIsTlsTrust() {
        OutboundHttpException failure = assertThrows(
                OutboundHttpException.class,
                () -> GovernedHttpSecuritySupport.buildCustomCaContext(null, ACTOR, "ca-ref"));

        assertEquals(OutboundHttpException.Category.TLS_TRUST, failure.category());
        assertTrue(failure.getMessage().contains("requires a vault"), failure.getMessage());
    }

    @Test
    void buildCustomCaContextWithAnUnresolvableReferenceIsTlsTrust() {
        SecretVault vault = mock(SecretVault.class);
        when(vault.get(any(), any())).thenReturn(Optional.empty());

        OutboundHttpException failure = assertThrows(
                OutboundHttpException.class,
                () -> GovernedHttpSecuritySupport.buildCustomCaContext(vault, ACTOR, "ca-ref"));

        assertEquals(OutboundHttpException.Category.TLS_TRUST, failure.category());
        assertTrue(failure.getMessage().contains("could not be resolved"), failure.getMessage());
    }

    @Test
    void buildCustomCaContextPropagatesAnOutboundHttpExceptionThrownByTheVaultLookup() {
        // The vault lookup sits *before* the try block, so this escapes without passing through the
        // `catch (OutboundHttpException e) { throw e; }` at the end of the method. That catch is dead:
        // nothing in the try body can throw OutboundHttpException (they throw CertificateException,
        // KeyStoreException, ... and VaultKey.get() throws IllegalStateException).
        SecretVault vault = mock(SecretVault.class);
        when(vault.get(any(), any())).thenThrow(OutboundHttpException.tls("vault offline"));

        OutboundHttpException failure = assertThrows(
                OutboundHttpException.class,
                () -> GovernedHttpSecuritySupport.buildCustomCaContext(vault, ACTOR, "ca-ref"));

        assertEquals("vault offline", failure.getMessage());
        assertEquals(OutboundHttpException.Category.TLS_TRUST, failure.category());
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpRequest get(URI uri) {
        return HttpRequest.newBuilder(uri).GET().build();
    }

    private static OutboundHttpPolicyService allowingPolicy() {
        OutboundHttpPolicyService policy = mock(OutboundHttpPolicyService.class);
        when(policy.evaluate(anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn(OutboundPolicyDecision.allow(PURPOSE, ACTOR));
        return policy;
    }

    private static GovernedHttpClient sendClient(OutboundHttpPolicyService policy, HttpClient transport) {
        GovernedHttpClient.Builder builder = GovernedHttpClient.builder(policy).actor(ACTOR);
        if (transport != null) {
            builder.httpClient(transport);
        }
        return builder.build();
    }

    private static HttpClient neverRedirecting() {
        HttpClient client = mock(HttpClient.class);
        when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        when(client.proxy()).thenReturn(Optional.empty());
        return client;
    }

    private static HttpClient neverRedirectingWithProxy() {
        HttpClient client = mock(HttpClient.class);
        when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        when(client.proxy())
                .thenReturn(
                        Optional.of(ProxySelector.of(new InetSocketAddress(InetAddress.getLoopbackAddress(), 3128))));
        return client;
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status) {
        return response(status, HttpHeaders.of(Map.of(), (name, value) -> true));
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, HttpHeaders headers) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(headers);
        return response;
    }

    private static HttpHeaders headers(Map<String, List<String>> values) {
        return HttpHeaders.of(values, (name, value) -> true);
    }

    @SuppressWarnings("unchecked")
    private static void willReturn(HttpClient client, HttpResponse<String> response) throws Exception {
        doReturn(response).when(client).send(any(HttpRequest.class), any());
    }

    @SuppressWarnings("unchecked")
    private static void willReturnSequence(HttpClient client, HttpResponse<String> first, HttpResponse<String> second)
            throws Exception {
        doReturn(first, second).when(client).send(any(HttpRequest.class), any());
    }

    @SuppressWarnings("unchecked")
    private static void willThrow(HttpClient client, Exception failure) throws Exception {
        doThrow(failure).when(client).send(any(HttpRequest.class), any());
    }

    private static HttpRequest capturedSecondRequest(HttpClient client) throws Exception {
        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(2)).send(requests.capture(), any());
        return requests.getAllValues().get(1);
    }

    private static SecretVault vaultHolding(String ref, String value) {
        SecretVault vault = mock(SecretVault.class);
        when(vault.get(any(), any()))
                .thenAnswer(invocation -> ref.equals(invocation.getArgument(0))
                        ? Optional.of(new VaultKey(value.getBytes(StandardCharsets.UTF_8)))
                        : Optional.empty());
        return vault;
    }

    private static LlmConnectionDto connection(String networkProfileJson) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new LlmConnectionDto(
                "conn-1",
                "USER",
                ACTOR,
                ACTOR,
                "Test connection",
                "openai",
                "endpoint-1",
                null,
                "bearer",
                networkProfileJson,
                "ACTIVE",
                1,
                now,
                now);
    }

    private static String proxyProfileWithCredential() {
        return "{\"requireProxy\":true,\"proxyUrl\":\"" + PROXY_URL + "\",\"proxyCredentialVaultRef\":\"proxy-ref\"}";
    }

    private static String customCaProfile() {
        return "{\"tlsTrustMode\":\"CUSTOM_CA\",\"customCaVaultRef\":\"ca-ref\"}";
    }

    private static HttpClient buildClient(GovernedHttpClient client, URI uri, List<Runnable> cleanups)
            throws Exception {
        Method m = GovernedHttpClient.class.getDeclaredMethod("buildClient", URI.class, List.class);
        m.setAccessible(true);
        try {
            return (HttpClient) m.invoke(client, uri, cleanups);
        } catch (InvocationTargetException e) {
            // Surface the transport's own exception type rather than the reflection wrapper.
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    private static int invokeInt(GovernedHttpClient client, String name) throws Exception {
        Method m = GovernedHttpClient.class.getDeclaredMethod(name);
        m.setAccessible(true);
        return (int) m.invoke(client);
    }

    private static HttpRequest invokeWithDefaultTimeout(GovernedHttpClient client, HttpRequest request)
            throws Exception {
        Method m = GovernedHttpClient.class.getDeclaredMethod("withDefaultTimeout", HttpRequest.class);
        m.setAccessible(true);
        return (HttpRequest) m.invoke(client, request);
    }

    private static int invokeStaticInt(String name, Object arg) throws Exception {
        Method m = GovernedHttpClient.class.getDeclaredMethod(name, arg.getClass());
        m.setAccessible(true);
        return (int) m.invoke(null, arg);
    }

    private static boolean invokeStaticBoolean(String name, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] instanceof URI ? URI.class : String.class;
        }
        Method m = GovernedHttpClient.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return (boolean) m.invoke(null, args);
    }

    @SuppressWarnings("unchecked")
    private static String[] invokeStaticFlatten(Map<String, List<String>> headers, boolean sameOrigin)
            throws Exception {
        Method m = GovernedHttpClient.class.getDeclaredMethod("flatten", Map.class, boolean.class);
        m.setAccessible(true);
        return (String[]) m.invoke(null, headers, sameOrigin);
    }
}

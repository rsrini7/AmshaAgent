package com.amsha.security.http;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

import com.amsha.core.security.OutboundHttpPolicyService;
import com.amsha.core.security.http.GovernedHttpClient;
import com.amsha.core.security.http.OutboundHttpException;
import com.amsha.persistence.api.LlmConnectionDto;
import com.amsha.security.api.OutboundPolicyDecision;
import com.amsha.security.api.OutboundPurpose;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GovernedHttpDnsPinning} and DNS-pinned / trusted-proxy transport checks.
 */
class GovernedHttpDnsPinningTest {

    private static final OutboundHttpPolicyService ALWAYS_ALLOW = new OutboundHttpPolicyService() {
        @Override
        public OutboundPolicyDecision evaluate(
                String url,
                OutboundPurpose purpose,
                LlmConnectionDto connection,
                String actorUserId,
                boolean preApproved) {
            return OutboundPolicyDecision.allow(purpose, actorUserId);
        }
    };

    @Test
    void validateAddressSafety_prohibitsLoopbackAndPrivateRanges() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        OutboundHttpException ex1 = assertThrows(
                OutboundHttpException.class, () -> GovernedHttpDnsPinning.validateAddressSafety(loopback, "test"));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, ex1.category());
        assertTrue(ex1.getMessage().contains("internal/loopback"));

        InetAddress siteLocal = InetAddress.getByName("192.168.1.50");
        OutboundHttpException ex2 = assertThrows(
                OutboundHttpException.class, () -> GovernedHttpDnsPinning.validateAddressSafety(siteLocal, "test"));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, ex2.category());

        InetAddress linkLocal = InetAddress.getByName("169.254.10.20");
        OutboundHttpException ex3 = assertThrows(
                OutboundHttpException.class, () -> GovernedHttpDnsPinning.validateAddressSafety(linkLocal, "test"));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, ex3.category());
    }

    @Test
    void validateAddressSafety_prohibitsCloudMetadata() throws Exception {
        InetAddress metadata = InetAddress.getByAddress(new byte[] {(byte) 169, (byte) 254, (byte) 169, (byte) 254});
        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class, () -> GovernedHttpDnsPinning.validateAddressSafety(metadata, "meta"));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, ex.category());
        assertTrue(ex.getMessage().contains("cloud metadata"));
    }

    @Test
    void validateAddressSafety_prohibitsSharedAddressSpace() throws Exception {
        InetAddress shared = InetAddress.getByAddress(new byte[] {100, 64, 1, 1});
        OutboundHttpException ex = assertThrows(
                OutboundHttpException.class, () -> GovernedHttpDnsPinning.validateAddressSafety(shared, "shared"));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, ex.category());
        assertTrue(ex.getMessage().contains("shared address space"));
    }

    @Test
    void validateAddressSafety_prohibitsIpv4MappedAndIpv6Ula() throws Exception {
        byte[] mappedBytes = new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 10, 0, 0, 1};
        assertTrue(GovernedHttpDnsPinning.isIpv4MappedAddress(mappedBytes));
        assertFalse(GovernedHttpDnsPinning.isIpv4MappedAddress(new byte[16]));
        assertFalse(GovernedHttpDnsPinning.isIpv4MappedAddress(new byte[4]));

        byte[] ulaBytes = new byte[] {(byte) 0xfd, 0x12, 0x34, 0x56, 0x78, (byte) 0x9a, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
        InetAddress ula = InetAddress.getByAddress(ulaBytes);
        OutboundHttpException ex2 = assertThrows(
                OutboundHttpException.class, () -> GovernedHttpDnsPinning.validateAddressSafety(ula, "ula"));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, ex2.category());
        assertTrue(ex2.getMessage().contains("IPv6 Unique Local Address"));
    }

    @Test
    void validateAddressSafety_allowsPublicAddress() throws Exception {
        InetAddress publicDns = InetAddress.getByAddress(new byte[] {8, 8, 8, 8});
        assertDoesNotThrow(() -> GovernedHttpDnsPinning.validateAddressSafety(publicDns, "dns.google"));
    }

    @Test
    void validateTrustedProxy_rejectsInvalidOrUnsafeUrls() {
        assertThrows(OutboundHttpException.class, () -> GovernedHttpDnsPinning.validateTrustedProxy(null, 1000));
        assertThrows(OutboundHttpException.class, () -> GovernedHttpDnsPinning.validateTrustedProxy("  ", 1000));

        OutboundHttpException schemeEx = assertThrows(
                OutboundHttpException.class,
                () -> GovernedHttpDnsPinning.validateTrustedProxy("ftp://proxy.corp:8080", 1000));
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, schemeEx.category());
        assertTrue(schemeEx.getMessage().contains("scheme must be http or https"));

        OutboundHttpException hostEx = assertThrows(
                OutboundHttpException.class, () -> GovernedHttpDnsPinning.validateTrustedProxy("http:///path", 1000));
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, hostEx.category());

        OutboundHttpException metaEx = assertThrows(
                OutboundHttpException.class,
                () -> GovernedHttpDnsPinning.validateTrustedProxy("http://169.254.169.254:8080", 1000));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, metaEx.category());

        OutboundHttpException privEx = assertThrows(
                OutboundHttpException.class,
                () -> GovernedHttpDnsPinning.validateTrustedProxy("http://10.0.0.5:8080", 1000));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, privEx.category());
    }

    @Test
    void validateTrustedProxy_acceptsValidLocalhostAndPublicProxies() {
        GovernedHttpDnsPinning.PinnedProxy local =
                GovernedHttpDnsPinning.validateTrustedProxy("http://127.0.0.1:8888", 1000);
        assertEquals("127.0.0.1", local.uri().getHost());
        assertEquals(8888, local.socketAddress().getPort());
        assertEquals("127.0.0.1", local.socketAddress().getAddress().getHostAddress());

        GovernedHttpDnsPinning.PinnedProxy pub =
                GovernedHttpDnsPinning.validateTrustedProxy("http://8.8.8.8:3128", 1000);
        assertEquals("8.8.8.8", pub.uri().getHost());
        assertEquals(3128, pub.socketAddress().getPort());
        assertEquals("8.8.8.8", pub.socketAddress().getAddress().getHostAddress());
    }

    @Test
    void validateHostDns_pinnedAddressSafetyAndMismatch() throws Exception {
        GovernedHttpDnsPinning.setHostResolverForTesting(
                (host, timeout) -> new InetAddress[] {InetAddress.getByName("8.8.8.8")});
        try {
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            OutboundHttpException ex1 = assertThrows(
                    OutboundHttpException.class,
                    () -> GovernedHttpDnsPinning.validateHostDns("8.8.8.8", 2000, loopback));
            assertEquals(OutboundHttpException.Category.DNS_REBINDING, ex1.category());

            InetAddress mismatchIp = InetAddress.getByAddress(new byte[] {1, 2, 3, 4});
            OutboundHttpException ex2 = assertThrows(
                    OutboundHttpException.class,
                    () -> GovernedHttpDnsPinning.validateHostDns("8.8.8.8", 2000, mismatchIp));
            assertEquals(OutboundHttpException.Category.DNS_REBINDING, ex2.category());
            assertTrue(ex2.getMessage().contains("Connected address does not match checked address"));

            InetAddress matchingIp = InetAddress.getByName("8.8.8.8");
            assertDoesNotThrow(() -> GovernedHttpDnsPinning.validateHostDns("8.8.8.8", 2000, matchingIp));
        } finally {
            GovernedHttpDnsPinning.setHostResolverForTesting(null);
        }
    }

    @Test
    void validateTrustedProxy_httpsSchemeDefaultsToPort443() {
        GovernedHttpDnsPinning.PinnedProxy proxy = GovernedHttpDnsPinning.validateTrustedProxy("https://8.8.8.8", 2000);
        assertEquals(443, proxy.socketAddress().getPort());
        assertEquals("8.8.8.8", proxy.socketAddress().getAddress().getHostAddress());
    }

    @Test
    void governedHttpClient_pinnedAddressMismatch_rejectsRequest() throws Exception {
        InetAddress pinned = InetAddress.getByAddress(new byte[] {9, 9, 9, 9});
        GovernedHttpClient client = GovernedHttpClient.builder(ALWAYS_ALLOW)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .pinnedAddress(pinned)
                .actor("u")
                .build();

        HttpRequest req = HttpRequest.newBuilder(URI.create("http://8.8.8.8:8080/test"))
                .GET()
                .build();

        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> client.send(req, HttpResponse.BodyHandlers.ofString()));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, ex.category());
        assertTrue(ex.getMessage().contains("Connected address does not match checked address"));
    }

    @Test
    void governedHttpClient_proxyToCloudMetadata_rejectedAtBuildOrSend() {
        String policyJson = "{\"requireProxy\":true,\"proxyUrl\":\"http://169.254.169.254:8080\"}";
        LlmConnectionDto conn = new LlmConnectionDto(
                "c1",
                "USER",
                "u",
                "u",
                "conn",
                "openai",
                null,
                null,
                "API_KEY",
                policyJson,
                "ACTIVE",
                1,
                Instant.now(),
                Instant.now());

        GovernedHttpClient client = GovernedHttpClient.builder(ALWAYS_ALLOW)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .connection(conn)
                .actor("u")
                .build();

        HttpRequest req = HttpRequest.newBuilder(URI.create("http://example.com/api"))
                .GET()
                .build();

        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> client.send(req, HttpResponse.BodyHandlers.ofString()));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, ex.category());
        assertTrue(ex.getMessage().contains("cloud metadata"));
    }

    @Test
    void isIpLiteral_correctlyIdentifiesIpv4AndIpv6AndRejectsHostnames() {
        assertTrue(GovernedHttpDnsPinning.isIpLiteral("127.0.0.1"));
        assertTrue(GovernedHttpDnsPinning.isIpLiteral("8.8.8.8"));
        assertTrue(GovernedHttpDnsPinning.isIpLiteral("192.168.1.1"));
        assertTrue(GovernedHttpDnsPinning.isIpLiteral("::1"));
        assertTrue(GovernedHttpDnsPinning.isIpLiteral("[::1]"));
        assertTrue(GovernedHttpDnsPinning.isIpLiteral("2001:db8::1"));
        assertTrue(GovernedHttpDnsPinning.isIpLiteral("[2001:db8::1]"));

        // Hex words or partials must not be classified as IP literals
        assertFalse(GovernedHttpDnsPinning.isIpLiteral("cafe"));
        assertFalse(GovernedHttpDnsPinning.isIpLiteral("face"));
        assertFalse(GovernedHttpDnsPinning.isIpLiteral("cafe.com"));
        assertFalse(GovernedHttpDnsPinning.isIpLiteral("example.com"));
        assertFalse(GovernedHttpDnsPinning.isIpLiteral("1.2.3"));
        assertFalse(GovernedHttpDnsPinning.isIpLiteral("1.2.3.4.5"));
        assertFalse(GovernedHttpDnsPinning.isIpLiteral("1.2.3.256"));
        assertFalse(GovernedHttpDnsPinning.isIpLiteral(null));
        assertFalse(GovernedHttpDnsPinning.isIpLiteral(""));
    }

    @Test
    void governedHttpClient_literalIpCloudMetadata_rejectedEvenWithoutPinnedAddress() {
        GovernedHttpClient client = GovernedHttpClient.builder(ALWAYS_ALLOW)
                .purpose(OutboundPurpose.PROVIDER_API_CALL)
                .actor("u")
                .build();

        HttpRequest req = HttpRequest.newBuilder(URI.create("http://169.254.169.254:8080/test"))
                .GET()
                .build();

        OutboundHttpException ex =
                assertThrows(OutboundHttpException.class, () -> client.send(req, HttpResponse.BodyHandlers.ofString()));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, ex.category());
        assertTrue(ex.getMessage().contains("cloud metadata"));
    }
}

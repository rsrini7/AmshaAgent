package com.amsha.core.security.http;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GovernedHttpDnsPinning} — the DNS-rebinding defence that sits behind every governed
 * outbound request.
 *
 * <p>Nothing here touches the network. {@link InetAddress#getAllByName} parses IP literals in-process, so the
 * production resolver can be exercised end-to-end with literals alone; named hosts go through the
 * {@link GovernedHttpDnsPinning#setHostResolverForTesting} seam instead. The seam is a process-wide static, so
 * every test restores it.
 */
class GovernedHttpDnsPinningTest {

    @AfterEach
    void restoreProductionResolver() {
        GovernedHttpDnsPinning.setHostResolverForTesting(null);
    }

    // --- defaultResolveAndValidateHost: the production path, no seam ---

    @Test
    void defaultResolutionRequiresAPositiveTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () -> GovernedHttpDnsPinning.defaultResolveAndValidateHost("127.0.0.1", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> GovernedHttpDnsPinning.defaultResolveAndValidateHost("127.0.0.1", Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> GovernedHttpDnsPinning.defaultResolveAndValidateHost("127.0.0.1", Duration.ofMillis(-1)));
    }

    @Test
    void defaultResolutionReturnsPublicLiteralsAndRejectsProhibitedOnes() throws Exception {
        InetAddress[] publicAddresses =
                GovernedHttpDnsPinning.defaultResolveAndValidateHost("8.8.8.8", Duration.ofSeconds(2));
        assertEquals(1, publicAddresses.length);
        assertEquals("8.8.8.8", publicAddresses[0].getHostAddress());

        // Every input below is an IP literal, so this never leaves the process.
        assertRebinding("127.0.0.1", "internal/loopback");
        assertRebinding("[::1]", "internal/loopback");
        assertRebinding("10.0.0.1", "internal/loopback");
        assertRebinding("169.254.169.254", "cloud metadata");
        assertRebinding("224.0.0.1", "internal/loopback");
        assertRebinding("0.0.0.0", "internal/loopback");
        assertRebinding("100.64.0.1", "shared address space");
        assertRebinding("fc00::1", "IPv6 Unique Local Address");
    }

    private static void assertRebinding(String address, String messageFragment) {
        OutboundHttpException error = assertThrows(
                OutboundHttpException.class,
                () -> GovernedHttpDnsPinning.defaultResolveAndValidateHost(address, Duration.ofSeconds(2)));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, error.category());
        assertTrue(error.getMessage().contains(messageFragment), error.getMessage());
    }

    // --- isIpLiteral ---

    @Test
    void isIpLiteralDistinguishesLiteralsFromHostnames() {
        assertTrue(GovernedHttpDnsPinning.isIpLiteral("127.0.0.1"));
        assertTrue(GovernedHttpDnsPinning.isIpLiteral("255.255.255.255"));
        assertTrue(GovernedHttpDnsPinning.isIpLiteral("::1"));
        assertTrue(GovernedHttpDnsPinning.isIpLiteral("[2001:db8::1]"));

        assertFalse(GovernedHttpDnsPinning.isIpLiteral(null));
        assertFalse(GovernedHttpDnsPinning.isIpLiteral("   "));
        assertFalse(GovernedHttpDnsPinning.isIpLiteral("example.com"));
        assertFalse(GovernedHttpDnsPinning.isIpLiteral("1.2.3")); // three parts
        assertFalse(GovernedHttpDnsPinning.isIpLiteral("1.2.3.4.5")); // five parts
        assertFalse(GovernedHttpDnsPinning.isIpLiteral("1.2.3.256")); // octet out of range
        assertFalse(GovernedHttpDnsPinning.isIpLiteral("1234.1.1.1")); // octet too long
        assertFalse(GovernedHttpDnsPinning.isIpLiteral("1.2.3.")); // empty octet
        assertFalse(GovernedHttpDnsPinning.isIpLiteral("1.2.3.a")); // non-digit
        assertFalse(GovernedHttpDnsPinning.isIpLiteral("not:an:address")); // colon, but unparseable
    }

    // --- validateAddressSafety ---

    @Test
    void validateAddressSafetyCoversEveryProhibitedRange() throws Exception {
        assertUnsafe("169.254.169.254", "cloud metadata");
        assertUnsafe("127.0.0.1", "internal/loopback");
        assertUnsafe("10.1.2.3", "internal/loopback");
        assertUnsafe("192.168.1.50", "internal/loopback");
        assertUnsafe("169.254.10.20", "internal/loopback"); // IPv4 link-local
        assertUnsafe("fe80::1", "internal/loopback"); // IPv6 link-local
        assertUnsafe("224.0.0.1", "internal/loopback"); // multicast
        assertUnsafe("0.0.0.0", "internal/loopback"); // any-local
        assertUnsafe("100.64.0.1", "shared address space");
        assertUnsafe("100.127.255.255", "shared address space"); // upper edge of 100.64.0.0/10
        assertUnsafe("fc00::1", "IPv6 Unique Local Address");
        assertUnsafe("fd12:3456:789a::1", "IPv6 Unique Local Address");

        assertDoesNotThrow(() -> safe("8.8.8.8"));
        // 100.128.0.0 sits just outside 100.64.0.0/10 and must be allowed.
        assertDoesNotThrow(() -> safe("100.128.0.1"));
        assertDoesNotThrow(() -> safe("172.32.0.1")); // just outside 172.16.0.0/12
    }

    @Test
    void ipv4MappedLiteralsCollapseToIpv4SoTheMappedBranchInAddressSafetyIsUnreachable() throws Exception {
        // The JDK normalises IPv4-mapped literals to Inet4Address before we ever see them, so the
        // `bytes.length == 16 && isIpv4MappedAddress(bytes)` guard cannot fire. Pinned by probe, not assumed.
        InetAddress mappedPublic = InetAddress.getByName("::ffff:8.8.8.8");
        assertEquals(4, mappedPublic.getAddress().length);
        assertDoesNotThrow(() -> GovernedHttpDnsPinning.validateAddressSafety(mappedPublic, "mapped-public"));

        // A mapped loopback is caught by the ordinary loopback check, never by the mapped check.
        InetAddress mappedLoopback = InetAddress.getByName("::ffff:127.0.0.1");
        assertEquals(4, mappedLoopback.getAddress().length);
        OutboundHttpException error = assertThrows(
                OutboundHttpException.class,
                () -> GovernedHttpDnsPinning.validateAddressSafety(mappedLoopback, "mapped-loopback"));
        assertTrue(error.getMessage().contains("internal/loopback"));
    }

    @Test
    void isIpv4MappedAddressMatchesOnlyTheSixteenByteFfFfForm() {
        byte[] mapped = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 10, 0, 0, 1};
        assertTrue(GovernedHttpDnsPinning.isIpv4MappedAddress(mapped));

        assertFalse(GovernedHttpDnsPinning.isIpv4MappedAddress(new byte[4])); // wrong length
        assertFalse(GovernedHttpDnsPinning.isIpv4MappedAddress(new byte[16])); // no ff:ff prefix
        byte[] notMapped = mapped.clone();
        notMapped[3] = 1; // non-zero inside the reserved prefix
        assertFalse(GovernedHttpDnsPinning.isIpv4MappedAddress(notMapped));
    }

    private static InetAddress safe(String address) throws UnknownHostException {
        InetAddress resolved = InetAddress.getByName(address);
        GovernedHttpDnsPinning.validateAddressSafety(resolved, address);
        return resolved;
    }

    private static void assertUnsafe(String address, String messageFragment) throws UnknownHostException {
        InetAddress resolved = InetAddress.getByName(address);
        OutboundHttpException error = assertThrows(
                OutboundHttpException.class, () -> GovernedHttpDnsPinning.validateAddressSafety(resolved, address));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, error.category());
        assertTrue(error.getMessage().contains(messageFragment), error.getMessage());
    }

    // --- validateHostDns ---

    @Test
    void validateHostDnsValidatesResolvedAddressesAndThePinnedMatch() throws Exception {
        GovernedHttpDnsPinning.setHostResolverForTesting(
                (host, timeout) -> new InetAddress[] {InetAddress.getByName("8.8.8.8")});

        // No pin: every resolved address is validated and no match check runs.
        assertDoesNotThrow(() -> GovernedHttpDnsPinning.validateHostDns("8.8.8.8", 2000, null));

        // A pin that matches the resolved address passes.
        assertDoesNotThrow(
                () -> GovernedHttpDnsPinning.validateHostDns("8.8.8.8", 2000, InetAddress.getByName("8.8.8.8")));

        // A pin that does not match fails closed.
        OutboundHttpException mismatch = assertThrows(
                OutboundHttpException.class,
                () -> GovernedHttpDnsPinning.validateHostDns("8.8.8.8", 2000, InetAddress.getByName("1.2.3.4")));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, mismatch.category());
        assertTrue(mismatch.getMessage().contains("does not match checked address"));

        // An unsafe pin is rejected before any resolution happens.
        OutboundHttpException unsafePin = assertThrows(
                OutboundHttpException.class,
                () -> GovernedHttpDnsPinning.validateHostDns("8.8.8.8", 2000, InetAddress.getByName("127.0.0.1")));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, unsafePin.category());
        assertTrue(unsafePin.getMessage().contains("internal/loopback"));
    }

    @Test
    void validateHostDnsFailsClosedWhenResolutionThrowsOrReturnsNothing() throws Exception {
        GovernedHttpDnsPinning.setHostResolverForTesting((host, timeout) -> {
            throw new UnknownHostException(host);
        });
        OutboundHttpException threw = assertThrows(
                OutboundHttpException.class, () -> GovernedHttpDnsPinning.validateHostDns("example.test", 2000, null));
        assertEquals(OutboundHttpException.Category.DNS_FAILURE, threw.category());

        // A null result trips the iteration and is reported as a DNS failure rather than an NPE.
        GovernedHttpDnsPinning.setHostResolverForTesting((host, timeout) -> null);
        OutboundHttpException nulled = assertThrows(
                OutboundHttpException.class, () -> GovernedHttpDnsPinning.validateHostDns("example.test", 2000, null));
        assertEquals(OutboundHttpException.Category.DNS_FAILURE, nulled.category());

        // An empty result with a pin leaves the match flag false.
        GovernedHttpDnsPinning.setHostResolverForTesting((host, timeout) -> new InetAddress[0]);
        OutboundHttpException empty = assertThrows(
                OutboundHttpException.class,
                () -> GovernedHttpDnsPinning.validateHostDns("8.8.8.8", 2000, InetAddress.getByName("8.8.8.8")));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, empty.category());
    }

    @Test
    void hostResolverSeamRestoresTheProductionResolverWhenCleared() throws Exception {
        GovernedHttpDnsPinning.setHostResolverForTesting((host, timeout) -> new InetAddress[0]);
        assertThrows(
                OutboundHttpException.class,
                () -> GovernedHttpDnsPinning.validateHostDns("8.8.8.8", 2000, InetAddress.getByName("8.8.8.8")));

        GovernedHttpDnsPinning.setHostResolverForTesting(null);
        // Back on the real resolver, which parses the literal in-process.
        assertDoesNotThrow(() -> GovernedHttpDnsPinning.validateHostDns("8.8.8.8", 2000, null));
    }

    // --- validateTrustedProxy ---

    @Test
    void validateTrustedProxyRejectsMalformedConfigurations() {
        assertPolicy(null, "none is configured");
        assertPolicy("   ", "none is configured");
        assertPolicy("http://[bad", "Invalid proxy URL");
        assertPolicy("socks5://proxy.corp:1080", "scheme must be http or https");
        assertPolicy("http:///path", "no host");
    }

    @Test
    void validateTrustedProxyPinsLocalhostAndLiteralAddresses() throws Exception {
        GovernedHttpDnsPinning.PinnedProxy loopback =
                GovernedHttpDnsPinning.validateTrustedProxy("http://127.0.0.1:8888", 1000);
        assertEquals(8888, loopback.socketAddress().getPort());
        assertEquals("127.0.0.1", loopback.socketAddress().getAddress().getHostAddress());

        // localhost and [::1] resolve through the local resolver, not the process-wide DNS seam.
        assertEquals(
                3128,
                GovernedHttpDnsPinning.validateTrustedProxy("http://localhost:3128", 1000)
                        .socketAddress()
                        .getPort());
        assertEquals(
                9999,
                GovernedHttpDnsPinning.validateTrustedProxy("http://[::1]:9999", 1000)
                        .socketAddress()
                        .getPort());

        // Default ports follow the scheme.
        assertEquals(
                443,
                GovernedHttpDnsPinning.validateTrustedProxy("https://8.8.8.8", 1000)
                        .socketAddress()
                        .getPort());
        assertEquals(
                80,
                GovernedHttpDnsPinning.validateTrustedProxy("http://8.8.8.8", 1000)
                        .socketAddress()
                        .getPort());

        // A prohibited literal is refused with DNS_REBINDING.
        OutboundHttpException privateProxy = assertThrows(
                OutboundHttpException.class,
                () -> GovernedHttpDnsPinning.validateTrustedProxy("http://10.0.0.5:8080", 1000));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, privateProxy.category());
    }

    @Test
    void validateTrustedProxyResolvesNamedHostsThroughTheDnsSeam() throws Exception {
        GovernedHttpDnsPinning.setHostResolverForTesting(
                (host, timeout) -> new InetAddress[] {InetAddress.getByName("8.8.8.8")});

        GovernedHttpDnsPinning.PinnedProxy proxy =
                GovernedHttpDnsPinning.validateTrustedProxy("http://proxy.corp:3128", 2000);
        assertEquals(3128, proxy.socketAddress().getPort());
        assertEquals("8.8.8.8", proxy.socketAddress().getAddress().getHostAddress());
    }

    @Test
    void validateTrustedProxyRejectsANamedHostThatFailsValidation() {
        GovernedHttpDnsPinning.setHostResolverForTesting((host, timeout) -> new InetAddress[0]);
        assertEquals(
                OutboundHttpException.Category.DNS_FAILURE,
                assertThrows(
                                OutboundHttpException.class,
                                () -> GovernedHttpDnsPinning.validateTrustedProxy("http://proxy.corp:3128", 2000))
                        .category());

        GovernedHttpDnsPinning.setHostResolverForTesting(
                (host, timeout) -> new InetAddress[] {InetAddress.getByName("127.0.0.1")});
        assertEquals(
                OutboundHttpException.Category.DNS_REBINDING,
                assertThrows(
                                OutboundHttpException.class,
                                () -> GovernedHttpDnsPinning.validateTrustedProxy("http://proxy.corp:3128", 2000))
                        .category());

        GovernedHttpDnsPinning.setHostResolverForTesting((host, timeout) -> {
            throw new UnknownHostException(host);
        });
        assertEquals(
                OutboundHttpException.Category.DNS_FAILURE,
                assertThrows(
                                OutboundHttpException.class,
                                () -> GovernedHttpDnsPinning.validateTrustedProxy("http://proxy.corp:3128", 2000))
                        .category());
    }

    @Test
    void leadingZeroOctetsParseAsIpv4SoTheInvalidProxyIpArmIsUnreachable() throws Exception {
        // `isIpLiteral` accepts 4 numeric octets in 0..255, and every such literal is one the JDK parses —
        // "010.1.1.1" included. So the `policy("Invalid proxy IP address")` arm cannot fire; the value is
        // rejected later, by validateAddressSafety, as a private range. Pinned by probe, not assumed.
        assertTrue(GovernedHttpDnsPinning.isIpLiteral("010.1.1.1"));
        assertEquals("10.1.1.1", InetAddress.getByName("010.1.1.1").getHostAddress());

        OutboundHttpException error = assertThrows(
                OutboundHttpException.class,
                () -> GovernedHttpDnsPinning.validateTrustedProxy("http://010.1.1.1:8080", 1000));
        assertEquals(OutboundHttpException.Category.DNS_REBINDING, error.category());
    }

    @Test
    void resolveAndValidateHostDelegatesToTheInstalledResolver() throws Exception {
        GovernedHttpDnsPinning.setHostResolverForTesting(
                (host, timeout) -> new InetAddress[] {InetAddress.getByName("9.9.9.9")});

        InetAddress[] resolved = GovernedHttpDnsPinning.resolveAndValidateHost("example.test", Duration.ofSeconds(1));
        assertEquals(1, resolved.length);
        assertEquals("9.9.9.9", resolved[0].getHostAddress());
    }

    @Test
    void defaultResolutionFailsClosedWhenTheHostCannotBeResolved() {
        // RFC 2606 reserves `.invalid` so it can never resolve; the JDK reports NXDOMAIN in milliseconds.
        // A black-hole resolver would surface TIMEOUT instead, so both categories are accepted — the point
        // is that resolution failure never escapes as anything but an OutboundHttpException.
        OutboundHttpException unresolvable = assertThrows(
                OutboundHttpException.class,
                () -> GovernedHttpDnsPinning.defaultResolveAndValidateHost(
                        "nonexistent-xyz.invalid", Duration.ofSeconds(5)));
        assertTrue(
                unresolvable.category() == OutboundHttpException.Category.DNS_FAILURE
                        || unresolvable.category() == OutboundHttpException.Category.TIMEOUT,
                unresolvable.category().name());

        // A host that opens a bracket without closing it takes the `endsWith("]") == false` arm of the
        // bracket strip and then fails resolution.
        OutboundHttpException unclosedBracket = assertThrows(
                OutboundHttpException.class,
                () -> GovernedHttpDnsPinning.defaultResolveAndValidateHost("[::1", Duration.ofSeconds(5)));
        assertEquals(OutboundHttpException.Category.DNS_FAILURE, unclosedBracket.category());
    }

    @Test
    void defaultResolutionFailsClosedWhenTheTimeoutDurationOverflows() {
        // A positive duration whose millisecond value overflows passes the positivity guard and then blows up
        // inside the try, which is the only deterministic route into the outer catch.
        OutboundHttpException error = assertThrows(
                OutboundHttpException.class,
                () -> GovernedHttpDnsPinning.defaultResolveAndValidateHost(
                        "8.8.8.8", Duration.ofSeconds(Long.MAX_VALUE)));
        assertEquals(OutboundHttpException.Category.DNS_FAILURE, error.category());
    }

    @Test
    void defaultResolutionFailsClosedWhenTheCallingThreadIsInterrupted() {
        Thread.currentThread().interrupt();
        try {
            OutboundHttpException error = assertThrows(
                    OutboundHttpException.class,
                    () -> GovernedHttpDnsPinning.defaultResolveAndValidateHost(
                            "nonexistent-xyz.invalid", Duration.ofSeconds(5)));
            assertEquals(OutboundHttpException.Category.DNS_FAILURE, error.category());
        } finally {
            // Clear the flag so a pending interrupt cannot leak into another test.
            Thread.interrupted();
        }
    }

    @Test
    void isIpLiteralRejectsUnclosedBracketsAndMappedLiterals() {
        assertFalse(GovernedHttpDnsPinning.isIpLiteral("[::1")); // opens a bracket but never closes it
        // A mapped literal contains dots *and* colons; the JDK collapses it to IPv4, so it is not an IPv6 literal.
        assertFalse(GovernedHttpDnsPinning.isIpLiteral("::ffff:8.8.8.8"));
    }

    @Test
    void validateAddressSafetyAllowsAddressesJustOutsideEachProhibitedRange() throws Exception {
        assertDoesNotThrow(() -> safe("169.1.1.1")); // 169.x, but not 169.254.0.0/16
        assertDoesNotThrow(() -> safe("100.0.0.1")); // 100.x, but below 100.64.0.0/10
        assertDoesNotThrow(() -> safe("100.128.0.1")); // above 100.127.255.255
        assertDoesNotThrow(() -> safe("172.32.0.1")); // just outside 172.16.0.0/12
        assertDoesNotThrow(() -> safe("2001:db8::1")); // public IPv6: reaches the ULA and mapped checks
    }

    @Test
    void validateAddressSafetyTreatsAMetadataAdjacentAddressAsLinkLocal() throws Exception {
        // 169.254.169.1 fails the last metadata octet and is then caught by the link-local check instead.
        assertUnsafe("169.254.169.1", "internal/loopback");
    }

    @Test
    void isIpv4MappedAddressRequiresBothFfOctets() {
        byte[] missingSecondFf = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, 0, 10, 0, 0, 1};
        assertFalse(GovernedHttpDnsPinning.isIpv4MappedAddress(missingSecondFf));
    }

    @Test
    void validateHostDnsStripsBracketsFromAnIpv6Literal() throws Exception {
        GovernedHttpDnsPinning.setHostResolverForTesting(
                (host, timeout) -> new InetAddress[] {InetAddress.getByName("8.8.8.8")});

        // The bracketed form must be normalised before it reaches the resolver.
        assertDoesNotThrow(() -> GovernedHttpDnsPinning.validateHostDns("[::1]", 2000, null));

        // An unclosed bracket is left alone rather than sliced.
        assertDoesNotThrow(() -> GovernedHttpDnsPinning.validateHostDns("[::1", 2000, null));
    }

    @Test
    void validateTrustedProxyAcceptsABracketedIpv6LiteralAndRejectsASchemeLessUrl() throws Exception {
        GovernedHttpDnsPinning.PinnedProxy ipv6 =
                GovernedHttpDnsPinning.validateTrustedProxy("http://[2001:db8::1]:3128", 1000);
        assertEquals(3128, ipv6.socketAddress().getPort());
        // Compare addresses, not text: getHostAddress() renders IPv6 uncompressed.
        assertEquals(InetAddress.getByName("2001:db8::1"), ipv6.socketAddress().getAddress());

        // A scheme-less URL leaves the scheme null rather than merely unsupported.
        assertPolicy("//proxy.corp:8080", "scheme must be http or https");
    }

    @Test
    void validateTrustedProxyRejectsANullResolutionResult() {
        GovernedHttpDnsPinning.setHostResolverForTesting((host, timeout) -> null);
        OutboundHttpException error = assertThrows(
                OutboundHttpException.class,
                () -> GovernedHttpDnsPinning.validateTrustedProxy("http://proxy.corp:3128", 2000));
        assertEquals(OutboundHttpException.Category.DNS_FAILURE, error.category());
    }

    private static void assertPolicy(String proxyUrl, String messageFragment) {
        OutboundHttpException error = assertThrows(
                OutboundHttpException.class, () -> GovernedHttpDnsPinning.validateTrustedProxy(proxyUrl, 1000));
        assertEquals(OutboundHttpException.Category.POLICY_DENIED, error.category());
        assertTrue(error.getMessage().contains(messageFragment), error.getMessage());
    }
}

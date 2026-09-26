package com.amsha.security.http;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;

import com.amsha.core.security.http.OutboundHttpException;

/**
 * Backwards-compatibility wrapper delegating to {@link com.amsha.core.security.http.GovernedHttpDnsPinning}.
 */
final class GovernedHttpDnsPinning {

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolveAndValidateHost(String host, Duration timeout) throws Exception;
    }

    private GovernedHttpDnsPinning() {}

    static void setHostResolverForTesting(HostResolver resolver) {
        if (resolver != null) {
            com.amsha.core.security.http.GovernedHttpDnsPinning.setHostResolverForTesting(
                    (host, timeout) -> resolver.resolveAndValidateHost(host, timeout));
        } else {
            com.amsha.core.security.http.GovernedHttpDnsPinning.setHostResolverForTesting(null);
        }
    }

    static boolean isIpLiteral(String host) {
        return com.amsha.core.security.http.GovernedHttpDnsPinning.isIpLiteral(host);
    }

    static void validateAddressSafety(InetAddress addr, String host) {
        try {
            com.amsha.core.security.http.GovernedHttpDnsPinning.validateAddressSafety(addr, host);
        } catch (OutboundHttpException e) {
            throw new OutboundHttpException(
                    OutboundHttpException.Category.valueOf(e.category().name()), e.getMessage());
        }
    }

    static void validateHostDns(String host, int timeoutMs, InetAddress pinnedAddress) {
        try {
            com.amsha.core.security.http.GovernedHttpDnsPinning.validateHostDns(host, timeoutMs, pinnedAddress);
        } catch (OutboundHttpException e) {
            throw new OutboundHttpException(
                    OutboundHttpException.Category.valueOf(e.category().name()), e.getMessage());
        }
    }

    record PinnedProxy(URI uri, InetSocketAddress socketAddress) {}

    static PinnedProxy validateTrustedProxy(String proxyUrl, int timeoutMs) {
        try {
            var corePinned =
                    com.amsha.core.security.http.GovernedHttpDnsPinning.validateTrustedProxy(proxyUrl, timeoutMs);
            return new PinnedProxy(corePinned.uri(), corePinned.socketAddress());
        } catch (OutboundHttpException e) {
            throw new OutboundHttpException(
                    OutboundHttpException.Category.valueOf(e.category().name()), e.getMessage());
        }
    }

    static boolean isIpv4MappedAddress(byte[] bytes) {
        return com.amsha.core.security.http.GovernedHttpDnsPinning.isIpv4MappedAddress(bytes);
    }
}

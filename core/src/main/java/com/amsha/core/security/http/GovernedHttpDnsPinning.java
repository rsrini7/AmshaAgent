package com.amsha.core.security.http;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.amsha.core.scope.ContextAwareExecutorService;

/**
 * Validates trusted proxy configurations and enforces DNS-pinned / validated-address checks
 * to defeat DNS-rebinding attacks. True socket-level address pinning is enforced on the egress
 * proxy leg via {@link #validateTrustedProxy(String, int)}; sensitive targets requiring strict
 * socket-level DNS rebinding defense must configure {@code requireProxy=true} on their network profile.
 */
public final class GovernedHttpDnsPinning {

    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolveAndValidateHost(String host, Duration timeout) throws Exception;
    }

    private static volatile HostResolver hostResolver = GovernedHttpDnsPinning::defaultResolveAndValidateHost;

    /**
     * Replaces the process-wide DNS resolver for tests; {@code null} restores the production resolver.
     *
     * <p>This is a process-wide test seam rather than a configuration surface: the resolver lives in a static
     * field, so a replacement is visible to every caller in the JVM, including concurrent ones, for as long as
     * it is installed. It is {@code public} only because
     * {@code com.amsha.security.http.GovernedHttpDnsPinning} delegates to it from a different package.
     * Production code must not call it, and every caller must restore the default when finished.
     */
    public static void setHostResolverForTesting(HostResolver resolver) {
        hostResolver = resolver != null ? resolver : GovernedHttpDnsPinning::defaultResolveAndValidateHost;
    }

    private GovernedHttpDnsPinning() {}

    public static InetAddress[] resolveAndValidateHost(String host, Duration timeout) throws Exception {
        return hostResolver.resolveAndValidateHost(host, timeout);
    }

    public static InetAddress[] defaultResolveAndValidateHost(String host, Duration dnsTimeout) {
        String normalized = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;

        if (dnsTimeout == null || dnsTimeout.isZero() || dnsTimeout.isNegative()) {
            throw new IllegalArgumentException("dnsTimeout must be positive");
        }
        try (ContextAwareExecutorService resolverExecutor =
                new ContextAwareExecutorService(Executors.newVirtualThreadPerTaskExecutor())) {
            Future<InetAddress[]> resolution = resolverExecutor.submit(() -> InetAddress.getAllByName(normalized));
            InetAddress[] addresses;
            try {
                long timeoutMillis = Math.max(1L, dnsTimeout.toMillis());
                addresses = resolution.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException _) {
                resolution.cancel(true);
                throw OutboundHttpException.timeout("DNS resolution timed out for destination target");
            } catch (InterruptedException e) {
                resolution.cancel(true);
                Thread.currentThread().interrupt();
                throw OutboundHttpException.dns("DNS resolution interrupted for destination target");
            } catch (ExecutionException _) {
                resolution.cancel(true);
                throw OutboundHttpException.dns("DNS resolution failed for destination target");
            }
            if (addresses == null || addresses.length == 0) {
                throw OutboundHttpException.dns("DNS resolution returned no addresses for " + host);
            }
            for (InetAddress addr : addresses) {
                validateAddressSafety(addr, host);
            }
            return addresses;
        } catch (OutboundHttpException e) {
            throw e;
        } catch (Exception _) {
            throw OutboundHttpException.dns("DNS resolution failed for " + host);
        }
    }

    /**
     * Determines whether the specified host string is an IPv4 or IPv6 address literal without
     * performing network DNS resolution.
     */
    public static boolean isIpLiteral(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalized = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        String[] parts = normalized.split("\\.", -1);
        if (parts.length == 4) {
            boolean allNumeric = true;
            for (String part : parts) {
                if (part.isEmpty() || part.length() > 3) {
                    allNumeric = false;
                    break;
                }
                for (int i = 0; i < part.length(); i++) {
                    if (!Character.isDigit(part.charAt(i))) {
                        allNumeric = false;
                        break;
                    }
                }
                if (!allNumeric) {
                    break;
                }
                int val = Integer.parseInt(part);
                if (val < 0 || val > 255) {
                    allNumeric = false;
                    break;
                }
            }
            if (allNumeric) {
                return true;
            }
        }
        if (normalized.indexOf(':') >= 0) {
            try {
                InetAddress addr = InetAddress.getByName(normalized);
                return addr instanceof Inet6Address;
            } catch (Exception _) {
                return false;
            }
        }
        return false;
    }

    /**
     * Validates that an IP address is not in any prohibited internal, private, loopback,
     * cloud-metadata, shared, or IPv6-mapped/ULA range.
     */
    public static void validateAddressSafety(InetAddress addr, String host) {
        byte[] bytes = addr.getAddress();
        if (bytes.length == 4
                && (bytes[0] & 0xff) == 169
                && (bytes[1] & 0xff) == 254
                && (bytes[2] & 0xff) == 169
                && (bytes[3] & 0xff) == 254) {
            throw OutboundHttpException.dnsRebinding(
                    "Resolved address for " + host + " is the cloud metadata endpoint");
        }
        if (addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()
                || addr.isAnyLocalAddress()) {
            throw OutboundHttpException.dnsRebinding(
                    "Resolved address for " + host + " is a prohibited internal/loopback range");
        }
        if (bytes.length == 4 && (bytes[0] & 0xff) == 100 && ((bytes[1] & 0xff) >= 64 && (bytes[1] & 0xff) <= 127)) {
            throw OutboundHttpException.dnsRebinding(
                    "Resolved address for " + host + " is in shared address space (100.64.0.0/10)");
        }
        if (isIpv4MappedAddress(bytes)) {
            throw OutboundHttpException.dnsRebinding("Resolved address for " + host + " is an IPv4-mapped address");
        }
        if (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc) {
            throw OutboundHttpException.dnsRebinding(
                    "Resolved address for " + host + " is an IPv6 Unique Local Address (fc00::/7)");
        }
    }

    /**
     * Resolves host DNS, validates all returned IP addresses, and if a pinned address is
     * specified, verifies that the connected/resolved address matches the checked address.
     */
    public static void validateHostDns(String host, int timeoutMs, InetAddress pinnedAddress) {
        if (pinnedAddress != null) {
            validateAddressSafety(pinnedAddress, host);
        }
        String normalized = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        try {
            InetAddress[] addresses = hostResolver.resolveAndValidateHost(normalized, Duration.ofMillis(timeoutMs));
            boolean matchedPinned = pinnedAddress == null;
            for (InetAddress addr : addresses) {
                validateAddressSafety(addr, host);
                if (pinnedAddress != null && addr.equals(pinnedAddress)) {
                    matchedPinned = true;
                }
            }
            if (!matchedPinned) {
                throw OutboundHttpException.dnsRebinding(
                        "Connected address does not match checked address: " + pinnedAddress);
            }
        } catch (OutboundHttpException e) {
            throw e;
        } catch (Exception _) {
            throw OutboundHttpException.dns("DNS resolution failed for " + host);
        }
    }

    public record PinnedProxy(URI uri, InetSocketAddress socketAddress) {}

    /**
     * Validates a trusted proxy endpoint: ensures valid scheme, non-empty host, verifies that the
     * proxy is not pointed at cloud metadata or prohibited internal endpoints, and pins the socket
     * address to the validated IP to defeat DNS rebinding on the proxy connection.
     */
    public static PinnedProxy validateTrustedProxy(String proxyUrl, int timeoutMs) {
        if (proxyUrl == null || proxyUrl.isBlank()) {
            throw OutboundHttpException.policy("Connection requires a proxy but none is configured");
        }
        URI proxyUri;
        try {
            proxyUri = URI.create(proxyUrl);
        } catch (Exception _) {
            throw OutboundHttpException.policy("Invalid proxy URL: " + proxyUrl);
        }
        String scheme = proxyUri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw OutboundHttpException.policy("Proxy scheme must be http or https: " + proxyUrl);
        }
        String host = proxyUri.getHost();
        if (host == null || host.isBlank()) {
            throw OutboundHttpException.policy("Proxy URL has no host: " + proxyUrl);
        }
        String normalized = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        int defaultPort = "https".equalsIgnoreCase(scheme) ? 443 : 80;
        int port = proxyUri.getPort() < 0 ? defaultPort : proxyUri.getPort();
        if (GovernedHttpClient.isLocalhost(host)) {
            try {
                InetAddress localAddr = InetAddress.getByName(normalized);
                return new PinnedProxy(proxyUri, new InetSocketAddress(localAddr, port));
            } catch (Exception _) {
                return new PinnedProxy(proxyUri, new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            }
        }
        InetAddress resolved;
        if (isIpLiteral(normalized)) {
            try {
                resolved = InetAddress.getByName(normalized);
                validateAddressSafety(resolved, host);
            } catch (OutboundHttpException e) {
                throw e;
            } catch (Exception _) {
                throw OutboundHttpException.policy("Invalid proxy IP address: " + host);
            }
        } else {
            resolved = resolveAndValidateSingleHost(host, timeoutMs);
        }
        return new PinnedProxy(proxyUri, new InetSocketAddress(resolved, port));
    }

    private static InetAddress resolveAndValidateSingleHost(String host, int timeoutMs) {
        String normalized = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        try {
            InetAddress[] addresses = hostResolver.resolveAndValidateHost(normalized, Duration.ofMillis(timeoutMs));
            if (addresses == null || addresses.length == 0) {
                throw OutboundHttpException.dns("DNS resolution failed for " + host);
            }
            for (InetAddress addr : addresses) {
                validateAddressSafety(addr, host);
            }
            return addresses[0];
        } catch (OutboundHttpException e) {
            throw e;
        } catch (Exception _) {
            throw OutboundHttpException.dns("DNS resolution failed for " + host);
        }
    }

    public static boolean isIpv4MappedAddress(byte[] bytes) {
        if (bytes.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }
}

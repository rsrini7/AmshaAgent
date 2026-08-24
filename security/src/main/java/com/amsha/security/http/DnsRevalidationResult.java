package com.amsha.security.http;

/**
 * Result of a DNS revalidation check performed immediately before a connect.
 *
 * <p>Revalidation must run on the resolved address, not the original hostname, to prevent DNS
 * rebinding: an attacker-controlled DNS response could return a public address for the initial
 * policy check but a private/loopback address when the socket actually connects.</p>
 */
public record DnsRevalidationResult(boolean safe, String reason) {
    public static DnsRevalidationResult ok() {
        return new DnsRevalidationResult(true, null);
    }

    public static DnsRevalidationResult unsafe(String reason) {
        return new DnsRevalidationResult(false, reason);
    }
}

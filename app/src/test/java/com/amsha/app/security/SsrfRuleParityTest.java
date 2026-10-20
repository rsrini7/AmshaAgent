package com.amsha.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;

import com.amsha.core.security.http.GovernedHttpDnsPinning;
import com.amsha.security.media.MediaSecurityValidator;

import org.junit.jupiter.api.Test;

/**
 * Guards the duplicated SSRF/address-safety rule sets against silent drift.
 *
 * <p>{@link GovernedHttpDnsPinning#validateAddressSafety} in {@code core} and
 * {@link MediaSecurityValidator#validateAddress} in {@code security} implement the same prohibitions:
 * loopback, link-local, site-local, multicast, any-local, the cloud metadata endpoint, CGNAT
 * {@code 100.64.0.0/10}, IPv4-mapped addresses, and IPv6 ULA {@code fc00::/7}. The rule set was forked
 * when the governed transport moved into {@code core}, because {@code core} cannot depend on
 * {@code security}.
 *
 * <p>This test does not remove that fork — de-duplicating it is an architectural decision. It makes drift
 * loud: the moment a hardening change lands in one copy only, the two verdicts diverge here instead of
 * reaching production as an SSRF gap in whichever copy was missed. Every case also pins the expected
 * verdict, so two copies agreeing on the <em>wrong</em> answer still fails.
 */
class SsrfRuleParityTest {

    private record Case(InetAddress address, boolean expectedReject) {}

    private static Case reject(String literal) throws Exception {
        return new Case(InetAddress.getByName(literal), true);
    }

    private static Case accept(String literal) throws Exception {
        return new Case(InetAddress.getByName(literal), false);
    }

    private static Map<String, Case> corpus() throws Exception {
        Map<String, Case> cases = new LinkedHashMap<>();
        cases.put("loopback IPv4", reject("127.0.0.1"));
        cases.put("loopback IPv6", reject("::1"));
        cases.put("link-local IPv4", reject("169.254.1.1"));
        cases.put("cloud metadata endpoint", reject("169.254.169.254"));
        cases.put("cloud metadata via IPv4-mapped syntax", reject("::ffff:169.254.169.254"));
        cases.put("link-local IPv6", reject("fe80::1"));
        cases.put("site-local 10/8", reject("10.0.0.1"));
        cases.put("site-local 192.168/16", reject("192.168.1.1"));
        cases.put("site-local 172.16/12", reject("172.16.0.1"));
        cases.put("multicast IPv4", reject("224.0.0.1"));
        cases.put("multicast IPv6", reject("ff02::1"));
        cases.put("any-local IPv4", reject("0.0.0.0"));
        cases.put("any-local IPv6", reject("::"));
        cases.put("CGNAT lower bound", reject("100.64.0.1"));
        cases.put("CGNAT upper bound", reject("100.127.255.255"));
        cases.put("IPv6 ULA fc00::/7", reject("fc00::1"));
        cases.put("IPv6 ULA fd00::/7", reject("fd00::1"));
        cases.put("public IPv4 Google DNS", accept("8.8.8.8"));
        cases.put("public IPv4 Cloudflare DNS", accept("1.1.1.1"));
        cases.put("public IPv6 Cloudflare DNS", accept("2606:4700:4700::1111"));
        return cases;
    }

    @Test
    void bothCopiesAgreeWithEachOtherAndWithTheExpectedVerdict() throws Exception {
        Map<String, Case> cases = corpus();
        assertTrue(cases.size() >= 20, "corpus must stay broad enough to be meaningful");

        for (Map.Entry<String, Case> entry : cases.entrySet()) {
            String description = entry.getKey();
            Case testCase = entry.getValue();

            boolean coreRejects =
                    rejects(() -> GovernedHttpDnsPinning.validateAddressSafety(testCase.address(), "host"));
            boolean securityRejects = rejects(() -> MediaSecurityValidator.validateAddress(testCase.address(), "host"));

            assertEquals(
                    testCase.expectedReject(),
                    coreRejects,
                    "core verdict changed for " + description + " (" + testCase.address() + ")");
            assertEquals(
                    testCase.expectedReject(),
                    securityRejects,
                    "security verdict changed for " + description + " (" + testCase.address() + ")");
            assertEquals(
                    securityRejects,
                    coreRejects,
                    "the two SSRF rule copies have drifted for " + description + " (" + testCase.address() + ")");
        }
    }

    private static boolean rejects(Runnable check) {
        try {
            check.run();
            return false;
        } catch (RuntimeException _) {
            return true;
        }
    }
}
